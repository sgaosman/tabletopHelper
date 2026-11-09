import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { MonsterActionResponse, MonsterActionTemplate } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import FamiliarAttack from './FamiliarAttack';

const FRESH = makeMockParticipant('x', 'x', 'PLAYER').turnEconomy!;
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER');

function warlock(overrides: Partial<EncounterParticipant> = {}) {
  return makeMockParticipant('part-wren', 'Wren', 'PLAYER', {
    isCurrentTurn: true, controlledByUserId: 'user-001', pactOfTheChain: true, ...overrides,
  });
}

function imp(overrides: Partial<EncounterParticipant> = {}) {
  return makeMockParticipant('part-imp', 'Imp', 'COMPANION', {
    controlledByUserId: 'user-001', summonedByParticipantId: 'part-wren', ...overrides,
  });
}

async function renderAttack(caster: EncounterParticipant, familiar: EncounterParticipant) {
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001" view="player">
      <FamiliarAttack warlock={caster} familiar={familiar} participants={[caster, familiar, goblin]} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return userEvent.setup();
}

describe('FamiliarAttack (Pact of the Chain, PHB p.107)', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json({
      actions: [
        { name: 'Sting', attackBonus: 5, deliveryMethod: 'ATTACK_ROLL', automatable: true,
          effects: [{ effectType: 'DAMAGE', damageDice: '1d4+3', damageType: 'piercing' }] },
        { name: 'Invisibility', description: 'The imp turns invisible.', automatable: false },
      ],
    } satisfies MonsterActionTemplate)));
  });

  it("sends the familiar's one attack with pactOfTheChain, and shows the result", async () => {
    let body: unknown;
    server.use(http.post('/api/encounters/:id/combat/monster-action', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({
        resolved: true, description: 'Imp uses Sting (reaction, Pact of the Chain).', totalDamage: 5, totalHealing: 0,
        targetResults: [], requiresManualResolution: false, conditionsInflicted: [],
        legendaryResistanceAvailable: false, legendaryResistanceRemaining: 0, encounterState: makeMockEncounter(),
      } satisfies MonsterActionResponse);
    }));
    const user = await renderAttack(warlock(), imp());

    const sting = await screen.findByRole('button', { name: 'Sting' });
    expect(sting).toHaveClass('min-h-11');
    expect(sting).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Target'), 'part-goblin');
    await user.click(sting);

    await waitFor(() => expect(body).toEqual({
      monsterParticipantId: 'part-imp', actionName: 'Sting', actionSource: 'ACTION',
      targetParticipantIds: ['part-goblin'], pactOfTheChain: true,
    }));
    expect(await screen.findByText('Imp uses Sting (reaction, Pact of the Chain).')).toBeInTheDocument();
  });

  it("shows the server's refusal as a toast", async () => {
    server.use(http.post('/api/encounters/:id/combat/monster-action', () => HttpResponse.json(
      { error: 'It has no attacks left in its Attack action and no action left this turn' }, { status: 409 })));
    const user = await renderAttack(warlock(), imp());

    await user.selectOptions(await screen.findByLabelText('Target'), 'part-goblin');
    await user.click(screen.getByRole('button', { name: 'Sting' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('no attacks left in its Attack action');
  });

  it("greys the attack once the warlock's attacks or the familiar's reaction are spent", async () => {
    await renderAttack(warlock({ turnEconomy: { ...FRESH, actionsUsed: 1, attackActionTaken: true, attacksLeft: 0 } }),
      imp());
    expect(await screen.findByText('No attacks left in the Attack action and no action left this turn'))
      .toBeInTheDocument();
  });

  it('says when the familiar has used its reaction', async () => {
    await renderAttack(warlock(), imp({ turnEconomy: { ...FRESH, reactionUsed: true } }));
    expect(await screen.findByText('Imp has already used its reaction')).toBeInTheDocument();
  });
});
