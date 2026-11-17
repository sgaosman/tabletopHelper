import type { MonsterAction } from '../types/combat';

/** The form value a request sends for a monster in none of an attack's forms, as a wereraven in hybrid form (D241). */
export const USUAL_FORM = 'usual';

/** The first damage that comes with every hit, the one a versatile weapon's two hands replace (D241). */
function firstDamage(action: MonsterAction) {
  return action.effects?.find(e => e.effectType === 'DAMAGE' && e.damageDice && !e.onlyIf);
}

/** The attack's one-handed damage, as "1d8 + 1 slashing"; undefined without one. */
export function oneHandedDamage(action: MonsterAction): string | undefined {
  const first = firstDamage(action);
  return first ? [first.damageDice, first.damageType].filter(Boolean).join(' ') : undefined;
}

/**
 * A versatile weapon's damage with two hands, as "1d10 + 1 slashing", from either way the data keeps it (D241);
 * undefined for an attack that isn't versatile.
 */
export function twoHandedDamage(action: MonsterAction): string | undefined {
  const first = firstDamage(action);
  if (!first) return undefined;
  const dice = action.versatile?.twoHandedDamageDice ?? first.twoHandedDamageDice;
  if (!dice) return undefined;
  const type = action.versatile?.twoHandedDamageType ?? first.damageType;
  return [dice, type].filter(Boolean).join(' ');
}

/** Whether a charge adds to the attack (D241). */
export function hasCharge(action: MonsterAction): boolean {
  return action.charge != null || (action.effects ?? []).some(e => e.onlyIf?.attackerCharged);
}

/** The forms the attack's damage depends on, lower case, in order, as "raven form" or "enlarged" (D241). */
export function actionForms(action: MonsterAction): string[] {
  const forms = (action.effects ?? [])
    .map(e => e.onlyIf?.attackerForm?.trim().toLowerCase())
    .filter((form): form is string => !!form);
  return [...new Set(forms)];
}

/**
 * The Form option's words: the form with the damage it brings, as "Raven form (1 piercing)", and "if charging" on
 * damage that also needs a charge, as a Minotaur Infiltrator's when enlarged (D243).
 */
export function formLabel(action: MonsterAction, form: string): string {
  const parts = (action.effects ?? [])
    .filter(e => e.damageDice && e.onlyIf?.attackerForm?.trim().toLowerCase() === form)
    .map(e => [e.damageDice, e.damageType].filter(Boolean).join(' ') + (e.onlyIf?.attackerCharged ? ' if charging' : ''));
  const name = form.charAt(0).toUpperCase() + form.slice(1);
  return parts.length > 0 ? `${name} (${parts.join(', ')})` : name;
}

/** The DM's words for a charge's checkbox, as "Charged (moved 30+ ft straight toward the target)". */
export function chargeLabel(feet: number | null | undefined): string {
  return feet ? `Charged (moved ${feet}+ ft straight toward the target first)` : 'Charged first';
}
