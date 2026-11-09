import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import ArcaneRecoveryPicker from './ArcaneRecoveryPicker';
import type { ArcaneRecoveryChoice } from '../../utils/classFeatures';

const SLOTS = { 1: { max: 4, used: 3 }, 2: { max: 3, used: 1 }, 3: { max: 2, used: 0 }, 6: { max: 1, used: 1 } };

function Harness({ onChange, usedToday = false }: { onChange: (choice: ArcaneRecoveryChoice) => void; usedToday?: boolean }) {
  const [choice, setChoice] = useState<ArcaneRecoveryChoice>({});
  return (
    <ArcaneRecoveryPicker wizardLevel={5} slots={SLOTS} usedToday={usedToday} choice={choice}
      onChange={next => { setChoice(next); onChange(next); }} />
  );
}

describe('ArcaneRecoveryPicker (PHB p.115, M17)', () => {
  it('offers only expended slots of 5th level or lower, up to half the wizard level rounded up', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    const user = userEvent.setup();

    expect(screen.queryByText('Level 3')).not.toBeInTheDocument();
    expect(screen.queryByText('Level 6')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Recover one more level 2 slot' }));
    expect(screen.getByRole('button', { name: 'Recover one more level 2 slot' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Recover one more level 1 slot' }));

    expect(onChange).toHaveBeenLastCalledWith({ 1: 1, 2: 1 });
    expect(screen.getByText('(3/3 slot levels)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Recover one more level 1 slot' })).toBeDisabled();
  });

  it('says it was used since the last long rest', () => {
    render(<Harness onChange={vi.fn()} usedToday />);
    expect(screen.getByText('Used since your last long rest.')).toBeInTheDocument();
  });
});
