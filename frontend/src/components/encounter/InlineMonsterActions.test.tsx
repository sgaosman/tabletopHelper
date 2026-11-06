import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { MonsterActionResponse } from '../../types/combat';
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
