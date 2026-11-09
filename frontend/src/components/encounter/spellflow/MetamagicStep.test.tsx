import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../../../test/test-utils';
import type { MetamagicOption } from '../../../types/combat';
import MetamagicStep from './MetamagicStep';

const options: MetamagicOption[] = [
  { option: 'QUICKENED', name: 'Quickened Spell', cost: 2, costIsCastLevel: false, maxCastLevel: null, ineligibleReason: null },
  { option: 'TWINNED', name: 'Twinned Spell', cost: 2, costIsCastLevel: true, maxCastLevel: 2, ineligibleReason: null },
  {
    option: 'HEIGHTENED', name: 'Heightened Spell', cost: 3, costIsCastLevel: false, maxCastLevel: null,
    ineligibleReason: 'Heightened Spell needs a spell that forces a saving throw',
  },
  { option: 'EMPOWERED', name: 'Empowered Spell', cost: 1, costIsCastLevel: false, maxCastLevel: null, ineligibleReason: null },
];

describe('MetamagicStep', () => {
  it('shows each option with its cost and greys the ineligible with the reason', () => {
    render(<MetamagicStep spellName="Hold Person" castLevel={3} options={options} points={5} chosen={[]}
      onToggle={() => {}} onContinue={() => {}} onBack={() => {}} />);

    expect(screen.getByRole('checkbox', { name: /^Quickened Spell/ })).toBeEnabled();
    // Twinned costs the cast level, and Hold Person can target two creatures at 3rd level
    expect(screen.getByRole('checkbox', { name: /^Twinned Spell/ })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: /^Heightened Spell/ })).toBeDisabled();
    expect(screen.getByText('Heightened Spell needs a spell that forces a saving throw')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue without Metamagic' })).toBeInTheDocument();
  });

  it('allows Empowered beside another option but no second other one', async () => {
    const onToggle = vi.fn();
    render(<MetamagicStep spellName="Hold Person" castLevel={2} options={options} points={3} chosen={['QUICKENED']}
      onToggle={onToggle} onContinue={() => {}} onBack={() => {}} />);

    expect(screen.getByRole('checkbox', { name: /^Twinned Spell/ })).toBeDisabled();
    expect(screen.getByText('Only one option per spell, except Empowered Spell')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /^Empowered Spell/ })).toBeEnabled();
    await userEvent.setup().click(screen.getByRole('checkbox', { name: /^Empowered Spell/ }));
    expect(onToggle).toHaveBeenCalledWith('EMPOWERED');
    expect(screen.getByRole('button', { name: 'Continue' })).toBeInTheDocument();
  });
});
