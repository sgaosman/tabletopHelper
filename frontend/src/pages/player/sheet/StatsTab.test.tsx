import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import { render } from '../../../test/test-utils';
import { makeMockCharacter } from '../../../test/mocks/handlers';
import StatsTab from './StatsTab';

describe('StatsTab', () => {
  const char = makeMockCharacter('char-001', 'Gimli', 'Fighter', 5);

  const defaultProps = {
    char,
    savingThrows: ['str', 'con'],
    skillProfs: ['athletics', 'perception'],
    skillExpertises: [] as string[],
    resistances: [] as string[],
  };

  it('renders all major sections', () => {
    render(<StatsTab {...defaultProps} />);
    expect(screen.getByText('Saving Throws')).toBeInTheDocument();
    expect(screen.getByText('Skills')).toBeInTheDocument();
    expect(screen.getByText('Proficiency')).toBeInTheDocument();
    expect(screen.getByText('Passive Perception')).toBeInTheDocument();
  });

  it('shows expertise star icon', () => {
    render(<StatsTab {...defaultProps} skillExpertises={['athletics']} />);
    expect(screen.getByText('Proficient')).toBeInTheDocument();
    expect(screen.getByText('Expertise')).toBeInTheDocument();
  });

  it('shows proficiency section when character has proficiencies', () => {
    render(<StatsTab {...defaultProps} />);
    expect(screen.getByText('Proficiencies')).toBeInTheDocument();
  });

  it('handles empty resistances array', () => {
    render(<StatsTab {...defaultProps} resistances={[]} />);
    expect(screen.queryByText('Damage Resistances')).not.toBeInTheDocument();
  });

  it('renders stat grid cards', () => {
    render(<StatsTab {...defaultProps} />);
    expect(screen.getByText('XP')).toBeInTheDocument();
    expect(screen.getByText('Hit Dice')).toBeInTheDocument();
  });
});
