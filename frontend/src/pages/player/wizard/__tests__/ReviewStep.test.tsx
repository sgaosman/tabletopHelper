import { describe, it, expect } from 'vitest';
import { render, screen } from '../../../../test/test-utils';
import ReviewStep from '../ReviewStep';
import { initialWizardState, type WizardState } from '../wizardReducer';
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

function renderStep(overrides: Partial<WizardState> = {}) {
  const state: WizardState = {
    ...initialWizardState,
    name: 'Aria', selectedClass: fighter, classEntries: [{ cls: fighter, level: 1, subclass: null, subclasses: [] }], level: 1,
    abilityMethod: 'manual',
    scores: { strength: 15, dexterity: 14, constitution: 13, intelligence: 10, wisdom: 12, charisma: 8 },
    ...overrides,
  };
  return render(<ReviewStep state={state} error="" />);
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

  it('shows the proficiency bonus from the class tables the server serves', async () => {
    renderStep({ level: 5, classEntries: [{ cls: fighter, level: 5, subclass: null, subclasses: [] }] });

    expect(await screen.findByText('+3')).toBeInTheDocument();
  });

  it('lists nothing for a level 1 character whose 1st-level choices the steps made', () => {
    renderStep({ selectedClass: wizard, classEntries: [{ cls: wizard, level: 1, subclass: null, subclasses: [] }] });

    expect(screen.queryByRole('heading', { name: 'To Choose on Your Sheet' })).not.toBeInTheDocument();
  });
});
