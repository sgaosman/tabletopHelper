import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockAttackOption, makeMockParticipant, makeMockResourcePool } from '../../test/mocks/handlers';
import type { EncounterParticipant, ResourcePoolEntry } from '../../types/encounter';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import ResourcePoolDisplay from '../common/ResourcePoolDisplay';

const PALADIN_CD = 'class:paladin-channel-divinity';

/** Aria, a Paladin 3 of the Oath of Devotion with a rapier and a longsword (PHB p.86). */
function aria(): EncounterParticipant {
  const pool = makeMockResourcePool(PALADIN_CD, 'Channel Divinity', {
    sourceName: 'Paladin', maxUses: 1, currentUses: 1, resetOn: 'shortRest', spendActionType: 'ACTION',
    metadata: { effect: 'channel-divinity' } as unknown as ResourcePoolEntry['metadata'],
  });
  return makeMockParticipant('part-aria', 'Aria', 'PLAYER', {
    isCurrentTurn: true, resourcePoolsCurrent: [pool], channelDivinityOptions: ['sacred-weapon', 'turn-the-unholy'],
  });
}

async function renderPools(participant: EncounterParticipant) {
  const onUse = vi.fn().mockResolvedValue(undefined);
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001" view="player">
      <ResourcePoolDisplay pools={participant.resourcePoolsCurrent} participant={participant} onUse={onUse}
        targets={[participant]} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return { onUse, user: userEvent.setup() };
}

describe('Sacred Weapon (PHB p.86)', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id/combat/attack-options', () => HttpResponse.json([
      makeMockAttackOption('Rapier'), makeMockAttackOption('Longsword'),
      makeMockAttackOption('Unarmed Strike', { damage: '4', damageType: 'bludgeoning' }),
    ])));
  });

  it("picks one of the paladin's weapons, not an unarmed strike, and sends it with the option", async () => {
    const { onUse, user } = await renderPools(aria());

    expect(await screen.findByRole('option', { name: 'Longsword' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Unarmed Strike' })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Weapon'), 'Longsword');
    await user.click(screen.getByRole('button', { name: 'Use Channel Divinity: Sacred Weapon' }));

    expect(onUse).toHaveBeenCalledWith(PALADIN_CD, {
      option: 'sacred-weapon', targetIds: undefined, amounts: undefined, weapon: 'Longsword',
    });
  });

  it('leaves the weapon to the server when none is picked, and shows no picker for another option', async () => {
    const { onUse, user } = await renderPools(aria());

    await screen.findByRole('option', { name: 'Rapier' });
    await user.click(screen.getByRole('button', { name: 'Use Channel Divinity: Sacred Weapon' }));
    expect(onUse.mock.calls[0][1]).not.toHaveProperty('weapon');

    await user.selectOptions(screen.getByLabelText('Channel Divinity option'), 'turn-the-unholy');
    expect(screen.queryByLabelText('Weapon')).not.toBeInTheDocument();
  });
});
