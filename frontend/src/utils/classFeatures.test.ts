import { describe, it, expect } from 'vitest';
import { makeMockCondition, makeMockParticipant } from '../test/mocks/handlers';
import type { WeaponHit } from '../types/combat';
import type { EncounterParticipant, ResourcePoolEntry, TurnEconomy } from '../types/encounter';
import {
  arcaneRecoveryBudget, arcaneRecoveryLevels, channelOptions, CLERIC_CHANNEL_DIVINITY, featureMarker, followableHit,
  guidedStrikeBlock, heldInspiration, kiLeft, KI_POOL, monkStrikeBlock, poolEffect, recoverableSlots, smiteDice,
  smiteSlots, sneakAttackUsed,
} from './classFeatures';

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

describe('classFeatures: Channel Divinity, Bardic Inspiration and Arcane Recovery (M17)', () => {
  const pool = (poolId: string, currentUses: number, effect?: string) => ({
    poolId, displayName: poolId, sourceType: 'CLASS', currentUses, maxUses: 1, spendActionType: 'ACTION',
    metadata: (effect ? { effect } : null) as unknown as ResourcePoolEntry['metadata'],
  });

  it('reads the new pool effects', () => {
    expect(poolEffect(pool('class:cleric-channel-divinity', 1, 'channel-divinity'))).toBe('channel-divinity');
    expect(poolEffect(pool('class:bard-bardic-inspiration', 1, 'bardic-inspiration'))).toBe('bardic-inspiration');
    expect(poolEffect(pool('class:wizard-arcane-recovery', 1, 'arcane-recovery'))).toBe('arcane-recovery');
    expect(poolEffect(pool('class:x', 1, 'unknown'))).toBeNull();
  });

  it("offers a cleric's options from the cleric's pool and a paladin's from the paladin's", () => {
    const both = { channelDivinityOptions: ['turn-undead', 'preserve-life', 'sacred-weapon', 'path-to-the-grave'] };
    expect(channelOptions(both, { poolId: 'class:cleric-channel-divinity' }).map(o => o.id))
      .toEqual(['turn-undead', 'preserve-life']);
    expect(channelOptions(both, { poolId: 'class:paladin-channel-divinity' }).map(o => o.id))
      .toEqual(['sacred-weapon']);
    expect(channelOptions({ channelDivinityOptions: null }, { poolId: 'class:cleric-channel-divinity' })).toEqual([]);
  });

  it('finds a held die and words it as a feature, not a condition', () => {
    const inspired = makeMockCondition('inspired', { heldDie: { sides: 10, onFailedSave: true } });
    expect(heldInspiration({ activeConditions: [makeMockCondition('prone'), inspired] })).toBe(inspired);
    expect(heldInspiration({ activeConditions: null })).toBeNull();
    expect(featureMarker(inspired)?.label).toBe('Inspired (d10)');
    expect(featureMarker(inspired)?.description).toMatch(/Kept for the next failed save/);
    expect(featureMarker({ name: 'vow of enmity' })?.label).toBe('Vow of Enmity');
    // Sacred Weapon names the one weapon it imbues (PHB p.86), and an entry from before it was recorded names none
    expect(featureMarker({ name: 'sacred weapon', weapon: 'Longsword' })?.label).toBe('Sacred Weapon: Longsword');
    expect(featureMarker({ name: 'sacred weapon', weapon: 'Longsword' })?.description)
      .toMatch(/attack rolls with the Longsword/);
    expect(featureMarker({ name: 'sacred weapon' })?.label).toBe('Sacred Weapon');
  });

  it('offers Guided Strike only to a War cleric with Channel Divinity left (PHB p.63)', () => {
    const warCleric = (uses: number) => ({
      channelDivinityOptions: ['turn-undead', 'guided-strike'],
      resourcePoolsCurrent: [pool(CLERIC_CHANNEL_DIVINITY, uses)],
    });
    expect(guidedStrikeBlock(warCleric(1))).toBeNull();
    expect(guidedStrikeBlock(warCleric(0))).toBe('No Channel Divinity left');
    expect(guidedStrikeBlock({ channelDivinityOptions: ['turn-undead'], resourcePoolsCurrent: [] }))
      .toBe('Not a War Domain cleric');
  });

  it('works out what Arcane Recovery can bring back (PHB p.115)', () => {
    expect(arcaneRecoveryBudget(1)).toBe(1);
    expect(arcaneRecoveryBudget(5)).toBe(3);
    expect(arcaneRecoveryBudget(20)).toBe(10);
    expect(recoverableSlots({ 1: { max: 4, used: 2 }, 2: { max: 3, used: 0 }, 6: { max: 1, used: 1 },
      pact_1: { max: 1, used: 1 } })).toEqual({ 1: 2 });
    expect(arcaneRecoveryLevels({ 2: 1, 1: 2 })).toEqual([1, 1, 2]);
  });
});
