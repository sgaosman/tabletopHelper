import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import TieOrderArrows from './TieOrderArrows';
import type { TurnEntry } from '../../types/encounter';

const turn = (participantId: string, tieGroup: number | null): TurnEntry =>
  ({ participantId, name: participantId, initiative: 12, lair: false, skipped: false, current: false, tieGroup });

// Three participants tied on 12, then one on its own
const TURNS = [turn('aria', 0), turn('bram', 0), turn('cass', 0), turn('goblin', null)];

describe('TieOrderArrows', () => {
  it('moves a participant up or down its tie, sending the whole tie in its new order', async () => {
    const onReorder = vi.fn();
    const user = userEvent.setup();
    render(<TieOrderArrows turns={TURNS} index={1} name="Bram" onReorder={onReorder} />);

    await user.click(screen.getByRole('button', { name: 'Move Bram up the tie' }));
    await user.click(screen.getByRole('button', { name: 'Move Bram down the tie' }));

    expect(onReorder.mock.calls).toEqual([[['bram', 'aria', 'cass']], [['aria', 'cass', 'bram']]]);
  });

  it('can\'t move the first of a tie up or the last down', () => {
    const { rerender } = render(<TieOrderArrows turns={TURNS} index={0} name="Aria" onReorder={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Move Aria up the tie' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Move Aria down the tie' })).toBeEnabled();

    rerender(<TieOrderArrows turns={TURNS} index={2} name="Cass" onReorder={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Move Cass up the tie' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Move Cass down the tie' })).toBeDisabled();
  });

  it('shows nothing for a turn tied with no other, or one not in the order', () => {
    const { container, rerender } = render(<TieOrderArrows turns={TURNS} index={3} name="Goblin" onReorder={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();

    rerender(<TieOrderArrows turns={TURNS} index={-1} name="Nobody" onReorder={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });
});
