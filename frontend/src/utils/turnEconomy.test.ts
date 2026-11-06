import { describe, it, expect } from 'vitest';
import { makeMockCondition, makeMockParticipant } from '../test/mocks/handlers';
import type { EncounterParticipant, TurnEconomy } from '../types/encounter';
import { chargeBlock, costPhrase, spellCastBlock, speedText } from './turnEconomy';

const FRESH: TurnEconomy = {
  actions: 1, actionsUsed: 0, attackActionTaken: false, attacksLeft: 0, bonusActionUsed: false,
  reactionUsed: false, objectInteractionUsed: false, bonusActionSpellCast: false, otherSpellCast: false,
  dashed: false, disengaged: false,
};

/** A player character on its own turn unless told otherwise, with this much of its turn spent. */
function caster(economy: Partial<TurnEconomy> = {}, overrides: Partial<EncounterParticipant> = {}) {
  return makeMockParticipant('part-cleric', 'Brother Tuck', 'PLAYER', {
    isCurrentTurn: true, turnEconomy: { ...FRESH, ...economy }, ...overrides,
  });
}

describe('chargeBlock', () => {
  it('lets a fresh turn pay for anything', () => {
    for (const cost of ['ACTION', 'BONUS_ACTION', 'REACTION'] as const) {
      expect(chargeBlock(caster(), { cost })).toBeNull();
    }
  });

  it('refuses a spent action, bonus action or reaction', () => {
    expect(chargeBlock(caster({ actionsUsed: 1 }), { cost: 'ACTION' })).toBe('No action left this turn');
    expect(chargeBlock(caster({ bonusActionUsed: true }), { cost: 'BONUS_ACTION' })).toBe('Bonus action already used this turn');
    expect(chargeBlock(caster({ reactionUsed: true }), { cost: 'REACTION' })).toMatch(/^Reaction already used/);
  });

  it('lets an attack continue an open Attack action, and asks for another action once it is spent (PHB p.72)', () => {
    expect(chargeBlock(caster({ actionsUsed: 1, attackActionTaken: true, attacksLeft: 1 }), { cost: 'ACTION', attack: true })).toBeNull();
    expect(chargeBlock(caster({ actionsUsed: 1, attackActionTaken: true, attacksLeft: 0 }), { cost: 'ACTION', attack: true }))
      .toBe('No attacks left in the Attack action and no action left this turn');
    // Action Surge's second action opens another Attack action
    expect(chargeBlock(caster({ actions: 2, actionsUsed: 1, attackActionTaken: true }), { cost: 'ACTION', attack: true })).toBeNull();
  });

  it('needs the Attack action before an off-hand attack (PHB p.195)', () => {
    expect(chargeBlock(caster(), { cost: 'BONUS_ACTION', attack: true })).toBe('An off-hand attack needs the Attack action first this turn');
    expect(chargeBlock(caster({ attackActionTaken: true, actionsUsed: 1 }), { cost: 'BONUS_ACTION', attack: true })).toBeNull();
  });

  it('allows only a reaction outside the creature\'s own turn (PHB p.190)', () => {
    const offTurn = caster({}, { isCurrentTurn: false });
    expect(chargeBlock(offTurn, { cost: 'REACTION' })).toBeNull();
    expect(chargeBlock(offTurn, { cost: 'ACTION' })).toBe("Not this creature's turn: only a reaction can be taken");
    expect(chargeBlock(offTurn, { cost: 'BONUS_ACTION' })).toBe("Not this creature's turn: only a reaction can be taken");
  });

  it('refuses an incapacitated creature anything but what costs nothing (PHB p.290)', () => {
    const stunned = caster({}, { activeConditions: [makeMockCondition('stunned')] });
    expect(chargeBlock(stunned, { cost: 'REACTION' })).toBe('Stunned, so no actions or reactions');
    expect(chargeBlock(stunned, { cost: 'NONE' })).toBeNull();
  });

  it('says nothing for a participant without a turn economy, as a monster in the Player View', () => {
    expect(chargeBlock(caster({}, { turnEconomy: null }), { cost: 'ACTION' })).toBeNull();
  });
});

describe('spellCastBlock and the bonus-action-spell rule (PHB p.202)', () => {
  it('after a bonus-action spell allows only a cantrip with a casting time of 1 action', () => {
    const afterHealingWord = caster({ bonusActionUsed: true, bonusActionSpellCast: true });
    expect(spellCastBlock(afterHealingWord, 'ACTION', 0, { dm: false })).toBeNull();
    expect(spellCastBlock(afterHealingWord, 'ACTION', 1, { dm: false }))
      .toBe('A bonus-action spell was cast this turn, so only a cantrip with a casting time of 1 action can follow');
  });

  it('after a levelled spell rules out a bonus-action spell, in the other order too', () => {
    const afterGuidingBolt = caster({ bonusActionUsed: false, otherSpellCast: true });
    expect(spellCastBlock(afterGuidingBolt, 'BONUS_ACTION', 1, { dm: false }))
      .toBe('A spell other than a 1-action cantrip was cast this turn, so no spell can be cast as a bonus action');
    // A 1-action cantrip doesn't set the flag, so the server leaves otherSpellCast false after one
    expect(spellCastBlock(caster({ actionsUsed: 1 }), 'BONUS_ACTION', 1, { dm: false })).toBeNull();
  });

  it('checks the rule only on the caster\'s own turn', () => {
    const offTurn = caster({ bonusActionSpellCast: true }, { isCurrentTurn: false });
    expect(spellCastBlock(offTurn, 'REACTION', 1, { dm: false })).toBeNull();
  });

  it('refuses a player a spell longer than a turn, and charges the DM an action for one', () => {
    expect(spellCastBlock(caster(), 'LONGER', 1, { dm: false }))
      .toBe("Its casting time is longer than a turn, so it can't be cast during combat");
    expect(spellCastBlock(caster(), 'LONGER', 1, { dm: true })).toBeNull();
    expect(spellCastBlock(caster({ actionsUsed: 1 }), 'LONGER', 1, { dm: true })).toBe('No action left this turn');
  });
});

describe('speedText', () => {
  it('shows walking first, then the other speeds, and the Dash distance after the Dash action', () => {
    expect(speedText({ fly: 60, walk: 30, swim: 0 }, false)).toEqual({ speeds: ['30 ft', 'fly 60 ft'], dash: null });
    expect(speedText({ walk: 30 }, true)).toEqual({ speeds: ['30 ft'], dash: '60 ft' });
    expect(speedText(undefined, false)).toEqual({ speeds: [], dash: null });
  });
});

describe('costPhrase', () => {
  it('words each cost as a sentence ends', () => {
    expect(costPhrase('ACTION')).toBe('an action');
    expect(costPhrase('BONUS_ACTION')).toBe('a bonus action');
    expect(costPhrase('NONE')).toBe('nothing from the turn');
  });
});
