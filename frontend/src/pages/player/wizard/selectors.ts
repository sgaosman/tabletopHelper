import type { CharacterCreateRequest } from '../../../types/character';
import type { Background, Race } from '../../../types/reference';
import type { SpellEntry } from '../sheet/types';
import { ABILITIES, ALL_SKILLS, safeJsonParse, ABILITY_ABBR as ABILITY_LABELS } from '../../../utils/dndRules';
import {
  ALL_STEPS, ALL_LANGUAGES, ALL_TOOLS, ARTISANS_TOOLS, MUSICAL_INSTRUMENTS,
  getToolAnyOptions, expandToolFrom, formatProfEntry,
} from './types';
import type { AbilityScores, ProfEntry, RaceChoiceReq, ChoiceReq } from './types';
import type { WizardDraft, WizardState } from './wizardReducer';
import { equipmentComplete, startingInventory, type WizardKits } from './startingEquipment';
import { ancestryOf, DRACONIC_ANCESTRIES, hasDraconicAncestry } from '../../../utils/draconicAncestry';

// The values the creation wizard works out from its state, and the request it sends

export type SkillChoices = { from: string[]; count: number };

export interface ResolvedRaceChoices {
  languages: string[];
  skills: string[];
  tools: string[];
  weapons: string[];
  resistances: string[];
  spellAbility: string | null;
  /** A Dragonborn's draconic ancestry, by its dragon (D208), whose damage type is among the resistances */
  draconicAncestry: string | null;
}

/** The race choice that picks a Dragonborn's draconic ancestry (PHB p.34, D208), in place of its resistance. */
export const ANCESTRY_KEY = 'ancestry';

/** How an ancestry reads among the race's choices: "Red (fire)". */
export function ancestryOption(dragon: string, damageType: string): string {
  return `${dragon} (${damageType})`;
}

export interface ResolvedBgProfs {
  skills: string[];
  tools: string[];
  languages: string[];
}

// ── Steps ──

/** The steps, with Spells only when the first class casts: another class's spells are chosen on the sheet. */
export function wizardSteps(state: WizardState): readonly string[] {
  return state.selectedClass?.isSpellcaster ? ALL_STEPS : ALL_STEPS.filter(s => s !== 'Spells');
}

export function currentStepName(state: WizardState): string | undefined {
  return wizardSteps(state)[state.step];
}

// ── Draft ──

/** Whether there is anything worth keeping as a draft. */
export function hasMeaningfulData(state: WizardState): boolean {
  return state.name.trim().length > 0 || state.selectedRace !== null || state.selectedClass !== null;
}

/** The draft saved in the browser, with the fields, in the order, saved drafts have always had. */
export function toDraft(state: WizardState): WizardDraft {
  return {
    name: state.name, alignment: state.alignment, step: state.step, abilityMethod: state.abilityMethod,
    scores: state.scores, standardAssignments: state.standardAssignments, level: state.level,
    raceId: state.selectedRace?.id ?? null,
    classId: state.selectedClass?.id ?? null,
    backgroundId: state.selectedBackground?.id ?? null,
  };
}

// ── Feats ──

// A background's feat, and a race's, such as a Variant Human's, are Pending Choices made on the sheet (D159)
export function bgFeatNames(background: Background | null): string[] {
  if (!background?.feats) return [];
  return safeJsonParse<string[]>(background.feats, []);
}

export function raceFeats(race: Race | null): number {
  if (!race) return 0;
  const choices = safeJsonParse<{ feats?: number } | null>(race.raceChoices, null);
  return typeof choices?.feats === 'number' ? choices.feats : 0;
}

// ── Class, skills and Expertise ──

export function classSkillChoices(state: WizardState): SkillChoices {
  if (!state.selectedClass?.skillChoices) return { from: [], count: 0 };
  return safeJsonParse<SkillChoices>(state.selectedClass.skillChoices, { from: [], count: 0 });
}

/** The skills each multiclass offers (PHB p.164), by class ID; the first class's are classSkillChoices. */
export function multiclassSkillChoices(state: WizardState): Record<string, SkillChoices> {
  const map: Record<string, SkillChoices> = {};
  const entries = state.classEntries;
  if (entries.length <= 1) return map;
  for (let i = 1; i < entries.length; i++) {
    const entry = entries[i];
    if (!entry.cls.multiclassProficiencies) continue;
    const mcProfs = safeJsonParse<{ skills?: SkillChoices }>(entry.cls.multiclassProficiencies, {});
    if (mcProfs.skills && mcProfs.skills.from.length > 0 && mcProfs.skills.count > 0) map[entry.cls.id] = mcProfs.skills;
  }
  return map;
}

/** A rogue's 1st-level Expertise (PHB p.94); every later level's is a Pending Choice made on the sheet (D159). */
export function expertiseCount(state: WizardState): number {
  return state.classEntries[0]?.cls.name === 'Rogue' ? 2 : 0;
}

/** Whether the first class's 1st level chooses its subclass, as a cleric's, sorcerer's or warlock's does. */
export function firstLevelSubclass(state: WizardState): boolean {
  const first = state.classEntries[0];
  return first?.cls.subclassLevel === 1 && (first?.subclasses.length ?? 0) > 0;
}

// ── Race ──

/** What the race asks the player to choose: languages, skills, tools, weapons, a resistance, a spell ability. */
export function raceChoiceReqs(race: Race | null): RaceChoiceReq[] {
  if (!race) return [];
  const choices = safeJsonParse<Record<string, unknown> | null>(race.raceChoices, null);
  if (!choices) return [];
  const reqs: RaceChoiceReq[] = [];
  const profs = safeJsonParse<{ skills?: string[]; languages?: string[]; tools?: string[] }>(race.proficiencies, {});
  if (choices.languages) {
    const existingLangs = new Set((profs.languages ?? []).map((l: string) => l.toLowerCase()));
    for (const lc of choices.languages as Array<Record<string, unknown>>) {
      if (lc.anyStandard) { reqs.push({ key: `lang_${reqs.length}`, label: 'Language', options: ALL_LANGUAGES.filter(l => !existingLangs.has(l.toLowerCase())), count: lc.anyStandard as number }); }
      if (lc.choose) { const ch = lc.choose as { from?: string[]; count?: number }; if (ch.from?.length) reqs.push({ key: `lang_${reqs.length}`, label: 'Language', options: ch.from, count: ch.count ?? 1 }); }
    }
  }
  if (choices.skills) {
    const existingSkills = new Set((profs.skills ?? []).map((s: string) => s.toLowerCase()));
    for (const sc of choices.skills as Array<Record<string, unknown>>) {
      if (sc.any) reqs.push({ key: `skill_${reqs.length}`, label: 'Skill Proficiency', options: ALL_SKILLS.filter(s => !existingSkills.has(s.toLowerCase())), count: sc.any as number });
      if (sc.choose) { const ch = sc.choose as { from?: string[]; count?: number }; if (ch.from?.length) reqs.push({ key: `skill_${reqs.length}`, label: 'Skill Proficiency', options: ch.from, count: ch.count ?? 1 }); }
    }
  }
  if (choices.tools) {
    for (const tc of choices.tools as Array<Record<string, unknown>>) {
      if (tc.any) reqs.push({ key: `tool_${reqs.length}`, label: 'Tool Proficiency', options: ALL_TOOLS, count: tc.any as number });
      if (tc.anyArtisansTool) reqs.push({ key: `tool_${reqs.length}`, label: "Artisan's Tool", options: ARTISANS_TOOLS, count: tc.anyArtisansTool as number });
      if (tc.anyMusicalInstrument) reqs.push({ key: `tool_${reqs.length}`, label: 'Musical Instrument', options: MUSICAL_INSTRUMENTS, count: tc.anyMusicalInstrument as number });
      if (tc.choose) { const ch = tc.choose as { from?: string[]; count?: number }; if (ch.from?.length) reqs.push({ key: `tool_${reqs.length}`, label: 'Tool Proficiency', options: ch.from, count: ch.count ?? 1 }); }
    }
  }
  if (choices.weapons) { for (const wc of choices.weapons as Array<{ from?: string[]; count?: number }>) reqs.push({ key: `weapon_${reqs.length}`, label: 'Martial Weapon Proficiency', options: wc.from ?? [], count: wc.count ?? 2 }); }
  // A race with Draconic Ancestry chooses its dragon, which gives the resistance (PHB p.34, D208)
  const draconic = hasDraconicAncestry(race.traits);
  if (draconic) reqs.push({ key: ANCESTRY_KEY, label: 'Draconic Ancestry', options: DRACONIC_ANCESTRIES.map(a => ancestryOption(a.dragon, a.damageType)), count: 1 });
  if (choices.resistances && !draconic) { for (const rc of choices.resistances as Array<{ from: string[] }>) reqs.push({ key: `resist_${reqs.length}`, label: 'Damage Resistance', options: rc.from, count: 1 }); }
  if (choices.spellAbility) reqs.push({ key: 'spellAbility', label: 'Spellcasting Ability', options: choices.spellAbility as string[], count: 1 });
  return reqs;
}

export function raceChoicesComplete(state: WizardState): boolean {
  return raceChoiceReqs(state.selectedRace).every(req => (state.raceChoiceSelections[req.key] ?? []).length === req.count);
}

export function resolvedRaceChoices(state: WizardState): ResolvedRaceChoices {
  const result: ResolvedRaceChoices = { languages: [], skills: [], tools: [], weapons: [], resistances: [], spellAbility: null, draconicAncestry: null };
  for (const req of raceChoiceReqs(state.selectedRace)) {
    const selected = state.raceChoiceSelections[req.key] ?? [];
    if (req.key.startsWith('lang_')) result.languages.push(...selected);
    else if (req.key.startsWith('skill_')) result.skills.push(...selected);
    else if (req.key.startsWith('tool_')) result.tools.push(...selected);
    else if (req.key.startsWith('weapon_')) result.weapons.push(...selected);
    else if (req.key.startsWith('resist_')) result.resistances.push(...selected);
    else if (req.key === 'spellAbility') result.spellAbility = selected[0] ?? null;
    else if (req.key === ANCESTRY_KEY) {
      const ancestry = ancestryOf((selected[0] ?? '').split(' ')[0]);
      if (ancestry) {
        result.draconicAncestry = ancestry.dragon;
        result.resistances.push(ancestry.damageType);
      }
    }
  }
  return result;
}

/** A race whose +1 goes to every ability, as the PHB human's does, has nothing to assign. */
export function isVanillaHuman(race: Race | null): boolean {
  if (!race) return false;
  const bonuses = safeJsonParse<Array<{ ability: string; bonus: number }>>(race.abilityScoreBonuses, []);
  return bonuses.length === 6 && bonuses.every(b => b.ability !== 'CHOOSE' && b.bonus === 1);
}

/** The race's skills, fixed and chosen, lower-cased. */
export function raceSkills(state: WizardState): Set<string> {
  if (!state.selectedRace) return new Set<string>();
  const profs = safeJsonParse<{ skills?: string[] }>(state.selectedRace.proficiencies, {});
  return new Set([
    ...(profs.skills ?? []).map(s => s.toLowerCase()),
    ...resolvedRaceChoices(state).skills.map(s => s.toLowerCase()),
  ]);
}

// ── Background ──

const BG_FIELDS = ['skillProficiencies', 'toolProficiencies', 'languageProficiencies'] as const;

function bgField(background: Background, field: typeof BG_FIELDS[number]): string | null {
  return (background as unknown as Record<string, string | null>)[field];
}

/** What the background asks the player to choose. */
export function bgChoiceReqs(background: Background | null): ChoiceReq[] {
  if (!background) return [];
  const reqs: ChoiceReq[] = [];
  const fields: Array<[typeof BG_FIELDS[number], string, string]> = [['skillProficiencies', 'Skill', 'skill'], ['toolProficiencies', 'Tool', 'tool'], ['languageProficiencies', 'Language', 'lang']];
  for (const [field, label, prefix] of fields) {
    const raw = bgField(background, field);
    if (!raw) continue;
    const parsed = safeJsonParse<ProfEntry[]>(raw, []);
    let chooseIdx = 0;
    for (const p of parsed) {
      if (typeof p === 'string') {
        const anyOpts = prefix === 'tool' ? getToolAnyOptions(p) : prefix === 'skill' && p === 'Any' ? ALL_SKILLS : null;
        if (anyOpts) { reqs.push({ key: `${prefix}_choose_${chooseIdx}`, label: `${label} Proficiency`, type: 'choose', from: [...anyOpts], count: 1 }); chooseIdx++; }
      } else if (typeof p === 'object' && p !== null && 'any' in p) {
        const n = (p as { any: number }).any;
        const pool = prefix === 'skill' ? ALL_SKILLS : prefix === 'tool' ? ALL_TOOLS : ALL_LANGUAGES;
        reqs.push({ key: `${prefix}_choose_${chooseIdx}`, label: `${label} Proficiency`, type: 'choose', from: [...pool], count: n }); chooseIdx++;
      } else if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
        const sets = (p as { chooseSet: ProfEntry[][] }).chooseSet;
        reqs.push({ key: `${prefix}_set`, label: `${label} Proficiencies`, type: 'chooseSet', sets: sets.map(s => s.map(formatProfEntry).filter(Boolean)) });
      } else if (typeof p === 'object' && p !== null && 'anyStandard' in p) {
        const n = (p as { anyStandard: number }).anyStandard;
        reqs.push({ key: `${prefix}_choose_${chooseIdx}`, label: `${label}`, type: 'choose', from: ALL_LANGUAGES, count: n }); chooseIdx++;
      } else if (typeof p === 'object' && p !== null && 'choose' in p) {
        const c = (p as { choose: { from?: string[]; count?: number } }).choose;
        if (c.from && c.from.length > 0) { const expanded = prefix === 'tool' ? expandToolFrom(c.from) : c.from; reqs.push({ key: `${prefix}_choose_${chooseIdx}`, label: `${label} Proficiency`, type: 'choose', from: expanded, count: c.count ?? 1 }); chooseIdx++; }
      }
    }
  }
  return reqs;
}

export function bgChoicesComplete(state: WizardState): boolean {
  return bgChoiceReqs(state.selectedBackground).every(req => {
    if (req.type === 'choose') return (state.bgProfChoices[req.key] ?? []).length === req.count;
    return state.bgSetChoices[req.key] != null;
  });
}

/** The background's proficiencies, fixed and chosen. */
export function resolvedBgProfs(state: WizardState): ResolvedBgProfs {
  const background = state.selectedBackground;
  if (!background) return { skills: [], tools: [], languages: [] };
  const { bgProfChoices, bgSetChoices } = state;
  const result: { skills: string[]; tools: string[]; languages: string[]; [key: string]: string[] } = { skills: [], tools: [], languages: [] };
  const fields: Array<[typeof BG_FIELDS[number], string, string]> = [['skillProficiencies', 'skill', 'skills'], ['toolProficiencies', 'tool', 'tools'], ['languageProficiencies', 'lang', 'languages']];
  for (const [field, prefix, outKey] of fields) {
    const raw = bgField(background, field);
    if (!raw) continue;
    const parsed = safeJsonParse<ProfEntry[]>(raw, []);
    let chooseIdx = 0;
    for (const p of parsed) {
      if (typeof p === 'string') {
        const isAny = prefix === 'tool' ? !!getToolAnyOptions(p) : prefix === 'skill' && p === 'Any';
        if (isAny) { result[outKey].push(...(bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [])); chooseIdx++; }
        else result[outKey].push(p);
      } else if (typeof p === 'object' && p !== null && 'any' in p) { result[outKey].push(...(bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [])); chooseIdx++; }
      else if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
        const idx = bgSetChoices[`${prefix}_set`];
        if (idx != null) { const sets = (p as { chooseSet: ProfEntry[][] }).chooseSet; result[outKey].push(...sets[idx].map(formatProfEntry).filter(Boolean)); }
      } else if (typeof p === 'object' && p !== null && ('anyStandard' in p || 'choose' in p)) { result[outKey].push(...(bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [])); chooseIdx++; }
    }
  }
  return result;
}

// ── Skills held (D226) ──

const lower = (s: string) => s.toLowerCase();

/** The multiclasses' skills, in class order, as chosen. */
function multiclassSkillList(state: WizardState): string[] {
  return state.classEntries.slice(1).flatMap(e => state.mcSkillSelections[e.cls.id] ?? []);
}

/** The skills the race and the classes give, lower-cased: everything held before the background's. */
export function skillsBeforeBackground(state: WizardState): Set<string> {
  return new Set([...raceSkills(state), ...state.selectedClassSkills.map(lower), ...multiclassSkillList(state).map(lower)]);
}

/** The skills a multiclass can't choose because the race, the first class or another multiclass gives them. */
export function multiclassSkillsTaken(state: WizardState, classId: string): Set<string> {
  return new Set([
    ...raceSkills(state),
    ...state.selectedClassSkills.map(lower),
    ...Object.entries(state.mcSkillSelections).filter(([k]) => k !== classId).flatMap(([, v]) => v.map(lower)),
  ]);
}

/**
 * How many skills a multiclass must choose (PHB p.164): its count, or fewer when the race and the other classes
 * already give the rest of its list (D226).
 */
export function multiclassSkillsRequired(state: WizardState, classId: string): number {
  const choices = multiclassSkillChoices(state)[classId];
  if (!choices) return 0;
  const taken = multiclassSkillsTaken(state, classId);
  return Math.min(choices.count, choices.from.filter(s => !taken.has(lower(s))).length);
}

export function multiclassSkillsComplete(state: WizardState): boolean {
  return Object.keys(multiclassSkillChoices(state))
    .every(id => (state.mcSkillSelections[id] ?? []).length >= multiclassSkillsRequired(state, id));
}

/**
 * The background's skills the race or a class already gives. The PHB (p.125) lets the character choose a different
 * skill for each, in the Background step (D226).
 */
export function bgSkillConflicts(state: WizardState): string[] {
  const before = skillsBeforeBackground(state);
  return resolvedBgProfs(state).skills.filter(s => before.has(lower(s)));
}

/** The skill chosen in place of each conflicting background skill, for the conflicts there are now. */
export function bgSkillReplacementList(state: WizardState): string[] {
  return bgSkillConflicts(state).map(c => state.bgSkillReplacements[c]).filter((r): r is string => !!r);
}

/** The skills that can replace `conflict`: any skill not already held, its own replacement aside (PHB p.125). */
export function bgReplacementOptions(state: WizardState, conflict: string): string[] {
  const held = new Set([
    ...skillsBeforeBackground(state),
    ...resolvedBgProfs(state).skills.map(lower),
    ...bgSkillConflicts(state).filter(c => c !== conflict).map(c => state.bgSkillReplacements[c]).filter(Boolean).map(lower),
  ]);
  return ALL_SKILLS.filter(s => !held.has(lower(s)));
}

/** Whether every conflicting background skill has a replacement that is still free. */
export function bgReplacementsComplete(state: WizardState): boolean {
  return bgSkillConflicts(state).every(c => {
    const r = state.bgSkillReplacements[c];
    return !!r && bgReplacementOptions(state, c).includes(r);
  });
}

/** Every skill the character will be proficient in, once each: the race's, the classes', the background's. */
export function heldSkills(state: WizardState): string[] {
  const profs = safeJsonParse<{ skills?: string[] }>(state.selectedRace?.proficiencies, {});
  const all = [
    ...(profs.skills ?? []), ...resolvedRaceChoices(state).skills, ...state.selectedClassSkills,
    ...resolvedBgProfs(state).skills, ...multiclassSkillList(state), ...bgSkillReplacementList(state),
  ];
  const seen = new Set<string>();
  return all.filter(s => !seen.has(lower(s)) && !!seen.add(lower(s)));
}

// ── Lists ──

export function filteredRaces(races: Race[], search: string): Race[] {
  if (!search) return races;
  const q = search.toLowerCase();
  return races.filter(r => r.name.toLowerCase().includes(q));
}

export function filteredBackgrounds(backgrounds: Background[], search: string): Background[] {
  if (!search) return backgrounds;
  const q = search.toLowerCase();
  return backgrounds.filter(b => b.name.toLowerCase().includes(q));
}

// ── Ability scores ──

export function racialBonuses(state: WizardState): Record<string, number> {
  const map: Record<string, number> = {};
  state.bonusAssignments.forEach(a => { if (a.ability) map[a.ability] = (map[a.ability] || 0) + a.bonus; });
  return map;
}

/** The scores with the race's bonuses: the standard array's as assigned, an unassigned score counting as 10. */
export function finalScores(state: WizardState): AbilityScores {
  const base = state.abilityMethod === 'standard'
    ? Object.fromEntries(ABILITIES.map(a => [a, state.standardAssignments[a] ?? 10])) as AbilityScores
    : state.scores;
  const result: AbilityScores = { ...base };
  for (const [ability, bonus] of Object.entries(racialBonuses(state))) {
    if (ability in result) result[ability as keyof AbilityScores] += bonus;
  }
  return result;
}

/** The point-buy cost of the scores (PHB p.13). */
export function pointBuyTotal(scores: AbilityScores): number {
  let total = 0;
  for (const a of ABILITIES) {
    const s = scores[a];
    if (s >= 8 && s <= 13) total += s - 8;
    else if (s === 14) total += 7;
    else if (s === 15) total += 9;
  }
  return total;
}

// ── Navigation ──

/**
 * Whether the current step's choices let the player go on. The Equipment step needs the chosen class's and
 * background's starting equipment, `kits`.
 */
export function canAdvance(state: WizardState, kits: WizardKits = {}): boolean {
  switch (currentStepName(state)) {
    case 'Basic Info': return state.name.trim().length > 0;
    case 'Race': {
      if (!state.selectedRace) return false;
      const bonusesOk = isVanillaHuman(state.selectedRace) || state.bonusAssignments.length === 0
        || state.bonusAssignments.every(a => a.ability !== null);
      return bonusesOk && raceChoicesComplete(state);
    }
    case 'Class': {
      if (!state.selectedClass) return false;
      const totalClassLevels = state.classEntries.reduce((s, e) => s + e.level, 0);
      if (totalClassLevels !== state.level) return false;
      if (firstLevelSubclass(state) && !state.classEntries[0].subclass) return false;
      const skills = classSkillChoices(state);
      if (skills.count > 0 && state.selectedClassSkills.length < skills.count) return false;
      // A multiclass's skill (PHB p.164) is a 1st-level choice of that class, made here (D226)
      return multiclassSkillsComplete(state);
    }
    case 'Ability Scores':
      if (state.abilityMethod === 'standard') return Object.values(state.standardAssignments).every(v => v !== null);
      if (state.abilityMethod === 'pointbuy') return pointBuyTotal(state.scores) <= 27;
      return true;
    case 'Background': return state.selectedBackground !== null && bgChoicesComplete(state) && bgReplacementsComplete(state);
    case 'Equipment': return equipmentComplete(state, kits);
    default: return true;
  }
}

/**
 * The first step, Review aside, whose choices are not complete, or null when every one is. Create waits for it, and
 * a restored draft opens on it (D226).
 */
export function firstIncompleteStep(state: WizardState, kits: WizardKits = {}): number | null {
  const steps = wizardSteps(state);
  for (let i = 0; i < steps.length - 1; i++) {
    if (!canAdvance({ ...state, step: i }, kits)) return i;
  }
  return null;
}

// ── The create request ──

/**
 * The first class's 1st-level cantrips and spells, and the race's spells; the server counts the class's as its
 * 1st level's choices.
 */
export function spellsKnown(state: WizardState): SpellEntry[] | undefined {
  const { selectedClass, selectedRace } = state;
  const entries: SpellEntry[] = [];
  if (selectedClass?.isSpellcaster) {
    const source = `class:${selectedClass.name}`;
    for (const s of state.selectedCantrips) entries.push({ name: s.name, level: 0, source });
    for (const s of state.selectedSpells) {
      const isWiz = selectedClass.name === 'Wizard';
      entries.push({ name: s.name, level: s.level, source, ...(selectedClass.isPreparedCaster && !isWiz ? { prepared: true } : {}) });
    }
  }
  if (selectedRace?.additionalSpells) {
    const raceSpells = safeJsonParse<{ fixedSpells?: Array<{ name: string; level: number; atWill?: boolean; usesPerLongRest?: number; unlocksAtLevel?: number }> }>(selectedRace.additionalSpells, {});
    const raceSource = `race:${selectedRace.name}`;
    for (const s of raceSpells.fixedSpells ?? []) {
      if (!s.unlocksAtLevel || s.unlocksAtLevel <= state.level) {
        entries.push({ name: s.name, level: s.level ?? 0, source: raceSource, ...(s.atWill ? { atWill: true } : {}), ...(s.usesPerLongRest ? { usesPerLongRest: s.usesPerLongRest } : {}), ...(s.unlocksAtLevel ? { unlocksAtLevel: s.unlocksAtLevel } : {}) });
      }
    }
  }
  return entries.length > 0 ? entries : undefined;
}

/**
 * The create request, or null until a race, class and background are chosen. It sends choices only: hit points,
 * speed, armour class, initiative, Hit Dice, slots and spell DCs are the server's to work out (D167). With the
 * class's and background's `kits` it carries the starting equipment and coins chosen from them.
 */
export function buildCreateRequest(state: WizardState, kits?: WizardKits): CharacterCreateRequest | null {
  const { selectedRace, selectedClass, selectedBackground, classEntries } = state;
  if (!selectedRace || !selectedClass || !selectedBackground) return null;
  const raceChoices = resolvedRaceChoices(state);
  const bgProfs = resolvedBgProfs(state);
  const scores = finalScores(state);
  const savingThrows = safeJsonParse<string[]>(selectedClass.savingThrowProficiencies, []);
  // A "choose: ..." entry is the race's choice, which the race choices answer, not a resistance of its own
  const resistances = [...safeJsonParse<string[]>(selectedRace.resistances, []).filter(r => !r.startsWith('choose:')),
    ...raceChoices.resistances];
  const raceProfs = safeJsonParse<{ skills?: string[]; languages?: string[]; tools?: string[]; weapons?: string[] }>(selectedRace.proficiencies, {});
  const isMulticlass = classEntries.length > 1;
  const classArmor = [...safeJsonParse<string[]>(selectedClass.armorProficiencies, [])];
  const classWeapons = [...safeJsonParse<string[]>(selectedClass.weaponProficiencies, [])];
  const classTools = [...safeJsonParse<string[]>(selectedClass.toolProficiencies, [])];
  for (let i = 1; i < classEntries.length; i++) {
    const mc = classEntries[i].cls;
    if (!mc.multiclassProficiencies) continue;
    const mcProfs = safeJsonParse<{ armor?: string[]; weapons?: string[]; tools?: string[]; skills?: unknown }>(mc.multiclassProficiencies, {});
    if (mcProfs.armor) classArmor.push(...mcProfs.armor);
    if (mcProfs.weapons) classWeapons.push(...mcProfs.weapons);
    if (mcProfs.tools) classTools.push(...mcProfs.tools);
  }
  // A background skill already held is replaced by the skill chosen for it (PHB p.125, D226)
  const allSkills = heldSkills(state);
  const allWeapons = [...(raceProfs.weapons ?? []), ...raceChoices.weapons, ...classWeapons];
  const allTools = [...(raceProfs.tools ?? []), ...raceChoices.tools, ...bgProfs.tools, ...classTools];
  const allLanguages = [...(raceProfs.languages ?? []), ...raceChoices.languages, ...bgProfs.languages];
  // The ability the race lets the player choose for its spells (D159), if any; the server works out the class's
  // ability otherwise
  const spellcastingAbility: string | undefined = raceChoices.spellAbility ?? undefined;
  // Only a subclass the first class chooses at 1st level goes with the request; every other is chosen on the sheet
  const firstSubclassId = firstLevelSubclass(state) ? classEntries[0]?.subclass?.id : undefined;
  const multiclassClassEntries = isMulticlass
    ? classEntries.map((e, i) => ({ classId: e.cls.id, subclassId: i === 0 ? firstSubclassId ?? null : null, level: e.level }))
    : undefined;
  return {
    name: state.name.trim(), level: state.level, raceId: selectedRace.id, classId: selectedClass.id,
    subclassId: firstSubclassId,
    backgroundId: selectedBackground.id, alignment: state.alignment || undefined,
    abilityScoreMethod: state.abilityMethod,
    racialAbilityBonuses: state.bonusAssignments.map(a => ({ ability: a.ability ? ABILITY_LABELS[a.ability] : null, bonus: a.bonus })),
    strength: scores.strength, dexterity: scores.dexterity, constitution: scores.constitution,
    intelligence: scores.intelligence, wisdom: scores.wisdom, charisma: scores.charisma,
    savingThrowProficiencies: savingThrows,
    skillProficiencies: allSkills.length > 0 ? [...new Set(allSkills)] : undefined,
    skillExpertises: state.selectedExpertise.length > 0 ? state.selectedExpertise : undefined,
    armorProficiencies: classArmor.length > 0 ? [...new Set(classArmor)] : undefined,
    weaponProficiencies: allWeapons.length > 0 ? [...new Set(allWeapons)] : undefined,
    toolProficiencies: allTools.length > 0 ? [...new Set(allTools)] : undefined,
    languageProficiencies: allLanguages.length > 0 ? [...new Set(allLanguages)] : undefined,
    damageResistances: resistances.length > 0 ? resistances : undefined,
    spellcastingAbility,
    ...(raceChoices.draconicAncestry ? { draconicAncestry: raceChoices.draconicAncestry } : {}),
    spellsKnown: spellsKnown(state),
    multiclassClassEntries,
    ...(kits ? kitFields(state, kits) : {}),
  };
}

// The starting equipment and coins, each left out when there is none
function kitFields(state: WizardState, kits: WizardKits): Pick<CharacterCreateRequest, 'equipment' | 'currency'> {
  const { equipment, currency } = startingInventory(state, kits);
  return {
    ...(equipment.length > 0 ? { equipment } : {}),
    ...(Object.values(currency).some(coins => coins > 0) ? { currency } : {}),
  };
}
