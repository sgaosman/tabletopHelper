import type { Schemas } from '../api/schema';

/**
 * The class tables the server's rules use (D167, D169), from GET /api/reference/class-tables: each caster type's
 * spell slots, each class's Cantrips Known and Spells Known columns, its spellbook, the subclasses with tables of
 * their own (the Eldritch Knight and Arcane Trickster), and the proficiency bonus. Every column has an entry for each
 * level from 0 to 20. Read them through useClassTables.
 */
export type ClassTables = Schemas['ClassTablesResponse'];
type Spellcasting = Schemas['SpellcastingResponse'];

// A subclass's own Spellcasting, as an Eldritch Knight's for a fighter, or else the class's
function spellcasting(tables: ClassTables, className: string, subclassName?: string | null): Spellcasting | null {
  const subclass = subclassName ? tables.subclasses[subclassName] : undefined;
  if (subclass && subclass.className === className && subclass.spellcasting) return subclass.spellcasting;
  return tables.classes[className]?.spellcasting ?? null;
}

/** Cantrips known at a class level; 0 for a class without cantrips. */
export function cantripsKnown(tables: ClassTables, className: string, level: number, subclassName?: string | null) {
  return spellcasting(tables, className, subclassName)?.cantripsKnown[level] ?? 0;
}

/** Spells known at a class level, for a class that knows a set number; 0 for one that prepares its spells. */
export function spellsKnown(tables: ClassTables, className: string, level: number, subclassName?: string | null) {
  return spellcasting(tables, className, subclassName)?.spellsKnown[level] ?? 0;
}

/**
 * The highest level of spell a class can learn at a class level: the highest slot on its own table, or a warlock's
 * Pact Magic slot level. 0 for a class without spell slots yet.
 */
export function highestSpellLevel(tables: ClassTables, className: string, level: number,
  subclassName?: string | null) {
  const casting = spellcasting(tables, className, subclassName);
  const row = casting ? tables.spellSlots[casting.casterType]?.[level] ?? [] : [];
  for (let i = row.length - 1; i >= 0; i--) {
    if (row[i] > 0) return i + 1;
  }
  return 0;
}

/** The spells in a class's spellbook at a class level: those of its 1st level and of each level after. */
export function spellbookSize(tables: ClassTables, className: string, level: number) {
  const spellbook = tables.classes[className]?.spellbook;
  if (!spellbook || level <= 0) return 0;
  return spellbook.firstLevel + (level - 1) * spellbook.laterLevels;
}

/** The proficiency bonus at a character level. */
export function proficiencyBonus(tables: ClassTables, level: number) {
  return tables.proficiencyBonus[level] ?? 0;
}

/** Whether a subclass gives its class the third caster's Spellcasting, as the Eldritch Knight does a fighter. */
export function isThirdCasterSubclass(tables: ClassTables, className: string, subclassName?: string | null) {
  const subclass = subclassName ? tables.subclasses[subclassName] : undefined;
  return subclass?.className === className && subclass.spellcasting?.casterType === 'third';
}
