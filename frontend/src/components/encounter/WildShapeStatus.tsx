import type { EncounterParticipant } from '../../types/encounter';
import { WILD_SHAPE_TEXT, wildShapeOf } from '../../utils/classFeatures';
import { formOf } from '../../utils/formSpells';

/**
 * A druid's Wild Shape on its row (PHB p.66–67): the beast it has become, the beast's HP and AC (the participant's
 * own numbers while it lasts) and the druid's own, which come back when it reverts. `onEnd` offers reverting early, a
 * bonus action, to whoever may: the DM, or the druid's player. A creature a form spell transformed (D192) shows the
 * spell and its form the same way, with no End: the form lasts as long as the spell. Nothing shows for a creature in its
 * own form, or for a monster's form in the Player View, which the server leaves out.
 */
export default function WildShapeStatus({ participant, onEnd, ending = false }: {
  participant: EncounterParticipant;
  onEnd?: () => void;
  ending?: boolean;
}) {
  const form = wildShapeOf(participant);
  if (!form) {
    const spellForm = formOf(participant);
    if (!spellForm?.spell) return null;
    return (
      <div role="status" aria-label={`${participant.displayName}'s ${spellForm.spell}`}
        className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px] text-ink">
        <span className="px-1 font-heading font-semibold text-card bg-buff">{spellForm.spell}: {spellForm.beastName}</span>
        <span className="tabular-nums">
          Form HP {participant.hpCurrent ?? '?'}/{participant.hpMax ?? spellForm.hpMax}, AC {participant.armourClass}
        </span>
        <span className="tabular-nums text-muted">
          Own HP {spellForm.normalHpCurrent}/{spellForm.normalHpMax}, AC {spellForm.normalArmourClass}
        </span>
      </div>
    );
  }
  return (
    <div role="status" aria-label={`${participant.displayName}'s Wild Shape`}
      className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px] text-ink" title={WILD_SHAPE_TEXT}>
      <span className="px-1 font-heading font-semibold text-card bg-buff">Wild Shape: {form.beastName}</span>
      <span className="tabular-nums">
        Beast HP {participant.hpCurrent ?? '?'}/{participant.hpMax ?? form.hpMax}, AC {participant.armourClass}
      </span>
      <span className="tabular-nums text-muted">
        Own HP {form.normalHpCurrent}/{form.normalHpMax}, AC {form.normalArmourClass}
      </span>
      {onEnd && (
        <button type="button" onClick={onEnd} disabled={ending} aria-label={`End ${participant.displayName}'s Wild Shape`}
          className="px-1.5 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] bg-ink/10 hover:bg-ink/20 text-ink disabled:opacity-40">
          {ending ? '...' : 'End (bonus action)'}
        </button>
      )}
    </div>
  );
}
