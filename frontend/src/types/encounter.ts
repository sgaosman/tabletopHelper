export type EncounterStatus = 'PREPARING' | 'ACTIVE' | 'PAUSED' | 'COMPLETED';
export type ParticipantType = 'PLAYER' | 'MONSTER' | 'COMPANION';

export interface EncounterParticipant {
  id: string;
  participantType: ParticipantType;
  characterId?: string;
  monsterId?: string;
  displayName: string;
  initiative?: number;
  initiativeModifier?: number;
  sortOrder?: number;
  hpMax: number;
  hpCurrent: number;
  hpTemp: number;
  armourClass: number;
  activeConditions?: string;
  concentrationSpell?: string;
  isVisibleToPlayers: boolean;
  isAlive: boolean;
  isCurrentTurn: boolean;
  controlledByUserId?: string;
  deathSaveSuccesses: number;
  deathSaveFailures: number;
  notes?: string;
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
