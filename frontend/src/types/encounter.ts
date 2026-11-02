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

export type SpellSlots = Record<string, SpellSlotLevel>;

export type EncounterStatus = 'PREPARING' | 'ACTIVE' | 'PAUSED' | 'COMPLETED';
export type ParticipantType = 'PLAYER' | 'MONSTER' | 'COMPANION';

/** Which view of a live encounter a page receives: the DM View or the Player View (D157). */
export type EncounterView = 'dm' | 'player';

/** How a monster's HP reads to players while the DM hides exact numbers (D157). */
export type HealthStatus = 'UNHARMED' | 'INJURED' | 'BLOODIED' | 'DOWN';

export interface EncounterParticipant {
  id: string;
  participantType: ParticipantType;
  characterId?: string;
  monsterId?: string;
  displayName: string;
  initiative?: number;
  initiativeModifier?: number;
  sortOrder?: number;
  /** HP is null for a monster in the Player View unless the DM shows exact monster HP; healthStatus is set instead. */
  hpMax: number | null;
  hpCurrent: number | null;
  hpTemp: number | null;
  healthStatus?: HealthStatus | null;
  armourClass: number;
  activeConditions?: string;
  concentrationSpell?: string;
  concentrationSlotLevel?: number;
  activeSpell?: string;
  activeSpellSlotLevel?: number;
  spellSlotsCurrent?: string;
  isVisibleToPlayers: boolean;
  isAlive: boolean;
  isCurrentTurn: boolean;
  controlledByUserId?: string;
  deathSaveSuccesses: number;
  deathSaveFailures: number;
  notes?: string;
  spellAttackBonus?: number;
  spellSaveDc?: number;
  spellcastingAbility?: string;
  spellsKnown?: string;
  resourcePoolsCurrent?: string;
  actionUsed?: boolean;
  bonusActionUsed?: boolean;
  reactionUsed?: boolean;
}

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

export interface Encounter {
  id: string;
  campaignId: string;
  campaignName: string;
  name: string;
  description?: string;
  status: EncounterStatus;
  currentTurnIndex: number;
  roundNumber: number;
  sessionCode?: string;
  participants: EncounterParticipant[];
  createdAt: string;
}

export interface EncounterCreateRequest {
  campaignId: string;
  name: string;
  description?: string;
}

export interface AddParticipantRequest {
  participantType: ParticipantType;
  characterId?: string;
  monsterId?: string;
  displayName: string;
  initiativeModifier?: number;
  hpMax?: number;
  armourClass?: number;
  controlledByUserId?: string;
  quantity?: number;
}

export interface SetInitiativeRequest {
  participantId: string;
  initiative: number;
}

export interface BulkInitiativeRequest {
  initiatives: SetInitiativeRequest[];
}
