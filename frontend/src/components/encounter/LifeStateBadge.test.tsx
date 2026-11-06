import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { makeMockParticipant } from '../../test/mocks/handlers';
import LifeStateBadge from './LifeStateBadge';

describe('LifeStateBadge', () => {
  it('names a participant that is Dying, Stable or Dead (D161)', () => {
    const { rerender } = render(<LifeStateBadge participant={makeMockParticipant('p', 'Gimli', 'PLAYER', { lifeState: 'DYING' })} />);
    expect(screen.getByText('Dying')).toHaveClass('text-debuff');

    rerender(<LifeStateBadge participant={makeMockParticipant('p', 'Gimli', 'PLAYER', { lifeState: 'STABLE' })} />);
    expect(screen.getByText('Stable')).toHaveClass('text-buff');

    rerender(<LifeStateBadge participant={makeMockParticipant('p', 'Gimli', 'PLAYER', { lifeState: 'DEAD' })} />);
    expect(screen.getByText('Dead')).toBeInTheDocument();
  });

  it('shows nothing while the participant is up', () => {
    const { container } = render(<LifeStateBadge participant={makeMockParticipant('p', 'Gimli', 'PLAYER')} />);
    expect(container).toBeEmptyDOMElement();
  });
});
