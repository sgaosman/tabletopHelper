import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { Sparkles } from 'lucide-react';
import { makeMockParticipant } from '../../../test/mocks/handlers';
import ConfirmStep from './ConfirmStep';

const wizard = makeMockParticipant('part-wizard', 'Elminster', 'PLAYER', {
  spellAttackBonus: 7, spellSaveDc: 15, concentrationSpell: 'Haste',
});
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER');
const orc = makeMockParticipant('part-orc', 'Orc', 'MONSTER');

function renderStep(props: Partial<ComponentProps<typeof ConfirmStep>> = {}) {
  const handlers = {
    onAdvantageChange: vi.fn(), onOverrideAttackBonus: vi.fn(), onOverrideSaveDC: vi.fn(), onSubmit: vi.fn(),
  };
  render(
    <ConfirmStep
      spellName="Hold Person" caster={wizard} targets={[goblin, orc]} advantage={null}
      allowManualModifiers={false} overrideAttackBonus="" overrideSaveDC=""
      submitLabel="Cast Spell" busyLabel="Casting..." submitIcon={Sparkles} submitting={false}
      {...handlers} {...props}
    />,
  );
  return { ...handlers, user: userEvent.setup() };
}

describe('ConfirmStep', () => {
  it('sums up the spell, its level, its cost and its targets', () => {
    renderStep({ levelLabel: 'Level 2', cost: 'Using level 3 slot' });

    expect(screen.getByText('Hold Person')).toBeInTheDocument();
    expect(screen.getByText('Level 2')).toBeInTheDocument();
    expect(screen.getByText('Using level 3 slot')).toBeInTheDocument();
    expect(screen.getByText('Targeting: Test Goblin, Orc')).toBeInTheDocument();
  });

  it("shows the casting time, a reaction's trigger and why the turn can't pay for it (D174)", () => {
    renderStep({
      castingTime: 'REACTION', reactionTrigger: 'which you take when you are hit by an attack',
      economyWarning: 'Reaction already used',
    });

    expect(screen.getByText('Reaction')).toBeInTheDocument();
    expect(screen.getByText('Cast as a reaction, which you take when you are hit by an attack')).toBeInTheDocument();
    expect(screen.getByText('Reaction already used')).toBeInTheDocument();
  });

  it('shows no casting time or warning unless given', () => {
    renderStep();
    expect(screen.queryByText(/Casting time/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Cast as a reaction/)).not.toBeInTheDocument();
  });

  it('says None when there are no targets', () => {
    renderStep({ targets: [] });
    expect(screen.getByText('Targeting: None')).toBeInTheDocument();
  });

  it('warns that concentration drops only when asked to', () => {
    renderStep({ warnConcentration: true });
    expect(screen.getByText(/Will drop concentration on/)).toHaveTextContent('Haste');
  });

  it('gives no concentration warning by default', () => {
    renderStep();
    expect(screen.queryByText(/Will drop concentration on/)).not.toBeInTheDocument();
  });

  it('marks the chosen advantage as pressed and reports a new choice', async () => {
    const { user, onAdvantageChange } = renderStep({ advantage: false });

    expect(screen.getByRole('button', { name: 'Disadvantage' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Normal' })).toHaveAttribute('aria-pressed', 'false');
    await user.click(screen.getByRole('button', { name: 'Advantage' }));
    await user.click(screen.getByRole('button', { name: 'Normal' }));

    expect(onAdvantageChange.mock.calls).toEqual([[true], [null]]);
  });

  it("offers Manual Modifiers with the caster's own numbers as placeholders when allowed (D164)", async () => {
    const { user, onOverrideSaveDC } = renderStep({ allowManualModifiers: true });

    expect(screen.getByLabelText('Spell Attack Bonus')).toHaveAttribute('placeholder', '7');
    expect(screen.getByLabelText('Spell Save DC')).toHaveAttribute('placeholder', '15');
    expect(screen.getByText(/noted in the combat log/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Spell Save DC'), '9');

    expect(onOverrideSaveDC).toHaveBeenCalledWith('9');
  });

  it('offers no Manual Modifiers unless allowed', () => {
    renderStep();
    expect(screen.queryByLabelText('Spell Attack Bonus')).not.toBeInTheDocument();
  });

  it('submits under its own label', async () => {
    const { user, onSubmit } = renderStep({ submitLabel: 'Repeat Effect', busyLabel: 'Repeating...' });

    await user.click(screen.getByRole('button', { name: 'Repeat Effect' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('shows the busy label while submitting', () => {
    renderStep({ submitting: true });
    expect(screen.getByRole('button', { name: /Casting\.\.\./ })).toBeInTheDocument();
  });

  it('goes back from the Back button', async () => {
    const onBack = vi.fn();
    const { user } = renderStep({ onBack });

    await user.click(screen.getByRole('button', { name: /Back/ }));

    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('has no Back button without a step before', () => {
    renderStep();
    expect(screen.queryByRole('button', { name: /Back/ })).not.toBeInTheDocument();
  });
});
