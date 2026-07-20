import { describe, it, expect } from 'vitest';
import {
  wizardSpellbookCount,
  getPreparedCount,
  maxSpellLevel,
  proficiencyBonusForLevel,
  thirdCasterMulticlassContribution,
} from './spellConstants';

describe('spellConstants', () => {
  // 19.1 wizardSpellbookCount at various levels
  describe('wizardSpellbookCount', () => {
    it('returns 6 spells at level 1', () => {
      expect(wizardSpellbookCount(1)).toBe(6);
    });

    it('returns 8 spells at level 2', () => {
      expect(wizardSpellbookCount(2)).toBe(8);
    });

    it('returns 14 spells at level 5', () => {
      expect(wizardSpellbookCount(5)).toBe(14);
    });

    it('returns 44 spells at level 20', () => {
      expect(wizardSpellbookCount(20)).toBe(44);
    });

    it('returns 0 for level 0 or below', () => {
      expect(wizardSpellbookCount(0)).toBe(0);
    });
  });

  // 19.2 getPreparedCount for full caster (Cleric)
  describe('getPreparedCount', () => {
    it('returns level + abilityMod for full caster (Cleric level 5, WIS +3 -> 8)', () => {
      expect(getPreparedCount('Cleric', 5, 3)).toBe(8);
    });

    // 19.3 getPreparedCount for half caster (Paladin)
    it('returns floor(level/2) + abilityMod for half caster (Paladin level 6, CHA +2 -> 5)', () => {
      expect(getPreparedCount('Paladin', 6, 2)).toBe(5);
    });

    // 19.4 getPreparedCount: minimum 1 with negative modifier
    it('returns minimum 1 when result would be 0 or negative (Cleric level 1, WIS -1)', () => {
      expect(getPreparedCount('Cleric', 1, -1)).toBe(1);
    });

    it('returns minimum 1 for Paladin with negative modifier', () => {
      expect(getPreparedCount('Paladin', 2, -3)).toBe(1);
    });

    it('uses half-caster formula for Artificer', () => {
      expect(getPreparedCount('Artificer', 6, 3)).toBe(6); // floor(6/2) + 3 = 6
    });
  });

  // 19.5 maxSpellLevel for full caster
  describe('maxSpellLevel', () => {
    it('returns 1 for full caster at level 1', () => {
      expect(maxSpellLevel('Wizard', 1)).toBe(1);
    });

    it('returns 2 for full caster at level 3', () => {
      expect(maxSpellLevel('Wizard', 3)).toBe(2);
    });

    it('returns 3 for full caster at level 5', () => {
      expect(maxSpellLevel('Wizard', 5)).toBe(3);
    });

    it('returns 5 for full caster at level 9', () => {
      expect(maxSpellLevel('Wizard', 9)).toBe(5);
    });

    it('caps at 9 for full casters', () => {
      expect(maxSpellLevel('Wizard', 20)).toBe(9);
    });

    // 19.6 maxSpellLevel for half caster
    it('returns 0 for half caster at level 1 (below threshold)', () => {
      expect(maxSpellLevel('Paladin', 1)).toBe(0);
    });

    it('returns 1 for half caster at level 2', () => {
      expect(maxSpellLevel('Paladin', 2)).toBe(1);
    });

    it('returns 2 for half caster at level 5', () => {
      expect(maxSpellLevel('Paladin', 5)).toBe(2);
    });

    it('returns 3 for half caster at level 9', () => {
      expect(maxSpellLevel('Paladin', 9)).toBe(3);
    });

    // 19.7 maxSpellLevel for 1/3 caster (EK/AT)
    it('returns 0 for third caster below level 3', () => {
      expect(maxSpellLevel('Fighter', 2, 'Eldritch Knight')).toBe(0);
    });

    it('returns 1 for Eldritch Knight at level 3', () => {
      expect(maxSpellLevel('Fighter', 3, 'Eldritch Knight')).toBe(1);
    });

    it('returns 2 for Eldritch Knight at level 7', () => {
      expect(maxSpellLevel('Fighter', 7, 'Eldritch Knight')).toBe(2);
    });

    it('returns 0 for non-caster Fighter without subclass', () => {
      expect(maxSpellLevel('Fighter', 10)).toBe(0);
    });

    it('returns correct level for Warlock (pact caster)', () => {
      expect(maxSpellLevel('Warlock', 1)).toBe(1);
      expect(maxSpellLevel('Warlock', 5)).toBe(3);
      expect(maxSpellLevel('Warlock', 9)).toBe(5);
    });
  });

  // 23.8 proficiencyBonusForLevel
  describe('proficiencyBonusForLevel', () => {
    it('returns +2 for levels 1-4', () => {
      expect(proficiencyBonusForLevel(1)).toBe(2);
      expect(proficiencyBonusForLevel(4)).toBe(2);
    });

    it('returns +3 for levels 5-8', () => {
      expect(proficiencyBonusForLevel(5)).toBe(3);
      expect(proficiencyBonusForLevel(8)).toBe(3);
    });

    it('returns +4 for levels 9-12', () => {
      expect(proficiencyBonusForLevel(9)).toBe(4);
      expect(proficiencyBonusForLevel(12)).toBe(4);
    });

    it('returns +5 for levels 13-16', () => {
      expect(proficiencyBonusForLevel(13)).toBe(5);
      expect(proficiencyBonusForLevel(16)).toBe(5);
    });

    it('returns +6 for levels 17-20', () => {
      expect(proficiencyBonusForLevel(17)).toBe(6);
      expect(proficiencyBonusForLevel(20)).toBe(6);
    });
  });

  // 23.9 thirdCasterMulticlassContribution
  describe('thirdCasterMulticlassContribution', () => {
    it('returns 0 for level 2 and below', () => {
      expect(thirdCasterMulticlassContribution(1)).toBe(0);
      expect(thirdCasterMulticlassContribution(2)).toBe(0);
    });

    it('returns 1 for level 3', () => {
      expect(thirdCasterMulticlassContribution(3)).toBe(1);
    });

    it('returns 2 for level 6', () => {
      expect(thirdCasterMulticlassContribution(6)).toBe(2);
    });

    it('returns 3 for level 9', () => {
      expect(thirdCasterMulticlassContribution(9)).toBe(3);
    });
  });
});
