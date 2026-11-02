import type { Schemas } from '../api/schema';

/** One entry of a participant's activeConditions, which arrives as a JSON string. */
export interface ConditionEntry {
  name: string;
  duration: number | null;
  appliedRound: number;
  sourceSpellName?: string;
  sourceParticipantId?: string;
  sourceRequiresConcentration?: boolean;
}

export interface SpellSlotLevel {
  max: number;
  remaining: number;
}

/** A participant's spellSlotsCurrent, which arrives as a JSON string. */
export type SpellSlots = Record<string, SpellSlotLevel>;

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

/** A single resource pool entry (matches backend ResourcePoolEntry record). */
export interface ResourcePoolEntry {
  poolId: string;
  displayName: string;
  sourceType: string;
  sourceName: string;
  maxUses: number;
  maxUsesFormula?: string;
  currentUses: number;
  resetOn: string;
  resetAmount?: string;
  resetCheck?: string;
  spendActionType?: string;
  icon?: string;
  metadata?: Record<string, unknown>;
}

export type EncounterCreateRequest = Schemas['EncounterCreateRequest'];

export type AddParticipantRequest = Schemas['AddParticipantRequest'];

export type SetInitiativeRequest = Schemas['SetInitiativeRequest'];

export type BulkInitiativeRequest = Schemas['BulkInitiativeRequest'];
