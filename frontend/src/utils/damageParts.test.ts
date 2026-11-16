import { describe, it, expect } from 'vitest';
import { damageBreakdown } from './damageParts';

describe('damageBreakdown', () => {
  it('names each part of a hit of two types, in the order rolled', () => {
    expect(damageBreakdown(16, [{ type: 'slashing', amount: 12 }, { type: 'lightning', amount: 4 }]))
      .toBe('12 slashing + 4 lightning');
  });

  it('leaves one part to the caller, which shows the total and type as before', () => {
    expect(damageBreakdown(5, [{ type: 'slashing', amount: 5 }])).toBeNull();
    expect(damageBreakdown(5, [])).toBeNull();
    expect(damageBreakdown(5, undefined)).toBeNull();
  });

  it('skips a part the target took none of, as one it is immune to', () => {
    expect(damageBreakdown(9, [{ type: 'fire', amount: 0 }, { type: 'slashing', amount: 9 }])).toBeNull();
  });

  it('leaves the total when the parts do not add up to it, as after a floor such as Harm\'s', () => {
    expect(damageBreakdown(10, [{ type: 'slashing', amount: 12 }, { type: 'lightning', amount: 4 }])).toBeNull();
  });

  it('writes untyped damage as its amount alone', () => {
    expect(damageBreakdown(7, [{ type: 'fire', amount: 4 }, { type: null, amount: 3 }])).toBe('4 fire + 3');
  });
});
