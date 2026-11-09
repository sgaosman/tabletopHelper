import { describe, it, expect } from 'vitest';
import { formatArmourClass } from './armourClass';

describe('formatArmourClass', () => {
  it('adds the parts up to the total', () => {
    expect(formatArmourClass({
      total: 19,
      parts: [{ label: 'Chain Mail', amount: 16 }, { label: 'Shield', amount: 2 }, { label: 'DM adjustments', amount: 1 }],
      warnings: [],
      armourEntries: [],
    })).toBe('Chain Mail 16 + Shield 2 + DM adjustments 1 = 19');
  });

  it('takes a negative part away', () => {
    expect(formatArmourClass({
      total: 10,
      parts: [{ label: 'Leather Armor', amount: 11 }, { label: 'DEX', amount: -1 }],
      warnings: [],
      armourEntries: [],
    })).toBe('Leather Armor 11 − DEX 1 = 10');
  });

  it('shows a lone total when there are no parts', () => {
    expect(formatArmourClass({ total: 12, parts: [], warnings: [], armourEntries: [] })).toBe('12');
  });
});
