// How a combat log entry is coloured, by its action type: the text colour and the left border, with the M12
// monster, legendary and lair actions. Both session pages use these (D171).

/** The text colour class for an entry of this action type. */
export function logTextColour(actionType: string): string {
  switch (actionType) {
    case 'ATTACK': return 'text-cls-fighter';
    case 'DAMAGE': case 'KILL': return 'text-debuff';
    case 'HEAL': case 'REVIVE': return 'text-buff';
    case 'CONDITION_ADD': return 'text-cls-monk';
    case 'CONDITION_REMOVE': return 'text-cls-wizard';
    case 'DEATH_SAVE': return 'text-cls-fighter';
    case 'STABILIZE': return 'text-buff';
    case 'CONCENTRATION_CHECK': return 'text-cls-warlock';
    case 'CONCENTRATION_LOST': return 'text-cls-warlock';
    case 'SPELL_CAST': return 'text-ink';
    case 'SPELL_SLOT_USE': return 'text-ink';
    case 'SPELL_SLOT_RESTORE': return 'text-ink';
    case 'TURN_ADVANCE': case 'TURN_BACK': return 'text-faint';
    case 'MONSTER_ACTION': return 'text-monster';
    case 'LEGENDARY_ACTION': return 'text-cls-monk';
    case 'LAIR_ACTION': return 'text-cls-warlock';
    case 'LEGENDARY_RESISTANCE_USED': return 'text-cls-monk';
    case 'RECHARGE_SUCCESS': return 'text-buff';
    case 'RECHARGE_FAILURE': return 'text-faint';
    case 'HP_SET': return 'text-buff';
    default: return 'text-muted';
  }
}

/** The left border colour class for an entry of this action type: harm, help, or the monster's kind of action. */
export function logBorderColour(actionType: string): string {
  switch (actionType) {
    case 'ATTACK': case 'DAMAGE': case 'KILL': case 'DEATH_SAVE':
    case 'CONDITION_ADD': case 'CONCENTRATION_LOST':
      return 'border-debuff';
    case 'HEAL': case 'REVIVE': case 'STABILIZE': case 'CONDITION_REMOVE': case 'HP_SET':
      return 'border-buff';
    case 'MONSTER_ACTION': return 'border-monster';
    case 'LEGENDARY_ACTION': case 'LEGENDARY_RESISTANCE_USED': return 'border-cls-monk';
    case 'LAIR_ACTION': return 'border-cls-warlock';
    case 'RECHARGE_SUCCESS': return 'border-buff';
    case 'RECHARGE_FAILURE': return 'border-rule';
    default:
      return 'border-muted';
  }
}

/** Whether the entry marks a turn changing, which the log shows as a "Turn:" header rather than a line. */
export function isTurnChange(actionType: string): boolean {
  return actionType === 'TURN_ADVANCE' || actionType === 'TURN_BACK';
}
