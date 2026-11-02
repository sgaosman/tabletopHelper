import { type ReactElement } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { AuthProvider } from '../context/AuthContext';
import { clearAccessToken, setAccessToken } from '../api/tokenStore';
import type { AuthResponse } from '../types/auth';
import { server } from './mocks/server';

function AllProviders({ children }: { children: React.ReactNode }) {
  return (
    <BrowserRouter>
      <AuthProvider>
        {children}
      </AuthProvider>
    </BrowserRouter>
  );
}

function customRender(ui: ReactElement, options?: Omit<RenderOptions, 'wrapper'>) {
  return render(ui, { wrapper: AllProviders, ...options });
}

// Re-export everything from testing-library so consumers only need one import
export { customRender as render };
export * from '@testing-library/react';

export const TEST_SESSION = {
  userId: 'user-001', username: 'testuser', displayName: 'Test User', accessToken: 'test-access-token',
} satisfies AuthResponse;

/**
 * Signs the test in (D156): the access token is held in memory, and the mock refresh endpoint,
 * which answers 401 by default, accepts the browser's cookie and returns `session`.
 */
export function setupAuthToken(session: AuthResponse = TEST_SESSION) {
  setAccessToken(session.accessToken);
  server.use(http.post('/api/auth/refresh', () => HttpResponse.json(session)));
}

/** Signs the test out: no token in memory, and the refresh endpoint answers 401 again. */
export function clearAuthToken() {
  clearAccessToken();
  server.use(
    http.post('/api/auth/refresh', () =>
      HttpResponse.json({ error: 'Your session has ended. Please sign in again.' }, { status: 401 })),
  );
}
