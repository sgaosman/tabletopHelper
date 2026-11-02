import { useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import type { EncounterParticipant } from '../../types/encounter';

/**
 * The DM's show/hide switch for a monster (D157). A hidden monster is left out of the Player View:
 * the player's initiative list, the target pickers and the combat log. `onToggle` receives the
 * visibility to set and rejects if the change fails.
 */
export default function VisibilityToggle({ participant, onToggle }: {
  participant: EncounterParticipant;
  onToggle: (visible: boolean) => Promise<void>;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const hidden = participant.isVisibleToPlayers === false;
  const action = hidden ? 'Show to players' : 'Hide from players';

  async function handleClick() {
    setSaving(true);
    try {
      await onToggle(hidden);
      setError('');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { error?: string } } })
        ?.response?.data?.error || 'Could not change who sees this monster';
      setError(msg);
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      {hidden && (
        <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-muted bg-page-alt border border-rule">
          Hidden
        </span>
      )}
      <button
        type="button"
        onClick={handleClick}
        disabled={saving}
        aria-label={action}
        title={action}
        className="p-1 text-faint hover:text-ink transition-colors disabled:opacity-50"
      >
        {hidden ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
      </button>
      {error && <span role="alert" className="font-body text-[12.5px] font-semibold text-debuff">{error}</span>}
    </>
  );
}
