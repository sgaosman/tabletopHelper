import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import NumberStepper from './NumberStepper';

function Harness({ initial = 1, min = 1, max = 20, onChange = vi.fn() }: {
  initial?: number; min?: number; max?: number; onChange?: (n: number) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <NumberStepper
      value={value}
      min={min}
      max={max}
      label="Starting level"
      onChange={n => { setValue(n); onChange(n); }}
    />
  );
}

describe('NumberStepper', () => {
  it('applies a typed level straight away', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    const box = screen.getByLabelText('Starting level');

    await userEvent.clear(box);
    await userEvent.type(box, '12');

    expect(onChange).toHaveBeenLastCalledWith(12);
    expect(box).toHaveValue('12');
  });

  it('pulls an out-of-range level back to the maximum when you leave the box', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    const box = screen.getByLabelText('Starting level');

    await userEvent.clear(box);
    await userEvent.type(box, '25');
    await userEvent.tab();

    expect(box).toHaveValue('20');
    expect(onChange).toHaveBeenLastCalledWith(20);
  });

  it('ignores letters', async () => {
    render(<Harness initial={4} />);
    const box = screen.getByLabelText('Starting level');

    await userEvent.type(box, 'abc');

    expect(box).toHaveValue('4');
  });

  it('steps with the up and down buttons and stops at the limits', async () => {
    render(<Harness initial={19} />);
    const up = screen.getByRole('button', { name: 'Increase starting level' });
    const down = screen.getByRole('button', { name: 'Decrease starting level' });

    await userEvent.click(up);
    expect(screen.getByLabelText('Starting level')).toHaveValue('20');
    expect(up).toBeDisabled();

    await userEvent.click(down);
    expect(screen.getByLabelText('Starting level')).toHaveValue('19');
    expect(down).toBeEnabled();
  });

  it('steps with the arrow keys', async () => {
    render(<Harness initial={5} />);
    const box = screen.getByLabelText('Starting level');

    box.focus();
    await userEvent.keyboard('{ArrowUp}{ArrowUp}{ArrowDown}');

    expect(box).toHaveValue('6');
  });
});
