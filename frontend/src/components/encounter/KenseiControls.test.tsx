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

const KI = 'class:monk-ki';

/** Ren, a Way of the Kensei monk of the level, with ki points left (XGE p.34-35). */
function ren(kenseiLevel: number | null, ki = 5, overrides: Partial<EncounterParticipant> = {}): EncounterParticipant {
  const pool = makeMockResourcePool(KI, 'Ki Points', {
    sourceName: 'Monk', maxUses: 11, currentUses: ki, resetOn: 'shortRest', spendActionType: 'FREE',
    metadata: { effect: 'ki' } as unknown as ResourcePoolEntry['metadata'],
  });
  return makeMockParticipant('part-ren', 'Ren', 'PLAYER', {
    isCurrentTurn: true, resourcePoolsCurrent: [pool], kenseiLevel, ...overrides,
  });
}

async function renderPools(participant: EncounterParticipant) {
  const onUse = vi.fn().mockResolvedValue(undefined);
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001" view="player">
      <ResourcePoolDisplay pools={participant.resourcePoolsCurrent} participant={participant} onUse={onUse} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return { onUse, user: userEvent.setup() };
}

describe('Way of the Kensei controls (XGE p.34-35)', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id/combat/attack-options', () => HttpResponse.json([
      makeMockAttackOption('Longsword', { kenseiWeapon: true }), makeMockAttackOption('Quarterstaff'),
      makeMockAttackOption('Longbow', { kenseiWeapon: true, ranged: true }),
    ])));
  });

  it("sends Kensei's Shot, which spends no ki, as a 44px control", async () => {
    const { onUse, user } = await renderPools(ren(3, 0));

    const shot = screen.getByRole('button', { name: "Kensei's Shot" });
    expect(shot).toHaveClass('min-h-11');
    expect(shot).toBeEnabled();
    await user.click(shot);
    expect(onUse).toHaveBeenCalledWith(KI, { option: 'kenseis-shot' });
    expect(screen.queryByRole('button', { name: /Sharpen the Blade/ })).not.toBeInTheDocument();
  });

  it("greys Kensei's Shot once the bonus action is spent", async () => {
    const spent = ren(3);
    await renderPools({ ...spent, turnEconomy: { ...spent.turnEconomy!, bonusActionUsed: true } });
    expect(screen.getByRole('button', { name: "Kensei's Shot" })).toBeDisabled();
  });

  it('sends Sharpen the Blade with the ki points and one of the kensei weapons', async () => {
    const { onUse, user } = await renderPools(ren(11));

    expect(await screen.findByRole('option', { name: 'Longbow' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Quarterstaff' })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Ki'), '3');
    await user.selectOptions(screen.getByLabelText('Weapon'), 'Longbow');
    await user.click(screen.getByRole('button', { name: 'Sharpen the Blade (+3)' }));

    expect(onUse).toHaveBeenCalledWith(KI, { option: 'sharpen-the-blade', uses: 3, weapon: 'Longbow' });
  });

  it('offers no more ki points than are left', async () => {
    await renderPools(ren(11, 1));
    expect(await screen.findByRole('option', { name: 'Longsword' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sharpen the Blade (+1)' })).toBeEnabled();
    expect(screen.getByRole('option', { name: '2' })).toBeDisabled();
    expect(screen.getByRole('option', { name: '3' })).toBeDisabled();
  });

  it('greys Sharpen the Blade without ki', async () => {
    await renderPools(ren(11, 0));
    expect(await screen.findByRole('option', { name: 'Longsword' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sharpen the Blade (+1)' })).toBeDisabled();
  });

  it('offers neither to a monk of another tradition', async () => {
    await renderPools(ren(null));
    expect(screen.getByRole('button', { name: 'Patient Defense' })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Way of the Kensei' })).not.toBeInTheDocument();
  });
});
