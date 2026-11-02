import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockLogEntry, makeMockParticipant } from '../../test/mocks/handlers';
import { MockStompClient } from '../../test/mocks/stomp';
import type { EncounterParticipant } from '../../types/encounter';
import EncounterSessionPage from './EncounterSessionPage';

const NAMES = /^(Test Goblin|Gimli|Hidden Bugbear)$/;
const DM_STATE_TOPIC = '/topic/encounter/enc-001/dm-state';

const goblin = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { sortOrder: 1, initiative: 17, hpMax: 7, hpCurrent: 7, armourClass: 15, ...overrides });
const gimli = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-gimli', 'Gimli', 'PLAYER', { sortOrder: 2, initiative: 12, controlledByUserId: 'user-002', ...overrides });
const bugbear = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-bugbear', 'Hidden Bugbear', 'MONSTER', { sortOrder: 3, initiative: 9, hpMax: 27, hpCurrent: 27, isVisibleToPlayers: false, ...overrides });

function encounterWith(goblinHp = 7) {
  return makeMockEncounter({ participants: [gimli(), goblin({ hpCurrent: goblinHp }), bugbear()] });
}

function renderPage() {
  window.history.pushState({}, '', '/dm/encounter/enc-001/session');
  return render(
    <Routes>
      <Route path="/dm/encounter/:encounterId/session" element={<EncounterSessionPage />} />
    </Routes>,
  );
}

/** Waits for the REST load and the socket connection, so a later message is not overwritten. */
async function renderLoaded() {
  const view = renderPage();
  await screen.findByRole('heading', { name: 'Goblin Ambush' });
  await screen.findByText('Live');
  return view;
}

describe('DM EncounterSessionPage', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id', () => HttpResponse.json(encounterWith())));
  });

  it('loads the encounter and lists every participant in initiative order', async () => {
    renderPage();
    expect(screen.getByText('Loading encounter...')).toBeInTheDocument();

    expect(await screen.findByRole('heading', { name: 'Goblin Ambush' })).toBeInTheDocument();
    expect(screen.getByText('Round 1 · 3/3 alive')).toBeInTheDocument();
    expect(screen.getAllByText(NAMES).map(el => el.textContent)).toEqual(['Test Goblin', 'Gimli', 'Hidden Bugbear']);
    expect(screen.getByText('7/7')).toBeInTheDocument();
    expect(screen.getByText('27/27')).toBeInTheDocument();
    expect(screen.getByText('ABC123XY')).toBeInTheDocument();
    expect(await screen.findByText('Live')).toBeInTheDocument();
  });

  it('shows the DM View pushed over the WebSocket', async () => {
    await renderLoaded();

    act(() => MockStompClient.latest().emit(DM_STATE_TOPIC, makeMockEncounter({
      roundNumber: 2,
      participants: [gimli({ isCurrentTurn: true }), goblin({ hpCurrent: 0, isAlive: false }), bugbear()],
    })));

    expect(screen.getByText('Round 2 · 2/3 alive')).toBeInTheDocument();
    expect(screen.getByText('0/7')).toBeInTheDocument();
  });

  it('subscribes to the DM View rather than the Player View', async () => {
    await renderLoaded();

    expect(MockStompClient.latest().isSubscribed(DM_STATE_TOPIC)).toBe(true);
    expect(MockStompClient.latest().isSubscribed('/topic/encounter/enc-001/state')).toBe(false);
  });

  it('hides a monster from players and shows a hidden one again', async () => {
    const visible: Record<string, boolean> = { 'part-goblin': true, 'part-bugbear': false };
    const requests: { participantId: string; body: unknown }[] = [];
    const current = () => makeMockEncounter({
      participants: [
        gimli(),
        goblin({ isVisibleToPlayers: visible['part-goblin'] }),
        bugbear({ isVisibleToPlayers: visible['part-bugbear'] }),
      ],
    });
    server.use(
      http.get('/api/encounters/:id', () => HttpResponse.json(current())),
      http.patch('/api/encounters/:id/participants/:participantId/visibility', async ({ params, request }) => {
        const body = await request.json() as { visible: boolean };
        requests.push({ participantId: params.participantId as string, body });
        visible[params.participantId as string] = body.visible;
        return HttpResponse.json(current());
      }),
    );
    const user = userEvent.setup();
    await renderLoaded();

    // Only monsters have the switch, and the bugbear starts hidden
    expect(screen.getByRole('button', { name: 'Hide from players' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show to players' })).toBeInTheDocument();
    expect(screen.getAllByText('Hidden')).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Show to players' }));
    expect(await screen.findAllByRole('button', { name: 'Hide from players' })).toHaveLength(2);
    expect(screen.queryByText('Hidden')).not.toBeInTheDocument();

    // The goblin comes first in initiative order
    await user.click(screen.getAllByRole('button', { name: 'Hide from players' })[0]);
    expect(await screen.findByText('Hidden')).toBeInTheDocument();
    expect(requests).toEqual([
      { participantId: 'part-bugbear', body: { visible: true } },
      { participantId: 'part-goblin', body: { visible: false } },
    ]);
  });

  it('shows why a monster could not be hidden', async () => {
    server.use(
      http.patch('/api/encounters/:id/participants/:participantId/visibility', () =>
        HttpResponse.json({ error: 'Only the DM can perform this action' }, { status: 403 })),
    );
    const user = userEvent.setup();
    await renderLoaded();

    await user.click(screen.getByRole('button', { name: 'Hide from players' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Only the DM can perform this action');
    expect(screen.getByRole('button', { name: 'Hide from players' })).toBeEnabled();
  });

  it('deals damage to the chosen participant and reloads the encounter', async () => {
    let goblinHp = 7;
    let damageRequest: unknown;
    server.use(
      http.get('/api/encounters/:id', () => HttpResponse.json(encounterWith(goblinHp))),
      http.post('/api/encounters/:id/combat/damage', async ({ request }) => {
        damageRequest = await request.json();
        goblinHp = 2;
        return HttpResponse.json(encounterWith(goblinHp));
      }),
    );
    const user = userEvent.setup();
    await renderLoaded();

    await user.click(screen.getAllByRole('button', { name: 'Deal damage' })[0]);
    expect(screen.getByRole('heading', { name: 'Damage Test Goblin' })).toBeInTheDocument();
    await user.type(screen.getByRole('spinbutton'), '5');
    await user.selectOptions(screen.getByRole('combobox'), 'fire');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    // R4 (D171): the damage response will update the page directly instead of a second GET
    expect(await screen.findByText('2/7')).toBeInTheDocument();
    expect(damageRequest).toEqual({ targetId: 'part-goblin', amount: 5, damageType: 'fire' });
  });

  describe('combat log', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('polls the combat log every 3 seconds until the page closes', async () => {
      // R4 (D171): the combat log will arrive over the WebSocket instead of being polled
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      const entries = [makeMockLogEntry('log-1', 'Gimli hits Test Goblin for 5 damage.')];
      let logRequests = 0;
      server.use(
        http.get('/api/encounters/:id/combat/log', () => {
          logRequests += 1;
          return HttpResponse.json(entries);
        }),
      );
      const { unmount } = renderPage();
      expect(await screen.findByText('(1 entries)')).toBeInTheDocument();
      expect(logRequests).toBe(1);

      entries.push(makeMockLogEntry('log-2', 'Test Goblin falls.', { actionType: 'KILL' }));
      await act(() => vi.advanceTimersByTimeAsync(2999));
      expect(logRequests).toBe(1);
      await act(() => vi.advanceTimersByTimeAsync(1));
      expect(await screen.findByText('(2 entries)')).toBeInTheDocument();
      expect(logRequests).toBe(2);

      await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
      expect(screen.getByText('Test Goblin falls.')).toBeInTheDocument();

      expect(vi.getTimerCount()).toBe(1);
      unmount();
      expect(vi.getTimerCount()).toBe(0);
    });
  });
});
