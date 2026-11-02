import { describe, it, expect } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockEncounter } from '../test/mocks/handlers';
import { MockStompClient } from '../test/mocks/stomp';
import { setupAuthToken } from '../test/test-utils';
import { EncounterProvider, useEncounter } from './EncounterContext';

const STATE_TOPIC = '/topic/encounter/enc-001/state';
const ERROR_QUEUE = '/user/queue/encounter/errors';

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

  it('subscribes and joins the encounter once connected', async () => {
    setupAuthToken();
    const { client } = await renderConnected();

    // R3 (D156): the token will be fetched in beforeConnect instead of read from localStorage
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer test-access-token' });
    expect(client.isSubscribed(STATE_TOPIC)).toBe(true);
    expect(client.isSubscribed(ERROR_QUEUE)).toBe(true);
    expect(client.published).toEqual([{ destination: '/app/encounter/enc-001/join', body: '{}', headers: {} }]);
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

  it('does not open a connection without a stored access token', async () => {
    renderProvider();

    expect(await screen.findByText('Goblin Ambush, round 1')).toBeInTheDocument();
    // R3 (D156): tokens leave localStorage, so this guard will change with them
    expect(MockStompClient.instances).toHaveLength(0);
    expect(screen.getByText('Not connected')).toBeInTheDocument();
  });
});
