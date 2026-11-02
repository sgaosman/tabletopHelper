import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { server } from './src/test/mocks/server';
import { MockStompClient } from './src/test/mocks/stomp';
import { clearAccessToken } from './src/api/tokenStore';

// No test opens a real socket: the STOMP client and SockJS are replaced everywhere.
vi.mock('@stomp/stompjs', async (importOriginal) => {
  const { MockStompClient: Client } = await import('./src/test/mocks/stomp');
  return { ...(await importOriginal<typeof import('@stomp/stompjs')>()), Client };
});

vi.mock('sockjs-client', () => ({
  default: class MockSockJS {
    url: string;
    constructor(url: string) {
      this.url = url;
    }
  },
}));

// Strict mode: a request with no handler fails with a network error. Components that
// catch that error would hide it, so the test is also failed here.
const unhandledRequests: string[] = [];

function failOnUnhandledRequests() {
  if (unhandledRequests.length === 0) return;
  const requests = unhandledRequests.splice(0);
  throw new Error(`Requests with no MSW handler: ${requests.join(', ')}`);
}

beforeAll(() => {
  server.events.on('request:unhandled', ({ request }) => {
    unhandledRequests.push(`${request.method} ${new URL(request.url).pathname}`);
  });
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  localStorage.clear();
  // The access token lives in module memory, which outlasts a single test (D156)
  clearAccessToken();
  MockStompClient.reset();
  failOnUnhandledRequests();
});

afterAll(() => {
  server.close();
  failOnUnhandledRequests();
});
