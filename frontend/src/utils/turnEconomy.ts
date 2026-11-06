import type { EncounterParticipant, TurnEconomy } from '../types/encounter';

/**
 * What something costs from a creature's turn (D174): an action (or one attack of an Attack action), a bonus action,
 * a reaction, or nothing from the budget.
 */
export type EconomyCost = 'ACTION' | 'BONUS_ACTION' | 'REACTION' | 'NONE';

/** A spell's casting time as the server reads it from the spell data (D174). */
export type CastingTime = 'ACTION' | 'BONUS_ACTION' | 'REACTION' | 'LONGER';

/** The conditions that leave a creature incapacitated, so it takes no actions or reactions (PHB p.290–292). */
const INCAPACITATING = ['incapacitated', 'paralyzed', 'petrified', 'stunned', 'unconscious'];

/** The incapacitating condition a participant has, if any. */
export function incapacitatingCondition(participant: Pick<EncounterParticipant, 'activeConditions'>): string | null {
  const names = (participant.activeConditions ?? []).map(c => c.name.trim().toLowerCase());
  return INCAPACITATING.find(name => names.includes(name)) ?? null;
}

/** What is being paid for, as the server's ActionEconomy checks it. */
export interface Charge {
  cost: EconomyCost;
  /** An attack: an action opens the Attack action or uses one of its attacks, and a bonus action is an off-hand attack. */
  attack?: boolean;
  /** A spell of this level (0 for a cantrip), for the bonus-action-spell rule (PHB p.202). */
  spellLevel?: number;
}

/**
 * Why the participant can't pay the charge now, or null when it can. It mirrors the server's ActionEconomy so the
 * page can say why before the request is sent; the server stays the authority, and its 409 is still shown (D174).
 */
export function chargeBlock(participant: EncounterParticipant, charge: Charge): string | null {
  const economy = participant.turnEconomy;
  if (!economy || charge.cost === 'NONE') return null;
  const incapacitated = incapacitatingCondition(participant);
  if (incapacitated) return `${capitalise(incapacitated)}, so no actions or reactions`;
  const ownTurn = participant.isCurrentTurn;
  if (!ownTurn && charge.cost !== 'REACTION') return "Not this creature's turn: only a reaction can be taken";

  const budget = budgetBlock(economy, charge);
  if (budget) return budget;
  return charge.spellLevel !== undefined && ownTurn ? spellRuleBlock(economy, charge.cost, charge.spellLevel) : null;
}

function budgetBlock(economy: TurnEconomy, charge: Charge): string | null {
  switch (charge.cost) {
    case 'ACTION':
      if (charge.attack && economy.attackActionTaken && economy.attacksLeft > 0) return null;
      if (economy.actionsUsed < economy.actions) return null;
      return charge.attack && economy.attackActionTaken
        ? 'No attacks left in the Attack action and no action left this turn'
        : 'No action left this turn';
    case 'BONUS_ACTION':
      if (economy.bonusActionUsed) return 'Bonus action already used this turn';
      return charge.attack && !economy.attackActionTaken ? 'An off-hand attack needs the Attack action first this turn' : null;
    case 'REACTION':
      return economy.reactionUsed ? "Reaction already used; it comes back at the start of the creature's turn" : null;
    default:
      return null;
  }
}

/**
 * The bonus-action-spell rule (PHB p.202), in either order on the caster's own turn: after a spell cast as a bonus
 * action, the only other spell is a cantrip with a casting time of 1 action; and after any other spell (such a
 * cantrip aside), no spell can be cast as a bonus action.
 */
function spellRuleBlock(economy: TurnEconomy, cost: EconomyCost, level: number): string | null {
  if (cost === 'BONUS_ACTION' && economy.otherSpellCast) {
    return 'A spell other than a 1-action cantrip was cast this turn, so no spell can be cast as a bonus action';
  }
  if (cost !== 'BONUS_ACTION' && economy.bonusActionSpellCast && !(level === 0 && cost === 'ACTION')) {
    return 'A bonus-action spell was cast this turn, so only a cantrip with a casting time of 1 action can follow';
  }
  return null;
}

/**
 * Why the caster can't cast a spell of this casting time and level now, or null when it can. A player can't cast a
 * spell that takes longer than a turn in an Active encounter; the DM's is charged an action (D174).
 */
export function spellCastBlock(caster: EncounterParticipant, castingTime: CastingTime, level: number,
  { dm }: { dm: boolean }): string | null {
  if (castingTime === 'LONGER') {
    if (!dm) return 'Its casting time is longer than a turn, so it can\'t be cast during combat';
    return chargeBlock(caster, { cost: 'ACTION', spellLevel: level });
  }
  return chargeBlock(caster, { cost: castingTime, spellLevel: level });
}

const CASTING_TIME_LABELS: Record<CastingTime, string> = {
  ACTION: 'Action',
  BONUS_ACTION: 'Bonus action',
  REACTION: 'Reaction',
  LONGER: 'Longer',
};

export function castingTimeLabel(castingTime: CastingTime): string {
  return CASTING_TIME_LABELS[castingTime];
}

/** What a cost takes from the turn, as a sentence ends: "an action", "a bonus action", "a reaction", "nothing". */
export function costPhrase(cost: EconomyCost): string {
  switch (cost) {
    case 'ACTION': return 'an action';
    case 'BONUS_ACTION': return 'a bonus action';
    case 'REACTION': return 'a reaction';
    default: return 'nothing from the turn';
  }
}

/**
 * A participant's speeds as the bar shows them: walking first, then the others in the order the stat block or sheet
 * gives them, each "30 ft" or "fly 60 ft". A creature that has taken the Dash action also shows its doubled walking
 * speed (PHB p.192).
 */
export function speedText(speed: Record<string, number> | null | undefined, dashed: boolean): { speeds: string[]; dash: string | null } {
  const entries = Object.entries(speed ?? {}).filter(([, feet]) => feet > 0);
  const walk = entries.find(([mode]) => mode === 'walk');
  const others = entries.filter(([mode]) => mode !== 'walk');
  const speeds = [
    ...(walk ? [`${walk[1]} ft`] : []),
    ...others.map(([mode, feet]) => `${mode} ${feet} ft`),
  ];
  return { speeds, dash: dashed && walk ? `${walk[1] * 2} ft` : null };
}

function capitalise(text: string) {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
