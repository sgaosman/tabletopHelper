import { describe, it, expect } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { makeMockEncounter } from '../test/mocks/handlers';
import { server } from '../test/mocks/server';
import { MockStompClient } from '../test/mocks/stomp';
import { setupAuthToken, TEST_SESSION } from '../test/test-utils';
import { EncounterProvider, useEncounter } from './EncounterContext';

const STATE_TOPIC = '/topic/encounter/enc-001/state';
const ERROR_QUEUE = '/user/queue/encounter/errors';

/** Answers each refresh with a new access token, socket-token-1, socket-token-2, and so on. */
function issueFreshTokens() {
  let issued = 0;
  server.use(
    http.post('/api/auth/refresh', () => {
      issued += 1;
      return HttpResponse.json({ ...TEST_SESSION, accessToken: `socket-token-${issued}` });
    }),
  );
  return () => issued;
}

function EncounterProbe() {
  const { encounter, isConnected, error, sendMessage } = useEncounter();
  return (
    <div>
      <p>{encounter ? `${encounter.name}, round ${encounter.roundNumber}` : 'No encounter'}</p>
      <p>{isConnected ? 'Connected' : 'Not connected'}</p>
      {error && <p role="alert">{error}</p>}
      <button onClick={() => sendMessage('/app/encounter/enc-001/ping', { at: 1 })}>Send</button>
    </div>
  );
}

function renderProvider() {
  return render(
    <EncounterProvider encounterId="enc-001">
      <EncounterProbe />
    </EncounterProvider>,
  );
}

/** Waits for the REST load and the socket connection, so later messages are not overwritten. */
async function renderConnected() {
  const view = renderProvider();
  await screen.findByText('Goblin Ambush, round 1');
  await screen.findByText('Connected');
  return { ...view, client: MockStompClient.latest() };
}

describe('EncounterContext', () => {
  it('loads the encounter over REST when it mounts', async () => {
    setupAuthToken();
    renderProvider();
    expect(screen.getByText('No encounter')).toBeInTheDocument();
    expect(await screen.findByText('Goblin Ambush, round 1')).toBeInTheDocument();
  });

  it('connects with a token fetched for the connection, then subscribes and joins', async () => {
    setupAuthToken();
    issueFreshTokens();
    const { client } = await renderConnected();

    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer socket-token-1' });
    expect(client.isSubscribed(STATE_TOPIC)).toBe(true);
    expect(client.isSubscribed(ERROR_QUEUE)).toBe(true);
    expect(client.published).toEqual([{ destination: '/app/encounter/enc-001/join', body: '{}', headers: {} }]);
  });

  it('fetches a fresh token before every connect, including a reconnect after the connection drops', async () => {
    setupAuthToken();
    const refreshes = issueFreshTokens();
    const { client } = await renderConnected();

    await act(() => client.drop());

    expect(refreshes()).toBe(2);
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer socket-token-2' });
    expect(client.connected).toBe(true);
    expect(client.isSubscribed(STATE_TOPIC)).toBe(true);
    expect(screen.getByText('Connected')).toBeInTheDocument();
  });

  it('replaces the encounter with each state message', async () => {
    setupAuthToken();
    const { client } = await renderConnected();

    act(() => client.emit(STATE_TOPIC, makeMockEncounter({ roundNumber: 2 })));
    expect(screen.getByText('Goblin Ambush, round 2')).toBeInTheDocument();

    act(() => client.emit(STATE_TOPIC, makeMockEncounter({ name: 'Second Wave', roundNumber: 3 })));
    expect(screen.getByText('Second Wave, round 3')).toBeInTheDocument();
  });

  it('shows an error frame and marks the connection as lost', async () => {
    setupAuthToken();
    const { client } = await renderConnected();

    act(() => client.fail('Access denied'));

    expect(screen.getByRole('alert')).toHaveTextContent('Access denied');
    expect(screen.getByText('Not connected')).toBeInTheDocument();
  });

  it('shows a message from the error queue until the next state arrives', async () => {
    setupAuthToken();
    const { client } = await renderConnected();

    act(() => client.emit(ERROR_QUEUE, 'It is not your turn'));
    expect(screen.getByRole('alert')).toHaveTextContent('It is not your turn');
    expect(screen.getByText('Connected')).toBeInTheDocument();

    act(() => client.emit(STATE_TOPIC, makeMockEncounter({ roundNumber: 2 })));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('publishes messages as JSON while connected', async () => {
    setupAuthToken();
    const { client } = await renderConnected();

    await userEvent.setup().click(screen.getByRole('button', { name: 'Send' }));

    expect(client.published).toContainEqual({ destination: '/app/encounter/enc-001/ping', body: '{"at":1}', headers: {} });
  });

  it('closes the connection when the provider unmounts', async () => {
    setupAuthToken();
    const { client, unmount } = await renderConnected();

    unmount();

    expect(client.active).toBe(false);
    expect(client.connected).toBe(false);
  });

  it('stops without connecting, and says why, when the session cannot be refreshed', async () => {
    // The refresh is refused after the encounter has loaded, so the encounter does not clear the error
    server.use(
      http.post('/api/auth/refresh', async () => {
        await delay(50);
        return HttpResponse.json({ error: 'Your session has ended. Please sign in again.' }, { status: 401 });
      }),
    );
    renderProvider();

    expect(await screen.findByText('Goblin Ambush, round 1')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended. Please sign in again.');
    const client = MockStompClient.latest();
    expect(client.active).toBe(false);
    expect(client.connected).toBe(false);
    expect(screen.getByText('Not connected')).toBeInTheDocument();
  });
});
