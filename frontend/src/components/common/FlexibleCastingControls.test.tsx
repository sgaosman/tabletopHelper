import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../../test/test-utils';
import type { ResourcePoolEntry } from '../../types/encounter';
import FlexibleCastingControls from './FlexibleCastingControls';

function points(current: number, max = 5): ResourcePoolEntry {
  return {
    poolId: 'class:sorcerer-sorcery-points', displayName: 'Sorcery Points', sourceType: 'CLASS', maxUses: max,
    currentUses: current, resetOn: 'longRest', spendActionType: 'FREE',
  } as ResourcePoolEntry;
}

describe('FlexibleCastingControls', () => {
  it('converts the chosen slot into points and creates a slot by the table (PHB p.101)', async () => {
    const onExchange = vi.fn().mockResolvedValue(undefined);
    render(<FlexibleCastingControls points={points(2)} slots={{ '1': { max: 4, used: 0 }, '2': { max: 3, used: 0 } }}
      onExchange={onExchange} />);
    const user = userEvent.setup();

    await user.selectOptions(screen.getByLabelText('Slot level to convert'), '2');
    await user.click(screen.getByRole('button', { name: 'Convert to 2 points' }));
    expect(onExchange).toHaveBeenCalledWith('SLOT_TO_POINTS', 2);

    // 2 points make only a 1st-level slot
    await user.selectOptions(screen.getByLabelText('Slot level to create'), '2');
    expect(screen.getByRole('button', { name: 'Create slot' })).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Slot level to create'), '1');
    await user.click(screen.getByRole('button', { name: 'Create slot' }));
    expect(onExchange).toHaveBeenLastCalledWith('POINTS_TO_SLOT', 1);
  });

  it('will not convert at the maximum, and greys both for a player whose bonus action is gone', () => {
    const { unmount } = render(<FlexibleCastingControls points={points(5)} slots={{ '1': { max: 4, used: 0 } }}
      onExchange={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Convert' })).toBeDisabled();
    unmount();

    render(<FlexibleCastingControls points={points(3)} slots={{ '1': { max: 4, used: 0 } }}
      onExchange={vi.fn()} blocked="Its bonus action is already used" />);
    expect(screen.getByRole('button', { name: 'Create slot' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Convert to 1 points' })).toBeDisabled();
    expect(screen.getByText('Its bonus action is already used')).toBeInTheDocument();
  });

  it("shows the server's refusal", async () => {
    const refused = vi.fn().mockRejectedValue(new Error('Sorin has no level 1 spell slot left'));
    render(<FlexibleCastingControls points={points(3)} slots={{ '1': { max: 4, used: 0 } }} onExchange={refused} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Convert to 1 points' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
  });
});
