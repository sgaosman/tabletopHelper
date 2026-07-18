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

export interface AuthResponse {
  userId: string;
  username: string;
  displayName: string;
  accessToken: string;
  refreshToken: string;
}

export interface AuthState {
  user: {
    userId: string;
    username: string;
    displayName: string;
  } | null;
  accessToken: string | null;
  refreshToken: string | null;
  isAuthenticated: boolean;
}
