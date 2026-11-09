import { describe, expect, it } from 'vitest';
import { exhaustionEffects, exhaustionLabel } from './exhaustion';

describe('exhaustion', () => {
  it('lists each level with the effects of those below (PHB p.291)', () => {
    expect(exhaustionEffects(0)).toEqual([]);
    expect(exhaustionEffects(undefined)).toEqual([]);
    expect(exhaustionEffects(2)).toEqual(['Disadvantage on ability checks', 'Speed halved']);
    expect(exhaustionEffects(6)).toHaveLength(6);
    expect(exhaustionEffects(9)).toHaveLength(6);
  });

  it('labels the chip only for a level above 0', () => {
    expect(exhaustionLabel(3)).toBe('Exhausted (3)');
    expect(exhaustionLabel(0)).toBe('');
    expect(exhaustionLabel(null)).toBe('');
  });
});
