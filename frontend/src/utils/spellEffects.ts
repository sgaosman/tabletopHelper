// Spell Effects (M15) as the session pages show them: the words for their triggers and uses, and their rounds left.
import type { SpellEffect, SpellTrigger } from '../types/encounter';

/** How the card names a moment its area acts on a creature (M15). */
export const TRIGGER_LABELS: Record<SpellTrigger, string> = {
  ENTER: 'Enters',
  START_OF_TURN: 'Starts turn',
  END_OF_TURN: 'Ends turn',
};

/** The rounds an effect has left in this round: it ends at the start of its caster's turn once they pass (D162). */
export function roundsLeft(effect: SpellEffect, roundNumber: number): number | null {
  if (effect.durationRounds == null) return null;
  return Math.max(0, effect.durationRounds - (roundNumber - effect.startedRound));
}

/** The button's words for the caster's use of the effect: a move for one that only moves, with what it costs. */
export function effectUseLabel(effect: SpellEffect): string | null {
  if (!effect.directedCost) return null;
  const verb = effect.directedResolves ? 'Use' : 'Move';
  switch (effect.directedCost) {
    case 'ACTION': return `${verb} (action)`;
    case 'BONUS_ACTION': return `${verb} (bonus action)`;
    default: return verb;
  }
}
