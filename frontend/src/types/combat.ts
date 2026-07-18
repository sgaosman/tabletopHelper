export interface CombatLogEntry {
  id: string;
  roundNumber: number;
  actorId?: string;
  actorName?: string;
  targetId?: string;
  targetName?: string;
  actionType: string;
  description: string;
  rollValue?: number;
  rollTotal?: number;
  damageDealt?: number;
  healingDone?: number;
  createdAt: string;
}
