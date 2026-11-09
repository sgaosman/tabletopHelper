import { useState } from 'react';
import { Music } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useEncounterAction } from '../../queries/encounters';
import type { BardicInspirationRequest } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import { heldInspiration, INSPIRATION_TEXT } from '../../utils/classFeatures';

const BUTTON = 'flex items-center gap-1.5 px-3 py-1.5 font-heading text-[12px] font-semibold tracking-[0.02em] border '
  + 'disabled:opacity-50';

type InspirationUse = BardicInspirationRequest['use'];

/**
 * A held Bardic Inspiration die (PHB p.53, M17), for its holder's player on their own character and for the DM on any
 * row. The die is added after the d20 is seen, before the result is known, so the holder keeps it for the next failed
 * saving throw the server rolls, where it is rolled and added only on a failure, or rolls it now for an ability check or
 * a save the DM is resolving by hand. An attack adds it from the attack form. The server decides, and its refusal
 * shows.
 */
export default function InspirationControls({ participant }: { participant: EncounterParticipant }) {
  const actions = useEncounterAction();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const held = heldInspiration(participant);
  if (!held?.heldDie) return null;
  const die = held.heldDie;

  async function use(choice: InspirationUse) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await actions.bardicInspiration({ participantId: participant.id, use: choice });
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label={`${participant.displayName}'s Bardic Inspiration`}
      className="mt-2 p-3 bg-page-alt border border-rule space-y-2">
      <p className="font-body text-[13px] font-semibold text-ink" title={INSPIRATION_TEXT}>
        <Music className="w-3.5 h-3.5 inline mr-1" aria-hidden="true" />
        Bardic Inspiration d{die.sides}
        {die.onFailedSave ? ': kept for the next failed save' : ': kept for an attack or a roll by hand'}
      </p>
      <div className="flex flex-wrap gap-2">
        <button type="button" disabled={busy} aria-pressed={die.onFailedSave}
          onClick={() => { void use(die.onFailedSave ? 'KEEP' : 'ON_FAILED_SAVE'); }}
          className={`${BUTTON} ${die.onFailedSave ? 'bg-buff text-card border-buff' : 'bg-card text-ink border-rule'}`}>
          Use on next failed save
        </button>
        <button type="button" disabled={busy} onClick={() => { void use('ROLL'); }}
          className={`${BUTTON} bg-card text-ink border-rule`}>
          Roll now (check or save by hand)
        </button>
      </div>
      {error && <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{error}</p>}
    </section>
  );
}
