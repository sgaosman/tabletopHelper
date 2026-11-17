import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ClassStep from '../ClassStep';
import { initialWizardState, type WizardState } from '../wizardReducer';
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

function renderStep(overrides: Partial<WizardState> = {}) {
  const noop = () => {};
  const state: WizardState = {
    ...initialWizardState,
    selectedClass: fighter,
    classEntries: [{ cls: fighter, level: 1, subclass: null, subclasses: [] }],
    level: 1,
    ...overrides,
  };
  return render(
    <ClassStep classes={[fighter, paladin]} state={state} dispatch={noop} onSelectClass={noop} onAddMulticlass={noop} />,
  );
}

describe('ClassStep multiclass skills and toggle state (D226)', () => {
  const rogue = mockClassRef({
    id: 'cls-rogue', name: 'Rogue',
    multiclassProficiencies: JSON.stringify({ armor: ['Light'], skills: { from: ['Deception', 'Stealth'], count: 1 } }),
  });

  it("asks for a multiclass's skill until it is chosen (PHB p.164), and marks the chosen class pressed", () => {
    renderStep({
      level: 2,
      classEntries: [
        { cls: fighter, level: 1, subclass: null, subclasses: [] }, { cls: rogue, level: 1, subclass: null, subclasses: [] },
      ],
    });

    expect(screen.getByRole('heading', { name: 'Rogue Multiclass Skills' })).toBeInTheDocument();
    expect(screen.getByText('Select 1 more skill to continue')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Stealth' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: /^Fighter/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /^Paladin/ })).toHaveAttribute('aria-pressed', 'false');
  });

  it('offers Expertise only in skills the character holds', () => {
    const rogueFirst = mockClassRef({
      id: 'cls-rogue', name: 'Rogue', skillChoices: JSON.stringify({ count: 2, from: ['Stealth', 'Insight', 'Deception'] }),
    });
    renderStep({
      selectedClass: rogueFirst, classEntries: [{ cls: rogueFirst, level: 1, subclass: null, subclasses: [] }],
      selectedClassSkills: ['Stealth'], selectedExpertise: ['Stealth'],
    });

    const expertise = screen.getByRole('heading', { name: 'Expertise' }).closest('div')!.parentElement!;
    expect(expertise.querySelectorAll('button')).toHaveLength(1);
    expect(expertise.querySelector('button')).toHaveAttribute('aria-pressed', 'true');
  });

  it("offers a rogue's Expertise thieves' tools beside its skills, and asks for the rest until two are chosen (PHB p.96)", () => {
    const rogueFirst = mockClassRef({
      id: 'cls-rogue', name: 'Rogue', toolProficiencies: '["Thieves\' tools"]',
      skillChoices: JSON.stringify({ count: 4, from: ['Stealth', 'Insight', 'Deception', 'Acrobatics'] }),
    });
    renderStep({
      selectedClass: rogueFirst, classEntries: [{ cls: rogueFirst, level: 1, subclass: null, subclasses: [] }],
      selectedClassSkills: ['Stealth', 'Insight'], selectedExpertise: ["Thieves' Tools"],
    });

    const expertise = screen.getByRole('heading', { name: 'Expertise' }).closest('div')!.parentElement!;
    expect([...expertise.querySelectorAll('button')].map(b => b.textContent)).toEqual(['Stealth', 'Insight', "Thieves' Tools"]);
    expect(screen.getByRole('button', { name: "Thieves' Tools" })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('1/2 selected')).toBeInTheDocument();
    expect(screen.getByText('Select 1 more to continue')).toBeInTheDocument();
  });
});

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
