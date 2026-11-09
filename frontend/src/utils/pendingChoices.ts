import type { PendingChoice, PendingChoiceType, PlayerCharacter } from '../types/character';

/** What each kind of Pending Choice is called on the sheet (D159). */
const TITLES: Record<PendingChoiceType, string> = {
  SUBCLASS: 'Subclass',
  FIGHTING_STYLE: 'Fighting Style',
  METAMAGIC: 'Metamagic',
  PROFICIENCY: 'Proficiencies',
  ASI: 'Ability Score Improvement',
  FEAT: 'Feat',
  EXPERTISE: 'Expertise',
  CANTRIPS: 'Cantrips',
  SPELLS: 'Spells',
};

/**
 * The 1st-level choices of a character's first class that the creation wizard doesn't ask for, made on the sheet
 * after creation (D159): a Fighter's Fighting Style (PHB p.72). The wizard makes the rest of the 1st level's: skills,
 * a subclass chosen at 1st level, a Rogue's Expertise, and cantrips and spells. A Paladin's and a Ranger's Fighting
 * Style come at 2nd level.
 */
export function firstLevelSheetChoices(className: string | null | undefined): string[] {
  return className === 'Fighter' ? ['Your Fighting Style'] : [];
}

/** Whether a SPELLS choice copies spells into a wizard's spellbook rather than teaching the class them. */
export function isSpellbookChoice(choice: PendingChoice): boolean {
  return choice.type === 'SPELLS' && choice.className === 'Wizard';
}

/** A kind of proficiency a subclass's PROFICIENCY choice offers, as the server names it. */
export type ProficiencyKind = 'skills' | 'languages' | 'tools' | 'weapons' | 'armor';

/** One proficiency a PROFICIENCY choice offers. */
export interface ProficiencyOption {
  kind: ProficiencyKind;
  name: string;
}

const KIND_ORDER: ProficiencyKind[] = ['skills', 'languages', 'tools', 'weapons', 'armor'];

const KIND_TITLES: Record<ProficiencyKind, string> = {
  skills: 'Skills', languages: 'Languages', tools: 'Tools', weapons: 'Weapons', armor: 'Armour',
};

/** What a kind of proficiency is called, such as "Languages". */
export function proficiencyKindTitle(kind: ProficiencyKind): string {
  return KIND_TITLES[kind];
}

/** What a PROFICIENCY choice offers, skills first, then languages, tools, weapons and armour, as the server orders them. */
export function proficiencyOptions(choice: PendingChoice): ProficiencyOption[] {
  const offered = choice.proficiencyOptions;
  if (!offered) return [];
  return KIND_ORDER.flatMap(kind => (offered[kind] ?? []).map(name => ({ kind, name })));
}

// One-handed melee weapons by category (PHB p.149), so a whole category held counts as each weapon held
const SIMPLE_WEAPONS = ['club', 'dagger', 'handaxe', 'javelin', 'light hammer', 'mace', 'quarterstaff', 'sickle', 'spear'];
const MARTIAL_WEAPONS = ['battleaxe', 'flail', 'longsword', 'morningstar', 'rapier', 'scimitar', 'shortsword', 'trident',
  'war pick', 'warhammer', 'whip'];

const SHEET_LISTS: Record<ProficiencyKind, (c: PlayerCharacter) => string[] | null | undefined> = {
  skills: c => c.skillProficiencies,
  languages: c => c.languageProficiencies,
  tools: c => c.toolProficiencies,
  weapons: c => c.weaponProficiencies,
  armor: c => c.armorProficiencies,
};

/**
 * Whether the character has the proficiency already, in any case, as the server judges it: a weapon also when the
 * sheet has it in the plural or its whole category, simple or martial (PHB p.149).
 */
export function hasProficiency(character: PlayerCharacter, option: ProficiencyOption): boolean {
  const wanted = option.name.trim().toLowerCase();
  const category = option.kind !== 'weapons' ? null
    : SIMPLE_WEAPONS.includes(wanted) ? 'simple' : MARTIAL_WEAPONS.includes(wanted) ? 'martial' : null;
  return (SHEET_LISTS[option.kind](character) ?? []).some(item => {
    const have = item.trim().toLowerCase();
    if (have === wanted) return true;
    if (option.kind !== 'weapons') return false;
    return have === `${wanted}s` || (category !== null && (have === category || have === `${category} weapons`));
  });
}

/**
 * Whether an option can't be chosen: one the character has already, or for a choice whose skills gain Expertise, a
 * skill it has Expertise in already (a skill it is only proficient in may be chosen).
 */
export function proficiencyTaken(character: PlayerCharacter, choice: PendingChoice, option: ProficiencyOption): boolean {
  if (choice.expertise) {
    return (character.skillExpertises ?? []).some(skill => skill.trim().toLowerCase() === option.name.toLowerCase());
  }
  return hasProficiency(character, option);
}

/** The choice's name, such as "Subclass", "Spellbook" for a wizard's spells, or "Skills or Languages". */
export function choiceTitle(choice: PendingChoice): string {
  if (isSpellbookChoice(choice)) return 'Spellbook';
  if (choice.type === 'PROFICIENCY') {
    const kinds = KIND_ORDER.filter(kind => (choice.proficiencyOptions?.[kind] ?? []).length > 0);
    if (kinds.length === 0) return TITLES.PROFICIENCY;
    const title = kinds.map((kind, i) => i === 0 ? KIND_TITLES[kind] : KIND_TITLES[kind].toLowerCase()).join(' or ');
    return choice.expertise ? `${title} with Expertise` : title;
  }
  return TITLES[choice.type];
}

/**
 * The choice with what owes it, as the sheet's banner lists it: "Ability Score Improvement (Fighter 4)", or for a
 * race's or background's feat "Feat (race)".
 */
export function choiceLabel(choice: PendingChoice): string {
  if (choice.source === 'RACE') return `${choiceTitle(choice)} (race)`;
  if (choice.source === 'BACKGROUND') return `${choiceTitle(choice)} (background)`;
  return choice.className ? `${choiceTitle(choice)} (${choice.className} ${choice.classLevel})` : choiceTitle(choice);
}

/** "1 choice to make" or "3 choices to make". */
export function choicesToMake(count: number): string {
  return `${count} choice${count === 1 ? '' : 's'} to make`;
}

/** "1 choice pending" or "2 choices pending", as the DM sees it. */
export function choicesPending(count: number): string {
  return `${count} choice${count === 1 ? '' : 's'} pending`;
}
