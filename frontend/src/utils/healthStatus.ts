import type { EncounterParticipant, HealthStatus } from '../types/encounter';

const HEALTH_STATUS_LABELS: Record<HealthStatus, string> = {
  UNHARMED: 'Unharmed',
  INJURED: 'Injured',
  BLOODIED: 'Bloodied',
  DOWN: 'Down',
};

export function healthStatusLabel(status: HealthStatus): string {
  return HEALTH_STATUS_LABELS[status];
}

/**
 * How a participant's health reads in the player's initiative list and the spell target pickers
 * (D157): exact HP when the view carries it, otherwise the Health Status the Player View sends for
 * a monster instead. Null when the view has neither.
 */
export function healthLabel(p: Pick<EncounterParticipant, 'hpCurrent' | 'hpMax' | 'healthStatus'>): string | null {
  if (p.hpCurrent != null && p.hpMax != null) return `${p.hpCurrent}/${p.hpMax} HP`;
  if (p.healthStatus) return healthStatusLabel(p.healthStatus);
  return null;
}
