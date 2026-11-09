import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockSpellEffect } from '../../test/mocks/handlers';
import SpellEffectCard from './SpellEffectCard';

describe('SpellEffectCard', () => {
  it('shows Spiritual Weapon\'s slot level and the rounds it has left, with no concentration link', () => {
    render(<SpellEffectCard effect={makeMockSpellEffect('Spiritual Weapon', { startedRound: 2 })} roundNumber={5} />);

    expect(screen.getByText('Spiritual Weapon')).toBeInTheDocument();
    expect(screen.getByText('level 2')).toBeInTheDocument();
    expect(screen.getByText('7 rounds left')).toBeInTheDocument();
    expect(screen.queryByText('Concentration')).not.toBeInTheDocument();
  });

  it('links Moonbeam to its concentration, lists its triggers and moves it for an action', async () => {
    const onUse = vi.fn();
    const moonbeam = makeMockSpellEffect('Moonbeam', {
      concentration: true, directedCost: 'ACTION', directedResolves: false, triggers: ['ENTER', 'START_OF_TURN'],
      startedRound: 1, durationRounds: 10,
    });
    render(<SpellEffectCard effect={moonbeam} roundNumber={10} onUse={onUse} />);

    expect(screen.getByText('Concentration')).toBeInTheDocument();
    expect(screen.getByText('1 round left')).toBeInTheDocument();
    expect(screen.getByText('On a creature: enters, starts turn')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Move (action)' }));
    expect(onUse).toHaveBeenCalledTimes(1);
  });

  it('shows the meteors left', () => {
    const meteors = makeMockSpellEffect('Melf\'s Minute Meteors', { usesLeft: 5, maxUsesEachTurn: 2, durationRounds: null });
    render(<SpellEffectCard effect={meteors} roundNumber={3} />);

    expect(screen.getByText('5 left')).toBeInTheDocument();
    expect(screen.queryByText(/rounds? left/)).not.toBeInTheDocument();
  });
});
