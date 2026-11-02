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

// Monsters as the Player View sends them (D157): a Health Status instead of HP, unless the DM shows exact HP.
// The server leaves out the bugbear while the DM hides it.
const HIDDEN_HP = { hpMax: null, hpCurrent: null, hpTemp: null };
const goblin = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { sortOrder: 1, ...HIDDEN_HP, healthStatus: 'UNHARMED', armourClass: 15, ...overrides });
const bugbear = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-bugbear', 'Hidden Bugbear', 'MONSTER', { sortOrder: 2, ...HIDDEN_HP, healthStatus: 'BLOODIED', ...overrides });
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
        HttpResponse.json(makeMockEncounter({ participants: [legolas(), gimli(), goblin()] })),
      ),
    );
  });

  it('lists the participants in the Player View, with a Health Status for monsters and HP for your own character', async () => {
    renderPage();
    expect(screen.getByText('Loading encounter...')).toBeInTheDocument();

    expect(await screen.findByRole('heading', { name: 'Goblin Ambush' })).toBeInTheDocument();
    expect(screen.getAllByText(NAMES).map(el => el.textContent)).toEqual(['Test Goblin', 'Gimli', 'Legolas']);
    expect(screen.getByText('You')).toBeInTheDocument();
    expect(screen.getByText('30/30')).toBeInTheDocument();
    expect(screen.getByText('Monster')).toBeInTheDocument();
    expect(screen.getByText('Unharmed')).toBeInTheDocument();
    expect(screen.getByText('Player')).toBeInTheDocument();
    expect(screen.queryByText(/7\/7/)).not.toBeInTheDocument();
    expect(await screen.findByText('Live')).toBeInTheDocument();
  });

  it('shows exact monster HP when the DM allows it', async () => {
    server.use(
      http.get('/api/encounters/:id', () =>
        HttpResponse.json(makeMockEncounter({ participants: [gimli(), goblin({ hpMax: 7, hpCurrent: 5, hpTemp: 0, healthStatus: null })] })),
      ),
    );
    renderPage();

    expect(await screen.findByText('5/7 HP')).toBeInTheDocument();
    expect(screen.queryByText('Unharmed')).not.toBeInTheDocument();
  });

  it('shows whatever the Player View sends, without filtering it again', async () => {
    // The server decides who players see; a flag on a participant it sent changes nothing here
    server.use(
      http.get('/api/encounters/:id', () =>
        HttpResponse.json(makeMockEncounter({ participants: [gimli(), goblin({ isVisibleToPlayers: false })] })),
      ),
    );
    renderPage();

    expect(await screen.findByText('Test Goblin')).toBeInTheDocument();
  });

  it('shows state pushed over the WebSocket, including the start of your turn', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Goblin Ambush' });
    await screen.findByText('Live');

    // The DM has revealed the bugbear, so the Player View now includes it
    act(() => MockStompClient.latest().emit('/topic/encounter/enc-001/state', makeMockEncounter({
      roundNumber: 2,
      participants: [goblin(), bugbear(), gimli({ hpCurrent: 18, isCurrentTurn: true }), legolas()],
    })));

    expect(screen.getByText('Round 2')).toBeInTheDocument();
    expect(screen.getByText('18/30')).toBeInTheDocument();
    expect(screen.getByText("It's your turn!")).toBeInTheDocument();
    expect(screen.getByText('Hidden Bugbear')).toBeInTheDocument();
    expect(screen.getByText('Bloodied')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Attack' })).toHaveLength(3);
  });

  it('subscribes to the Player View, never the DM View', async () => {
    renderPage();
    await screen.findByText('Live');

    expect(MockStompClient.latest().isSubscribed('/topic/encounter/enc-001/state')).toBe(true);
    expect(MockStompClient.latest().isSubscribed('/topic/encounter/enc-001/dm-state')).toBe(false);
  });

  it('offers the participants in the Player View as spell targets, with a Health Status for monsters', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Cast Spell' }));
    await user.click(screen.getByRole('button', { name: /Fire Bolt/ }));

    const dialog = screen.getByRole('dialog', { name: 'Cast Spell' });
    const goblinTarget = await within(dialog).findByRole('button', { name: /Test Goblin/ });
    expect(goblinTarget).toHaveTextContent('Unharmed');
    expect(goblinTarget).not.toHaveTextContent('HP');
    expect(within(dialog).getByRole('button', { name: /Legolas/ })).toHaveTextContent('25/25 HP');
    expect(within(dialog).queryByRole('button', { name: /Hidden Bugbear/ })).not.toBeInTheDocument();
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
