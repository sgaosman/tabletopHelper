import { createContext, useContext, useReducer, useCallback, useEffect, ReactNode } from 'react';
import { authApi } from '../api/authApi';
import type { AuthState, LoginRequest, RegisterRequest, AuthResponse } from '../types/auth';

type AuthAction =
  | { type: 'LOGIN_SUCCESS'; payload: AuthResponse }
  | { type: 'LOGOUT' };

const initialState: AuthState = {
  user: null,
  accessToken: null,
  refreshToken: null,
  isAuthenticated: false,
};

function loadInitialState(): AuthState {
  const accessToken = localStorage.getItem('accessToken');
  const refreshToken = localStorage.getItem('refreshToken');
  const userJson = localStorage.getItem('user');

  if (accessToken && refreshToken && userJson) {
    try {
      const user = JSON.parse(userJson);
      return { user, accessToken, refreshToken, isAuthenticated: true };
    } catch {
      return initialState;
    }
  }
  return initialState;
}

function authReducer(state: AuthState, action: AuthAction): AuthState {
  switch (action.type) {
    case 'LOGIN_SUCCESS': {
      const { userId, username, displayName, accessToken, refreshToken } = action.payload;
      return {
        user: { userId, username, displayName },
        accessToken,
        refreshToken,
        isAuthenticated: true,
      };
    }
    case 'LOGOUT':
      return initialState;
    default:
      return state;
  }
}

interface AuthContextType extends AuthState {
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(authReducer, initialState, loadInitialState);

  useEffect(() => {
    if (state.isAuthenticated && state.user) {
      localStorage.setItem('accessToken', state.accessToken!);
      localStorage.setItem('refreshToken', state.refreshToken!);
      localStorage.setItem('user', JSON.stringify(state.user));
    } else {
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      localStorage.removeItem('user');
    }
  }, [state]);

  const login = useCallback(async (data: LoginRequest) => {
    const response = await authApi.login(data);
    dispatch({ type: 'LOGIN_SUCCESS', payload: response.data });
  }, []);

  const register = useCallback(async (data: RegisterRequest) => {
    const response = await authApi.register(data);
    dispatch({ type: 'LOGIN_SUCCESS', payload: response.data });
  }, []);

  const logout = useCallback(() => {
    dispatch({ type: 'LOGOUT' });
  }, []);

  return (
    <AuthContext.Provider value={{ ...state, login, register, logout }}>
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
