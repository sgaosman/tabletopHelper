import { useState } from 'react';
import { apiErrorMessage } from '../../api/errors';
import { useEncounterResults } from '../../queries/encounters';
import type { CombatResultPreview } from '../../types/encounter';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  encounterName: string;
  /** Ends the encounter, writing its results back to the character sheets or not */
  onEnd: (applyResults: boolean) => Promise<unknown>;
  onClose: () => void;
}

// "Level 3 slots" or "Pact slots", as a slot's key names them
function slotName(slot: string): string {
  return slot.startsWith('pact_') ? 'Pact slots' : `Level ${slot} slots`;
}

// How the character comes out of the fight, when it isn't simply alive
function fate(result: CombatResultPreview): string | null {
  if (result.deadBefore) return 'Already dead';
  if (result.deadAfter) return 'Dies';
  if (result.lifeState === 'DYING' || result.lifeState === 'STABLE') return 'Down: back on the sheet alive at 0 HP';
  return null;
}

function ResultRow({ result }: { result: CombatResultPreview }) {
  const lines: string[] = [];
  if (result.hpAfter !== result.hpBefore) {
    lines.push(`HP ${result.hpBefore} → ${result.hpAfter} of ${result.hpMax}`);
  }
  if (result.hpTempAfter !== result.hpTempBefore) {
    lines.push(`Temporary HP ${result.hpTempBefore} → ${result.hpTempAfter}`);
  }
  for (const slot of result.slots) {
    lines.push(`${slotName(slot.slot)} spent ${slot.usedBefore} → ${slot.usedAfter} of ${slot.max}`);
  }
  for (const pool of result.pools) {
    lines.push(`${pool.displayName} ${pool.currentBefore} → ${pool.currentAfter} of ${pool.maxUses}`);
  }
  const outcome = fate(result);

  return (
    <li className="bg-page-alt border border-rule px-3 py-2">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <p className="font-heading text-[15px] font-bold text-ink">{result.name}</p>
        {outcome && <span className="font-heading text-[12px] font-semibold text-debuff">{outcome}</span>}
      </div>
      {lines.length > 0 ? (
        <ul className="mt-1 space-y-0.5">
          {lines.map(line => <li key={line} className="font-body text-[14px] font-semibold text-muted">{line}</li>)}
        </ul>
      ) : (
        <p className="mt-1 font-body text-[14px] font-semibold text-faint">No change to the sheet</p>
      )}
    </li>
  );
}

/**
 * The DM's End dialog (D166). Ending is final, and the DM decides once for the whole encounter whether its results are
 * written back to the character sheets, which it does by default. The dialog previews what writing them back would do
 * to each player character's sheet as it stands now; the preview can't be edited. Declining writes nothing back, pool
 * uses included, so the preview dims and its heading says so.
 */
export default function EndEncounterModal({ encounterName, onEnd, onClose }: Props) {
  const results = useEncounterResults();
  const [applyResults, setApplyResults] = useState(true);
  const [ending, setEnding] = useState(false);
  const [error, setError] = useState('');

  async function handleEnd() {
    setEnding(true);
    setError('');
    try {
      await onEnd(applyResults);
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
      setEnding(false);
      return;
    }
    onClose();
  }

  return (
    <Modal
      title="End this encounter?"
      subtitle={encounterName}
      onClose={onClose}
      dismissible={!ending}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose} disabled={ending}>Cancel</Button>
          <Button variant="danger" onClick={handleEnd} loading={ending}>
            {ending ? 'Ending...' : 'End encounter'}
          </Button>
        </>
      )}
    >
      <div className="space-y-4">
        <p className="font-body text-[15px] font-semibold text-muted">Ending is final: the fight can't be resumed.</p>

        <section aria-label="Results for each character">
          <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-2">
            {applyResults ? 'What each sheet takes' : 'None of this is written back'}
          </h3>
          {results.isPending ? (
            <p className="font-body text-[15px] text-muted">Working out each character's results...</p>
          ) : results.isError ? (
            <p role="alert" className="font-body text-[15px] text-debuff">{apiErrorMessage(results.error)}</p>
          ) : results.data.length === 0 ? (
            <p className="font-body text-[15px] text-faint">No player characters to write back to.</p>
          ) : (
            <ul className={`space-y-1.5 ${applyResults ? '' : 'opacity-50'}`}>
              {results.data.map(result => <ResultRow key={result.characterId} result={result} />)}
            </ul>
          )}
        </section>

        <label className="flex items-start gap-3 cursor-pointer">
          <input
            type="checkbox"
            checked={applyResults}
            onChange={e => setApplyResults(e.target.checked)}
            disabled={ending}
            className="mt-1 accent-ink"
          />
          <span>
            <span className="block font-heading text-[15px] font-bold text-ink">Apply results to character sheets</span>
            <span className="block font-body text-[13px] font-semibold text-faint">
              {applyResults
                ? 'Each sheet takes the hit points, spell slots and pool uses lost or regained in this fight, and a death.'
                : 'Nothing is written back: every sheet stays as it is, pool uses included.'}
            </span>
          </span>
        </label>

        {error && <p role="alert" className="text-debuff font-body text-[15px]">{error}</p>}
      </div>
    </Modal>
  );
}
