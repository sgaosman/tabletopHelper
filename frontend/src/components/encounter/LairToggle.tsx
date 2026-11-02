import { useState } from 'react';
import { Castle } from 'lucide-react';
import type { EncounterParticipant } from '../../types/encounter';
import { apiErrorMessage } from '../../api/errors';

/**
 * The DM's "In lair" switch for a monster whose action templates have lair actions (D163). While a monster
 * fighting in its lair is alive, the Lair takes a turn on initiative 20, losing ties. `onToggle` receives
 * whether the monster should be in its lair and rejects if the change fails.
 */
export default function LairToggle({ participant, inLair, onToggle }: {
  participant: EncounterParticipant;
  inLair: boolean;
  onToggle: (inLair: boolean) => Promise<unknown>;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  async function handleClick() {
    setSaving(true);
    try {
      await onToggle(!inLair);
      setError('');
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <button
        type="button"
        onClick={handleClick}
        disabled={saving}
        aria-pressed={inLair}
        title={inLair
          ? `${participant.displayName} is fighting in its lair: the Lair acts on initiative 20`
          : `Put ${participant.displayName} in its lair, so the Lair acts on initiative 20`}
        className={`inline-flex items-center gap-1 font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 border transition-colors disabled:opacity-50 ${
          inLair ? 'text-cls-warlock bg-cls-warlock/10 border-cls-warlock' : 'text-faint bg-page-alt border-rule hover:text-ink'
        }`}
      >
        <Castle className="w-3 h-3" /> In lair
      </button>
      {error && <span role="alert" className="font-body text-[12.5px] font-semibold text-debuff">{error}</span>}
    </>
  );
}
