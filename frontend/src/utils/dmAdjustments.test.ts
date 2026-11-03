import { describe, it, expect } from 'vitest';
import { makeMockAdjustment } from '../test/mocks/handlers';
import { activeAdjustments, describeAdjustment, removedAdjustments } from './dmAdjustments';

describe('describeAdjustment', () => {
  it('signs a stat adjustment and names the stat', () => {
    expect(describeAdjustment(makeMockAdjustment())).toBe('+1 AC');
    expect(describeAdjustment(makeMockAdjustment({ target: 'HP_MAX', amount: -5 }))).toBe('-5 HP maximum');
    expect(describeAdjustment(makeMockAdjustment({ target: 'SPEED', amount: 10 }))).toBe('+10 ft speed');
    expect(describeAdjustment(makeMockAdjustment({ target: 'CON', amount: 2 }))).toBe('+2 CON');
    expect(describeAdjustment(makeMockAdjustment({ target: 'SPELL_SAVE_DC', amount: 1 }))).toBe('+1 spell save DC');
  });

  it('names a defence and its damage type, and a feat granted', () => {
    expect(describeAdjustment(makeMockAdjustment({ kind: 'DEFENCE', target: 'fire', amount: null, defence: 'RESISTANCE' })))
      .toBe('Resistance to fire');
    expect(describeAdjustment(makeMockAdjustment({ kind: 'DEFENCE', target: 'cold', amount: null, defence: 'VULNERABILITY' })))
      .toBe('Vulnerability to cold');
    expect(describeAdjustment(makeMockAdjustment({ kind: 'FEAT', target: 'Lucky', amount: null }))).toBe('Feat: Lucky');
  });
});

describe('activeAdjustments and removedAdjustments', () => {
  const ring = makeMockAdjustment({ id: 'adj-ring' });
  const curse = makeMockAdjustment({ id: 'adj-curse', removedAt: '2026-11-03T11:00:00Z', removedBy: 'user-001' });
  const boon = makeMockAdjustment({ id: 'adj-boon', removedAt: '2026-11-03T12:00:00Z', removedBy: 'user-001' });

  it('keeps those that count, oldest first', () => {
    expect(activeAdjustments([ring, curse, boon]).map(a => a.id)).toEqual(['adj-ring']);
    expect(activeAdjustments(null)).toEqual([]);
  });

  it('keeps the removed ones as the record, the latest removed first', () => {
    expect(removedAdjustments([ring, curse, boon]).map(a => a.id)).toEqual(['adj-boon', 'adj-curse']);
    expect(removedAdjustments(undefined)).toEqual([]);
  });
});
