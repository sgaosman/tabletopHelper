import api from './axiosConfig';
import type { AuthResponse, LoginRequest, RegisterRequest } from '../types/auth';

export const authApi = {
  register(data: RegisterRequest) {
    return api.post<AuthResponse>('/auth/register', data);
  },

  login(data: LoginRequest) {
    return api.post<AuthResponse>('/auth/login', data);
  },

  refresh(refreshToken: string) {
    return api.post<AuthResponse>('/auth/refresh', { refreshToken });
  },
};
