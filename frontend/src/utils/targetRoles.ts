// What each target listed in a cast is to the spell (D199, D200), as the server reads the order of a cast's targetIds:
// the spell's own targets first and the creatures the caster picks after them, or one dart per entry for a spell of
// darts, a target listed again taking another.
import type { SpellOption } from '../types/combat';

type Roles = SpellOption['targetRoles'];

/** How the target step lists a spell's targets; null for a spell whose targets are all alike. */
export type TargetRoleMode =
  /**
   * The first `main` chosen are the spell's own targets, as Ice Knife's (XGE p.157); the rest are creatures picked
   * within `pickedWithin` feet, of the main target or, with no main target, of the creature the caster's next attack is
   * against, as Hail of Thorns' (PHB p.249).
   */
  | { kind: 'split'; main: number; pickedWithin: number | null }
  /** `count` darts to share out among the targets, as Magic Missile's (PHB p.257). */
  | { kind: 'darts'; count: number };

/**
 * The roles for a cast at `castLevel`: the darts at that level, or the spell's own targets, one more with Twinned
 * Spell for a spell with any (PHB p.102), as the server counts them.
 */
export function targetRoleMode(roles: Roles | undefined, castLevel: number | null | undefined,
  twinned: boolean): TargetRoleMode | null {
  if (!roles) return null;
  if (roles.darts) {
    const count = roles.darts[String(castLevel ?? '')] ?? Object.values(roles.darts)[0];
    return count ? { kind: 'darts', count } : null;
  }
  if (roles.primaryTargets == null) return null;
  const main = roles.primaryTargets + (twinned && roles.primaryTargets > 0 ? 1 : 0);
  return { kind: 'split', main, pickedWithin: roles.pickedWithinFeet ?? null };
}

/** The label of the target at `index` among those chosen, for a spell whose targets split. */
export function roleLabel(mode: Extract<TargetRoleMode, { kind: 'split' }>, index: number): string {
  if (index < mode.main) return 'Main target';
  return mode.pickedWithin != null ? `Picked (within ${mode.pickedWithin} ft)` : 'Picked';
}

/** What the creatures picked must be near, for the target step's hint. */
export function pickedHint(mode: Extract<TargetRoleMode, { kind: 'split' }>): string {
  const within = mode.pickedWithin != null ? `within ${mode.pickedWithin} ft of ` : 'near ';
  if (mode.main === 0) {
    return `Pick the creatures ${within}the creature your next attack is against.`;
  }
  const first = mode.main === 1 ? 'The first creature chosen is the main target' : `The first ${mode.main} chosen are the main targets`;
  return `${first}; the others are creatures ${within}${mode.main === 1 ? 'it' : 'them'} that the spell also reaches.`;
}

/** Moves a picked creature to the front, so it is the main target and the last main target becomes a pick. */
export function makeMain(selected: string[], id: string): string[] {
  if (!selected.includes(id)) return selected;
  return [id, ...selected.filter(other => other !== id)];
}

/** How many darts each target has, in the order first chosen. */
export function dartCounts(selected: string[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const id of selected) counts.set(id, (counts.get(id) ?? 0) + 1);
  return counts;
}

/** One more dart at the target, while any are left. */
export function addDart(selected: string[], id: string, count: number): string[] {
  return selected.length >= count ? selected : [...selected, id];
}

/** One dart fewer at the target, its last. */
export function removeDart(selected: string[], id: string): string[] {
  const at = selected.lastIndexOf(id);
  return at < 0 ? selected : [...selected.slice(0, at), ...selected.slice(at + 1)];
}

/** The confirm step's line naming each target with its role or darts; null for a spell whose targets are all alike. */
export function describeTargets(mode: TargetRoleMode | null, selected: string[],
  nameOf: (id: string) => string | undefined): string | null {
  if (!mode) return null;
  const name = (id: string) => nameOf(id) ?? 'Unknown';
  if (mode.kind === 'darts') {
    return [...dartCounts(selected)]
      .map(([id, darts]) => `${name(id)} (${darts} ${darts === 1 ? 'dart' : 'darts'})`)
      .join(', ') || 'None';
  }
  const main = selected.slice(0, mode.main).map(name);
  const picked = selected.slice(mode.main).map(name);
  const parts: string[] = [];
  if (main.length > 0) parts.push(`${main.join(', ')} (main)`);
  if (picked.length > 0) {
    parts.push(`picked${mode.pickedWithin != null ? ` within ${mode.pickedWithin} ft` : ''}: ${picked.join(', ')}`);
  }
  return parts.join('; ') || 'None';
}

