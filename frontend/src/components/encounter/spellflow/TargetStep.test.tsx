import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { makeMockParticipant } from '../../../test/mocks/handlers';
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
});
