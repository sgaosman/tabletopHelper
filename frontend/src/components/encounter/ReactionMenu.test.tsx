import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ComponentProps } from 'react';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockParticipant, makeMockResourcePool } from '../../test/mocks/handlers';
import type { SpellOption } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import ReactionMenu from './ReactionMenu';

const FRESH = makeMockParticipant('x', 'x', 'PLAYER').turnEconomy!;

// Off its own turn, as a reaction usually is
const wizard = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-wiz', 'Elminster', 'PLAYER', { controlledByUserId: 'user-001', ...overrides });
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { isCurrentTurn: true });

const OPTIONS: SpellOption[] = [
  { name: 'Fire Bolt', level: 0, castingTime: 'ACTION', sources: [{ source: 'NONE', levels: [0] }] },
  { name: 'Shield', level: 1, castingTime: 'REACTION', reactionTrigger: 'which you take when you are hit by an attack',
    sources: [{ source: 'SLOT', levels: [1] }] },
];

// A pool used as a reaction, and one that isn't
const arcaneDeflection = makeMockResourcePool('feat:arcane-deflection', 'Arcane Deflection', { spendActionType: 'REACTION' });
const secondWind = makeMockResourcePool('class:fighter-second-wind', 'Second Wind', { spendActionType: 'BONUS_ACTION' });

async function renderMenu(props: Partial<ComponentProps<typeof ReactionMenu>> = {}) {
  const onClose = vi.fn();
  const participant = props.participant ?? wizard();
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <ReactionMenu participant={participant} participants={[participant, goblin]} allowManualModifiers={false}
        onClose={onClose} {...props} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return { onClose, user: userEvent.setup(), menu: screen.getByRole('dialog', { name: /Reactions/ }) };
}

describe('ReactionMenu', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('opens the weapon attack with the reaction\'s cost for an opportunity attack off the player\'s turn (D174)', async () => {
    let body: unknown;
    server.use(http.post('/api/encounters/:id/combat/attack', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(makeMockEncounter());
    }));
    const { menu, user, onClose } = await renderMenu();

    // The creature itself isn't offered as a target
    expect(within(menu).queryByRole('option', { name: 'Elminster' })).not.toBeInTheDocument();
    await user.selectOptions(within(menu).getByLabelText('Against'), 'Test Goblin');
    expect(await within(menu).findByLabelText('Cost')).toHaveValue('REACTION');
    await user.click(within(menu).getByRole('button', { name: 'Roll Attack' }));

    await waitFor(() => expect(body).toMatchObject({ targetId: 'part-goblin', cost: 'REACTION' }));
    expect(onClose).toHaveBeenCalled();
  });

  it('opens the cast flow with only the spells whose casting time is a reaction', async () => {
    server.use(http.get('/api/encounters/:id/combat/spell-options', () => HttpResponse.json(OPTIONS)));
    const { menu, user } = await renderMenu({ participant: wizard({ spellsKnown: [{ name: 'Shield', level: 1 }] }) });

    await user.click(within(menu).getByRole('button', { name: 'Cast a reaction spell' }));

    const cast = await screen.findByRole('dialog', { name: /Cast a Reaction Spell/ });
    expect(await within(cast).findByText('Shield')).toBeInTheDocument();
    expect(within(cast).queryByText('Fire Bolt')).not.toBeInTheDocument();
  });

  it('offers only a caster the reaction spells', async () => {
    const { menu } = await renderMenu();

    expect(within(menu).queryByRole('button', { name: 'Cast a reaction spell' })).not.toBeInTheDocument();
  });

  it('lists only the pools used as a reaction, and uses one', async () => {
    let body: unknown;
    server.use(http.post('/api/encounters/:id/combat/use-pool', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(makeMockEncounter());
    }));
    const { menu, user } = await renderMenu({ participant: wizard({ resourcePoolsCurrent: [arcaneDeflection, secondWind] }) });

    const pools = within(menu).getByRole('region', { name: 'Reaction pools' });
    expect(within(pools).getByText('Reaction')).toBeInTheDocument();
    expect(within(pools).queryByText('Second Wind')).not.toBeInTheDocument();
    await user.click(within(pools).getByRole('button', { name: 'Use Arcane Deflection' }));

    await waitFor(() => expect(body).toEqual({ participantId: 'part-wiz', poolId: 'feat:arcane-deflection' }));
  });

  it('greys a player\'s reactions with the reason once the reaction is used', async () => {
    const { menu } = await renderMenu({
      participant: wizard({
        spellsKnown: [{ name: 'Shield', level: 1 }], resourcePoolsCurrent: [arcaneDeflection],
        turnEconomy: { ...FRESH, reactionUsed: true },
      }),
    });

    expect(within(menu).getByRole('status')).toHaveTextContent("Reaction already used; it comes back at the start of the creature's turn");
    expect(within(menu).getByLabelText('Against')).toBeDisabled();
    expect(within(menu).getByRole('button', { name: 'Cast a reaction spell' })).toBeDisabled();
    expect(within(menu).getByRole('button', { name: 'Use Arcane Deflection' })).toBeDisabled();
  });

  it('only warns the DM once the reaction is used', async () => {
    const { menu, user } = await renderMenu({
      asDm: true,
      participant: wizard({ resourcePoolsCurrent: [arcaneDeflection], turnEconomy: { ...FRESH, reactionUsed: true } }),
    });

    expect(within(menu).getByRole('status')).toHaveTextContent(/^Over the action economy: Reaction already used/);
    expect(within(menu).getByRole('button', { name: 'Use Arcane Deflection' })).toBeEnabled();
    await user.selectOptions(within(menu).getByLabelText('Against'), 'Test Goblin');
    expect(await within(menu).findByLabelText('Cost')).toHaveValue('REACTION');
  });

  it('shows the server\'s refusal of a pool use', async () => {
    server.use(http.post('/api/encounters/:id/combat/use-pool', () =>
      HttpResponse.json({ error: 'You can only take that action on your turn' }, { status: 409 })));
    const { menu, user } = await renderMenu({ participant: wizard({ resourcePoolsCurrent: [arcaneDeflection] }) });

    await user.click(within(menu).getByRole('button', { name: 'Use Arcane Deflection' }));

    expect(await within(menu).findByRole('alert')).toHaveTextContent('You can only take that action on your turn');
  });

  it('sends the DM to a monster\'s stat-block reactions in the monster panel', async () => {
    const onOpenMonsterReactions = vi.fn();
    const monster = makeMockParticipant('part-bugbear', 'Bugbear', 'MONSTER');
    const { menu, user, onClose } = await renderMenu({ asDm: true, participant: monster, onOpenMonsterReactions });

    await user.click(within(menu).getByRole('button', { name: 'Stat block reactions (monster panel)' }));

    expect(onOpenMonsterReactions).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  it("offers the Protection fighting style's reaction for another creature, and sends it (PHB p.72, M17)", async () => {
    let body: unknown;
    server.use(http.post('/api/encounters/:id/combat/protection', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(makeMockEncounter());
    }));
    const { menu, user, onClose } = await renderMenu({ participant: wizard({ fightingStyles: ['Protection'] }) });

    await user.selectOptions(within(menu).getByLabelText('Protect'), 'Test Goblin');
    await user.click(within(menu).getByRole('button', { name: 'Use Protection' }));

    await waitFor(() => expect(body).toEqual({ participantId: 'part-wiz', protectedId: 'part-goblin' }));
    expect(onClose).toHaveBeenCalled();
  });

  it('offers Protection only with the style', async () => {
    const { menu } = await renderMenu();
    expect(within(menu).queryByRole('button', { name: 'Use Protection' })).not.toBeInTheDocument();
  });
});
