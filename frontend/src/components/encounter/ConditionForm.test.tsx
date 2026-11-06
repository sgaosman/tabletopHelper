import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ALL_CONDITIONS } from '../../utils/conditions';
import ConditionForm from './ConditionForm';

describe('ConditionForm', () => {
  it('offers the 14 conditions', () => {
    render(<ConditionForm onSubmit={vi.fn()} />);
    const options = screen.getAllByRole('option').map(o => o.getAttribute('value')).filter(Boolean);
    expect(options).toEqual([...ALL_CONDITIONS]);
  });

  it('sends the condition and its rounds, then empties', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ConditionForm onSubmit={onSubmit} />);

    await user.selectOptions(screen.getByLabelText('Condition'), 'prone');
    await user.type(screen.getByLabelText('Duration (rounds)'), '3');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(onSubmit).toHaveBeenCalledWith('prone', 3);
    expect(screen.getByLabelText('Condition')).toHaveValue('');
    expect(screen.getByLabelText('Duration (rounds)')).toHaveValue(null);
  });

  it('sends no duration for a condition until removed, and nothing without a condition', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ConditionForm onSubmit={onSubmit} />);

    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(onSubmit).not.toHaveBeenCalled();

    await user.selectOptions(screen.getByLabelText('Condition'), 'blinded');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(onSubmit).toHaveBeenCalledWith('blinded', undefined);
  });
});
