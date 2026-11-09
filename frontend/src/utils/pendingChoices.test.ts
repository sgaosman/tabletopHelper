import { describe, it, expect } from 'vitest';
import { makeMockCharacter, makeMockPendingChoice } from '../test/mocks/handlers';
import {
  choiceLabel, choicesPending, choicesToMake, choiceTitle, hasProficiency, isSpellbookChoice, proficiencyOptions,
  proficiencyTaken,
} from './pendingChoices';

describe('pendingChoices', () => {
  it('names a choice with the class level that owes it (D159)', () => {
    expect(choiceLabel(makeMockPendingChoice())).toBe('Ability Score Improvement (Fighter 4)');
    expect(choiceLabel(makeMockPendingChoice({ type: 'SUBCLASS', classLevel: 3 }))).toBe('Subclass (Fighter 3)');
    expect(choiceLabel(makeMockPendingChoice({ type: 'EXPERTISE', className: 'Rogue', classLevel: 1 })))
      .toBe('Expertise (Rogue 1)');
    expect(choiceLabel(makeMockPendingChoice({ type: 'SPELLS', className: 'Sorcerer', classLevel: 3 })))
      .toBe('Spells (Sorcerer 3)');
  });

  it("calls a wizard's spells its spellbook, and a race's or background's feat by where it comes from", () => {
    const spellbook = makeMockPendingChoice({ type: 'SPELLS', className: 'Wizard', classLevel: 2, count: 2 });
    expect(isSpellbookChoice(spellbook)).toBe(true);
    expect(choiceTitle(spellbook)).toBe('Spellbook');
    expect(choiceLabel(spellbook)).toBe('Spellbook (Wizard 2)');
    const feat = { type: 'FEAT', classId: null, className: null, classLevel: null, characterLevel: 1 } as const;
    expect(choiceLabel(makeMockPendingChoice({ ...feat, source: 'RACE' }))).toBe('Feat (race)');
    expect(choiceLabel(makeMockPendingChoice({ ...feat, source: 'BACKGROUND' }))).toBe('Feat (background)');
  });

  it('counts the choices for the player and the DM', () => {
    expect(choicesToMake(1)).toBe('1 choice to make');
    expect(choicesToMake(3)).toBe('3 choices to make');
    expect(choicesPending(1)).toBe('1 choice pending');
    expect(choicesPending(2)).toBe('2 choices pending');
  });
  it("names a subclass's proficiency choice by what it offers (PHB p.59, XGE p.30)", () => {
    const languages = makeMockPendingChoice({ type: 'PROFICIENCY', className: 'Cleric', classLevel: 1, count: 2,
      proficiencyOptions: { languages: ['Elvish', 'Giant'] } });
    expect(choiceLabel(languages)).toBe('Languages (Cleric 1)');
    expect(choiceTitle({ ...languages, proficiencyOptions: { skills: ['Arcana'] }, expertise: true }))
      .toBe('Skills with Expertise');
    expect(choiceTitle({ ...languages, proficiencyOptions: { skills: ['History'], languages: ['Draconic'] } }))
      .toBe('Skills or languages');
    expect(proficiencyOptions({ ...languages, proficiencyOptions: { languages: ['Draconic'], skills: ['History'] } }))
      .toEqual([{ kind: 'skills', name: 'History' }, { kind: 'languages', name: 'Draconic' }]);
  });

  it('judges a proficiency held as the server does: in any case, and a weapon by its plural or category', () => {
    const character = {
      ...makeMockCharacter('char-003', 'Elra', 'Wizard', 2),
      skillProficiencies: ['history'], skillExpertises: ['Arcana'], weaponProficiencies: ['Daggers', 'Martial Weapons'],
    };
    expect(hasProficiency(character, { kind: 'skills', name: 'History' })).toBe(true);
    expect(hasProficiency(character, { kind: 'weapons', name: 'Dagger' })).toBe(true);
    expect(hasProficiency(character, { kind: 'weapons', name: 'Rapier' })).toBe(true);
    expect(hasProficiency(character, { kind: 'weapons', name: 'Club' })).toBe(false);
    const expertise = makeMockPendingChoice({ type: 'PROFICIENCY', expertise: true });
    // A Knowledge Domain's skill may already be a proficiency, but not already have Expertise
    expect(proficiencyTaken(character, expertise, { kind: 'skills', name: 'History' })).toBe(false);
    expect(proficiencyTaken(character, expertise, { kind: 'skills', name: 'Arcana' })).toBe(true);
    // Every PHB weapon has its category, ranged ones too
    expect(hasProficiency(character, { kind: 'weapons', name: 'Longbow' })).toBe(true);
    expect(hasProficiency(character, { kind: 'weapons', name: 'Shortbow' })).toBe(false);
  });

  it("lets a kensei choose a weapon it is proficient with, but not one that is a kensei weapon already (XGE p.34)", () => {
    const monk = {
      ...makeMockCharacter('char-005', 'Shiro', 'Monk', 6),
      weaponProficiencies: ['Simple Weapons', 'Shortswords', 'Longbow'], kenseiWeapons: ['Shortsword', 'longbow'],
    };
    const kensei = makeMockPendingChoice({
      type: 'PROFICIENCY', className: 'Monk', classLevel: 6, characterLevel: 6, kenseiWeapons: true,
      proficiencyOptions: { weapons: ['Spear', 'Shortsword', 'Longbow', 'Longsword'] },
    });
    expect(proficiencyTaken(monk, kensei, { kind: 'weapons', name: 'Spear' })).toBe(false);
    expect(proficiencyTaken(monk, kensei, { kind: 'weapons', name: 'Longbow' })).toBe(true);
    expect(proficiencyTaken(monk, kensei, { kind: 'weapons', name: 'Longsword' })).toBe(false);
    expect(choiceTitle(kensei)).toBe('Kensei Weapon');
    expect(choiceLabel(kensei)).toBe('Kensei Weapon (Monk 6)');
  });
});
