import type { DamagePart } from '../types/combat';

/**
 * A hit of more than one damage type as each part, "12 slashing + 4 lightning" (D207's quick result), from the
 * server's damageParts. Null when there is only one part, or when the parts don't add up to the damage taken (a
 * floor such as Harm's or Death Ward changed it), so the caller shows the total as before.
 */
export function damageBreakdown(total: number, parts: DamagePart[] | null | undefined): string | null {
  const taken = (parts ?? []).filter(part => part.amount > 0);
  if (taken.length < 2 || taken.reduce((sum, part) => sum + part.amount, 0) !== total) {
    return null;
  }
  return taken.map(part => (part.type ? `${part.amount} ${part.type}` : `${part.amount}`)).join(' + ');
}
