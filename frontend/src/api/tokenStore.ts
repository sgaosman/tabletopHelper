import axios from 'axios';
import type { AuthResponse } from '../types/auth';

// The access token lives only in this module's memory. The refresh token is an httpOnly cookie the
// page never sees; the server reads it on /api/auth/refresh and /api/auth/logout (D156).
let accessToken: string | null = null;
let refreshInFlight: Promise<AuthResponse> | null = null;
// Bumped by clearAccessToken, so a refresh that was already under way cannot bring the token back
let generation = 0;

const REFRESH_LOCK = 'tabletophelper-token-refresh';

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string): void {
  accessToken = token;
}

/** Forgets the access token, and the result of any refresh still under way. */
export function clearAccessToken(): void {
  accessToken = null;
  refreshInFlight = null;
  generation += 1;
}

// Plain axios rather than the api instance, so a failed refresh never reaches the 401 handling
// that called it. withCredentials sends the cookie even if the API moves to another origin.
async function requestRefresh(): Promise<AuthResponse> {
  const response = await axios.post<AuthResponse>('/api/auth/refresh', null, { withCredentials: true });
  return response.data;
}

/**
 * Trades the refresh cookie for a new access token, keeps the token, and resolves with the session.
 * Rejects when the cookie is missing, expired or revoked, or the server cannot be reached.
 *
 * Callers in one tab share a single request. Tabs take turns through the Web Locks API, so each
 * refresh sends the cookie the previous one set; two tabs sending the same cookie would look like
 * reuse and end the session (D156). Browsers without Web Locks (and jsdom) refresh without the lock.
 */
export function refreshAccessToken(): Promise<AuthResponse> {
  if (refreshInFlight) return refreshInFlight;

  const startedIn = generation;
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
  const refresh = (locks ? locks.request(REFRESH_LOCK, requestRefresh) : requestRefresh())
    .then((session) => {
      if (startedIn === generation) accessToken = session.accessToken;
      return session;
    })
    .finally(() => {
      if (refreshInFlight === refresh) refreshInFlight = null;
    });
  refreshInFlight = refresh;
  return refresh;
}
