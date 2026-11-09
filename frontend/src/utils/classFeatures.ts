import type { ConditionEntry, EncounterParticipant, ResourcePoolEntry } from '../types/encounter';
import type { UsePoolRequest } from '../types/combat';
import { poolMetadata } from './turnEconomy';

/**
 * The class features a pool's use does more than spend (M17), as the server names them in the pool's metadata.effect:
 * Rage, Second Wind, Lay on Hands and Ki. The server decides what each use does; this only words and offers it.
 */
export type PoolEffect = 'rage' | 'second-wind' | 'lay-on-hands' | 'ki';

/** What a pool use sends besides the participant and the pool: its uses, option and target. */
export type PoolUseOptions = Omit<UsePoolRequest, 'participantId' | 'poolId'>;

/** The entries a feature leaves among a creature's conditions while it is in force (M17, D174). */
export const RAGING = 'raging';
export const RECKLESS = 'reckless';

/** What Rage gives while it lasts (PHB p.48). */
export const RAGE_TEXT = '+Rage Damage on melee weapon attacks using Strength, resistance to bludgeoning, piercing '
  + 'and slashing damage, advantage on Strength checks and saving throws; no spellcasting or concentration. Ends early '
  + 'if a turn ends without attacking or taking damage since the last, or when knocked unconscious.';

/** What Reckless Attack does until the start of the barbarian's next turn (PHB p.48). */
export const RECKLESS_TEXT = 'Advantage on melee weapon attacks using Strength this turn; attacks against it have '
  + 'advantage until its next turn.';

/** The feature a pool's use sets off, or null for a pool that only spends. */
export function poolEffect(pool: Pick<ResourcePoolEntry, 'metadata'>): PoolEffect | null {
  const effect = poolMetadata(pool).effect;
  return effect === 'rage' || effect === 'second-wind' || effect === 'lay-on-hands' || effect === 'ki' ? effect : null;
}

function hasCondition(participant: EncounterParticipant, name: string): boolean {
  return (participant.activeConditions ?? []).some(c => c.name.trim().toLowerCase() === name);
}

/** Whether the participant rages (PHB p.48). */
export function isRaging(participant: EncounterParticipant): boolean {
  return hasCondition(participant, RAGING);
}

/** Whether the participant attacks recklessly this turn, or did on its last (PHB p.48). */
export function isReckless(participant: EncounterParticipant): boolean {
  return hasCondition(participant, RECKLESS);
}

/**
 * How a feature's entry among the conditions reads, with what it does, or null for an ordinary condition: "Raging" and
 * "Reckless" are a class feature's state, not conditions of appendix A.
 */
export function featureMarker(condition: Pick<ConditionEntry, 'name'>): { label: string; description: string } | null {
  switch (condition.name.trim().toLowerCase()) {
    case RAGING: return { label: 'Raging', description: RAGE_TEXT };
    case RECKLESS: return { label: 'Reckless', description: RECKLESS_TEXT };
    default: return null;
  }
}

/** Ki's options that take an action as a bonus action for 1 ki point (PHB p.78). */
export const KI_OPTIONS: { option: string; label: string; description: string }[] = [
  { option: 'patient-defense', label: 'Patient Defense', description: 'Dodge as a bonus action' },
  { option: 'step-of-the-wind-dash', label: 'Step of the Wind: Dash', description: 'Dash as a bonus action, jump doubled' },
  {
    option: 'step-of-the-wind-disengage', label: 'Step of the Wind: Disengage',
    description: 'Disengage as a bonus action, jump doubled',
  },
];

/** What a use of the feature's pool does, shown under its cost. */
export function poolEffectText(effect: PoolEffect | null): string | null {
  switch (effect) {
    case 'rage': return 'rage for 1 minute';
    case 'second-wind': return 'regain 1d10 + fighter level HP';
    case 'lay-on-hands': return 'restore HP from the pool by touch, or 5 points to cure a disease or poison';
    case 'ki': return 'Patient Defense or Step of the Wind for 1 ki as a bonus action';
    default: return null;
  }
}
