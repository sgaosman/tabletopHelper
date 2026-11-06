import { describe, it, expect } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { useState, type ReactElement } from 'react';
import { delay, http, HttpResponse } from 'msw';
import { makeMockEncounter, makeMockLogEntry } from '../test/mocks/handlers';
import { server } from '../test/mocks/server';
import { MockStompClient } from '../test/mocks/stomp';
import { createTestQueryClient, setupAuthToken, TEST_SESSION } from '../test/test-utils';
import { useCombatLog, useEncounterAction } from '../queries/encounters';
import { queryKeys } from '../queries/keys';
import type { Encounter, EncounterView } from '../types/encounter';
import { EncounterProvider } from './EncounterContext';
import { useEncounter } from '../hooks/useEncounter';

const STATE_TOPIC = '/topic/encounter/enc-001/state';
const LOG_TOPIC = '/topic/encounter/enc-001/log';
const DM_STATE_TOPIC = '/topic/encounter/enc-001/dm-state';
const DM_LOG_TOPIC = '/topic/encounter/enc-001/dm-log';
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

/** Counts the requests for the encounter and its log, answering each with `round` from the encounter loads so far. */
function countLoads() {
  const loads = { encounter: 0, log: 0 };
  server.use(
    http.get('/api/encounters/:id', () => {
      loads.encounter += 1;
      return HttpResponse.json(makeMockEncounter({ roundNumber: loads.encounter }));
    }),
    http.get('/api/encounters/:id/combat/log', () => {
      loads.log += 1;
      return HttpResponse.json([makeMockLogEntry('log-1', 'Gimli hits Test Goblin.')]);
    }),
  );
  return loads;
}

function EncounterProbe() {
  const { encounter, isConnected, error, sendMessage } = useEncounter();
  const log = useCombatLog();
  return (
    <div>
      <p>{encounter ? `${encounter.name}, round ${encounter.roundNumber}` : 'No encounter'}</p>
      <p>{log.length} log entries</p>
      <p>{isConnected ? 'Connected' : 'Not connected'}</p>
      {error && <p role="alert">{error}</p>}
      <button onClick={() => sendMessage('/app/encounter/enc-001/ping', { at: 1 })}>Send</button>
    </div>
  );
}

/** Deals 5 damage to the goblin through useEncounterAction, and says when the request has settled. */
function ActionProbe() {
  const actions = useEncounterAction();
  const [outcome, setOutcome] = useState('');

  async function dealDamage() {
    try {
      await actions.applyDamage('part-001', 5);
      setOutcome('Damage dealt');
    } catch {
      setOutcome('Damage failed');
    }
  }

  return (
    <div>
      <button onClick={dealDamage}>Deal damage</button>
      {outcome && <p>{outcome}</p>}
    </div>
  );
}

/** Renders the provider in a fresh query cache, returned as `queryClient`. */
function renderProvider(view?: EncounterView, extra?: ReactElement) {
  const queryClient = createTestQueryClient();
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <EncounterProvider encounterId="enc-001" view={view}>
        <EncounterProbe />
        {extra}
      </EncounterProvider>
    </QueryClientProvider>,
  );
  return { ...rendered, queryClient };
}

/** Waits for the REST loads and the socket connection, so later messages are not overwritten. */
async function renderConnected(view?: EncounterView, extra?: ReactElement) {
  const rendered = renderProvider(view, extra);
  await screen.findByText(/Goblin Ambush, round \d/);
  await screen.findByText('Connected');
  return { ...rendered, client: MockStompClient.latest() };
}

/** The Player View the cache holds for enc-001. */
function cachedEncounter(queryClient: QueryClient) {
  return queryClient.getQueryData<Encounter>(queryKeys.encounters.detail('enc-001', 'player'));
}

describe('EncounterContext', () => {
  it('loads the encounter and its combat log over REST when it mounts', async () => {
    setupAuthToken();
    countLoads();
    renderProvider();
    expect(screen.getByText('No encounter')).toBeInTheDocument();
    expect(await screen.findByText('Goblin Ambush, round 1')).toBeInTheDocument();
    expect(await screen.findByText('1 log entries')).toBeInTheDocument();
  });

  it('shows why the encounter could not be loaded', async () => {
    setupAuthToken();
    server.use(
      http.get('/api/encounters/:id', () => HttpResponse.json({ error: 'You are not a member of this campaign' }, { status: 403 })),
    );
    renderProvider();

    expect(await screen.findByRole('alert')).toHaveTextContent('You are not a member of this campaign');
    expect(screen.getByText('No encounter')).toBeInTheDocument();
  });

  it('connects with a token fetched for the connection, then subscribes and joins', async () => {
    setupAuthToken();
    issueFreshTokens();
    const { client } = await renderConnected();

    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer socket-token-1' });
    expect(client.isSubscribed(STATE_TOPIC)).toBe(true);
    expect(client.isSubscribed(LOG_TOPIC)).toBe(true);
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
    expect(client.isSubscribed(LOG_TOPIC)).toBe(true);
    expect(screen.getByText('Connected')).toBeInTheDocument();
  });

  it('loads the encounter and its log again after each reconnect, but not after the first connect', async () => {
    setupAuthToken();
    const loads = countLoads();
    const { client } = await renderConnected();
    await waitFor(() => expect(loads).toEqual({ encounter: 1, log: 1 }));

    await act(() => client.drop());

    expect(await screen.findByText('Goblin Ambush, round 2')).toBeInTheDocument();
    await waitFor(() => expect(loads).toEqual({ encounter: 2, log: 2 }));
  });

  it('subscribes to the DM View and the whole combat log instead of the players\' topics when asked for them', async () => {
    setupAuthToken();
    const { client } = await renderConnected('dm');

    expect(client.isSubscribed(DM_STATE_TOPIC)).toBe(true);
    expect(client.isSubscribed(DM_LOG_TOPIC)).toBe(true);
    expect(client.isSubscribed(STATE_TOPIC)).toBe(false);
    expect(client.isSubscribed(LOG_TOPIC)).toBe(false);
    expect(client.isSubscribed(ERROR_QUEUE)).toBe(true);
    act(() => client.emit(DM_STATE_TOPIC, makeMockEncounter({ roundNumber: 4 })));
    act(() => client.emit(DM_LOG_TOPIC, [makeMockLogEntry('log-1', 'Bugbear hits Gimli.')]));
    expect(await screen.findByText('Goblin Ambush, round 4')).toBeInTheDocument();
    expect(await screen.findByText('1 log entries')).toBeInTheDocument();
  });

  it('replaces the encounter with each state message', async () => {
    setupAuthToken();
    const { client } = await renderConnected();

    act(() => client.emit(STATE_TOPIC, makeMockEncounter({ roundNumber: 2 })));
    expect(await screen.findByText('Goblin Ambush, round 2')).toBeInTheDocument();

    act(() => client.emit(STATE_TOPIC, makeMockEncounter({ name: 'Second Wave', roundNumber: 3 })));
    expect(await screen.findByText('Second Wave, round 3')).toBeInTheDocument();
  });

  it('replaces the combat log with each log message', async () => {
    setupAuthToken();
    const { client } = await renderConnected();

    act(() => client.emit(LOG_TOPIC, [
      makeMockLogEntry('log-1', 'Gimli hits Test Goblin.'),
      makeMockLogEntry('log-2', 'Test Goblin falls.', { actionType: 'KILL' }),
    ]));
    expect(await screen.findByText('2 log entries')).toBeInTheDocument();

    // Revealing a monster sends its entries again; hiding one sends a shorter log
    act(() => client.emit(LOG_TOPIC, [makeMockLogEntry('log-2', 'Test Goblin falls.', { actionType: 'KILL' })]));
    expect(await screen.findByText('1 log entries')).toBeInTheDocument();
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
    // The refresh is refused after the encounter has loaded, so the message is not a load failure
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

describe('useEncounterAction', () => {
  it('shows the encounter state an action answers with, without loading the encounter again', async () => {
    setupAuthToken();
    const loads = countLoads();
    server.use(
      http.post('/api/encounters/:id/combat/damage', () => HttpResponse.json(makeMockEncounter({ roundNumber: 7 }))),
    );
    const { queryClient } = await renderConnected('player', <ActionProbe />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Deal damage' }));

    expect(await screen.findByText('Damage dealt')).toBeInTheDocument();
    expect(cachedEncounter(queryClient)?.roundNumber).toBe(7);
    expect(await screen.findByText('Goblin Ambush, round 7')).toBeInTheDocument();
    expect(loads).toEqual({ encounter: 1, log: 1 });
  });

  it('keeps newer state from the WebSocket when a slow response arrives after it', async () => {
    setupAuthToken();
    let answer!: () => void;
    server.use(
      http.post('/api/encounters/:id/combat/damage', async () => {
        await new Promise<void>(resolve => { answer = resolve; });
        return HttpResponse.json(makeMockEncounter({ roundNumber: 2 }));
      }),
    );
    const { client, queryClient } = await renderConnected('player', <ActionProbe />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Deal damage' }));
    await waitFor(() => expect(answer).toBeDefined());
    // The server publishes each change before it answers, so this state already includes the damage
    act(() => client.emit(STATE_TOPIC, makeMockEncounter({ roundNumber: 3 })));
    expect(await screen.findByText('Goblin Ambush, round 3')).toBeInTheDocument();
    act(() => answer());

    // The action has settled once its outcome shows, so the cache holds whatever it decided
    expect(await screen.findByText('Damage dealt')).toBeInTheDocument();
    expect(cachedEncounter(queryClient)?.roundNumber).toBe(3);
    expect(screen.getByText('Goblin Ambush, round 3')).toBeInTheDocument();
  });

  it('leaves the encounter as it was when the action fails', async () => {
    setupAuthToken();
    server.use(
      http.post('/api/encounters/:id/combat/damage', () => HttpResponse.json({ error: 'Target is already dead' }, { status: 409 })),
    );
    const { queryClient } = await renderConnected('player', <ActionProbe />);
    const loaded = cachedEncounter(queryClient);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Deal damage' }));

    expect(await screen.findByText('Damage failed')).toBeInTheDocument();
    expect(cachedEncounter(queryClient)).toBe(loaded);
    expect(screen.getByText('Goblin Ambush, round 1')).toBeInTheDocument();
  });
});
