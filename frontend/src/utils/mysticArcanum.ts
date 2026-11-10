/**
 * A warlock's Mystic Arcanum (PHB p.108): one warlock spell each of 6th, 7th, 8th and 9th level, chosen at Warlock 11,
 * 13, 15 and 17, known with the source "arcanum:Warlock" and cast once without a slot between long rests from its own
 * pool, as the server keeps it.
 */

/** What the source of an arcanum spell starts with, as the server records it: "arcanum:Warlock". */
export const MYSTIC_ARCANUM_SOURCE = 'arcanum:';

/** A spell level as the rules write it: "1st", "2nd", "3rd", "6th". */
export function spellLevelOrdinal(level: number): string {
  if (level === 1) return '1st';
  if (level === 2) return '2nd';
  if (level === 3) return '3rd';
  return `${level}th`;
}

/** An arcanum of a spell level, as the sheet names it and its pool: "Mystic Arcanum (6th)". */
export function mysticArcanumTitle(level: number): string {
  return `Mystic Arcanum (${spellLevelOrdinal(level)})`;
}

/** The pool that holds an arcanum's one use, on the sheet or a participant: "mystic-arcanum:6". */
export function mysticArcanumPoolId(level: number): string {
  return `mystic-arcanum:${level}`;
}
