import type { Schemas } from '../api/schema';

export type CharacterCreateRequest = Schemas['CharacterCreateRequest'];

/**
 * The details a character's owner sets, which no rule reads (D158). A field left out keeps its value, and an empty
 * text clears it.
 */
export type CharacterDetailsRequest = Schemas['CharacterDetailsRequest'];

/** The campaign a character plays in; null takes it out of its campaign. */
export type CharacterCampaignRequest = Schemas['CharacterCampaignRequest'];

/** A character's whole inventory and coins, which replace those it has. */
export type InventoryRequest = Schemas['InventoryRequest'];

/** Damage, healing or temporary hit points on the sheet between encounters. */
export type HitPointChangeRequest = Schemas['HitPointChangeRequest'];

export type HitPointChange = HitPointChangeRequest['kind'];

/** Uses of a resource pool to spend or recover, one unless an amount is given. */
export type PoolUseRequest = Schemas['PoolUseRequest'];

/** The Hit Dice spent on a short rest, each with the roll entered, or none for the server to roll it. */
export type ShortRestRequest = Schemas['ShortRestRequest'];

export type HitDieSpent = Schemas['HitDieSpent'];

/** A short rest taken: the rested character, and what each Hit Die spent rolled, in the order they were sent. */
export type ShortRestResponse = Schemas['ShortRestResponse'];

/**
 * One Hit Die a short rest spent: its roll, entered by the player or rolled by the server, and the hit points it heals,
 * the roll plus the CON modifier. Hit points stop at the maximum, so the dice can heal more than the character regains.
 */
export type HitDieRoll = Schemas['HitDieRoll'];

/** The spells one class has prepared, which replace those it had prepared. */
export type PreparedSpellsRequest = Schemas['PreparedSpellsRequest'];

/** Spells to copy into a wizard's spellbook, or to take out of it. */
export type SpellbookRequest = Schemas['SpellbookRequest'];

/**
 * A character. Its JSON fields, such as spellsKnown and features, arrive as structured JSON in the shapes below
 * (D168). createdAt and updatedAt are null in the response that creates it. inLiveEncounter is true while it is in an
 * Active or Paused encounter, when rests and levelling wait for the encounter to end (D166). pendingChoices are the
 * choices its levels owe that the player hasn't made yet (D159).
 */
export type PlayerCharacter = Schemas['CharacterResponse'];

/** A level gained: the character, and the names of the features the level brought. */
export type LevelUpResponse = Schemas['LevelUpResponse'];

/**
 * A Pending Choice (D159): a choice one of the character's levels owes, such as an Ability Score Improvement or a
 * subclass, which the player makes on the sheet or leaves for later. count says how many to choose, and options the
 * fixed list it is chosen from, where it has one.
 */
export type PendingChoice = Schemas['PendingChoice'];

export type PendingChoiceType = PendingChoice['type'];

/** Makes one Pending Choice, named by its ID, with only the answer its type takes. */
export type ApplyChoicesRequest = Schemas['ApplyChoicesRequest'];

/** A known caster's swap of one spell it knows for another, as it gains a level. */
export type SpellSwap = Schemas['SpellSwap'];

export type AsiChoice = Schemas['AsiChoice'];

export type AbilityIncrease = Schemas['AbilityIncrease'];

/** A class the character could take its next level in, and whether it meets the multiclass prerequisites. */
export type EligibleClassResponse = Schemas['EligibleClassResponse'];

/** A class, subclass, race, background or feat feature on the sheet. */
export type Feature = Schemas['Feature'];

/** An inventory entry. */
export type InventoryItem = Schemas['InventoryItem'];

export type Currency = Schemas['Currency'];

/** One of the character's classes and its level. */
export type ClassEntry = Schemas['ClassEntry'];

/** A character's Hit Dice from one class, which the sheet keys by class name. */
export type HitDicePool = Schemas['HitDicePool'];

/**
 * What one of a character's classes casts with, worked out by the server from the class's own table (D167): its
 * ability, save DC and attack bonus, the highest level of spell it can learn or prepare, and its cantrips, spells
 * known or spells prepared.
 */
export type ClassSpellcasting = Schemas['ClassSpellcasting'];

/** The highest each ability score can be increased to: 20, or 24 for STR and CON after Primal Champion. */
export type AbilityScoreMaximums = Schemas['AbilityScoreMaximums'];

/**
 * The ability scores without the DM's adjustments, which an ability score improvement raises up to the maximums. The
 * character's own scores include the adjustments.
 */
export type BaseAbilityScores = Schemas['BaseAbilityScores'];

/**
 * A DM Adjustment (D158): a change the DM of the character's campaign makes on top of what the rules give it, with
 * the reason its player sees. A removed one, with removedAt set, stays as the record and counts for nothing.
 */
export type DmAdjustment = Schemas['DmAdjustment'];

/** What an adjustment changes: a stat by an amount, a damage defence, or a feat granted. */
export type DmAdjustmentKind = DmAdjustment['kind'];

/** A resistance, immunity or vulnerability to a damage type (D161). */
export type DamageDefence = NonNullable<DmAdjustment['defence']>;

/** A DM Adjustment to add; each kind needs its own fields, and the reason is required. */
export type DmAdjustmentRequest = Schemas['DmAdjustmentRequest'];
