import { Link2, Move, RotateCw, X } from 'lucide-react';
import type { SpellEffect } from '../../types/encounter';
import { TRIGGER_LABELS, effectUseLabel, roundsLeft } from '../../utils/spellEffects';
import IconButton from '../ui/IconButton';

/**
 * One Spell Effect, shown as a card indented under its caster (M15): the spell, its slot level, whether it ends with
 * the caster's concentration, the rounds and uses it has left, and the moments its area acts on creatures. `onUse`
 * adds the caster's button for an effect they direct, greyed with `blockedReason`'s reason, and `onEnd` the button that
 * ends it by hand.
 */
export default function SpellEffectCard({ effect, roundNumber, onUse, blockedReason, onEnd }: {
  effect: SpellEffect;
  roundNumber: number;
  onUse?: () => void;
  blockedReason?: string | null;
  onEnd?: () => void;
}) {
  const left = roundsLeft(effect, roundNumber);
  const label = effectUseLabel(effect);
  return (
    <div role="group" aria-label={`${effect.spell} effect`}
      className="flex flex-wrap items-center gap-x-2 gap-y-1 ml-3 mt-1 pl-2 py-1 border-l-2 border-cls-warlock/40 bg-cls-warlock/5">
      <span className="font-body text-[15px] font-semibold text-cls-warlock">{effect.spell}</span>
      {effect.slotLevel != null && effect.slotLevel > 0 && (
        <span className="font-body text-[13px] font-semibold text-muted">level {effect.slotLevel}</span>
      )}
      {effect.concentration && (
        <span title="Ends when its caster's concentration ends"
          className="inline-flex items-center gap-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] text-cls-warlock">
          <Link2 className="w-3 h-3" aria-hidden="true" /> Concentration
        </span>
      )}
      {left != null && (
        <span className="font-body text-[13px] font-semibold text-muted">{left} {left === 1 ? 'round' : 'rounds'} left</span>
      )}
      {effect.usesLeft != null && (
        <span className="font-body text-[13px] font-semibold text-muted">{effect.usesLeft} left</span>
      )}
      {effect.triggers.length > 0 && (
        <span className="font-body text-[13px] font-semibold text-faint">
          On a creature: {effect.triggers.map(t => TRIGGER_LABELS[t].toLowerCase()).join(', ')}
        </span>
      )}
      {onUse && label && (
        <button type="button" onClick={e => { e.stopPropagation(); onUse(); }} disabled={!!blockedReason}
          title={blockedReason ?? undefined}
          className="inline-flex items-center gap-1 px-2 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] border border-cls-warlock/30 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock disabled:opacity-50 disabled:cursor-not-allowed">
          {effect.directedResolves ? <RotateCw className="w-3 h-3" aria-hidden="true" /> : <Move className="w-3 h-3" aria-hidden="true" />}
          {label}
        </button>
      )}
      {onEnd && (
        <IconButton label={`End ${effect.spell}`} onClick={e => { e.stopPropagation(); onEnd(); }}
          className="p-0.5 text-faint hover:text-debuff hover:bg-debuff/10">
          <X className="w-3 h-3" />
        </IconButton>
      )}
    </div>
  );
}
