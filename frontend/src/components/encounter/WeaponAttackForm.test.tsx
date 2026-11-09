import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ComponentProps } from 'react';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockAttackOption, makeMockCondition, makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { EncounterParticipant } from '../../types/encounter';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import WeaponAttackForm from './WeaponAttackForm';

const FRESH = {
  actions: 1, actionsUsed: 0, attackActionTaken: false, attacksLeft: 0, bonusActionUsed: false,
  reactionUsed: false, objectInteractionUsed: false, bonusActionSpellCast: false, otherSpellCast: false,
  dashed: false, disengaged: false,
};

const rogue = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-rogue', 'Vex', 'PLAYER', { isCurrentTurn: true, controlledByUserId: 'user-001', ...overrides });
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { armourClass: 15 });

/** Records each attack request, answering with the encounter, or with the response given. */
function attackRequests(answer?: () => Response) {
  const bodies: unknown[] = [];
  server.use(http.post('/api/encounters/:id/combat/attack', async ({ request }) => {
    bodies.push(await request.json());
    return answer ? answer() : HttpResponse.json(makeMockEncounter());
  }));
  return bodies;
}

async function renderForm(props: Partial<ComponentProps<typeof WeaponAttackForm>> = {}) {
  const onDone = vi.fn();
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <WeaponAttackForm attacker={rogue()} target={goblin} allowManualModifiers={false} onDone={onDone} {...props} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return { onDone, user: userEvent.setup() };
}

describe('WeaponAttackForm', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('makes an attack of the Attack action by default, leaving the cost out (D174)', async () => {
    const bodies = attackRequests();
    const { onDone, user } = await renderForm();

    expect(await screen.findByLabelText('Cost')).toHaveValue('ACTION');
    expect(screen.getByRole('option', { name: 'Off-hand (bonus action)' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Opportunity attack (reaction)' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(bodies).toHaveLength(1);
    expect(bodies[0]).not.toHaveProperty('cost');
  });

  it('sends an off-hand attack as a bonus action', async () => {
    const bodies = attackRequests();
    const { onDone, user } = await renderForm({
      attacker: rogue({ turnEconomy: { ...FRESH, actionsUsed: 1, attackActionTaken: true } }),
    });

    await user.selectOptions(await screen.findByLabelText('Cost'), 'BONUS_ACTION');
    await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(bodies[0]).toMatchObject({ targetId: 'part-goblin', weapon: 'Longsword', cost: 'BONUS_ACTION' });
  });

  it('starts from the cost it is given, as an opportunity attack off the player\'s turn', async () => {
    const bodies = attackRequests();
    const { onDone, user } = await renderForm({ attacker: rogue({ isCurrentTurn: false }), defaultCost: 'REACTION' });

    expect(await screen.findByLabelText('Cost')).toHaveValue('REACTION');
    expect(screen.queryByText(/Not this creature's turn/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(bodies[0]).toMatchObject({ cost: 'REACTION' });
  });

  it('says why the turn can\'t pay for the chosen cost before it is sent', async () => {
    await renderForm();

    await userEvent.setup().selectOptions(await screen.findByLabelText('Cost'), 'BONUS_ACTION');

    expect(screen.getByText('An off-hand attack needs the Attack action first this turn')).toBeInTheDocument();
  });

  it('shows the server\'s reason when it refuses the attack over the action economy', async () => {
    attackRequests(() => HttpResponse.json(
      { error: 'It has already taken its bonus action this turn' }, { status: 409 }));
    const { onDone, user } = await renderForm();

    await user.selectOptions(await screen.findByLabelText('Cost'), 'BONUS_ACTION');
    await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('It has already taken its bonus action this turn');
    expect(onDone).not.toHaveBeenCalled();
  });
  describe('the default cost (D174)', () => {
    const SPENT_ATTACK = { ...FRESH, actionsUsed: 1, attackActionTaken: true, attacksLeft: 0 };

    it('starts on an off-hand attack once the Attack action has no attacks or actions left and the bonus action is free', async () => {
      const bodies = attackRequests();
      const { onDone, user } = await renderForm({ attacker: rogue({ turnEconomy: SPENT_ATTACK }) });

      expect(await screen.findByLabelText('Cost')).toHaveValue('BONUS_ACTION');
      expect(screen.queryByText(/No attacks left/)).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

      await waitFor(() => expect(onDone).toHaveBeenCalled());
      expect(bodies[0]).toMatchObject({ cost: 'BONUS_ACTION' });
    });

    it('keeps the Attack action while an attack of it is left', async () => {
      await renderForm({ attacker: rogue({ turnEconomy: { ...SPENT_ATTACK, attacksLeft: 1 } }) });

      expect(await screen.findByLabelText('Cost')).toHaveValue('ACTION');
    });

    it('stays on Attack, with the reason, when nothing on the turn can pay', async () => {
      await renderForm({ attacker: rogue({ turnEconomy: { ...SPENT_ATTACK, bonusActionUsed: true } }) });

      expect(await screen.findByLabelText('Cost')).toHaveValue('ACTION');
      expect(screen.getByText('No attacks left in the Attack action and no action left this turn')).toBeInTheDocument();
    });

    it("starts on an opportunity attack off the attacker's turn", async () => {
      await renderForm({ attacker: rogue({ isCurrentTurn: false }) });

      expect(await screen.findByLabelText('Cost')).toHaveValue('REACTION');
    });

    it('follows the turn as it is spent, but never overrides a cost the player picked', async () => {
      const ui = (attacker: EncounterParticipant) => (
        <EncounterProvider encounterId="enc-001">
          <WeaponAttackForm attacker={attacker} target={goblin} allowManualModifiers={false} onDone={vi.fn()} />
        </EncounterProvider>
      );
      const { rerender } = render(ui(rogue({ turnEconomy: FRESH })));
      const user = userEvent.setup();
      expect(await screen.findByLabelText('Cost')).toHaveValue('ACTION');

      rerender(ui(rogue({ turnEconomy: SPENT_ATTACK })));
      expect(screen.getByLabelText('Cost')).toHaveValue('BONUS_ACTION');

      await user.selectOptions(screen.getByLabelText('Cost'), 'ACTION');
      rerender(ui(rogue({ turnEconomy: { ...SPENT_ATTACK, actionsUsed: 0, attackActionTaken: false } })));
      rerender(ui(rogue({ turnEconomy: SPENT_ATTACK })));
      expect(screen.getByLabelText('Cost')).toHaveValue('ACTION');
    });
  });

  describe('Reckless Attack (PHB p.48)', () => {
    const barbarianOptions = () => server.use(http.get('/api/encounters/:id/combat/attack-options', () =>
      HttpResponse.json([makeMockAttackOption('Greataxe', { recklessAttack: true }), makeMockAttackOption('Rapier')])));

    it('offers the toggle only where the server does, and sends it with the first attack', async () => {
      barbarianOptions();
      const bodies = attackRequests();
      const { user } = await renderForm({ attacker: rogue({ turnEconomy: FRESH }) });

      await user.click(await screen.findByRole('button', { name: 'Reckless' }));
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

      await waitFor(() => expect(bodies).toHaveLength(1));
      expect(bodies[0]).toMatchObject({ weapon: 'Greataxe', recklessAttack: true });
      await user.selectOptions(screen.getByLabelText('Weapon'), 'Rapier');
      expect(screen.queryByRole('button', { name: 'Reckless' })).not.toBeInTheDocument();
    });

    it("greys the toggle once the turn's first attack is made", async () => {
      barbarianOptions();
      await renderForm({ attacker: rogue({ turnEconomy: { ...FRESH, attackActionTaken: true, attacksLeft: 1 } }) });
      expect(await screen.findByRole('button', { name: 'Reckless' })).toBeDisabled();
    });

    it('shows the advantage a reckless barbarian already has this turn', async () => {
      barbarianOptions();
      await renderForm({ attacker: rogue({
        turnEconomy: { ...FRESH, attackActionTaken: true, attacksLeft: 1 },
        activeConditions: [makeMockCondition('reckless')],
      }) });
      expect(await screen.findByText('Reckless: advantage')).toBeInTheDocument();
    });
  });

  describe('class features on the attack (M17)', () => {
    function serveOptions(...options: ReturnType<typeof makeMockAttackOption>[]) {
      server.use(http.get('/api/encounters/:id/combat/attack-options', () => HttpResponse.json(options)));
    }

    it('sends a versatile weapon held in two hands, showing its two-handed damage', async () => {
      serveOptions(makeMockAttackOption('Longsword', { versatileDamage: '1d10 + 3', features: ['Dueling +2'], damage: '1d8 + 5' }));
      const bodies = attackRequests();
      const { onDone, user } = await renderForm();

      expect(await screen.findByText(/1d8 \+ 5 slashing \(Dueling \+2\)/)).toBeInTheDocument();
      await user.selectOptions(screen.getByLabelText('Grip'), 'TWO_HANDED');
      expect(screen.getByText(/1d10 \+ 3 slashing/)).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

      await waitFor(() => expect(onDone).toHaveBeenCalled());
      expect(bodies[0]).toMatchObject({ weapon: 'Longsword', grip: 'TWO_HANDED' });
    });

    it('leaves Sneak Attack to the roll by default, and sends a claim of an ally next to the target', async () => {
      serveOptions(makeMockAttackOption('Rapier', { sneakAttack: '2d6' }));
      const bodies = attackRequests();
      const { onDone, user } = await renderForm();

      await user.click(await screen.findByRole('button', { name: 'Roll Attack' }));
      await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
      expect(bodies[0]).not.toHaveProperty('sneakAttack');

      await user.selectOptions(screen.getByLabelText('Sneak Attack 2d6'), 'claim');
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));
      await waitFor(() => expect(onDone).toHaveBeenCalledTimes(2));
      expect(bodies[1]).toMatchObject({ weapon: 'Rapier', sneakAttack: true });
    });

    it('says Sneak Attack was already dealt on this turn', async () => {
      serveOptions(makeMockAttackOption('Rapier', { sneakAttack: '2d6' }));
      server.use(http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({ turnStamp: '1:part-rogue' }))));
      await renderForm({ attacker: rogue({ turnEconomy: { ...FRESH, sneakAttackTurn: '1:part-rogue' } }) });

      expect(await screen.findByText('Already dealt this turn')).toBeInTheDocument();
    });

    it('offers a monk Martial Arts and Flurry of Blows, which strike unarmed, and says when they must wait', async () => {
      serveOptions(makeMockAttackOption('Quarterstaff'), makeMockAttackOption('Unarmed Strike', { martialArts: true }));
      const bodies = attackRequests();
      const monk = rogue({
        turnEconomy: { ...FRESH, actionsUsed: 1, attackActionTaken: true, martialArtsAttack: true },
        resourcePoolsCurrent: [{ poolId: 'class:monk-ki', displayName: 'Ki Points', sourceType: 'CLASS', currentUses: 5,
          maxUses: 5, spendActionType: 'FREE' }],
      });
      const { onDone, user } = await renderForm({ attacker: monk });

      await user.selectOptions(await screen.findByLabelText('Cost'), 'FLURRY_OF_BLOWS');
      expect(screen.getByLabelText('Weapon')).toHaveValue('Unarmed Strike');
      await user.click(screen.getByRole('button', { name: 'Roll Attack' }));

      await waitFor(() => expect(onDone).toHaveBeenCalled());
      expect(bodies[0]).toMatchObject({ weapon: 'Unarmed Strike', cost: 'FLURRY_OF_BLOWS' });
    });

    it('offers no monk strikes to a character without Martial Arts or ki', async () => {
      await renderForm();
      expect(await screen.findByLabelText('Cost')).toBeInTheDocument();
      expect(screen.queryByRole('option', { name: /Martial Arts/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('option', { name: /Flurry of Blows/ })).not.toBeInTheDocument();
    });
  });
});
