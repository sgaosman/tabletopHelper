import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockParticipant } from '../../test/mocks/handlers';
import ActiveSpells from './ActiveSpells';

const cleric = makeMockParticipant('p', 'Brother Tuck', 'PLAYER', { concentrationSpell: 'Bless', activeSpell: 'Spiritual Weapon' });

describe('ActiveSpells', () => {
  it('shows the spell concentrated on and the active one, with no buttons without onRepeat', () => {
    render(<ActiveSpells participant={cleric} />);
    expect(screen.getByText('Concentrating: Bless')).toBeInTheDocument();
    expect(screen.getByText('Active: Spiritual Weapon')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('repeats a spell\'s effect with onRepeat', async () => {
    const onRepeat = vi.fn();
    render(<ActiveSpells participant={cleric} onRepeat={onRepeat} />);

    const buttons = screen.getAllByRole('button', { name: 'Repeat spell effect' });
    expect(buttons).toHaveLength(2);
    await userEvent.setup().click(buttons[0]);
    expect(onRepeat).toHaveBeenCalledTimes(1);
  });

  it('shows nothing for a participant with neither', () => {
    const { container } = render(<ActiveSpells participant={makeMockParticipant('p', 'Gimli', 'PLAYER')} />);
    expect(container).toBeEmptyDOMElement();
  });
});
