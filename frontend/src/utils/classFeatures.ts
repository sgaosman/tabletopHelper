import type { ConditionEntry, EncounterParticipant, ResourcePoolEntry, WildShapeForm } from '../types/encounter';
import type { UsePoolRequest, WeaponHit } from '../types/combat';
import { chargeBlock, poolMetadata, type EconomyCost } from './turnEconomy';

/**
 * The class features a pool's use does more than spend (M17), as the server names them in the pool's metadata.effect:
 * Rage, Second Wind, Lay on Hands, Ki, Channel Divinity, Bardic Inspiration, Arcane Recovery (used only on a short
 * rest) and Wild Shape; and a hydra's Heads (D206), which the DM changes as heads die or regrow. The server decides
 * what each use does; this only words and offers it.
 */
export type PoolEffect = 'rage' | 'second-wind' | 'lay-on-hands' | 'ki' | 'channel-divinity' | 'bardic-inspiration'
  | 'arcane-recovery' | 'wild-shape' | 'heads';

const POOL_EFFECTS: PoolEffect[] = ['rage', 'second-wind', 'lay-on-hands', 'ki', 'channel-divinity',
  'bardic-inspiration', 'arcane-recovery', 'wild-shape', 'heads'];

/** What a pool use sends besides the participant and the pool: its uses, option and target. */
export type PoolUseOptions = Omit<UsePoolRequest, 'participantId' | 'poolId'>;

/** The entries a feature leaves among a creature's conditions while it is in force (M17, D174). */
export const RAGING = 'raging';
export const RECKLESS = 'reckless';

/** What Rage gives while it lasts (PHB p.48). */
export const RAGE_TEXT = '+Rage Damage on melee weapon attacks using Strength, resistance to bludgeoning, piercing '
  + 'and slashing damage, advantage on Strength checks and saving throws; no spellcasting or concentration. Ends early '
  + 'if a turn ends without attacking or taking damage since the last, or when knocked unconscious.';

/** The entry Wild Shape leaves among the druid's conditions while it lasts, holding the beast form (PHB p.66). */
export const WILD_SHAPE = 'wild shape';

/** What Wild Shape changes while it lasts (PHB p.67). */
export const WILD_SHAPE_TEXT = "The beast's HP, AC, speeds, Strength, Dexterity and Constitution, with your own "
  + 'Intelligence, Wisdom and Charisma; no spellcasting, though concentration continues. At 0 HP you revert, and '
  + 'damage left over carries to your normal form.';

/** The beast form the participant has taken with Wild Shape, or null. */
export function wildShapeOf(participant: Pick<EncounterParticipant, 'wildShape'>): WildShapeForm | null {
  return participant.wildShape ?? null;
}

/** What Reckless Attack does until the start of the barbarian's next turn (PHB p.48). */
export const RECKLESS_TEXT = 'Advantage on melee weapon attacks using Strength this turn; attacks against it have '
  + 'advantage until its next turn.';

/** The Wild Shape pool's ID, which names its effect where a pool copied before the effect was added doesn't. */
export const WILD_SHAPE_POOL = 'class:druid-wild-shape';

/** The feature a pool's use sets off, or null for a pool that only spends. */
export function poolEffect(pool: Pick<ResourcePoolEntry, 'metadata'> & Partial<Pick<ResourcePoolEntry, 'poolId'>>):
  PoolEffect | null {
  const effect = poolMetadata(pool).effect;
  return POOL_EFFECTS.find(known => known === effect) ?? (pool.poolId === WILD_SHAPE_POOL ? 'wild-shape' : null);
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
export function featureMarker(condition: Pick<ConditionEntry, 'name'>
  & Partial<Pick<ConditionEntry, 'heldDie' | 'wildShape' | 'weapon' | 'bonus'>>):
  { label: string; description: string } | null {
  switch (condition.name.trim().toLowerCase()) {
    case RAGING: return { label: 'Raging', description: RAGE_TEXT };
    case WILD_SHAPE: return {
      label: `Wild Shape${condition.wildShape ? `: ${condition.wildShape.beastName}` : ''}`,
      description: WILD_SHAPE_TEXT,
    };
    case RECKLESS: return { label: 'Reckless', description: RECKLESS_TEXT };
    case INSPIRED: return {
      label: `Inspired${condition.heldDie ? ` (d${condition.heldDie.sides})` : ''}`,
      description: INSPIRATION_TEXT + (condition.heldDie?.onFailedSave ? ' Kept for the next failed save.' : ''),
    };
    case SACRED_WEAPON: return {
      label: `Sacred Weapon${condition.weapon ? `: ${condition.weapon}` : ''}`,
      description: `Charisma modifier (at least +1) added to attack rolls with ${condition.weapon
        ? `the ${condition.weapon}` : 'the imbued weapon'} for 1 minute; it sheds bright light in a 20-foot radius and `
        + 'is magical (PHB p.86).',
    };
    case VOW_OF_ENMITY: return {
      label: 'Vow of Enmity',
      description: 'The paladin who vowed has advantage on attack rolls against this creature for 1 minute or until it '
        + 'drops to 0 hit points (PHB p.88).',
    };
    case WAR_GODS_BLESSING: return {
      label: "War God's Blessing",
      description: "+10 to this creature's next attack roll (PHB p.63).",
    };
    case AGILE_PARRY: return {
      label: `Agile Parry +${condition.bonus ?? 2} AC`,
      description: `Defending with ${condition.weapon ? `the ${condition.weapon}` : 'a kensei weapon'}: +${
        condition.bonus ?? 2} AC until the start of its next turn, while it isn't incapacitated (XGE p.34).`,
    };
    case KENSEIS_SHOT: return {
      label: "Kensei's Shot",
      description: 'Ranged attacks with a kensei weapon deal an extra 1d4 damage of the weapon\'s type on a hit until '
        + 'the end of this turn (XGE p.34).',
    };
    case SHARPEN_THE_BLADE: return {
      label: `Sharpen the Blade${condition.weapon ? `: ${condition.weapon}` : ''} +${condition.bonus ?? 1}`,
      description: `+${condition.bonus ?? 1} to attack and damage rolls with ${condition.weapon
        ? `the ${condition.weapon}` : 'the sharpened kensei weapon'} for 1 minute, or until used again; none on a magic `
        + 'weapon that already has a bonus (XGE p.35).',
    };
    default: return null;
  }
}

// ── Way of the Kensei (XGE p.34-35) ─────────────────────────────────────

/** The entries a Kensei monk's features leave among its conditions. */
export const AGILE_PARRY = 'agile parry';
export const KENSEIS_SHOT = "kensei's shot";
export const SHARPEN_THE_BLADE = 'sharpen the blade';

/** The Monk levels of Kensei's Shot and Sharpen the Blade (XGE p.34-35). */
export const KENSEIS_SHOT_LEVEL = 3;
export const SHARPEN_THE_BLADE_LEVEL = 11;

/** Deft Strike's extra damage: the martial arts die of the monk's level (PHB p.77), doubled on a critical hit. */
export function deftStrikeDie(monkLevel: number | null | undefined, critical: boolean): string {
  const level = monkLevel ?? 6;
  const sides = level >= 17 ? 10 : level >= 11 ? 8 : level >= 5 ? 6 : 4;
  return `${critical ? 2 : 1}d${sides}`;
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
    case 'channel-divinity': return 'pick an option below, or Use alone for one the app does not model';
    case 'bardic-inspiration': return 'give another creature an inspiration die for 10 minutes';
    case 'arcane-recovery': return 'used when finishing a short rest, on the character sheet';
    case 'wild-shape': return 'become a beast you have seen, for up to half your druid level in hours';
    case 'heads': return 'its multiattack bites once per head; change it as heads die or regrow';
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

// ── Channel Divinity, Bardic Inspiration and Arcane Recovery (M17 workstream C) ──────────────────────

/** The entries Channel Divinity and Bardic Inspiration leave among a creature's conditions (M17). */
export const INSPIRED = 'inspired';
export const SACRED_WEAPON = 'sacred weapon';
export const VOW_OF_ENMITY = 'vow of enmity';
export const WAR_GODS_BLESSING = "war god's blessing";

/** What a held Bardic Inspiration die does (PHB p.53). */
export const INSPIRATION_TEXT = 'Add the die to one ability check, attack roll or saving throw within 10 minutes, '
  + 'after seeing the d20 and before knowing the result.';

/** The pool a cleric's Guided Strike spends (PHB p.63). */
export const CLERIC_CHANNEL_DIVINITY = 'class:cleric-channel-divinity';

/**
 * Whom a Channel Divinity option reaches: no one but the user, one other creature, one creature or the user, any number
 * of creatures it affects, or creatures with hit points split among them (Preserve Life); or it is chosen on the attack.
 */
export type ChannelTargets = 'none' | 'one' | 'one-or-self' | 'many' | 'split' | 'attack';

export interface ChannelOption {
  label: string;
  targets: ChannelTargets;
  cost: EconomyCost;
  /** What it does, and whether the server automates it or only logs it. */
  description: string;
}

/** The PHB's Channel Divinity options the server knows (PHB p.58-63, p.85-88), by the id the use-pool option names. */
export const CHANNEL_DIVINITY_OPTIONS: Record<string, ChannelOption> = {
  'turn-undead': {
    label: 'Turn Undead', targets: 'many', cost: 'ACTION',
    description: 'Each undead picked makes a WIS save or is turned for 1 minute or until damaged; Destroy Undead from '
      + 'cleric 5',
  },
  'preserve-life': {
    label: 'Preserve Life', targets: 'split', cost: 'ACTION',
    description: 'Split 5 × cleric level HP; none above half its maximum; not undead or constructs',
  },
  'radiance-of-the-dawn': {
    label: 'Radiance of the Dawn', targets: 'many', cost: 'ACTION',
    description: 'Hostile creatures picked make a CON save: 2d10 + cleric level radiant, half on a success',
  },
  'charm-animals-and-plants': {
    label: 'Charm Animals and Plants', targets: 'many', cost: 'ACTION',
    description: 'Beasts and plants picked make a WIS save or are charmed for 1 minute or until damaged',
  },
  'destructive-wrath': {
    label: 'Destructive Wrath', targets: 'none', cost: 'NONE',
    description: 'Logged: the DM sets the lightning or thunder damage roll to its maximum',
  },
  'knowledge-of-the-ages': {
    label: 'Knowledge of the Ages', targets: 'none', cost: 'ACTION',
    description: 'Logged: proficiency with a skill or tool for 10 minutes',
  },
  'read-thoughts': {
    label: 'Read Thoughts', targets: 'one', cost: 'ACTION',
    description: 'Logged: the creature makes a WIS save, which the DM rolls and rules',
  },
  'invoke-duplicity': {
    label: 'Invoke Duplicity', targets: 'none', cost: 'ACTION',
    description: 'Logged: an illusory duplicate for 1 minute (concentration)',
  },
  'cloak-of-shadows': {
    label: 'Cloak of Shadows', targets: 'none', cost: 'ACTION',
    description: 'Invisible until the end of your next turn; attacking or casting ends it, removed by hand',
  },
  'guided-strike': {
    label: 'Guided Strike', targets: 'attack', cost: 'NONE',
    description: 'Chosen on the attack form: +10 when the roll would miss',
  },
  'war-gods-blessing': {
    label: "War God's Blessing", targets: 'one-or-self', cost: 'REACTION',
    description: "+10 to the creature's next attack roll",
  },
  'sacred-weapon': {
    label: 'Sacred Weapon', targets: 'none', cost: 'ACTION',
    description: '+CHA modifier (at least +1) to attack rolls with the one weapon imbued, for 1 minute',
  },
  'turn-the-unholy': {
    label: 'Turn the Unholy', targets: 'many', cost: 'ACTION',
    description: 'Each fiend or undead picked makes a WIS save or is turned for 1 minute or until damaged',
  },
  'natures-wrath': {
    label: "Nature's Wrath", targets: 'one', cost: 'ACTION',
    description: 'STR or DEX save (its better) or restrained, repeating the save at the end of each of its turns',
  },
  'turn-the-faithless': {
    label: 'Turn the Faithless', targets: 'many', cost: 'ACTION',
    description: 'Each fey or fiend picked makes a WIS save or is turned for 1 minute or until damaged',
  },
  'abjure-enemy': {
    label: 'Abjure Enemy', targets: 'one', cost: 'ACTION',
    description: 'WIS save (fiends and undead with disadvantage) or frightened, speed 0, for 1 minute or until damaged',
  },
  'vow-of-enmity': {
    label: 'Vow of Enmity', targets: 'one', cost: 'BONUS_ACTION',
    description: 'Advantage on attack rolls against the creature for 1 minute or until it drops to 0 HP',
  },
};

const PALADIN_OPTIONS = ['sacred-weapon', 'turn-the-unholy', 'natures-wrath', 'turn-the-faithless', 'abjure-enemy',
  'vow-of-enmity'];

/** The Channel Divinity options the participant can use from this pool, a cleric's or a paladin's, in the server's order. */
export function channelOptions(participant: Pick<EncounterParticipant, 'channelDivinityOptions'>,
  pool: Pick<ResourcePoolEntry, 'poolId'>): { id: string; option: ChannelOption }[] {
  const paladin = pool.poolId.includes('paladin');
  return (participant.channelDivinityOptions ?? [])
    .filter(id => CHANNEL_DIVINITY_OPTIONS[id] !== undefined && PALADIN_OPTIONS.includes(id) === paladin)
    .map(id => ({ id, option: CHANNEL_DIVINITY_OPTIONS[id] }));
}

/** The Bardic Inspiration die the participant holds, or null (PHB p.53). */
export function heldInspiration(participant: Pick<EncounterParticipant, 'activeConditions'>): ConditionEntry | null {
  return (participant.activeConditions ?? []).find(c => c.name.trim().toLowerCase() === INSPIRED && !!c.heldDie)
    ?? null;
}

/**
 * Why Guided Strike can't be offered on the participant's attack, or null (PHB p.63): it needs the War Domain's option
 * and a Channel Divinity use left.
 */
export function guidedStrikeBlock(
  participant: Pick<EncounterParticipant, 'channelDivinityOptions' | 'resourcePoolsCurrent'>,
): string | null {
  if (!(participant.channelDivinityOptions ?? []).includes('guided-strike')) return 'Not a War Domain cleric';
  const pool = (participant.resourcePoolsCurrent ?? []).find(p => p.poolId === CLERIC_CHANNEL_DIVINITY);
  return pool && pool.currentUses > 0 ? null : 'No Channel Divinity left';
}

/** Arcane Recovery's budget (PHB p.115): slot levels adding up to half the wizard level, rounded up. */
export function arcaneRecoveryBudget(wizardLevel: number): number {
  return Math.ceil(wizardLevel / 2);
}

/** The expended spell slots of 1st to 5th level Arcane Recovery can bring back, by level (PHB p.115). */
export function recoverableSlots(slots: Record<string, { max: number; used: number }> | null | undefined):
  Record<number, number> {
  const expended: Record<number, number> = {};
  for (const [key, slot] of Object.entries(slots ?? {})) {
    const level = Number(key);
    if (Number.isInteger(level) && level >= 1 && level <= 5 && slot.used > 0) expended[level] = slot.used;
  }
  return expended;
}

/** The pool counting Arcane Recovery's one use a day (PHB p.115). */
export const ARCANE_RECOVERY_POOL = 'class:wizard-arcane-recovery';

/** The slots chosen for Arcane Recovery, by level: how many of each. */
export type ArcaneRecoveryChoice = Record<number, number>;

/** One level for each slot chosen, lowest first, as the short rest request sends them. */
export function arcaneRecoveryLevels(choice: ArcaneRecoveryChoice): number[] {
  return Object.entries(choice)
    .flatMap(([level, count]) => Array.from({ length: count }, () => Number(level)))
    .sort((a, b) => a - b);
}

/** The participant's Wild Shape pool's ID, as its pools name it, or the content data's (PHB p.66). */
export function wildShapePoolId(participant: Pick<EncounterParticipant, 'resourcePoolsCurrent'>): string {
  const pool = (participant.resourcePoolsCurrent ?? []).find(entry => poolEffect(entry) === 'wild-shape');
  return pool?.poolId ?? WILD_SHAPE_POOL;
}
