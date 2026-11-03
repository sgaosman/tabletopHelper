import type { Schemas } from '../api/schema';

/** One of a participant's activeConditions. */
export type ConditionEntry = Schemas['ConditionEntry'];

/**
 * The save a condition's creature repeats at the end of each of its turns to end it (D162). awaitingRoll is
 * set when the campaign leaves a player character's roll to its player.
 */
export type RepeatSave = Schemas['RepeatSave'];

/**
 * The slots of one spell level in a participant's spellSlotsCurrent or a sheet's spellSlots, which key them "1" to "9",
 * and pact slots "pact_N" (D168).
 */
export type SpellSlot = Schemas['SpellSlot'];

/** One of a participant's spellsKnown: a player character's spells as they joined, or a monster's. */
export type KnownSpell = Schemas['KnownSpell'];

/** One resource pool of a participant or of a character sheet (D168). */
export type ResourcePoolEntry = Schemas['ResourcePoolEntry'];

/** Which view of a live encounter a page receives: the DM View or the Player View (D157). */
export type EncounterView = 'dm' | 'player';

/**
 * A participant in the DM View or the Player View. In the Player View a hidden monster is left out, and
 * a monster's HP is null with healthStatus set instead, unless the DM shows exact monster HP (D157).
 */
export type EncounterParticipant = Schemas['ParticipantResponse'];

export type Encounter = Schemas['EncounterResponse'];

/**
 * One turn in an encounter's computed order (D163): a participant's, or the Lair's on initiative 20. The
 * encounter's currentTurnIndex points into its turnOrder, and each participant's sortOrder is its turn's
 * place there. The Player View leaves out turns players can't see and numbers the rest again (D157).
 */
export type TurnEntry = Schemas['TurnResponse'];

export type EncounterStatus = Encounter['status'];
export type ParticipantType = EncounterParticipant['participantType'];

/** How a monster's HP reads to players while the DM hides exact numbers (D157). */
export type HealthStatus = NonNullable<EncounterParticipant['healthStatus']>;

export type EncounterCreateRequest = Schemas['EncounterCreateRequest'];

export type AddParticipantRequest = Schemas['AddParticipantRequest'];

export type SetInitiativeRequest = Schemas['SetInitiativeRequest'];

export type BulkInitiativeRequest = Schemas['BulkInitiativeRequest'];

/** Every participant of a tie on initiative and DEX, in the order the DM wants them to act (D163). */
export type TieOrderRequest = Schemas['TieOrderRequest'];
