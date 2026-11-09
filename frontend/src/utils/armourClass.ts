import type { ArmourClassBreakdown } from '../types/character';

/**
 * How the AC adds up, as one line (M13.5): "Chain Mail 16 + Shield 2 + DM adjustments 1 = 19". The first part is
 * the armour or unarmoured base; a negative part, such as a DEX of -1, is taken away.
 */
export function formatArmourClass(breakdown: ArmourClassBreakdown): string {
  const sum = breakdown.parts.map((part, i) => {
    if (i === 0) return `${part.label} ${part.amount}`;
    return part.amount < 0 ? `− ${part.label} ${-part.amount}` : `+ ${part.label} ${part.amount}`;
  }).join(' ');
  return sum ? `${sum} = ${breakdown.total}` : String(breakdown.total);
}
