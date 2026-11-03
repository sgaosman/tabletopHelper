import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ClassStep, { type ClassStepProps } from '../ClassStep';
import type { CharacterClassRef } from '../../../../types/reference';

function mockClassRef(overrides: Partial<CharacterClassRef> = {}): CharacterClassRef {
  return {
    id: 'cls-fighter',
    name: 'Fighter',
    source: 'PHB',
    hitDice: 10,
    primaryAbility: 'STR or DEX',
    savingThrowProficiencies: '["STR","CON"]',
    armorProficiencies: '["Light Armor","Medium Armor","Heavy Armor","Shields"]',
    weaponProficiencies: '["Simple Weapons","Martial Weapons"]',
    toolProficiencies: '[]',
    skillChoices: '{}',
    spellcastingAbility: null,
    isSpellcaster: false,
    isPreparedCaster: false,
    isKnownCaster: false,
    isPactMagic: false,
    spellSlotProgression: 'none',
    features: '[]',
    startingEquipment: '[]',
    subclassLevel: 3,
    multiclassRequirements: null,
    multiclassProficiencies: null,
    ...overrides,
  };
}

const fighter = mockClassRef();
const paladin = mockClassRef({ id: 'cls-paladin', name: 'Paladin', primaryAbility: 'STR and CHA' });

function renderStep(overrides: Partial<ClassStepProps> = {}) {
  const noop = () => {};
  const props: ClassStepProps = {
    classes: [fighter, paladin],
    selectedClass: fighter,
    onSelectClass: noop,
    classEntries: [{ cls: fighter, level: 1, subclass: null, subclasses: [] }],
    level: 1,
    multiclassExpanded: false,
    setMulticlassExpanded: noop,
    addMulticlass: noop,
    removeMulticlass: noop,
    handleClassLevelChange: noop,
    handleEntrySubclass: noop,
    finalScores: { strength: 15, dexterity: 14, constitution: 13, intelligence: 10, wisdom: 12, charisma: 8 },
    classSkillChoices: { from: [], count: 0 },
    selectedClassSkills: [],
    setSelectedClassSkills: noop,
    raceSkills: new Set<string>(),
    mcSkillChoicesMap: {},
    mcSkillSelections: {},
    setMcSkillSelections: noop,
    resolvedBgProfs: { skills: [], tools: [], languages: [] },
    resolvedRaceChoices: { languages: [], skills: [], tools: [], weapons: [], resistances: [], spellAbility: null },
    selectedExpertise: [],
    setSelectedExpertise: noop,
    expertiseCount: 0,
    selectedRace: null,
    ...overrides,
  };
  return render(<ClassStep {...props} />);
}

describe('ClassStep', () => {
  it("says a 1st-level choice the steps don't ask for, such as a Fighter's Fighting Style, is made on the sheet (D159)", () => {
    renderStep();

    expect(screen.getByRole('heading', { name: 'Choices made on your sheet' })).toBeInTheDocument();
    expect(screen.getByText(/such as a Fighter's Fighting Style, is made on your character sheet after creation/))
      .toBeInTheDocument();
    expect(screen.queryByText(/each level above the 1st/)).not.toBeInTheDocument();
  });

  it('adds the choices of the levels above the 1st for a character created at a higher level', () => {
    renderStep({ level: 5, classEntries: [{ cls: fighter, level: 5, subclass: null, subclasses: [] }] });

    const note = screen.getByText(/such as a Fighter's Fighting Style/);
    expect(note).toHaveTextContent('So are the Ability Score Improvements, subclasses, Fighting Styles, Expertise, '
      + 'and the cantrips and spells of each level above the 1st.');
  });

  it('shows no note before a class is chosen', () => {
    renderStep({ selectedClass: null, classEntries: [] });

    expect(screen.queryByRole('heading', { name: 'Choices made on your sheet' })).not.toBeInTheDocument();
  });

  it("shows each class's primary ability as the class data gives it, either of two or both", () => {
    renderStep();

    expect(screen.getByText('Primary: STR or DEX')).toBeInTheDocument();
    expect(screen.getByText('Primary: STR and CHA')).toBeInTheDocument();
  });
});
