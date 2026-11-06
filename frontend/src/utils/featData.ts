import type { Feat } from '../types/reference';
import { ALL_SKILLS } from './dndRules';

/** A feat's effects as the server types them: the 5etools lists, whose entries are a string or an object, and bonuses. */
export type FeatEffects = NonNullable<Feat['effects']>;

/** One of the spell lists a feat's spells can come from, such as Magic Initiate's classes. */
export type FeatSpellOption = NonNullable<Feat['grantsFeatures']>[number];

/** A spell a feat grants: named, or chosen by a filter such as "level=0|class=Wizard", or from a list. */
type FeatSpellEntry = NonNullable<FeatSpellOption['known']>[string][number];

// An object entry of a 5etools list, or null for a string or anything else
function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' ? value : fallback;
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];
}

// ── Ability score increases ──────────────────────────────────

export interface AbilityIncrease {
  /** Each fixed increase by ability, as the data writes it, such as CON */
  fixed: Record<string, number>;
  /** A choice of an increase to one of the abilities listed */
  choose: { from: string[]; amount: number } | null;
}

/** The feat's ability score increases, null when it gives none. */
export function abilityIncrease(feat: Feat): AbilityIncrease | null {
  const entries = feat.abilityScoreIncrease ?? [];
  if (entries.length === 0) return null;
  const result: AbilityIncrease = { fixed: {}, choose: null };
  for (const entry of entries) {
    if (entry.choose) {
      result.choose = { from: entry.choose.from, amount: entry.choose.amount };
      continue;
    }
    for (const [ability, amount] of Object.entries(entry)) {
      if (typeof amount === 'number') result.fixed[ability] = amount;
    }
  }
  return result;
}

/** The increases in words, such as "+1 CON" or "+1 STR/DEX". */
export function abilityIncreasePreview(feat: Feat): string {
  const increase = abilityIncrease(feat);
  if (!increase) return '';
  const parts = Object.entries(increase.fixed).map(([ability, amount]) => `+${amount} ${ability}`);
  if (increase.choose) parts.push(`+${increase.choose.amount} ${increase.choose.from.join('/')}`);
  return parts.join(', ');
}

// ── The choices a feat's effects ask for ─────────────────────

export interface ChoicesNeeded {
  ability: boolean;
  resistance: boolean;
  skill: number;
  savingThrow: boolean;
  expertise: number;
  tool: number;
  language: number;
  weapon: number;
  optionalFeatures: number;
}

/** How many of each choice the feat asks the player to make, apart from its spells. */
export function choicesNeeded(effects: FeatEffects | null, increase: AbilityIncrease | null): ChoicesNeeded {
  const needed: ChoicesNeeded = {
    ability: !!increase?.choose, resistance: false, skill: 0, savingThrow: false, expertise: 0, tool: 0,
    language: 0, weapon: 0, optionalFeatures: 0,
  };
  if (!effects) return needed;
  const objects = (list: unknown[] | null | undefined) =>
    (list ?? []).map(record).filter((entry): entry is Record<string, unknown> => entry !== null);

  needed.resistance = objects(effects.resistances).some(entry => entry.choose);
  const skillChoice = objects(effects.skillProficiencies).find(entry => entry.choose);
  if (skillChoice) needed.skill = numberOr(record(skillChoice.choose)?.count, 1);
  needed.savingThrow = objects(effects.savingThrowProficiencies).some(entry => entry.choose);
  const expertise = objects(effects.expertise).find(entry => entry.anyProficientSkill);
  if (expertise) needed.expertise = numberOr(expertise.anyProficientSkill, 1);
  const tool = objects(effects.toolProficiencies).find(entry => entry.any || entry.anyArtisansTool);
  if (tool) needed.tool = numberOr(tool.any, numberOr(tool.anyArtisansTool, 1));
  const language = objects(effects.languageProficiencies).find(entry => entry.any);
  if (language) needed.language = numberOr(language.any, 1);
  const weapon = objects(effects.weaponProficiencies).find(entry => entry.choose);
  if (weapon) needed.weapon = numberOr(record(weapon.choose)?.count, 4);
  needed.optionalFeatures = (effects.optionalFeatureProgression ?? [])
    .reduce((sum, progression) => sum + (progression.progression?.['*'] ?? 0), 0);
  for (const entry of objects(effects.skillToolLanguageProficiencies)) {
    if (Array.isArray(entry.choose)) {
      for (const choice of entry.choose) needed.skill += numberOr(record(choice)?.count, 0);
    }
  }
  return needed;
}

/** The damage types a resistance choice offers. */
export function resistanceOptions(effects: FeatEffects | null): string[] {
  for (const entry of effects?.resistances ?? []) {
    const from = strings(record(record(entry)?.choose)?.from);
    if (from.length > 0) return from;
  }
  return [];
}

/** The skills a skill choice offers: those it lists, or any the character isn't proficient in. */
export function skillOptions(effects: FeatEffects | null, proficient: string[]): string[] {
  const anySkill = [...ALL_SKILLS].filter(skill => !proficient.includes(skill));
  const lists = effects?.skillProficiencies?.length ? effects.skillProficiencies : effects?.skillToolLanguageProficiencies;
  for (const entry of lists ?? []) {
    const choose = record(entry)?.choose;
    const from = strings(record(choose)?.from);
    if (from.length > 0 && !from.includes('anySkill')) {
      return from.map(skill => skill.charAt(0).toUpperCase() + skill.slice(1));
    }
    if (choose) return anySkill;
  }
  return anySkill;
}

// The optional feature types 5etools names, as the reference data stores them
const OPTIONAL_FEATURE_TYPES: Record<string, string> = {
  EI: 'EldritchInvocation', MM: 'Metamagic', 'MV:B': 'BattleManeuver',
  'FS:F': 'FightingStyle', 'FS:R': 'FightingStyle', 'FS:P': 'FightingStyle', 'FS:B': 'FightingStyle',
};

/** The optional feature types the feat lets the player choose from, such as EldritchInvocation. */
export function optionalFeatureTypes(effects: FeatEffects | null): string[] {
  const types = (effects?.optionalFeatureProgression ?? []).flatMap(progression => progression.featureType ?? []);
  return [...new Set(types.map(type => OPTIONAL_FEATURE_TYPES[type] ?? type))];
}

// ── Spells ───────────────────────────────────────────────────

const SCHOOLS: Record<string, string> = {
  A: 'Abjuration', C: 'Conjuration', D: 'Divination', E: 'Enchantment',
  V: 'Evocation', I: 'Illusion', N: 'Necromancy', T: 'Transmutation',
};

/** One spell the player chooses: of a level (-1 for any), class and school, or from a list. */
export interface SpellFilter {
  level: number;
  classes: string[];
  schools: string[];
  fromList?: string[];
}

/** A spell's name without its book, class tag or level, each word capitalised: "hex|xge" gives "Hex". */
export function spellName(reference: string): string {
  return reference.replace(/#c$/, '').replace(/#\d+$/, '').replace(/\|.*$/, '')
    .split(/\s+/).map(word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase()).join(' ');
}

// A choice's filter, such as "level=0|class=Wizard" or "level=1|school=D;E"
function parseFilter(filter: string, anyLevel: number): SpellFilter {
  const result: SpellFilter = { level: anyLevel, classes: [], schools: [] };
  for (const part of filter.split('|')) {
    const [key, value = ''] = part.split('=');
    if (key === 'level') result.level = parseInt(value) || 0;
    if (key === 'class') {
      result.classes = value.split(';').map(c => c.trim().charAt(0).toUpperCase() + c.trim().slice(1).toLowerCase());
    }
    if (key === 'school') result.schools = value.split(';').map(s => SCHOOLS[s.trim()] ?? s.trim()).filter(Boolean);
  }
  return result;
}

/** The spells an option grants outright, by name, and one filter for each spell the player chooses. */
export function spellSlots(option: FeatSpellOption | undefined): { fixed: string[]; choices: SpellFilter[] } {
  const fixed: string[] = [];
  const choices: SpellFilter[] = [];
  if (!option) return { fixed, choices };
  const add = (entry: FeatSpellEntry, anyLevel: number) => {
    if (typeof entry === 'string') {
      fixed.push(spellName(entry));
      return;
    }
    const count = entry.count ?? 1;
    for (let i = 0; i < count; i++) {
      if (typeof entry.choose === 'string') {
        choices.push(parseFilter(entry.choose, anyLevel));
      } else {
        choices.push({ level: -1, classes: [], schools: [], fromList: entry.choose.from.map(spellName) });
      }
    }
  };
  // Known spells are cantrips unless the filter says otherwise
  for (const entry of option.known?._ ?? []) add(entry, 0);
  const innate = option.innate?._;
  for (const spells of Object.values(innate?.daily ?? innate?.rest ?? {})) {
    for (const entry of spells) add(entry, -1);
  }
  return { fixed, choices };
}
