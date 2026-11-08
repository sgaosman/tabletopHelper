import { useState, type FormEvent } from 'react';
import { apiErrorMessage } from '../../api/errors';
import { useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import { chargeBlock, objectInteractionBlock } from '../../utils/turnEconomy';
import { FIELD_INPUT, FIELD_LABEL } from './formStyles';

/**
 * An object interaction on the participant's turn, such as drawing a sword or opening a door (PHB p.190, D174). The
 * first each turn is free; once it is used, a player's box says another needs the Use an Object action and offers it
 * while an action is left. As in StandardActionMenu, a player's Interact is greyed with the reason when the turn can't
 * have it (incapacitated, off its turn, or the free one used), while the DM's is only warned, since the server never
 * refuses the DM. The server's refusal shows too.
 */
export default function ObjectInteractionBox({ participant, asDm = false, onUseAnObject }: {
  participant: EncounterParticipant;
  asDm?: boolean;
  /** Takes the Use an Object action instead, with what was typed as its note. */
  onUseAnObject?: (description: string) => void;
}) {
  const actions = useEncounterAction();
  const [description, setDescription] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [done, setDone] = useState('');

  const blocked = objectInteractionBlock(participant);
  const refused = blocked !== null && !asDm;
  const used = participant.turnEconomy?.objectInteractionUsed ?? false;
  const fieldId = `object-interaction-${participant.id}`;
  const reasonId = `${fieldId}-reason`;
  // The Use an Object action is offered only while the turn can pay for it
  const offerUseAnObject = used && !asDm && participant.isCurrentTurn
    && chargeBlock(participant, { cost: 'ACTION' }) === null;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const text = description.trim();
    if (!text || sending || refused) return;
    setSending(true);
    setError('');
    setDone('');
    try {
      await actions.objectInteraction({ participantId: participant.id, description: text });
      setDescription('');
      setDone(`Logged: ${text}`);
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setSending(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-1.5">
      <label htmlFor={fieldId} className={FIELD_LABEL}>Object interaction{used ? '' : ' (free once a turn)'}</label>
      <div className="flex gap-2">
        <input id={fieldId} type="text" value={description} onChange={e => setDescription(e.target.value)}
          placeholder="Draw a sword, open a door..." className={FIELD_INPUT} maxLength={200} />
        <button type="submit" disabled={sending || refused || !description.trim()} aria-describedby={blocked ? reasonId : undefined}
          className="px-3 py-2 bg-ink hover:bg-ink/90 disabled:bg-ink/50 text-card font-body text-[15px] font-semibold whitespace-nowrap">
          {sending ? '...' : 'Interact'}
        </button>
      </div>
      {blocked && (
        <p id={reasonId} className={`font-body text-[13px] font-semibold ${asDm ? 'text-cls-fighter' : 'text-debuff'}`}>
          {asDm ? `Over the action economy: ${blocked}` : blocked}
        </p>
      )}
      {offerUseAnObject && onUseAnObject && (
        <button type="button" onClick={() => onUseAnObject(description.trim())}
          className="font-body text-[13px] font-semibold text-ink underline hover:text-muted">
          Take the Use an Object action instead
        </button>
      )}
      {done && <p role="status" className="font-body text-[13px] font-semibold text-muted">{done}</p>}
      {error && <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{error}</p>}
    </form>
  );
}
