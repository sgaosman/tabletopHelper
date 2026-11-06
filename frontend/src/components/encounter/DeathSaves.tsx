import { useState } from 'react';
import type { EncounterParticipant } from '../../types/encounter';

/**
 * A Dying participant's death save successes and failures (D161). `onRoll` adds the Roll button, which pages pass
 * only while the encounter is Active (D166). `showDue` adds the DM's "Death save due" badge: a Dying PC whose turn
 * has started is due one until it rolls (D163). The roll stands out while one is due.
 */
export default function DeathSaves({ participant, onRoll, showDue = false }: {
  participant: EncounterParticipant;
  onRoll?: () => Promise<unknown>;
  showDue?: boolean;
}) {
  const [rolling, setRolling] = useState(false);

  async function handleRoll() {
    if (!onRoll) return;
    setRolling(true);
    try {
      await onRoll();
    } finally {
      setRolling(false);
    }
  }

  return (
    <div className="flex items-center gap-3 mt-1">
      <div className="flex items-center gap-1">
        <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">Saves:</span>
        {[0, 1, 2].map(i => (
          <div key={`s${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveSuccesses ? 'bg-buff border-buff' : 'border-rule-light'}`} />
        ))}
      </div>
      <div className="flex items-center gap-1">
        <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">Fails:</span>
        {[0, 1, 2].map(i => (
          <div key={`f${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveFailures ? 'bg-debuff border-debuff' : 'border-rule-light'}`} />
        ))}
      </div>
      {showDue && participant.deathSaveDue && (
        <span role="status" className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff">
          Death save due
        </span>
      )}
      {onRoll && (
        <button
          onClick={handleRoll}
          disabled={rolling}
          className={`px-2 py-0.5 font-body text-[16px] font-semibold disabled:opacity-50 ${
            participant.deathSaveDue ? 'bg-debuff text-card hover:bg-debuff/90' : 'bg-page-alt hover:bg-rule text-muted'
          }`}
        >
          {rolling ? '...' : 'Roll'}
        </button>
      )}
    </div>
  );
}
