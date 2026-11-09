import type { Background, CharacterClassRef, Race, Spell, Subclass } from '../../../types/reference';
import { ABILITIES, safeJsonParse } from '../../../utils/dndRules';
import type { AbilityScores, ClassEntry } from './types';

export type AbilityMethod = 'standard' | 'pointbuy' | 'manual';

/** One of the race's ability score bonuses and the ability it goes to, null until chosen. */
export interface BonusAssignment {
  bonus: number;
  ability: string | null;
}

/**
 * Everything the creation wizard's player has chosen. The chosen race, class and background are the reference
 * objects themselves; `classEntries` holds the first class (once its subclasses have loaded) and any multiclass.
 */
export interface WizardState {
  step: number;
  name: string;
  alignment: string;
  level: number;

  raceSearch: string;
  selectedRace: Race | null;
  raceChoiceSelections: Record<string, string[]>;
  bonusAssignments: BonusAssignment[];

  abilityMethod: AbilityMethod;
  scores: AbilityScores;
  standardAssignments: Record<string, number | null>;

  selectedClass: CharacterClassRef | null;
  classEntries: ClassEntry[];
  multiclassExpanded: boolean;
  selectedClassSkills: string[];
  mcSkillSelections: Record<string, string[]>;
  selectedExpertise: string[];

  bgSearch: string;
  selectedBackground: Background | null;
  bgProfChoices: Record<string, string[]>;
  bgSetChoices: Record<string, number | null>;

  /**
   * The starting equipment's choices (PHB p.143): the option chosen on each line that offers a choice, by
   * `class:<line>` or `background:<line>`.
   */
  equipmentOptions: Record<string, string>;
  /**
   * The items picked for an entry such as "a martial weapon", by `<owner>:<line>:<option>:<entry>`, one item ID per
   * pick ('' for one not yet made).
   */
  equipmentPicks: Record<string, string[]>;
  /** The gold pieces taken in place of the class's and background's equipment, or null to take the equipment. */
  startingGold: number | null;

  selectedCantrips: Spell[];
  selectedSpells: Spell[];
}

/** The draft kept in the browser under DRAFT_KEY, in the format saved drafts already have. */
export interface WizardDraft {
  name: string;
  alignment: string;
  step: number;
  abilityMethod: AbilityMethod;
  scores: AbilityScores;
  standardAssignments: Record<string, number | null>;
  level: number;
  raceId: string | null;
  classId: string | null;
  backgroundId: string | null;
}

/** The reference lists a draft's IDs are looked up in. */
export interface WizardRefs {
  races: Race[];
  classes: CharacterClassRef[];
  backgrounds: Background[];
}

export const initialWizardState: WizardState = {
  step: 0,
  name: '',
  alignment: '',
  level: 1,
  raceSearch: '',
  selectedRace: null,
  raceChoiceSelections: {},
  bonusAssignments: [],
  abilityMethod: 'standard',
  scores: { strength: 10, dexterity: 10, constitution: 10, intelligence: 10, wisdom: 10, charisma: 10 },
  standardAssignments: {
    strength: null, dexterity: null, constitution: null,
    intelligence: null, wisdom: null, charisma: null,
  },
  selectedClass: null,
  classEntries: [],
  multiclassExpanded: false,
  selectedClassSkills: [],
  mcSkillSelections: {},
  selectedExpertise: [],
  bgSearch: '',
  selectedBackground: null,
  bgProfChoices: {},
  bgSetChoices: {},
  equipmentOptions: {},
  equipmentPicks: {},
  startingGold: null,
  selectedCantrips: [],
  selectedSpells: [],
};

export type WizardAction =
  | { type: 'setStep'; step: number }
  | { type: 'setName'; name: string }
  | { type: 'setAlignment'; alignment: string }
  | { type: 'setLevel'; level: number }
  | { type: 'setRaceSearch'; search: string }
  | { type: 'selectRace'; race: Race | null }
  | { type: 'toggleRaceChoice'; key: string; value: string; count: number }
  | { type: 'assignBonus'; row: number; ability: string }
  | { type: 'setAbilityMethod'; method: AbilityMethod }
  | { type: 'setScore'; ability: keyof AbilityScores; value: number }
  | { type: 'setStandardAssignments'; assignments: Record<string, number | null> }
  | { type: 'selectClass'; cls: CharacterClassRef | null }
  | { type: 'primaryClassLoaded'; cls: CharacterClassRef; subclasses: Subclass[] }
  | { type: 'addMulticlass'; cls: CharacterClassRef; subclasses: Subclass[] }
  | { type: 'removeMulticlass'; classId: string }
  | { type: 'setClassLevel'; classId: string; level: number }
  | { type: 'setSubclass'; classId: string; subclass: Subclass | null }
  | { type: 'toggleMulticlassExpanded' }
  | { type: 'toggleClassSkill'; skill: string; max: number }
  | { type: 'toggleMulticlassSkill'; classId: string; skill: string; max: number }
  | { type: 'toggleExpertise'; skill: string; max: number }
  | { type: 'setBackgroundSearch'; search: string }
  | { type: 'selectBackground'; background: Background | null }
  | { type: 'toggleBgProfChoice'; key: string; value: string; count: number }
  | { type: 'toggleBgSetChoice'; key: string; index: number }
  | { type: 'chooseEquipmentOption'; key: string; option: string }
  | { type: 'pickEquipment'; key: string; index: number; count: number; itemId: string }
  | { type: 'setStartingGold'; gold: number | null }
  | { type: 'toggleCantrip'; spell: Spell; max: number }
  | { type: 'toggleSpell'; spell: Spell; max: number }
  | { type: 'restoreDraft'; draft: Partial<WizardDraft>; refs: WizardRefs };

/** The race's bonuses as rows to assign: a fixed bonus starts on its ability, a chosen one on none. */
export function bonusRows(race: Race | null): BonusAssignment[] {
  if (!race) return [];
  const bonuses = safeJsonParse<Array<{ ability: string; bonus: number; count?: number }>>(race.abilityScoreBonuses, []);
  const rows: BonusAssignment[] = [];
  for (const b of bonuses) {
    if (b.ability === 'CHOOSE') {
      for (let i = 0; i < (b.count || 1); i++) rows.push({ bonus: b.bonus, ability: null });
    } else {
      const key = b.ability.toLowerCase().slice(0, 3);
      rows.push({ bonus: b.bonus, ability: ABILITIES.find(a => a.startsWith(key)) || null });
    }
  }
  return rows;
}

/**
 * Picks `value` for a choice of `count`: picking it again drops it, and a pick past the count drops the oldest.
 */
function toggleLimited(current: string[], value: string, count: number): string[] {
  if (current.includes(value)) return current.filter(v => v !== value);
  if (current.length >= count) return [...current.slice(1), value];
  return [...current, value];
}

/** Adds `value` while fewer than `max` are chosen, or drops it when chosen. */
function toggleUpTo<T>(current: T[], value: T, max: number, same: (a: T, b: T) => boolean = Object.is): T[] {
  if (current.some(v => same(v, value))) return current.filter(v => !same(v, value));
  if (current.length < max) return [...current, value];
  return current;
}

const sameSpell = (a: Spell, b: Spell) => a.id === b.id;

/** Drops the equipment choices of a class or background, whose keys start with its owner. */
function withoutOwner<T>(choices: Record<string, T>, owner: 'class' | 'background'): Record<string, T> {
  return Object.fromEntries(Object.entries(choices).filter(([key]) => !key.startsWith(`${owner}:`)));
}

/**
 * Brings the class levels to the character's level after it changes. The first class takes up the difference;
 * if that would leave it below 1, the classes are kept in order, each at its level or what is left, until the
 * levels run out, and the first class takes up any shortfall.
 */
export function rebalanceClassLevels(entries: ClassEntry[], level: number): ClassEntry[] {
  if (entries.length === 0) return entries;
  const currentTotal = entries.reduce((s, e) => s + e.level, 0);
  if (currentTotal === level) return entries;
  const newPrimaryLevel = entries[0].level + (level - currentTotal);
  if (newPrimaryLevel >= 1) return entries.map((e, i) => i === 0 ? { ...e, level: newPrimaryLevel } : e);
  let remaining = level;
  const result: ClassEntry[] = [];
  for (const entry of entries) {
    if (remaining <= 0) break;
    const entryLevel = Math.min(entry.level, remaining);
    result.push({ ...entry, level: Math.max(1, entryLevel) });
    remaining -= Math.max(1, entryLevel);
  }
  if (result.length > 0) {
    const total = result.reduce((s, e) => s + e.level, 0);
    if (total < level) result[0] = { ...result[0], level: result[0].level + (level - total) };
  }
  return result;
}

/** Keeps only the multiclass skills of classes still in `entries`. */
function keepMulticlassSkills(selections: Record<string, string[]>, entries: ClassEntry[]): Record<string, string[]> {
  const ids = new Set(entries.map(e => e.cls.id));
  const kept = Object.entries(selections).filter(([classId]) => ids.has(classId));
  return kept.length === Object.keys(selections).length ? selections : Object.fromEntries(kept);
}

/**
 * The wizard's state changes. Each action makes its own resets: a new race clears the race's choices and
 * re-seeds its bonuses, a new class clears its subclass, skills, Expertise and spells, a new background clears
 * the background's choices, each with its starting equipment's, and a new character level rebalances the class levels.
 */
export function wizardReducer(state: WizardState, action: WizardAction): WizardState {
  switch (action.type) {
    case 'setStep':
      return { ...state, step: action.step };
    case 'setName':
      return { ...state, name: action.name };
    case 'setAlignment':
      return { ...state, alignment: action.alignment };
    case 'setLevel': {
      const classEntries = rebalanceClassLevels(state.classEntries, action.level);
      return {
        ...state, level: action.level, classEntries,
        mcSkillSelections: keepMulticlassSkills(state.mcSkillSelections, classEntries),
      };
    }

    case 'setRaceSearch':
      return { ...state, raceSearch: action.search };
    case 'selectRace':
      return { ...state, selectedRace: action.race, raceChoiceSelections: {}, bonusAssignments: bonusRows(action.race) };
    case 'toggleRaceChoice':
      return {
        ...state,
        raceChoiceSelections: {
          ...state.raceChoiceSelections,
          [action.key]: toggleLimited(state.raceChoiceSelections[action.key] ?? [], action.value, action.count),
        },
      };
    case 'assignBonus':
      // An ability takes one bonus row: assigning it to this row takes it off any other
      return {
        ...state,
        bonusAssignments: state.bonusAssignments.map((a, i) => {
          if (i === action.row) return { ...a, ability: action.ability };
          if (a.ability === action.ability) return { ...a, ability: null };
          return a;
        }),
      };

    case 'setAbilityMethod': {
      // Point buy starts every score at 8 with all 27 points unspent (PHB); choosing it again keeps the scores
      if (action.method === 'pointbuy' && state.abilityMethod !== 'pointbuy') {
        const scores = { ...state.scores };
        for (const a of ABILITIES) scores[a] = 8;
        return { ...state, abilityMethod: action.method, scores };
      }
      return { ...state, abilityMethod: action.method };
    }
    case 'setScore':
      return { ...state, scores: { ...state.scores, [action.ability]: action.value } };
    case 'setStandardAssignments':
      return { ...state, standardAssignments: action.assignments };

    case 'selectClass':
      // The first class's entry comes with its subclasses, in primaryClassLoaded
      return {
        ...state, selectedClass: action.cls, classEntries: [],
        selectedClassSkills: [], mcSkillSelections: {}, selectedExpertise: [],
        selectedCantrips: [], selectedSpells: [],
        equipmentOptions: withoutOwner(state.equipmentOptions, 'class'),
        equipmentPicks: withoutOwner(state.equipmentPicks, 'class'),
        startingGold: null,
      };
    case 'primaryClassLoaded':
      // Subclasses that arrive after another class was chosen are dropped
      if (state.selectedClass?.id !== action.cls.id) return state;
      return { ...state, classEntries: [{ cls: action.cls, level: state.level, subclass: null, subclasses: action.subclasses }] };
    case 'addMulticlass': {
      if (state.classEntries.length === 0 || state.classEntries.some(e => e.cls.id === action.cls.id)) return state;
      // The new class's level comes from the first class's
      const updated = state.classEntries.map((e, i) => i === 0 ? { ...e, level: Math.max(1, e.level - 1) } : e);
      return { ...state, classEntries: [...updated, { cls: action.cls, level: 1, subclass: null, subclasses: action.subclasses }] };
    }
    case 'removeMulticlass': {
      const entry = state.classEntries.find(e => e.cls.id === action.classId);
      if (!entry || state.classEntries[0].cls.id === action.classId) return state;
      // Its levels go back to the first class, and its skills go with it
      const classEntries = state.classEntries
        .filter(e => e.cls.id !== action.classId)
        .map((e, i) => i === 0 ? { ...e, level: e.level + entry.level } : e);
      return { ...state, classEntries, mcSkillSelections: keepMulticlassSkills(state.mcSkillSelections, classEntries) };
    }
    case 'setClassLevel': {
      const idx = state.classEntries.findIndex(e => e.cls.id === action.classId);
      if (idx < 0) return state;
      // Between 1 and what the other classes leave of the character's level
      const otherTotal = state.classEntries.reduce((s, e, i) => i === idx ? s : s + e.level, 0);
      const clamped = Math.max(1, Math.min(action.level, state.level - otherTotal));
      return { ...state, classEntries: state.classEntries.map((e, i) => i === idx ? { ...e, level: clamped } : e) };
    }
    case 'setSubclass':
      return {
        ...state,
        classEntries: state.classEntries.map(e => e.cls.id === action.classId ? { ...e, subclass: action.subclass } : e),
      };
    case 'toggleMulticlassExpanded':
      return { ...state, multiclassExpanded: !state.multiclassExpanded };
    case 'toggleClassSkill':
      return { ...state, selectedClassSkills: toggleUpTo(state.selectedClassSkills, action.skill, action.max) };
    case 'toggleMulticlassSkill':
      return {
        ...state,
        mcSkillSelections: {
          ...state.mcSkillSelections,
          [action.classId]: toggleUpTo(state.mcSkillSelections[action.classId] ?? [], action.skill, action.max),
        },
      };
    case 'toggleExpertise':
      return { ...state, selectedExpertise: toggleUpTo(state.selectedExpertise, action.skill, action.max) };

    case 'setBackgroundSearch':
      return { ...state, bgSearch: action.search };
    case 'selectBackground':
      return {
        ...state, selectedBackground: action.background, bgProfChoices: {}, bgSetChoices: {},
        equipmentOptions: withoutOwner(state.equipmentOptions, 'background'),
        equipmentPicks: withoutOwner(state.equipmentPicks, 'background'),
      };
    case 'toggleBgProfChoice':
      return {
        ...state,
        bgProfChoices: {
          ...state.bgProfChoices,
          [action.key]: toggleLimited(state.bgProfChoices[action.key] ?? [], action.value, action.count),
        },
      };
    case 'toggleBgSetChoice':
      return {
        ...state,
        bgSetChoices: { ...state.bgSetChoices, [action.key]: state.bgSetChoices[action.key] === action.index ? null : action.index },
      };

    case 'chooseEquipmentOption':
      return { ...state, equipmentOptions: { ...state.equipmentOptions, [action.key]: action.option } };
    case 'pickEquipment': {
      const picks = Array.from({ length: action.count }, (_, i) => state.equipmentPicks[action.key]?.[i] ?? '');
      picks[action.index] = action.itemId;
      return { ...state, equipmentPicks: { ...state.equipmentPicks, [action.key]: picks } };
    }
    case 'setStartingGold':
      return { ...state, startingGold: action.gold };

    case 'toggleCantrip':
      return { ...state, selectedCantrips: toggleUpTo(state.selectedCantrips, action.spell, action.max, sameSpell) };
    case 'toggleSpell':
      return { ...state, selectedSpells: toggleUpTo(state.selectedSpells, action.spell, action.max, sameSpell) };

    case 'restoreDraft':
      return restoreDraft(state, action.draft, action.refs);
  }
}

/**
 * Applies a saved draft over the state. Each field the draft has replaces the state's; the race, class and
 * background are looked up by ID and chosen as the player would choose them, with the same resets. A class still
 * needs its subclasses, through primaryClassLoaded.
 */
function restoreDraft(state: WizardState, d: Partial<WizardDraft>, refs: WizardRefs): WizardState {
  let s = state;
  if (d.name) s = { ...s, name: d.name };
  if (d.alignment) s = { ...s, alignment: d.alignment };
  if (d.step != null) s = { ...s, step: d.step };
  if (d.abilityMethod) s = { ...s, abilityMethod: d.abilityMethod };
  if (d.scores) s = { ...s, scores: d.scores };
  if (d.standardAssignments) s = { ...s, standardAssignments: d.standardAssignments };
  if (d.level) s = wizardReducer(s, { type: 'setLevel', level: d.level });
  const race = d.raceId ? refs.races.find(r => r.id === d.raceId) : undefined;
  if (race) s = wizardReducer(s, { type: 'selectRace', race });
  const cls = d.classId ? refs.classes.find(c => c.id === d.classId) : undefined;
  if (cls) s = wizardReducer(s, { type: 'selectClass', cls });
  const background = d.backgroundId ? refs.backgrounds.find(b => b.id === d.backgroundId) : undefined;
  if (background) s = wizardReducer(s, { type: 'selectBackground', background });
  return s;
}
