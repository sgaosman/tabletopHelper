import { describe, it, expect } from 'vitest';
import { parseFeatOptions } from './featSpellParser';

describe('featSpellParser', () => {
  // 21.1 parseFeatOptions: known spells (fixed cantrips)
  describe('parseFeatOptions: known spells', () => {
    it('extracts fixed cantrips from known spells', () => {
      const grantsFeatures = JSON.stringify([
        {
          name: 'Magic Initiate (Wizard)',
          known: { _: ['fire bolt#c', 'mage hand#c'] },
        },
      ]);

      const result = parseFeatOptions(grantsFeatures);
      expect(result).toHaveLength(1);
      expect(result[0].fixedCantrips).toEqual(['Fire Bolt', 'Mage Hand']);
    });
  });

  // 21.2 parseFeatOptions: innate spells (daily use)
  describe('parseFeatOptions: innate spells', () => {
    it('extracts innate daily-use spells', () => {
      const grantsFeatures = JSON.stringify([
        {
          name: 'Fey Touched',
          innate: { _: { daily: { '1': ['misty step'] } } },
        },
      ]);

      const result = parseFeatOptions(grantsFeatures);
      expect(result).toHaveLength(1);
      expect(result[0].fixedSpells).toEqual([
        { name: 'Misty Step', usesPerDay: 1 },
      ]);
    });
  });

  // 21.3 parseFeatOptions: choose from list
  describe('parseFeatOptions: choose from list', () => {
    it('extracts spell choices from a fixed list', () => {
      const grantsFeatures = JSON.stringify([
        {
          name: 'Shadow Touched',
          innate: {
            _: {
              daily: {
                '1': [
                  { choose: { from: ['cause fear', 'inflict wounds', 'silent image'], count: 1 } },
                ],
              },
            },
          },
        },
      ]);

      const result = parseFeatOptions(grantsFeatures);
      expect(result).toHaveLength(1);
      expect(result[0].spellChoice).not.toBeNull();
      expect(result[0].spellChoice!.fromList).toEqual(['Cause Fear', 'Inflict Wounds', 'Silent Image']);
      expect(result[0].spellChoice!.count).toBe(1);
    });
  });

  // 21.4 parseFeatOptions: choose by filter (class)
  describe('parseFeatOptions: choose by filter', () => {
    it('extracts cantrip choice with class filter', () => {
      const grantsFeatures = JSON.stringify([
        {
          name: 'Magic Initiate (Wizard)',
          known: { _: [{ choose: 'level=0|class=wizard', count: 2 }] },
        },
      ]);

      const result = parseFeatOptions(grantsFeatures);
      expect(result).toHaveLength(1);
      expect(result[0].cantripChoice).not.toBeNull();
      expect(result[0].cantripChoice!.count).toBe(2);
      expect(result[0].cantripChoice!.classes).toEqual(['Wizard']);
    });
  });

  // 21.5 parseFeatOptions: ability choice
  describe('parseFeatOptions: ability choice', () => {
    it('extracts fixed ability', () => {
      const grantsFeatures = JSON.stringify([
        {
          name: 'Heavily Armored',
          ability: 'str',
        },
      ]);

      const result = parseFeatOptions(grantsFeatures);
      expect(result).toHaveLength(1);
      expect(result[0].ability).toBe('STR');
    });

    it('extracts ability choices', () => {
      const grantsFeatures = JSON.stringify([
        {
          name: 'Skill Expert',
          ability: { choose: ['str', 'dex', 'con', 'int', 'wis', 'cha'] },
        },
      ]);

      const result = parseFeatOptions(grantsFeatures);
      expect(result).toHaveLength(1);
      expect(result[0].abilityChoices).toEqual(['STR', 'DEX', 'CON', 'INT', 'WIS', 'CHA']);
    });
  });

  // 21.6 parseFeatOptions: null grantsFeatures
  describe('parseFeatOptions: null/empty input', () => {
    it('returns empty array for null', () => {
      expect(parseFeatOptions(null)).toEqual([]);
    });

    it('returns empty array for empty string', () => {
      expect(parseFeatOptions('')).toEqual([]);
    });

    it('returns empty array for invalid JSON', () => {
      expect(parseFeatOptions('not json')).toEqual([]);
    });
  });

  // 21.7 parseFeatOptions: daily-use spells with specific uses
  describe('parseFeatOptions: daily-use spells', () => {
    it('parses spells with specific daily use count', () => {
      const grantsFeatures = JSON.stringify([
        {
          name: 'Fey Touched',
          innate: {
            _: {
              daily: {
                '1': ['misty step'],
                '1e': ['bless'],
              },
            },
          },
        },
      ]);

      const result = parseFeatOptions(grantsFeatures);
      expect(result).toHaveLength(1);
      // '1' -> usesPerDay: 1, '1e' -> usesPerDay: 1
      const spells = result[0].fixedSpells;
      expect(spells.length).toBe(2);
      expect(spells).toContainEqual({ name: 'Misty Step', usesPerDay: 1 });
      expect(spells).toContainEqual({ name: 'Bless', usesPerDay: 1 });
    });
  });
});
