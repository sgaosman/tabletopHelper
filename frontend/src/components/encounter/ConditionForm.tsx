import { useId, useState, type FormEvent } from 'react';
import { ALL_CONDITIONS } from '../../utils/conditions';
import { useToast } from '../../hooks/useToast';
import type { EncounterParticipant } from '../../types/encounter';
import { FIELD_LABEL, FIELD_INPUT, SUBMIT_BUTTON } from './formStyles';

/**
 * Adds a condition, for a number of rounds or until removed. `onSubmit` sends it; the form empties once it
 * succeeds, and choosing no condition sends nothing. A failure shows as a toast and keeps what was chosen. Given
 * `sources`, frightened also asks what the creature is frightened of (PHB p.290, D201), so its disadvantage applies only
 * while that creature is in sight; "No one in particular" sends none.
 */
export default function ConditionForm({ onSubmit, sources = [] }: {
  onSubmit: (condition: string, durationRounds?: number, sourceParticipantId?: string) => Promise<unknown>;
  sources?: EncounterParticipant[];
}) {
  const id = useId();
  const [condition, setCondition] = useState('');
  const [duration, setDuration] = useState('');
  const [source, setSource] = useState('');
  const [loading, setLoading] = useState(false);
  const toast = useToast();
  const asksSource = condition === 'frightened' && sources.length > 0;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (loading || !condition) return;
    setLoading(true);
    try {
      const rounds = duration ? parseInt(duration) : undefined;
      if (asksSource && source) {
        await onSubmit(condition, rounds, source);
      } else {
        await onSubmit(condition, rounds);
      }
      setCondition('');
      setDuration('');
      setSource('');
    } catch (err: unknown) {
      toast.errorFrom(err);
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
      <div className="flex-1 min-w-40">
        <label htmlFor={`${id}-condition`} className={FIELD_LABEL}>Condition</label>
        <select id={`${id}-condition`} value={condition} onChange={e => setCondition(e.target.value)} className={FIELD_INPUT} autoFocus>
          <option value="">Select condition...</option>
          {ALL_CONDITIONS.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
      </div>
      {asksSource && (
        <div className="flex-1 min-w-40">
          <label htmlFor={`${id}-source`} className={FIELD_LABEL}>Frightened of</label>
          <select id={`${id}-source`} value={source} onChange={e => setSource(e.target.value)} className={FIELD_INPUT}>
            <option value="">No one in particular</option>
            {sources.map(p => <option key={p.id} value={p.id}>{p.displayName}</option>)}
          </select>
        </div>
      )}
      <div className="w-28">
        <label htmlFor={`${id}-duration`} className={FIELD_LABEL}>Duration (rounds)</label>
        <input id={`${id}-duration`} type="number" min="1" value={duration} onChange={e => setDuration(e.target.value)}
          placeholder="&#8734;" className={FIELD_INPUT} />
      </div>
      <button type="submit" disabled={loading} className={SUBMIT_BUTTON}>
        {loading ? '...' : 'Apply'}
      </button>
    </form>
  );
}
