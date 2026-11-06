import type { EncounterParticipant, ResourcePoolEntry, TurnEconomy } from '../types/encounter';

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

// ── Standard actions, object interactions and pools (D174) ──────────────

/** One of the standard actions other than Attack and Cast a Spell (PHB p.192–193). */
export type StandardAction = 'DASH' | 'DISENGAGE' | 'DODGE' | 'HELP' | 'HIDE' | 'READY' | 'SEARCH' | 'USE_OBJECT';

/** The standard actions in the PHB's order, with their names and what each does at the table. */
export const STANDARD_ACTIONS: { action: StandardAction; label: string; summary: string }[] = [
  { action: 'DASH', label: 'Dash', summary: 'Extra movement equal to its speed this turn' },
  { action: 'DISENGAGE', label: 'Disengage', summary: "Its movement doesn't provoke opportunity attacks this turn" },
  { action: 'DODGE', label: 'Dodge', summary: 'Attacks against it have disadvantage until its next turn' },
  { action: 'HELP', label: 'Help', summary: 'Gives a creature advantage on its next check or attack' },
  { action: 'HIDE', label: 'Hide', summary: 'Rolls Dexterity (Stealth); the DM decides whether it is hidden' },
  { action: 'READY', label: 'Ready', summary: 'Names a trigger; the response will take its reaction' },
  { action: 'SEARCH', label: 'Search', summary: 'Looks for something' },
  { action: 'USE_OBJECT', label: 'Use an Object', summary: 'A second object interaction, or an object that takes an action' },
];

/** Whether anything could let the action be taken as a bonus action: only Dash, Disengage and Hide. */
export function canBeBonusAction(action: StandardAction): boolean {
  return action === 'DASH' || action === 'DISENGAGE' || action === 'HIDE';
}

/**
 * What lets a creature take the action as a bonus action, from the names of its features or traits, as the server's
 * StandardActions reads them: Cunning Action for Dash, Disengage or Hide (PHB p.96), and Nimble Escape for Disengage
 * or Hide (MM p.166). Null when nothing does.
 */
export function bonusActionGrant(action: StandardAction, featureNames: readonly string[]): string | null {
  const names = featureNames.map(name => name.trim().toLowerCase());
  const cunning = names.includes('cunning action');
  const nimble = names.includes('nimble escape');
  if (action === 'DASH') return cunning ? 'Cunning Action' : null;
  if (action === 'DISENGAGE' || action === 'HIDE') return cunning ? 'Cunning Action' : nimble ? 'Nimble Escape' : null;
  return null;
}

/** Why the action can't be taken as a bonus action by a creature that has nothing granting it, as the server says. */
export function bonusActionRefusal(action: StandardAction): string {
  const label = STANDARD_ACTIONS.find(a => a.action === action)?.label ?? action;
  if (action === 'DASH') return 'Dash as a bonus action needs Cunning Action';
  return canBeBonusAction(action)
    ? `${label} as a bonus action needs Cunning Action or Nimble Escape`
    : `${label} can't be taken as a bonus action`;
}

/**
 * Why the participant can't have an object interaction now, or null when it can: only on its own turn, and only the
 * first each turn is free; another needs the Use an Object action (PHB p.190).
 */
export function objectInteractionBlock(participant: EncounterParticipant): string | null {
  const economy = participant.turnEconomy;
  if (!economy) return null;
  if (!participant.isCurrentTurn) return "Not this creature's turn: object interactions happen on its own turn";
  return economy.objectInteractionUsed
    ? 'The free object interaction is used this turn; another needs the Use an Object action'
    : null;
}

/** What using a pool costs, from its spendActionType (D125); a pool without one costs nothing. */
export type PoolCost = 'ACTION' | 'BONUS_ACTION' | 'REACTION' | 'FREE' | 'REPLACE_ATTACK';

const POOL_COST_LABELS: Record<PoolCost, string> = {
  ACTION: 'Action',
  BONUS_ACTION: 'Bonus action',
  REACTION: 'Reaction',
  FREE: 'Free',
  REPLACE_ATTACK: 'In place of an attack',
};

export function poolCost(pool: Pick<ResourcePoolEntry, 'spendActionType'>): PoolCost {
  const type = pool.spendActionType?.trim() ?? '';
  return type in POOL_COST_LABELS ? (type as PoolCost) : 'FREE';
}

/** The pool's metadata as the server writes it (D169), or an empty object. */
export function poolMetadata(pool: Pick<ResourcePoolEntry, 'metadata'>): Record<string, unknown> {
  const metadata: unknown = pool.metadata;
  return metadata && typeof metadata === 'object' ? (metadata as Record<string, unknown>) : {};
}

/** Whether the pool adds an action when used, as Action Surge does (PHB p.72). */
export function grantsAction(pool: Pick<ResourcePoolEntry, 'metadata'>): boolean {
  return poolMetadata(pool).grantsAction === true;
}

/** What a use of the pool costs from the turn, as the pool shows it: "Bonus action", or Action Surge's own wording. */
export function poolCostLabel(pool: Pick<ResourcePoolEntry, 'spendActionType' | 'metadata'>): string {
  if (grantsAction(pool)) return 'Own turn, once: +1 action';
  return POOL_COST_LABELS[poolCost(pool)];
}

/**
 * Why nobody, the DM included, can use the pool now, or null: it has no uses left, or it recovers another pool, as
 * Psionic Recovery does, that has nothing expended to regain. An unlimited pool is never refused (D174).
 */
export function poolRefusal(pool: ResourcePoolEntry, pools: readonly ResourcePoolEntry[]): string | null {
  const metadata = poolMetadata(pool);
  if (metadata.unlimited !== true && pool.currentUses < 1) return `No ${pool.displayName} uses left`;
  const recovered = typeof metadata.recovers === 'string' ? pools.find(p => p.poolId === metadata.recovers) : undefined;
  if (recovered && poolMetadata(recovered).unlimited !== true && recovered.currentUses >= recovered.maxUses) {
    return `No ${recovered.displayName} expended to regain`;
  }
  return null;
}

/**
 * Why the participant's turn can't pay for a use of the pool, or null when it can (D174): a reaction or a free use may
 * be taken on any turn, an action or bonus action only on its own, and a use in place of an attack takes one attack
 * of the Attack action. Action Surge is used on its own turn, once on the same turn (PHB p.72).
 */
export function poolBudgetBlock(participant: EncounterParticipant, pool: ResourcePoolEntry): string | null {
  if (grantsAction(pool)) {
    if (!participant.turnEconomy) return null;
    if (!participant.isCurrentTurn) return `${pool.displayName} is used on its own turn`;
    return participant.turnEconomy.actionSurgeUsed
      ? `${pool.displayName} was already used this turn; it can only be used once on the same turn`
      : null;
  }
  switch (poolCost(pool)) {
    case 'ACTION': return chargeBlock(participant, { cost: 'ACTION' });
    case 'BONUS_ACTION': return chargeBlock(participant, { cost: 'BONUS_ACTION' });
    case 'REACTION': return chargeBlock(participant, { cost: 'REACTION' });
    case 'REPLACE_ATTACK': return chargeBlock(participant, { cost: 'ACTION', attack: true });
    default: return null;
  }
}

/**
 * What using the pool gives back, for a pool that recovers another, as Psionic Recovery regains a Psionic Energy die:
 * "regains a die of Psionic Energy Dice", or "regains a use of …" for a pool without a die. Null for any other pool.
 */
export function poolRecoveryText(pool: ResourcePoolEntry, pools: readonly ResourcePoolEntry[]): string | null {
  const recovers = poolMetadata(pool).recovers;
  if (typeof recovers !== 'string') return null;
  const recovered = pools.find(p => p.poolId === recovers);
  if (!recovered) return null;
  const die = poolMetadata(recovered).die;
  return `regains a ${typeof die === 'string' ? 'die' : 'use'} of ${recovered.displayName}`;
}
