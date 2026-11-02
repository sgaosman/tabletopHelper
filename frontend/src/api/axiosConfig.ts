import axios from 'axios';
import { clearAccessToken, getAccessToken, refreshAccessToken } from './tokenStore';

const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Login, register and refresh report their own failures; refreshing on those would loop (D155)
function isAuthRequest(url: string | undefined) {
  return url?.startsWith('/auth/') ?? false;
}

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    const status = error.response?.status;

    // Only a 401 means the session expired; a 403 is a refusal that a new token would not change (D155)
    const hadToken = originalRequest.headers?.Authorization?.startsWith('Bearer ');
    if (status === 401 && hadToken && !isAuthRequest(originalRequest.url) && !originalRequest._retry) {
      originalRequest._retry = true;

      // 401s that arrive together share one refresh (tokenStore). The page cannot see the httpOnly
      // refresh cookie, so it always asks; the server answers 401 when there is no session (D156).
      try {
        const { accessToken } = await refreshAccessToken();
        originalRequest.headers.Authorization = `Bearer ${accessToken}`;
        return api(originalRequest);
      } catch {
        clearAccessToken();
        window.location.href = '/login';
        return new Promise(() => {});
      }
    }

    return Promise.reject(error);
  }
);

export default api;
