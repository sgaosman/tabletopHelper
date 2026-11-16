import type { ReactionOffer } from '../types/combat';

/** What happened, in words, from the offer's fields (D216). */
export function describeTrigger(offer: ReactionOffer): string {
  const actor = offer.triggerActorName ?? 'Someone';
  if (offer.trigger === 'SPELL_CAST') {
    const level = offer.spellLevel && offer.spellLevel > 0 ? ` at level ${offer.spellLevel}` : '';
    return `${actor} casts ${offer.spellName ?? 'a spell'}${level}.`;
  }
  const damage = offer.damageTaken
    ? `${offer.damageTaken}${offer.damageTypes?.length ? ` ${offer.damageTypes.join(' and ')}` : ''} damage`
    : null;
  if (offer.trigger === 'ATTACK_HIT') {
    const roll = offer.attackTotal != null
      ? ` (attack roll ${offer.attackTotal}${offer.armourClass != null ? ` against AC ${offer.armourClass}` : ''})`
      : '';
    return `${actor} ${offer.critical ? 'critically hits' : 'hits'} ${offer.reactorName}${roll}${damage ? `, ${damage}` : ''}.`;
  }
  return `${actor} deals ${damage ?? 'damage'} to ${offer.reactorName}.`;
}
