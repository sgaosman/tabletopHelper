import { useId, useState, type FormEvent } from 'react';
import { FIELD_LABEL, FIELD_INPUT, SUBMIT_BUTTON } from './formStyles';
import { useToast } from '../../hooks/useToast';

/**
 * Sets the spell a participant concentrates on, or clears it when left blank. `onSubmit` sends it; the form empties
 * once it succeeds. A failure shows as a toast.
 */
export default function ConcentrationForm({ onSubmit }: {
  onSubmit: (spellName: string | null) => Promise<unknown>;
}) {
  const id = useId();
  const [spellName, setSpellName] = useState('');
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (loading) return;
    setLoading(true);
    try {
      await onSubmit(spellName || null);
      setSpellName('');
    } catch (err: unknown) {
      toast.errorFrom(err);
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex items-end gap-3">
      <div className="flex-1">
        <label htmlFor={`${id}-spell`} className={FIELD_LABEL}>Spell Name (blank to clear)</label>
        <input id={`${id}-spell`} type="text" value={spellName} onChange={e => setSpellName(e.target.value)}
          placeholder="e.g. Bless, Hold Person" className={FIELD_INPUT} autoFocus />
      </div>
      <button type="submit" disabled={loading} className={SUBMIT_BUTTON}>
        {loading ? '...' : 'Apply'}
      </button>
    </form>
  );
}
