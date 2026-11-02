import { describe, it, expect, afterEach, vi } from 'vitest';
import { delay, http, HttpResponse } from 'msw';
import { server } from '../test/mocks/server';
import type { AuthResponse } from '../types/auth';
import { clearAccessToken, getAccessToken, refreshAccessToken, setAccessToken } from './tokenStore';

const SESSION = {
  userId: 'user-001', username: 'testuser', displayName: 'Test User', accessToken: 'fresh-access-token',
} satisfies AuthResponse;

/** Answers refreshes with `respond`, recording each request's credentials mode and body. */
function refreshEndpoint(respond: () => Response | Promise<Response> = () => HttpResponse.json(SESSION)) {
  const calls: { credentials: RequestCredentials; body: string }[] = [];
  server.use(
    http.post('/api/auth/refresh', async ({ request }) => {
      calls.push({ credentials: request.credentials, body: await request.text() });
      return respond();
    }),
  );
  return calls;
}

/** A stand-in for navigator.locks that records each request and whether a lock is held. */
function fakeLocks() {
  const names: string[] = [];
  let held = false;
  const locks = {
    async request<T>(name: string, callback: () => T) {
      names.push(name);
      held = true;
      try {
        return await callback();
      } finally {
        held = false;
      }
    },
  };
  return { locks, names, isHeld: () => held };
}

describe('tokenStore', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('keeps the access token in memory, never in web storage', () => {
    setAccessToken('in-memory-token');

    expect(getAccessToken()).toBe('in-memory-token');
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);

    clearAccessToken();
    expect(getAccessToken()).toBeNull();
  });

  it('refreshes with the cookie and no body, then keeps the new token', async () => {
    const calls = refreshEndpoint();

    const session = await refreshAccessToken();

    expect(session).toEqual(SESSION);
    expect(calls).toEqual([{ credentials: 'include', body: '' }]);
    expect(getAccessToken()).toBe('fresh-access-token');
  });

  it('gives callers that refresh at the same time one shared request', async () => {
    const calls = refreshEndpoint(async () => {
      await delay(20);
      return HttpResponse.json(SESSION);
    });

    const sessions = await Promise.all([refreshAccessToken(), refreshAccessToken(), refreshAccessToken()]);

    expect(calls).toHaveLength(1);
    expect(sessions.map(s => s.accessToken)).toEqual(['fresh-access-token', 'fresh-access-token', 'fresh-access-token']);
  });

  it('sends a new request for a refresh that starts after the last one finished', async () => {
    const calls = refreshEndpoint();

    await refreshAccessToken();
    await refreshAccessToken();

    expect(calls).toHaveLength(2);
  });

  it('refreshes while holding a Web Lock, so tabs take turns', async () => {
    const fake = fakeLocks();
    vi.stubGlobal('navigator', { locks: fake.locks });
    let heldDuringRequest = false;
    refreshEndpoint(() => {
      heldDuringRequest = fake.isHeld();
      return HttpResponse.json(SESSION);
    });

    await refreshAccessToken();

    expect(fake.names).toEqual(['tabletophelper-token-refresh']);
    expect(heldDuringRequest).toBe(true);
    expect(fake.isHeld()).toBe(false);
  });

  it('refreshes without a lock where the browser has no Web Locks, as in jsdom', async () => {
    expect(navigator.locks).toBeUndefined();
    const calls = refreshEndpoint();

    await expect(refreshAccessToken()).resolves.toEqual(SESSION);
    expect(calls).toHaveLength(1);
  });

  it('rejects when the server refuses the cookie, keeping no token', async () => {
    refreshEndpoint(() => HttpResponse.json({ error: 'Your session has ended. Please sign in again.' }, { status: 401 }));

    await expect(refreshAccessToken()).rejects.toMatchObject({ response: { status: 401 } });
    expect(getAccessToken()).toBeNull();
  });

  it('does not bring the token back when a refresh under way finishes after the session was cleared', async () => {
    let release!: () => void;
    const released = new Promise<void>(resolve => { release = resolve; });
    refreshEndpoint(async () => {
      await released;
      return HttpResponse.json(SESSION);
    });

    const refresh = refreshAccessToken();
    clearAccessToken();
    release();

    await expect(refresh).resolves.toEqual(SESSION);
    expect(getAccessToken()).toBeNull();
  });
});
