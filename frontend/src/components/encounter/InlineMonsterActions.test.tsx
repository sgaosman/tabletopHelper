import { describe, it, expect, beforeEach } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { CombatLogEntry, MonsterActionResponse, MonsterActionTemplate, MultiattackOption } from '../../types/combat';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import InlineMonsterActions from './InlineMonsterActions';

const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER');
const gimli = makeMockParticipant('part-gimli', 'Gimli', 'PLAYER');
const fallen = makeMockParticipant('part-fallen', 'Fallen Scout', 'PLAYER', { hpCurrent: 0, lifeState: 'DEAD' });
const participants = [goblin, gimli, fallen];

async function renderActions() {
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001" view="dm">
      <InlineMonsterActions participant={goblin} participants={participants} isLairTurn={false} inLair={false} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'dm'))).toBeDefined());
  return userEvent.setup();
}

describe('InlineMonsterActions', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('marks the result taken back once its command is undone, as Shield does to a hit (D216)', async () => {
    const { queryClient } = render(
      <EncounterProvider encounterId="enc-001" view="dm">
        <InlineMonsterActions participant={goblin} participants={participants} isLairTurn={false} inLair={false} />
      </EncounterProvider>,
    );
    await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'dm'))).toBeDefined());
    const user = userEvent.setup();
    const logKey = queryKeys.encounters.log('enc-001', 'dm');
    const entry = (id: string, actorId: string, commandId: string, undone: boolean) =>
      ({ id, actorId, commandId, undone, actionType: 'ATTACK', description: id, roundNumber: 1 }) as unknown as CombatLogEntry;
    queryClient.setQueryData(logKey, [entry('old', 'part-goblin', 'cmd-0', false)]);

    await user.click(await screen.findByRole('button', { name: /Scimitar/ }));
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Scimitar (1 target)' }));
    expect(await screen.findByText('Test Goblin uses Scimitar.')).toBeInTheDocument();

    // A later command by another creature undone, or the monster's earlier one, isn't this result
    act(() => queryClient.setQueryData(logKey, [entry('old', 'part-goblin', 'cmd-0', true),
      entry('new', 'part-goblin', 'cmd-1', false), entry('shield', 'part-gimli', 'cmd-2', false)]));
    expect(screen.queryByText(/Taken back/)).not.toBeInTheDocument();

    act(() => queryClient.setQueryData(logKey, [entry('old', 'part-goblin', 'cmd-0', true),
      entry('new', 'part-goblin', 'cmd-1', true), entry('shield', 'part-gimli', 'cmd-2', false)]));
    expect(await screen.findByText('Taken back: undone since')).toBeInTheDocument();
  });

  it('offers the living as targets, and shows the result of an action for each target', async () => {
    const requests: unknown[] = [];
    server.use(http.post('/api/encounters/:id/combat/monster-action', async ({ request }) => {
      requests.push(await request.json());
      return HttpResponse.json({
        resolved: true, description: 'Test Goblin uses Scimitar.', totalDamage: 5, totalHealing: 0,
        targetResults: [{
          targetId: 'part-gimli', targetName: 'Gimli', attackOutcome: 'HIT', damage: 5, damageType: 'slashing', healing: 0,
          conditionsApplied: ['prone'], savedSuccessfully: false, legendaryResistanceAvailable: false, legendaryResistanceRemaining: 0,
          damageParts: [{ type: 'slashing', amount: 5 }],
        }],
        requiresManualResolution: false, conditionsInflicted: [], legendaryResistanceAvailable: false, legendaryResistanceRemaining: 0,
        encounterState: makeMockEncounter({ participants }),
      } satisfies MonsterActionResponse);
    }));
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /Scimitar/ }));
    expect(screen.queryByRole('button', { name: 'Fallen Scout' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Scimitar (1 target)' }));

    expect(await screen.findByText('Test Goblin uses Scimitar.')).toBeInTheDocument();
    expect(screen.getByText('Gimli: HIT — 5 slashing — prone')).toBeInTheDocument();
    expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Scimitar', actionSource: 'ACTION', targetParticipantIds: ['part-gimli'],
    }]);

    await user.click(screen.getByRole('button', { name: 'Clear' }));
    expect(screen.queryByText('Test Goblin uses Scimitar.')).not.toBeInTheDocument();
  });

  it('shows each damage type of a hit with two (D207)', async () => {
    server.use(http.post('/api/encounters/:id/combat/monster-action', () => HttpResponse.json({
      resolved: true, description: 'Test Goblin uses Scimitar.', totalDamage: 16, totalHealing: 0,
      targetResults: [{
        targetId: 'part-gimli', targetName: 'Gimli', attackOutcome: 'HIT', damage: 16, damageType: 'slashing', healing: 0,
        conditionsApplied: [], savedSuccessfully: false, legendaryResistanceAvailable: false, legendaryResistanceRemaining: 0,
        damageParts: [{ type: 'slashing', amount: 12 }, { type: 'lightning', amount: 4 }],
      }],
      requiresManualResolution: false, conditionsInflicted: [], legendaryResistanceAvailable: false, legendaryResistanceRemaining: 0,
      encounterState: makeMockEncounter({ participants }),
    } satisfies MonsterActionResponse)));
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /Scimitar/ }));
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Scimitar (1 target)' }));

    expect(await screen.findByText('Gimli: HIT — 12 slashing + 4 lightning')).toBeInTheDocument();
  });

  it('shows the server\'s reason when it refuses', async () => {
    server.use(http.post('/api/encounters/:id/combat/monster-action', () =>
      HttpResponse.json({ error: 'Scimitar is recharging' }, { status: 400 })));
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /Scimitar/ }));
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Scimitar (1 target)' }));

    expect(await screen.findByText('Scimitar is recharging')).toHaveClass('text-debuff');
  });
});

describe('InlineMonsterActions: a shared recharge (D227)', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [
        { name: 'Fire Breath', recharge: '5-6', rechargeGroup: 'Breath Weapons', deliveryMethod: 'SAVING_THROW' },
        { name: 'Sleep Breath', recharge: '5-6', rechargeGroup: 'Breath Weapons', deliveryMethod: 'SAVING_THROW' },
        { name: 'Wing Buffet', recharge: '5-6', deliveryMethod: 'SAVING_THROW' },
      ],
      resourcePools: { 'monster:recharge:breath-weapons': { currentUses: 0 } },
    } satisfies MonsterActionTemplate & { resourcePools: Record<string, { currentUses: number }> })));
  });

  it("disables both breath weapons once their shared Breath Weapons pool is spent, and not another recharge", async () => {
    await renderActions();

    expect(await screen.findByRole('button', { name: /Fire Breath/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Sleep Breath/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Wing Buffet/ })).toBeEnabled();
  });
});

describe('InlineMonsterActions: limited uses (MM p.11)', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [
        { name: 'Smoke Bomb (1/Day)', description: 'The goblin throws a smoke bomb.', automatable: false,
          limitedUses: { poolId: 'monster:limited:smoke-bomb-1-day', maxUses: 1 } },
        { name: 'Shriek (3/Day)', description: 'The goblin shrieks.', automatable: false,
          limitedUses: { poolId: 'monster:limited:shriek-3-day', maxUses: 3 } },
      ],
      resourcePools: { 'monster:limited:smoke-bomb-1-day': { currentUses: 0 } },
    } satisfies MonsterActionTemplate & { resourcePools: Record<string, { currentUses: number }> })));
  });

  it('shows the uses left, full for an action not used yet, and disables one with none left', async () => {
    await renderActions();

    const smokeBomb = await screen.findByRole('button', { name: /Smoke Bomb/ });
    expect(smokeBomb).toBeDisabled();
    expect(smokeBomb).toHaveTextContent('[0/1 left]');
    expect(smokeBomb).toHaveAttribute('title', expect.stringContaining('No uses left'));
    const shriek = screen.getByRole('button', { name: /Shriek/ });
    expect(shriek).toBeEnabled();
    expect(shriek).toHaveTextContent('[3/3 left]');
  });
});

describe('InlineMonsterActions: bonus actions and reactions (D138, D174)', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [{ name: 'Scimitar', attackBonus: 4, deliveryMethod: 'MELEE_WEAPON', automatable: true,
        effects: [{ effectType: 'DAMAGE', damageDice: '1d6+2', damageType: 'slashing' }] }],
      bonusActions: [{ name: 'Nimble Escape', description: 'The goblin takes the Disengage or Hide action.', automatable: false }],
      reactions: [{ name: 'Parry', description: 'The goblin adds 2 to its AC against one melee attack.', automatable: true,
        deliveryMethod: 'SELF', dmNote: 'Add 2 to its AC against the hit; if that makes it miss, undo the damage.' }],
    } satisfies MonsterActionTemplate)));
  });

  /** Records each monster action request. */
  function monsterActions() {
    const requests: unknown[] = [];
    server.use(http.post('/api/encounters/:id/combat/monster-action', async ({ request }) => {
      requests.push(await request.json());
      return HttpResponse.json({
        resolved: true, description: 'Done.', totalDamage: 0, totalHealing: 0, targetResults: [],
        requiresManualResolution: false, conditionsInflicted: [], legendaryResistanceAvailable: false,
        legendaryResistanceRemaining: 0, encounterState: makeMockEncounter({ participants }),
      } satisfies MonsterActionResponse);
    }));
    return requests;
  }

  it('uses a reaction off the monster\'s turn through the REACTION source, with what the DM applies by hand', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: 'Reactions (1)' }));
    expect(screen.queryByText(/Over the action economy/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Parry/ }));
    expect(screen.getByText('DM: Add 2 to its AC against the hit; if that makes it miss, undo the damage.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Parry (1 target)' }));

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Parry', actionSource: 'REACTION', targetParticipantIds: ['part-gimli'],
    }]));
  });

  it('uses a bonus action through the BONUS source, warning the DM when it is over the monster\'s turn economy', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: 'Bonus actions (1)' }));
    expect(screen.getByText("Over the action economy: Not this creature's turn: only a reaction can be taken")).toBeInTheDocument();
    // A manual action is logged for the DM at once, at the targets chosen
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: /Nimble Escape/ }));

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Nimble Escape', actionSource: 'BONUS', targetParticipantIds: ['part-gimli'],
    }]));
  });

  it('warns when the monster\'s reaction is already used', async () => {
    const user = userEvent.setup();
    const { queryClient } = render(
      <EncounterProvider encounterId="enc-001" view="dm">
        <InlineMonsterActions participant={{ ...goblin, turnEconomy: { ...goblin.turnEconomy!, reactionUsed: true } }}
          participants={participants} isLairTurn={false} inLair={false} />
      </EncounterProvider>,
    );
    await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'dm'))).toBeDefined());

    await user.click(await screen.findByRole('button', { name: 'Reactions (1)' }));

    expect(screen.getByText(/^Over the action economy: Reaction already used/)).toBeInTheDocument();
  });
});

describe('InlineMonsterActions: conditions on attack rolls (PHB appendix A)', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [
        { name: 'Scimitar', attackBonus: 4, deliveryMethod: 'ATTACK_ROLL', attackType: 'MELEE_WEAPON', automatable: true,
          effects: [{ effectType: 'DAMAGE', damageDice: '1d6+2', damageType: 'slashing' }] },
        { name: 'Shortbow', attackBonus: 4, deliveryMethod: 'ATTACK_ROLL', attackType: 'RANGED_WEAPON', automatable: true,
          effects: [{ effectType: 'DAMAGE', damageDice: '1d6+2', damageType: 'piercing' }] },
      ],
    } satisfies MonsterActionTemplate)));
  });

  it("shows, for each chosen target, what its and the monster's conditions add, a ranged attack from beyond 5 ft", async () => {
    const asked: string[] = [];
    server.use(http.get('/api/encounters/:id/combat/attack-roll-preview', ({ request }) => {
      const url = new URL(request.url);
      asked.push(url.search);
      const within = url.searchParams.get('withinFiveFeet') === 'true';
      return HttpResponse.json({
        rollMode: within ? 'ADVANTAGE' : 'DISADVANTAGE',
        sources: [within ? { mode: 'ADVANTAGE', reason: 'target prone within 5 ft' }
          : { mode: 'DISADVANTAGE', reason: 'target prone beyond 5 ft' }],
        hitIsCritical: false,
      });
    }));
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /Scimitar/ }));
    expect(screen.queryByRole('note', { name: 'Automatic advantage and disadvantage' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    expect(await screen.findByRole('note', { name: 'Automatic advantage and disadvantage' }))
      .toHaveTextContent('Gimli: Advantage: target prone within 5 ft');

    await user.click(screen.getByRole('button', { name: /Shortbow/ }));
    expect(await screen.findByText('Disadvantage: target prone beyond 5 ft')).toBeInTheDocument();
    expect(asked).toEqual([
      '?attackerId=part-goblin&targetId=part-gimli&withinFiveFeet=true',
      '?attackerId=part-goblin&targetId=part-gimli&withinFiveFeet=false',
    ]);
  });
});

describe('InlineMonsterActions: damage options (D207)', () => {
  const dragonTypes = ['acid', 'cold', 'fire', 'lightning', 'poison'];

  beforeEach(() => {
    setupAuthToken();
    server.use(
      http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
        actions: [
          { name: 'Multiattack', isMultiattack: true, multiattackComponents: [], automatable: true },
          { name: 'Bite', attackBonus: 9, deliveryMethod: 'ATTACK_ROLL', attackType: 'MELEE_WEAPON', automatable: true,
            effects: [{ effectType: 'DAMAGE', damageDice: '1d10 + 5', damageType: 'option' }], damageOptions: dragonTypes },
          { name: 'Claw', attackBonus: 9, deliveryMethod: 'ATTACK_ROLL', attackType: 'MELEE_WEAPON', automatable: true,
            effects: [{ effectType: 'DAMAGE', damageDice: '1d6 + 5', damageType: 'slashing' }] },
        ],
      } satisfies MonsterActionTemplate)),
      http.get('/api/encounters/:id/combat/multiattack-options', () => HttpResponse.json([{
        name: 'Multiattack', description: 'The dracohydra makes as many Bite attacks as it has heads.', manualReason: null,
        components: [
          { name: 'Bite', automated: true, manualReason: null, maxTargets: 1, damageOptions: dragonTypes },
          { name: 'Bite', automated: true, manualReason: null, maxTargets: 1, damageOptions: dragonTypes },
          { name: 'Claw', automated: true, manualReason: null, maxTargets: 1, damageOptions: [] },
        ],
      }] satisfies MultiattackOption[])),
    );
  });

  /** Records each monster action request. */
  function monsterActions() {
    const requests: unknown[] = [];
    server.use(http.post('/api/encounters/:id/combat/monster-action', async ({ request }) => {
      requests.push(await request.json());
      return HttpResponse.json({
        resolved: true, description: 'Done.', totalDamage: 0, totalHealing: 0, targetResults: [],
        requiresManualResolution: false, conditionsInflicted: [], legendaryResistanceAvailable: false,
        legendaryResistanceRemaining: 0, encounterState: makeMockEncounter({ participants }),
      } satisfies MonsterActionResponse);
    }));
    return requests;
  }

  it('asks for the damage type of an action with options, and sends it once chosen', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /^Bite/ }));
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    const execute = screen.getByRole('button', { name: 'Execute Bite (1 target)' });
    expect(execute).toBeDisabled();
    const select = screen.getByLabelText<HTMLSelectElement>('Damage type');
    expect([...select.options].map(o => o.value)).toEqual(['', ...dragonTypes]);
    await user.selectOptions(select, 'fire');
    await user.click(execute);

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Bite', actionSource: 'ACTION', targetParticipantIds: ['part-gimli'],
      damageType: 'fire',
    }]));
  });

  it('asks nothing for an action without options, and forgets the type when another action is chosen', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /^Bite/ }));
    await user.selectOptions(screen.getByLabelText('Damage type'), 'acid');
    await user.click(screen.getByRole('button', { name: /^Claw/ }));
    expect(screen.queryByLabelText('Damage type')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Claw (1 target)' }));
    await user.click(screen.getByRole('button', { name: /^Bite/ }));
    expect(screen.getByLabelText('Damage type')).toHaveValue('');

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Claw', actionSource: 'ACTION', targetParticipantIds: ['part-gimli'],
    }]));
  });

  it('asks a multiattack for a type for each Bite with a target, and sends them in order', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: '⚔ Multiattack' }));
    await user.click(within(await screen.findByRole('group', { name: 'Same target for every attack' }))
      .getByRole('button', { name: 'Gimli' }));
    const execute = screen.getByRole('button', { name: 'Execute Multiattack (3 attacks)' });
    expect(execute).toBeDisabled();
    expect(screen.getByText('Choose a damage type for each attack that has a target.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Damage type for 3. Claw')).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Damage type for 1. Bite'), 'fire');
    expect(execute).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Damage type for 2. Bite'), 'cold');
    await user.click(execute);

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Multiattack', actionSource: 'ACTION', targetParticipantIds: [],
      componentTargetIds: [['part-gimli'], ['part-gimli'], ['part-gimli']],
      componentDamageTypes: ['fire', 'cold', ''],
    }]));
  });

  it('needs no type for a Bite left without a target', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: '⚔ Multiattack' }));
    await user.click(within(await screen.findByRole('group', { name: 'Same target for every attack' }))
      .getByRole('button', { name: 'Gimli' }));
    await user.click(within(screen.getByRole('group', { name: 'Targets for 2. Bite' })).getByRole('button', { name: 'Gimli' }));
    await user.selectOptions(screen.getByLabelText('Damage type for 1. Bite'), 'poison');
    await user.click(screen.getByRole('button', { name: 'Execute Multiattack (2 attacks)' }));

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Multiattack', actionSource: 'ACTION', targetParticipantIds: [],
      componentTargetIds: [['part-gimli'], [], ['part-gimli']],
      componentDamageTypes: ['poison', '', ''],
    }]));
  });
});

describe('InlineMonsterActions: review fixes', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  /** Records each monster action request. */
  function monsterActions() {
    const requests: unknown[] = [];
    server.use(http.post('/api/encounters/:id/combat/monster-action', async ({ request }) => {
      requests.push(await request.json());
      return HttpResponse.json({
        resolved: true, description: 'Done.', totalDamage: 0, totalHealing: 0, targetResults: [],
        requiresManualResolution: false, conditionsInflicted: [], legendaryResistanceAvailable: false,
        legendaryResistanceRemaining: 0, encounterState: makeMockEncounter({ participants }),
      } satisfies MonsterActionResponse);
    }));
    return requests;
  }

  const LAIR_TEMPLATES = {
    actions: [],
    lairActions: [{ name: 'Tremor', description: 'The ground shakes.', automatable: false }],
  } satisfies MonsterActionTemplate;

  it('loads the templates again after a monster action, so the legendary count shown follows the server', async () => {
    let legendaryLeft = 3;
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [],
      legendaryActions: [{ name: 'Detect', description: 'The goblin makes a Wisdom (Perception) check.', automatable: false, cost: 1 }],
      legendaryActionCount: 3,
      resourcePools: { 'monster:legendary-actions': { currentUses: legendaryLeft } },
    } satisfies MonsterActionTemplate & { resourcePools: Record<string, { currentUses: number }> })));
    server.use(http.post('/api/encounters/:id/combat/monster-action', () => {
      legendaryLeft -= 1;
      return HttpResponse.json({
        resolved: true, description: 'Test Goblin uses Detect.', totalDamage: 0, totalHealing: 0, targetResults: [],
        requiresManualResolution: false, conditionsInflicted: [], legendaryResistanceAvailable: false,
        legendaryResistanceRemaining: 0, encounterState: makeMockEncounter({ participants }),
      } satisfies MonsterActionResponse);
    }));
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: 'Legendary (3)' }));
    await user.click(screen.getByRole('button', { name: /Detect/ }));

    expect(await screen.findByRole('button', { name: 'Legendary (2)' })).toBeInTheDocument();
  });

  it('logs a manual action with no target chosen, as the server takes it', async () => {
    const requests = monsterActions();
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [{ name: 'Shriek', description: 'The goblin shrieks.', automatable: false }],
    } satisfies MonsterActionTemplate)));
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /Shriek/ }));

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Shriek', actionSource: 'ACTION', targetParticipantIds: [],
    }]));
  });

  it('says an automatable action waits for a target', async () => {
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /Scimitar/ }));

    expect(screen.getByText('Choose a target first.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Execute Scimitar' })).toBeDisabled();
  });

  it('makes lair actions buttons the keyboard reaches, and logs a manual one with no target', async () => {
    const requests = monsterActions();
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json(LAIR_TEMPLATES)));
    const user = userEvent.setup();
    const { queryClient } = render(
      <EncounterProvider encounterId="enc-001" view="dm">
        <InlineMonsterActions participant={goblin} participants={participants} isLairTurn inLair />
      </EncounterProvider>,
    );
    await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'dm'))).toBeDefined());

    await user.click(await screen.findByRole('button', { name: 'Lair (1)' }));
    screen.getByRole('button', { name: /Tremor/ }).focus();
    await user.keyboard('{Enter}');

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-goblin', actionName: 'Tremor', actionSource: 'LAIR', targetParticipantIds: [],
    }]));
  });

  it('disables lair actions off the Lair turn', async () => {
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json(LAIR_TEMPLATES)));
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: 'Lair (1)' }));

    expect(screen.getByRole('button', { name: /Tremor/ })).toBeDisabled();
  });

  it('starts afresh when the DM switches from one multiattack to another', async () => {
    server.use(
      http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
        actions: [
          { name: 'Multiattack', isMultiattack: true, multiattackComponents: [], automatable: true },
          { name: 'Multiattack (Ranged)', isMultiattack: true, multiattackComponents: [], automatable: true },
        ],
      } satisfies MonsterActionTemplate)),
      http.get('/api/encounters/:id/combat/multiattack-options', () => HttpResponse.json([
        { name: 'Multiattack', description: null, manualReason: null, components: [
          { name: 'Bite', automated: true, manualReason: null, maxTargets: 1, damageOptions: [] },
          { name: 'Claw', automated: true, manualReason: null, maxTargets: 1, damageOptions: [] },
          { name: 'Claw', automated: true, manualReason: null, maxTargets: 1, damageOptions: [] },
        ] },
        { name: 'Multiattack (Ranged)', description: null, manualReason: null, components: [
          { name: 'Longbow', automated: true, manualReason: null, maxTargets: 1, damageOptions: [] },
        ] },
      ] satisfies MultiattackOption[])),
    );
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: '⚔ Multiattack' }));
    await user.click(within(await screen.findByRole('group', { name: 'Same target for every attack' }))
      .getByRole('button', { name: 'Gimli' }));
    expect(screen.getByRole('button', { name: 'Execute Multiattack (3 attacks)' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: '⚔ Multiattack (Ranged)' }));

    expect(await screen.findByRole('button', { name: 'Execute Multiattack (Ranged) (0 attacks)' })).toBeDisabled();
    expect(within(screen.getByRole('group', { name: 'Targets for 1. Longbow' })).getByRole('button', { name: 'Gimli' }))
      .toHaveAttribute('aria-pressed', 'false');
  });
});
