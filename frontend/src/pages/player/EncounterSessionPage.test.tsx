import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCondition, makeMockEncounter, makeMockLogEntry, makeMockParticipant } from '../../test/mocks/handlers';
import { MockStompClient } from '../../test/mocks/stomp';
import type { EncounterParticipant } from '../../types/encounter';
import PlayerEncounterSessionPage from './EncounterSessionPage';

const NAMES = /^(Test Goblin|Hidden Bugbear|Gimli|Legolas)$/;
const LOG_TOPIC = '/topic/encounter/enc-001/log';

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
    spellsKnown: [{ name: 'Fire Bolt', level: 0 }],
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

    expect(await screen.findByText('Round 2')).toBeInTheDocument();
    expect(screen.getByText('18/30')).toBeInTheDocument();
    expect(screen.getByText("It's your turn!")).toBeInTheDocument();
    expect(screen.getByText('Hidden Bugbear')).toBeInTheDocument();
    expect(screen.getByText('Bloodied')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Attack' })).toHaveLength(3);
  });

  it('subscribes to the Player View and the players\' combat log, never the DM\'s topics', async () => {
    renderPage();
    await screen.findByText('Live');

    expect(MockStompClient.latest().isSubscribed('/topic/encounter/enc-001/state')).toBe(true);
    expect(MockStompClient.latest().isSubscribed(LOG_TOPIC)).toBe(true);
    expect(MockStompClient.latest().isSubscribed('/topic/encounter/enc-001/dm-state')).toBe(false);
    expect(MockStompClient.latest().isSubscribed('/topic/encounter/enc-001/dm-log')).toBe(false);
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

  it('removes a condition from your character with one request and shows the state it answers with', async () => {
    let encounterRequests = 0;
    let removal: unknown;
    server.use(
      http.get('/api/encounters/:id', () => {
        encounterRequests += 1;
        return HttpResponse.json(makeMockEncounter({
          participants: [legolas(), gimli({ activeConditions: [makeMockCondition('prone')] }), goblin()],
        }));
      }),
      http.post('/api/encounters/:id/combat/condition/remove', async ({ request }) => {
        removal = await request.json();
        return HttpResponse.json(makeMockEncounter({ participants: [legolas(), gimli(), goblin()] }));
      }),
    );
    renderPage();

    await userEvent.setup().click(await screen.findByRole('button', { name: /prone/ }));

    await waitFor(() => expect(screen.queryByRole('button', { name: /prone/ })).not.toBeInTheDocument());
    expect(removal).toEqual({ targetId: 'part-gimli', condition: 'prone' });
    expect(encounterRequests).toBe(1);
  });

  describe('lifecycle (D166)', () => {
    // Gimli's turn, with a condition and a spell slot, so every action a player has would show
    const busyTable = (status: 'ACTIVE' | 'PAUSED' | 'COMPLETED') => makeMockEncounter({
      status,
      participants: [
        legolas(),
        gimli({
          isCurrentTurn: true,
          activeConditions: [makeMockCondition('prone')],
          spellSlotsCurrent: { 1: { max: 2, used: 0 } },
        }),
        goblin(),
      ],
    });
    const ACTIONS: (string | RegExp)[] = ['Add Condition', /Concentration/, 'Cast Spell', 'Attack', /prone/, /level 1 slot/];

    it('shows every action while the encounter is Active', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('ACTIVE'))));
      renderPage();
      await screen.findByRole('heading', { name: 'Goblin Ambush' });

      for (const name of ACTIONS) {
        expect(screen.getAllByRole('button', { name }).length).toBeGreaterThan(0);
      }
    });

    it.each(['PAUSED', 'COMPLETED'] as const)('hides every action while the encounter is %s, keeping the table readable', async status => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(busyTable(status))));
      renderPage();
      await screen.findByRole('heading', { name: 'Goblin Ambush' });

      for (const name of ACTIONS) {
        expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
      }
      expect(screen.getByText('prone')).toBeInTheDocument();
      expect(screen.getByText('2/2')).toBeInTheDocument();
    });

    it('hides an open action panel when the DM pauses', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('ACTIVE'))));
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: 'Add Condition' }));
      expect(screen.getByRole('button', { name: 'Apply' })).toBeInTheDocument();
      await screen.findByText('Live');

      act(() => MockStompClient.latest().emit('/topic/encounter/enc-001/state', busyTable('PAUSED')));

      await waitFor(() => expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument());
    });
  });

  describe('dying (D161)', () => {
    it('shows death saves only while Dying', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [gimli({ hpCurrent: 0, lifeState: 'DYING', deathSaveFailures: 1 }), goblin()],
      }))));
      renderPage();

      expect(await screen.findByText('You are dying!')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Roll Death Save' })).toBeInTheDocument();
      expect(screen.getByText('Dying')).toBeInTheDocument();
      // A character at 0 HP takes no actions
      expect(screen.queryByRole('button', { name: 'Add Condition' })).not.toBeInTheDocument();
    });

    it('a Stable or Dead character makes no death saves', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [gimli({ hpCurrent: 0, lifeState: 'STABLE' }), goblin({ lifeState: 'DEAD', healthStatus: 'DOWN' })],
      }))));
      renderPage();

      expect(await screen.findByText(/You are stable/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Roll Death Save' })).not.toBeInTheDocument();
      expect(screen.getByText('Stable')).toBeInTheDocument();
      expect(screen.getByText('Dead')).toBeInTheDocument();
    });

    it('attacks from within 5 feet unless the player says otherwise, and offers no forced critical', async () => {
      const attackRequests: unknown[] = [];
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [gimli({ isCurrentTurn: true }), goblin()],
        }))),
        http.post('/api/encounters/:id/combat/attack', async ({ request }) => {
          attackRequests.push(await request.json());
          return HttpResponse.json(makeMockEncounter({ participants: [gimli({ isCurrentTurn: true }), goblin()] }));
        }),
      );
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Attack' }));
      expect(screen.queryByRole('button', { name: 'Crit' })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Within 5 ft' }));
      await user.type(screen.getByPlaceholderText('+5'), '5');
      await user.type(screen.getByPlaceholderText('1d8+3'), '1d8+3');
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

      await waitFor(() => expect(attackRequests).toEqual([
        { targetId: 'part-goblin', attackBonus: 5, damageDice: '1d8+3', within5Ft: false },
      ]));
    });
  });

  describe('combat log', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('shows the players\' combat log the WebSocket sends after each change, without polling', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      let logRequests = 0;
      server.use(
        http.get('/api/encounters/:id/combat/log', () => {
          logRequests += 1;
          return HttpResponse.json([makeMockLogEntry('log-1', 'Legolas hits Test Goblin for 4 damage.')]);
        }),
      );
      renderPage();
      expect(await screen.findByText('(1 entries)')).toBeInTheDocument();
      await screen.findByText('Live');

      act(() => MockStompClient.latest().emit(LOG_TOPIC, [
        makeMockLogEntry('log-1', 'Legolas hits Test Goblin for 4 damage.'),
        makeMockLogEntry('log-2', 'Gimli casts Fire Bolt.', { actionType: 'SPELL_CAST' }),
      ]));

      expect(await screen.findByText('(2 entries)')).toBeInTheDocument();
      await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
      expect(screen.getByText('Gimli casts Fire Bolt.')).toBeInTheDocument();
      expect(logRequests).toBe(1);
      expect(vi.getTimerCount()).toBe(0);
    });

    it('shows a hidden monster\'s entries again when the log the DM\'s reveal sends includes them', async () => {
      server.use(
        http.get('/api/encounters/:id/combat/log', () =>
          HttpResponse.json([makeMockLogEntry('log-1', 'Legolas hits Test Goblin for 4 damage.')])),
      );
      renderPage();
      await screen.findByText('Live');
      await userEvent.setup().click(await screen.findByRole('button', { name: /Combat Log/ }));
      expect(screen.queryByText('Hidden Bugbear hits Gimli.')).not.toBeInTheDocument();

      act(() => MockStompClient.latest().emit(LOG_TOPIC, [
        makeMockLogEntry('log-0', 'Hidden Bugbear hits Gimli.', { actorId: 'part-bugbear', targetId: 'part-gimli' }),
        makeMockLogEntry('log-1', 'Legolas hits Test Goblin for 4 damage.'),
      ]));

      expect(await screen.findByText('Hidden Bugbear hits Gimli.')).toBeInTheDocument();
      expect(screen.getByText('(2 entries)')).toBeInTheDocument();
    });
  });

  describe('turns (D157, D162, D163)', () => {
    it('lists the turns players can see in the order the server numbers them, the Lair\'s included', async () => {
      const participants = [goblin({ sortOrder: 0 }), gimli({ sortOrder: 2 }), legolas({ sortOrder: 3 })];
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants,
        turnOrder: [
          { participantId: 'part-goblin', name: 'Test Goblin', initiative: 22, lair: false, skipped: false, current: false, tieGroup: null },
          { participantId: null, name: 'Lair Actions', initiative: 20, lair: true, skipped: false, current: true, tieGroup: null },
          { participantId: 'part-gimli', name: 'Gimli', initiative: 15, lair: false, skipped: false, current: false, tieGroup: null },
          { participantId: 'part-legolas', name: 'Legolas', initiative: 12, lair: false, skipped: false, current: false, tieGroup: null },
        ],
        currentTurnIndex: 1,
        monstersInLair: ['part-goblin'],
      }))));
      renderPage();

      await screen.findByRole('heading', { name: 'Goblin Ambush' });
      const list = screen.getByRole('list', { name: 'Initiative order' });
      // The Lair's row names the monster in its lair
      expect(within(list).getAllByText(/^(Test Goblin|Lair Actions|Gimli|Legolas)$/).map(el => el.textContent))
        .toEqual(['Test Goblin', 'Lair Actions', 'Test Goblin', 'Gimli', 'Legolas']);
      expect(list.querySelector('[aria-current="step"]')).toHaveTextContent('Lair Actions');
      expect(screen.queryByText('It\'s your turn!')).not.toBeInTheDocument();
    });

    it('marks no turn while one the player can\'t see is taken', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [goblin(), gimli()], currentTurnIndex: null,
      }))));
      renderPage();

      const list = await screen.findByRole('list', { name: 'Initiative order' });
      expect(list.querySelector('[aria-current]')).toBeNull();
    });

    it('prompts the death save due as a dying character\'s turn starts, and rolls it on the server', async () => {
      const requests: unknown[] = [];
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [gimli({ hpCurrent: 0, lifeState: 'DYING', deathSaveDue: true, isCurrentTurn: true }), goblin()],
        }))),
        http.post('/api/encounters/:id/combat/death-save', async ({ request }) => {
          requests.push(await request.json());
          return HttpResponse.json(makeMockEncounter({
            participants: [gimli({ hpCurrent: 0, lifeState: 'DYING', deathSaveSuccesses: 1, isCurrentTurn: true }), goblin()],
          }));
        }),
      );
      const user = userEvent.setup();
      renderPage();

      expect(await screen.findByText('Your turn has started: roll your death saving throw.')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Roll Death Save' }));

      await waitFor(() => expect(screen.queryByText(/Your turn has started/)).not.toBeInTheDocument());
      expect(requests).toEqual([{ participantId: 'part-gimli' }]);
    });

    it('prompts a Repeat Save the campaign leaves to the player, and rolls it on the server', async () => {
      const requests: unknown[] = [];
      const paralyzed = makeMockCondition('paralyzed', {
        duration: 10, endRule: { type: 'REPEAT_SAVE' }, repeatSave: { ability: 'CON', dc: 10, awaitingRoll: true },
      });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [gimli({ activeConditions: [paralyzed] }), goblin()],
        }))),
        http.post('/api/encounters/:id/combat/repeat-save', async ({ request }) => {
          requests.push(await request.json());
          return HttpResponse.json(makeMockEncounter({ participants: [gimli(), goblin()] }));
        }),
      );
      const user = userEvent.setup();
      renderPage();

      expect(await screen.findByText('Repeat your CON save (DC 10) to end paralyzed')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Roll CON save (DC 10) to end paralyzed' }));

      await waitFor(() => expect(screen.queryByText(/Repeat your CON save/)).not.toBeInTheDocument());
      expect(requests).toEqual([{ participantId: 'part-gimli', conditionId: 'cond-paralyzed' }]);
    });

    it('shows no Repeat Save prompt while the server rolls them', async () => {
      const paralyzed = makeMockCondition('paralyzed', { repeatSave: { ability: 'CON', dc: 10, awaitingRoll: false } });
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [gimli({ activeConditions: [paralyzed] }), goblin()],
      }))));
      renderPage();

      expect(await screen.findByText('paralyzed x')).toBeInTheDocument();
      expect(screen.queryByText(/Repeat your/)).not.toBeInTheDocument();
    });
  });
});
