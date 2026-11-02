import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Trash2 } from 'lucide-react';
import IconButton from './IconButton';

describe('IconButton', () => {
  it('is announced by its label, which is also its tooltip', () => {
    render(<IconButton label="Delete character"><Trash2 /></IconButton>);
    const button = screen.getByRole('button', { name: 'Delete character' });

    expect(button).toHaveAccessibleName('Delete character');
    expect(button).toHaveAttribute('title', 'Delete character');
  });

  it('names a button whose content is a symbol after its label, not the symbol', () => {
    render(<IconButton label="Spend one Rage use">−</IconButton>);

    expect(screen.getByRole('button', { name: 'Spend one Rage use' })).toHaveTextContent('−');
  });

  it('is a plain button by default and passes other props through', async () => {
    const onClick = vi.fn();
    render(<IconButton label="Next page" onClick={onClick} className="p-2"><Trash2 /></IconButton>);
    const button = screen.getByRole('button', { name: 'Next page' });

    await userEvent.click(button);

    expect(button).toHaveAttribute('type', 'button');
    expect(button).toHaveClass('p-2');
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('can be disabled', () => {
    render(<IconButton label="Previous page" disabled><Trash2 /></IconButton>);

    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
  });

  it('keeps its label over an aria-label passed alongside it', () => {
    render(<IconButton label="Close" aria-label="Dismiss"><Trash2 /></IconButton>);

    expect(screen.getByRole('button')).toHaveAccessibleName('Close');
  });

  it('does not compile without a label', () => {
    // @ts-expect-error: an IconButton must have a label
    const unlabelled = <IconButton><Trash2 /></IconButton>;

    expect(unlabelled.props).not.toHaveProperty('label');
  });
});
