import { describe, it, expect } from 'vitest';
import { checkFeatPrerequisites, parseFeatEffects, parseAbilityScoreIncrease } from './featPrerequisites';
import type { PlayerCharacter } from '../types/character';
import type { Feat } from '../types/reference';

function mockCharacter(overrides: Partial<PlayerCharacter> = {}): PlayerCharacter {
  return {
    id: 'test-id',
    userId: 'user-id',
    ownerDisplayName: 'Test User',
    name: 'Test Character',
    level: 5,
    experiencePoints: 0,
    strength: 14,
    dexterity: 12,
    constitution: 14,
    intelligence: 10,
    wisdom: 12,
    charisma: 10,
    hpMax: 40,
    hpCurrent: 40,
    hpTemp: 0,
    armourClass: 16,
    initiativeBonus: 1,
    speed: 30,
    proficiencyBonus: 3,
    deathSaveSuccesses: 0,
    deathSaveFailures: 0,
    isActive: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
    ...overrides,
  };
}

function mockFeat(overrides: Partial<Feat> = {}): Feat {
  return {
    id: 'feat-id',
    name: 'Test Feat',
    source: 'PHB',
    prerequisite: '',
    description: 'A test feat',
    abilityScoreIncrease: '',
    grantsFeatures: '',
    effects: '',
    ...overrides,
  };
}

describe('featPrerequisites', () => {
  // 20.1 checkFeatPrerequisites: ability score met
  describe('checkFeatPrerequisites', () => {
    it('returns eligible when ability score meets requirement', () => {
      const feat = mockFeat({
        name: 'Heavily Armored',
        prerequisite: JSON.stringify([{ ability: [{ strength: 13 }] }]),
      });
      const character = mockCharacter({ strength: 14 });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(true);
      expect(result.reason).toBeNull();
    });

    // 20.2 checkFeatPrerequisites: ability score not met
    it('returns ineligible with reason when ability score is below requirement', () => {
      const feat = mockFeat({
        name: 'Heavily Armored',
        prerequisite: JSON.stringify([{ ability: [{ strength: 13 }] }]),
      });
      const character = mockCharacter({ strength: 10 });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(false);
      expect(result.reason).toContain('Strength');
      expect(result.reason).toContain('13');
    });

    // 20.3 checkFeatPrerequisites: proficiency prerequisite
    it('returns eligible when character has required proficiency', () => {
      const feat = mockFeat({
        name: 'Heavy Armor Master',
        prerequisite: JSON.stringify([{ proficiency: ['heavy armor'] }]),
      });
      const character = mockCharacter({
        armorProficiencies: JSON.stringify(['Light Armor', 'Medium Armor', 'Heavy Armor', 'Shields']),
      });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(true);
    });

    it('returns ineligible when character lacks required proficiency', () => {
      const feat = mockFeat({
        name: 'Heavy Armor Master',
        prerequisite: JSON.stringify([{ proficiency: ['heavy armor'] }]),
      });
      const character = mockCharacter({
        armorProficiencies: JSON.stringify(['Light Armor']),
      });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(false);
      expect(result.reason).toContain('Proficiency');
    });

    // 20.4 checkFeatPrerequisites: spellcasting prerequisite
    it('returns ineligible when spellcasting is required but character is non-caster', () => {
      const feat = mockFeat({
        name: 'Ritual Caster',
        prerequisite: JSON.stringify([{ spellcasting: true }]),
      });
      const character = mockCharacter({ spellcastingAbility: undefined });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(false);
      expect(result.reason).toContain('Spellcasting');
    });

    it('returns eligible when spellcasting is required and character is a caster', () => {
      const feat = mockFeat({
        name: 'Ritual Caster',
        prerequisite: JSON.stringify([{ spellcasting: true }]),
      });
      const character = mockCharacter({ spellcastingAbility: 'intelligence' });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(true);
    });

    // 20.5 checkFeatPrerequisites: no prerequisites always passes
    it('returns eligible when feat has no prerequisites (null)', () => {
      const feat = mockFeat({ name: 'Alert', prerequisite: '' });
      const character = mockCharacter();

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(true);
      expect(result.reason).toBeNull();
    });
  });

  // 20.6 parseFeatEffects: ability score increase and proficiencies
  describe('parseFeatEffects', () => {
    it('parses effects with ASI data', () => {
      const feat = mockFeat({
        effects: JSON.stringify({
          armorProficiencies: ['Medium Armor', 'Shields'],
          speedBonus: 10,
        }),
      });

      const result = parseFeatEffects(feat);
      expect(result).not.toBeNull();
      expect(result!.armorProficiencies).toEqual(['Medium Armor', 'Shields']);
      expect(result!.speedBonus).toBe(10);
    });

    // 20.7 parseFeatEffects: proficiency list
    it('parses effects with multiple proficiency types', () => {
      const feat = mockFeat({
        effects: JSON.stringify({
          armorProficiencies: ['Medium Armor', 'Shields'],
          weaponProficiencies: ['Martial Weapons'],
          skillProficiencies: ['Athletics'],
        }),
      });

      const result = parseFeatEffects(feat);
      expect(result).not.toBeNull();
      expect(result!.armorProficiencies).toEqual(['Medium Armor', 'Shields']);
      expect(result!.weaponProficiencies).toEqual(['Martial Weapons']);
      expect(result!.skillProficiencies).toEqual(['Athletics']);
    });

    it('returns null when effects field is empty', () => {
      const feat = mockFeat({ effects: '' });
      const result = parseFeatEffects(feat);
      expect(result).toBeNull();
    });

    it('handles already-parsed effects object', () => {
      const effectsObj = { speedBonus: 10, initiativeBonus: 5 };
      const feat = mockFeat({ effects: effectsObj as unknown as string });
      const result = parseFeatEffects(feat);
      expect(result).not.toBeNull();
      expect(result!.speedBonus).toBe(10);
      expect(result!.initiativeBonus).toBe(5);
    });
  });

  // 20.8 parseAbilityScoreIncrease: fixed and choice-based
  describe('parseAbilityScoreIncrease', () => {
    it('parses fixed ASI (Heavily Armored: +1 STR)', () => {
      const feat = mockFeat({
        abilityScoreIncrease: JSON.stringify([{ strength: 1 }]),
      });

      const result = parseAbilityScoreIncrease(feat);
      expect(result).not.toBeNull();
      expect(result!.fixed).toEqual({ strength: 1 });
      expect(result!.choose).toBeNull();
    });

    it('parses choice-based ASI (Resilient: choose one ability +1)', () => {
      const feat = mockFeat({
        abilityScoreIncrease: JSON.stringify([
          { choose: { from: ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'], amount: 1 } },
        ]),
      });

      const result = parseAbilityScoreIncrease(feat);
      expect(result).not.toBeNull();
      expect(result!.fixed).toEqual({});
      expect(result!.choose).not.toBeNull();
      expect(result!.choose!.from).toHaveLength(6);
      expect(result!.choose!.amount).toBe(1);
    });

    it('returns null when abilityScoreIncrease is empty', () => {
      const feat = mockFeat({ abilityScoreIncrease: '' });
      const result = parseAbilityScoreIncrease(feat);
      expect(result).toBeNull();
    });
  });
});
