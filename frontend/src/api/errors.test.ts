import { describe, it, expect } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '../test/mocks/server';
import api from './axiosConfig';
import { apiErrorMessage, apiErrorStatus, NETWORK_ERROR_MESSAGE, SERVER_ERROR_MESSAGE } from './errors';

/** Sends GET /api/campaigns/camp-001, answered by `respond`, and returns the error axios rejects with. */
async function failedRequest(respond: () => Response): Promise<unknown> {
  server.use(http.get('/api/campaigns/:id', respond));
  return api.get('/campaigns/camp-001').then(
    () => { throw new Error('Expected the request to fail'); },
    (error: unknown) => error,
  );
}

describe('api errors', () => {
  it.each([400, 403, 404, 409])("reads the server's message and status from a %i", async (status) => {
    const error = await failedRequest(() => HttpResponse.json({ error: 'Campaign not found' }, { status }));

    expect(apiErrorStatus(error)).toBe(status);
    expect(apiErrorMessage(error)).toBe('Campaign not found');
  });

  it('gives a generic message for a 5xx, whatever its body says', async () => {
    const leaky = await failedRequest(() =>
      HttpResponse.json({ error: 'NullPointerException at CampaignService.java:93' }, { status: 500 }));
    const fromProxy = await failedRequest(() => new HttpResponse('<html>Bad Gateway</html>', { status: 502 }));

    expect(apiErrorStatus(leaky)).toBe(500);
    expect(apiErrorMessage(leaky)).toBe(SERVER_ERROR_MESSAGE);
    expect(apiErrorStatus(fromProxy)).toBe(502);
    expect(apiErrorMessage(fromProxy)).toBe(SERVER_ERROR_MESSAGE);
  });

  it('says the server could not be reached when no response arrives', async () => {
    const error = await failedRequest(() => HttpResponse.error());

    expect(apiErrorStatus(error)).toBeUndefined();
    expect(apiErrorMessage(error)).toBe(NETWORK_ERROR_MESSAGE);
  });

  it('falls back to the generic message for a 4xx without a message', async () => {
    const plainText = await failedRequest(() => new HttpResponse('Bad Request', { status: 400 }));
    const blank = await failedRequest(() => HttpResponse.json({ error: ' ' }, { status: 404 }));

    expect(apiErrorMessage(plainText)).toBe(SERVER_ERROR_MESSAGE);
    expect(apiErrorStatus(blank)).toBe(404);
    expect(apiErrorMessage(blank)).toBe(SERVER_ERROR_MESSAGE);
  });

  it('gives a generic message for an error that did not come from a request', () => {
    expect(apiErrorStatus(new TypeError('Cannot read properties of undefined'))).toBeUndefined();
    expect(apiErrorMessage(new TypeError('Cannot read properties of undefined'))).toBe(SERVER_ERROR_MESSAGE);
    expect(apiErrorMessage(undefined)).toBe(SERVER_ERROR_MESSAGE);
  });
});
