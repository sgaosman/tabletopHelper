import type { CharacterClassRef, Subclass } from '../../../types/reference';
import { ABILITIES, ABILITY_FROM_ABBR, safeJsonParse } from '../../../utils/dndRules';

export const ALL_STEPS = ['Basic Info', 'Race', 'Ability Scores', 'Class', 'Background', 'Spells', 'Review'] as const;

export const ALIGNMENTS = [
  'Lawful Good', 'Neutral Good', 'Chaotic Good',
  'Lawful Neutral', 'True Neutral', 'Chaotic Neutral',
  'Lawful Evil', 'Neutral Evil', 'Chaotic Evil',
];

export const STANDARD_ARRAY = [15, 14, 13, 12, 10, 8];

export const STANDARD_LANGUAGES = [
  'Common', 'Dwarvish', 'Elvish', 'Giant', 'Gnomish', 'Goblin', 'Halfling', 'Orc',
];

export const EXOTIC_LANGUAGES = [
  'Abyssal', 'Celestial', 'Deep Speech', 'Draconic', 'Infernal', 'Primordial', 'Sylvan', 'Undercommon',
];

export const ALL_LANGUAGES = [...STANDARD_LANGUAGES, ...EXOTIC_LANGUAGES];

export const ARTISANS_TOOLS = [
  "Alchemist's Supplies", "Brewer's Supplies", "Calligrapher's Supplies",
  "Carpenter's Tools", "Cartographer's Tools", "Cobbler's Tools",
  "Cook's Utensils", "Glassblower's Tools", "Jeweler's Tools",
  "Leatherworker's Tools", "Mason's Tools", "Painter's Supplies",
  "Potter's Tools", "Smith's Tools", "Tinker's Tools",
  "Weaver's Tools", "Woodcarver's Tools",
];

export const MUSICAL_INSTRUMENTS = [
  'Bagpipes', 'Drum', 'Dulcimer', 'Flute', 'Lute', 'Lyre',
  'Horn', 'Pan Flute', 'Shawm', 'Viol',
];

export const ALL_TOOLS = [
  ...ARTISANS_TOOLS,
  'Disguise Kit', 'Forgery Kit', 'Herbalism Kit',
  "Navigator's Tools", "Poisoner's Kit", "Thieves' Tools",
  ...MUSICAL_INSTRUMENTS,
  'Dice Set', 'Dragonchess Set', 'Playing Card Set', "Three-Dragon Ante Set",
];

export const GAMING_SETS = [
  'Dice Set', 'Dragonchess Set', 'Playing Card Set', "Three-Dragon Ante Set",
];

export type AbilityScores = Record<typeof ABILITIES[number], number>;

export type ProfEntry = string | { choose: { from?: string[]; count?: number } } | { anyStandard: number } | { any: number } | { chooseSet: ProfEntry[][] };

export interface ClassEntry {
  cls: CharacterClassRef;
  level: number;
  subclass: Subclass | null;
  subclasses: Subclass[];
}

export function getToolAnyOptions(entry: string): string[] | null {
  if (entry === 'Any Gaming Set') return GAMING_SETS;
  if (entry === "Any Artisan's Tool") return ARTISANS_TOOLS;
  if (entry === 'Any Musical Instrument') return MUSICAL_INSTRUMENTS;
  return null;
}

export function expandToolFrom(from: string[]): string[] {
  const result: string[] = [];
  for (const item of from) {
    if (item === 'AnyArtisansTool') result.push(...ARTISANS_TOOLS);
    else if (item === 'Musical instrument') result.push(...MUSICAL_INSTRUMENTS);
    else if (item === 'Gaming set') result.push(...GAMING_SETS);
    else result.push(item);
  }
  return result;
}

export function formatProfEntry(p: ProfEntry): string {
  if (typeof p === 'string') return p;
  if ('any' in p && !('anyStandard' in p) && !('chooseSet' in p)) {
    const n = (p as { any: number }).any;
    return `Any ${n}`;
  }
  if ('choose' in p && !('chooseSet' in p)) {
    const c = (p as { choose: { from?: string[]; count?: number } }).choose;
    const count = c.count ?? 1;
    if (c.from && c.from.length > 0) return `Choose ${count} from ${c.from.join(', ')}`;
    return `Choose ${count}`;
  }
  if ('anyStandard' in p) {
    const n = (p as { anyStandard: number }).anyStandard;
    return `Any ${n} language${n > 1 ? 's' : ''}`;
  }
  return '';
}

export function formatProficiencies(raw: string | null): string[] {
  if (!raw) return [];
  const parsed = safeJsonParse<ProfEntry[]>(raw, []);
  return parsed.flatMap(p => {
    if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
      return ['Choose one set from below'];
    }
    const text = formatProfEntry(p);
    return text ? [text] : [];
  });
}

export function hasChooseSet(raw: string | null): ProfEntry[][] | null {
  if (!raw) return null;
  const parsed = safeJsonParse<ProfEntry[]>(raw, []);
  for (const p of parsed) {
    if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
      return (p as { chooseSet: ProfEntry[][] }).chooseSet;
    }
  }
  return null;
}

const STANDARD_ASI_LEVELS = new Set([4, 8, 12, 16, 19]);
const FIGHTER_ASI_LEVELS = new Set([4, 6, 8, 12, 14, 16, 19]);
const ROGUE_ASI_LEVELS = new Set([4, 8, 10, 12, 16, 19]);

export function isAsiLevel(className: string, classLevel: number): boolean {
  if (className === 'Fighter') return FIGHTER_ASI_LEVELS.has(classLevel);
  if (className === 'Rogue') return ROGUE_ASI_LEVELS.has(classLevel);
  return STANDARD_ASI_LEVELS.has(classLevel);
}

export function countAsiLevels(entries: ClassEntry[]): number {
  let count = 0;
  for (const entry of entries) {
    for (let lvl = 1; lvl <= entry.level; lvl++) {
      if (isAsiLevel(entry.cls.name, lvl)) count++;
    }
  }
  return count;
}

export function checkMulticlassEligibility(
  cls: CharacterClassRef,
  scores: AbilityScores
): { eligible: boolean; reason: string } {
  if (!cls.multiclassRequirements) return { eligible: true, reason: '' };
  const reqs = safeJsonParse<Array<{ ability: string; minimum: number; operator?: string }>>(
    cls.multiclassRequirements, []
  );
  if (reqs.length === 0) return { eligible: true, reason: '' };

  const isOr = reqs.some(r => r.operator === 'OR');
  const results = reqs.map(r => {
    const abilityKey = ABILITY_FROM_ABBR[r.ability] as keyof AbilityScores | undefined;
    const score = abilityKey ? scores[abilityKey] : 0;
    return { ...r, score, met: score >= r.minimum };
  });

  if (isOr) {
    const eligible = results.some(r => r.met);
    const reason = results.map(r => `${r.ability} ${r.score}/${r.minimum}`).join(' or ');
    return { eligible, reason: `Requires ${reason}` };
  }
  const eligible = results.every(r => r.met);
  const reason = results.map(r => `${r.ability} ${r.score}/${r.minimum}`).join(' and ');
  return { eligible, reason: `Requires ${reason}` };
}
