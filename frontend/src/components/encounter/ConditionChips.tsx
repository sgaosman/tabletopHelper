import { Fragment } from 'react';
import { X } from 'lucide-react';
import type { ConditionEntry, EncounterParticipant } from '../../types/encounter';
import RepeatSavePrompt from './RepeatSavePrompt';
import { featureMarker } from '../../utils/classFeatures';

const CHIP_BASE = 'font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5';
const CHIP = `${CHIP_BASE} text-debuff bg-debuff-bg border border-debuff`;
// A class feature in force, such as Rage (M17), is the creature's own benefit, not a condition of appendix A
const FEATURE_CHIP = `${CHIP_BASE} text-buff bg-buff-bg border border-buff`;

/**
 * A participant's conditions, each with its source spell and the rounds it has left. With `onRemove` each chip is
 * a button that removes its condition. `showRepeatSave` adds the DM's compact roll beside a condition whose Repeat
 * Save waits on a player (D162). A class feature in force, Raging or Reckless (M17), shows in the buff colours with
 * what it does.
 */
export default function ConditionChips({ participant, roundNumber, onRemove, showRepeatSave = false }: {
  participant: EncounterParticipant;
  roundNumber: number;
  onRemove?: (condition: ConditionEntry) => void;
  showRepeatSave?: boolean;
}) {
  const conditions = participant.activeConditions ?? [];
  if (conditions.length === 0) return null;

  return (
    <div className="flex gap-1 mt-1 flex-wrap">
      {conditions.map(c => {
        const remaining = c.duration != null ? Math.max(0, c.duration - (roundNumber - c.appliedRound)) : null;
        const marker = featureMarker(c);
        const label = marker
          ? `${marker.label}${remaining != null ? ` (${remaining})` : ''}`
          : `${c.name}${c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}${remaining != null ? ` (${remaining})` : ''}`;
        const chip = marker ? FEATURE_CHIP : CHIP;
        // A Repeat Save is rolled at the end of each of the creature's turns (D162)
        const saveEnds = c.repeatSave ? `; ${c.repeatSave.ability} save DC ${c.repeatSave.dc} at the end of each turn ends it`
          : marker ? `: ${marker.description}` : '';
        return (
          <Fragment key={c.id}>
            {onRemove ? (
              <button
                onClick={() => onRemove(c)}
                className={`${chip} hover:opacity-75 cursor-pointer`}
                title={`Click to remove ${c.name}${remaining != null ? ` (${remaining} rounds left)` : ''}${saveEnds}`}
              >
                {label} <X className="w-2.5 h-2.5 inline" />
              </button>
            ) : (
              <span
                className={chip}
                title={remaining != null || saveEnds ? `${remaining != null ? `${remaining} rounds left` : ''}${saveEnds}` : undefined}
              >
                {label}
              </span>
            )}
            {showRepeatSave && <RepeatSavePrompt participant={participant} condition={c} compact />}
          </Fragment>
        );
      })}
    </div>
  );
}
