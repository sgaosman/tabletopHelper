import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ALL_CONDITIONS } from '../../utils/conditions';
import { makeMockParticipant } from '../../test/mocks/handlers';
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
  describe('frightened of (D201)', () => {
    const sources = [
      makeMockParticipant('dragon', 'Adult Red Dragon', 'MONSTER'),
      makeMockParticipant('mage', 'Mage', 'MONSTER'),
    ];

    it('asks what the creature is frightened of only for frightened, and sends the source', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined);
      const user = userEvent.setup();
      render(<ConditionForm onSubmit={onSubmit} sources={sources} />);

      expect(screen.queryByLabelText('Frightened of')).not.toBeInTheDocument();
      await user.selectOptions(screen.getByLabelText('Condition'), 'frightened');
      expect(screen.getByLabelText('Frightened of')).toHaveValue('');
      await user.selectOptions(screen.getByLabelText('Frightened of'), 'Adult Red Dragon');
      await user.click(screen.getByRole('button', { name: 'Apply' }));

      expect(onSubmit).toHaveBeenCalledWith('frightened', undefined, 'dragon');
      expect(screen.queryByLabelText('Frightened of')).not.toBeInTheDocument();
    });

    it('sends no source for "No one in particular", nor for another condition chosen after', async () => {
      const onSubmit = vi.fn().mockResolvedValue(undefined);
      const user = userEvent.setup();
      render(<ConditionForm onSubmit={onSubmit} sources={sources} />);

      await user.selectOptions(screen.getByLabelText('Condition'), 'frightened');
      await user.click(screen.getByRole('button', { name: 'Apply' }));
      expect(onSubmit).toHaveBeenLastCalledWith('frightened', undefined);

      await user.selectOptions(screen.getByLabelText('Condition'), 'frightened');
      await user.selectOptions(screen.getByLabelText('Frightened of'), 'Mage');
      await user.selectOptions(screen.getByLabelText('Condition'), 'poisoned');
      await user.click(screen.getByRole('button', { name: 'Apply' }));
      expect(onSubmit).toHaveBeenLastCalledWith('poisoned', undefined);
    });

    it('asks nothing without creatures to choose from', async () => {
      const user = userEvent.setup();
      render(<ConditionForm onSubmit={vi.fn()} />);
      await user.selectOptions(screen.getByLabelText('Condition'), 'frightened');
      expect(screen.queryByLabelText('Frightened of')).not.toBeInTheDocument();
    });
  });
});
