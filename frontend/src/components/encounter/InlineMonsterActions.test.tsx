import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { MonsterActionResponse, MonsterActionTemplate } from '../../types/combat';
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

  it('offers the living as targets, and shows the result of an action for each target', async () => {
    const requests: unknown[] = [];
    server.use(http.post('/api/encounters/:id/combat/monster-action', async ({ request }) => {
      requests.push(await request.json());
      return HttpResponse.json({
        resolved: true, description: 'Test Goblin uses Scimitar.', totalDamage: 5, totalHealing: 0,
        targetResults: [{
          targetId: 'part-gimli', targetName: 'Gimli', attackOutcome: 'HIT', damage: 5, damageType: 'slashing', healing: 0,
          conditionsApplied: ['prone'], savedSuccessfully: false, legendaryResistanceAvailable: false, legendaryResistanceRemaining: 0,
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
