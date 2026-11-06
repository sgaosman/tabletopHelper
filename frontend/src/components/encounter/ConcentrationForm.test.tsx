import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ConcentrationForm from './ConcentrationForm';

describe('ConcentrationForm', () => {
  it('sends the spell named, then empties', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ConcentrationForm onSubmit={onSubmit} />);

    await user.type(screen.getByLabelText('Spell Name (blank to clear)'), 'Bless');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(onSubmit).toHaveBeenCalledWith('Bless');
    expect(screen.getByLabelText('Spell Name (blank to clear)')).toHaveValue('');
  });

  it('clears concentration when left blank', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<ConcentrationForm onSubmit={onSubmit} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Apply' }));
    expect(onSubmit).toHaveBeenCalledWith(null);
  });
});
