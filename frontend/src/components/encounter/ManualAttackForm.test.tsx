import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockParticipant } from '../../test/mocks/handlers';
import ManualAttackForm from './ManualAttackForm';

const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER');

describe('ManualAttackForm', () => {
  it('sends each complete row in turn, within 5 feet unless switched off, and empties', async () => {
    const onRoll = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ManualAttackForm target={goblin} onRoll={onRoll} />);

    await user.type(screen.getByPlaceholderText('+5'), '4');
    await user.type(screen.getByPlaceholderText('1d8+3'), '1d6+2');
    await user.selectOptions(screen.getByRole('combobox'), 'fire');
    await user.click(screen.getByRole('button', { name: 'Adv' }));
    await user.click(screen.getByRole('button', { name: 'Clone this attack' }));
    await user.click(screen.getAllByRole('button', { name: 'Within 5 ft' })[1]);
    await user.click(screen.getAllByRole('button', { name: 'Crit' })[1]);
    await user.click(screen.getByRole('button', { name: /Add attack/ }));
    await user.click(screen.getByRole('button', { name: 'Roll 3 Attacks' }));

    expect(onRoll.mock.calls.map(([request]) => request)).toEqual([
      { targetId: 'part-goblin', attackBonus: 4, damageDice: '1d6+2', damageType: 'fire', advantage: true, forceCrit: undefined, within5Ft: true },
      { targetId: 'part-goblin', attackBonus: 4, damageDice: '1d6+2', damageType: 'fire', advantage: true, forceCrit: true, within5Ft: false },
    ]);
    expect(screen.getByRole('button', { name: 'Roll Attack' })).toBeInTheDocument();
    expect(screen.getByPlaceholderText('+5')).toHaveValue(null);
  });

  it('holds at most five rows and at least one', async () => {
    const user = userEvent.setup();
    render(<ManualAttackForm target={goblin} onRoll={vi.fn()} />);

    expect(screen.queryByRole('button', { name: 'Remove this attack' })).not.toBeInTheDocument();
    for (let i = 0; i < 4; i++) await user.click(screen.getByRole('button', { name: /Add attack/ }));
    expect(screen.getAllByPlaceholderText('+5')).toHaveLength(5);
    expect(screen.queryByRole('button', { name: /Add attack/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clone this attack' })).not.toBeInTheDocument();

    await user.click(screen.getAllByRole('button', { name: 'Remove this attack' })[0]);
    expect(screen.getAllByPlaceholderText('+5')).toHaveLength(4);
  });
});
