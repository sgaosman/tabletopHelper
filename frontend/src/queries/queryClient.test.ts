import { describe, it, expect } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '../test/mocks/server';
import api from '../api/axiosConfig';
import { createQueryClient, STALE_TIME_MS } from './queryClient';

/** Answers GET /api/campaigns with `respond` and counts the requests. */
function failingCampaigns(respond: () => Response) {
  let requests = 0;
  server.use(
    http.get('/api/campaigns', () => {
      requests += 1;
      return respond();
    }),
  );
  return () => requests;
}

function loadCampaigns() {
  return createQueryClient().fetchQuery({
    queryKey: ['campaigns'],
    queryFn: () => api.get('/campaigns'),
    retryDelay: 0,
  });
}

describe('createQueryClient', () => {
  it('asks twice more after a server error, then gives up', async () => {
    const requests = failingCampaigns(() => HttpResponse.json({ error: 'Unavailable' }, { status: 503 }));

    await expect(loadCampaigns()).rejects.toThrow();
    expect(requests()).toBe(3);
  });

  it('asks twice more when the server cannot be reached', async () => {
    const requests = failingCampaigns(() => HttpResponse.error());

    await expect(loadCampaigns()).rejects.toThrow();
    expect(requests()).toBe(3);
  });

  it.each([400, 403, 404, 409])('does not ask again after a %i', async (status) => {
    const requests = failingCampaigns(() => HttpResponse.json({ error: 'No' }, { status }));

    await expect(loadCampaigns()).rejects.toThrow();
    expect(requests()).toBe(1);
  });

  it('keeps data fresh for 30 seconds and never retries a mutation', () => {
    const defaults = createQueryClient().getDefaultOptions();

    expect(defaults.queries?.staleTime).toBe(STALE_TIME_MS);
    expect(STALE_TIME_MS).toBe(30_000);
    expect(defaults.mutations?.retry).toBe(false);
  });
});
