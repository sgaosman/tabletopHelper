import type { Schemas } from '../api/schema';

export type CharacterCreateRequest = Schemas['CharacterCreateRequest'];

/** A field left out, or null, keeps its value; a JSON field given replaces the whole value. */
export type CharacterUpdateRequest = Schemas['CharacterUpdateRequest'];

/**
 * A character. Its JSON fields, such as spellsKnown and features, arrive as structured JSON in the shapes below
 * (D168). createdAt and updatedAt are null in the response that creates it.
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

/** One entry of a character's featResources, a feat's limited uses. */
export type FeatResource = Schemas['FeatResourceEntry'];
