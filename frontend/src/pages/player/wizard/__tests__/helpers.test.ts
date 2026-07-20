import { describe, it, expect } from 'vitest';
import {
  checkMulticlassEligibility,
  isAsiLevel,
  countAsiLevels,
  expandToolFrom,
  getToolAnyOptions,
  ARTISANS_TOOLS,
  MUSICAL_INSTRUMENTS,
  GAMING_SETS,
} from '../types';
import type { AbilityScores, ClassEntry } from '../types';
import type { CharacterClassRef } from '../../../../types/reference';

function mockClassRef(overrides: Partial<CharacterClassRef> = {}): CharacterClassRef {
  return {
    id: 'class-id',
    name: 'Fighter',
    source: 'PHB',
    hitDice: 10,
    primaryAbility: 'STR',
    savingThrowProficiencies: '["STR","CON"]',
    armorProficiencies: '["Light Armor","Medium Armor","Heavy Armor","Shields"]',
    weaponProficiencies: '["Simple Weapons","Martial Weapons"]',
    toolProficiencies: '[]',
    skillChoices: '{"choose":{"from":["Acrobatics","Animal Handling","Athletics","History","Insight","Intimidation","Perception","Survival"],"count":2}}',
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

function mockScores(overrides: Partial<AbilityScores> = {}): AbilityScores {
  return {
    strength: 14,
    dexterity: 12,
    constitution: 14,
    intelligence: 10,
    wisdom: 12,
    charisma: 10,
    ...overrides,
  };
}

describe('wizard helper functions', () => {
  // 23.1 checkMulticlassEligibility: exit and entry prerequisites
  describe('checkMulticlassEligibility', () => {
    it('returns eligible when all prerequisites are met', () => {
      const cls = mockClassRef({
        name: 'Wizard',
        multiclassRequirements: JSON.stringify([{ ability: 'INT', minimum: 13 }]),
      });
      const scores = mockScores({ intelligence: 14 });

      const result = checkMulticlassEligibility(cls, scores);
      expect(result.eligible).toBe(true);
    });

    it('returns ineligible when prerequisites are not met', () => {
      const cls = mockClassRef({
        name: 'Wizard',
        multiclassRequirements: JSON.stringify([{ ability: 'INT', minimum: 13 }]),
      });
      const scores = mockScores({ intelligence: 10 });

      const result = checkMulticlassEligibility(cls, scores);
      expect(result.eligible).toBe(false);
      expect(result.reason).toContain('INT');
    });

    // 23.2 checkMulticlassEligibility: OR prerequisites
    it('returns eligible when at least one OR prerequisite is met', () => {
      const cls = mockClassRef({
        name: 'Ranger',
        multiclassRequirements: JSON.stringify([
          { ability: 'STR', minimum: 13, operator: 'OR' },
          { ability: 'DEX', minimum: 13, operator: 'OR' },
        ]),
      });
      const scores = mockScores({ strength: 10, dexterity: 15 });

      const result = checkMulticlassEligibility(cls, scores);
      expect(result.eligible).toBe(true);
    });

    // 23.3 checkMulticlassEligibility: AND prerequisites
    it('returns ineligible when any AND prerequisite fails', () => {
      const cls = mockClassRef({
        name: 'Paladin',
        multiclassRequirements: JSON.stringify([
          { ability: 'STR', minimum: 13 },
          { ability: 'CHA', minimum: 13 },
        ]),
      });
      const scores = mockScores({ strength: 14, charisma: 11 });

      const result = checkMulticlassEligibility(cls, scores);
      expect(result.eligible).toBe(false);
      expect(result.reason).toContain('CHA');
    });

    it('returns eligible when no multiclassRequirements', () => {
      const cls = mockClassRef({ multiclassRequirements: null });
      const scores = mockScores();

      const result = checkMulticlassEligibility(cls, scores);
      expect(result.eligible).toBe(true);
    });
  });

  // 23.4 isAsiLevel: standard + Fighter/Rogue extras
  describe('isAsiLevel', () => {
    it('returns true at level 4 for all classes', () => {
      expect(isAsiLevel('Fighter', 4)).toBe(true);
      expect(isAsiLevel('Rogue', 4)).toBe(true);
      expect(isAsiLevel('Wizard', 4)).toBe(true);
    });

    it('returns true at level 6 only for Fighter', () => {
      expect(isAsiLevel('Fighter', 6)).toBe(true);
      expect(isAsiLevel('Rogue', 6)).toBe(false);
      expect(isAsiLevel('Wizard', 6)).toBe(false);
    });

    it('returns true at level 10 only for Rogue', () => {
      expect(isAsiLevel('Fighter', 10)).toBe(false);
      expect(isAsiLevel('Rogue', 10)).toBe(true);
      expect(isAsiLevel('Wizard', 10)).toBe(false);
    });

    it('returns true at level 14 only for Fighter', () => {
      expect(isAsiLevel('Fighter', 14)).toBe(true);
      expect(isAsiLevel('Rogue', 14)).toBe(false);
      expect(isAsiLevel('Wizard', 14)).toBe(false);
    });

    it('returns false at non-ASI levels', () => {
      expect(isAsiLevel('Wizard', 3)).toBe(false);
      expect(isAsiLevel('Wizard', 5)).toBe(false);
      expect(isAsiLevel('Wizard', 7)).toBe(false);
    });

    it('returns true at standard ASI levels (8, 12, 16, 19) for all classes', () => {
      for (const level of [8, 12, 16, 19]) {
        expect(isAsiLevel('Wizard', level)).toBe(true);
        expect(isAsiLevel('Fighter', level)).toBe(true);
        expect(isAsiLevel('Rogue', level)).toBe(true);
      }
    });
  });

  // 23.5 countAsiLevels: total ASIs up to a level
  describe('countAsiLevels', () => {
    it('counts 3 ASIs for Fighter at level 8 (levels 4, 6, 8)', () => {
      const entries: ClassEntry[] = [
        { cls: mockClassRef({ name: 'Fighter' }), level: 8, subclass: null, subclasses: [] },
      ];
      expect(countAsiLevels(entries)).toBe(3);
    });

    it('counts 2 ASIs for Wizard at level 8 (levels 4, 8)', () => {
      const entries: ClassEntry[] = [
        { cls: mockClassRef({ name: 'Wizard' }), level: 8, subclass: null, subclasses: [] },
      ];
      expect(countAsiLevels(entries)).toBe(2);
    });

    it('counts ASIs across multiclass entries', () => {
      const entries: ClassEntry[] = [
        { cls: mockClassRef({ name: 'Fighter' }), level: 6, subclass: null, subclasses: [] },
        { cls: mockClassRef({ name: 'Wizard' }), level: 4, subclass: null, subclasses: [] },
      ];
      // Fighter levels 4,6 = 2 ASIs, Wizard level 4 = 1 ASI -> total 3
      expect(countAsiLevels(entries)).toBe(3);
    });
  });

  // 23.6 expandToolFrom: "Any Artisan's Tool"
  describe('expandToolFrom', () => {
    it('expands AnyArtisansTool to all artisan tools', () => {
      const result = expandToolFrom(['AnyArtisansTool']);
      expect(result).toEqual(ARTISANS_TOOLS);
      expect(result.length).toBeGreaterThan(0);
      expect(result).toContain("Smith's Tools");
      expect(result).toContain("Brewer's Supplies");
    });

    it('expands Musical instrument to all instruments', () => {
      const result = expandToolFrom(['Musical instrument']);
      expect(result).toEqual(MUSICAL_INSTRUMENTS);
    });

    it('expands Gaming set to all gaming sets', () => {
      const result = expandToolFrom(['Gaming set']);
      expect(result).toEqual(GAMING_SETS);
    });

    it('passes through non-expandable tool names', () => {
      const result = expandToolFrom(["Thieves' Tools", "Herbalism Kit"]);
      expect(result).toEqual(["Thieves' Tools", "Herbalism Kit"]);
    });
  });

  // 23.7 getToolAnyOptions: category options
  describe('getToolAnyOptions', () => {
    it('returns gaming sets for "Any Gaming Set"', () => {
      expect(getToolAnyOptions('Any Gaming Set')).toEqual(GAMING_SETS);
    });

    it('returns artisan tools for "Any Artisan\'s Tool"', () => {
      expect(getToolAnyOptions("Any Artisan's Tool")).toEqual(ARTISANS_TOOLS);
    });

    it('returns musical instruments for "Any Musical Instrument"', () => {
      expect(getToolAnyOptions('Any Musical Instrument')).toEqual(MUSICAL_INSTRUMENTS);
    });

    it('returns null for unrecognized entries', () => {
      expect(getToolAnyOptions("Thieves' Tools")).toBeNull();
    });
  });
});
