import { describe, it, expect } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { ReconnectionTimeMode } from '@stomp/stompjs';
import { delay, http, HttpResponse } from 'msw';
import { makeMockEncounter, makeMockLogEntry } from '../test/mocks/handlers';
import { server } from '../test/mocks/server';
import { MockStompClient } from '../test/mocks/stomp';
import { createTestQueryClient, setupAuthToken, TEST_SESSION } from '../test/test-utils';
import { useLiveEncounter } from './liveEncounter';
import { EncounterProvider } from './EncounterContext';
import { ConnectionBanner } from '../components/encounter/ConnectionStatus';
import { MAX_RECONNECT_DELAY_MS, RECONNECT_DELAY_MS } from '../hooks/useWebSocket';

/** Counts the loads of the encounter, answering each with the round number of that load. */
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

function ConnectionProbe() {
  const { encounter, connectionState, reconnect } = useLiveEncounter();
  return (
    <div>
      <p>{encounter ? `Round ${encounter.roundNumber}` : 'No encounter'}</p>
      <p data-testid="state">{connectionState}</p>
      <ConnectionBanner state={connectionState} onRetry={reconnect} />
    </div>
  );
}

async function renderConnected() {
  const queryClient = createTestQueryClient();
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <EncounterProvider encounterId="enc-001">
        <ConnectionProbe />
      </EncounterProvider>
    </QueryClientProvider>,
  );
  await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('connected'));
  return { ...rendered, client: MockStompClient.latest() };
}

function setBrowserOnline(online: boolean) {
  Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => online });
  window.dispatchEvent(new Event(online ? 'online' : 'offline'));
}

describe('the live connection of an encounter page', () => {
  afterEach(() => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => true });
  });

  it('retries with exponential backoff, from a second up to half a minute', async () => {
    setupAuthToken();
    const { client } = await renderConnected();

    const config = client as unknown as { reconnectDelay: number; reconnectTimeMode: ReconnectionTimeMode; maxReconnectDelay: number };
    expect(config.reconnectDelay).toBe(RECONNECT_DELAY_MS);
    expect(config.reconnectTimeMode).toBe(ReconnectionTimeMode.EXPONENTIAL);
    expect(config.maxReconnectDelay).toBe(MAX_RECONNECT_DELAY_MS);
  });

  it('says it is connecting until the first connect, and shows no banner then', async () => {
    setupAuthToken();
    // The token for the socket comes late, so the connect waits
    server.use(http.post('/api/auth/refresh', async () => {
      await delay(50);
      return HttpResponse.json(TEST_SESSION);
    }));
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <EncounterProvider encounterId="enc-001"><ConnectionProbe /></EncounterProvider>
      </QueryClientProvider>,
    );

    expect(screen.getByTestId('state')).toHaveTextContent('connecting');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('connected'));
  });

  it('shows the reconnecting banner when the socket drops, and loads the encounter again once it is back', async () => {
    setupAuthToken();
    const loads = countLoads();
    const { client } = await renderConnected();
    await waitFor(() => expect(loads).toEqual({ encounter: 1, log: 1 }));

    act(() => client.goDown());

    expect(screen.getByTestId('state')).toHaveTextContent('reconnecting');
    expect(screen.getByRole('status')).toHaveTextContent(/Connection lost. Reconnecting/);

    await act(() => client.reconnectNow());

    expect(screen.getByTestId('state')).toHaveTextContent('connected');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    // What was published while it was down is loaded again
    expect(await screen.findByText('Round 2')).toBeInTheDocument();
    await waitFor(() => expect(loads).toEqual({ encounter: 2, log: 2 }));
  });

  it('fetches a fresh token for the reconnect', async () => {
    setupAuthToken();
    let issued = 0;
    server.use(http.post('/api/auth/refresh', () => {
      issued += 1;
      return HttpResponse.json({ ...TEST_SESSION, accessToken: `socket-token-${issued}` });
    }));
    const { client } = await renderConnected();
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer socket-token-1' });

    act(() => client.goDown());
    await act(() => client.reconnectNow());

    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer socket-token-2' });
  });

  it('says it is offline while the browser is, and connects again at once when the network returns', async () => {
    setupAuthToken();
    const loads = countLoads();
    const { client } = await renderConnected();
    await waitFor(() => expect(loads.encounter).toBe(1));

    act(() => {
      setBrowserOnline(false);
      client.goDown();
    });
    expect(screen.getByTestId('state')).toHaveTextContent('offline');
    expect(screen.getByRole('status')).toHaveTextContent(/You are offline/);

    act(() => setBrowserOnline(true));

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('connected'));
    expect(client.active).toBe(true);
    await waitFor(() => expect(loads.encounter).toBe(2));
  });

  it('stays live when the network blinks but the socket survives', async () => {
    setupAuthToken();
    await renderConnected();

    act(() => setBrowserOnline(false));
    expect(screen.getByTestId('state')).toHaveTextContent('offline');

    act(() => setBrowserOnline(true));
    expect(screen.getByTestId('state')).toHaveTextContent('connected');
  });

  it('tries again at once from the banner\'s Retry now', async () => {
    setupAuthToken();
    const loads = countLoads();
    const { client } = await renderConnected();
    await waitFor(() => expect(loads.encounter).toBe(1));

    act(() => client.goDown());
    await userEvent.click(screen.getByRole('button', { name: /Retry now/ }));

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('connected'));
    await waitFor(() => expect(loads.encounter).toBe(2));
  });

  it('is offline, without retrying, once the session has ended', async () => {
    server.use(http.post('/api/auth/refresh', async () => {
      await delay(20);
      return HttpResponse.json({ error: 'Your session has ended. Please sign in again.' }, { status: 401 });
    }));
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <EncounterProvider encounterId="enc-001"><ConnectionProbe /></EncounterProvider>
      </QueryClientProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('offline'));
    expect(MockStompClient.latest().active).toBe(false);
  });
});
