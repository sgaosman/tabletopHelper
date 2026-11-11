import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import {
  makeMockAttackOption, makeMockCastSpellResponse, makeMockCondition, makeMockEncounter, makeMockLogEntry, makeMockParticipant,
  makeMockResourcePool, makeMockSpellEffect,
} from '../../test/mocks/handlers';
import { MockStompClient } from '../../test/mocks/stomp';
import type { Encounter, EncounterParticipant } from '../../types/encounter';
import PlayerEncounterSessionPage from './EncounterSessionPage';

const NAMES = /^(Test Goblin|Hidden Bugbear|Gimli|Legolas)$/;
const LOG_TOPIC = '/topic/encounter/enc-001/log';

// Monsters as the Player View sends them (D157): a Health Status instead of HP, unless the DM shows exact HP.
// The server leaves out the bugbear while the DM hides it.
// Nor a monster's turn economy, attacks or speed (D174).
const HIDDEN_HP = { hpMax: null, hpCurrent: null, hpTemp: null, turnEconomy: null, attacksPerAction: null, speed: {} };
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

  // The row's left edge carries the colour, as on the DM's page (R8 D)
  it('shows each player character in its class colour, and a monster in the monster colour', async () => {
    server.use(
      http.get('/api/encounters/:id', () =>
        HttpResponse.json(makeMockEncounter({ participants: [legolas({ characterClass: 'Ranger' }), goblin()] })),
      ),
    );
    renderPage();

    expect((await screen.findByText('Legolas')).closest('[style]')?.getAttribute('style')).toContain('var(--color-cls-ranger)');
    expect(screen.getByText('Test Goblin').closest('[style]')?.getAttribute('style')).toContain('var(--color-monster)');
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
    server.use(
      http.get('/api/encounters/:id', () =>
        HttpResponse.json(makeMockEncounter({ participants: [legolas(), gimli({ isCurrentTurn: true }), goblin()] }))),
      http.get('/api/encounters/:id/combat/spell-options', () =>
        HttpResponse.json([{ name: 'Fire Bolt', level: 0, castingTime: 'ACTION', sources: [{ source: 'NONE', levels: [0] }] }])),
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Cast Spell' }));
    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));

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
    const ACTIONS: (string | RegExp)[] = [
      'Add Condition', /Concentration/, 'Cast Spell', 'Attack', 'Actions', 'Reaction', /prone/, /level 1 slot/,
    ];

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
      expect(screen.getByRole('button', { name: 'Roll' })).toBeInTheDocument();
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
      expect(screen.queryByRole('button', { name: 'Roll' })).not.toBeInTheDocument();
      expect(screen.getByText('Stable')).toBeInTheDocument();
      expect(screen.getByText('Dead')).toBeInTheDocument();
    });
  });

  describe('weapon attacks (D164)', () => {
    // Gimli's turn, and every attack he rolls is recorded
    function attackTable(overrides: Partial<Encounter> = {}) {
      const requests: unknown[] = [];
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [gimli({ isCurrentTurn: true }), goblin()], ...overrides,
        }))),
        http.post('/api/encounters/:id/combat/attack', async ({ request }) => {
          requests.push(await request.json());
          return HttpResponse.json(makeMockEncounter({ participants: [gimli({ isCurrentTurn: true }), goblin()] }));
        }),
      );
      return requests;
    }

    it('attacks with a weapon the server works out, from within 5 feet unless the player says otherwise, with no Manual Modifiers when the campaign disallows them', async () => {
      const requests = attackTable({ allowPlayerManualModifiers: false });
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Attack' }));
      expect(await screen.findByText('+5 to hit · 1d8 + 3 slashing')).toBeInTheDocument();
      expect(screen.getByRole('option', { name: 'Unarmed Strike' })).toBeInTheDocument();
      expect(screen.queryByRole('option', { name: /enter the numbers/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Crit' })).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Extra +')).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Within 5 ft' }));
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

      await waitFor(() => expect(requests).toEqual([{ targetId: 'part-goblin', weapon: 'Longsword', within5Ft: false }]));
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Roll Attack' })).not.toBeInTheDocument());
    });

    it('offers Manual Modifiers while the campaign allows them: a forced critical, a magic bonus and an attack entered by hand', async () => {
      const requests = attackTable();
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Attack' }));
      const first = await screen.findByRole('group', { name: 'Attack 1' });
      await user.click(within(first).getByRole('button', { name: 'Crit' }));
      expect(within(first).getByRole('button', { name: 'Crit' })).toHaveAttribute('aria-pressed', 'true');
      // The darker gold, whose light text reads in every theme
      expect(within(first).getByRole('button', { name: 'Crit' })).toHaveClass('bg-cls-paladin', 'text-card');
      await user.type(within(first).getByLabelText('Extra +'), '1');
      await user.type(within(first).getByLabelText('Extra dmg'), '2d6');
      await user.selectOptions(within(first).getByLabelText('Its type'), 'fire');
      await user.click(screen.getByRole('button', { name: /Add attack/ }));
      const second = screen.getByRole('group', { name: 'Attack 2' });
      await user.selectOptions(within(second).getByLabelText('Weapon'), 'Other: enter the numbers');
      await user.type(within(second).getByPlaceholderText('+5'), '7');
      await user.type(within(second).getByPlaceholderText('1d8+3'), '1d8+4');
      await user.click(screen.getByRole('button', { name: 'Roll 2 Attacks' }));

      await waitFor(() => expect(requests).toEqual([
        { targetId: 'part-goblin', weapon: 'Longsword', within5Ft: true, forceCrit: true, extraAttackBonus: 1, extraDamageDice: '2d6', extraDamageType: 'fire' },
        { targetId: 'part-goblin', attackBonus: 7, damageDice: '1d8+4', within5Ft: true },
      ]));
    });

    it("offers Great Weapon Master's trade where the server allows it, with the traded numbers, and a ranged weapon attacks from beyond 5 feet", async () => {
      const requests = attackTable({ allowPlayerManualModifiers: false });
      server.use(http.get('/api/encounters/:id/combat/attack-options', () => HttpResponse.json([
        makeMockAttackOption('Greatsword', { damage: '2d6 + 3', powerAttack: { feat: 'Great Weapon Master', attackBonus: 0, damage: '2d6 + 13' } }),
        makeMockAttackOption('Longbow', { ability: 'DEX', proficient: false, attackBonus: 2, damage: '1d8 + 2', damageType: 'piercing', ranged: true }),
      ])));
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Attack' }));
      const first = await screen.findByRole('group', { name: 'Attack 1' });
      await user.click(within(first).getByRole('button', { name: 'Great Weapon Master: -5 to hit, +10 damage' }));
      expect(within(first).getByText('+0 to hit · 2d6 + 13 slashing')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: /Add attack/ }));
      const second = screen.getByRole('group', { name: 'Attack 2' });
      await user.selectOptions(within(second).getByLabelText('Weapon'), 'Longbow');
      expect(within(second).getByText(/\+2 to hit · 1d8 \+ 2 piercing/)).toHaveTextContent('(not proficient)');
      expect(within(second).getByRole('button', { name: 'Within 5 ft' })).toHaveAttribute('aria-pressed', 'false');
      expect(within(second).queryByRole('button', { name: /Great Weapon Master/ })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Roll 2 Attacks' }));

      await waitFor(() => expect(requests).toEqual([
        { targetId: 'part-goblin', weapon: 'Greatsword', within5Ft: true, powerAttack: true },
        { targetId: 'part-goblin', weapon: 'Longbow', within5Ft: false },
      ]));
    });

    it('shows why the server refused an attack, and keeps the form open', async () => {
      attackTable();
      server.use(http.post('/api/encounters/:id/combat/attack', () =>
        HttpResponse.json({ error: "This campaign doesn't allow players to enter Manual Modifiers" }, { status: 403 })));
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Attack' }));
      await user.click(await screen.findByRole('button', { name: 'Roll Attack' }));

      expect(await screen.findByRole('alert')).toHaveTextContent("This campaign doesn't allow players to enter Manual Modifiers");
      expect(screen.getByRole('button', { name: 'Roll Attack' })).toBeInTheDocument();
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
      await user.click(screen.getByRole('button', { name: 'Roll' }));

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

      expect(await screen.findByRole('button', { name: 'paralyzed' })).toBeInTheDocument();
      expect(screen.queryByText(/Repeat your/)).not.toBeInTheDocument();
    });
  });

  describe('turn economy (D174)', () => {
    it('shows a card of the player\'s own turn economy and no other participant\'s', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [legolas(), gimli({ isCurrentTurn: true, turnEconomy: { ...gimli().turnEconomy!, dashed: true } }), goblin()],
      }))));
      renderPage();

      expect(await screen.findByText('This Turn')).toBeInTheDocument();
      expect(screen.getByRole('group', { name: "Gimli's turn economy" })).toBeInTheDocument();
      expect(screen.getByText('Dashed')).toBeInTheDocument();
      expect(screen.getAllByRole('group', { name: /turn economy/ })).toHaveLength(1);
      expect(screen.queryByRole('group', { name: "Test Goblin's turn economy" })).not.toBeInTheDocument();
      expect(screen.queryByRole('group', { name: "Legolas's turn economy" })).not.toBeInTheDocument();
      // The player can't edit it
      expect(within(screen.getByRole('group', { name: "Gimli's turn economy" })).queryAllByRole('button')).toHaveLength(0);
    });

    it('opens the opportunity attack from the Reaction menu off the player\'s turn, with the reaction\'s cost', async () => {
      let attack: unknown;
      server.use(http.post('/api/encounters/:id/combat/attack', async ({ request }) => {
        attack = await request.json();
        return HttpResponse.json(makeMockEncounter({ participants: [legolas(), gimli(), goblin()] }));
      }));
      const user = userEvent.setup();
      renderPage();

      await screen.findByText('This Turn');
      // Off the player's turn the rows offer no attack and there is no Actions menu: only the Reaction menu
      expect(screen.queryByRole('button', { name: 'Attack' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Actions' })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Reaction' }));
      const menu = await screen.findByRole('dialog', { name: /Reactions/ });
      await user.selectOptions(within(menu).getByLabelText('Against'), 'Test Goblin');
      expect(await within(menu).findByLabelText('Cost')).toHaveValue('REACTION');
      await user.click(within(menu).getByRole('button', { name: 'Roll Attack' }));

      await waitFor(() => expect(attack).toMatchObject({ cost: 'REACTION', targetId: 'part-goblin' }));
    });

    it('offers no Reaction menu once the player\'s reaction is used', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [legolas(), gimli({ turnEconomy: { ...gimli().turnEconomy!, reactionUsed: true } }), goblin()],
      }))));
      renderPage();

      expect(await screen.findByText('This Turn')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Reaction' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Attack' })).not.toBeInTheDocument();
    });

    it('takes a standard action from the Actions menu on the player\'s own turn', async () => {
      let body: unknown;
      const table = makeMockEncounter({ participants: [legolas(), gimli({ isCurrentTurn: true }), goblin()] });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(table)),
        http.post('/api/encounters/:id/combat/standard-action', async ({ request }) => {
          body = await request.json();
          return HttpResponse.json(table);
        }),
      );
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Actions' }));
      const menu = await screen.findByRole('dialog', { name: /Actions/ });
      await user.click(within(menu).getByRole('button', { name: /^Dodge/ }));
      await user.click(within(menu).getByRole('button', { name: 'Take Dodge' }));

      await waitFor(() => expect(body).toEqual({ participantId: 'part-gimli', action: 'DODGE' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });

    it('uses the player\'s own pool at the table, showing what it costs', async () => {
      let body: unknown;
      const secondWind = makeMockResourcePool('class:fighter-second-wind', 'Second Wind', {
        maxUses: 1, currentUses: 1, spendActionType: 'BONUS_ACTION',
      });
      const table = makeMockEncounter({
        participants: [legolas(), gimli({ isCurrentTurn: true, resourcePoolsCurrent: [secondWind] }), goblin()],
      });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(table)),
        http.post('/api/encounters/:id/combat/use-pool', async ({ request }) => {
          body = await request.json();
          return HttpResponse.json(table);
        }),
      );
      const user = userEvent.setup();
      renderPage();

      const pools = await screen.findByRole('list', { name: "Gimli's resource pools" });
      expect(within(pools).getByText('Bonus action')).toBeInTheDocument();
      await user.click(within(pools).getByRole('button', { name: 'Use Second Wind' }));

      await waitFor(() => expect(body).toEqual({ participantId: 'part-gimli', poolId: 'class:fighter-second-wind' }));
    });
  });

  describe('Spell Effects (M15)', () => {
    const weapon = makeMockSpellEffect('Spiritual Weapon');
    const moonbeam = makeMockSpellEffect('Moonbeam', {
      concentration: true, directedCost: 'ACTION', directedResolves: false, triggers: ['ENTER', 'START_OF_TURN'],
    });

    it('moves the player\'s Moonbeam for an action at once, and opens Spiritual Weapon\'s attack, on their turn', async () => {
      let body: unknown;
      const table = makeMockEncounter({
        participants: [legolas(), gimli({ isCurrentTurn: true, concentrationSpell: 'Moonbeam', activeEffects: [moonbeam, weapon] }), goblin()],
      });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(table)),
        http.get('/api/reference/spells/targeting', () => new HttpResponse(null, { status: 404 })),
        http.post('/api/encounters/:id/combat/repeat-spell-effect', async ({ request }) => {
          body = await request.json();
          return HttpResponse.json(makeMockCastSpellResponse({ encounterState: table, spellName: 'Moonbeam' }));
        }),
      );
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'Move (action)' }));
      await waitFor(() => expect(body).toEqual({ targetIds: [], effectId: 'effect-moonbeam' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Use (bonus action)' }));
      expect(await screen.findByRole('dialog', { name: 'Spiritual Weapon — Repeat Effect' })).toBeInTheDocument();
    });

    it('greys the use off the player\'s turn but still lets them end it; another character\'s effects have no buttons', async () => {
      let body: unknown;
      const table = makeMockEncounter({
        participants: [legolas({ activeEffects: [makeMockSpellEffect('Flaming Sphere')] }), gimli({ activeEffects: [weapon] }), goblin()],
      });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(table)),
        http.post('/api/encounters/:id/combat/spell-effect/end', async ({ request }) => {
          body = await request.json();
          return HttpResponse.json(table);
        }),
      );
      const user = userEvent.setup();
      renderPage();

      const use = await screen.findByRole('button', { name: 'Use (bonus action)' });
      expect(use).toBeDisabled();
      expect(use).toHaveAttribute('title', 'Only on your turn');
      expect(within(screen.getByRole('group', { name: 'Flaming Sphere effect' })).queryByRole('button')).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'End Spiritual Weapon' }));

      await waitFor(() => expect(body).toEqual({ participantId: 'part-gimli', effectId: 'effect-spiritual-weapon' }));
    });

    // Legolas cast Dragon's Breath on Gimli, the player's character, who exhales it with his own action (XGE p.154)
    const breath = makeMockSpellEffect("Dragon's Breath", {
      slotLevel: 2, concentration: true, directedCost: 'ACTION', option: 'fire',
      affectedIds: ['part-gimli'], usableByIds: ['part-gimli'],
    });

    it("gives the creature Dragon's Breath touched a button to exhale it on its turn, sent as that creature (D200)", async () => {
      let request: { body: unknown; actorId: string | null } | undefined;
      const table = makeMockEncounter({
        participants: [legolas({ activeEffects: [breath] }), gimli({ isCurrentTurn: true }), goblin()],
      });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(table)),
        http.post('/api/encounters/:id/combat/repeat-spell-effect', async ({ request: sent }) => {
          request = { body: await sent.json(), actorId: new URL(sent.url).searchParams.get('actorId') };
          return HttpResponse.json(makeMockCastSpellResponse({ encounterState: table, spellName: "Dragon's Breath",
            resultSummary: 'Gimli breathes fire.' }));
        }),
      );
      const user = userEvent.setup();
      renderPage();

      expect(await screen.findByText("Legolas's spell, fire")).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: "Exhale Dragon's Breath" }));
      const dialog = await screen.findByRole('dialog', { name: "Dragon's Breath — Repeat Effect" });
      await user.click(await within(dialog).findByRole('button', { name: /^Test Goblin/ }));
      await user.click(within(dialog).getByRole('button', { name: 'Continue' }));
      await user.click(within(dialog).getByRole('button', { name: 'Repeat Effect' }));

      expect(await screen.findByText('Gimli breathes fire.')).toBeInTheDocument();
      expect(request).toEqual({ body: { targetIds: ['part-goblin'], advantage: null, effectId: "effect-dragon-s-breath" },
        actorId: 'part-gimli' });
    });

    it("greys the exhalation off the creature's turn with the reason, and on the caster's card names who uses it", async () => {
      const table = makeMockEncounter({
        participants: [legolas(), gimli({ activeEffects: [{ ...breath, affectedIds: ['part-legolas'], usableByIds: ['part-legolas'] }] }),
          goblin()],
      });
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(table)));
      renderPage();

      // Gimli cast it on Legolas, whose player exhales it: Gimli's own card can't use it
      const use = await screen.findByRole('button', { name: 'Use (action)' });
      expect(use).toBeDisabled();
      expect(use).toHaveAttribute('title', 'Only Legolas can use it, with its own action');
      expect(screen.queryByRole('button', { name: "Exhale Dragon's Breath" })).not.toBeInTheDocument();
    });

    it('greys the exhalation off the turn of the creature that exhales it', async () => {
      const table = makeMockEncounter({ participants: [legolas({ activeEffects: [breath] }), gimli(), goblin()] });
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(table)));
      renderPage();

      const exhale = await screen.findByRole('button', { name: "Exhale Dragon's Breath" });
      expect(exhale).toBeDisabled();
      expect(screen.getByText('Only on your turn')).toBeInTheDocument();
    });
  });

  it("prompts the WIS check Wrathful Smite's fear ends on, made with the character's action (D200)", async () => {
    const frightened = makeMockCondition('frightened', {
      sourceSpellName: 'Wrathful Smite',
      repeatSave: { ability: 'WIS', dc: 13, awaitingRoll: false, byAction: true, check: true },
    });
    server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
      participants: [gimli({ activeConditions: [frightened] }), goblin()],
    }))));
    renderPage();

    expect(await screen.findByText('Use your action on a WIS check (DC 13) to end frightened')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use the action: WIS check (DC 13) to end frightened' }))
      .toHaveTextContent('Roll check');
  });

  it("offers the escape from a monster's grapple, rolled by the server with the character's action (D203)", async () => {
    let sent: unknown = null;
    server.use(http.post('/api/encounters/:id/combat/action-save', async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(makeMockEncounter());
    }));
    const grappled = makeMockCondition('grappled', {
      sourceSpellName: 'Giant Constrictor Snake',
      sourceAction: 'Constrict',
      repeatSave: { ability: 'STR', dc: 16, awaitingRoll: false, byAction: true, check: true,
        skill: 'Athletics or Acrobatics', escape: true },
    });
    server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
      participants: [gimli({ activeConditions: [grappled] }), goblin()],
    }))));
    renderPage();

    expect(await screen.findByText('Use your action to escape the grapple of Giant Constrictor Snake: Athletics or '
      + 'Acrobatics check (DC 16)')).toBeInTheDocument();
    const escape = screen.getByRole('button', {
      name: 'Use the action to escape grappled: Athletics or Acrobatics check (DC 16)',
    });
    expect(escape).toHaveTextContent('Escape');
    await userEvent.setup().click(escape);
    await waitFor(() => expect(sent).toEqual({ participantId: expect.any(String), conditionId: 'cond-grappled' }));
  });
});

describe('Player EncounterSessionPage in Wild Shape (PHB p.66–67)', () => {
  const wolf = {
    monsterId: 'monster-wolf', beastName: 'Wolf', challengeRating: '1/4', hpMax: 11, armourClass: 13, strength: 12,
    dexterity: 15, constitution: 12, saves: {}, skills: {}, speed: { walk: 40 }, senses: null, damageResistances: [],
    damageImmunities: [], damageVulnerabilities: [], conditionImmunities: [], normalHpCurrent: 30, normalHpMax: 30,
    normalArmourClass: 16, druidLevel: 2,
  };

  beforeEach(() => {
    setupAuthToken();
    server.use(
      http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [goblin(), gimli({ isCurrentTurn: true, hpCurrent: 11, hpMax: 11, armourClass: 13, wildShape: wolf,
          activeConditions: [makeMockCondition('wild shape', { wildShape: wolf })] })],
      }))),
    );
  });

  it("offers the wolf's actions, and ends the Wild Shape with the pool's end option", async () => {
    const requests: unknown[] = [];
    server.use(http.post('/api/encounters/:id/combat/use-pool', async ({ request }) => {
      requests.push(await request.json());
      return HttpResponse.json(makeMockEncounter());
    }));
    renderPage();
    const user = userEvent.setup();

    expect(await screen.findByRole('status', { name: "Gimli's Wild Shape" })).toHaveTextContent('Own HP 30/30, AC 16');
    await user.click(screen.getByRole('button', { name: 'Wolf actions' }));
    expect(await screen.findByRole('button', { name: /Scimitar/ })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: "End Gimli's Wild Shape" }));
    await waitFor(() => expect(requests).toEqual([
      { participantId: 'part-gimli', poolId: 'class:druid-wild-shape', option: 'end' },
    ]));
  });

  it("offers a polymorphed character its form's actions, with no Wild Shape to end (D192)", async () => {
    const ape = { ...wolf, monsterId: 'monster-giant-ape', beastName: 'Giant Ape', spell: 'Polymorph', druidLevel: 0 };
    server.use(
      http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [goblin(), gimli({ isCurrentTurn: true, form: ape, formName: 'Giant Ape' })],
      }))),
    );
    renderPage();
    const user = userEvent.setup();

    expect(await screen.findByText('(as Giant Ape)')).toBeInTheDocument();
    expect(screen.getByRole('status', { name: "Gimli's Polymorph" })).toHaveTextContent('Own HP 30/30, AC 16');
    expect(screen.queryByRole('button', { name: "End Gimli's Wild Shape" })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Giant Ape actions' }));
    expect(await screen.findByRole('button', { name: /Scimitar/ })).toBeInTheDocument();
  });
});

describe('Player EncounterSessionPage with summoned creatures (D182)', () => {
  // Gimli's owl familiar, which the player controls too, listed before Gimli
  const owl = (overrides: Partial<EncounterParticipant> = {}) =>
    makeMockParticipant('part-owl', 'Owl', 'COMPANION', {
      sortOrder: 2, monsterId: 'mon-owl', controlledByUserId: 'user-001', hpMax: 1, hpCurrent: 1,
      summonedByParticipantId: 'part-gimli',
      summon: { spell: 'Find Familiar', kind: 'FAMILIAR', initiativeMode: 'INDIVIDUAL', cannotAttack: true },
      ...overrides,
    });
  const skeleton = (overrides: Partial<EncounterParticipant> = {}) =>
    makeMockParticipant('part-skeleton', 'Skeleton', 'COMPANION', {
      sortOrder: 4, monsterId: 'mon-skeleton', controlledByUserId: 'user-001', summonedByParticipantId: 'part-gimli',
      summon: { spell: 'Animate Dead', kind: 'ANIMATE_DEAD', initiativeMode: 'GROUP', needsCommand: true },
      ...overrides,
    });

  function serve(...participants: EncounterParticipant[]) {
    server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({ participants }))));
  }

  beforeEach(() => {
    setupAuthToken();
  });

  it("takes the player character as the player's own, not a creature it summoned", async () => {
    serve(goblin(), owl(), gimli());
    renderPage();

    const you = await screen.findByText('You');
    expect(you.closest('[style]')).toHaveTextContent('Gimli');
    expect(screen.getAllByText('You')).toHaveLength(1);
    expect(screen.getByText('Summoned by Gimli (Find Familiar)')).toBeInTheDocument();
    // The self-actions are Gimli's: he knows Fire Bolt
    expect(screen.getByRole('button', { name: /Cast Spell/ })).toBeInTheDocument();
  });

  it("shows the familiar's turn economy and actions on its turn, without its attacks (PHB p.240)", async () => {
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [
        { name: 'Talons', deliveryMethod: 'ATTACK_ROLL', attackBonus: 3 },
        { name: 'Flyby Help', deliveryMethod: 'NONE', automatable: false },
      ],
    })));
    serve(goblin(), owl({ isCurrentTurn: true }), gimli());
    renderPage();

    const turn = await screen.findByRole('region', { name: "Owl's turn" });
    expect(within(turn).getByText("It's Owl's turn")).toBeInTheDocument();
    expect(await within(turn).findByRole('button', { name: /Flyby Help/ })).toBeInTheDocument();
    expect(within(turn).queryByRole('button', { name: /Talons/ })).not.toBeInTheDocument();
    // Not the character's turn
    expect(screen.queryByText("It's your turn!")).not.toBeInTheDocument();
  });

  it("commands uncommanded undead with a bonus action on the caster's turn (PHB p.212)", async () => {
    const commands: unknown[] = [];
    const commanded = skeleton({ summon: { ...skeleton().summon, commandedRound: 1 } });
    server.use(http.post('/api/encounters/:id/combat/summons/command', async ({ request }) => {
      commands.push(await request.json());
      return HttpResponse.json(makeMockEncounter({ participants: [goblin(), gimli({ isCurrentTurn: true }), commanded] }));
    }));
    serve(goblin(), gimli({ isCurrentTurn: true }), skeleton());
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /Command Skeleton/ }));
    await waitFor(() => expect(commands).toEqual([{ casterParticipantId: 'part-gimli', participantIds: ['part-skeleton'] }]));
    await waitFor(() => expect(screen.queryByRole('button', { name: /Command Skeleton/ })).not.toBeInTheDocument());
  });

  it("offers a Pact of the Chain warlock's familiar attack on the warlock's turn, and none without the pact", async () => {
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [{ name: 'Talons', deliveryMethod: 'ATTACK_ROLL', attackBonus: 3 }],
    })));
    serve(goblin(), owl(), gimli({ isCurrentTurn: true, pactOfTheChain: true }));
    renderPage();

    const pact = await screen.findByRole('region', { name: "Owl's attack (Pact of the Chain)" });
    expect(await within(pact).findByRole('button', { name: /Talons/ })).toBeInTheDocument();
  });

  it('offers no familiar attack to a caster without Pact of the Chain', async () => {
    serve(goblin(), owl(), gimli({ isCurrentTurn: true }));
    renderPage();

    expect(await screen.findByRole('button', { name: /Cast Spell/ })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: "Owl's attack (Pact of the Chain)" })).not.toBeInTheDocument();
  });

  it('tells the player an uncommanded skeleton only defends itself on its turn', async () => {
    serve(goblin(), gimli(), skeleton({ isCurrentTurn: true }));
    renderPage();

    const turn = await screen.findByRole('region', { name: "Skeleton's turn" });
    expect(within(turn).getByRole('note')).toHaveTextContent('Skeleton has no command, so it only defends itself');
  });
});
