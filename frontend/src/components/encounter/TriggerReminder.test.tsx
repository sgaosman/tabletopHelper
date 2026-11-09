import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockParticipant, makeMockSpellEffect } from '../../test/mocks/handlers';
import TriggerReminder from './TriggerReminder';

const moonbeam = makeMockSpellEffect('Moonbeam', {
  concentration: true, directedCost: 'ACTION', directedResolves: false, triggers: ['ENTER', 'START_OF_TURN'],
});
const sphere = makeMockSpellEffect('Flaming Sphere', { concentration: true, triggers: ['END_OF_TURN'] });
const druid = makeMockParticipant('part-druid', 'Druid', 'PLAYER', { activeEffects: [moonbeam] });
const wizard = makeMockParticipant('part-wizard', 'Wizard', 'PLAYER', { activeEffects: [sphere, makeMockSpellEffect()] });
const goblin = makeMockParticipant('part-goblin', 'Goblin', 'MONSTER', { isCurrentTurn: true });

describe('TriggerReminder', () => {
  it('lists each effect with a trigger on the current creature\'s turn and applies the chosen moment to it', async () => {
    const onApply = vi.fn();
    render(<TriggerReminder participants={[druid, wizard, goblin]} current={goblin} onApply={onApply} />);

    expect(screen.getByText('Is Goblin in a spell\'s area?')).toBeInTheDocument();
    expect(screen.getByText('(Druid)')).toBeInTheDocument();
    expect(screen.getByText('(Wizard)')).toBeInTheDocument();
    // Spiritual Weapon has no trigger, so it isn't listed
    expect(screen.queryByText(/Spiritual Weapon/)).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Moonbeam: Goblin starts turn' }));

    expect(onApply).toHaveBeenCalledWith(druid, moonbeam, 'START_OF_TURN', 'part-goblin');
    expect(screen.getByRole('button', { name: 'Flaming Sphere: Goblin ends turn' })).toBeInTheDocument();
  });

  it('shows nothing without a current creature or an effect with a trigger', () => {
    const { container, rerender } = render(<TriggerReminder participants={[druid, goblin]} current={undefined} onApply={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();

    rerender(<TriggerReminder participants={[goblin]} current={goblin} onApply={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });
});
