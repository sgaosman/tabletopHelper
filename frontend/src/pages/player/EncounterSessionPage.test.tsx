import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockLogEntry, makeMockParticipant } from '../../test/mocks/handlers';
import { MockStompClient } from '../../test/mocks/stomp';
import type { EncounterParticipant } from '../../types/encounter';
import PlayerEncounterSessionPage from './EncounterSessionPage';

const NAMES = /^(Test Goblin|Hidden Bugbear|Gimli|Legolas)$/;

const goblin = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { sortOrder: 1, hpMax: 7, hpCurrent: 7, armourClass: 15, ...overrides });
const bugbear = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-bugbear', 'Hidden Bugbear', 'MONSTER', { sortOrder: 2, hpMax: 27, hpCurrent: 27, isVisibleToPlayers: false, ...overrides });
// setupAuthToken signs in as user-001, so Gimli is the player's own character.
const gimli = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-gimli', 'Gimli', 'PLAYER', {
    sortOrder: 3, controlledByUserId: 'user-001',
    spellsKnown: JSON.stringify([{ name: 'Fire Bolt', level: 0 }]),
    ...overrides,
  });
const legolas = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-legolas', 'Legolas', 'PLAYER', { sortOrder: 4, hpMax: 25, hpCurrent: 25, controlledByUserId: 'user-002', ...overrides });

function renderPage() {
  window.history.pushState({}, '', '/player/encounter/enc-001/session');
  return render(
    <Routes>
      <Route path="/player/encounter/:encounterId/session" element={<PlayerEncounterSessionPage />} />
    </Routes>,
  );
}

describe('Player EncounterSessionPage', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(
      http.get('/api/encounters/:id', () =>
        HttpResponse.json(makeMockEncounter({ participants: [legolas(), gimli(), bugbear(), goblin()] })),
      ),
    );
  });

  it('lists the visible participants and shows HP only for your own character', async () => {
    renderPage();
    expect(screen.getByText('Loading encounter...')).toBeInTheDocument();

    expect(await screen.findByRole('heading', { name: 'Goblin Ambush' })).toBeInTheDocument();
    // R3 (D157): the server will leave hidden participants out of the Player View; today the page filters them
    expect(screen.getAllByText(NAMES).map(el => el.textContent)).toEqual(['Test Goblin', 'Gimli', 'Legolas']);
    expect(screen.getByText('You')).toBeInTheDocument();
    expect(screen.getByText('30/30')).toBeInTheDocument();
    // R3 (D157): monster rows gain a Health Status label, or exact HP if the DM turns that on
    expect(screen.getByText('Monster')).toBeInTheDocument();
    expect(screen.getByText('Player')).toBeInTheDocument();
    expect(screen.queryByText('7/7')).not.toBeInTheDocument();
    expect(await screen.findByText('Live')).toBeInTheDocument();
  });

  it('shows state pushed over the WebSocket, including the start of your turn', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Goblin Ambush' });
    await screen.findByText('Live');

    act(() => MockStompClient.latest().emit('/topic/encounter/enc-001/state', makeMockEncounter({
      roundNumber: 2,
      participants: [goblin(), bugbear({ isVisibleToPlayers: true }), gimli({ hpCurrent: 18, isCurrentTurn: true }), legolas()],
    })));

    expect(screen.getByText('Round 2')).toBeInTheDocument();
    expect(screen.getByText('18/30')).toBeInTheDocument();
    expect(screen.getByText("It's your turn!")).toBeInTheDocument();
    expect(screen.getByText('Hidden Bugbear')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Attack' })).toHaveLength(3);
  });

  it('offers hidden participants, with exact monster HP, as spell targets', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Cast Spell' }));
    await user.click(screen.getByRole('button', { name: /Fire Bolt/ }));

    // R3 (D157): the Player View will drop hidden participants and show monster HP as a Health Status
    const dialog = screen.getByRole('dialog', { name: 'Cast Spell' });
    expect(await within(dialog).findByRole('button', { name: /Hidden Bugbear/ })).toHaveTextContent('27/27 HP');
    expect(within(dialog).getByRole('button', { name: /Test Goblin/ })).toHaveTextContent('7/7 HP');
  });

  describe('combat log', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('polls the combat log every 3 seconds until the page closes', async () => {
      // R4 (D171): the combat log will arrive over the WebSocket instead of being polled
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      const entries = [makeMockLogEntry('log-1', 'Legolas hits Test Goblin for 4 damage.')];
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

      entries.push(makeMockLogEntry('log-2', 'Gimli casts Fire Bolt.', { actionType: 'SPELL_CAST' }));
      await act(() => vi.advanceTimersByTimeAsync(2999));
      expect(logRequests).toBe(1);
      await act(() => vi.advanceTimersByTimeAsync(1));
      expect(await screen.findByText('(2 entries)')).toBeInTheDocument();
      expect(logRequests).toBe(2);

      await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
      expect(screen.getByText('Gimli casts Fire Bolt.')).toBeInTheDocument();

      expect(vi.getTimerCount()).toBe(1);
      unmount();
      expect(vi.getTimerCount()).toBe(0);
    });
  });
});
