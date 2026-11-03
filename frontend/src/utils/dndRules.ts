import type { PlayerCharacter } from '../types/character';

export const ABILITIES = ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'] as const;

export type AbilityName = typeof ABILITIES[number];

export const ABILITY_FROM_ABBR: Record<string, string> = {
  STR: 'strength', DEX: 'dexterity', CON: 'constitution',
  INT: 'intelligence', WIS: 'wisdom', CHA: 'charisma',
};

export const ABILITY_ABBR: Record<string, string> = {
  strength: 'STR', dexterity: 'DEX', constitution: 'CON',
  intelligence: 'INT', wisdom: 'WIS', charisma: 'CHA',
};

export const ABILITY_LABELS = ABILITY_ABBR;

export const ALL_SKILLS = [
  'Acrobatics', 'Animal Handling', 'Arcana', 'Athletics', 'Deception', 'History',
  'Insight', 'Intimidation', 'Investigation', 'Medicine', 'Nature', 'Perception',
  'Performance', 'Persuasion', 'Religion', 'Sleight of Hand', 'Stealth', 'Survival',
] as const;

export const SKILLS: Array<{ name: string; ability: AbilityName }> = [
  { name: 'Acrobatics', ability: 'dexterity' }, { name: 'Animal Handling', ability: 'wisdom' },
  { name: 'Arcana', ability: 'intelligence' }, { name: 'Athletics', ability: 'strength' },
  { name: 'Deception', ability: 'charisma' }, { name: 'History', ability: 'intelligence' },
  { name: 'Insight', ability: 'wisdom' }, { name: 'Intimidation', ability: 'charisma' },
  { name: 'Investigation', ability: 'intelligence' }, { name: 'Medicine', ability: 'wisdom' },
  { name: 'Nature', ability: 'intelligence' }, { name: 'Perception', ability: 'wisdom' },
  { name: 'Performance', ability: 'charisma' }, { name: 'Persuasion', ability: 'charisma' },
  { name: 'Religion', ability: 'intelligence' }, { name: 'Sleight of Hand', ability: 'dexterity' },
  { name: 'Stealth', ability: 'dexterity' }, { name: 'Survival', ability: 'wisdom' },
];

export function abilityMod(score: number): number {
  return Math.floor((score - 10) / 2);
}

export function formatMod(mod: number): string {
  return mod >= 0 ? `+${mod}` : `${mod}`;
}

export function formatAbilityMod(score: number): string {
  return formatMod(abilityMod(score));
}

/**
 * A character's classes as its sheet's heading gives them: each class and its level for a multiclass character, such
 * as "Paladin 5 / Sorcerer 1", or else the class and its subclass, "Fighter (Champion)".
 */
export function formatClasses(character: Pick<PlayerCharacter, 'characterClass' | 'subclass' | 'multiclassEntries'>): string {
  const entries = character.multiclassEntries ?? [];
  if (entries.length > 1) {
    return entries.map(e => `${e.className} ${e.level}`).join(' / ');
  }
  return (character.characterClass ?? '') + (character.subclass ? ` (${character.subclass})` : '');
}

export function safeJsonParse<T>(json: unknown, fallback: T): T {
  if (json == null) return fallback;
  if (typeof json !== 'string') return json as T;
  try { return JSON.parse(json); } catch { return fallback; }
}
