import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockLogEntry, makeMockParticipant } from '../../test/mocks/handlers';
import { MockStompClient } from '../../test/mocks/stomp';
import type { Encounter, EncounterParticipant } from '../../types/encounter';
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
      participants: [gimli({ isCurrentTurn: true }), goblin({ hpCurrent: 0, isAlive: false }), bugbear()],
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
  });

  describe('lifecycle (D166)', () => {
    const PAUSED_NOTICE = 'Paused — resume to continue';

    // A downed Gimli, a prone and concentrating goblin, and a bugbear with spell slots, so every kind of
    // combat control has something to show for
    const busyTable = (status: Encounter['status']) => makeMockEncounter({
      status,
      participants: [
        gimli({ isAlive: false, hpCurrent: 0, deathSaveFailures: 1 }),
        goblin({
          activeConditions: JSON.stringify([{ name: 'prone', duration: null, appliedRound: 1 }]),
          concentrationSpell: 'Bless',
        }),
        bugbear({ spellSlotsCurrent: JSON.stringify({ 1: { remaining: 2, max: 3 } }) }),
      ],
    });

    const COMBAT_CONTROLS: (string | RegExp)[] = [
      'Previous turn', /Next Turn/, 'Attack roll', 'Deal damage', 'Heal', 'Add condition', 'Set concentration',
      'Cast spell', 'Monster actions', 'Repeat spell effect', 'Roll', /level 1 slot/, /prone/,
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
});
