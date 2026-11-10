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

/** The condition of a creature Banishment has removed from the fight until the spell ends (PHB p.217, D185). */
export const BANISHED = 'banished';

/** The condition of a creature inside Otiluke's Resilient Sphere, which nothing passes (PHB p.264, D185). */
export const ENCLOSED = 'enclosed';

/** Whether the creature has the condition, by name in any case. */
export function hasCondition(participant: { activeConditions?: { name: string }[] | null }, name: string): boolean {
  return (participant.activeConditions ?? []).some(condition => condition.name.toLowerCase() === name);
}

/**
 * Whether the caster's spell or attack can reach the creature (D185): a banished creature is out of the fight, and
 * nothing crosses Otiluke's Resilient Sphere, so a creature inside reaches only itself and nothing outside reaches it.
 * The server refuses the same with a 409.
 */
export function reachable(
  caster: { id: string; activeConditions?: { name: string }[] | null },
  target: { id: string; activeConditions?: { name: string }[] | null },
): boolean {
  if (hasCondition(target, BANISHED)) return false;
  if (caster.id === target.id) return true;
  return !hasCondition(target, ENCLOSED) && !hasCondition(caster, ENCLOSED);
}

/**
 * What a Spell Effect keeps for later, in words for its card (D185): the damage it can still deal, as Guardian of
 * Faith's, what it deals back to a melee attacker, as Fire Shield's, and damage due at the end of a creature's next
 * turn, as Vitriolic Sphere's, naming the creature when it is known; and (D187) the creatures it controls and each
 * creature's count of saves.
 */
export function laterNotes(effect: SpellEffect, nameOf: (id: string) => string | undefined): string[] {
  const notes: string[] = [];
  if (effect.damageLeft != null) notes.push(`${effect.damageLeft} damage left`);
  if (effect.retaliation) notes.push(`strikes back: ${effect.retaliation}`);
  for (const delayed of effect.delayed ?? []) {
    const name = nameOf(delayed.target);
    notes.push(`${delayed.dice} ${delayed.type} to ${name ?? 'a creature'} at the end of its next turn`);
  }
  // The 5th-level spells (D187): creatures it controls, as Dominate Person's, and each creature's count of saves, as
  // Contagion's, from its rule such as "CON 3/3"
  const controlled = (effect.controlledIds ?? []).map(id => nameOf(id) ?? 'a creature');
  if (controlled.length > 0) notes.push(`controls ${controlled.join(', ')}`);
  const [ability, counts] = (effect.tallyRule ?? '').split(' ');
  const [successes, failures] = (counts ?? '').split('/');
  for (const tally of effect.tallies ?? []) {
    const name = nameOf(tally.creature) ?? 'a creature';
    notes.push(tally.done
      ? `${name}: ${ability} saves done (${tally.successes} successes, ${tally.failures} failures)`
      : `${name}: ${ability} saves ${tally.successes}/${successes} successes, ${tally.failures}/${failures} failures`);
  }
  return notes;
}
