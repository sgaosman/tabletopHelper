import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { makeMockParticipant } from '../../test/mocks/handlers';
import SpellModifierChips from './SpellModifierChips';

const cleric = makeMockParticipant('part-cleric', 'Aria', 'PLAYER');

describe('SpellModifierChips', () => {
  it('shows a chip per spell on the creature, a buff or a debuff, naming what it does and who cast it (D184)', () => {
    const fighter = makeMockParticipant('part-fighter', 'Bron', 'PLAYER', {
      spellModifiers: [
        { effectId: 'e1', casterId: 'part-cleric', spell: 'Bless', label: 'Bless', hinders: false,
          modifiers: ['+1d4 to attack rolls', '+1d4 to saving throws'] },
        { effectId: 'e2', casterId: null, spell: 'Faerie Fire', label: 'Faerie Fire', hinders: true,
          modifiers: ['advantage on attack rolls against it'] },
      ],
    });
    render(<SpellModifierChips participant={fighter} participants={[cleric, fighter]} />);

    const bless = screen.getByText('Bless');
    expect(bless).toHaveAttribute('title', 'Bless (from Aria): +1d4 to attack rolls; +1d4 to saving throws');
    expect(bless.className).toContain('text-buff');
    const faerieFire = screen.getByText('Faerie Fire');
    expect(faerieFire).toHaveAttribute('title', 'Faerie Fire: advantage on attack rolls against it');
    expect(faerieFire.className).toContain('text-debuff');
  });

  it('shows nothing when no spell is on the creature', () => {
    const { container } = render(<SpellModifierChips participant={cleric} />);
    expect(container).toBeEmptyDOMElement();
  });
});
