import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ReviewStep, { type ReviewStepProps } from '../ReviewStep';
import type { CharacterClassRef } from '../../../../types/reference';

function mockClassRef(overrides: Partial<CharacterClassRef> = {}): CharacterClassRef {
  return {
    id: 'cls-fighter', name: 'Fighter', source: 'PHB', hitDice: 10, primaryAbility: 'STR or DEX',
    savingThrowProficiencies: '["STR","CON"]', armorProficiencies: '[]', weaponProficiencies: '[]',
    toolProficiencies: '[]', skillChoices: '{}', spellcastingAbility: null, isSpellcaster: false,
    isPreparedCaster: false, isKnownCaster: false, isPactMagic: false, spellSlotProgression: 'none',
    features: '[]', startingEquipment: '[]', subclassLevel: 3, multiclassRequirements: null,
    multiclassProficiencies: null,
    ...overrides,
  };
}

const fighter = mockClassRef();
const wizard = mockClassRef({ id: 'cls-wizard', name: 'Wizard', hitDice: 6, primaryAbility: 'INT', subclassLevel: 2 });

function renderStep(overrides: Partial<ReviewStepProps> = {}) {
  const props: ReviewStepProps = {
    name: 'Aria', alignment: '', selectedRace: null,
    selectedClass: fighter, classEntries: [{ cls: fighter, level: 1, subclass: null, subclasses: [] }], level: 1,
    selectedBackground: null,
    finalScores: { strength: 15, dexterity: 14, constitution: 13, intelligence: 10, wisdom: 12, charisma: 8 },
    selectedClassSkills: [],
    resolvedRaceChoices: { languages: [], skills: [], tools: [], weapons: [], resistances: [], spellAbility: null },
    resolvedBgProfs: { skills: [], tools: [], languages: [] },
    raceFeats: 0, bgFeatNames: [],
    selectedCantrips: [], selectedSpells: [],
    error: '',
    ...overrides,
  };
  return render(<ReviewStep {...props} />);
}

describe('ReviewStep', () => {
  it("lists a level 1 Fighter's Fighting Style to choose on the sheet (D159)", () => {
    renderStep();

    expect(screen.getByRole('heading', { name: 'To Choose on Your Sheet' })).toBeInTheDocument();
    const items = screen.getAllByRole('listitem').map(item => item.textContent);
    expect(items).toEqual(['Your Fighting Style']);
  });

  it("names the 1st level's choice before those of the later levels", () => {
    renderStep({ level: 3, classEntries: [{ cls: fighter, level: 3, subclass: null, subclasses: [] }] });

    const items = screen.getAllByRole('listitem').map(item => item.textContent);
    expect(items).toEqual([
      'Your Fighting Style',
      'The choices of your levels above the 1st, such as Ability Score Improvements and spells',
    ]);
  });

  it('lists nothing for a level 1 character whose 1st-level choices the steps made', () => {
    renderStep({ selectedClass: wizard, classEntries: [{ cls: wizard, level: 1, subclass: null, subclasses: [] }] });

    expect(screen.queryByRole('heading', { name: 'To Choose on Your Sheet' })).not.toBeInTheDocument();
  });
});
