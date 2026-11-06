import { describe, it, expect } from 'vitest';
import {
  initialWizardState, rebalanceClassLevels, wizardReducer, type WizardAction, type WizardState,
} from '../wizardReducer';
import type { ClassEntry } from '../types';
import {
  background, cleric, evocation, fighter, highElf, human, lifeDomain, rogue, sage, soldier, spell, variantHuman,
  wizard,
} from './fixtures';

function reduce(state: WizardState, ...actions: WizardAction[]): WizardState {
  return actions.reduce(wizardReducer, state);
}

function entry(c: ClassEntry['cls'], level: number, overrides: Partial<ClassEntry> = {}): ClassEntry {
  return { cls: c, level, subclass: null, subclasses: [], ...overrides };
}

/** A character of `level` whose first class has loaded, as choosing it in the wizard leaves it. */
function withClass(c: ClassEntry['cls'], level = 1, state: WizardState = initialWizardState): WizardState {
  return reduce({ ...state, level },
    { type: 'selectClass', cls: c },
    { type: 'primaryClassLoaded', cls: c, subclasses: c.id === cleric.id ? [lifeDomain] : [] });
}

const levels = (state: WizardState) => state.classEntries.map(e => `${e.cls.name} ${e.level}`);

describe('wizardReducer: basic info', () => {
  it('sets the step, name and alignment', () => {
    const state = reduce(initialWizardState,
      { type: 'setStep', step: 3 }, { type: 'setName', name: 'Pip' }, { type: 'setAlignment', alignment: 'Chaotic Good' });

    expect(state).toMatchObject({ step: 3, name: 'Pip', alignment: 'Chaotic Good' });
  });

  it('sets the level with no class chosen', () => {
    expect(reduce(initialWizardState, { type: 'setLevel', level: 7 })).toMatchObject({ level: 7, classEntries: [] });
  });
});

describe('wizardReducer: levels rebalance the classes', () => {
  it('gives a higher level to the first class', () => {
    const state = reduce(withClass(fighter, 3), { type: 'addMulticlass', cls: wizard, subclasses: [] },
      { type: 'setLevel', level: 6 });

    expect(levels(state)).toEqual(['Fighter 5', 'Wizard 1']);
  });

  it('takes a lower level from the first class while it keeps at least 1', () => {
    const state = reduce(withClass(fighter, 5), { type: 'addMulticlass', cls: wizard, subclasses: [] },
      { type: 'setClassLevel', classId: fighter.id, level: 3 }, { type: 'setClassLevel', classId: wizard.id, level: 2 },
      { type: 'setLevel', level: 3 });

    expect(levels(state)).toEqual(['Fighter 1', 'Wizard 2']);
  });

  it('keeps the classes in order until the levels run out when the first class would fall below 1', () => {
    const entries = [entry(fighter, 1), entry(wizard, 3), entry(cleric, 2)];

    expect(rebalanceClassLevels(entries, 3).map(e => `${e.cls.name} ${e.level}`)).toEqual(['Fighter 1', 'Wizard 2']);
    expect(rebalanceClassLevels(entries, 1).map(e => `${e.cls.name} ${e.level}`)).toEqual(['Fighter 1']);
  });

  it('leaves the classes alone when they already add up to the level', () => {
    const entries = [entry(fighter, 2), entry(wizard, 1)];

    expect(rebalanceClassLevels(entries, 3)).toBe(entries);
  });

  it('drops the skills of a class the new level leaves out', () => {
    const state = reduce(withClass(fighter, 3), { type: 'addMulticlass', cls: rogue, subclasses: [] },
      { type: 'setClassLevel', classId: fighter.id, level: 1 },
      { type: 'toggleMulticlassSkill', classId: rogue.id, skill: 'Stealth', max: 1 });
    expect(levels(state)).toEqual(['Fighter 1', 'Rogue 1']);

    const lowered = reduce(state, { type: 'setLevel', level: 1 });

    expect(levels(lowered)).toEqual(['Fighter 1']);
    expect(lowered.mcSkillSelections).toEqual({});
  });
});

describe('wizardReducer: race', () => {
  it('seeds a fixed bonus on its ability and a chosen one on none', () => {
    expect(reduce(initialWizardState, { type: 'selectRace', race: highElf }).bonusAssignments).toEqual([
      { bonus: 2, ability: 'dexterity' }, { bonus: 1, ability: 'intelligence' },
    ]);
    expect(reduce(initialWizardState, { type: 'selectRace', race: variantHuman }).bonusAssignments).toEqual([
      { bonus: 1, ability: null }, { bonus: 1, ability: null },
    ]);
  });

  it("clears the last race's choices and bonuses on a new race or none", () => {
    const chosen = reduce(initialWizardState, { type: 'selectRace', race: variantHuman },
      { type: 'toggleRaceChoice', key: 'skill_0', value: 'Perception', count: 1 },
      { type: 'assignBonus', row: 0, ability: 'wisdom' });

    const human1 = reduce(chosen, { type: 'selectRace', race: human });
    expect(human1.raceChoiceSelections).toEqual({});
    expect(human1.bonusAssignments).toHaveLength(6);

    const none = reduce(chosen, { type: 'selectRace', race: null });
    expect(none).toMatchObject({ selectedRace: null, raceChoiceSelections: {}, bonusAssignments: [] });
  });

  it('keeps the class and its skills when the race changes', () => {
    const state = reduce(withClass(fighter), { type: 'toggleClassSkill', skill: 'Athletics', max: 2 },
      { type: 'selectRace', race: human });

    expect(state.selectedClassSkills).toEqual(['Athletics']);
    expect(levels(state)).toEqual(['Fighter 1']);
  });

  it('toggles a race choice, dropping the oldest past its count', () => {
    const pick = (value: string): WizardAction => ({ type: 'toggleRaceChoice', key: 'lang_0', value, count: 2 });

    expect(reduce(initialWizardState, pick('Elvish'), pick('Giant')).raceChoiceSelections).toEqual({ lang_0: ['Elvish', 'Giant'] });
    expect(reduce(initialWizardState, pick('Elvish'), pick('Giant'), pick('Orc')).raceChoiceSelections).toEqual({ lang_0: ['Giant', 'Orc'] });
    expect(reduce(initialWizardState, pick('Elvish'), pick('Elvish')).raceChoiceSelections).toEqual({ lang_0: [] });
  });

  it('moves an ability from another bonus row to the one it is assigned to', () => {
    const state = reduce(initialWizardState, { type: 'selectRace', race: variantHuman },
      { type: 'assignBonus', row: 0, ability: 'wisdom' }, { type: 'assignBonus', row: 1, ability: 'wisdom' });

    expect(state.bonusAssignments).toEqual([{ bonus: 1, ability: null }, { bonus: 1, ability: 'wisdom' }]);
  });
});

describe('wizardReducer: ability scores', () => {
  it('starts point buy at 8 for every score, and keeps the scores when it is chosen again', () => {
    const pointBuy = reduce(initialWizardState, { type: 'setAbilityMethod', method: 'pointbuy' });
    expect(Object.values(pointBuy.scores)).toEqual([8, 8, 8, 8, 8, 8]);

    const raised = reduce(pointBuy, { type: 'setScore', ability: 'strength', value: 15 },
      { type: 'setAbilityMethod', method: 'pointbuy' });
    expect(raised.scores.strength).toBe(15);
  });

  it('keeps the scores when switching to manual or the standard array', () => {
    const state = reduce(initialWizardState, { type: 'setScore', ability: 'charisma', value: 17 },
      { type: 'setAbilityMethod', method: 'manual' }, { type: 'setAbilityMethod', method: 'standard' });

    expect(state.abilityMethod).toBe('standard');
    expect(state.scores.charisma).toBe(17);
  });

  it('sets the standard array assignments', () => {
    const assignments = { ...initialWizardState.standardAssignments, strength: 15 };

    expect(reduce(initialWizardState, { type: 'setStandardAssignments', assignments }).standardAssignments).toBe(assignments);
  });
});

describe('wizardReducer: class', () => {
  it("clears the last class's subclass, skills, Expertise and spells on a new class", () => {
    const chosen = reduce(withClass(cleric),
      { type: 'setSubclass', classId: cleric.id, subclass: lifeDomain },
      { type: 'toggleClassSkill', skill: 'Medicine', max: 2 },
      { type: 'toggleExpertise', skill: 'Medicine', max: 2 },
      { type: 'toggleCantrip', spell: spell('Guidance', 0), max: 3 },
      { type: 'toggleSpell', spell: spell('Bless', 1), max: 2 });

    const state = reduce(chosen, { type: 'selectClass', cls: wizard });

    expect(state).toMatchObject({
      selectedClass: wizard, classEntries: [], selectedClassSkills: [], mcSkillSelections: {}, selectedExpertise: [],
      selectedCantrips: [], selectedSpells: [],
    });
  });

  it('clears everything about the class when none is chosen', () => {
    const state = reduce(withClass(fighter), { type: 'toggleClassSkill', skill: 'Athletics', max: 2 },
      { type: 'selectClass', cls: null });

    expect(state).toMatchObject({ selectedClass: null, classEntries: [], selectedClassSkills: [] });
  });

  it("keeps the race, scores and background when the class changes", () => {
    const state = reduce(initialWizardState, { type: 'selectRace', race: highElf },
      { type: 'setScore', ability: 'wisdom', value: 14 }, { type: 'selectBackground', background: sage },
      { type: 'selectClass', cls: fighter });

    expect(state).toMatchObject({ selectedRace: highElf, selectedBackground: sage });
    expect(state.scores.wisdom).toBe(14);
  });

  it("makes the first class's entry at the character's level once its subclasses load", () => {
    const state = reduce({ ...initialWizardState, level: 4 }, { type: 'selectClass', cls: cleric },
      { type: 'primaryClassLoaded', cls: cleric, subclasses: [lifeDomain] });

    expect(state.classEntries).toEqual([entry(cleric, 4, { subclasses: [lifeDomain] })]);
  });

  it('drops subclasses that arrive after another class was chosen', () => {
    const state = reduce(initialWizardState, { type: 'selectClass', cls: cleric }, { type: 'selectClass', cls: fighter },
      { type: 'primaryClassLoaded', cls: cleric, subclasses: [lifeDomain] });

    expect(state.classEntries).toEqual([]);
  });

  it('sets and clears a subclass', () => {
    const set = reduce(withClass(cleric), { type: 'setSubclass', classId: cleric.id, subclass: lifeDomain });
    expect(set.classEntries[0].subclass).toBe(lifeDomain);

    expect(reduce(set, { type: 'setSubclass', classId: cleric.id, subclass: null }).classEntries[0].subclass).toBeNull();
  });

  it('opens and closes the multiclassing options', () => {
    const open = reduce(initialWizardState, { type: 'toggleMulticlassExpanded' });

    expect(open.multiclassExpanded).toBe(true);
    expect(reduce(open, { type: 'toggleMulticlassExpanded' }).multiclassExpanded).toBe(false);
  });
});

describe('wizardReducer: multiclassing', () => {
  it('adds a class at level 1, taken from the first class', () => {
    const state = reduce(withClass(fighter, 5), { type: 'addMulticlass', cls: wizard, subclasses: [evocation] });

    expect(levels(state)).toEqual(['Fighter 4', 'Wizard 1']);
    expect(state.classEntries[1].subclasses).toEqual([evocation]);
  });

  it('adds a class only once, and only after the first has loaded', () => {
    const twice = reduce(withClass(fighter, 5), { type: 'addMulticlass', cls: wizard, subclasses: [] },
      { type: 'addMulticlass', cls: wizard, subclasses: [] });
    expect(levels(twice)).toEqual(['Fighter 4', 'Wizard 1']);

    const unloaded = reduce({ ...initialWizardState, level: 5 }, { type: 'selectClass', cls: fighter },
      { type: 'addMulticlass', cls: wizard, subclasses: [] });
    expect(unloaded.classEntries).toEqual([]);
  });

  it('gives a removed class its levels back to the first, with its skills gone', () => {
    const state = reduce(withClass(fighter, 5), { type: 'addMulticlass', cls: rogue, subclasses: [] },
      { type: 'setClassLevel', classId: fighter.id, level: 3 }, { type: 'setClassLevel', classId: rogue.id, level: 2 },
      { type: 'toggleMulticlassSkill', classId: rogue.id, skill: 'Stealth', max: 1 },
      { type: 'removeMulticlass', classId: rogue.id });

    expect(levels(state)).toEqual(['Fighter 5']);
    expect(state.mcSkillSelections).toEqual({});
  });

  it('never removes the first class', () => {
    const state = withClass(fighter, 5);

    expect(reduce(state, { type: 'removeMulticlass', classId: fighter.id })).toBe(state);
    expect(reduce(state, { type: 'removeMulticlass', classId: 'cls-unknown' })).toBe(state);
  });

  it("holds a class's level between 1 and what the others leave of the character's level", () => {
    const state = reduce(withClass(fighter, 5), { type: 'addMulticlass', cls: wizard, subclasses: [] });

    expect(levels(reduce(state, { type: 'setClassLevel', classId: wizard.id, level: 9 }))).toEqual(['Fighter 4', 'Wizard 1']);
    expect(levels(reduce(state, { type: 'setClassLevel', classId: fighter.id, level: 3 }))).toEqual(['Fighter 3', 'Wizard 1']);
    expect(levels(reduce(state, { type: 'setClassLevel', classId: fighter.id, level: 0 }))).toEqual(['Fighter 1', 'Wizard 1']);
    expect(reduce(state, { type: 'setClassLevel', classId: 'cls-unknown', level: 2 })).toBe(state);
  });
});

describe('wizardReducer: skills and Expertise', () => {
  it('toggles a class skill up to the number the class gives', () => {
    const pick = (skill: string): WizardAction => ({ type: 'toggleClassSkill', skill, max: 2 });

    expect(reduce(initialWizardState, pick('Athletics'), pick('Perception'), pick('Survival')).selectedClassSkills)
      .toEqual(['Athletics', 'Perception']);
    expect(reduce(initialWizardState, pick('Athletics'), pick('Athletics')).selectedClassSkills).toEqual([]);
  });

  it("toggles a multiclass's skills up to its number, apart from the other classes'", () => {
    const pick = (classId: string, skill: string): WizardAction => ({ type: 'toggleMulticlassSkill', classId, skill, max: 1 });
    const state = reduce(initialWizardState, pick(rogue.id, 'Stealth'), pick(rogue.id, 'Deception'), pick('cls-bard', 'Insight'));

    expect(state.mcSkillSelections).toEqual({ [rogue.id]: ['Stealth'], 'cls-bard': ['Insight'] });
    expect(reduce(state, pick(rogue.id, 'Stealth')).mcSkillSelections[rogue.id]).toEqual([]);
  });

  it('toggles Expertise up to its number', () => {
    const pick = (skill: string): WizardAction => ({ type: 'toggleExpertise', skill, max: 2 });

    expect(reduce(initialWizardState, pick('Stealth'), pick('Insight'), pick('Deception'), pick('Stealth')).selectedExpertise)
      .toEqual(['Insight']);
  });
});

describe('wizardReducer: background', () => {
  it("clears the last background's choices on a new background or none", () => {
    const chosen = reduce(initialWizardState, { type: 'selectBackground', background: sage },
      { type: 'toggleBgProfChoice', key: 'lang_choose_0', value: 'Draconic', count: 2 },
      { type: 'toggleBgSetChoice', key: 'tool_set', index: 1 });

    expect(reduce(chosen, { type: 'selectBackground', background: soldier }))
      .toMatchObject({ selectedBackground: soldier, bgProfChoices: {}, bgSetChoices: {} });
    expect(reduce(chosen, { type: 'selectBackground', background: null }))
      .toMatchObject({ selectedBackground: null, bgProfChoices: {}, bgSetChoices: {} });
  });

  it('toggles a choice, dropping the oldest past its count, and a set', () => {
    const pick = (value: string): WizardAction => ({ type: 'toggleBgProfChoice', key: 'lang_choose_0', value, count: 2 });
    const set = (index: number): WizardAction => ({ type: 'toggleBgSetChoice', key: 'tool_set', index });

    expect(reduce(initialWizardState, pick('Draconic'), pick('Dwarvish'), pick('Elvish')).bgProfChoices)
      .toEqual({ lang_choose_0: ['Dwarvish', 'Elvish'] });
    expect(reduce(initialWizardState, set(1)).bgSetChoices).toEqual({ tool_set: 1 });
    expect(reduce(initialWizardState, set(1), set(0)).bgSetChoices).toEqual({ tool_set: 0 });
    expect(reduce(initialWizardState, set(1), set(1)).bgSetChoices).toEqual({ tool_set: null });
  });

  it('keeps the searches as typed', () => {
    const state = reduce(initialWizardState, { type: 'setRaceSearch', search: 'elf' },
      { type: 'setBackgroundSearch', search: 'sa' });

    expect(state).toMatchObject({ raceSearch: 'elf', bgSearch: 'sa' });
  });
});

describe('wizardReducer: spells', () => {
  it('toggles cantrips and spells up to their numbers, telling spells apart by ID', () => {
    const fireBolt = spell('Fire Bolt', 0);
    const state = reduce(initialWizardState,
      { type: 'toggleCantrip', spell: fireBolt, max: 1 },
      { type: 'toggleCantrip', spell: spell('Light', 0), max: 1 },
      { type: 'toggleSpell', spell: spell('Shield', 1), max: Infinity },
      { type: 'toggleSpell', spell: spell('Sleep', 1), max: Infinity });

    expect(state.selectedCantrips.map(s => s.name)).toEqual(['Fire Bolt']);
    expect(state.selectedSpells.map(s => s.name)).toEqual(['Shield', 'Sleep']);
    expect(reduce(state, { type: 'toggleCantrip', spell: { ...fireBolt }, max: 1 }).selectedCantrips).toEqual([]);
  });
});

describe('wizardReducer: restoring a draft', () => {
  const refs = { races: [human, highElf], classes: [fighter, wizard], backgrounds: [soldier, sage] };

  it("restores every field, choosing the race, class and background as the player would", () => {
    const draft = {
      name: 'Pip', alignment: 'Chaotic Good', step: 4, abilityMethod: 'standard' as const,
      scores: { strength: 10, dexterity: 10, constitution: 10, intelligence: 10, wisdom: 10, charisma: 10 },
      standardAssignments: { strength: 15, dexterity: 14, constitution: 13, intelligence: 12, wisdom: 10, charisma: 8 },
      level: 2, raceId: highElf.id, classId: wizard.id, backgroundId: sage.id,
    };

    const state = reduce(initialWizardState, { type: 'restoreDraft', draft, refs });

    expect(state).toMatchObject({
      name: 'Pip', alignment: 'Chaotic Good', step: 4, abilityMethod: 'standard', scores: draft.scores,
      standardAssignments: draft.standardAssignments, level: 2,
      selectedRace: highElf, bonusAssignments: [{ bonus: 2, ability: 'dexterity' }, { bonus: 1, ability: 'intelligence' }],
      selectedClass: wizard, classEntries: [], selectedBackground: sage,
    });
  });

  it("keeps what the draft doesn't have, and skips a race, class or background no longer listed", () => {
    const before = reduce(initialWizardState, { type: 'setName', name: 'Typed' }, { type: 'selectRace', race: human });

    const state = reduce(before, {
      type: 'restoreDraft', draft: { name: '', raceId: 'race-gone', classId: fighter.id, backgroundId: background('bg-gone', 'Gone').id },
      refs,
    });

    expect(state).toMatchObject({ name: 'Typed', selectedRace: human, selectedClass: fighter, selectedBackground: null, step: 0 });
  });
});
