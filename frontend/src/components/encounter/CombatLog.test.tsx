import { describe, it, expect, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockLogEntry } from '../../test/mocks/handlers';
import CombatLog from './CombatLog';

const ENTRIES = [
  makeMockLogEntry('log-1', 'Gimli hits Test Goblin.', { actionType: 'ATTACK', turnParticipantName: 'Gimli' }),
  makeMockLogEntry('log-2', 'Next turn.', { actionType: 'TURN_ADVANCE', targetName: 'Test Goblin' }),
  makeMockLogEntry('log-3', 'Test Goblin uses Scimitar.', { actionType: 'MONSTER_ACTION', turnParticipantName: 'Test Goblin' }),
  makeMockLogEntry('log-4', 'Round two begins.', { actionType: 'HEAL', roundNumber: 2, turnParticipantName: 'Gimli' }),
];

async function renderOpen(props: Partial<Parameters<typeof CombatLog>[0]> = {}) {
  render(<CombatLog entries={ENTRIES} {...props} />);
  await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
}

describe('CombatLog', () => {
  it('stays folded until opened, counting its entries', () => {
    render(<CombatLog entries={ENTRIES} />);
    expect(screen.getByText('(4 entries)')).toBeInTheDocument();
    expect(screen.queryByText('Gimli hits Test Goblin.')).not.toBeInTheDocument();
  });

  it('says when there is nothing yet', async () => {
    render(<CombatLog entries={[]} />);
    await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
    expect(screen.getByText('No actions yet')).toBeInTheDocument();
  });

  it('groups entries under their round and turn, showing a turn change as its header', async () => {
    await renderOpen();
    expect(screen.getByText('Round 1')).toBeInTheDocument();
    expect(screen.getByText('Round 2')).toBeInTheDocument();
    expect(screen.getAllByText(/^Turn: /).map(el => el.textContent)).toEqual(['Turn: Gimli', 'Turn: Test Goblin', 'Turn: Gimli']);
    expect(screen.queryByText('Next turn.')).not.toBeInTheDocument();
  });

  it('colours each entry and its border by its action type, the M12 monster actions included, in 15px text', async () => {
    await renderOpen();
    const attack = screen.getByText('Gimli hits Test Goblin.');
    expect(attack).toHaveClass('text-cls-fighter', 'text-[15px]');
    expect(attack.parentElement).toHaveClass('border-debuff');
    const monster = screen.getByText('Test Goblin uses Scimitar.');
    expect(monster).toHaveClass('text-monster');
    expect(monster.parentElement).toHaveClass('border-monster');
    expect(screen.getByText('Round two begins.').parentElement).toHaveClass('border-buff');
  });

  it('adds nothing after an entry without entryActions, even one Legendary Resistance could answer', async () => {
    render(<CombatLog entries={[makeMockLogEntry('log-1', 'Aboleth fails.', {
      legendaryResistanceEligible: true, lrResolved: false, lrSuccessDamage: 0,
    })]} />);
    await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });

  it('adds what entryActions gives after each entry that is not a turn change', async () => {
    const entryActions = vi.fn((entry: { id: string }) => <button>Act on {entry.id}</button>);
    await renderOpen({ entryActions });
    expect(screen.getByRole('button', { name: 'Act on log-1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Act on log-4' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Act on log-2' })).not.toBeInTheDocument();
  });

  it('dims an undone entry, strikes it through and tags it Undone (M14)', async () => {
    render(<CombatLog entries={[
      makeMockLogEntry('log-1', 'DM deals 5 damage to Goblin.', { undone: true, commandId: 'cmd-1' }),
      makeMockLogEntry('log-2', 'The DM undoes: DM deals 5 damage to Goblin.', { actionType: 'UNDO' }),
    ]} />);
    await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
    expect(screen.getByText('DM deals 5 damage to Goblin.')).toHaveClass('line-through', 'opacity-60');
    expect(screen.getByText('The DM undoes: DM deals 5 damage to Goblin.')).not.toHaveClass('line-through');
    expect(screen.getAllByText('Undone')).toHaveLength(1);
  });

  it('strikes through an undone turn change in its header, and adds turnActions there', async () => {
    const turnActions = vi.fn((entry: { id: string }) => <button>Undo to {entry.id}</button>);
    render(<CombatLog turnActions={turnActions} entries={[
      makeMockLogEntry('log-1', 'Turn passes to Goblin.', { actionType: 'TURN_ADVANCE', targetName: 'Goblin', undone: true }),
    ]} />);
    await userEvent.setup().click(screen.getByRole('button', { name: /Combat Log/ }));
    expect(screen.getByText('Turn: Goblin')).toHaveClass('line-through');
    expect(screen.getByText('Undone')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Undo to log-1' })).toBeInTheDocument();
  });

  it('highlights entries for a moment as they become undone', async () => {
    vi.useFakeTimers();
    try {
      const entry = makeMockLogEntry('log-1', 'Gimli hits Test Goblin.', { actionType: 'ATTACK' });
      const { rerender } = render(<CombatLog entries={[entry]} />);
      act(() => { screen.getByRole('button', { name: /Combat Log/ }).click(); });
      expect(screen.getByText('Gimli hits Test Goblin.').parentElement).not.toHaveClass('ring-2');

      rerender(<CombatLog entries={[{ ...entry, undone: true }]} />);
      expect(screen.getByText('Gimli hits Test Goblin.').parentElement).toHaveClass('ring-2');

      act(() => { vi.advanceTimersByTime(2500); });
      expect(screen.getByText('Gimli hits Test Goblin.').parentElement).not.toHaveClass('ring-2');
    } finally {
      vi.useRealTimers();
    }
  });
});
