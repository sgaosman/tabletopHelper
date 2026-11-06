import { Fragment } from 'react';
import { X } from 'lucide-react';
import type { ConditionEntry, EncounterParticipant } from '../../types/encounter';
import RepeatSavePrompt from './RepeatSavePrompt';

const CHIP = 'font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff';

/**
 * A participant's conditions, each with its source spell and the rounds it has left. With `onRemove` each chip is
 * a button that removes its condition. `showRepeatSave` adds the DM's compact roll beside a condition whose Repeat
 * Save waits on a player (D162).
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
        const label = `${c.name}${c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}${remaining != null ? ` (${remaining})` : ''}`;
        // A Repeat Save is rolled at the end of each of the creature's turns (D162)
        const saveEnds = c.repeatSave ? `; ${c.repeatSave.ability} save DC ${c.repeatSave.dc} at the end of each turn ends it` : '';
        return (
          <Fragment key={c.id}>
            {onRemove ? (
              <button
                onClick={() => onRemove(c)}
                className={`${CHIP} hover:opacity-75 cursor-pointer`}
                title={`Click to remove ${c.name}${remaining != null ? ` (${remaining} rounds left)` : ''}${saveEnds}`}
              >
                {label} <X className="w-2.5 h-2.5 inline" />
              </button>
            ) : (
              <span
                className={CHIP}
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
