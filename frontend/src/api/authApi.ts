import api from './axiosConfig';
import type { AuthResponse, LoginRequest, RegisterRequest } from '../types/auth';

// Refreshing goes through tokenStore, which keeps it to one request at a time (D156)
export const authApi = {
  register(data: RegisterRequest) {
    return api.post<AuthResponse>('/auth/register', data);
  },

  login(data: LoginRequest) {
    return api.post<AuthResponse>('/auth/login', data);
  },

  // The server finds the session from the httpOnly refresh cookie, revokes it and clears the cookie
  logout() {
    return api.post<void>('/auth/logout');
  },
};
