import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockParticipant, makeMockSpellEffect } from '../../test/mocks/handlers';
import ActiveSpells from './ActiveSpells';

const weapon = makeMockSpellEffect('Spiritual Weapon');
const guardians = makeMockSpellEffect('Spirit Guardians', {
  slotLevel: 3, concentration: true, durationRounds: 100, directedCost: null, directedResolves: false,
  triggers: ['ENTER', 'START_OF_TURN'],
});
const cleric = makeMockParticipant('p', 'Brother Tuck', 'PLAYER', {
  concentrationSpell: 'Spirit Guardians', activeEffects: [guardians, weapon],
});

describe('ActiveSpells', () => {
  it('shows the spell concentrated on and a card for each Spell Effect, with no buttons without handlers', () => {
    render(<ActiveSpells participant={cleric} roundNumber={1} />);
    expect(screen.getByText('Concentrating: Spirit Guardians')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Spiritual Weapon effect' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Spirit Guardians effect' })).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('uses and ends the effect whose card is clicked, offering a use only for an effect its caster directs', async () => {
    const onUse = vi.fn();
    const onEnd = vi.fn();
    render(<ActiveSpells participant={cleric} roundNumber={1} onUse={onUse} onEnd={onEnd} />);
    const user = userEvent.setup();

    expect(within(screen.getByRole('group', { name: 'Spirit Guardians effect' }))
      .queryByRole('button', { name: /Use/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Use (bonus action)' }));
    await user.click(screen.getByRole('button', { name: 'End Spirit Guardians' }));

    expect(onUse).toHaveBeenCalledWith(weapon);
    expect(onEnd).toHaveBeenCalledWith(guardians);
  });

  it('greys a use the turn can\'t pay for, with the reason', () => {
    render(<ActiveSpells participant={cleric} roundNumber={1} onUse={vi.fn()}
      blockedBy={() => 'Bonus action already used this turn'} />);
    const use = screen.getByRole('button', { name: 'Use (bonus action)' });
    expect(use).toBeDisabled();
    expect(use).toHaveAttribute('title', 'Bonus action already used this turn');
  });

  it('shows nothing for a participant with neither', () => {
    const { container } = render(<ActiveSpells participant={makeMockParticipant('p', 'Gimli', 'PLAYER')} roundNumber={1} />);
    expect(container).toBeEmptyDOMElement();
  });
});
