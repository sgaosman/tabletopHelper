import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCastSpellResponse, makeMockEncounter } from '../../test/mocks/handlers';
import type { ReactionOffer, ReactionOption } from '../../types/combat';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import ReactionPrompt from './ReactionPrompt';
import { describeTrigger } from '../../utils/reactionOffers';

const SHIELD: ReactionOption = {
  kind: 'SPELL', name: 'Shield', spellName: 'Shield', spellSource: 'SLOT', slotLevels: [1, 3],
  targetIds: ['part-wiz'], resolves: true, note: '+5 AC makes it 17: the attack roll of 15 misses, and the hit is taken back.',
};

const hit = (overrides: Partial<ReactionOffer> = {}): ReactionOffer => ({
  id: 'offer-1', reactorId: 'part-wiz', reactorName: 'Elminster', trigger: 'ATTACK_HIT',
  triggerActorId: 'part-goblin', triggerActorName: 'Test Goblin', attackTotal: 15, armourClass: 12, critical: false,
  damageTaken: 4, damageTypes: ['slashing'], options: [SHIELD], ...overrides,
});

/** Records each request to the path, with its actorId, answering with the body given. */
function requests(path: string, response: unknown) {
  const seen: { body: unknown; actorId: string | null }[] = [];
  server.use(http.post(`/api/encounters/:id/combat/${path}`, async ({ request }) => {
    seen.push({ body: await request.json(), actorId: new URL(request.url).searchParams.get('actorId') });
    return HttpResponse.json(response as Record<string, unknown>);
  }));
  return seen;
}

async function renderPrompt(offers: ReactionOffer[]) {
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <ReactionPrompt offers={offers} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return userEvent.setup();
}

describe('describeTrigger', () => {
  it('reads a hit with its roll, AC and damage', () => {
    expect(describeTrigger(hit())).toBe('Test Goblin hits Elminster (attack roll 15 against AC 12), 4 slashing damage.');
    expect(describeTrigger(hit({ critical: true, damageTaken: null }))).toBe(
      'Test Goblin critically hits Elminster (attack roll 15 against AC 12).');
  });

  it('reads damage and a spell cast', () => {
    expect(describeTrigger(hit({ trigger: 'DAMAGE', damageTaken: 6, damageTypes: ['fire'] })))
      .toBe('Test Goblin deals 6 fire damage to Elminster.');
    expect(describeTrigger(hit({ trigger: 'SPELL_CAST', spellName: 'Fireball', spellLevel: 3, triggerActorName: 'Mage' })))
      .toBe('Mage casts Fireball at level 3.');
    expect(describeTrigger(hit({ trigger: 'SPELL_CAST', spellName: 'Fire Bolt', spellLevel: 0, triggerActorName: 'Mage' })))
      .toBe('Mage casts Fire Bolt.');
  });
});

describe('ReactionPrompt', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('renders nothing without offers', async () => {
    await renderPrompt([]);
    expect(screen.queryByRole('region', { name: 'Reaction prompts' })).not.toBeInTheDocument();
  });

  it('lists the offer with what happened, its options and their notes (D216)', async () => {
    await renderPrompt([hit()]);
    const region = screen.getByRole('region', { name: 'Reaction prompts' });
    const offer = within(region).getByRole('listitem', { name: 'Elminster can react' });
    expect(within(offer).getByText(/Test Goblin hits Elminster/)).toBeInTheDocument();
    expect(within(offer).getByRole('button', { name: 'Shield' })).toBeInTheDocument();
    expect(within(offer).getByText(/the hit is taken back/)).toBeInTheDocument();
    expect(within(offer).getByRole('button', { name: "Skip Elminster's reaction" })).toBeInTheDocument();
  });

  it('casts Shield through cast-spell for the reacting creature, naming the offer', async () => {
    const seen = requests('cast-spell', makeMockCastSpellResponse());
    const user = await renderPrompt([hit()]);

    await user.click(screen.getByRole('button', { name: 'Shield' }));

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].actorId).toBe('part-wiz');
    expect(seen[0].body).toMatchObject({ spellName: 'Shield', spellSource: 'SLOT', slotLevel: 1,
      targetIds: ['part-wiz'], reactionOfferId: 'offer-1' });
  });

  it('casts at the slot level chosen when several are offered', async () => {
    const seen = requests('cast-spell', makeMockCastSpellResponse());
    const user = await renderPrompt([hit()]);

    await user.selectOptions(screen.getByLabelText('Shield slot level'), 'Level 3');
    await user.click(screen.getByRole('button', { name: 'Shield' }));

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].body).toMatchObject({ slotLevel: 3 });
  });

  it("uses a pool with the damage taken, as Stone's Endurance reads it", async () => {
    const seen = requests('use-pool', makeMockEncounter());
    const user = await renderPrompt([hit({ trigger: 'DAMAGE', options: [{ kind: 'POOL', name: "Stone's Endurance",
      poolId: 'race:stones-endurance', amount: 9, resolves: true }] })]);

    await user.click(screen.getByRole('button', { name: "Stone's Endurance" }));

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].body).toMatchObject({ participantId: 'part-wiz', poolId: 'race:stones-endurance', amounts: [9] });
  });

  it("takes a monster's stat-block reaction as a REACTION monster action, marked for the DM", async () => {
    const seen = requests('monster-action', { encounterState: makeMockEncounter(), results: [] });
    const user = await renderPrompt([hit({ reactorId: 'part-goblin', reactorName: 'Test Goblin', options: [
      { kind: 'MONSTER_REACTION', name: 'Parry', actionName: 'Parry', resolves: false, note: '+2 AC against one melee hit.' },
    ] })]);

    expect(screen.getByText(/\(The DM adjusts by hand\.\)/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Parry' }));

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].body).toMatchObject({ monsterParticipantId: 'part-goblin', actionName: 'Parry',
      actionSource: 'REACTION', targetParticipantIds: [] });
  });

  it('skips the offer through dismiss-reaction', async () => {
    const seen = requests('dismiss-reaction', makeMockEncounter());
    const user = await renderPrompt([hit()]);

    await user.click(screen.getByRole('button', { name: "Skip Elminster's reaction" }));

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].body).toEqual({ offerId: 'offer-1' });
  });

  it("shows the server's refusal", async () => {
    server.use(http.post('/api/encounters/:id/combat/cast-spell', () =>
      HttpResponse.json({ error: 'It has already taken its reaction this round' }, { status: 409 })));
    const user = await renderPrompt([hit()]);

    await user.click(screen.getByRole('button', { name: 'Shield' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('It has already taken its reaction this round');
  });
});
