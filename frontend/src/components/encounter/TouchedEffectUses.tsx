import { Wind } from 'lucide-react';
import type { EncounterParticipant, SpellEffect } from '../../types/encounter';
import { touchedEffects, touchedUseLabel } from '../../utils/spellEffects';

/**
 * The buttons on a creature for the Spell Effects of other creatures' spells it uses with its own action, as the
 * creature Dragon's Breath touched exhales the caster's breath (XGE p.154, D199, D200): its player's button, on its turn,
 * greyed with `blockedBy`'s reason, shown beside it, where its turn can't pay for it. Shows nothing when it has none.
 */
export default function TouchedEffectUses({ participant, participants, onUse, blockedBy }: {
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  onUse: (effect: SpellEffect) => void;
  blockedBy: (effect: SpellEffect) => string | null;
}) {
  const uses = touchedEffects(participant.id, participants);
  if (uses.length === 0) return null;
  return (
    <div className="flex flex-col gap-1 mt-1">
      {uses.map(({ holder, effect }) => {
        const blocked = blockedBy(effect);
        return (
          <div key={effect.id} className="flex flex-wrap items-center gap-2">
            <button type="button" onClick={() => onUse(effect)} disabled={!!blocked}
              title={blocked ?? `${holder.displayName}'s ${effect.spell}`}
              className="inline-flex items-center gap-1 min-h-11 px-3 py-1.5 font-heading text-[13px] font-semibold tracking-[0.02em] border border-cls-warlock/30 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock disabled:opacity-50 disabled:cursor-not-allowed">
              <Wind className="w-3.5 h-3.5" aria-hidden="true" />
              {touchedUseLabel(effect)}
            </button>
            <span className="font-body text-[13px] font-semibold text-muted">
              {holder.displayName}&apos;s spell{effect.option ? `, ${effect.option}` : ''}
            </span>
            {blocked && <span className="font-body text-[13px] font-semibold text-debuff">{blocked}</span>}
          </div>
        );
      })}
    </div>
  );
}
