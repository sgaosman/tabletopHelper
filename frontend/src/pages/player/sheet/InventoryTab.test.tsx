import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../../../test/test-utils';
import InventoryTab from './InventoryTab';

describe('InventoryTab', () => {
  const equipment = [
    { name: 'Longsword', quantity: 1 },
    { name: 'Shield', quantity: 1 },
    { name: 'Healing Potion', quantity: 3, description: 'Restores 2d4+2 HP' },
  ];
  const currency = { cp: 15, sp: 0, ep: 0, gp: 120, pp: 5 };

  it('renders currency section with coin type labels', () => {
    render(<InventoryTab equipment={equipment} currency={currency} saveField={async () => {}} />);
    expect(screen.getByText('Currency')).toBeInTheDocument();
    expect(screen.getByText('gp')).toBeInTheDocument();
    expect(screen.getByText('pp')).toBeInTheDocument();
  });

  it('renders equipment by name', () => {
    render(<InventoryTab equipment={equipment} currency={currency} saveField={async () => {}} />);
    expect(screen.getByText('Longsword')).toBeInTheDocument();
    expect(screen.getByText('Healing Potion')).toBeInTheDocument();
  });

  it('shows empty state when no equipment', () => {
    render(<InventoryTab equipment={[]} currency={currency} saveField={async () => {}} />);
    expect(screen.getByText(/No equipment recorded/)).toBeInTheDocument();
  });

  it('calls saveField when editing currency', async () => {
    let saved = false;
    render(<InventoryTab equipment={[]} currency={currency} saveField={async () => { saved = true; }} />);
    const user = userEvent.setup();
    await user.click(screen.getByText('Edit'));
    await user.click(screen.getByText('Save'));
    expect(saved).toBe(true);
  });
});
