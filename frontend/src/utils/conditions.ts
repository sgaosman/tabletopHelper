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
