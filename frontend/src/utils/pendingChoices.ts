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
