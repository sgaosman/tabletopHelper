import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../../test/test-utils';
import ExhaustionControl from './ExhaustionControl';

describe('ExhaustionControl', () => {
  it('steps the level up and down by one and lists what the level does (D183)', async () => {
    const onChange = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ExhaustionControl level={2} name="Gimli" onChange={onChange} />);

    expect(screen.getByLabelText("Gimli's exhaustion level")).toHaveTextContent('2');
    expect(screen.getByText('Disadvantage on ability checks; Speed halved')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: "Raise Gimli's exhaustion" }));
    expect(onChange).toHaveBeenLastCalledWith(3);
    await waitFor(() => expect(screen.getByRole('button', { name: "Lower Gimli's exhaustion" })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: "Lower Gimli's exhaustion" }));
    expect(onChange).toHaveBeenLastCalledWith(1);
  });

  it('stops at 0 and 6, and names the step to 6 as death', () => {
    const { rerender } = render(<ExhaustionControl level={0} name="Gimli" onChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: "Lower Gimli's exhaustion" })).toBeDisabled();

    rerender(<ExhaustionControl level={5} name="Gimli" onChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: "Raise Gimli's exhaustion to 6 (death)" })).toBeEnabled();

    rerender(<ExhaustionControl level={6} name="Gimli" onChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: "Raise Gimli's exhaustion to 6 (death)" })).toBeDisabled();
  });
});
