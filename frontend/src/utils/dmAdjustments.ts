import type { DamageDefence, DmAdjustment, DmAdjustmentKind } from '../types/character';

/** What a DM Adjustment can change (D158). */
export const ADJUSTMENT_KINDS: { kind: DmAdjustmentKind; label: string }[] = [
  { kind: 'STAT', label: 'Stat' },
  { kind: 'DEFENCE', label: 'Defence' },
  { kind: 'FEAT', label: 'Feat' },
];

/**
 * The stats a STAT adjustment can change, as the server names them: the label the DM picks from, and the words an
 * adjustment is described with. Saving throws and skills follow from the ability scores.
 */
export const ADJUSTABLE_STATS: { target: string; label: string; phrase: string }[] = [
  { target: 'ARMOUR_CLASS', label: 'AC', phrase: 'AC' },
  { target: 'HP_MAX', label: 'HP maximum', phrase: 'HP maximum' },
  { target: 'SPEED', label: 'Speed (ft)', phrase: 'ft speed' },
  { target: 'INITIATIVE', label: 'Initiative', phrase: 'initiative' },
  { target: 'SPELL_SAVE_DC', label: 'Spell save DC', phrase: 'spell save DC' },
  { target: 'SPELL_ATTACK_BONUS', label: 'Spell attack bonus', phrase: 'spell attack bonus' },
  { target: 'STR', label: 'Strength', phrase: 'STR' },
  { target: 'DEX', label: 'Dexterity', phrase: 'DEX' },
  { target: 'CON', label: 'Constitution', phrase: 'CON' },
  { target: 'INT', label: 'Intelligence', phrase: 'INT' },
  { target: 'WIS', label: 'Wisdom', phrase: 'WIS' },
  { target: 'CHA', label: 'Charisma', phrase: 'CHA' },
];

/** The defences a DEFENCE adjustment gives (D161). */
export const DAMAGE_DEFENCES: { defence: DamageDefence; label: string }[] = [
  { defence: 'RESISTANCE', label: 'Resistance' },
  { defence: 'IMMUNITY', label: 'Immunity' },
  { defence: 'VULNERABILITY', label: 'Vulnerability' },
];

/** The thirteen damage types combat knows (PHB p.196), as the server names them. */
export const DAMAGE_TYPES = [
  'acid', 'bludgeoning', 'cold', 'fire', 'force', 'lightning', 'necrotic', 'piercing', 'poison', 'psychic',
  'radiant', 'slashing', 'thunder',
];

/** An adjustment in a few words, such as "+1 AC", "Resistance to fire" or "Feat: Lucky". */
export function describeAdjustment(adjustment: DmAdjustment): string {
  switch (adjustment.kind) {
    case 'STAT': {
      const amount = adjustment.amount ?? 0;
      const phrase = ADJUSTABLE_STATS.find(s => s.target === adjustment.target)?.phrase ?? adjustment.target;
      return `${amount > 0 ? '+' : ''}${amount} ${phrase}`;
    }
    case 'DEFENCE': {
      const label = DAMAGE_DEFENCES.find(d => d.defence === adjustment.defence)?.label ?? 'Defence';
      return `${label} to ${adjustment.target}`;
    }
    case 'FEAT':
      return `Feat: ${adjustment.target}`;
  }
}

/** The adjustments that count, oldest first. */
export function activeAdjustments(adjustments: DmAdjustment[] | null | undefined): DmAdjustment[] {
  return (adjustments ?? []).filter(a => !a.removedAt);
}

/** The adjustments the DM removed, kept as the record, the latest removed first. */
export function removedAdjustments(adjustments: DmAdjustment[] | null | undefined): DmAdjustment[] {
  return (adjustments ?? []).filter(a => a.removedAt)
    .sort((a, b) => (b.removedAt ?? '').localeCompare(a.removedAt ?? ''));
}
