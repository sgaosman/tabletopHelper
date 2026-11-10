import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { makeMockCondition, makeMockParticipant } from '../../../test/mocks/handlers';
import type { SpellTargetingInfo } from '../../../types/reference';
import TargetStep from './TargetStep';

const cleric = makeMockParticipant('part-cleric', 'Brother Tuck', 'PLAYER');
const legolas = makeMockParticipant('part-legolas', 'Legolas', 'PLAYER');
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { armourClass: 15 });
const kobold = makeMockParticipant('part-kobold', 'Fallen Kobold', 'MONSTER', { hpCurrent: 0, lifeState: 'DEAD' });
const participants = [cleric, legolas, goblin, kobold];

const ANYONE: SpellTargetingInfo = {
  maxTargets: -1, selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true,
  targetType: 'SINGLE_TARGET', hasRepeatEffect: false,
};

function renderStep(props: Partial<ComponentProps<typeof TargetStep>> = {}) {
  const handlers = { onToggle: vi.fn(), onProceed: vi.fn() };
  render(
    <TargetStep
      spellName="Bless" caster={cleric} participants={participants} selectedTargets={[]}
      targetingInfo={ANYONE} targetingLoading={false} {...handlers} {...props}
    />,
  );
  return { ...handlers, user: userEvent.setup() };
}

function offered() {
  return screen.getAllByRole('button', { name: /AC \d+/ })
    .map(b => within(b).getByText(/Brother Tuck|Legolas|Test Goblin|Fallen Kobold/).textContent);
}

describe('TargetStep', () => {
  it("leaves out a banished creature and one inside Otiluke's Resilient Sphere (D185)", () => {
    const banished = { ...goblin, activeConditions: [makeMockCondition('banished')] };
    const enclosed = { ...kobold, lifeState: 'ALIVE' as const, activeConditions: [makeMockCondition('enclosed')] };
    renderStep({ participants: [cleric, legolas, banished, enclosed] });
    expect(offered()).toEqual(['Brother Tuck', 'Legolas']);
  });

  it('offers the caster and everyone still alive, but not the dead', () => {
    renderStep();

    expect(offered()).toEqual(['Brother Tuck', 'Legolas', 'Test Goblin']);
    expect(screen.getByRole('button', { name: /Brother Tuck/ })).toHaveTextContent('(self)');
  });

  it('offers only allies or only enemies as the spell allows', () => {
    renderStep({ targetingInfo: { ...ANYONE, canTargetSelf: false, canTargetEnemies: false } });
    expect(offered()).toEqual(['Legolas']);
  });

  it('offers a monster caster the monsters as its allies', () => {
    const shaman = makeMockParticipant('part-shaman', 'Goblin Shaman', 'MONSTER');
    render(
      <TargetStep
        spellName="Bless" caster={shaman} participants={[shaman, ...participants]} selectedTargets={[]}
        targetingInfo={{ ...ANYONE, canTargetSelf: false, canTargetAllies: false }} targetingLoading={false}
        onToggle={vi.fn()} onProceed={vi.fn()}
      />,
    );
    expect(offered()).toEqual(['Brother Tuck', 'Legolas']);
  });

  it('offers only the caster for a self-only spell', () => {
    renderStep({ targetingInfo: { ...ANYONE, selfOnly: true } });
    expect(offered()).toEqual(['Brother Tuck']);
  });

  it('offers everyone still alive when the targeting is unknown', () => {
    renderStep({ targetingInfo: null });
    expect(offered()).toEqual(['Brother Tuck', 'Legolas', 'Test Goblin']);
    expect(screen.getByText('— Select target(s)')).toBeInTheDocument();
  });

  it('shows nothing to pick while the targeting loads', () => {
    renderStep({ targetingLoading: true });

    expect(screen.getByText('Loading targeting info...')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /AC \d+/ })).not.toBeInTheDocument();
  });

  it('toggles a target, and disables the rest at the spell limit', async () => {
    const { user, onToggle } = renderStep({ targetingInfo: { ...ANYONE, maxTargets: 1 }, selectedTargets: ['part-goblin'] });

    expect(screen.getByText('— Select targets (1/1)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Test Goblin/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Legolas/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /Test Goblin/ }));

    expect(onToggle).toHaveBeenCalledWith('part-goblin');
  });

  it('continues only once a target is chosen', async () => {
    const { user, onProceed } = renderStep({ selectedTargets: ['part-legolas'] });

    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(onProceed).toHaveBeenCalledTimes(1);
  });

  it('cannot continue with no target chosen', () => {
    renderStep();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
  });

  it('has no Back button without a step before', () => {
    renderStep();
    expect(screen.queryByRole('button', { name: /Back/ })).not.toBeInTheDocument();
  });

  it('goes back from the Back button', async () => {
    const onBack = vi.fn();
    const { user } = renderStep({ onBack });

    await user.click(screen.getByRole('button', { name: /Back/ }));

    expect(onBack).toHaveBeenCalledTimes(1);
  });

  describe('target roles (D200)', () => {
    const iceKnife = { kind: 'split', main: 1, pickedWithin: 5 } as const;

    it("labels Ice Knife's first target the main target and the rest picked within 5 ft", () => {
      renderStep({ spellName: 'Ice Knife', roles: iceKnife, selectedTargets: ['part-goblin', 'part-legolas'] });

      expect(screen.getByText(/the first creature chosen is the main target/i)).toBeInTheDocument();
      expect(within(screen.getByRole('button', { name: /^Test Goblin/ })).getByText('Main target')).toBeInTheDocument();
      expect(within(screen.getByRole('button', { name: /^Legolas/ })).getByText('Picked (within 5 ft)')).toBeInTheDocument();
      expect(within(screen.getByRole('button', { name: /^Brother Tuck/ })).queryByText(/Main target|Picked/)).toBeNull();
    });

    it('makes a picked creature the main target', async () => {
      const onChangeTargets = vi.fn();
      const { user } = renderStep({ roles: iceKnife, selectedTargets: ['part-goblin', 'part-legolas'], onChangeTargets });

      expect(screen.queryByRole('button', { name: 'Make Test Goblin the main target' })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Make Legolas the main target' }));

      expect(onChangeTargets).toHaveBeenCalledWith(['part-legolas', 'part-goblin']);
    });

    it('labels every target of Hail of Thorns picked, with no main target to make', () => {
      renderStep({ roles: { kind: 'split', main: 0, pickedWithin: 5 }, selectedTargets: ['part-goblin'] });

      expect(screen.getByText('Pick the creatures within 5 ft of the creature your next attack is against.')).toBeInTheDocument();
      expect(within(screen.getByRole('button', { name: /^Test Goblin/ })).getByText('Picked (within 5 ft)')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /the main target/ })).not.toBeInTheDocument();
    });

    it("shares Magic Missile's darts with a counter for each creature, sending a repeated target for each dart", async () => {
      const onChangeTargets = vi.fn();
      const { user } = renderStep({
        spellName: 'Magic Missile', roles: { kind: 'darts', count: 3 }, selectedTargets: ['part-goblin', 'part-goblin'],
        onChangeTargets,
      });

      expect(screen.getByText(/Assign darts \(2\/3\)/)).toBeInTheDocument();
      expect(within(screen.getByRole('group', { name: 'Darts at Test Goblin' })).getByText('2')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'One dart fewer at Legolas' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'One more dart at Legolas' }));
      expect(onChangeTargets).toHaveBeenLastCalledWith(['part-goblin', 'part-goblin', 'part-legolas']);
      await user.click(screen.getByRole('button', { name: 'One dart fewer at Test Goblin' }));
      expect(onChangeTargets).toHaveBeenLastCalledWith(['part-goblin']);
    });

    it('continues once every dart is assigned, and adds none past the count', () => {
      renderStep({ roles: { kind: 'darts', count: 3 }, selectedTargets: ['part-goblin', 'part-legolas', 'part-goblin'] });

      expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'One more dart at Brother Tuck' })).toBeDisabled();
      expect(screen.queryByRole('button', { name: /AC \d+/ })).not.toBeInTheDocument();
    });
  });
});
