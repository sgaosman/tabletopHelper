import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockParticipant } from '../../test/mocks/handlers';
import DeathSaves from './DeathSaves';

const dying = (overrides: Parameters<typeof makeMockParticipant>[3] = {}) =>
  makeMockParticipant('p', 'Gimli', 'PLAYER', { hpCurrent: 0, lifeState: 'DYING', ...overrides });

describe('DeathSaves', () => {
  it('fills a pip for each success and each failure', () => {
    const { container } = render(<DeathSaves participant={dying({ deathSaveSuccesses: 2, deathSaveFailures: 1 })} />);
    expect(container.querySelectorAll('.bg-buff')).toHaveLength(2);
    expect(container.querySelectorAll('.bg-debuff')).toHaveLength(1);
  });

  it('offers no roll without onRoll, as while the encounter is not Active (D166)', () => {
    render(<DeathSaves participant={dying()} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('rolls with onRoll', async () => {
    const onRoll = vi.fn().mockResolvedValue(undefined);
    render(<DeathSaves participant={dying()} onRoll={onRoll} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Roll' }));
    expect(onRoll).toHaveBeenCalledTimes(1);
  });

  it('shows "Death save due" only with showDue, the DM\'s badge (D163)', () => {
    const { rerender } = render(<DeathSaves participant={dying({ deathSaveDue: true })} onRoll={vi.fn()} />);
    expect(screen.queryByText('Death save due')).not.toBeInTheDocument();
    // The roll stands out on both pages while one is due
    expect(screen.getByRole('button', { name: 'Roll' })).toHaveClass('bg-debuff');

    rerender(<DeathSaves participant={dying({ deathSaveDue: true })} onRoll={vi.fn()} showDue />);
    expect(screen.getByRole('status')).toHaveTextContent('Death save due');

    rerender(<DeathSaves participant={dying()} onRoll={vi.fn()} showDue />);
    expect(screen.queryByText('Death save due')).not.toBeInTheDocument();
  });
});
