import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { makeMockLogEntry } from '../../test/mocks/handlers';
import type { UndoInfo } from '../../types/combat';
import UndoDialog from './UndoDialog';

const UNDO: UndoInfo = {
  commandId: 'cmd-3', kind: 'TURN_ADVANCE', summary: 'Turn passes to Goblin (Round 1)', entryCount: 2,
  stepsAvailable: 3,
  recent: [
    { commandId: 'cmd-3', kind: 'TURN_ADVANCE', summary: 'Turn passes to Goblin (Round 1)', entryCount: 2 },
    { commandId: 'cmd-2', kind: 'SPELL_CAST', summary: 'Vex casts Fireball', entryCount: 1 },
    { commandId: 'cmd-1', kind: 'LAIR_STATUS', summary: 'The DM changes which monsters fight in their lair', entryCount: 0 },
  ],
};

const LOG = [
  makeMockLogEntry('log-1', 'Vex casts Fireball', { actionType: 'SPELL_CAST', commandId: 'cmd-2' }),
  makeMockLogEntry('log-2', 'Goblin rolls 1d6 (5). Goblin recharges Fire Breath!', { actionType: 'RECHARGE_SUCCESS', commandId: 'cmd-3' }),
  makeMockLogEntry('log-3', 'Turn passes to Goblin (Round 1)', { actionType: 'TURN_ADVANCE', commandId: 'cmd-3' }),
];

function conflict(message: string) {
  return new AxiosError('Conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 409, statusText: 'Conflict', data: { error: message }, headers: {}, config: { headers: new AxiosHeaders() },
  });
}

describe('UndoDialog', () => {
  it('lists the newest action with the entries it wrote, and says dice are rolled fresh', () => {
    render(<UndoDialog undo={UNDO} steps={1} log={LOG} onUndo={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByRole('dialog', { name: 'Undo the last action?' })).toBeInTheDocument();
    const listed = screen.getByRole('region', { name: 'What will be undone' });
    expect(listed).toHaveTextContent('Turn passes to Goblin (Round 1)');
    expect(listed).toHaveTextContent('Goblin recharges Fire Breath!');
    expect(listed).not.toHaveTextContent('Vex casts Fireball');
    expect(screen.getByText(/rolled fresh/)).toBeInTheDocument();
    // Next Turn: undo restores what Previous Turn can't
    expect(screen.getByText(/Previous Turn only moves the turn marker/)).toBeInTheDocument();
  });

  it('lists every action "Undo to here" takes, newest first, and names the barrier when it reaches it', () => {
    render(<UndoDialog undo={UNDO} steps={3} log={LOG} onUndo={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByRole('dialog', { name: 'Undo the last 3 actions?' })).toBeInTheDocument();
    const summaries = screen.getAllByRole('listitem').map(item => item.querySelector('p')?.textContent).filter(Boolean);
    expect(summaries).toEqual(['Turn passes to Goblin (Round 1)', 'Vex casts Fireball',
      'The DM changes which monsters fight in their lair']);
    expect(screen.getByText('Wrote nothing to the log')).toBeInTheDocument();
    expect(screen.getByText(/as far back as undo goes/)).toBeInTheDocument();
  });

  it('undoes naming the newest action and the number of steps, then closes', async () => {
    const onUndo = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    render(<UndoDialog undo={UNDO} steps={2} log={LOG} onUndo={onUndo} onClose={onClose} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Undo' }));

    expect(onUndo).toHaveBeenCalledWith('cmd-3', 2);
    expect(onClose).toHaveBeenCalled();
    expect(screen.queryByText(/as far back as undo goes/)).not.toBeInTheDocument();
  });

  it('stays open with the server\'s reason when the last action changed meanwhile', async () => {
    const onUndo = vi.fn().mockRejectedValue(conflict('The last action has changed since you reviewed it; review it again'));
    const onClose = vi.fn();
    render(<UndoDialog undo={UNDO} steps={1} log={LOG} onUndo={onUndo} onClose={onClose} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Undo' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('review it again');
    expect(onClose).not.toHaveBeenCalled();
  });
});
