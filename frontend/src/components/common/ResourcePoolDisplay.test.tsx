import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ResourcePoolEntry } from '../../types/encounter';
import ResourcePoolDisplay from './ResourcePoolDisplay';

const rage = {
  poolId: 'class:barbarian:rage', displayName: 'Rage', sourceType: 'CLASS', sourceName: 'Barbarian',
  maxUses: 3, currentUses: 2, resetOn: 'LONG_REST', icon: 'flame',
} satisfies ResourcePoolEntry;
const luck = {
  poolId: 'feat:lucky', displayName: 'Luck Points', sourceType: 'FEAT', sourceName: '',
  maxUses: 3, currentUses: 0, resetOn: 'LONG_REST',
} satisfies ResourcePoolEntry;
const breath = {
  poolId: 'monster:recharge:fire-breath', displayName: 'Fire Breath', sourceType: 'MONSTER', sourceName: 'Red Dragon',
  maxUses: 1, currentUses: 1, resetOn: 'RECHARGE', resetCheck: '1d6>=5',
} satisfies ResourcePoolEntry;

const pools = (...entries: ResourcePoolEntry[]) => JSON.stringify(entries);

describe('ResourcePoolDisplay', () => {
  it.each([
    ['missing', undefined],
    ['null', null],
    ['an empty list', '[]'],
    ['invalid JSON', '{not json'],
    ['JSON that is not a list', '{"poolId":"x"}'],
  ])('renders nothing when the pools are %s', (_label, value) => {
    const { container } = render(<ResourcePoolDisplay pools={value} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows each pool with its icon and remaining uses', () => {
    render(<ResourcePoolDisplay pools={pools(rage, luck)} />);

    expect(screen.getByText('Rage').parentElement).toHaveTextContent('🔥Rage2/3');
    expect(screen.getByText('Luck Points').parentElement).toHaveTextContent('●Luck Points0/3');
  });

  it('describes the source and reset rule in the tooltip', () => {
    render(<ResourcePoolDisplay pools={pools(rage, luck, breath)} />);

    expect(screen.getByTitle('Rage — Barbarian — resets on LONG_REST')).toBeInTheDocument();
    expect(screen.getByTitle('Luck Points — FEAT — resets on LONG_REST')).toBeInTheDocument();
    expect(screen.getByTitle('Fire Breath — Red Dragon — resets on RECHARGE (recharge 1d6>=5)')).toBeInTheDocument();
  });

  it('shows only the pools from the requested sources', () => {
    render(<ResourcePoolDisplay pools={pools(rage, luck, breath)} filterSourceType={['CLASS', 'MONSTER']} />);

    expect(screen.getByText('Rage')).toBeInTheDocument();
    expect(screen.getByText('Fire Breath')).toBeInTheDocument();
    expect(screen.queryByText('Luck Points')).not.toBeInTheDocument();
  });

  it('renders nothing when the filter matches no pool', () => {
    const { container } = render(<ResourcePoolDisplay pools={pools(rage)} filterSourceType={['RACE']} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('has no spend or recover buttons unless editable', () => {
    render(<ResourcePoolDisplay pools={pools(rage)} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('reports spends and recoveries by pool id when editable', async () => {
    const onSpend = vi.fn();
    const onRecover = vi.fn();
    const user = userEvent.setup();
    render(<ResourcePoolDisplay pools={pools(rage)} editable onSpend={onSpend} onRecover={onRecover} />);

    await user.click(screen.getByTitle('Spend one Rage use'));
    await user.click(screen.getByTitle('Recover one Rage use'));

    expect(onSpend).toHaveBeenCalledWith('class:barbarian:rage');
    expect(onRecover).toHaveBeenCalledWith('class:barbarian:rage');
  });

  it('disables spending an empty pool and recovering a full one', () => {
    render(<ResourcePoolDisplay pools={pools(luck, breath)} editable onSpend={vi.fn()} onRecover={vi.fn()} />);

    expect(screen.getByTitle('Spend one Luck Points use')).toBeDisabled();
    expect(screen.getByTitle('Recover one Luck Points use')).toBeEnabled();
    expect(screen.getByTitle('Spend one Fire Breath use')).toBeEnabled();
    expect(screen.getByTitle('Recover one Fire Breath use')).toBeDisabled();
  });
});
