import { useReducer, useCallback, useEffect, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authApi } from '../api/authApi';
import { clearAccessToken, refreshAccessToken, setAccessToken } from '../api/tokenStore';
import { apiErrorStatus } from '../api/errors';
import { stopLiveSockets } from '../api/liveSockets';
import type { AuthStatus, AuthUser, LoginRequest, RegisterRequest, AuthResponse } from '../types/auth';
import { AuthContext } from './authSession';

type AuthAction =
  | { type: 'LOGIN_SUCCESS'; payload: AuthResponse }
  | { type: 'LOGOUT' };

interface SessionState {
  status: AuthStatus;
  user: AuthUser | null;
}

const initialState: SessionState = {
  status: 'loading',
  user: null,
};

/**
 * The waits before asking the server again when a page load can't reach it or it answers 5xx (D229): a server
 * restarting or a dropped connection isn't a signed-out visitor. A 401 signs out at once.
 */
const SESSION_RETRY_DELAYS_MS = [1000, 2000, 4000, 8000];

// Where tokens lived before D156; removed from any browser that still holds them
const LEGACY_STORAGE_KEYS = ['accessToken', 'refreshToken', 'user'];

function authReducer(state: SessionState, action: AuthAction): SessionState {
  switch (action.type) {
    case 'LOGIN_SUCCESS': {
      const { userId, username, displayName } = action.payload;
      return { status: 'authenticated', user: { userId, username, displayName } };
    }
    case 'LOGOUT':
      return { status: 'anonymous', user: null };
    default:
      return state;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(authReducer, initialState);
  const queryClient = useQueryClient();

  // Nothing is stored in the page, so a load or reload asks the server: the httpOnly refresh
  // cookie, if the browser has one, becomes a new access token (D156)
  useEffect(() => {
    LEGACY_STORAGE_KEYS.forEach(key => localStorage.removeItem(key));

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const attempt = (tries: number) => {
      refreshAccessToken()
        .then(session => {
          if (!cancelled) dispatch({ type: 'LOGIN_SUCCESS', payload: session });
        })
        .catch(err => {
          if (cancelled) return;
          // A server that can't be reached or failed is asked again while the page shows it loading
          const status = apiErrorStatus(err);
          if ((status === undefined || status >= 500) && tries < SESSION_RETRY_DELAYS_MS.length) {
            timer = setTimeout(() => attempt(tries + 1), SESSION_RETRY_DELAYS_MS[tries]);
            return;
          }
          // No cookie, or one the server no longer accepts: the visitor signs in again
          dispatch({ type: 'LOGOUT' });
        });
    };
    attempt(0);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, []);

  // A change still in flight at sign-out can land after logout emptied the query cache, so a new
  // session starts from an empty cache as well
  const login = useCallback(async (data: LoginRequest) => {
    const response = await authApi.login(data);
    setAccessToken(response.data.accessToken);
    queryClient.clear();
    dispatch({ type: 'LOGIN_SUCCESS', payload: response.data });
  }, [queryClient]);

  const register = useCallback(async (data: RegisterRequest) => {
    const response = await authApi.register(data);
    setAccessToken(response.data.accessToken);
    queryClient.clear();
    dispatch({ type: 'LOGIN_SUCCESS', payload: response.data });
  }, [queryClient]);

  // The server revokes the session and clears the cookie. If it cannot be reached, this tab
  // still signs out; the cookie then lasts until it expires. The query cache is emptied too, so
  // the next account to sign in sees none of this one's data (D171). Live encounter connections
  // are closed first, so none reconnects while the session ends (D238)
  const logout = useCallback(async () => {
    await stopLiveSockets();
    try {
      await authApi.logout();
    } catch (err) {
      console.warn('Sign-out request failed', err);
    }
    clearAccessToken();
    queryClient.clear();
    dispatch({ type: 'LOGOUT' });
  }, [queryClient]);

  return (
    <AuthContext.Provider
      value={{ ...state, isAuthenticated: state.status === 'authenticated', login, register, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}
