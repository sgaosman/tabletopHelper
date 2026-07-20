import { describe, it, expect } from 'vitest';
import { abilityMod, formatMod, formatAbilityMod, safeJsonParse } from './dndRules';

describe('dndRules', () => {
  // 18.1 abilityMod: standard score calculations
  describe('abilityMod', () => {
    it('returns 0 for score 10 (D&D baseline)', () => {
      expect(abilityMod(10)).toBe(0);
    });

    it('returns -1 for score 8', () => {
      expect(abilityMod(8)).toBe(-1);
    });

    it('returns +2 for score 14', () => {
      expect(abilityMod(14)).toBe(2);
    });

    it('returns +5 for score 20 (max normal)', () => {
      expect(abilityMod(20)).toBe(5);
    });
  });

  // 18.2 formatMod: plus/minus/zero formatting
  describe('formatMod', () => {
    it('prefixes positive values with "+"', () => {
      expect(formatMod(3)).toBe('+3');
    });

    it('uses "-" for negative values', () => {
      expect(formatMod(-2)).toBe('-2');
    });

    it('formats zero as "+0"', () => {
      expect(formatMod(0)).toBe('+0');
    });
  });

  // 18.3 formatAbilityMod: score to formatted modifier
  describe('formatAbilityMod', () => {
    it('combines abilityMod and formatMod (score 16 -> "+3")', () => {
      expect(formatAbilityMod(16)).toBe('+3');
    });

    it('handles low scores (score 8 -> "-1")', () => {
      expect(formatAbilityMod(8)).toBe('-1');
    });
  });

  // 18.4 safeJsonParse: valid JSON string
  describe('safeJsonParse', () => {
    it('parses a valid JSON object string', () => {
      const result = safeJsonParse('{"name":"Thorin","level":5}', {});
      expect(result).toEqual({ name: 'Thorin', level: 5 });
    });

    it('parses a valid JSON array string', () => {
      const result = safeJsonParse('["Athletics","Perception"]', []);
      expect(result).toEqual(['Athletics', 'Perception']);
    });

    // 18.5 safeJsonParse: invalid/null/undefined returns fallback
    it('returns fallback for null', () => {
      expect(safeJsonParse(null, [])).toEqual([]);
    });

    it('returns fallback for undefined', () => {
      expect(safeJsonParse(undefined, [])).toEqual([]);
    });

    it('returns fallback for malformed JSON', () => {
      expect(safeJsonParse('not valid json {', [])).toEqual([]);
    });

    // 18.6 safeJsonParse: already-parsed object returned as-is
    it('returns an already-parsed object as-is', () => {
      const obj = { name: 'Thorin' };
      expect(safeJsonParse(obj, {})).toBe(obj);
    });

    it('returns an already-parsed array as-is', () => {
      const arr = ['Athletics', 'Perception'];
      expect(safeJsonParse(arr, [])).toBe(arr);
    });
  });
});
