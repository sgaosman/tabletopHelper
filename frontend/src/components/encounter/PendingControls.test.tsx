import { describe, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { ToastProvider } from '../../context/ToastContext';
import { makeMockCondition, makeMockParticipant } from '../../test/mocks/handlers';
import SpellSlotPips from './SpellSlotPips';
import DeathSaves from './DeathSaves';
import ConditionChips from './ConditionChips';

function withToasts(ui: ReactElement) {
  return render(<ToastProvider>{ui}</ToastProvider>);
}

function deferred() {
  let resolve!: () => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<void>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const SLOTS = { 1: { max: 2, used: 0 } };

describe('SpellSlotPips while a change is on its way', () => {
  it('waits for a spend to answer before taking another tap', async () => {
    const request = deferred();
    const onUse = vi.fn(() => request.promise);
    withToasts(<SpellSlotPips slots={SLOTS} variant="card" onUse={onUse} />);
    const pips = screen.getAllByRole('button', { name: 'Use level 1 slot' });

    await userEvent.click(pips[0]);
    await userEvent.click(pips[1]);

    expect(onUse).toHaveBeenCalledOnce();
    pips.forEach(pip => expect(pip).toBeDisabled());

    await act(async () => request.resolve());
    pips.forEach(pip => expect(pip).toBeEnabled());
  });

  it('shows a failed spend as a toast', async () => {
    withToasts(<SpellSlotPips slots={SLOTS} variant="card" onUse={() => Promise.reject(new Error('boom'))} />);

    await userEvent.click(screen.getAllByRole('button', { name: 'Use level 1 slot' })[0]);

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
  });

  it('gives the player card\'s pips a 44px target', () => {
    withToasts(<SpellSlotPips slots={SLOTS} variant="card" onUse={vi.fn()} />);
    screen.getAllByRole('button', { name: 'Use level 1 slot' }).forEach(pip => expect(pip).toHaveClass('size-11'));
  });
});

describe('DeathSaves', () => {
  it('shows a failed roll as a toast, and the Roll button is a 44px target', async () => {
    const dying = makeMockParticipant('part-gimli', 'Gimli', 'PLAYER', { lifeState: 'DYING', hpCurrent: 0 });
    withToasts(<DeathSaves participant={dying} onRoll={() => Promise.reject(new Error('boom'))} />);
    const roll = screen.getByRole('button', { name: 'Roll' });
    expect(roll).toHaveClass('min-h-11');

    await userEvent.click(roll);

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Roll' })).toBeEnabled());
  });
});

describe('ConditionChips', () => {
  it('waits while a removal is on its way', async () => {
    const request = deferred();
    const onRemove = vi.fn(() => request.promise);
    const prone = makeMockParticipant('part-gimli', 'Gimli', 'PLAYER', {
      activeConditions: [makeMockCondition('Prone'), makeMockCondition('Grappled')],
    });
    withToasts(<ConditionChips participant={prone} roundNumber={1} onRemove={onRemove} />);

    await userEvent.click(screen.getByRole('button', { name: /Prone/ }));
    await userEvent.click(screen.getByRole('button', { name: /Grappled/ }));

    expect(onRemove).toHaveBeenCalledOnce();
    expect(screen.getByRole('button', { name: /Grappled/ })).toBeDisabled();
    await act(async () => request.resolve());
    expect(screen.getByRole('button', { name: /Grappled/ })).toBeEnabled();
  });
});
