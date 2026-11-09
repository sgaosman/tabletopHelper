/**
 * Exhaustion's six levels (PHB p.291, D183), each adding its effect to those of the levels below; the server applies
 * them, and these name them for the chips, the sheet and the DM's control.
 */
export const EXHAUSTION_EFFECTS = [
  'Disadvantage on ability checks',
  'Speed halved',
  'Disadvantage on attack rolls and saving throws',
  'Hit point maximum halved',
  'Speed reduced to 0',
  'Death',
] as const;

/** The highest level: death. */
export const MAX_EXHAUSTION = 6;

/** What a level does, its own effect and those below, lowest first; empty for 0 or none. */
export function exhaustionEffects(level: number | null | undefined): string[] {
  const kept = Math.max(0, Math.min(MAX_EXHAUSTION, level ?? 0));
  return EXHAUSTION_EFFECTS.slice(0, kept);
}

/** The chip's label, "Exhausted (3)"; empty for 0 or none. */
export function exhaustionLabel(level: number | null | undefined): string {
  return level && level > 0 ? `Exhausted (${Math.min(MAX_EXHAUSTION, level)})` : '';
}
