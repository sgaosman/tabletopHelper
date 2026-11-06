import { useId, useState, type FormEvent } from 'react';
import { ALL_CONDITIONS } from '../../utils/conditions';
import { FIELD_LABEL, FIELD_INPUT, SUBMIT_BUTTON } from './formStyles';

/**
 * Adds a condition, for a number of rounds or until removed. `onSubmit` sends it; the form empties once it
 * succeeds, and choosing no condition sends nothing.
 */
export default function ConditionForm({ onSubmit }: {
  onSubmit: (condition: string, durationRounds?: number) => Promise<unknown>;
}) {
  const id = useId();
  const [condition, setCondition] = useState('');
  const [duration, setDuration] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (loading || !condition) return;
    setLoading(true);
    try {
      await onSubmit(condition, duration ? parseInt(duration) : undefined);
      setCondition('');
      setDuration('');
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex items-end gap-3">
      <div className="flex-1">
        <label htmlFor={`${id}-condition`} className={FIELD_LABEL}>Condition</label>
        <select id={`${id}-condition`} value={condition} onChange={e => setCondition(e.target.value)} className={FIELD_INPUT} autoFocus>
          <option value="">Select condition...</option>
          {ALL_CONDITIONS.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
      </div>
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
