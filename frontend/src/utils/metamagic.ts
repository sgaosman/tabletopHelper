import type { ResourcePoolEntry, SpellSlot } from '../types/encounter';
import type { MetamagicName, MetamagicOption } from '../types/combat';

/**
 * A sorcerer's Font of Magic and Metamagic (PHB p.101–102, M18) as the pages offer them. The server decides every
 * exchange and cast; these only word, count and pre-check them.
 */

/** The sorcerer's Sorcery Points pool, as resource-pools.json names it. */
export const SORCERY_POINTS = 'class:sorcerer-sorcery-points';

/** Flexible Casting creates spell slots no higher than 5th level (PHB p.101). */
export const MAX_CREATED_SLOT_LEVEL = 5;

// The Creating Spell Slots table (PHB p.101), by slot level from the 1st
const CREATION_COST = [2, 3, 5, 6, 7];

/** The sorcery points a slot of this level costs to create, or null above the 5th level. */
export function creationCost(level: number): number | null {
  return level >= 1 && level <= MAX_CREATED_SLOT_LEVEL ? CREATION_COST[level - 1] : null;
}

/** Every slot of a level: those the levels give and those Flexible Casting created, which a long rest ends. */
export function slotTotal(slot: SpellSlot): number {
  return slot.max + (slot.created ?? 0);
}

/** The slots of a level left to spend. */
export function slotsLeft(slot: SpellSlot): number {
  return slotTotal(slot) - slot.used;
}

/** The Sorcery Points pool among a creature's pools, if it has one. */
export function sorceryPoints(pools: ResourcePoolEntry[] | null | undefined): ResourcePoolEntry | null {
  return (pools ?? []).find(pool => pool.poolId === SORCERY_POINTS) ?? null;
}

/** The levels of the regular and pact slots with one left to expend for points, lowest first. */
export function expendableLevels(slots: Record<string, SpellSlot> | null | undefined): number[] {
  const levels = new Set<number>();
  Object.entries(slots ?? {}).forEach(([key, slot]) => {
    if (slotsLeft(slot) > 0) levels.add(parseInt(key.replace('pact_', '')));
  });
  return [...levels].filter(level => level >= 1).sort((a, b) => a - b);
}

/** What one Metamagic option does, in a line, for its toggle (PHB p.102). */
export const METAMAGIC_TEXT: Record<MetamagicName, string> = {
  CAREFUL: 'Chosen creatures, up to your Charisma modifier, automatically succeed on their saves.',
  DISTANT: 'Double the range, or touch becomes 30 feet. The DM applies the range.',
  EMPOWERED: 'Reroll up to your Charisma modifier of the damage dice that rolled low. Can go with another option.',
  EXTENDED: 'Double the duration, up to 24 hours.',
  HEIGHTENED: 'One target has disadvantage on its first save against the spell.',
  QUICKENED: 'Cast it as a bonus action instead of an action.',
  SUBTLE: 'Cast it without somatic or verbal components.',
  TWINNED: 'Target a second creature with a spell that targets only one.',
};

/** The option's cost for a spell cast at castLevel: Twinned Spell's is that level, 1 for a cantrip. */
export function metamagicCost(option: MetamagicOption, castLevel: number): number {
  return option.costIsCastLevel ? Math.max(1, castLevel) : option.cost;
}

/** Why the option can't change the spell cast at castLevel, or null when it can. */
export function metamagicBlock(option: MetamagicOption, castLevel: number): string | null {
  if (option.ineligibleReason) return option.ineligibleReason;
  if (option.maxCastLevel != null && castLevel > option.maxCastLevel) {
    return `${option.name} needs a spell that can't target more than one creature at the level it is cast at`;
  }
  return null;
}

/** The sorcery points the chosen options cost together for a spell cast at castLevel. */
export function chosenCost(options: MetamagicOption[], chosen: MetamagicName[], castLevel: number): number {
  return options.filter(option => chosen.includes(option.option))
    .reduce((sum, option) => sum + metamagicCost(option, castLevel), 0);
}

/**
 * Whether the option can join those already chosen: one option per spell, except that Empowered Spell can go with
 * another (PHB p.102).
 */
export function metamagicCombines(option: MetamagicName, chosen: MetamagicName[]): boolean {
  if (chosen.includes(option)) return true;
  if (option === 'EMPOWERED') return true;
  return chosen.every(other => other === 'EMPOWERED');
}
