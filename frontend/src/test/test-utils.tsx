import { type ReactElement } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { AuthProvider } from '../context/AuthContext';
import { GlossaryProvider } from '../context/GlossaryContext';
import { clearAccessToken, setAccessToken } from '../api/tokenStore';
import { createQueryClient } from '../queries/queryClient';
import type { AuthResponse } from '../types/auth';
import { server } from './mocks/server';

/** The app's query cache without retries, so a failed request shows its error straight away. */
export function createTestQueryClient(): QueryClient {
  const queryClient = createQueryClient();
  const defaults = queryClient.getDefaultOptions();
  queryClient.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retry: false } });
  return queryClient;
}

/** Renders inside the app's providers. Each render gets its own query cache, returned as `queryClient`. */
function customRender(ui: ReactElement, options?: Omit<RenderOptions, 'wrapper'>) {
  const queryClient = createTestQueryClient();

  function AllProviders({ children }: { children: React.ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthProvider>
            {children}
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    );
  }

  return { ...render(ui, { wrapper: AllProviders, ...options }), queryClient };
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

/**
 * Signs the test in and renders inside a GlossaryProvider as well (M19), so the mock Glossary (mocks/glossary.ts)
 * loads; wait for a term with a findBy query.
 */
export function renderWithGlossary(ui: ReactElement) {
  setupAuthToken();
  return customRender(<GlossaryProvider>{ui}</GlossaryProvider>);
}
