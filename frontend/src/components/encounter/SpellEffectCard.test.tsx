import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockSpellEffect } from '../../test/mocks/handlers';
import SpellEffectCard from './SpellEffectCard';

describe('SpellEffectCard', () => {
  it('lists what Guardian of Faith and Vitriolic Sphere keep for later (D185)', () => {
    const guardian = makeMockSpellEffect('Guardian of Faith', { damageLeft: 40, directedCost: null, triggers: ['ENTER'] });
    const { unmount } = render(<SpellEffectCard effect={guardian} roundNumber={1} />);
    expect(screen.getByText('40 damage left')).toBeInTheDocument();
    unmount();

    const sphere = makeMockSpellEffect('Vitriolic Sphere', {
      directedCost: null, delayed: [{ target: 'ogre', dice: '5d4', type: 'acid', armed: true }],
    });
    render(<SpellEffectCard effect={sphere} roundNumber={1} nameOf={() => 'Ogre'} />);
    expect(screen.getByText('5d4 acid to Ogre at the end of its next turn')).toBeInTheDocument();
  });

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

  it('names the creatures its Ongoing Modifiers are on, its option, and what they do (D184)', () => {
    const weapon = makeMockSpellEffect('Elemental Weapon', {
      concentration: true, directedCost: null, option: 'fire', affectedIds: ['part-bron'],
      modifiers: ['+1 to weapon attack rolls', '+1d4 fire damage on weapon hits'],
    });
    render(<SpellEffectCard effect={weapon} roundNumber={1} affectedNames={['Bron']} />);

    expect(screen.getByText('Elemental Weapon (fire)')).toHaveAttribute('title',
      '+1 to weapon attack rolls; +1d4 fire damage on weapon hits');
    expect(screen.getByText('on Bron')).toBeInTheDocument();
  });

  it("moves Hunter's Mark to another creature chosen from the list (PHB p.251, D184)", async () => {
    const onMove = vi.fn();
    const mark = makeMockSpellEffect("Hunter's Mark", { concentration: true, directedCost: null, retargetable: true });
    render(<SpellEffectCard effect={mark} roundNumber={1} moveTargets={[{ id: 'part-orc', name: 'Orc' }]}
      onMove={onMove} />);

    await userEvent.setup().selectOptions(screen.getByLabelText("Move Hunter's Mark to"), 'part-orc');
    expect(onMove).toHaveBeenCalledWith('part-orc');
  });
});
