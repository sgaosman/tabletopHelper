import type { Schemas } from '../api/schema';

export type CharacterCreateRequest = Schemas['CharacterCreateRequest'];

/** A field left out, or null, keeps its value. */
export type CharacterUpdateRequest = Schemas['CharacterUpdateRequest'];

/**
 * A character. Its JSON fields, such as spellsKnown and features, arrive as strings; parse them with
 * safeJsonParse (utils/dndRules). createdAt and updatedAt are null in the response that creates it.
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

/** One entry of a character's featResources, which arrives as a JSON string. */
export interface FeatResource {
  featName: string;
  name: string;
  maxUses: number;
  currentUses: number;
  resetOn: string;
}
