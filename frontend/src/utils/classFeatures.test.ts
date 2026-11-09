import { describe, it, expect } from 'vitest';
import { makeMockParticipant } from '../test/mocks/handlers';
import type { WeaponHit } from '../types/combat';
import type { EncounterParticipant, TurnEconomy } from '../types/encounter';
import { followableHit, kiLeft, KI_POOL, monkStrikeBlock, smiteDice, smiteSlots, sneakAttackUsed } from './classFeatures';

const FRESH: TurnEconomy = {
  actions: 1, actionsUsed: 0, attackActionTaken: false, attacksLeft: 0, bonusActionUsed: false,
  reactionUsed: false, objectInteractionUsed: false, bonusActionSpellCast: false, otherSpellCast: false,
  dashed: false, disengaged: false,
};

const HIT: WeaponHit = {
  turn: '2:part-aria', attackLogId: 'log-1', targetId: 'part-goblin', weapon: 'Longsword', critical: false,
  divineSmiteOffered: true, stunningStrikeOffered: false, smitten: false, stunningStrikeUsed: false,
};

function monk(economy: Partial<TurnEconomy>, ki = 5): EncounterParticipant {
  return makeMockParticipant('part-kai', 'Kai', 'PLAYER', {
    isCurrentTurn: true,
    turnEconomy: { ...FRESH, ...economy },
    resourcePoolsCurrent: [{
      poolId: KI_POOL, displayName: 'Ki Points', sourceType: 'CLASS', currentUses: ki, maxUses: 5,
      spendActionType: 'FREE',
    }],
  });
}

describe('classFeatures: weapon attack riders (M17)', () => {
  it('follows a hit only on the turn it was made', () => {
    const aria = makeMockParticipant('part-aria', 'Aria', 'PLAYER', { turnEconomy: { ...FRESH, lastMeleeHit: HIT } });

    expect(followableHit(aria, '2:part-aria')).toEqual(HIT);
    expect(followableHit(aria, '2:part-goblin')).toBeNull();
    expect(followableHit(aria, null)).toBeNull();
  });

  it('knows Sneak Attack was dealt on this turn, any creature\'s (PHB p.96)', () => {
    const sly = makeMockParticipant('part-sly', 'Sly', 'PLAYER', { turnEconomy: { ...FRESH, sneakAttackTurn: '1:part-goblin' } });

    expect(sneakAttackUsed(sly, '1:part-goblin')).toBe(true);
    expect(sneakAttackUsed(sly, '1:part-sly')).toBe(false);
  });

  it('lists the slots Divine Smite can spend, lowest first, and its dice doubled on a critical hit (PHB p.85)', () => {
    const aria = makeMockParticipant('part-aria', 'Aria', 'PLAYER', {
      spellSlotsCurrent: { 2: { max: 2, used: 0 }, 1: { max: 4, used: 4 }, pact_1: { max: 1, used: 0 } },
    });

    expect(smiteSlots(aria)).toEqual([
      { key: 'pact_1', level: 1, pact: true, remaining: 1 },
      { key: '2', level: 2, pact: false, remaining: 2 },
    ]);
    expect(smiteDice(1, false)).toBe('2d8');
    expect(smiteDice(4, false)).toBe('5d8');
    expect(smiteDice(9, true)).toBe('10d8');
  });

  it('says why a monk\'s bonus-action strike can\'t be made yet (PHB p.78)', () => {
    expect(monkStrikeBlock(monk({}), 'MARTIAL_ARTS')).toMatch(/follows the Attack action/);
    expect(monkStrikeBlock(monk({ attackActionTaken: true, martialArtsAttack: true }), 'MARTIAL_ARTS')).toBeNull();
    expect(monkStrikeBlock(monk({}), 'FLURRY_OF_BLOWS')).toBe('Flurry of Blows follows the Attack action this turn');
    expect(monkStrikeBlock(monk({ attackActionTaken: true }, 0), 'FLURRY_OF_BLOWS')).toBe('No ki points left');
    expect(monkStrikeBlock(monk({ attackActionTaken: true, bonusActionUsed: true, flurryStrikesLeft: 1 }),
      'FLURRY_OF_BLOWS')).toBeNull();
    expect(monkStrikeBlock(monk({ attackActionTaken: true, bonusActionUsed: true }), 'FLURRY_OF_BLOWS'))
      .toBe('Bonus action already used this turn');
    expect(kiLeft(monk({}, 3))).toBe(3);
    expect(kiLeft(makeMockParticipant('part-x', 'X', 'PLAYER'))).toBeNull();
  });
});
