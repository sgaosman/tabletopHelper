import { Link2, Move, RotateCw, X } from 'lucide-react';
import type { SpellEffect } from '../../types/encounter';
import { TRIGGER_LABELS, effectUseLabel, laterNotes, roundsLeft } from '../../utils/spellEffects';
import IconButton from '../ui/IconButton';

/**
 * One Spell Effect, shown as a card indented under its caster (M15): the spell, its slot level, whether it ends with
 * the caster's concentration, the rounds and uses it has left, and the moments its area acts on creatures. `onUse`
 * adds the caster's button for an effect they direct, greyed with `blockedReason`'s reason, and `onEnd` the button that
 * ends it by hand. An effect with Ongoing Modifiers (D184) names the creatures they are on, `affectedNames`, and its
 * option, with what the modifiers do as its title. `moveTargets` with `onMove` offers to move Hunter's Mark or Hex to
 * another creature (PHB p.251), which the server allows once the marked creature is at 0 hit points. What the effect
 * keeps for later (D185), such as Guardian of Faith's damage left, Fire Shield's retaliation or Vitriolic Sphere's damage
 * still to come, is listed too, naming creatures through `nameOf`.
 */
export default function SpellEffectCard({
  effect, roundNumber, onUse, blockedReason, onEnd, affectedNames = [], moveTargets = [], onMove, nameOf,
}: {
  effect: SpellEffect;
  roundNumber: number;
  onUse?: () => void;
  blockedReason?: string | null;
  onEnd?: () => void;
  affectedNames?: string[];
  moveTargets?: { id: string; name: string }[];
  onMove?: (targetId: string) => void;
  nameOf?: (id: string) => string | undefined;
}) {
  const left = roundsLeft(effect, roundNumber);
  const later = laterNotes(effect, nameOf ?? (() => undefined));
  const label = effectUseLabel(effect);
  return (
    <div role="group" aria-label={`${effect.spell} effect`}
      className="flex flex-wrap items-center gap-x-2 gap-y-1 ml-3 mt-1 pl-2 py-1 border-l-2 border-cls-warlock/40 bg-cls-warlock/5">
      <span className="font-body text-[15px] font-semibold text-cls-warlock"
        title={effect.modifiers?.length ? effect.modifiers.join('; ') : undefined}>
        {effect.spell}{effect.option ? ` (${effect.option})` : ''}
      </span>
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
      {affectedNames.length > 0 && (
        <span className="font-body text-[13px] font-semibold text-muted">on {affectedNames.join(', ')}</span>
      )}
      {effect.usesLeft != null && (
        <span className="font-body text-[13px] font-semibold text-muted">{effect.usesLeft} left</span>
      )}
      {later.map(note => (
        <span key={note} className="font-body text-[13px] font-semibold text-muted">{note}</span>
      ))}
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
      {onMove && effect.retargetable && moveTargets.length > 0 && (
        <select aria-label={`Move ${effect.spell} to`} value="" onClick={e => e.stopPropagation()}
          onChange={e => { if (e.target.value) onMove(e.target.value); }}
          className="font-heading text-[11px] font-semibold border border-cls-warlock/30 bg-card text-cls-warlock px-1 py-0.5">
          <option value="">Move to…</option>
          {moveTargets.map(target => <option key={target.id} value={target.id}>{target.name}</option>)}
        </select>
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
