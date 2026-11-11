import type { ConditionEntry, EncounterParticipant, FearStatus, RepeatSave } from '../types/encounter';

/** The 14 conditions of the 2014 rules, as the server names them. */
export const ALL_CONDITIONS = [
  'blinded', 'charmed', 'deafened', 'frightened', 'grappled',
  'incapacitated', 'invisible', 'paralyzed', 'petrified',
  'poisoned', 'prone', 'restrained', 'stunned', 'unconscious',
] as const;

/** The 13 damage types of the 2014 rules, as the server names them. */
export const DAMAGE_TYPES = [
  'bludgeoning', 'piercing', 'slashing', 'acid', 'cold', 'fire',
  'force', 'lightning', 'necrotic', 'poison', 'psychic', 'radiant', 'thunder',
] as const;

/**
 * What a creature's action is spent on to end its condition (D188, D199, D200): an ability check, with its skill, as
 * against Wrathful Smite's fear ("WIS check") or Phantasmal Force's phantasm ("INT (Investigation) check"), else a save
 * ("WIS save").
 */
export function repeatRollName(save: RepeatSave): string {
  // The escape from a monster's grapple: the creature's better of the two skills (PHB p.195, D203)
  if (save.escape) return `${save.skill ?? 'Athletics or Acrobatics'} check`;
  if (save.skill) return `${save.ability} (${save.skill}) check`;
  return save.check ? `${save.ability} check` : `${save.ability} save`;
}

/**
 * What a condition's chip says ends it when its creature spends its action (D188, D203): the spell for a spell's
 * condition, the grapple for a monster's grapple, or the condition for a monster's net.
 */
export function actionEndText(condition: ConditionEntry): string {
  const save = condition.repeatSave;
  if (!save) return '';
  if (save.escape) return `; escape with its action: ${repeatRollName(save)} DC ${save.dc}, its better skill`;
  return `; a ${repeatRollName(save)} DC ${save.dc} made with its action ends ${condition.sourceAction ? 'it' : 'the spell'}`;
}

/** What a condition lasts while, as a restraint until its grapple ends (D203); undefined when it lasts on its own. */
export function linkedName(participant: EncounterParticipant, condition: ConditionEntry): string | undefined {
  if (!condition.linkedTo) return undefined;
  return participant.activeConditions?.find(other => other.id === condition.linkedTo)?.name;
}

/** A participant's fear for its frightened entry (D201), if the server sent one. */
export function fearFor(participant: EncounterParticipant, condition: ConditionEntry): FearStatus | undefined {
  return participant.fears?.find(fear => fear.conditionId === condition.id);
}

// The spells whose fear makes the creature Dash away from its source on its turns
const DASHES_AWAY: Record<string, string> = {
  Fear: 'PHB p.239',
  Eyebite: 'PHB p.238',
};

/**
 * What the table applies by hand while a creature is frightened, there being no map (PHB p.290, D201): it can't
 * willingly move closer to the source of its fear, and Fear's or Eyebite's panic makes it Dash away from the caster on
 * each of its turns.
 */
export function fearReminders(condition: ConditionEntry, fear?: FearStatus): string[] {
  const source = fear?.sourceName ?? 'the source of its fear';
  const reminders = [`It can't willingly move closer to ${source}`];
  const page = condition.sourceSpellName ? DASHES_AWAY[condition.sourceSpellName] : undefined;
  if (page) {
    reminders.push(`On each of its turns it must Dash away from ${source} by the safest route `
      + `(${condition.sourceSpellName}, ${page})`);
  }
  return reminders;
}

/**
 * Whether the fear's source is in sight, in words for its chip and tooltip (D201): "in sight", which gives disadvantage
 * on attack rolls and ability checks, or "out of sight" with why.
 */
export function fearSightNote(fear: FearStatus): string {
  return fear.inSight
    ? 'In sight: disadvantage on attack rolls and ability checks'
    : `Out of sight (${fear.sight}): no disadvantage`;
}
