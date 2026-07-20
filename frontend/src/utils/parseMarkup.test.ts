import { describe, it, expect } from 'vitest';
import { parseMarkup } from './parseMarkup';

describe('parseMarkup', () => {
  // 22.1 {@bold text} -> bold text
  describe('bold tags', () => {
    it('converts {@bold text} to <strong> HTML', () => {
      expect(parseMarkup('{@bold important rules}')).toBe('<strong>important rules</strong>');
    });

    it('converts {@b text} to <strong> HTML', () => {
      expect(parseMarkup('{@b some text}')).toBe('<strong>some text</strong>');
    });
  });

  // 22.2 {@spell fireball} -> "fireball"
  describe('spell tags', () => {
    it('extracts spell name from {@spell} tag', () => {
      expect(parseMarkup('{@spell fireball}')).toBe('fireball');
    });
  });

  // 22.3 {@item longsword} -> "longsword"
  describe('item tags', () => {
    it('extracts item name from {@item} tag', () => {
      expect(parseMarkup('{@item longsword}')).toBe('longsword');
    });
  });

  // 22.4 Nested/chained tags
  describe('chained tags', () => {
    it('processes multiple tags in sequence', () => {
      const input = 'Deal {@damage 8d6} fire damage with {@spell fireball}';
      const result = parseMarkup(input);
      expect(result).toBe('Deal 8d6 fire damage with fireball');
    });

    it('handles dice tags', () => {
      expect(parseMarkup('{@dice 2d6}')).toBe('2d6');
    });

    it('handles DC tags', () => {
      expect(parseMarkup('{@dc 15}')).toBe('DC 15');
    });

    it('handles hit tags', () => {
      expect(parseMarkup('{@hit 5}')).toBe('+5');
    });

    it('handles attack type tags', () => {
      expect(parseMarkup('{@atk mw}')).toBe('Melee Weapon Attack:');
    });
  });

  // 22.5 Plain text passes through
  describe('plain text passthrough', () => {
    it('returns plain text unchanged', () => {
      const text = 'The dragon breathes fire in a 60-foot cone.';
      expect(parseMarkup(text)).toBe(text);
    });

    it('returns empty string for null/undefined input', () => {
      expect(parseMarkup(null as unknown as string)).toBe('');
      expect(parseMarkup(undefined as unknown as string)).toBe('');
    });
  });

  // 22.6 Pipe-separated display name
  describe('pipe-separated display name', () => {
    it('strips source info after pipe for spell tags', () => {
      expect(parseMarkup('{@spell fireball|PHB}')).toBe('fireball');
    });

    it('strips source info after pipe for creature tags', () => {
      expect(parseMarkup('{@creature goblin|mm}')).toBe('goblin');
    });

    it('strips source info after pipe for item tags', () => {
      expect(parseMarkup('{@item longsword|PHB}')).toBe('longsword');
    });

    it('handles dice with pipe display text', () => {
      expect(parseMarkup('{@dice 2d6|2d6}')).toBe('2d6');
    });
  });
});
