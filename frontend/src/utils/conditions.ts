import type { RepeatSave } from '../types/encounter';

/** The 14 conditions of the 2014 rules, as the server names them. */
export const ALL_CONDITIONS = [
  'blinded', 'charmed', 'deafened', 'frightened', 'grappled',
  'incapacitated', 'invisible', 'paralyzed', 'petrified',
  'poisoned', 'prone', 'restrained', 'stunned', 'unconscious',
] as const;

/** The 13 damage types of the 2014 rules, as the server names them. */
export const DAMAGE_TYPES = [
  'bludgeoning', 'piercing', 'slashing', 'acid', 'cold', 'fire',
  'force', 'lightning', 'necrotic', 'poison', 'psychic', 'radiant', 'thunder',
] as const;

/**
 * What a creature's action is spent on to end its condition (D188, D199, D200): an ability check, with its skill, as
 * against Wrathful Smite's fear ("WIS check") or Phantasmal Force's phantasm ("INT (Investigation) check"), else a save
 * ("WIS save").
 */
export function repeatRollName(save: RepeatSave): string {
  if (save.skill) return `${save.ability} (${save.skill}) check`;
  return save.check ? `${save.ability} check` : `${save.ability} save`;
}
