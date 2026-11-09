import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { WeaponHit } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import HitFollowUps from './HitFollowUps';

const FRESH = makeMockParticipant('x', 'x', 'PLAYER').turnEconomy!;
const TURN = '1:part-aria';
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER');

const HIT: WeaponHit = {
  turn: TURN, attackLogId: 'log-7', targetId: 'part-goblin', weapon: 'Longsword', critical: false,
  divineSmiteOffered: true, stunningStrikeOffered: false, smitten: false, stunningStrikeUsed: false,
};

function attacker(hit: Partial<WeaponHit> | null, overrides: Partial<EncounterParticipant> = {}) {
  return makeMockParticipant('part-aria', 'Aria', 'PLAYER', {
    isCurrentTurn: true, controlledByUserId: 'user-001',
    turnEconomy: { ...FRESH, lastMeleeHit: hit ? { ...HIT, ...hit } : null },
    spellSlotsCurrent: { 1: { max: 4, used: 1 }, 2: { max: 2, used: 0 } },
    ...overrides,
  });
}

async function renderFollowUps(participant: EncounterParticipant, turnStamp = TURN) {
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <HitFollowUps participant={participant} participants={[participant, goblin]} turnStamp={turnStamp} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return userEvent.setup();
}

describe('HitFollowUps', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('smites the hit with the chosen slot, naming the hit so no other is smitten (PHB p.85, M17)', async () => {
    let body: unknown;
    server.use(http.post('/api/encounters/:id/combat/divine-smite', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(makeMockEncounter());
    }));
    const user = await renderFollowUps(attacker({}));

    expect(screen.getByText('Longsword hit Test Goblin')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Smite slot'), '2');
    await user.click(screen.getByRole('button', { name: /Divine Smite \(3d8 radiant\)/ }));

    await waitFor(() => expect(body).toEqual({ participantId: 'part-aria', slotLevel: 2, attackLogId: 'log-7' }));
  });

  it('doubles the dice it shows for a critical hit', async () => {
    await renderFollowUps(attacker({ critical: true }));

    expect(screen.getByText('Longsword hit Test Goblin (critical hit)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Divine Smite \(4d8 radiant\)/ })).toBeInTheDocument();
  });

  it('shows nothing for a hit on an earlier turn', async () => {
    await renderFollowUps(attacker({}), '2:part-goblin');
    expect(screen.queryByRole('region', { name: 'After the hit' })).not.toBeInTheDocument();
  });

  it('shows nothing once the hit has been smitten', async () => {
    await renderFollowUps(attacker({ smitten: true }));
    expect(screen.queryByRole('button', { name: /Divine Smite/ })).not.toBeInTheDocument();
  });

  it('sends Stunning Strike for a monk\'s hit, and greys it without ki (PHB p.79)', async () => {
    let body: unknown;
    server.use(http.post('/api/encounters/:id/combat/stunning-strike', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(makeMockEncounter());
    }));
    const ki = (uses: number) => [{ poolId: 'class:monk-ki', displayName: 'Ki Points', sourceType: 'CLASS',
      currentUses: uses, maxUses: 5, spendActionType: 'FREE' }];
    const user = await renderFollowUps(attacker({ weapon: 'Unarmed Strike', divineSmiteOffered: false,
      stunningStrikeOffered: true }, { resourcePoolsCurrent: ki(3) }));

    await user.click(screen.getByRole('button', { name: 'Stunning Strike (1 ki, 3 left)' }));
    await waitFor(() => expect(body).toEqual({ participantId: 'part-aria', attackLogId: 'log-7' }));
  });

  it('shows the server\'s refusal', async () => {
    server.use(http.post('/api/encounters/:id/combat/divine-smite', () => HttpResponse.json(
      { error: 'Aria has no level 1 spell slot left' }, { status: 409 })));
    const user = await renderFollowUps(attacker({}));

    await user.click(screen.getByRole('button', { name: /Divine Smite/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Aria has no level 1 spell slot left');
  });
});
