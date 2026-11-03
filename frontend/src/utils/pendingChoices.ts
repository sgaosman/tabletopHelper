import type { PendingChoice, PendingChoiceType } from '../types/character';

/** What each kind of Pending Choice is called on the sheet (D159). */
const TITLES: Record<PendingChoiceType, string> = {
  SUBCLASS: 'Subclass',
  FIGHTING_STYLE: 'Fighting Style',
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

/** The choice's name, such as "Subclass", or "Spellbook" for a wizard's spells. */
export function choiceTitle(choice: PendingChoice): string {
  return isSpellbookChoice(choice) ? 'Spellbook' : TITLES[choice.type];
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
