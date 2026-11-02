import type { Schemas } from '../api/schema';

/** One of a participant's activeConditions. */
export type ConditionEntry = Schemas['ConditionEntry'];

/**
 * The slots of one spell level in a participant's spellSlotsCurrent, which keys them "1" to "9", and pact
 * slots "pact_N" (D168).
 */
export type SpellSlot = Schemas['SpellSlot'];

/** One of a participant's spellsKnown: a player character's spells as they joined, or a monster's. */
export type KnownSpell = Schemas['KnownSpell'];

/** One resource pool of a participant, or of a character sheet, where it still arrives as a JSON string. */
export type ResourcePoolEntry = Schemas['ResourcePoolEntry'];

/** Which view of a live encounter a page receives: the DM View or the Player View (D157). */
export type EncounterView = 'dm' | 'player';

/**
 * A participant in the DM View or the Player View. In the Player View a hidden monster is left out, and
 * a monster's HP is null with healthStatus set instead, unless the DM shows exact monster HP (D157).
 */
export type EncounterParticipant = Schemas['ParticipantResponse'];

export type Encounter = Schemas['EncounterResponse'];

export type EncounterStatus = Encounter['status'];
export type ParticipantType = EncounterParticipant['participantType'];

/** How a monster's HP reads to players while the DM hides exact numbers (D157). */
export type HealthStatus = NonNullable<EncounterParticipant['healthStatus']>;

export type EncounterCreateRequest = Schemas['EncounterCreateRequest'];

export type AddParticipantRequest = Schemas['AddParticipantRequest'];

export type SetInitiativeRequest = Schemas['SetInitiativeRequest'];

export type BulkInitiativeRequest = Schemas['BulkInitiativeRequest'];
