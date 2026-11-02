import { QueryClient, type QueryKey } from '@tanstack/react-query';
import { apiErrorStatus } from '../api/errors';

/** Data fetched within the last 30 seconds is shown without asking the server again (D171). */
export const STALE_TIME_MS = 30_000;

/**
 * Reference data, such as spells, monsters and the options of their filters, doesn't change while the app
 * runs, so it never goes stale: each list is fetched once while it stays in the cache.
 */
export const REFERENCE_STALE_TIME = Infinity;

const MAX_QUERY_RETRIES = 2;

/**
 * A failed load is tried twice more, unless the server answered with a 4xx: asking again would get the
 * same answer. A 401 never gets here, because the axios interceptor refreshes the session first.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  const status = apiErrorStatus(error);
  if (status !== undefined && status >= 400 && status < 500) return false;
  return failureCount < MAX_QUERY_RETRIES;
}

/** The app's query cache. Mutations never retry, so a change is never sent twice. */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: STALE_TIME_MS, retry: shouldRetryQuery },
      mutations: { retry: false },
    },
  });
}

/**
 * A 409 means the cached copy is out of date (someone else changed it, or its state moved on), so the
 * page refetches it and shows the current state beside the server's message.
 */
export function invalidateOnConflict(queryClient: QueryClient, error: unknown, ...queryKeys: QueryKey[]) {
  if (apiErrorStatus(error) !== 409) return;
  queryKeys.forEach(queryKey => void queryClient.invalidateQueries({ queryKey }));
}
