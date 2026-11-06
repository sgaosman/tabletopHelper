import { createContext } from 'react';
import type { AuthState, LoginRequest, RegisterRequest } from '../types/auth';

/** What useAuth() gives: the session's status and user, and the calls that change them. */
export interface AuthContextType extends AuthState {
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
}

/** Filled by AuthProvider (AuthContext.tsx); read it with useAuth() (hooks/useAuth.ts). */
export const AuthContext = createContext<AuthContextType | null>(null);
