import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { makeMockParticipant } from '../../test/mocks/handlers';
import HpReadout from './HpReadout';

describe('HpReadout', () => {
  it('shows HP, temporary HP in the wizard colour, and AC', () => {
    const { container } = render(<HpReadout participant={makeMockParticipant('p', 'Gimli', 'PLAYER', { hpMax: 30, hpCurrent: 18, hpTemp: 5, armourClass: 16 })} />);

    expect(screen.getByText('18/30')).toBeInTheDocument();
    expect(screen.getByText('+5')).toHaveClass('text-cls-wizard');
    expect(screen.getByText('16')).toBeInTheDocument();
    expect(container.querySelector('.lucide-shield')).toBeInTheDocument();
  });

  it('shows no AC for a monster whose AC the Player View leaves out (D235)', () => {
    const { container } = render(<HpReadout participant={makeMockParticipant('m', 'Goblin', 'MONSTER', { hpMax: 7, hpCurrent: 7, armourClass: null })} />);

    expect(screen.getByText('7/7')).toBeInTheDocument();
    expect(container.querySelector('.lucide-shield')).not.toBeInTheDocument();
  });

  it('shows no temporary HP when there is none', () => {
    render(<HpReadout participant={makeMockParticipant('p', 'Gimli', 'PLAYER', { hpMax: 30, hpCurrent: 30 })} />);
    expect(screen.queryByText(/^\+/)).not.toBeInTheDocument();
  });
});
