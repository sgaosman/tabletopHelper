import { Sparkles } from 'lucide-react';
import type { EncounterParticipant } from '../../types/encounter';
import IconButton from '../ui/IconButton';
import { slotsLeft, slotTotal } from '../../utils/metamagic';

type SpellSlots = NonNullable<EncounterParticipant['spellSlotsCurrent']>;

/**
 * A participant's spell slots as pips, one per slot, filled while unspent. `variant` is 'row' for a line inside an
 * initiative row (the DM's) or 'card' for a card of its own (the player's character). With `onUse` an unspent pip
 * spends its slot, and with `onRestore` a spent one restores it; pages pass them only while the encounter is Active,
 * and the slots are read-only otherwise (D166). Slots a sorcerer created with Flexible Casting (PHB p.101, M18) count
 * with the level's, and the total says how many were created.
 */
export default function SpellSlotPips({ slots, variant, onUse, onRestore }: {
  slots: SpellSlots | null | undefined;
  variant: 'row' | 'card';
  onUse?: (level: number) => void;
  onRestore?: (level: number) => void;
}) {
  if (!slots) return null;
  const levels = Object.keys(slots).sort((a, b) => parseInt(a) - parseInt(b));
  if (levels.length === 0) return null;

  const isCard = variant === 'card';
  const pipSize = isCard ? 'w-3.5 h-3.5' : 'w-2.5 h-2.5';

  const list = levels.map(lvl => {
    const s = slots[lvl];
    const remaining = slotsLeft(s);
    const total = slotTotal(s);
    const created = s.created ?? 0;
    const level = parseInt(lvl);
    return (
      <div key={lvl} className={`flex items-center ${isCard ? 'gap-1' : 'gap-0.5'}`}>
        {isCard
          ? <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted w-6">Lv{lvl}</span>
          : <span className="font-heading text-[12px] font-semibold tracking-[0.1em] text-faint">{lvl}:</span>}
        <div className={`flex ${isCard ? 'gap-1' : 'gap-0.5'}`}>
          {Array.from({ length: total }).map((_, i) => {
            const unspent = i < remaining;
            if (!onUse) {
              return <span key={i} aria-hidden="true" className={`${pipSize} rounded-full border ${unspent ? 'bg-ink border-ink' : 'border-rule-light'}`} />;
            }
            if (unspent) {
              return (
                <IconButton key={i} label={`Use level ${lvl} slot`} onClick={() => onUse(level)}
                  className={`${pipSize} rounded-full border transition-colors bg-ink border-ink hover:bg-ink/80`} />
              );
            }
            return onRestore ? (
              <IconButton key={i} label={`Restore level ${lvl} slot`} onClick={() => onRestore(level)}
                className={`${pipSize} rounded-full border transition-colors border-rule-light hover:border-ink`} />
            ) : (
              <IconButton key={i} label={`Level ${lvl} slot used`} disabled
                className={`${pipSize} rounded-full border transition-colors border-rule-light cursor-default`} />
            );
          })}
          {!onUse && !isCard && <span className="sr-only">{`${remaining} of ${total} left`}</span>}
        </div>
        {isCard && (
          <span className="font-body text-[12.5px] font-semibold text-faint">
            {remaining}/{total}{created > 0 && ` (${created} created)`}
          </span>
        )}
      </div>
    );
  });

  if (!isCard) {
    return (
      <div className="flex items-center gap-2 mt-1 flex-wrap">
        <Sparkles className="w-3 h-3 text-ink" />
        {list}
      </div>
    );
  }
  return (
    <div className="bg-card border border-rule p-4 mb-4">
      <div className="flex items-center gap-2 mb-2">
        <Sparkles className="w-4 h-4 text-ink" />
        <span className="font-heading text-[15px] font-semibold tracking-[0.01em] text-muted">Spell Slots</span>
      </div>
      <div className="flex items-center gap-4 flex-wrap">{list}</div>
    </div>
  );
}
