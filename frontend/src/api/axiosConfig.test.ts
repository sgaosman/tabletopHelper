import { describe, it, expect, afterEach, vi } from 'vitest';
import { delay, http, HttpResponse } from 'msw';
import { server } from '../test/mocks/server';
import { setupAuthToken } from '../test/test-utils';
import type { AuthResponse } from '../types/auth';
import api from './axiosConfig';

const REFRESHED = {
  userId: 'user-001', username: 'testuser', displayName: 'Test User',
  accessToken: 'refreshed-access-token', refreshToken: 'refreshed-refresh-token',
} satisfies AuthResponse;

/** GET /api/protected rejects the stored token with `status` and accepts the refreshed one. */
function protectedEndpoint(status: number) {
  const authHeaders: (string | null)[] = [];
  server.use(
    http.get('/api/protected', ({ request }) => {
      const auth = request.headers.get('Authorization');
      authHeaders.push(auth);
      return auth === `Bearer ${REFRESHED.accessToken}`
        ? HttpResponse.json({ ok: true })
        : new HttpResponse(null, { status });
    }),
  );
  return authHeaders;
}

function refreshEndpoint(respond: () => Response | Promise<Response> = () => HttpResponse.json(REFRESHED)) {
  const bodies: unknown[] = [];
  server.use(
    http.post('/api/auth/refresh', async ({ request }) => {
      bodies.push(await request.json());
      return respond();
    }),
  );
  return bodies;
}

describe('axiosConfig', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('refreshes once on a 401 and retries the request with the new token', async () => {
    setupAuthToken();
    const authHeaders = protectedEndpoint(401);
    const refreshBodies = refreshEndpoint();

    const response = await api.get('/protected');

    expect(response.data).toEqual({ ok: true });
    expect(authHeaders).toEqual(['Bearer test-access-token', `Bearer ${REFRESHED.accessToken}`]);
    // R3 (D156): the access token moves to memory and the refresh token to an httpOnly cookie
    expect(refreshBodies).toEqual([{ refreshToken: 'test-refresh-token' }]);
    expect(localStorage.getItem('accessToken')).toBe(REFRESHED.accessToken);
    expect(localStorage.getItem('refreshToken')).toBe(REFRESHED.refreshToken);
  });

  it('shares one refresh between 401s that arrive together', async () => {
    setupAuthToken();
    let rejected = 0;
    let releaseRefresh!: () => void;
    const allRejected = new Promise<void>(resolve => { releaseRefresh = resolve; });
    server.use(
      http.get('/api/protected/:n', ({ request, params }) => {
        if (request.headers.get('Authorization') === `Bearer ${REFRESHED.accessToken}`) {
          return HttpResponse.json({ n: params.n });
        }
        rejected += 1;
        if (rejected === 3) releaseRefresh();
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const refreshBodies = refreshEndpoint(async () => {
      // Hold the refresh until every request has been rejected, so the later two must queue.
      await allRejected;
      await delay(20);
      return HttpResponse.json(REFRESHED);
    });

    const responses = await Promise.all([api.get('/protected/1'), api.get('/protected/2'), api.get('/protected/3')]);

    expect(responses.map(r => r.data)).toEqual([{ n: '1' }, { n: '2' }, { n: '3' }]);
    expect(refreshBodies).toHaveLength(1);
  });

  it('clears the session and sends the user to /login when the refresh fails', async () => {
    setupAuthToken();
    protectedEndpoint(401);
    refreshEndpoint(() => new HttpResponse(null, { status: 401 }));
    const location = { href: window.location.href };
    vi.stubGlobal('location', location);

    let settled = false;
    void api.get('/protected').finally(() => { settled = true; });

    await vi.waitFor(() => expect(location.href).toBe('/login'));
    // R3 (D156): tokens leave localStorage, so clearing the session changes with them
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('refreshToken')).toBeNull();
    expect(localStorage.getItem('user')).toBeNull();
    // The failed request is left pending rather than rejected.
    expect(settled).toBe(false);
  });

  it('does not refresh when the request carried no token', async () => {
    const authHeaders = protectedEndpoint(401);
    const refreshBodies = refreshEndpoint();

    await expect(api.get('/protected')).rejects.toMatchObject({ response: { status: 401 } });

    expect(authHeaders).toEqual([null]);
    expect(refreshBodies).toEqual([]);
  });

  it('passes the 401 on when there is no refresh token to use', async () => {
    // R3 (D156): the refresh token becomes an httpOnly cookie the page cannot see
    localStorage.setItem('accessToken', 'test-access-token');
    protectedEndpoint(401);
    const refreshBodies = refreshEndpoint();

    await expect(api.get('/protected')).rejects.toMatchObject({ response: { status: 401 } });

    expect(refreshBodies).toEqual([]);
  });

  it('retries a request at most once', async () => {
    setupAuthToken();
    const authHeaders: (string | null)[] = [];
    server.use(
      http.get('/api/protected', ({ request }) => {
        authHeaders.push(request.headers.get('Authorization'));
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const refreshBodies = refreshEndpoint();

    await expect(api.get('/protected')).rejects.toMatchObject({ response: { status: 401 } });

    expect(refreshBodies).toHaveLength(1);
    expect(authHeaders).toEqual(['Bearer test-access-token', `Bearer ${REFRESHED.accessToken}`]);
  });

  it('refreshes on a 403 as well', async () => {
    // R3 (D155): a 403 should no longer trigger a refresh; only a 401 should
    setupAuthToken();
    protectedEndpoint(403);
    const refreshBodies = refreshEndpoint();

    const response = await api.get('/protected');

    expect(response.data).toEqual({ ok: true });
    expect(refreshBodies).toHaveLength(1);
  });

  it('passes other errors on without a refresh', async () => {
    setupAuthToken();
    protectedEndpoint(500);
    const refreshBodies = refreshEndpoint();

    await expect(api.get('/protected')).rejects.toMatchObject({ response: { status: 500 } });

    expect(refreshBodies).toEqual([]);
  });
});
