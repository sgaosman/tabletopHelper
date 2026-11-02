import { createContext, useContext, useReducer, useCallback, useEffect, type ReactNode } from 'react';
import { authApi } from '../api/authApi';
import { clearAccessToken, refreshAccessToken, setAccessToken } from '../api/tokenStore';
import type { AuthState, AuthStatus, AuthUser, LoginRequest, RegisterRequest, AuthResponse } from '../types/auth';

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

interface AuthContextType extends AuthState {
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(authReducer, initialState);

  // Nothing is stored in the page, so a load or reload asks the server: the httpOnly refresh
  // cookie, if the browser has one, becomes a new access token (D156)
  useEffect(() => {
    LEGACY_STORAGE_KEYS.forEach(key => localStorage.removeItem(key));

    let cancelled = false;
    refreshAccessToken()
      .then(session => {
        if (!cancelled) dispatch({ type: 'LOGIN_SUCCESS', payload: session });
      })
      .catch(() => {
        // No cookie, or one the server no longer accepts: the visitor signs in again
        if (!cancelled) dispatch({ type: 'LOGOUT' });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (data: LoginRequest) => {
    const response = await authApi.login(data);
    setAccessToken(response.data.accessToken);
    dispatch({ type: 'LOGIN_SUCCESS', payload: response.data });
  }, []);

  const register = useCallback(async (data: RegisterRequest) => {
    const response = await authApi.register(data);
    setAccessToken(response.data.accessToken);
    dispatch({ type: 'LOGIN_SUCCESS', payload: response.data });
  }, []);

  // The server revokes the session and clears the cookie. If it cannot be reached, this tab
  // still signs out; the cookie then lasts until it expires
  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch (err) {
      console.warn('Sign-out request failed', err);
    }
    clearAccessToken();
    dispatch({ type: 'LOGOUT' });
  }, []);

  return (
    <AuthContext.Provider
      value={{ ...state, isAuthenticated: state.status === 'authenticated', login, register, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
