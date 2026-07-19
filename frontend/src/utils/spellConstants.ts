// PHB cantrips known per class per level (index 0 = level 0 unused, 1-20 = character levels)
export const CANTRIPS_KNOWN: Record<string, number[]> = {
  Bard:      [0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4],
  Cleric:    [0, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5],
  Druid:     [0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4],
  Sorcerer:  [0, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6],
  Warlock:   [0, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4],
  Wizard:    [0, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5],
  Artificer: [0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4],
};

// PHB spells known per class per level (known casters only)
export const SPELLS_KNOWN: Record<string, number[]> = {
  Bard:     [0, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 15, 16, 18, 19, 19, 20, 22, 22, 22],
  Ranger:   [0, 0, 2, 3, 3, 4, 4,  5,  5,  6,  6,  7,  7,  8,  8,  9,  9, 10, 10, 11, 11],
  Sorcerer: [0, 2, 3, 4, 5, 6, 7,  8,  9, 10, 11, 12, 12, 13, 13, 14, 14, 15, 15, 15, 15],
  Warlock:  [0, 2, 3, 4, 5, 6, 7,  8,  9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15],
};

// Half-casters that use abilityMod + floor(level/2) for prepared count
export const HALF_CASTERS = new Set(['Paladin', 'Ranger']);

export function getPreparedCount(className: string, level: number, abilityMod: number): number {
  if (HALF_CASTERS.has(className)) {
    return Math.max(1, abilityMod + Math.floor(level / 2));
  }
  if (className === 'Artificer') {
    return Math.max(1, abilityMod + Math.floor(level / 2));
  }
  return Math.max(1, abilityMod + level);
}

const FULL_CASTER_CLASSES = new Set(['Bard', 'Cleric', 'Druid', 'Sorcerer', 'Wizard']);
const HALF_CASTER_CLASSES = new Set(['Paladin', 'Ranger']);
const PACT_CASTER_CLASSES = new Set(['Warlock']);
const ARTIFICER_CLASS = 'Artificer';

export function maxSpellLevel(className: string, level: number): number {
  if (FULL_CASTER_CLASSES.has(className)) {
    return Math.min(9, Math.ceil(level / 2));
  }
  if (HALF_CASTER_CLASSES.has(className) || className === ARTIFICER_CLASS) {
    if (level < 2) return 0;
    return Math.min(5, Math.ceil(level / 4));
  }
  if (PACT_CASTER_CLASSES.has(className)) {
    return Math.min(5, Math.ceil(level / 2));
  }
  return 0;
}

export function proficiencyBonusForLevel(level: number): number {
  if (level <= 4) return 2;
  if (level <= 8) return 3;
  if (level <= 12) return 4;
  if (level <= 16) return 5;
  return 6;
}
