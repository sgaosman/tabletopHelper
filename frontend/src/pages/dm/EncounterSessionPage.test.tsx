import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCondition, makeMockEncounter, makeMockLogEntry, makeMockParticipant } from '../../test/mocks/handlers';
import { MockStompClient } from '../../test/mocks/stomp';
import type { Encounter, EncounterParticipant, TurnEntry } from '../../types/encounter';
import EncounterSessionPage from './EncounterSessionPage';

const NAMES = /^(Test Goblin|Gimli|Hidden Bugbear)$/;
const DM_STATE_TOPIC = '/topic/encounter/enc-001/dm-state';
const DM_LOG_TOPIC = '/topic/encounter/enc-001/dm-log';

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
      participants: [gimli({ isCurrentTurn: true }), goblin({ hpCurrent: 0, lifeState: 'DEAD' }), bugbear()],
    })));

    expect(await screen.findByText('Round 2 · 2/3 alive')).toBeInTheDocument();
    expect(screen.getByText('0/7')).toBeInTheDocument();
  });

  it('subscribes to the DM View and the whole combat log rather than the players\' topics', async () => {
    await renderLoaded();

    expect(MockStompClient.latest().isSubscribed(DM_STATE_TOPIC)).toBe(true);
    expect(MockStompClient.latest().isSubscribed(DM_LOG_TOPIC)).toBe(true);
    expect(MockStompClient.latest().isSubscribed('/topic/encounter/enc-001/state')).toBe(false);
    expect(MockStompClient.latest().isSubscribed('/topic/encounter/enc-001/log')).toBe(false);
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

  it('deals damage to the chosen participant and shows the state the server answers with, without fetching it again', async () => {
    let encounterRequests = 0;
    let damageRequest: unknown;
    server.use(
      http.get('/api/encounters/:id', () => {
        encounterRequests += 1;
        return HttpResponse.json(encounterWith(7));
      }),
      http.post('/api/encounters/:id/combat/damage', async ({ request }) => {
        damageRequest = await request.json();
        return HttpResponse.json(encounterWith(2));
      }),
    );
    const user = userEvent.setup();
    await renderLoaded();

    await user.click(screen.getAllByRole('button', { name: 'Deal damage' })[0]);
    expect(screen.getByRole('heading', { name: 'Damage Test Goblin' })).toBeInTheDocument();
    await user.type(screen.getByRole('spinbutton'), '5');
    await user.selectOptions(screen.getByRole('combobox'), 'fire');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(await screen.findByText('2/7')).toBeInTheDocument();
    expect(damageRequest).toEqual({ targetId: 'part-goblin', amount: 5, damageType: 'fire' });
    expect(encounterRequests).toBe(1);
  });

  it('advances the turn with one request and shows the state it answers with', async () => {
    let encounterRequests = 0;
    server.use(
      http.get('/api/encounters/:id', () => {
        encounterRequests += 1;
        return HttpResponse.json(encounterWith());
      }),
      http.post('/api/encounters/:id/combat/turn/next', () =>
        HttpResponse.json(makeMockEncounter({ roundNumber: 2, participants: [gimli(), goblin({ isCurrentTurn: true }), bugbear()] }))),
    );
    const user = userEvent.setup();
    await renderLoaded();

    await user.click(screen.getByRole('button', { name: /Next Turn/ }));

    expect(await screen.findByText('Round 2 · 3/3 alive')).toBeInTheDocument();
    expect(encounterRequests).toBe(1);
  });

  describe('combat log', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('shows the whole combat log the WebSocket sends after each change, without polling', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      let logRequests = 0;
      server.use(
        http.get('/api/encounters/:id/combat/log', () => {
          logRequests += 1;
          return HttpResponse.json([makeMockLogEntry('log-1', 'Gimli hits Test Goblin for 5 damage.')]);
        }),
      );
      await renderLoaded();
      expect(await screen.findByText('(1 entries)')).toBeInTheDocument();

      // The DM's log keeps the hidden bugbear's entries (D157)
      act(() => MockStompClient.latest().emit(DM_LOG_TOPIC, [
        makeMockLogEntry('log-1', 'Gimli hits Test Goblin for 5 damage.'),
        makeMockLogEntry('log-2', 'Hidden Bugbear hits Gimli.', { actorId: 'part-bugbear', targetId: 'part-gimli' }),
        makeMockLogEntry('log-3', 'Test Goblin falls.', { actionType: 'KILL' }),
      ]));

      expect(await screen.findByText('(3 entries)')).toBeInTheDocument();
      await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
      expect(screen.getByText('Hidden Bugbear hits Gimli.')).toBeInTheDocument();
      expect(screen.getByText('Test Goblin falls.')).toBeInTheDocument();
      expect(logRequests).toBe(1);
      expect(vi.getTimerCount()).toBe(0);
    });

    it('uses Legendary Resistance from the log with one request, and the log the server sends next resolves it', async () => {
      const unresolved = makeMockLogEntry('log-lr', 'Gimli\'s Hold Person paralyzes Test Goblin.', {
        legendaryResistanceEligible: true, lrResolved: false, lrTargetId: 'part-goblin',
        lrSuccessDamage: 0, lrConditionIds: ['cond-paralyzed'],
      });
      let logRequests = 0;
      let resistance: unknown;
      server.use(
        http.get('/api/encounters/:id/combat/log', () => {
          logRequests += 1;
          return HttpResponse.json([unresolved]);
        }),
        http.post('/api/encounters/:id/combat/legendary-resistance', async ({ request }) => {
          resistance = await request.json();
          return HttpResponse.json(encounterWith());
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();
      await user.click(await screen.findByRole('button', { name: /Combat Log/ }));

      await user.click(await screen.findByRole('button', { name: 'Use Legendary Resistance' }));

      await waitFor(() => expect(resistance).toEqual({ combatLogId: 'log-lr' }));
      act(() => MockStompClient.latest().emit(DM_LOG_TOPIC, [{ ...unresolved, lrResolved: true }]));
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Use Legendary Resistance' })).not.toBeInTheDocument());
      expect(logRequests).toBe(1);
    });

    it('offers no Legendary Resistance on an entry logged before R5, which lacks the success outcome (D163)', async () => {
      server.use(
        http.get('/api/encounters/:id/combat/log', () => HttpResponse.json([
          makeMockLogEntry('log-old', 'Aboleth uses Enslave on Test Goblin: FAILED_SAVE', {
            legendaryResistanceEligible: true, lrResolved: false, lrTargetId: 'part-goblin',
            lrDamageDealt: 0, lrConditionsApplied: 'charmed',
          }),
        ])),
      );
      const user = userEvent.setup();
      await renderLoaded();
      await user.click(await screen.findByRole('button', { name: /Combat Log/ }));

      expect(await screen.findByText('Aboleth uses Enslave on Test Goblin: FAILED_SAVE')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Use Legendary Resistance' })).not.toBeInTheDocument();
    });
  });

  describe('dying and the DM\'s corrections (D161)', () => {
    it('shows Dying, Stable and Dead, with death save controls only while Dying', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [
          gimli({ lifeState: 'DYING', hpCurrent: 0, deathSaveFailures: 1 }),
          makeMockParticipant('part-aria', 'Aria', 'PLAYER', { sortOrder: 4, hpCurrent: 0, lifeState: 'STABLE' }),
          goblin({ hpCurrent: 0, lifeState: 'DEAD' }),
        ],
      }))));
      await renderLoaded();

      expect(screen.getByText('Dying')).toBeInTheDocument();
      expect(screen.getByText('Stable')).toBeInTheDocument();
      expect(screen.getByText('Dead')).toBeInTheDocument();
      // One set of death saves, Gimli's
      expect(screen.getAllByRole('button', { name: 'Roll' })).toHaveLength(1);
      expect(screen.getAllByText('Saves:')).toHaveLength(1);
      // The dead goblin can't be attacked, damaged or healed, only revived
      expect(screen.getAllByRole('button', { name: 'Attack roll' })).toHaveLength(2);
      expect(screen.getAllByRole('button', { name: 'Heal' })).toHaveLength(2);
      expect(screen.getAllByRole('button', { name: 'Set HP / revive' })).toHaveLength(3);
      expect(screen.queryByRole('button', { name: 'Resurrect' })).not.toBeInTheDocument();
    });

    it('revives with Set HP, sending the HP and any temporary HP', async () => {
      let setHpRequest: unknown;
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [gimli(), goblin({ hpCurrent: 0, lifeState: 'DEAD' })],
        }))),
        http.post('/api/encounters/:id/combat/hp', async ({ request }) => {
          setHpRequest = await request.json();
          return HttpResponse.json(makeMockEncounter({ participants: [gimli(), goblin({ hpCurrent: 4 })] }));
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getAllByRole('button', { name: 'Set HP / revive' })[0]);
      expect(screen.getByRole('heading', { name: 'Set HP / revive Test Goblin' })).toBeInTheDocument();
      await user.type(screen.getByLabelText('HP (of 7)'), '4');
      await user.type(screen.getByLabelText('Temp HP'), '2');
      await user.click(screen.getByRole('button', { name: 'Apply' }));

      expect(await screen.findByText('4/7')).toBeInTheDocument();
      expect(setHpRequest).toEqual({ targetId: 'part-goblin', hpCurrent: 4, hpTemp: 2 });
    });

    it('attacks from within 5 feet unless the DM says otherwise', async () => {
      const attackRequests: unknown[] = [];
      server.use(http.post('/api/encounters/:id/combat/attack', async ({ request }) => {
        attackRequests.push(await request.json());
        return HttpResponse.json(encounterWith());
      }));
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getAllByRole('button', { name: 'Attack roll' })[0]);
      const within = screen.getByRole('button', { name: 'Within 5 ft' });
      expect(within).toHaveAttribute('aria-pressed', 'true');
      await user.type(screen.getByPlaceholderText('+5'), '4');
      await user.type(screen.getByPlaceholderText('1d8+3'), '1d6');
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));
      await waitFor(() => expect(attackRequests).toHaveLength(1));

      await user.click(screen.getAllByRole('button', { name: 'Attack roll' })[0]);
      await user.click(screen.getByRole('button', { name: 'Within 5 ft' }));
      await user.type(screen.getByPlaceholderText('+5'), '4');
      await user.type(screen.getByPlaceholderText('1d8+3'), '1d6');
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

      await waitFor(() => expect(attackRequests).toHaveLength(2));
      expect(attackRequests).toEqual([
        { targetId: 'part-goblin', attackBonus: 4, damageDice: '1d6', within5Ft: true },
        { targetId: 'part-goblin', attackBonus: 4, damageDice: '1d6', within5Ft: false },
      ]);
    });
  });

  describe('lifecycle (D166)', () => {
    const PAUSED_NOTICE = 'Paused — resume to continue';

    // A downed Gimli, a prone and concentrating goblin, and a bugbear with spell slots, so every kind of
    // combat control has something to show for
    const busyTable = (status: Encounter['status']) => makeMockEncounter({
      status,
      participants: [
        gimli({ lifeState: 'DYING', hpCurrent: 0, deathSaveFailures: 1 }),
        goblin({
          activeConditions: [makeMockCondition('prone')],
          concentrationSpell: 'Bless',
        }),
        bugbear({ spellSlotsCurrent: { 1: { max: 3, used: 1 } } }),
      ],
    });

    const COMBAT_CONTROLS: (string | RegExp)[] = [
      'Previous turn', /Next Turn/, 'Attack roll', 'Deal damage', 'Heal', 'Set HP / revive', 'Add condition',
      'Set concentration', 'Cast spell', 'Monster actions', 'Repeat spell effect', 'Roll', /level 1 slot/, /prone/,
    ];

    function expectNoCombatControls() {
      for (const name of COMBAT_CONTROLS) {
        expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
      }
    }

    it('shows every combat control while the encounter is Active', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('ACTIVE'))));
      await renderLoaded();

      for (const name of COMBAT_CONTROLS) {
        expect(screen.getAllByRole('button', { name }).length).toBeGreaterThan(0);
      }
      expect(screen.queryByText(PAUSED_NOTICE)).not.toBeInTheDocument();
    });

    it('while Paused, hides every combat control, keeps the table readable and says to resume', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('PAUSED'))));
      await renderLoaded();

      expect(screen.getByText(PAUSED_NOTICE)).toBeInTheDocument();
      expectNoCombatControls();
      expect(screen.getByRole('button', { name: /Resume/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /End/ })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /Pause/ })).not.toBeInTheDocument();
      // Conditions, concentration and spell slots still show, read-only
      expect(screen.getByText('prone')).toBeInTheDocument();
      expect(screen.getByText('Concentrating: Bless')).toBeInTheDocument();
      expect(screen.getByText('2 of 3 left')).toBeInTheDocument();
      // Hiding a monster from players is not combat, so it stays available
      expect(screen.getByRole('button', { name: 'Show to players' })).toBeInTheDocument();
    });

    it('pausing hides the open action panel along with the combat controls', async () => {
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('ACTIVE'))),
        http.post('/api/encounters/:id/pause', () => HttpResponse.json(busyTable('PAUSED'))),
      );
      const user = userEvent.setup();
      await renderLoaded();
      await user.click(screen.getAllByRole('button', { name: 'Deal damage' })[0]);
      expect(screen.getByRole('heading', { name: 'Damage Test Goblin' })).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /Pause/ }));

      expect(await screen.findByText(PAUSED_NOTICE)).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: 'Damage Test Goblin' })).not.toBeInTheDocument();
      expectNoCombatControls();
    });

    it('a pause pushed over the WebSocket, as from another DM tab, hides the combat controls', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('ACTIVE'))));
      await renderLoaded();

      act(() => MockStompClient.latest().emit(DM_STATE_TOPIC, busyTable('PAUSED')));

      expect(await screen.findByText(PAUSED_NOTICE)).toBeInTheDocument();
      expectNoCombatControls();
    });

    it('resuming brings the combat controls back', async () => {
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('PAUSED'))),
        http.post('/api/encounters/:id/resume', () => HttpResponse.json(busyTable('ACTIVE'))),
      );
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: /Resume/ }));

      expect(await screen.findByRole('button', { name: /Next Turn/ })).toBeInTheDocument();
      expect(screen.queryByText(PAUSED_NOTICE)).not.toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: 'Deal damage' })).toHaveLength(3);
    });

    it('offers Legendary Resistance from the log only while the encounter is Active', async () => {
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('PAUSED'))),
        http.get('/api/encounters/:id/combat/log', () => HttpResponse.json([
          makeMockLogEntry('log-lr', 'Gimli\'s Hold Person paralyzes Test Goblin.', {
            legendaryResistanceEligible: true, lrResolved: false, lrTargetId: 'part-goblin',
            lrSuccessDamage: 0, lrConditionIds: ['cond-paralyzed'],
          }),
        ])),
      );
      const user = userEvent.setup();
      await renderLoaded();
      expect(await screen.findByText('(1 entries)')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /Combat Log/ }));

      expect(screen.getByText('Gimli\'s Hold Person paralyzes Test Goblin.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Use Legendary Resistance' })).not.toBeInTheDocument();
    });

    it('a Completed encounter shows no combat controls and no pause, resume or end', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(busyTable('COMPLETED'))));
      await renderLoaded();

      expect(screen.getByText('Complete')).toBeInTheDocument();
      expectNoCombatControls();
      for (const name of [/Pause/, /Resume/, /End/]) {
        expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
      }
      expect(screen.queryByText(PAUSED_NOTICE)).not.toBeInTheDocument();
    });
  });

  describe('turn order (D163)', () => {
    const dragon = (overrides: Partial<EncounterParticipant> = {}) =>
      makeMockParticipant('part-dragon', 'Red Dragon', 'MONSTER', { sortOrder: 0, initiative: 22, hasLairActions: true, ...overrides });
    const lairTurn = (current: boolean): TurnEntry =>
      ({ participantId: null, name: 'Lair Actions', initiative: 20, lair: true, skipped: false, current, tieGroup: null });
    const turnOf = (p: EncounterParticipant, overrides: Partial<TurnEntry> = {}): TurnEntry =>
      ({ participantId: p.id, name: p.displayName, initiative: p.initiative ?? null, lair: false, skipped: false, current: false, tieGroup: null, ...overrides });

    // The dragon fights in its lair, and the Lair's turn on 20 comes after the dragon's 22
    function lairFight(onLairTurn: boolean): Encounter {
      const participants = [dragon(), goblin({ sortOrder: 2 }), gimli({ sortOrder: 3 })];
      return makeMockEncounter({
        participants,
        turnOrder: [turnOf(participants[0]), lairTurn(onLairTurn), turnOf(participants[1]), turnOf(participants[2])],
        monstersInLair: ['part-dragon'],
      });
    }

    it('lists the computed order with the Lair\'s turn, and marks the current turn', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(lairFight(true))));
      await renderLoaded();

      expect(screen.getAllByText(/^(Lair Actions|Test Goblin|Gimli)$/).map(el => el.textContent))
        .toEqual(['Lair Actions', 'Test Goblin', 'Gimli']);
      const current = screen.getByRole('list', { name: 'Initiative order' }).querySelector('[aria-current="step"]');
      expect(current).toHaveTextContent('Lair Actions');
      expect(current).toHaveTextContent('Red Dragon');
    });

    it('puts a monster with lair actions in its lair, and offers no switch to one without', async () => {
      let requested: unknown;
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [gimli(), goblin({ hasLairActions: true }), bugbear({ hasLairActions: false })],
        }))),
        http.post('/api/encounters/:id/combat/lair-status', async ({ request }) => {
          requested = await request.json();
          return HttpResponse.json(makeMockEncounter({
            participants: [gimli(), goblin({ hasLairActions: true }), bugbear({ hasLairActions: false })],
            monstersInLair: ['part-goblin'],
          }));
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();

      const toggle = screen.getByRole('button', { name: /In lair/ });
      expect(toggle).toHaveAttribute('aria-pressed', 'false');
      await user.click(toggle);

      await waitFor(() => expect(screen.getByRole('button', { name: /In lair/ })).toHaveAttribute('aria-pressed', 'true'));
      expect(requested).toEqual({ monsterIds: ['part-goblin'] });
      expect(screen.getAllByRole('button', { name: /In lair/ })).toHaveLength(1);
    });

    it('reorders a tie on initiative and DEX with the arrows', async () => {
      let requested: unknown;
      const participants = [goblin({ initiative: 12 }), gimli({ initiative: 12 }), bugbear()];
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants,
          turnOrder: [turnOf(participants[0], { tieGroup: 0, current: true }), turnOf(participants[1], { tieGroup: 0 }), turnOf(participants[2])],
        }))),
        http.put('/api/encounters/:id/initiatives/tie-order', async ({ request }) => {
          requested = await request.json();
          return HttpResponse.json(makeMockEncounter({ participants }));
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();

      expect(screen.getByRole('button', { name: 'Move Test Goblin up the tie' })).toBeDisabled();
      expect(screen.queryByRole('button', { name: 'Move Hidden Bugbear up the tie' })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Move Gimli up the tie' }));

      await waitFor(() => expect(requested).toEqual({ participantIds: ['part-gimli', 'part-goblin'] }));
    });

    it('shows why the turn could not move on', async () => {
      server.use(http.post('/api/encounters/:id/combat/turn/next', () =>
        HttpResponse.json({ error: 'Nobody in the turn order can take a turn' }, { status: 409 })));
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: /Next Turn/ }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Nobody in the turn order can take a turn');
    });

    it('holds back legendary actions on the monster\'s own turn, and lair actions outside the Lair\'s', async () => {
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [goblin({ isCurrentTurn: true }), gimli()],
        }))),
        http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
          actions: [{ name: 'Scimitar', attackBonus: 4, deliveryMethod: 'MELEE_WEAPON', automatable: true,
            effects: [{ effectType: 'DAMAGE', damageDice: '1d6+2', damageType: 'slashing' }] }],
          legendaryActions: [{ name: 'Tail Attack', cost: 1, deliveryMethod: 'ATTACK_ROLL', attackBonus: 4,
            effects: [{ effectType: 'DAMAGE', damageDice: '1d8', damageType: 'bludgeoning' }] }],
          lairActions: [{ name: 'Tremor', deliveryMethod: 'SAVING_THROW', saveDC: 15, saveAbility: 'DEX',
            effects: [{ effectType: 'CONDITION', condition: 'prone' }], description: 'The ground shakes.' }],
          resourcePools: { 'monster:legendary-actions': { currentUses: 3, maxUses: 3, displayName: 'Legendary Actions' } },
        })),
      );
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Monster actions' }));
      await user.click(await screen.findByRole('button', { name: 'Legendary (3)' }));
      expect(screen.getByRole('button', { name: /Tail Attack/ })).toBeDisabled();
      expect(screen.getByText(/Legendary actions are used on other creatures' turns/)).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Lair (1)' }));
      expect(screen.getByText('Test Goblin isn\'t fighting in its lair.')).toBeInTheDocument();
      expect(screen.getByText('Tremor').closest('[aria-disabled]')).toHaveAttribute('aria-disabled', 'true');
    });

    it('shows a death save due, and rolls a Repeat Save waiting on a player', async () => {
      let requested: unknown;
      const paralyzed = makeMockCondition('paralyzed', { repeatSave: { ability: 'CON', dc: 10, awaitingRoll: true }, endRule: { type: 'REPEAT_SAVE' } });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [goblin(), gimli({ hpCurrent: 0, lifeState: 'DYING', deathSaveDue: true, activeConditions: [paralyzed] })],
        }))),
        http.post('/api/encounters/:id/combat/repeat-save', async ({ request }) => {
          requested = await request.json();
          return HttpResponse.json(makeMockEncounter({ participants: [goblin(), gimli({ hpCurrent: 0, lifeState: 'DYING' })] }));
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();

      expect(screen.getByText('Death save due')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Roll CON save' }));

      await waitFor(() => expect(requested).toEqual({ participantId: 'part-gimli', conditionId: 'cond-paralyzed' }));
      expect(await screen.findByText('Gimli')).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByText('Death save due')).not.toBeInTheDocument());
    });
  });
});
