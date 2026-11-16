import { describe, it, expect, vi } from 'vitest';
import { registerLiveSocket, stopLiveSockets, tokenLifetimeMs } from './liveSockets';

function jwt(payload: object) {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'HS512' })}.${encode(payload)}.signature`;
}

describe('liveSockets', () => {
  it('stops every registered connection once, and forgets them', async () => {
    const first = vi.fn(async () => {});
    const second = vi.fn(async () => {});
    registerLiveSocket(first);
    registerLiveSocket(second);

    await stopLiveSockets();
    await stopLiveSockets();

    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('does not stop a connection that has unregistered, and survives one that fails to stop', async () => {
    const gone = vi.fn(async () => {});
    const failing = vi.fn(async () => { throw new Error('already closed'); });
    registerLiveSocket(gone)();
    registerLiveSocket(failing);

    await expect(stopLiveSockets()).resolves.toBeUndefined();

    expect(gone).not.toHaveBeenCalled();
    expect(failing).toHaveBeenCalledTimes(1);
  });

  it('reads how long a token was issued for from its exp and iat', () => {
    expect(tokenLifetimeMs(jwt({ sub: 'u', iat: 1_700_000_000, exp: 1_700_000_900 }))).toBe(900_000);
  });

  it('returns null for something that is not a JWT with exp and iat', () => {
    expect(tokenLifetimeMs('test-access-token')).toBeNull();
    expect(tokenLifetimeMs('a.not-base64!.c')).toBeNull();
    expect(tokenLifetimeMs(jwt({ sub: 'u', exp: 1_700_000_900 }))).toBeNull();
    expect(tokenLifetimeMs(jwt({ sub: 'u', iat: 1_700_000_900, exp: 1_700_000_000 }))).toBeNull();
  });
});
