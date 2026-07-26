import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import { render } from '../../../test/test-utils';
import { makeMockCharacter } from '../../../test/mocks/handlers';
import ActionsTab from './ActionsTab';

describe('ActionsTab', () => {
  const char = makeMockCharacter('char-001', 'Gimli', 'Fighter', 5);

  const combatFeatures = [
    { name: 'Extra Attack', description: 'You can attack twice instead of once when you take the Attack action.', source: 'Class' },
    { name: 'Action Surge', description: 'Take one additional action on your turn.', source: 'Class' },
  ];

  const nonCombatFeatures = [
    { name: 'Ritual Caster', description: 'You can cast spells as rituals.', source: 'Feat' },
  ];

  it('renders combat stat cards for spellcasters', () => {
    const caster = { ...char, spellSaveDc: 13, spellAttackBonus: 5 };
    render(<ActionsTab char={caster} features={combatFeatures} />);
    expect(screen.getByText(/Spell Save DC/)).toBeInTheDocument();
    expect(screen.getByText(/Spell Attack/)).toBeInTheDocument();
  });

  it('hides spell stats when character has none', () => {
    render(<ActionsTab char={char} features={combatFeatures} />);
    expect(screen.queryByText('Spell Save DC')).not.toBeInTheDocument();
  });

  it('renders combat-relevant features', () => {
    render(<ActionsTab char={char} features={combatFeatures} />);
    expect(screen.getByText('Extra Attack')).toBeInTheDocument();
    expect(screen.getByText('Action Surge')).toBeInTheDocument();
  });

  it('filters out non-combat features', () => {
    render(<ActionsTab char={char} features={nonCombatFeatures} />);
    expect(screen.queryByText('Ritual Caster')).not.toBeInTheDocument();
  });

  it('shows empty message when no combat features', () => {
    render(<ActionsTab char={char} features={[]} />);
    expect(screen.getByText(/No combat actions configured/)).toBeInTheDocument();
  });

  it('renders both STR and DEX attack bonuses', () => {
    render(<ActionsTab char={char} features={combatFeatures} />);
    expect(screen.getByText('STR melee')).toBeInTheDocument();
    expect(screen.getByText('DEX ranged/finesse')).toBeInTheDocument();
  });
});
