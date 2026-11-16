// The page's open STOMP connections, so logout can close them itself instead of waiting for the encounter page to
// unmount (D238). Each connection registers a function that stops it for good.
type StopSocket = () => Promise<void>;

const sockets = new Set<StopSocket>();

/** Adds a connection; the returned function removes it again, as when its page unmounts. */
export function registerLiveSocket(stop: StopSocket): () => void {
  sockets.add(stop);
  return () => {
    sockets.delete(stop);
  };
}

/** Stops every open connection, without reconnecting, and resolves once they have closed. */
export async function stopLiveSockets(): Promise<void> {
  const stopping = [...sockets].map(stop => stop());
  sockets.clear();
  await Promise.allSettled(stopping);
}

/**
 * How long a JWT was issued for, in milliseconds, read from its exp and iat claims without checking the signature (the
 * server checks it). The lifetime, unlike exp itself, does not depend on this computer's clock agreeing with the
 * server's. Null when the token is not a JWT with both claims.
 */
export function tokenLifetimeMs(token: string): number | null {
  const payload = token.split('.')[1];
  if (!payload) return null;
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const claims: unknown = JSON.parse(atob(padded));
    if (typeof claims !== 'object' || claims === null) return null;
    const { exp, iat } = claims as { exp?: unknown; iat?: unknown };
    if (typeof exp !== 'number' || typeof iat !== 'number' || exp <= iat) return null;
    return (exp - iat) * 1000;
  } catch {
    return null;
  }
}
