import { Fragment } from 'react';
import { Eye, EyeOff, X } from 'lucide-react';
import type { ConditionEntry, EncounterParticipant } from '../../types/encounter';
import type { GlossaryEntry } from '../../types/reference';
import RepeatSavePrompt from './RepeatSavePrompt';
import { featureMarker } from '../../utils/classFeatures';
import { useGlossary } from '../../hooks/useGlossary';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';
import { exhaustionEffects, exhaustionLabel } from '../../utils/exhaustion';
import { actionEndText, fearFor, fearReminders, fearSightNote, linkedName } from '../../utils/conditions';
import IconButton from '../ui/IconButton';

const CHIP_BASE = 'font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5';
const CHIP = `${CHIP_BASE} text-debuff bg-debuff-bg border border-debuff`;
// A class feature in force, such as Rage (M17), is the creature's own benefit, not a condition of appendix A
const FEATURE_CHIP = `${CHIP_BASE} text-buff bg-buff-bg border border-buff`;

/**
 * A participant's conditions, each with its source spell and the rounds it has left. With `onRemove` each chip is
 * a button that removes its condition. `showRepeatSave` adds the DM's compact roll beside a condition whose Repeat
 * Save waits on a player (D162). A class feature in force, Raging or Reckless (M17), shows in the buff colours with
 * what it does. Without `onRemove`, a condition the Glossary defines is a button that opens its definition (M19).
 * While a removal is being sent the chips wait, and a failed one shows as a toast. A creature's exhaustion shows first
 * as one chip, "Exhausted (3)", with what its level does (PHB p.291, D183); it opens the Glossary's Exhaustion, and
 * the DM changes it with the exhaustion control rather than by removing the chip.
 * A frightened chip names what the creature is frightened of and whether it is in sight, "frightened of Adult Red
 * Dragon · in sight", and its title says what that does and what the table applies by hand: no moving closer, and
 * Fear's Dash away (PHB p.290, D201). `onToggleFearSight`, the DM's, adds a button beside it that marks the source out
 * of sight or back in sight.
 */
export default function ConditionChips({ participant, roundNumber, onRemove, showRepeatSave = false,
  onToggleFearSight }: {
  participant: EncounterParticipant;
  roundNumber: number;
  onRemove?: (condition: ConditionEntry) => unknown;
  showRepeatSave?: boolean;
  onToggleFearSight?: (condition: ConditionEntry, outOfSight: boolean) => unknown;
}) {
  const glossary = useGlossary();
  const { pending, run } = usePending();
  const toast = useToast();
  const conditions = participant.activeConditions ?? [];
  const exhaustion = participant.exhaustionLevel ?? 0;
  if (conditions.length === 0 && exhaustion === 0) return null;

  return (
    <div className="flex gap-1 mt-1 flex-wrap">
      {exhaustion > 0 && (
        <ReadOnlyChip
          chip={CHIP}
          label={exhaustionLabel(exhaustion)}
          title={exhaustionEffects(exhaustion).join('; ')}
          entry={glossary?.findTerm('exhaustion')}
          onOpen={id => glossary?.openTerm(id)}
        />
      )}
      {conditions.map(c => {
        const remaining = c.duration != null ? Math.max(0, c.duration - (roundNumber - c.appliedRound)) : null;
        const marker = featureMarker(c);
        // A fear names its source and whether it is in sight (D201)
        const fear = marker ? undefined : fearFor(participant, c);
        const label = marker
          ? `${marker.label}${remaining != null ? ` (${remaining})` : ''}`
          : `${c.name}${fear?.sourceName ? ` of ${fear.sourceName}` : ''}${c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}`
            + `${remaining != null ? ` (${remaining})` : ''}${fear ? ` · ${fear.inSight ? 'in sight' : 'out of sight'}` : ''}`;
        const chip = marker ? FEATURE_CHIP : CHIP;
        // A Repeat Save is rolled at the end of each of the creature's turns (D162); one made with the action, or a
        // monster's grapple escaped with it, waits for that (D188, D203); and a condition tied to another lasts while
        // that one does, as a restraint until its grapple ends (D203)
        const linked = linkedName(participant, c);
        const saveEnds = (c.repeatSave?.byAction
          ? actionEndText(c)
          : c.repeatSave ? `; ${c.repeatSave.ability} save DC ${c.repeatSave.dc} at the end of each turn ends it`
          : marker ? `: ${marker.description}` : '')
          + (linked ? `; lasts while ${linked}` : '')
          + (fear ? `; ${fearSightNote(fear)}; ${fearReminders(c, fear).join('; ')}` : '');
        return (
          <Fragment key={c.id}>
            {onRemove ? (
              <button
                onClick={() => { run(async () => { await onRemove(c); }).catch(toast.errorFrom); }}
                disabled={pending}
                className={`${chip} hover:opacity-75 cursor-pointer disabled:opacity-50 disabled:cursor-wait`}
                title={`Click to remove ${c.name}${remaining != null ? ` (${remaining} rounds left)` : ''}${saveEnds}`}
              >
                {label} <X className="w-2.5 h-2.5 inline" />
              </button>
            ) : (
              <ReadOnlyChip
                chip={chip}
                label={label}
                title={remaining != null || saveEnds ? `${remaining != null ? `${remaining} rounds left` : ''}${saveEnds}` : undefined}
                entry={marker ? undefined : glossary?.findTerm(c.name)}
                onOpen={id => glossary?.openTerm(id)}
              />
            )}
            {fear && onToggleFearSight && (
              <IconButton
                label={fear.markedOutOfSight
                  ? `Mark ${fear.sourceName ?? 'the source'} back in sight`
                  : `Mark ${fear.sourceName ?? 'the source'} out of sight`}
                onClick={() => { run(async () => { await onToggleFearSight(c, !fear.markedOutOfSight); }).catch(toast.errorFrom); }}
                disabled={pending}
                className="text-muted hover:text-ink disabled:opacity-50"
              >
                {fear.markedOutOfSight ? <Eye className="w-3 h-3" /> : <EyeOff className="w-3 h-3" />}
              </IconButton>
            )}
            {showRepeatSave && <RepeatSavePrompt participant={participant} condition={c} compact />}
          </Fragment>
        );
      })}
    </div>
  );
}

/** A chip that only shows its condition; one the Glossary defines opens the definition (M19). */
function ReadOnlyChip({ chip, label, title, entry, onOpen }: {
  chip: string;
  label: string;
  title?: string;
  entry?: GlossaryEntry;
  onOpen: (id: string) => void;
}) {
  if (!entry) return <span className={chip} title={title}>{label}</span>;
  return (
    <button
      type="button"
      aria-haspopup="dialog"
      onClick={() => onOpen(entry.id)}
      className={`${chip} cursor-help underline decoration-dotted underline-offset-2`}
      title={title ? `${title}. Click for what ${entry.name} means` : `What ${entry.name} means`}
    >
      {label}
    </button>
  );
}
