import { safeJsonParse } from './dndRules';

/**
 * A Dragonborn's draconic ancestry (PHB p.34, D208): the dragon, the damage type of its breath and its resistance, the
 * breath's shape and the save it calls for. The server holds the same table (RacialFeatures.DraconicAncestry) and
 * checks every choice; this words and offers it.
 */
export interface DraconicAncestry {
  dragon: string;
  damageType: string;
  breath: string;
}

export const DRACONIC_ANCESTRIES: DraconicAncestry[] = [
  { dragon: 'Black', damageType: 'acid', breath: '5 by 30 ft. line (DEX save)' },
  { dragon: 'Blue', damageType: 'lightning', breath: '5 by 30 ft. line (DEX save)' },
  { dragon: 'Brass', damageType: 'fire', breath: '5 by 30 ft. line (DEX save)' },
  { dragon: 'Bronze', damageType: 'lightning', breath: '5 by 30 ft. line (DEX save)' },
  { dragon: 'Copper', damageType: 'acid', breath: '5 by 30 ft. line (DEX save)' },
  { dragon: 'Gold', damageType: 'fire', breath: '15 ft. cone (DEX save)' },
  { dragon: 'Green', damageType: 'poison', breath: '15 ft. cone (CON save)' },
  { dragon: 'Red', damageType: 'fire', breath: '15 ft. cone (DEX save)' },
  { dragon: 'Silver', damageType: 'cold', breath: '15 ft. cone (CON save)' },
  { dragon: 'White', damageType: 'cold', breath: '15 ft. cone (CON save)' },
];

/** The trait whose race chooses an ancestry: the PHB Dragonborn's, and the EGW Draconblood's and Ravenite's. */
export const DRACONIC_ANCESTRY_TRAIT = 'Draconic Ancestry';

/** The ancestry of this dragon, in any case, or undefined. */
export function ancestryOf(dragon: string | null | undefined): DraconicAncestry | undefined {
  const wanted = (dragon ?? '').trim().toLowerCase();
  return DRACONIC_ANCESTRIES.find(a => a.dragon.toLowerCase() === wanted);
}

/** How an ancestry reads in a list: "Red (fire, 15 ft. cone (DEX save))". */
export function ancestryLabel(ancestry: DraconicAncestry): string {
  return `${ancestry.dragon} (${ancestry.damageType}, ${ancestry.breath})`;
}

/**
 * Whether a race's traits, as the races endpoint gives them (an array of {name, description}, or the same as JSON
 * text), include Draconic Ancestry.
 */
export function hasDraconicAncestry(traits: unknown): boolean {
  const parsed = safeJsonParse<unknown>(traits, []);
  return Array.isArray(parsed) && parsed.some(trait => typeof trait === 'object' && trait !== null
    && String((trait as { name?: unknown }).name ?? '').trim().toLowerCase() === DRACONIC_ANCESTRY_TRAIT.toLowerCase());
}
