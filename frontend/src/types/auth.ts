export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  displayName?: string;
}

// The refresh token never appears in a body: it travels in an httpOnly cookie (D156)
export interface AuthResponse {
  userId: string;
  username: string;
  displayName: string;
  accessToken: string;
}

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
