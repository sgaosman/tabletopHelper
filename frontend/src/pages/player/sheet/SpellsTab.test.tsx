import { describe, it, expect, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import { render } from '../../../test/test-utils';
import { makeMockCharacter } from '../../../test/mocks/handlers';
import type { ClassSpellcasting, PlayerCharacter } from '../../../types/character';
import SpellsTab from './SpellsTab';

function casting(overrides: Partial<ClassSpellcasting>): ClassSpellcasting {
  return {
    className: 'Wizard', classLevel: 3, ability: 'INT', spellSaveDc: 13, spellAttackBonus: 5,
    highestSpellLevel: 2, cantripsKnown: 3, preparedSpells: 6, pactMagic: false,
    ...overrides,
  };
}

function renderTab(char: PlayerCharacter) {
  return render(<SpellsTab char={char} spellsKnown={char.spellsKnown ?? []} spellSlots={char.spellSlots ?? {}}
    saveField={vi.fn()} />);
}

// The box that a class's or race's spells are listed in, by its heading
function box(heading: string) {
  return screen.getByRole('heading', { name: heading }).closest('div.bg-card') as HTMLElement;
}

describe('SpellsTab', () => {
  it("shows each class's save DC, attack bonus and highest spell level as the server works them out (D167)", () => {
    const char = {
      ...makeMockCharacter('char-001', 'Elara', 'Wizard / Cleric', 5),
      spellcasting: [
        casting({}),
        casting({ className: 'Cleric', classLevel: 2, ability: 'WIS', spellSaveDc: 12, spellAttackBonus: 4,
          highestSpellLevel: 1, preparedSpells: 3 }),
      ],
    };

    renderTab(char);

    expect(screen.getByText('Spellcasting by Class')).toBeInTheDocument();
    expect(screen.getByText('13')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('+4')).toBeInTheDocument();
    expect(within(box('Cleric Spells')).getByText('Prepared (0/3)')).toBeInTheDocument();
    expect(within(box('Wizard Spells')).getByText('Prepared (0/6)')).toBeInTheDocument();
  });

  it('gives a 1st-level Paladin no spells to prepare', () => {
    const char = {
      ...makeMockCharacter('char-002', 'Aric', 'Paladin', 1),
      spellcasting: [casting({ className: 'Paladin', classLevel: 1, ability: 'CHA', spellSaveDc: 10,
        spellAttackBonus: 2, highestSpellLevel: 0, cantripsKnown: 0, preparedSpells: 0 })],
    };

    renderTab(char);

    expect(within(box('Paladin Spells')).getByText('Prepared (0/0)')).toBeInTheDocument();
  });

  it("reads an Eldritch Knight's known spells and cantrips from the server", () => {
    const char = {
      ...makeMockCharacter('char-003', 'Gwen', 'Fighter', 4),
      spellsKnown: [
        { name: 'Fire Bolt', level: 0, source: 'class:Fighter' },
        { name: 'Shield', level: 1, source: 'class:Fighter' },
      ],
      spellcasting: [casting({ className: 'Fighter', subclassName: 'Eldritch Knight', classLevel: 4,
        highestSpellLevel: 1, cantripsKnown: 2, spellsKnown: 4, preparedSpells: null })],
    };

    renderTab(char);

    const knight = box('Fighter (Eldritch Knight) Spells');
    expect(within(knight).getByText('Cantrips (1/2)')).toBeInTheDocument();
    expect(within(knight).getByText('Known Spells (1/4)')).toBeInTheDocument();
  });

  it('shows the main numbers for race and feat spells beside the class ones', () => {
    const char = {
      ...makeMockCharacter('char-004', 'Zara', 'Wizard', 3),
      spellcastingAbility: 'INT', spellSaveDc: 13, spellAttackBonus: 5,
      spellsKnown: [{ name: 'Hellish Rebuke', level: 1, source: 'race:Tiefling', usesPerLongRest: 1 }],
      spellcasting: [casting({})],
    };

    renderTab(char);

    expect(screen.getByText('Race & Feat')).toBeInTheDocument();
    expect(box('Tiefling Spells')).toBeInTheDocument();
  });
});
