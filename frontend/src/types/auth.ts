import type { Schemas } from '../api/schema';

export type LoginRequest = Schemas['LoginRequest'];

// Without a display name, the server uses the username
export type RegisterRequest = Schemas['RegisterRequest'];

// The refresh token never appears in a body: it travels in an httpOnly cookie (D156)
export type AuthResponse = Schemas['AuthResponse'];

export interface AuthUser {
  userId: string;
  username: string;
  displayName: string;
}

// 'loading' until the refresh cookie has been tried when the app starts
export type AuthStatus = 'loading' | 'authenticated' | 'anonymous';

export interface AuthState {
  status: AuthStatus;
  user: AuthUser | null;
  isAuthenticated: boolean;
}
