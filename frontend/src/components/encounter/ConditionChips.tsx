import { Fragment } from 'react';
import { X } from 'lucide-react';
import type { ConditionEntry, EncounterParticipant } from '../../types/encounter';
import type { GlossaryEntry } from '../../types/reference';
import RepeatSavePrompt from './RepeatSavePrompt';
import { featureMarker } from '../../utils/classFeatures';
import { useGlossary } from '../../hooks/useGlossary';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';
import { exhaustionEffects, exhaustionLabel } from '../../utils/exhaustion';

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
 */
export default function ConditionChips({ participant, roundNumber, onRemove, showRepeatSave = false }: {
  participant: EncounterParticipant;
  roundNumber: number;
  onRemove?: (condition: ConditionEntry) => unknown;
  showRepeatSave?: boolean;
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
        const label = marker
          ? `${marker.label}${remaining != null ? ` (${remaining})` : ''}`
          : `${c.name}${c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}${remaining != null ? ` (${remaining})` : ''}`;
        const chip = marker ? FEATURE_CHIP : CHIP;
        // A Repeat Save is rolled at the end of each of the creature's turns (D162)
        const saveEnds = c.repeatSave?.byAction
          ? `; a ${c.repeatSave.ability} save DC ${c.repeatSave.dc} made with its action ends the spell`
          : c.repeatSave ? `; ${c.repeatSave.ability} save DC ${c.repeatSave.dc} at the end of each turn ends it`
          : marker ? `: ${marker.description}` : '';
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
