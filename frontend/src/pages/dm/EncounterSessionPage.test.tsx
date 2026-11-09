import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import {
  makeMockCastSpellResponse, makeMockCondition, makeMockEncounter, makeMockLogEntry, makeMockParticipant,
  makeMockResourcePool, makeMockSpellEffect,
} from '../../test/mocks/handlers';
import { MockStompClient } from '../../test/mocks/stomp';
import type { MonsterActionResponse, MonsterActionTemplate, MultiattackOption } from '../../types/combat';
import type { CombatResultPreview, Encounter, EncounterParticipant, TurnEntry } from '../../types/encounter';
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

  describe('undo (M14)', () => {
    const UNDO = {
      commandId: 'cmd-2', kind: 'DAMAGE', summary: 'DM deals 3 damage to Test Goblin', entryCount: 1, stepsAvailable: 2,
      recent: [
        { commandId: 'cmd-2', kind: 'DAMAGE', summary: 'DM deals 3 damage to Test Goblin', entryCount: 1 },
        { commandId: 'cmd-1', kind: 'ATTACK', summary: 'Gimli hits Test Goblin', entryCount: 1 },
      ],
    };
    const LOG = [
      makeMockLogEntry('log-0', 'An old entry from before undo.'),
      makeMockLogEntry('log-1', 'Gimli hits Test Goblin', { actionType: 'ATTACK', commandId: 'cmd-1' }),
      makeMockLogEntry('log-2', 'DM deals 3 damage to Test Goblin', { commandId: 'cmd-2' }),
      makeMockLogEntry('log-x', 'DM deals 1 damage to Gimli', { commandId: 'cmd-x', undone: true }),
    ];

    it('has nothing to undo until the server offers it', async () => {
      await renderLoaded();

      expect(screen.getByRole('button', { name: 'Nothing to undo' })).toBeDisabled();
    });

    it('asks first, listing what will be undone, then undoes the newest action with one request', async () => {
      let body: unknown;
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json({ ...encounterWith(), undo: UNDO })),
        http.get('/api/encounters/:id/combat/log', () => HttpResponse.json(LOG)),
        http.post('/api/encounters/:id/combat/undo', async ({ request }) => {
          body = await request.json();
          return HttpResponse.json(encounterWith());
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Undo: DM deals 3 damage to Test Goblin' }));
      const dialog = await screen.findByRole('dialog', { name: 'Undo the last action?' });
      expect(within(dialog).getByText(/rolled fresh/)).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Undo' }));

      await waitFor(() => expect(body).toEqual({ expectedCommandId: 'cmd-2', steps: 1 }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });

    it('offers "Undo to here" on the first entry of each action undo can still take, dimming undone entries', async () => {
      let body: unknown;
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json({ ...encounterWith(), undo: UNDO })),
        http.get('/api/encounters/:id/combat/log', () => HttpResponse.json(LOG)),
        http.post('/api/encounters/:id/combat/undo', async ({ request }) => {
          body = await request.json();
          return HttpResponse.json(encounterWith());
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();
      await user.click(await screen.findByRole('button', { name: /Combat Log/ }));

      expect(await screen.findAllByRole('button', { name: 'Undo to here' })).toHaveLength(2);
      expect(screen.getByText('DM deals 1 damage to Gimli')).toHaveClass('line-through');
      await user.click(screen.getAllByRole('button', { name: 'Undo to here' })[0]);
      const dialog = await screen.findByRole('dialog', { name: 'Undo the last 2 actions?' });
      await user.click(within(dialog).getByRole('button', { name: 'Undo' }));

      await waitFor(() => expect(body).toEqual({ expectedCommandId: 'cmd-2', steps: 2 }));
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
          activeEffects: [makeMockSpellEffect('Spiritual Weapon')],
        }),
        bugbear({ spellSlotsCurrent: { 1: { max: 3, used: 1 } } }),
      ],
    });

    // Removing a participant goes too: the server allows it only while the encounter is prepared or fought
    const COMBAT_CONTROLS: (string | RegExp)[] = [
      'Previous turn', /Next Turn/, 'Attack roll', 'Deal damage', 'Heal', 'Set HP / revive', 'Add condition',
      'Set concentration', 'Cast spell', 'Monster actions', 'Use (bonus action)', 'End Spiritual Weapon', 'Roll', /level 1 slot/, /prone/,
      /^Remove /,
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

  describe('removing a participant mid-fight (D166)', () => {
    it('removes a participant once the DM confirms, and shows the state the server answers with', async () => {
      const removed: string[] = [];
      server.use(http.delete('/api/encounters/:id/participants/:participantId', ({ params }) => {
        removed.push(params.participantId as string);
        return HttpResponse.json(makeMockEncounter({ participants: [gimli(), bugbear()] }));
      }));
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Remove Test Goblin' }));
      const dialog = screen.getByRole('dialog', { name: 'Remove Test Goblin?' });
      expect(dialog).toHaveTextContent('if it is their turn, the turn passes to the next creature');
      expect(removed).toEqual([]);
      await user.click(within(dialog).getByRole('button', { name: 'Remove' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(removed).toEqual(['part-goblin']);
      expect(screen.queryByText('Test Goblin')).not.toBeInTheDocument();
      expect(screen.getByText('Round 1 · 2/2 alive')).toBeInTheDocument();
    });

    it('removes nobody when the DM cancels', async () => {
      const removed: string[] = [];
      server.use(http.delete('/api/encounters/:id/participants/:participantId', ({ params }) => {
        removed.push(params.participantId as string);
        return HttpResponse.json(makeMockEncounter({ participants: [goblin(), bugbear()] }));
      }));
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Remove Gimli' }));
      await user.click(within(screen.getByRole('dialog', { name: 'Remove Gimli?' })).getByRole('button', { name: 'Cancel' }));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByText('Gimli')).toBeInTheDocument();
      expect(removed).toEqual([]);
    });

    it('shows why the server refused, and keeps the dialog open', async () => {
      server.use(http.delete('/api/encounters/:id/participants/:participantId', () =>
        HttpResponse.json({ error: 'Participants can be removed only while the encounter is prepared or active' },
          { status: 409 })));
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Remove Test Goblin' }));
      const dialog = screen.getByRole('dialog', { name: 'Remove Test Goblin?' });
      await user.click(within(dialog).getByRole('button', { name: 'Remove' }));

      expect(await within(dialog).findByRole('alert'))
        .toHaveTextContent('Participants can be removed only while the encounter is prepared or active');
      expect(within(dialog).getByRole('button', { name: 'Remove' })).toBeEnabled();
    });

    it('says a player character leaving mid-fight takes its changes to its sheet; a monster takes nothing', async () => {
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Remove Gimli' }));
      expect(screen.getByRole('dialog', { name: 'Remove Gimli?' }))
        .toHaveTextContent('such as hit points, spell slots and pool uses, is written back to their character sheet now');
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
      await user.click(screen.getByRole('button', { name: 'Remove Test Goblin' }));

      expect(screen.getByRole('dialog', { name: 'Remove Test Goblin?' })).not.toHaveTextContent('character sheet');
    });
  });

  describe('ending (D166)', () => {
    const preview = [
      {
        characterId: 'char-001', name: 'Gimli', lifeState: 'ALIVE', hpMax: 30, hpBefore: 30, hpAfter: 12,
        hpTempBefore: 0, hpTempAfter: 4, deadBefore: false, deadAfter: false,
        slots: [{ slot: '1', max: 2, usedBefore: 0, usedAfter: 1 }],
        pools: [{ poolId: 'class:barbarian-rage', displayName: 'Rage', maxUses: 2, currentBefore: 2, currentAfter: 1 }],
      },
      {
        characterId: 'char-002', name: 'Bram', lifeState: 'DEAD', hpMax: 20, hpBefore: 20, hpAfter: 0,
        hpTempBefore: 0, hpTempAfter: 0, deadBefore: false, deadAfter: true, slots: [], pools: [],
      },
      {
        characterId: 'char-003', name: 'Cora', lifeState: 'STABLE', hpMax: 24, hpBefore: 0, hpAfter: 0,
        hpTempBefore: 0, hpTempAfter: 0, deadBefore: false, deadAfter: false, slots: [], pools: [],
      },
    ] satisfies CombatResultPreview[];

    // Answers the preview, and records each end's body
    function recordEnd() {
      const bodies: unknown[] = [];
      server.use(
        http.get('/api/encounters/:id/results', () => HttpResponse.json(preview)),
        http.post('/api/encounters/:id/end', async ({ request }) => {
          bodies.push(await request.json());
          return HttpResponse.json(makeMockEncounter({ status: 'COMPLETED', participants: [gimli(), goblin(), bugbear()] }));
        }),
      );
      return bodies;
    }

    async function openEndDialog(user: ReturnType<typeof userEvent.setup>) {
      await user.click(screen.getByRole('button', { name: /End/ }));
      return screen.getByRole('dialog', { name: 'End this encounter?' });
    }

    it("previews what each sheet takes, which the DM can't edit, and ends with the results applied by default", async () => {
      const bodies = recordEnd();
      const user = userEvent.setup();
      await renderLoaded();

      const dialog = await openEndDialog(user);
      const results = within(dialog).getByRole('region', { name: 'Results for each character' });

      expect(await within(results).findByText('HP 30 → 12 of 30')).toBeInTheDocument();
      expect(within(results).getByText('Temporary HP 0 → 4')).toBeInTheDocument();
      expect(within(results).getByText('Level 1 slots spent 0 → 1 of 2')).toBeInTheDocument();
      expect(within(results).getByText('Rage 2 → 1 of 2')).toBeInTheDocument();
      expect(within(results).getByText('Dies')).toBeInTheDocument();
      expect(within(results).getByText('Down: back on the sheet alive at 0 HP')).toBeInTheDocument();
      expect(within(results).getByText('No change to the sheet')).toBeInTheDocument();
      // The preview is read only: the box is the one control beside the buttons
      expect(within(dialog).queryAllByRole('textbox')).toHaveLength(0);
      expect(within(dialog).queryAllByRole('spinbutton')).toHaveLength(0);
      expect(within(dialog).getAllByRole('checkbox')).toHaveLength(1);
      expect(within(dialog).getByRole('checkbox', { name: /Apply results to character sheets/ })).toBeChecked();
      expect(bodies).toEqual([]);

      await user.click(within(dialog).getByRole('button', { name: 'End encounter' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(bodies).toEqual([{ applyResults: true }]);
      expect(screen.getByText('Complete')).toBeInTheDocument();
    });

    it('ends without writing anything back once the DM unticks the box, and the dimmed preview says none of it is', async () => {
      const bodies = recordEnd();
      const user = userEvent.setup();
      await renderLoaded();

      const dialog = await openEndDialog(user);
      const results = within(dialog).getByRole('region', { name: 'Results for each character' });
      await within(results).findByText('HP 30 → 12 of 30');
      expect(within(results).getByRole('heading', { name: 'What each sheet takes' })).toBeInTheDocument();
      expect(within(results).getAllByRole('list')[0]).not.toHaveClass('opacity-50');
      await user.click(within(dialog).getByRole('checkbox', { name: /Apply results to character sheets/ }));

      expect(within(results).getByRole('heading', { name: 'None of this is written back' })).toBeInTheDocument();
      expect(within(results).queryByRole('heading', { name: 'What each sheet takes' })).not.toBeInTheDocument();
      expect(within(results).getAllByRole('list')[0]).toHaveClass('opacity-50');
      expect(within(dialog).getByText('Nothing is written back: every sheet stays as it is, pool uses included.'))
        .toBeInTheDocument();

      // Ticking the box again puts the heading back
      await user.click(within(dialog).getByRole('checkbox', { name: /Apply results to character sheets/ }));
      expect(within(results).getByRole('heading', { name: 'What each sheet takes' })).toBeInTheDocument();
      await user.click(within(dialog).getByRole('checkbox', { name: /Apply results to character sheets/ }));
      await user.click(within(dialog).getByRole('button', { name: 'End encounter' }));

      await waitFor(() => expect(bodies).toEqual([{ applyResults: false }]));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });

    it('keeps the encounter going when the DM cancels, and shows why the server refused an end', async () => {
      const ends: unknown[] = [];
      server.use(
        http.get('/api/encounters/:id/results', () => HttpResponse.json([] satisfies CombatResultPreview[])),
        http.post('/api/encounters/:id/end', async ({ request }) => {
          ends.push(await request.json());
          return HttpResponse.json({ error: 'The encounter is already completed' }, { status: 409 });
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();

      let dialog = await openEndDialog(user);
      expect(await within(dialog).findByText('No player characters to write back to.')).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(ends).toEqual([]);

      dialog = await openEndDialog(user);
      await user.click(within(dialog).getByRole('button', { name: 'End encounter' }));

      expect(await within(dialog).findByRole('alert')).toHaveTextContent('The encounter is already completed');
      expect(within(dialog).getByRole('button', { name: 'End encounter' })).toBeEnabled();
    });
  });

  it('shows a player character in its class colour, and a monster in the monster colour', async () => {
    server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
      participants: [gimli({ characterClass: 'Fighter' }), goblin()],
    }))));
    await renderLoaded();

    expect(screen.getByText('PLAYER').getAttribute('style')).toContain('var(--color-cls-fighter)');
    expect(screen.getByText('PLAYER').getAttribute('style')).toContain('var(--color-cls-fighter-bg)');
    expect(screen.getByText('MONSTER').getAttribute('style')).toContain('var(--color-monster)');
  });

  it('gives Pause and a forced Crit the darker gold, whose light text reads in every theme', async () => {
    const user = userEvent.setup();
    await renderLoaded();

    expect(screen.getByRole('button', { name: /Pause/ })).toHaveClass('bg-cls-paladin', 'text-card');
    await user.click(screen.getAllByRole('button', { name: 'Attack roll' })[0]);
    await user.click(screen.getByRole('button', { name: 'Crit' }));

    expect(screen.getByRole('button', { name: 'Crit' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Crit' })).toHaveClass('bg-cls-paladin', 'text-card');
  });

  describe('multiattack (D165)', () => {
    const owlbear = makeMockParticipant('part-owlbear', 'Owlbear', 'MONSTER', { sortOrder: 0, initiative: 18, hpMax: 59, hpCurrent: 59, armourClass: 13 });
    const tara = makeMockParticipant('part-tara', 'Tara', 'PLAYER', { sortOrder: 3, initiative: 8, controlledByUserId: 'user-003' });
    const dead = makeMockParticipant('part-dead', 'Fallen Scout', 'PLAYER', { sortOrder: 4, initiative: 5, hpCurrent: 0, lifeState: 'DEAD' });
    const owlbearTemplates = {
      actions: [
        { name: 'Multiattack', isMultiattack: true, multiattackComponents: ['Beak', 'Claw'],
          multiattackDescription: 'The owlbear makes two attacks: one with its beak and one with its claws.' },
        { name: 'Beak', deliveryMethod: 'ATTACK_ROLL', attackBonus: 7, effects: [{ effectType: 'DAMAGE', damageDice: '1d10 + 5', damageType: 'piercing' }] },
        { name: 'Claw', deliveryMethod: 'ATTACK_ROLL', attackBonus: 7, effects: [{ effectType: 'DAMAGE', damageDice: '2d8 + 5', damageType: 'slashing' }] },
      ],
    } satisfies MonsterActionTemplate;

    function owlbearFight(multiattack: MultiattackOption) {
      const requests: unknown[] = [];
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({ participants: [owlbear, gimli(), tara, dead] }))),
        http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json(owlbearTemplates)),
        http.get('/api/encounters/:id/combat/multiattack-options', () => HttpResponse.json([multiattack])),
        http.post('/api/encounters/:id/combat/monster-action', async ({ request }) => {
          requests.push(await request.json());
          return HttpResponse.json({
            resolved: true, description: 'Owlbear uses Multiattack.', totalDamage: 20, totalHealing: 0,
            targetResults: [], requiresManualResolution: false, conditionsInflicted: [],
            legendaryResistanceAvailable: false, legendaryResistanceRemaining: 0,
            encounterState: makeMockEncounter({ participants: [owlbear, gimli(), tara, dead] }),
          } satisfies MonsterActionResponse);
        }),
      );
      return requests;
    }

    it('sends each attack at the target chosen for it, offering only the living', async () => {
      const requests = owlbearFight({
        name: 'Multiattack', description: 'The owlbear makes two attacks: one with its beak and one with its claws.',
        components: [
          { name: 'Beak', automated: true, manualReason: null, maxTargets: 1 },
          { name: 'Claw', automated: true, manualReason: null, maxTargets: 1 },
        ],
        manualReason: null,
      });
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Monster actions' }));
      await user.click(await screen.findByRole('button', { name: /Multiattack/ }));
      const beak = await screen.findByRole('group', { name: 'Targets for 1. Beak' });
      const claw = screen.getByRole('group', { name: 'Targets for 2. Claw' });
      expect(screen.getByText('The owlbear makes two attacks: one with its beak and one with its claws.')).toBeInTheDocument();
      expect(screen.getByText('+7 · 1d10 + 5')).toBeInTheDocument();
      expect(within(beak).queryByRole('button', { name: 'Fallen Scout' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Execute Multiattack (0 attacks)' })).toBeDisabled();

      await user.click(within(beak).getByRole('button', { name: 'Gimli' }));
      await user.click(within(claw).getByRole('button', { name: 'Gimli' }));
      // An attack that takes one target swaps it
      await user.click(within(claw).getByRole('button', { name: 'Tara' }));
      expect(within(claw).getByRole('button', { name: 'Gimli' })).toHaveAttribute('aria-pressed', 'false');
      await user.click(screen.getByRole('button', { name: 'Execute Multiattack (2 attacks)' }));

      await waitFor(() => expect(requests).toEqual([{
        monsterParticipantId: 'part-owlbear', actionName: 'Multiattack', actionSource: 'ACTION',
        targetParticipantIds: [], componentTargetIds: [['part-gimli'], ['part-tara']],
      }]));
      expect(await screen.findByText('Owlbear uses Multiattack.')).toBeInTheDocument();
    });

    it('sends every attack at one target in one click, and skips an attack the DM resolves', async () => {
      const requests = owlbearFight({
        name: 'Multiattack', description: null,
        components: [
          { name: 'Frightful Presence', automated: true, manualReason: null, maxTargets: null },
          { name: 'Roar', automated: false, manualReason: 'The owlbear roars.', maxTargets: null },
          { name: 'Claw', automated: true, manualReason: null, maxTargets: 1 },
        ],
        manualReason: null,
      });
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Monster actions' }));
      await user.click(await screen.findByRole('button', { name: /Multiattack/ }));
      await user.click(within(await screen.findByRole('group', { name: 'Same target for every attack' }))
        .getByRole('button', { name: 'Tara' }));
      // An attack that takes any number adds another
      await user.click(within(screen.getByRole('group', { name: 'Targets for 1. Frightful Presence' }))
        .getByRole('button', { name: 'Gimli' }));
      expect(screen.getByText('The DM resolves this: The owlbear roars.')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Execute Multiattack (2 attacks)' }));

      await waitFor(() => expect(requests).toEqual([{
        monsterParticipantId: 'part-owlbear', actionName: 'Multiattack', actionSource: 'ACTION',
        targetParticipantIds: [], componentTargetIds: [['part-tara', 'part-gimli'], [], ['part-tara']],
      }]));
    });

    it("shows why a multiattack can't be read, and logs it for the DM to resolve", async () => {
      const requests = owlbearFight({
        name: 'Multiattack', description: 'The owlbear makes two melee attacks.', components: [],
        manualReason: 'Multiattack has no components defined',
      });
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Monster actions' }));
      await user.click(await screen.findByRole('button', { name: /Multiattack/ }));

      expect(await screen.findByRole('note')).toHaveTextContent(
        "Multiattack has no components defined: resolve it by hand, or use Owlbear's actions one at a time.");
      await user.click(screen.getByRole('button', { name: 'Log Multiattack for the DM to resolve' }));
      await waitFor(() => expect(requests).toEqual([{
        monsterParticipantId: 'part-owlbear', actionName: 'Multiattack', actionSource: 'ACTION', targetParticipantIds: [],
      }]));
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

  describe('turn economy (D174)', () => {
    it('shows every participant\'s turn economy on its row', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        participants: [
          gimli({ isCurrentTurn: true, attacksPerAction: 2, turnEconomy: { ...gimli().turnEconomy!, actionsUsed: 1, attackActionTaken: true, attacksLeft: 1 } }),
          goblin({ turnEconomy: { ...goblin().turnEconomy!, reactionUsed: true } }),
          bugbear(),
        ],
      }))));
      await renderLoaded();

      expect(screen.getByRole('group', { name: "Gimli's turn economy" })).toBeInTheDocument();
      expect(screen.getByRole('group', { name: "Test Goblin's turn economy" })).toBeInTheDocument();
      expect(screen.getByRole('group', { name: "Hidden Bugbear's turn economy" })).toBeInTheDocument();
      expect(screen.getByText('Attack 1/2')).toBeInTheDocument();
      expect(within(screen.getByRole('group', { name: "Test Goblin's turn economy" }))
        .getByRole('button', { name: 'Reaction used' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('replaces a participant\'s whole turn economy when the DM toggles a pip', async () => {
      let request: unknown;
      server.use(http.put('/api/encounters/:id/combat/turn-economy', async ({ request: req }) => {
        request = await req.json();
        return HttpResponse.json(encounterWith());
      }));
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(within(screen.getByRole('group', { name: "Gimli's turn economy" }))
        .getByRole('button', { name: 'Bonus action available' }));

      await waitFor(() => expect(request).toEqual({
        participantId: 'part-gimli', turnEconomy: { ...gimli().turnEconomy!, bonusActionUsed: true },
      }));
    });

    it('shows the turn economy read-only while the encounter is Paused', async () => {
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json({ ...encounterWith(), status: 'PAUSED' })));
      await renderLoaded();

      const bar = screen.getByRole('group', { name: "Gimli's turn economy" });
      expect(within(bar).queryAllByRole('button')).toHaveLength(0);
      expect(within(bar).getByRole('img', { name: 'Action available' })).toBeInTheDocument();
    });

    it('opens any creature\'s Actions menu, off its turn too, with warnings only, and sends the action', async () => {
      let body: unknown;
      server.use(http.post('/api/encounters/:id/combat/standard-action', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(encounterWith());
      }));
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Actions for Test Goblin' }));
      const menu = await screen.findByRole('dialog', { name: /Actions/ });
      // Not the goblin's turn: the DM is warned, never stopped
      expect(within(menu).getAllByText(/Over the action economy: Not this creature's turn/).length).toBeGreaterThan(0);
      await user.click(within(menu).getByRole('button', { name: /^Dash/ }));
      await user.click(within(menu).getByRole('button', { name: 'Take Dash' }));

      await waitFor(() => expect(body).toEqual({ participantId: 'part-goblin', action: 'DASH' }));
    });

    it('opens a creature\'s Reaction menu, and its stat-block reactions in the monster panel', async () => {
      server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
        actions: [], reactions: [{ name: 'Parry', description: 'Adds 2 to its AC against one melee attack.', dmNote: 'Add 2 to AC' }],
      })));
      const user = userEvent.setup();
      await renderLoaded();

      await user.click(screen.getByRole('button', { name: 'Reactions for Test Goblin' }));
      const menu = await screen.findByRole('dialog', { name: /Reactions/ });
      expect(within(menu).getByRole('region', { name: 'Opportunity attack' })).toBeInTheDocument();
      await user.click(within(menu).getByRole('button', { name: 'Stat block reactions (monster panel)' }));

      expect(await screen.findByText('Parry')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Reactions (1)' })).toBeInTheDocument();
    });

    it('uses any participant\'s pool, warning when its turn can\'t pay', async () => {
      let body: unknown;
      const rage = makeMockResourcePool('class:barbarian-rage', 'Rage', { spendActionType: 'BONUS_ACTION' });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
          participants: [gimli({ resourcePoolsCurrent: [rage] }), goblin(), bugbear()],
        }))),
        http.post('/api/encounters/:id/combat/use-pool', async ({ request }) => {
          body = await request.json();
          return HttpResponse.json(encounterWith());
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();

      const pools = screen.getByRole('list', { name: "Gimli's resource pools" });
      expect(within(pools).getByText(/Over the action economy: Not this creature's turn/)).toBeInTheDocument();
      await user.click(within(pools).getByRole('button', { name: 'Use Rage' }));

      await waitFor(() => expect(body).toEqual({ participantId: 'part-gimli', poolId: 'class:barbarian-rage' }));
    });

    it('offers no menus or pool use while the encounter is Paused', async () => {
      const rage = makeMockResourcePool('class:barbarian-rage', 'Rage', { spendActionType: 'BONUS_ACTION' });
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({
        status: 'PAUSED', participants: [gimli({ resourcePoolsCurrent: [rage] }), goblin(), bugbear()],
      }))));
      await renderLoaded();

      expect(screen.getByText('Rage')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Use Rage' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^Actions for/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^Reactions for/ })).not.toBeInTheDocument();
    });
  });

  describe('Spell Effects (M15)', () => {
    it('reminds the DM of Moonbeam on the goblin\'s turn and applies its start-of-turn damage to the goblin', async () => {
      let body: unknown;
      let actorId: string | null = null;
      const moonbeam = makeMockSpellEffect('Moonbeam', {
        concentration: true, directedCost: 'ACTION', directedResolves: false, triggers: ['ENTER', 'START_OF_TURN'],
      });
      const table = makeMockEncounter({
        status: 'ACTIVE',
        participants: [gimli({ concentrationSpell: 'Moonbeam', activeEffects: [moonbeam] }), goblin({ isCurrentTurn: true })],
      });
      server.use(
        http.get('/api/encounters/:id', () => HttpResponse.json(table)),
        http.post('/api/encounters/:id/combat/repeat-spell-effect', async ({ request }) => {
          body = await request.json();
          actorId = new URL(request.url).searchParams.get('actorId');
          return HttpResponse.json(makeMockCastSpellResponse({ encounterState: table, spellName: 'Moonbeam', resultSummary: 'The goblin burns.' }));
        }),
      );
      const user = userEvent.setup();
      await renderLoaded();

      const reminders = screen.getByRole('region', { name: 'Spell effect reminders' });
      expect(within(reminders).getByText('Is Test Goblin in a spell\'s area?')).toBeInTheDocument();
      await user.click(within(reminders).getByRole('button', { name: 'Moonbeam: Test Goblin starts turn' }));
      const dialog = await screen.findByRole('dialog', { name: 'Moonbeam — Apply Effect' });
      await user.click(within(dialog).getByRole('button', { name: 'Continue' }));
      await user.click(within(dialog).getByRole('button', { name: 'Apply Effect' }));

      expect(await screen.findByText('The goblin burns.')).toBeInTheDocument();
      expect(body).toEqual({ targetIds: ['part-goblin'], advantage: null, effectId: 'effect-moonbeam', trigger: 'START_OF_TURN' });
      expect(actorId).toBe('part-gimli');
    });
  });
});
