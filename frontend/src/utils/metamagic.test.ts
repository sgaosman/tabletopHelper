import { describe, it, expect } from 'vitest';
import type { MetamagicOption } from '../types/combat';
import {
  chosenCost, creationCost, expendableLevels, metamagicBlock, metamagicCombines, metamagicCost, slotsLeft, slotTotal,
} from './metamagic';

const twinned: MetamagicOption = {
  option: 'TWINNED', name: 'Twinned Spell', cost: 2, costIsCastLevel: true, maxCastLevel: 2, ineligibleReason: null,
};
const quickened: MetamagicOption = {
  option: 'QUICKENED', name: 'Quickened Spell', cost: 2, costIsCastLevel: false, maxCastLevel: null,
  ineligibleReason: null,
};
const empowered: MetamagicOption = {
  option: 'EMPOWERED', name: 'Empowered Spell', cost: 1, costIsCastLevel: false, maxCastLevel: null,
  ineligibleReason: null,
};

describe('metamagic', () => {
  it('creates slots by the Creating Spell Slots table, no higher than 5th (PHB p.101)', () => {
    expect([1, 2, 3, 4, 5].map(creationCost)).toEqual([2, 3, 5, 6, 7]);
    expect(creationCost(6)).toBeNull();
    expect(creationCost(0)).toBeNull();
  });

  it('counts created slots with the level, and lists the levels with one left to expend', () => {
    expect(slotTotal({ max: 2, used: 1, created: 1 })).toBe(3);
    expect(slotsLeft({ max: 2, used: 1, created: 1 })).toBe(2);
    expect(slotsLeft({ max: 2, used: 2 })).toBe(0);
    expect(expendableLevels({
      '1': { max: 4, used: 4 }, '3': { max: 0, used: 0, created: 1 }, pact_2: { max: 2, used: 0 },
    })).toEqual([2, 3]);
  });

  it("costs Twinned Spell the cast level, 1 for a cantrip, and blocks it above a target-scaling spell's own level", () => {
    expect(metamagicCost(twinned, 0)).toBe(1);
    expect(metamagicCost(twinned, 4)).toBe(4);
    expect(metamagicCost(quickened, 5)).toBe(2);
    expect(metamagicBlock(twinned, 2)).toBeNull();
    expect(metamagicBlock(twinned, 3)).toMatch(/can't target more than one creature/);
    expect(metamagicBlock({ ...quickened, ineligibleReason: 'Quickened Spell needs a casting time of 1 action' }, 1))
      .toBe('Quickened Spell needs a casting time of 1 action');
    expect(chosenCost([twinned, quickened, empowered], ['TWINNED', 'EMPOWERED'], 2)).toBe(3);
  });

  it('allows one option per spell, and Empowered Spell with another (PHB p.102)', () => {
    expect(metamagicCombines('QUICKENED', [])).toBe(true);
    expect(metamagicCombines('EMPOWERED', ['QUICKENED'])).toBe(true);
    expect(metamagicCombines('QUICKENED', ['EMPOWERED'])).toBe(true);
    expect(metamagicCombines('TWINNED', ['QUICKENED'])).toBe(false);
  });
});
