import { describe, it, expect } from 'vitest';
import { CLASS_TABLES } from '../test/mocks/classTables';
import {
  cantripsKnown, highestSpellLevel, isThirdCasterSubclass, proficiencyBonus, spellbookSize, spellsKnown,
} from './classTables';

// The cases that tested spellConstants' tables, now read from the server's class tables (D169) as the mocked
// endpoint serves them
describe('classTables', () => {
  // 19.1 a wizard's spellbook at various levels
  describe('spellbookSize', () => {
    it('returns 6 spells at level 1', () => {
      expect(spellbookSize(CLASS_TABLES, 'Wizard', 1)).toBe(6);
    });

    it('returns 8 spells at level 2', () => {
      expect(spellbookSize(CLASS_TABLES, 'Wizard', 2)).toBe(8);
    });

    it('returns 14 spells at level 5', () => {
      expect(spellbookSize(CLASS_TABLES, 'Wizard', 5)).toBe(14);
    });

    it('returns 44 spells at level 20', () => {
      expect(spellbookSize(CLASS_TABLES, 'Wizard', 20)).toBe(44);
    });

    it('returns 0 for level 0 or below, and for a class without a spellbook', () => {
      expect(spellbookSize(CLASS_TABLES, 'Wizard', 0)).toBe(0);
      expect(spellbookSize(CLASS_TABLES, 'Sorcerer', 5)).toBe(0);
    });
  });

  // 19.5 to 19.7 the highest spell level
  describe('highestSpellLevel', () => {
    it('returns 1 for full caster at level 1', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Wizard', 1)).toBe(1);
    });

    it('returns 2 for full caster at level 3', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Wizard', 3)).toBe(2);
    });

    it('returns 3 for full caster at level 5', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Wizard', 5)).toBe(3);
    });

    it('returns 5 for full caster at level 9', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Wizard', 9)).toBe(5);
    });

    it('caps at 9 for full casters', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Wizard', 20)).toBe(9);
    });

    it('returns 0 for half caster at level 1 (below threshold)', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Paladin', 1)).toBe(0);
    });

    it('returns 1 for half caster at level 2', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Paladin', 2)).toBe(1);
    });

    it('returns 2 for half caster at level 5', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Paladin', 5)).toBe(2);
    });

    it('returns 3 for half caster at level 9', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Paladin', 9)).toBe(3);
    });

    it('returns 0 for third caster below level 3', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Fighter', 2, 'Eldritch Knight')).toBe(0);
    });

    it('returns 1 for Eldritch Knight at level 3', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Fighter', 3, 'Eldritch Knight')).toBe(1);
    });

    it('returns 2 for Eldritch Knight at level 7', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Fighter', 7, 'Eldritch Knight')).toBe(2);
    });

    it('returns 0 for non-caster Fighter without subclass', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Fighter', 10)).toBe(0);
    });

    it('returns correct level for Warlock (pact caster)', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Warlock', 1)).toBe(1);
      expect(highestSpellLevel(CLASS_TABLES, 'Warlock', 5)).toBe(3);
      expect(highestSpellLevel(CLASS_TABLES, 'Warlock', 9)).toBe(5);
    });

    it('follows the Artificer table, which rounds up', () => {
      expect(highestSpellLevel(CLASS_TABLES, 'Artificer', 1)).toBe(1);
      expect(highestSpellLevel(CLASS_TABLES, 'Artificer', 5)).toBe(2);
      expect(highestSpellLevel(CLASS_TABLES, 'Artificer', 17)).toBe(5);
    });
  });

  // 23.8 the proficiency bonus
  describe('proficiencyBonus', () => {
    it('returns +2 for levels 1-4', () => {
      expect(proficiencyBonus(CLASS_TABLES, 1)).toBe(2);
      expect(proficiencyBonus(CLASS_TABLES, 4)).toBe(2);
    });

    it('returns +3 for levels 5-8', () => {
      expect(proficiencyBonus(CLASS_TABLES, 5)).toBe(3);
      expect(proficiencyBonus(CLASS_TABLES, 8)).toBe(3);
    });

    it('returns +4 for levels 9-12', () => {
      expect(proficiencyBonus(CLASS_TABLES, 9)).toBe(4);
      expect(proficiencyBonus(CLASS_TABLES, 12)).toBe(4);
    });

    it('returns +5 for levels 13-16', () => {
      expect(proficiencyBonus(CLASS_TABLES, 13)).toBe(5);
      expect(proficiencyBonus(CLASS_TABLES, 16)).toBe(5);
    });

    it('returns +6 for levels 17-20', () => {
      expect(proficiencyBonus(CLASS_TABLES, 17)).toBe(6);
      expect(proficiencyBonus(CLASS_TABLES, 20)).toBe(6);
    });
  });

  describe('cantrips and spells known', () => {
    it('reads each class column by class level', () => {
      expect(cantripsKnown(CLASS_TABLES, 'Sorcerer', 1)).toBe(4);
      expect(cantripsKnown(CLASS_TABLES, 'Cleric', 10)).toBe(5);
      expect(spellsKnown(CLASS_TABLES, 'Bard', 10)).toBe(14);
      expect(spellsKnown(CLASS_TABLES, 'Ranger', 1)).toBe(0);
    });

    it("reads an Eldritch Knight's and Arcane Trickster's own columns, and none for a class that prepares", () => {
      expect(cantripsKnown(CLASS_TABLES, 'Fighter', 3, 'Eldritch Knight')).toBe(2);
      expect(cantripsKnown(CLASS_TABLES, 'Rogue', 3, 'Arcane Trickster')).toBe(3);
      expect(spellsKnown(CLASS_TABLES, 'Rogue', 20, 'Arcane Trickster')).toBe(13);
      expect(cantripsKnown(CLASS_TABLES, 'Fighter', 3, 'Champion')).toBe(0);
      expect(spellsKnown(CLASS_TABLES, 'Wizard', 5)).toBe(0);
      expect(cantripsKnown(CLASS_TABLES, 'Mystic', 5)).toBe(0);
    });

    it('knows the third-caster subclasses only under their own class', () => {
      expect(isThirdCasterSubclass(CLASS_TABLES, 'Fighter', 'Eldritch Knight')).toBe(true);
      expect(isThirdCasterSubclass(CLASS_TABLES, 'Rogue', 'Arcane Trickster')).toBe(true);
      expect(isThirdCasterSubclass(CLASS_TABLES, 'Fighter', 'Arcane Trickster')).toBe(false);
      expect(isThirdCasterSubclass(CLASS_TABLES, 'Fighter', 'Champion')).toBe(false);
      expect(isThirdCasterSubclass(CLASS_TABLES, 'Fighter', null)).toBe(false);
    });
  });
});
