import type { ConditionEntry, EncounterParticipant, ResourcePoolEntry } from '../types/encounter';
import type { UsePoolRequest, WeaponHit } from '../types/combat';
import { chargeBlock, poolMetadata } from './turnEconomy';

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

// ── Weapon attack riders (M17 workstream B) ─────────────────────────────

/** The pool whose points Flurry of Blows and Stunning Strike spend (PHB p.78-79). */
export const KI_POOL = 'class:monk-ki';

/** What Sneak Attack asks of an attack (PHB p.96). */
export const SNEAK_ATTACK_TEXT = 'Once per turn, on a hit with a finesse or ranged weapon: with advantage, or when '
  + 'another enemy of the target is within 5 ft of it and not incapacitated, and never with disadvantage.';

/** The ki points a participant has left, or null when it has no ki pool. */
export function kiLeft(participant: Pick<EncounterParticipant, 'resourcePoolsCurrent'>): number | null {
  const ki = (participant.resourcePoolsCurrent ?? []).find(pool => pool.poolId === KI_POOL);
  if (!ki) return null;
  return ki.currentUses;
}

/** Whether the participant has already dealt Sneak Attack damage on the turn the encounter is on (PHB p.96). */
export function sneakAttackUsed(participant: EncounterParticipant, turnStamp: string | null | undefined): boolean {
  const used = participant.turnEconomy?.sneakAttackTurn;
  return !!used && !!turnStamp && used === turnStamp;
}

/**
 * The participant's latest melee weapon hit when it was made on the turn the encounter is on, which is when Divine
 * Smite and Stunning Strike may follow it (PHB p.85, p.79); null otherwise.
 */
export function followableHit(participant: EncounterParticipant, turnStamp: string | null | undefined): WeaponHit | null {
  const hit = participant.turnEconomy?.lastMeleeHit;
  return hit && turnStamp && hit.turn === turnStamp ? hit : null;
}

/** A spell slot Divine Smite can spend: a spell slot or a pact slot with one left. */
export interface SmiteSlot {
  key: string;
  level: number;
  pact: boolean;
  remaining: number;
}

/** The participant's slots with one left, lowest level first, spell slots before pact slots of the same level. */
export function smiteSlots(participant: Pick<EncounterParticipant, 'spellSlotsCurrent'>): SmiteSlot[] {
  return Object.entries(participant.spellSlotsCurrent ?? {})
    .map(([key, slot]) => {
      const pact = key.startsWith('pact_');
      return { key, level: Number(pact ? key.slice(5) : key), pact, remaining: slot.max - slot.used };
    })
    .filter(slot => Number.isInteger(slot.level) && slot.level >= 1 && slot.level <= 9 && slot.remaining > 0)
    .sort((a, b) => a.level - b.level || Number(a.pact) - Number(b.pact));
}

/**
 * Divine Smite's dice for a slot level (PHB p.85): 2d8, and 1d8 for each level above 1st to 5d8, doubled on a critical
 * hit. An undead or a fiend takes 1d8 more, which the server adds.
 */
export function smiteDice(level: number, critical: boolean): string {
  const dice = Math.min(5, level + 1);
  return `${critical ? dice * 2 : dice}d8`;
}

/**
 * Why a monk's bonus-action unarmed strike can't be made now, or null (PHB p.78), mirroring the server: Martial Arts'
 * strike follows the Attack action taken with an unarmed strike or a monk weapon; Flurry of Blows' first strike
 * follows the Attack action and spends 1 ki and the bonus action, and its second costs nothing more. The server stays
 * the authority.
 */
export function monkStrikeBlock(attacker: EncounterParticipant, cost: 'MARTIAL_ARTS' | 'FLURRY_OF_BLOWS'): string | null {
  const economy = attacker.turnEconomy;
  if (!economy) return null;
  if (!attacker.isCurrentTurn) return "Not this creature's turn: only a reaction can be taken";
  if (cost === 'FLURRY_OF_BLOWS' && (economy.flurryStrikesLeft ?? 0) > 0) return null;
  const budget = chargeBlock(attacker, { cost: 'BONUS_ACTION' });
  if (budget) return budget;
  if (cost === 'MARTIAL_ARTS') {
    return economy.martialArtsAttack ? null
      : 'Martial Arts\' bonus strike follows the Attack action taken with an unarmed strike or a monk weapon';
  }
  if (!economy.attackActionTaken) return 'Flurry of Blows follows the Attack action this turn';
  return (kiLeft(attacker) ?? 0) < 1 ? 'No ki points left' : null;
}
