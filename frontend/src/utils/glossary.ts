import type { GlossaryCategory, GlossaryEntry } from '../types/reference';

/** How the glossary dialog names each kind of term. */
export const CATEGORY_LABELS: Record<GlossaryCategory, string> = {
  CONDITION: 'Condition',
  ACTION: 'Action',
  SENSE: 'Sense',
  SKILL: 'Skill',
  DAMAGE_TYPE: 'Damage type',
  COMBAT: 'Combat',
  RULE: 'Rule',
  SPELLCASTING: 'Spellcasting',
};

/**
 * Terms outside the conditions and senses that are linked wherever a description mentions them, in any case. Other
 * terms are linked only where the bold patterns already pick them out, so everyday words such as "help" or "nature"
 * stay plain.
 */
const LINKED_IN_ANY_CASE = new Set([
  'concentration', 'surprised', 'opportunity-attack', 'two-weapon-fighting', 'critical-hit', 'temporary-hit-points',
  'damage-resistance', 'death-saving-throw', 'difficult-terrain', 'passive-perception', 'initiative',
]);

/** The way a bold phrase is looked up: lower case, single spaces, without a leading "an", "your" and the like. */
export function termKey(text: string): string {
  const key = text.trim().toLowerCase().replace(/\s+/g, ' ').replace(/^(?:an?|the|your|one|as an?) /, '');
  // "DC 15" is the Difficulty Class
  return /^dc \d+$/.test(key) ? 'dc' : key;
}

/** Each entry by its name and aliases in lower case. */
export function buildTermIndex(entries: readonly GlossaryEntry[]): Map<string, GlossaryEntry> {
  const index = new Map<string, GlossaryEntry>();
  for (const entry of entries) {
    for (const spelling of [entry.name, ...entry.aliases]) {
      const key = termKey(spelling);
      if (!index.has(key)) index.set(key, entry);
    }
  }
  return index;
}

/** The entry a bold phrase names, trying the singular of a plural ("saving throws"); undefined when none does. */
export function findTerm(index: ReadonlyMap<string, GlossaryEntry>, text: string): GlossaryEntry | undefined {
  const key = termKey(text);
  return index.get(key) ?? (key.endsWith('s') ? index.get(key.slice(0, -1)) : undefined);
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** A pattern matching any of the words, longest first so "temporary hit points" wins over "hit points". */
function anyOf(words: string[], suffix: string, flags: string): RegExp | null {
  const unique = [...new Set(words)].filter(w => w.length > 0).sort((a, b) => b.length - a.length);
  return unique.length > 0 ? new RegExp(`\\b(?:${unique.map(escapeRegExp).join('|')})${suffix}`, flags) : null;
}

/**
 * The patterns that find glossary terms in running text, beyond what the bold patterns find:
 * - conditions, senses and the few terms in LINKED_IN_ANY_CASE, by name or alias in any case ("knocked prone")
 * - actions by name, capitalised, before "action" ("the Dash action")
 * - damage types before "damage" ("fire damage"), so "cold" or "force" alone stay plain
 * - skills by name, capitalised ("Wisdom (Perception)"), so "nature" in a sentence stays plain
 */
export function glossaryPatterns(entries: readonly GlossaryEntry[]): RegExp[] {
  const spellings = (filter: (e: GlossaryEntry) => boolean) =>
    entries.filter(filter).flatMap(e => [e.name, ...e.aliases]);
  const names = (category: GlossaryCategory) => entries.filter(e => e.category === category).map(e => e.name);

  return [
    anyOf(spellings(e => e.category === 'CONDITION' || e.category === 'SENSE' || LINKED_IN_ANY_CASE.has(e.id)), '\\b', 'gi'),
    anyOf(names('ACTION'), '(?= action\\b)', 'g'),
    anyOf(names('DAMAGE_TYPE'), '(?= damage\\b)', 'gi'),
    anyOf(names('SKILL'), '\\b', 'g'),
  ].filter((p): p is RegExp => p !== null);
}
