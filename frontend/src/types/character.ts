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

/** The spells one class has prepared, which replace those it had prepared. */
export type PreparedSpellsRequest = Schemas['PreparedSpellsRequest'];

/**
 * The cantrips, and for a class that knows a set number of spells its spells, that one class knows. A list left out
 * keeps what the class knows of that kind. Interim, until Pending Choices (D159).
 */
export type KnownSpellsRequest = Schemas['KnownSpellsRequest'];

/** Spells to copy into a wizard's spellbook, or to take out of it. */
export type SpellbookRequest = Schemas['SpellbookRequest'];

/**
 * A character. Its JSON fields, such as spellsKnown and features, arrive as structured JSON in the shapes below
 * (D168). createdAt and updatedAt are null in the response that creates it. inLiveEncounter is true while it is in an
 * Active or Paused encounter, when rests and levelling wait for the encounter to end (D166).
 */
export type PlayerCharacter = Schemas['CharacterResponse'];

export type LevelUpResponse = Schemas['LevelUpResponse'];

/** What the player still has to choose for the level just gained. */
export type PendingChoices = Schemas['PendingChoices'];

export type ApplyChoicesRequest = Schemas['ApplyChoicesRequest'];

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
