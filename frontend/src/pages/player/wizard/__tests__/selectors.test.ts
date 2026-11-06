import { describe, it, expect } from 'vitest';
import { initialWizardState, wizardReducer, type WizardAction, type WizardState } from '../wizardReducer';
import {
  bgChoiceReqs, bgChoicesComplete, bgFeatNames, bgSkillConflicts, buildCreateRequest, canAdvance, classSkillChoices,
  currentStepName, expertiseCount, filteredBackgrounds, filteredRaces, finalScores, firstLevelSubclass,
  hasMeaningfulData, isVanillaHuman, multiclassSkillChoices, pointBuyTotal, raceChoiceReqs, raceChoicesComplete,
  raceFeats, raceSkills, racialBonuses, resolvedBgProfs, resolvedRaceChoices, spellsKnown, toDraft, wizardSteps,
} from '../selectors';
import {
  background, cleric, fighter, highElf, human, lifeDomain, race, rogue, sage, soldier, spell, variantHuman, wizard,
} from './fixtures';

function reduce(state: WizardState, ...actions: WizardAction[]): WizardState {
  return actions.reduce(wizardReducer, state);
}

function withClass(c: typeof fighter, level = 1, state: WizardState = initialWizardState): WizardState {
  return reduce({ ...state, level },
    { type: 'selectClass', cls: c },
    { type: 'primaryClassLoaded', cls: c, subclasses: c.id === cleric.id ? [lifeDomain] : [] });
}

const STANDARD = { strength: 15, dexterity: 14, constitution: 13, intelligence: 12, wisdom: 10, charisma: 8 };

describe('wizardSteps', () => {
  it('has a Spells step only when the first class casts', () => {
    expect(wizardSteps(initialWizardState)).toEqual(['Basic Info', 'Race', 'Ability Scores', 'Class', 'Background', 'Review']);
    expect(wizardSteps(withClass(wizard))).toContain('Spells');
    expect(wizardSteps(reduce(withClass(fighter, 3), { type: 'addMulticlass', cls: wizard, subclasses: [] })))
      .not.toContain('Spells');
  });

  it('names the current step from the steps the class leaves', () => {
    expect(currentStepName({ ...withClass(fighter), step: 5 })).toBe('Review');
    expect(currentStepName({ ...withClass(wizard), step: 5 })).toBe('Spells');
  });
});

describe('drafts', () => {
  it('saves the fields and order saved drafts have always had', () => {
    const state = reduce(withClass(fighter, 2), { type: 'setName', name: 'Pip' }, { type: 'setAlignment', alignment: 'Chaotic Good' },
      { type: 'setStep', step: 4 }, { type: 'setStandardAssignments', assignments: STANDARD },
      { type: 'selectRace', race: highElf }, { type: 'selectBackground', background: sage });

    expect(JSON.stringify(toDraft(state))).toBe('{"name":"Pip","alignment":"Chaotic Good","step":4,"abilityMethod":"standard",'
      + '"scores":{"strength":10,"dexterity":10,"constitution":10,"intelligence":10,"wisdom":10,"charisma":10},'
      + '"standardAssignments":{"strength":15,"dexterity":14,"constitution":13,"intelligence":12,"wisdom":10,"charisma":8},'
      + '"level":2,"raceId":"race-high-elf","classId":"cls-fighter","backgroundId":"bg-sage"}');
    expect(toDraft(initialWizardState)).toMatchObject({ raceId: null, classId: null, backgroundId: null });
  });

  it('keeps a draft once there is a name, race or class', () => {
    expect(hasMeaningfulData(initialWizardState)).toBe(false);
    expect(hasMeaningfulData({ ...initialWizardState, name: '  ' })).toBe(false);
    expect(hasMeaningfulData({ ...initialWizardState, name: 'Pip' })).toBe(true);
    expect(hasMeaningfulData({ ...initialWizardState, selectedRace: human })).toBe(true);
    expect(hasMeaningfulData({ ...initialWizardState, selectedClass: fighter })).toBe(true);
    expect(hasMeaningfulData({ ...initialWizardState, selectedBackground: sage })).toBe(false);
  });
});

describe('feats', () => {
  it("counts a race's feats and names a background's", () => {
    expect(raceFeats(variantHuman)).toBe(1);
    expect(raceFeats(human)).toBe(0);
    expect(raceFeats(null)).toBe(0);
    expect(bgFeatNames(background('bg-x', 'X', { feats: '["Alert","Lucky"]' }))).toEqual(['Alert', 'Lucky']);
    expect(bgFeatNames(sage)).toEqual([]);
  });
});

describe('class selectors', () => {
  it("reads the first class's skill choices", () => {
    expect(classSkillChoices(withClass(fighter))).toEqual({ count: 2, from: ['Acrobatics', 'Athletics', 'Perception', 'Survival'] });
    expect(classSkillChoices(initialWizardState)).toEqual({ from: [], count: 0 });
  });

  it("offers a multiclass's skills by its class ID, and none for the first class or a class without", () => {
    const state = reduce(withClass(fighter, 3), { type: 'addMulticlass', cls: rogue, subclasses: [] },
      { type: 'addMulticlass', cls: wizard, subclasses: [] });

    expect(multiclassSkillChoices(state)).toEqual({ [rogue.id]: { from: ['Deception', 'Stealth'], count: 1 } });
    expect(multiclassSkillChoices(withClass(rogue))).toEqual({});
  });

  it("gives a first-class rogue's 1st-level Expertise only", () => {
    expect(expertiseCount(withClass(rogue))).toBe(2);
    expect(expertiseCount(reduce(withClass(fighter, 2), { type: 'addMulticlass', cls: rogue, subclasses: [] }))).toBe(0);
  });

  it('chooses a subclass at 1st level only for a first class whose subclass comes then and has some', () => {
    expect(firstLevelSubclass(withClass(cleric))).toBe(true);
    expect(firstLevelSubclass(withClass(wizard))).toBe(false);
    expect(firstLevelSubclass(reduce(initialWizardState, { type: 'selectClass', cls: cleric }))).toBe(false);
  });
});

describe('race selectors', () => {
  it("lists the race's choices, offering languages and skills it doesn't already give", () => {
    const reqs = raceChoiceReqs(variantHuman);

    expect(reqs.map(r => [r.key, r.label, r.count])).toEqual([['lang_0', 'Language', 1], ['skill_1', 'Skill Proficiency', 1]]);
    expect(reqs[0].options).not.toContain('Common');
    expect(raceChoiceReqs(highElf)).toEqual([]);
  });

  it("lists tools, weapons, resistances and a spellcasting ability", () => {
    const dragon = race('race-x', 'X', {
      raceChoices: JSON.stringify({
        tools: [{ anyArtisansTool: 1 }], weapons: [{ from: ['Longsword', 'Rapier'], count: 1 }],
        resistances: [{ from: ['fire', 'cold'] }], spellAbility: ['INT', 'WIS', 'CHA'],
      }),
    });

    expect(raceChoiceReqs(dragon).map(r => r.key)).toEqual(['tool_0', 'weapon_1', 'resist_2', 'spellAbility']);
  });

  it('is complete when every choice has its count, and resolves them by kind', () => {
    const state = reduce(initialWizardState, { type: 'selectRace', race: variantHuman },
      { type: 'toggleRaceChoice', key: 'lang_0', value: 'Giant', count: 1 });
    expect(raceChoicesComplete(state)).toBe(false);

    const done = reduce(state, { type: 'toggleRaceChoice', key: 'skill_1', value: 'Stealth', count: 1 });
    expect(raceChoicesComplete(done)).toBe(true);
    expect(resolvedRaceChoices(done)).toEqual({ languages: ['Giant'], skills: ['Stealth'], tools: [], weapons: [], resistances: [], spellAbility: null });
    expect(raceSkills(done)).toEqual(new Set(['stealth']));
    expect(raceSkills(reduce(initialWizardState, { type: 'selectRace', race: highElf }))).toEqual(new Set(['perception']));
  });

  it("knows a human's +1 to every ability needs no assigning", () => {
    expect(isVanillaHuman(human)).toBe(true);
    expect(isVanillaHuman(variantHuman)).toBe(false);
    expect(isVanillaHuman(null)).toBe(false);
  });

  it('filters the races and backgrounds by name, ignoring case', () => {
    expect(filteredRaces([human, highElf], 'ELF')).toEqual([highElf]);
    expect(filteredRaces([human, highElf], '')).toEqual([human, highElf]);
    expect(filteredBackgrounds([soldier, sage], 'sa')).toEqual([sage]);
  });
});

describe('background selectors', () => {
  const guild = background('bg-guild', 'Guild Artisan', {
    skillProficiencies: '["Insight","Persuasion"]',
    toolProficiencies: '["Any Artisan\'s Tool"]',
    languageProficiencies: '[{"anyStandard":1}]',
  });

  it("lists the background's choices", () => {
    expect(bgChoiceReqs(guild).map(r => [r.key, r.label])).toEqual([
      ['tool_choose_0', 'Tool Proficiency'], ['lang_choose_0', 'Language'],
    ]);
    expect(bgChoiceReqs(soldier)).toEqual([]);
  });

  it('is complete when every choice has its count, and resolves the fixed and chosen proficiencies', () => {
    const state = reduce(initialWizardState, { type: 'selectBackground', background: guild },
      { type: 'toggleBgProfChoice', key: 'tool_choose_0', value: "Smith's Tools", count: 1 });
    expect(bgChoicesComplete(state)).toBe(false);

    const done = reduce(state, { type: 'toggleBgProfChoice', key: 'lang_choose_0', value: 'Dwarvish', count: 1 });
    expect(bgChoicesComplete(done)).toBe(true);
    expect(resolvedBgProfs(done)).toEqual({ skills: ['Insight', 'Persuasion'], tools: ["Smith's Tools"], languages: ['Dwarvish'] });
  });

  it('resolves a chosen set', () => {
    const outlander = background('bg-x', 'X', { toolProficiencies: '[{"chooseSet":[["Lute"],["Flute","Drum"]]}]' });
    const state = reduce(initialWizardState, { type: 'selectBackground', background: outlander },
      { type: 'toggleBgSetChoice', key: 'tool_set', index: 1 });

    expect(bgChoiceReqs(outlander)).toEqual([{ key: 'tool_set', label: 'Tool Proficiencies', type: 'chooseSet', sets: [['Lute'], ['Flute', 'Drum']] }]);
    expect(resolvedBgProfs(state).tools).toEqual(['Flute', 'Drum']);
  });

  it('names the background skills the race or class already gives', () => {
    const state = reduce(withClass(fighter), { type: 'toggleClassSkill', skill: 'Athletics', max: 2 },
      { type: 'selectBackground', background: soldier });

    expect(bgSkillConflicts(state)).toEqual(['Athletics']);
  });
});

describe('ability score selectors', () => {
  it("adds the race's bonuses to the standard array, an unassigned score counting as 10", () => {
    const state = reduce(initialWizardState, { type: 'selectRace', race: highElf },
      { type: 'setStandardAssignments', assignments: { ...STANDARD, charisma: null } });

    expect(racialBonuses(state)).toEqual({ dexterity: 2, intelligence: 1 });
    expect(finalScores(state)).toEqual({ ...STANDARD, dexterity: 16, intelligence: 13, charisma: 10 });
  });

  it('adds them to the scores in point buy and manual', () => {
    const state = reduce(initialWizardState, { type: 'selectRace', race: highElf }, { type: 'setAbilityMethod', method: 'pointbuy' });

    expect(finalScores(state)).toEqual({ strength: 8, dexterity: 10, constitution: 8, intelligence: 9, wisdom: 8, charisma: 8 });
  });

  it('costs point buy as the PHB does (p.13)', () => {
    expect(pointBuyTotal({ strength: 8, dexterity: 8, constitution: 8, intelligence: 8, wisdom: 8, charisma: 8 })).toBe(0);
    expect(pointBuyTotal({ strength: 15, dexterity: 15, constitution: 15, intelligence: 8, wisdom: 8, charisma: 8 })).toBe(27);
    expect(pointBuyTotal({ strength: 14, dexterity: 13, constitution: 8, intelligence: 8, wisdom: 8, charisma: 8 })).toBe(12);
  });
});

describe('canAdvance', () => {
  const at = (state: WizardState, name: string) => ({ ...state, step: wizardSteps(state).indexOf(name) });

  it('needs a name', () => {
    expect(canAdvance(at(initialWizardState, 'Basic Info'))).toBe(false);
    expect(canAdvance(at({ ...initialWizardState, name: 'Pip' }, 'Basic Info'))).toBe(true);
  });

  it("needs a race with its bonuses assigned and its choices made, a human's needing none", () => {
    expect(canAdvance(at(initialWizardState, 'Race'))).toBe(false);
    expect(canAdvance(at(reduce(initialWizardState, { type: 'selectRace', race: highElf }), 'Race'))).toBe(true);
    const variant = reduce(initialWizardState, { type: 'selectRace', race: variantHuman },
      { type: 'toggleRaceChoice', key: 'lang_0', value: 'Giant', count: 1 },
      { type: 'toggleRaceChoice', key: 'skill_1', value: 'Stealth', count: 1 },
      { type: 'assignBonus', row: 0, ability: 'wisdom' });
    expect(canAdvance(at(variant, 'Race'))).toBe(false);
    expect(canAdvance(at(reduce(variant, { type: 'assignBonus', row: 1, ability: 'constitution' }), 'Race'))).toBe(true);
    const human1 = reduce(initialWizardState, { type: 'selectRace', race: human },
      { type: 'toggleRaceChoice', key: 'lang_0', value: 'Giant', count: 1 });
    expect(canAdvance(at(human1, 'Race'))).toBe(true);
  });

  it('needs the standard array assigned, or point buy within 27', () => {
    expect(canAdvance(at(initialWizardState, 'Ability Scores'))).toBe(false);
    expect(canAdvance(at({ ...initialWizardState, standardAssignments: STANDARD }, 'Ability Scores'))).toBe(true);
    const pointBuy = reduce(initialWizardState, { type: 'setAbilityMethod', method: 'pointbuy' });
    expect(canAdvance(at(pointBuy, 'Ability Scores'))).toBe(true);
    const over = { ...pointBuy, scores: { ...pointBuy.scores, strength: 15, dexterity: 15, constitution: 15, wisdom: 9 } };
    expect(canAdvance(at(over, 'Ability Scores'))).toBe(false);
  });

  it("needs a loaded class whose levels add up, its 1st-level subclass and its skills", () => {
    expect(canAdvance(at(reduce(initialWizardState, { type: 'selectClass', cls: fighter }), 'Class'))).toBe(false);
    const fighter1 = withClass(fighter);
    expect(canAdvance(at(fighter1, 'Class'))).toBe(false);
    expect(canAdvance(at(reduce(fighter1, { type: 'toggleClassSkill', skill: 'Athletics', max: 2 },
      { type: 'toggleClassSkill', skill: 'Survival', max: 2 }), 'Class'))).toBe(true);

    const cleric1 = reduce(withClass(cleric), { type: 'toggleClassSkill', skill: 'History', max: 2 },
      { type: 'toggleClassSkill', skill: 'Insight', max: 2 });
    expect(canAdvance(at(cleric1, 'Class'))).toBe(false);
    expect(canAdvance(at(reduce(cleric1, { type: 'setSubclass', classId: cleric.id, subclass: lifeDomain }), 'Class'))).toBe(true);

    const unbalanced = reduce(withClass(rogue, 3), { type: 'addMulticlass', cls: fighter, subclasses: [] },
      { type: 'setClassLevel', classId: rogue.id, level: 1 });
    expect(canAdvance(at({ ...unbalanced, selectedClassSkills: ['Stealth', 'Insight', 'Deception', 'Acrobatics'] }, 'Class'))).toBe(false);
  });

  it("needs a background with its choices made", () => {
    expect(canAdvance(at(initialWizardState, 'Background'))).toBe(false);
    expect(canAdvance(at(reduce(initialWizardState, { type: 'selectBackground', background: soldier }), 'Background'))).toBe(true);
    expect(canAdvance(at(reduce(initialWizardState, { type: 'selectBackground', background: sage }), 'Background'))).toBe(false);
  });
});

describe('spellsKnown', () => {
  it("sends the first class's cantrips and spells, prepared for a prepared caster other than a Wizard", () => {
    const pick = (state: WizardState) => reduce(state,
      { type: 'toggleCantrip', spell: spell('Light', 0), max: 3 }, { type: 'toggleSpell', spell: spell('Bless', 1), max: 6 });

    expect(spellsKnown(pick(withClass(cleric)))).toEqual([
      { name: 'Light', level: 0, source: 'class:Cleric' }, { name: 'Bless', level: 1, source: 'class:Cleric', prepared: true },
    ]);
    expect(spellsKnown(pick(withClass(wizard)))).toEqual([
      { name: 'Light', level: 0, source: 'class:Wizard' }, { name: 'Bless', level: 1, source: 'class:Wizard' },
    ]);
  });

  it("sends the race's spells the level has reached, and nothing when there are none", () => {
    const tiefling = race('race-tiefling', 'Tiefling', {
      additionalSpells: JSON.stringify({ fixedSpells: [
        { name: 'Thaumaturgy', level: 0, atWill: true },
        { name: 'Hellish Rebuke', level: 1, usesPerLongRest: 1, unlocksAtLevel: 3 },
      ] }),
    });
    const state = reduce(withClass(fighter), { type: 'selectRace', race: tiefling });

    expect(spellsKnown(state)).toEqual([{ name: 'Thaumaturgy', level: 0, source: 'race:Tiefling', atWill: true }]);
    expect(spellsKnown({ ...state, level: 3 })).toHaveLength(2);
    expect(spellsKnown(withClass(fighter))).toBeUndefined();
  });
});

describe('buildCreateRequest', () => {
  function cleric1(): WizardState {
    return reduce(withClass(cleric),
      { type: 'setName', name: ' Jhessail ' },
      { type: 'selectRace', race: highElf },
      { type: 'setStandardAssignments', assignments: STANDARD },
      { type: 'setSubclass', classId: cleric.id, subclass: lifeDomain },
      { type: 'toggleClassSkill', skill: 'Medicine', max: 2 },
      { type: 'toggleClassSkill', skill: 'Insight', max: 2 },
      { type: 'selectBackground', background: soldier });
  }

  it('needs a race, class and background', () => {
    expect(buildCreateRequest(withClass(fighter))).toBeNull();
    expect(buildCreateRequest(cleric1())).not.toBeNull();
  });

  it('sends the choices, with each proficiency once', () => {
    expect(buildCreateRequest(cleric1())).toEqual({
      name: 'Jhessail', level: 1, raceId: highElf.id, classId: cleric.id, subclassId: lifeDomain.id,
      backgroundId: soldier.id, alignment: undefined, abilityScoreMethod: 'standard',
      racialAbilityBonuses: [{ ability: 'DEX', bonus: 2 }, { ability: 'INT', bonus: 1 }],
      strength: 15, dexterity: 16, constitution: 13, intelligence: 13, wisdom: 10, charisma: 8,
      savingThrowProficiencies: ['WIS', 'CHA'],
      skillProficiencies: ['Perception', 'Medicine', 'Insight', 'Athletics', 'Intimidation'],
      skillExpertises: undefined,
      armorProficiencies: ['Light', 'Medium', 'Shield'],
      weaponProficiencies: ['Longsword', 'Simple'],
      toolProficiencies: ['Vehicles (Land)'],
      languageProficiencies: ['Common', 'Elvish'],
      damageResistances: undefined,
      spellcastingAbility: undefined,
      spellsKnown: undefined,
      multiclassClassEntries: undefined,
    });
  });

  it("sends each class's levels, the multiclass's proficiencies and skills, and only a 1st-level subclass", () => {
    const state = reduce(cleric1(), { type: 'setLevel', level: 3 }, { type: 'addMulticlass', cls: rogue, subclasses: [] },
      { type: 'toggleMulticlassSkill', classId: rogue.id, skill: 'Stealth', max: 1 });
    const request = buildCreateRequest(state);

    expect(request?.multiclassClassEntries).toEqual([
      { classId: cleric.id, subclassId: lifeDomain.id, level: 2 }, { classId: rogue.id, subclassId: null, level: 1 },
    ]);
    expect(request?.skillProficiencies).toContain('Stealth');
    expect(request?.toolProficiencies).toEqual(['Vehicles (Land)', "Thieves' tools"]);
    expect(buildCreateRequest(reduce(withClass(wizard, 2), { type: 'setSubclass', classId: wizard.id, subclass: null },
      { type: 'selectRace', race: human }, { type: 'selectBackground', background: sage }))?.subclassId).toBeUndefined();
  });

  it("sends a rogue's Expertise and the race's chosen spellcasting ability", () => {
    const fairy = race('race-fairy', 'Fairy', { raceChoices: JSON.stringify({ spellAbility: ['INT', 'WIS', 'CHA'] }) });
    const state = reduce(withClass(rogue), { type: 'selectRace', race: fairy },
      { type: 'toggleRaceChoice', key: 'spellAbility', value: 'WIS', count: 1 },
      { type: 'toggleExpertise', skill: 'Stealth', max: 2 }, { type: 'selectBackground', background: soldier });

    expect(buildCreateRequest(state)).toMatchObject({ skillExpertises: ['Stealth'], spellcastingAbility: 'WIS' });
  });
});
