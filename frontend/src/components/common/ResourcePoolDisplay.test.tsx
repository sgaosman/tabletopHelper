import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AxiosError, AxiosHeaders } from 'axios';
import userEvent from '@testing-library/user-event';
import { makeMockCondition, makeMockParticipant, makeMockResourcePool } from '../../test/mocks/handlers';
import type { EncounterParticipant, ResourcePoolEntry } from '../../types/encounter';
import ResourcePoolDisplay from './ResourcePoolDisplay';

const rage = makeMockResourcePool('class:barbarian:rage', 'Rage', {
  sourceName: 'Barbarian', currentUses: 2, resetOn: 'LONG_REST', icon: 'flame',
});
const luck = makeMockResourcePool('feat:lucky', 'Luck Points', {
  sourceType: 'FEAT', sourceName: '', currentUses: 0, resetOn: 'LONG_REST',
});
const breath = makeMockResourcePool('monster:recharge:fire-breath', 'Fire Breath', {
  sourceType: 'MONSTER', sourceName: 'Red Dragon', maxUses: 1, currentUses: 1, resetOn: 'RECHARGE', resetCheck: '1d6>=5',
});

const pools = (...entries: ResourcePoolEntry[]) => entries;

// The metadata the server writes: the generated type reads it as an empty record
const metadata = (value: Record<string, unknown>) => value as unknown as ResourcePoolEntry['metadata'];

const inspiration = makeMockResourcePool('class:bard-bardic-inspiration', 'Bardic Inspiration', {
  sourceName: 'Bard', currentUses: 1, resetOn: 'shortRest', icon: 'music', metadata: metadata({ die: 'd8' }),
});
const primalRage = makeMockResourcePool('class:barbarian-rage', 'Rage', {
  sourceName: 'Barbarian', maxUses: 6, currentUses: 6, icon: 'flame',
  metadata: metadata({ unlimited: true }),
});

describe('ResourcePoolDisplay', () => {
  it.each([
    ['missing', undefined],
    ['null', null],
    ['an empty list', []],
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

  it("shows a pool's die beside its name", () => {
    render(<ResourcePoolDisplay pools={pools(inspiration, rage)} />);

    expect(screen.getByText('Bardic Inspiration').parentElement).toHaveTextContent('🎵Bardic Inspirationd81/3');
    expect(screen.getByText('d8')).toBeInTheDocument();
    expect(screen.getByText('Rage').parentElement).toHaveTextContent('🔥Rage2/3');
  });

  it('shows an unlimited pool as Unlimited, with no counter', () => {
    render(<ResourcePoolDisplay pools={pools(primalRage)} />);

    expect(screen.getByText('Rage').parentElement).toHaveTextContent('🔥RageUnlimited');
    expect(screen.queryByText('6/6')).not.toBeInTheDocument();
  });

  it('never disables spending an unlimited pool, even at no uses, and offers no recovery', async () => {
    const onSpend = vi.fn();
    const user = userEvent.setup();
    const spent = { ...primalRage, currentUses: 0 };
    render(<ResourcePoolDisplay pools={pools(spent)} editable onSpend={onSpend} onRecover={vi.fn()} />);

    const spend = screen.getByTitle('Spend one Rage use');
    expect(spend).toBeEnabled();
    expect(screen.queryByTitle('Recover one Rage use')).not.toBeInTheDocument();
    await user.click(spend);
    expect(onSpend).toHaveBeenCalledWith('class:barbarian-rage');
  });

  // ── At the table (D174) ──────────────────────────────────────────

  describe('in an encounter', () => {
    const FRESH = makeMockParticipant('x', 'x', 'PLAYER').turnEconomy!;
    const fighter = (entries: ResourcePoolEntry[], overrides: Partial<EncounterParticipant> = {}) =>
      makeMockParticipant('part-vex', 'Vex', 'PLAYER', { isCurrentTurn: true, resourcePoolsCurrent: entries, ...overrides });
    const secondWind = makeMockResourcePool('class:fighter-second-wind', 'Second Wind', {
      maxUses: 1, currentUses: 1, spendActionType: 'BONUS_ACTION',
    });
    const actionSurge = makeMockResourcePool('class:fighter-action-surge', 'Action Surge', {
      maxUses: 1, currentUses: 1, spendActionType: 'FREE', metadata: metadata({ grantsAction: true }),
    });
    const energy = (currentUses: number) => makeMockResourcePool('subclass:psi-warrior-psionic-energy', 'Psionic Energy Dice', {
      maxUses: 4, currentUses, metadata: metadata({ die: 'd6' }),
    });
    const recovery = makeMockResourcePool('subclass:psi-warrior-psionic-recovery', 'Psionic Recovery', {
      maxUses: 1, currentUses: 1, spendActionType: 'BONUS_ACTION',
      metadata: metadata({ recovers: 'subclass:psi-warrior-psionic-energy' }),
    });
    const duelist = makeMockResourcePool('feat:defensive-duelist', 'Defensive Duelist', { spendActionType: 'REACTION' });
    const luckFree = makeMockResourcePool('feat:lucky', 'Luck Points', { spendActionType: 'FREE' });

    function renderAtTable(participant: EncounterParticipant, { asDm = false, onUse = vi.fn().mockResolvedValue(undefined) } = {}) {
      const view = render(<ResourcePoolDisplay pools={participant.resourcePoolsCurrent} participant={participant} onUse={onUse} asDm={asDm} />);
      return { onUse, view, user: userEvent.setup() };
    }

    it('shows each pool\'s cost from its spendActionType and uses it', async () => {
      const { onUse, user } = renderAtTable(fighter([secondWind, duelist, luckFree]));

      expect(screen.getByText('Bonus action')).toBeInTheDocument();
      expect(screen.getByText('Reaction')).toBeInTheDocument();
      expect(screen.getByText('Free')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Use Second Wind' }));

      expect(onUse).toHaveBeenCalledWith('class:fighter-second-wind');
    });

    it('offers no use without the encounter\'s use, as for a player looking at another\'s pools', () => {
      render(<ResourcePoolDisplay pools={pools(secondWind)} />);

      expect(screen.queryByRole('button', { name: 'Use Second Wind' })).not.toBeInTheDocument();
      expect(screen.queryByText('Bonus action')).not.toBeInTheDocument();
    });

    it('greys a player\'s action or bonus action pool off their turn, keeping reactions and free uses', () => {
      renderAtTable(fighter([secondWind, duelist, luckFree], { isCurrentTurn: false }));

      expect(screen.getByRole('button', { name: 'Use Second Wind' })).toBeDisabled();
      expect(screen.getByText("Not this creature's turn: only a reaction can be taken")).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Use Defensive Duelist' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'Use Luck Points' })).toBeEnabled();
    });

    it('only warns the DM about what the turn can\'t pay for', () => {
      renderAtTable(fighter([secondWind], { turnEconomy: { ...FRESH, bonusActionUsed: true } }), { asDm: true });

      expect(screen.getByRole('button', { name: 'Use Second Wind' })).toBeEnabled();
      expect(screen.getByText('Over the action economy: Bonus action already used this turn')).toBeInTheDocument();
    });

    it('refuses everyone a pool with no uses left', () => {
      renderAtTable(fighter([{ ...secondWind, currentUses: 0 }]), { asDm: true });

      expect(screen.getByRole('button', { name: 'Use Second Wind' })).toBeDisabled();
      expect(screen.getByText('No Second Wind uses left')).toBeInTheDocument();
    });

    it('shows that Psionic Recovery regains a die, at its cost, and refuses it while no die is expended', () => {
      const full = renderAtTable(fighter([energy(4), recovery]), { asDm: true });

      expect(screen.getByText('Bonus action: regains a die of Psionic Energy Dice')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Use Psionic Recovery' })).toBeDisabled();
      expect(screen.getByText('No Psionic Energy Dice expended to regain')).toBeInTheDocument();
      full.view.unmount();

      renderAtTable(fighter([energy(2), recovery]));
      expect(screen.getByRole('button', { name: 'Use Psionic Recovery' })).toBeEnabled();
    });

    it('lets Action Surge be used on the fighter\'s own turn, once', () => {
      const fresh = renderAtTable(fighter([actionSurge]));
      expect(screen.getByText('Own turn, once: +1 action')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Use Action Surge' })).toBeEnabled();
      fresh.view.unmount();

      const surged = renderAtTable(fighter([{ ...actionSurge, maxUses: 2 }], {
        turnEconomy: { ...FRESH, actions: 2, actionSurgeUsed: true },
      }));
      expect(screen.getByRole('button', { name: 'Use Action Surge' })).toBeDisabled();
      expect(screen.getByText(/can only be used once on the same turn/)).toBeInTheDocument();
      surged.view.unmount();

      renderAtTable(fighter([actionSurge], { isCurrentTurn: false }));
      expect(screen.getByRole('button', { name: 'Use Action Surge' })).toBeDisabled();
      expect(screen.getByText('Action Surge is used on its own turn')).toBeInTheDocument();
    });

    it('shows the server\'s refusal', async () => {
      const refusal = new AxiosError('Conflict', '409', undefined, undefined, {
        status: 409, statusText: 'Conflict', headers: {}, config: { headers: new AxiosHeaders() },
        data: { error: 'Bonus action already used this turn' },
      });
      const { user } = renderAtTable(fighter([secondWind]), { onUse: vi.fn().mockRejectedValue(refusal) });

      await user.click(screen.getByRole('button', { name: 'Use Second Wind' }));

      await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Bonus action already used this turn'));
    });
  });

  // ── Class features at the table (M17) ────────────────────────────

  describe('class features in an encounter', () => {
    const feature = (poolId: string, name: string, effect: string, overrides: Partial<ResourcePoolEntry> = {}) =>
      makeMockResourcePool(poolId, name, { metadata: metadata({ effect }), ...overrides });
    const ragePool = feature('class:barbarian-rage', 'Rage', 'rage', { maxUses: 3, currentUses: 2, spendActionType: 'BONUS_ACTION' });
    const kiPool = feature('class:monk-ki', 'Ki Points', 'ki', { maxUses: 5, currentUses: 5, spendActionType: 'FREE' });
    const layOnHands = feature('class:paladin-lay-on-hands', 'Lay on Hands', 'lay-on-hands', {
      maxUses: 25, currentUses: 25, spendActionType: 'ACTION',
    });
    const hero = (entries: ResourcePoolEntry[], overrides: Partial<EncounterParticipant> = {}) =>
      makeMockParticipant('part-hero', 'Hero', 'PLAYER', { isCurrentTurn: true, resourcePoolsCurrent: entries, ...overrides });

    function renderPools(participant: EncounterParticipant, targets?: EncounterParticipant[]) {
      const onUse = vi.fn().mockResolvedValue(undefined);
      render(<ResourcePoolDisplay pools={participant.resourcePoolsCurrent} participant={participant} onUse={onUse}
        targets={targets} />);
      return { onUse, user: userEvent.setup() };
    }

    it('says what a rage gives while it lasts, and ends it with the end option', async () => {
      const raging = hero([ragePool], { activeConditions: [makeMockCondition('raging', { duration: 10 })] });
      const { onUse, user } = renderPools(raging);

      expect(screen.getByRole('status')).toHaveTextContent(/Raging.*resistance to bludgeoning, piercing and slashing/);
      expect(screen.queryByRole('button', { name: 'Use Rage' })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'End Rage' }));

      expect(onUse).toHaveBeenCalledWith('class:barbarian-rage', { option: 'end' });
    });

    it('starts a rage with a plain use, saying what it does', async () => {
      const { onUse, user } = renderPools(hero([ragePool]));

      expect(screen.getByText('Bonus action: rage for 1 minute')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Use Rage' }));

      expect(onUse).toHaveBeenCalledWith('class:barbarian-rage');
    });

    it('offers Patient Defense and Step of the Wind for ki, greyed once the bonus action is spent', async () => {
      const { onUse, user } = renderPools(hero([kiPool]));

      await user.click(screen.getByRole('button', { name: 'Patient Defense' }));
      expect(onUse).toHaveBeenCalledWith('class:monk-ki', { option: 'patient-defense' });
      await user.click(screen.getByRole('button', { name: 'Step of the Wind: Disengage' }));
      expect(onUse).toHaveBeenCalledWith('class:monk-ki', { option: 'step-of-the-wind-disengage' });
    });

    it('greys the ki options for a player whose bonus action is spent', () => {
      const spent = hero([kiPool]);
      renderPools({ ...spent, turnEconomy: { ...spent.turnEconomy!, bonusActionUsed: true } });

      expect(screen.getByRole('button', { name: 'Step of the Wind: Dash' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Use Ki Points' })).toBeEnabled();
    });

    it("sends Lay on Hands' points, use and creature, and refuses a cure not in fives", async () => {
      const ally = makeMockParticipant('part-ally', 'Gimli', 'PLAYER');
      const { onUse, user } = renderPools(hero([layOnHands]), [ally]);

      const points = screen.getByLabelText('Lay on Hands points');
      await user.clear(points);
      await user.type(points, '12');
      await user.selectOptions(screen.getByLabelText('Lay on Hands target'), 'part-ally');
      await user.click(screen.getByRole('button', { name: 'Use Lay on Hands' }));
      expect(onUse).toHaveBeenCalledWith('class:paladin-lay-on-hands', { uses: 12, option: 'heal', targetIds: ['part-ally'] });

      await user.selectOptions(screen.getByLabelText('Lay on Hands use'), 'cure');
      expect(screen.getByRole('button', { name: 'Use Lay on Hands' })).toBeDisabled();
      await user.clear(points);
      await user.type(points, '5');
      await user.selectOptions(screen.getByLabelText('Lay on Hands target'), 'part-hero');
      await user.click(screen.getByRole('button', { name: 'Use Lay on Hands' }));
      expect(onUse).toHaveBeenLastCalledWith('class:paladin-lay-on-hands', { uses: 5, option: 'cure', targetIds: undefined });
    });
  });
});
