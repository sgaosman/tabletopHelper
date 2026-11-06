import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SpellSlotPips from './SpellSlotPips';

// Level 1: one of two spent; level 2: none of one spent
const SLOTS = { '2': { max: 1, used: 0 }, '1': { max: 2, used: 1 } };

describe('SpellSlotPips', () => {
  it('shows nothing for a participant without slots', () => {
    const { container, rerender } = render(<SpellSlotPips slots={null} variant="row" />);
    expect(container).toBeEmptyDOMElement();
    rerender(<SpellSlotPips slots={{}} variant="card" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('a read-only row lists the levels in order, saying for screen readers how many are left', () => {
    render(<SpellSlotPips slots={SLOTS} variant="row" />);
    expect(screen.getAllByText(/^\d:$/).map(el => el.textContent)).toEqual(['1:', '2:']);
    expect(screen.getByText('1 of 2 left')).toHaveClass('sr-only');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('a row spends an unspent slot with onUse and restores a spent one with onRestore', async () => {
    const onUse = vi.fn();
    const onRestore = vi.fn();
    const user = userEvent.setup();
    render(<SpellSlotPips slots={SLOTS} variant="row" onUse={onUse} onRestore={onRestore} />);

    await user.click(screen.getByRole('button', { name: 'Use level 2 slot' }));
    await user.click(screen.getByRole('button', { name: 'Restore level 1 slot' }));
    expect(onUse).toHaveBeenCalledWith(2);
    expect(onRestore).toHaveBeenCalledWith(1);
  });

  it('a card has its heading and counts, and without onRestore a spent slot stays spent', async () => {
    const onUse = vi.fn();
    render(<SpellSlotPips slots={SLOTS} variant="card" onUse={onUse} />);

    expect(screen.getByText('Spell Slots')).toBeInTheDocument();
    expect(screen.getByText('Lv1')).toBeInTheDocument();
    expect(screen.getByText('1/2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Level 1 slot used' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Restore/ })).not.toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole('button', { name: 'Use level 1 slot' }));
    expect(onUse).toHaveBeenCalledWith(1);
  });

  it('a read-only card shows its counts and no buttons', () => {
    render(<SpellSlotPips slots={SLOTS} variant="card" />);
    expect(screen.getByText('1/1')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
