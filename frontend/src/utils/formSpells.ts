import type { EncounterParticipant, WildShapeForm } from '../types/encounter';
import type { FormOptions } from '../types/combat';

/**
 * The spells that turn a creature into another creature's stat block (D192): the caster picks each creature's form
 * with the cast, from the options the server lists for that creature.
 */
export const FORM_SPELLS = ['Polymorph', 'Mass Polymorph', 'True Polymorph', 'Shapechange', 'Animal Shapes'] as const;

/** Shapechange is cast on the caster itself, so its one form is the caster's (PHB p.274). */
export const SHAPECHANGE = 'Shapechange';

/** The spells whose caster may give a new form with its action while the spell lasts (PHB p.274, p.212). */
export const RESHAPING_SPELLS = [SHAPECHANGE, 'Animal Shapes'] as const;

/** Whether the spell gives creatures a new form, so its cast chooses one for each. */
export function isFormSpell(spellName: string | null | undefined): boolean {
  return !!spellName && (FORM_SPELLS as readonly string[]).includes(spellName);
}

/** The spell whose caster can give a new form now, from the spell it concentrates on, or null. */
export function reshapingSpell(caster: Pick<EncounterParticipant, 'concentrationSpell'>): string | null {
  const spell = caster.concentrationSpell ?? null;
  return spell && (RESHAPING_SPELLS as readonly string[]).includes(spell) ? spell : null;
}

/**
 * The creatures a cast of the spell gives a form to: the caster for Shapechange, else each chosen target, in the order
 * chosen.
 */
export function formSubjects(spellName: string, caster: EncounterParticipant, targetIds: string[],
  participants: EncounterParticipant[]): EncounterParticipant[] {
  if (spellName === SHAPECHANGE) return [caster];
  const everyone = [caster, ...participants.filter(p => p.id !== caster.id)];
  return targetIds.map(id => everyone.find(p => p.id === id)).filter((p): p is EncounterParticipant => !!p);
}

/** The form a creature has taken, by a spell or a druid's Wild Shape, or null in its own form. */
export function formOf(participant: Pick<EncounterParticipant, 'form' | 'wildShape'>): WildShapeForm | null {
  return participant.form ?? participant.wildShape ?? null;
}

const SIZES: Record<string, string> = {
  T: 'Tiny', S: 'Small', M: 'Medium', L: 'Large', H: 'Huge', G: 'Gargantuan',
};

/** A size as words: the stat block's letter ("L") or the word itself ("Large"). */
export function sizeName(size: string | null | undefined): string | null {
  if (!size) return null;
  return SIZES[size.toUpperCase()] ?? size.charAt(0).toUpperCase() + size.slice(1).toLowerCase();
}

/** The spell's limits for the creature, as the picker words them: "A beast of CR 7 or lower, Large or smaller". */
export function formLimitText(options: Pick<FormOptions, 'beastsOnly' | 'maxChallengeRating' | 'largestSize'>): string {
  const parts = [`${options.beastsOnly ? 'A beast' : 'A creature'} of CR ${options.maxChallengeRating} or lower`];
  const size = sizeName(options.largestSize);
  if (size) parts.push(`${size} or smaller`);
  return parts.join(', ');
}
