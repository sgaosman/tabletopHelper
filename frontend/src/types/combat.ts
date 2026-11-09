import type { Schemas } from '../api/schema';

/**
 * An attack (D164): a weapon from the attacker's attack options, whose numbers the server works out, with any Manual
 * Modifiers added; or an attack bonus and damage entered by hand, which is itself a Manual Modifier.
 */
export type AttackRollRequest = Schemas['AttackRollRequest'];

/**
 * A weapon a player character can attack with, with the attack bonus and damage the server works out, and Great
 * Weapon Master's or Sharpshooter's trade when the character may take it (D164).
 */
export type AttackOption = Schemas['AttackOption'];

/**
 * What the attacker's and the target's conditions and senses do to an attack roll between them now (PHB appendix A):
 * each source of advantage or disadvantage with its reason, such as "target restrained", and what they come to. The
 * attacker's own choice still combines with them when it attacks (PHB p.173).
 */
export type AttackRollPreview = Schemas['AttackRollPreview'];

/**
 * How an attacker holds its weapons for an attack (M17): one-handed, two-handed (a two-handed weapon always, a
 * versatile one for its larger die) or a weapon in each hand. Dueling needs one-handed and Great Weapon Fighting
 * two-handed (PHB p.72, p.147).
 */
export type AttackGrip = NonNullable<AttackRollRequest['grip']>;

/**
 * A participant's latest melee weapon hit, kept in its turn economy (M17), which Divine Smite (PHB p.85) and Stunning
 * Strike (PHB p.79) may follow on the same turn, once each.
 */
export type WeaponHit = Schemas['WeaponHit'];

/** Divine Smite on the paladin's latest melee weapon hit this turn, spending a spell slot (PHB p.85, M17). */
export type DivineSmiteRequest = Schemas['DivineSmiteRequest'];

/** Stunning Strike on the monk's latest melee weapon hit this turn, spending 1 ki (PHB p.79, M17). */
export type StunningStrikeRequest = Schemas['StunningStrikeRequest'];
export type DeftStrikeRequest = Schemas['DeftStrikeRequest'];

/**
 * What the holder of a Bardic Inspiration die does with it (PHB p.53, M17): keep it for its next failed save, keep it
 * for an attack or a roll by hand, or roll it now for a check or save the DM resolves.
 */
export type BardicInspirationRequest = Schemas['BardicInspirationRequest'];

/** The Protection fighting style's reaction for a creature within 5 feet, logged for the DM (PHB p.72, M17). */
export type ProtectionRequest = Schemas['ProtectionRequest'];

export type CastSpellRequest = Schemas['CastSpellRequest'];

/** A Metamagic option put on a cast (PHB p.102, M18), with the creatures Careful or Heightened Spell names. */
export type MetamagicChoice = Schemas['MetamagicChoice'];

/** A Metamagic option the caster knows, with its cost for a spell and why it can't change it (M18). */
export type MetamagicOption = Schemas['MetamagicOption'];

/** The PHB's eight Metamagic options (PHB p.102). */
export type MetamagicName = MetamagicOption['option'];

/** A sorcerer's Flexible Casting at the table, a bonus action (PHB p.101, M18). */
export type FontOfMagicUseRequest = Schemas['FontOfMagicUseRequest'];

export type TargetOutcome = Schemas['TargetOutcome'];

/** The result of a spell, with the encounter state it left: the caller's view (D157). */
export type CastSpellResponse = Schemas['CastSpellResponse'];

export type RepeatSpellEffectRequest = Schemas['RepeatSpellEffectRequest'];

/** Uses of a participant's resource pool spent at the table, one when uses is left out (D174). */
export type UsePoolRequest = Schemas['UsePoolRequest'];

/**
 * Dash, Disengage, Dodge, Help, Hide, Ready, Search or Use an Object on the participant's turn, costing an action, or a
 * bonus action where Cunning Action or Nimble Escape grants it (D174).
 */
export type StandardActionRequest = Schemas['StandardActionRequest'];

/** An object interaction on the participant's turn: the first each turn is free (D174). */
export type ObjectInteractionRequest = Schemas['ObjectInteractionRequest'];

/** The DM's edit of a participant's whole turn economy (D174). */
export type TurnEconomyRequest = Schemas['TurnEconomyRequest'];

/**
 * A combat log entry. The players' log leaves out entries about participants the DM hides, and its
 * entries have no Legendary Resistance fields (D157). Each entry since M14 names the combat command that wrote it
 * (commandId); the DM's log keeps the entries of commands the DM undid, marked undone, and the players' log leaves
 * them out.
 */
export type CombatLogEntry = Schemas['CombatLogResponse'];

/** The DM's undo of the newest combat commands, refused unless expectedCommandId is still the newest (M14). */
export type UndoRequest = Schemas['UndoRequest'];

/** What the DM's Undo would take back: the newest command and those before it that one undo may take (M14). */
export type UndoInfo = Schemas['UndoInfo'];

/** A combat command the DM may undo, with what it did and how many log entries it wrote (M14). */
export type UndoableCommand = Schemas['UndoableCommand'];

// ── M12 Monster Action Types ──────────────────────────────────────

export type MonsterActionRequest = Schemas['MonsterActionRequest'];

export type MonsterActionTargetResult = Schemas['TargetResult'];

export type MonsterActionResponse = Schemas['MonsterActionResponse'];

/**
 * A monster's multiattack and the attacks it makes, each taking its own targets (D165). One the server can't read
 * from the stat block has no components and a manualReason.
 */
export type MultiattackOption = Schemas['MultiattackOption'];

/** One attack of a multiattack; maxTargets is null for any number. */
export type MultiattackComponent = Schemas['ComponentOption'];

/** What pays for a cast spell: a spell slot, a pact slot, an innate use, or nothing (D165). */
export type SpellSource = Schemas['SourceOption']['source'];

/** A spell a participant can cast now, with each Spell Source that can pay for it (D164, D165). */
export type SpellOption = Schemas['SpellOption'];

export type SpellSourceOption = Schemas['SourceOption'];

export interface MonsterActionTemplate {
  actions: MonsterAction[];
  legendaryActions?: MonsterAction[];
  lairActions?: MonsterAction[];
  spellcasting?: MonsterSpellcasting;
  bonusActions?: MonsterAction[];
  reactions?: MonsterAction[];
  traits?: MonsterTrait[];
  legendaryActionCount?: number;
  legendaryResistanceCount?: number;
  hasLairActions?: boolean;
}

export interface MonsterAction {
  name: string;
  actionType?: string;
  deliveryMethod?: string;
  attackType?: string;
  attackBonus?: number;
  reach?: number;
  range?: number;
  targetCount?: number;
  targetType?: string;
  aoeSize?: number;
  saveDC?: number;
  saveAbility?: string;
  halfOnSave?: boolean;
  effects?: MonsterEffect[];
  isMultiattack?: boolean;
  multiattackDescription?: string;
  multiattackComponents?: string[];
  automatable?: boolean;
  recharge?: string;
  description?: string;
  cost?: number;
  referencesAction?: string;
  /** What the DM applies by hand when it is used, such as a Parry's AC against a hit already rolled (D174). */
  dmNote?: string;
  /**
   * Limited uses, such as "(1/Day)" or "(Recharges after a Short or Long Rest)" (MM p.11), as the server reads them: the
   * pool each use spends and its full count, which a monster that hasn't used the action yet doesn't hold.
   */
  limitedUses?: { poolId: string; maxUses: number };
}

export interface MonsterEffect {
  effectType: string;
  damageDice?: string;
  damageType?: string;
  condition?: string;
  durationRounds?: number;
  durationText?: string;
  saveToEndEachTurn?: boolean;
  saveToEndDC?: number;
  saveToEndAbility?: string;
}

export interface MonsterSpellcasting {
  ability?: string;
  saveDC?: number;
  attackBonus?: number;
  innateSpells?: boolean;
  name?: string;
  slots?: Record<string, number>;
  spellsByLevel?: Record<string, string[]>;
  dailySpells?: Record<string, string[]>;
  atWillSpells?: string[];
}

export interface MonsterTrait {
  name: string;
  description: string;
  combatRelevant?: boolean;
  mechanicType?: string;
}

/**
 * The beasts a druid can become with Wild Shape now, by the Beast Shapes limits of its level, with Circle Forms for a
 * Circle of the Moon druid (PHB p.66, p.69). Whether the druid has seen the beast is the DM's call.
 */
export type WildShapeOptions = Schemas['WildShapeOptions'];

/** A beast Wild Shape can turn the druid into; its monsterId is the use-pool request's beastId. */
export type WildShapeBeast = Schemas['Beast'];

/** A Circle of the Moon druid's Combat Wild Shape healing: a spell slot for 1d8 HP per level (PHB p.69). */
export type WildShapeHealRequest = Schemas['WildShapeHealRequest'];
