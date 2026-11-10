import { useState } from 'react';
import { Dices } from 'lucide-react';
import { useEncounterAction } from '../../queries/encounters';
import { apiErrorMessage } from '../../api/errors';
import type { ConditionEntry, EncounterParticipant } from '../../types/encounter';
import Button from '../ui/Button';

/**
 * A Repeat Save waiting for its roll (D162): the campaign leaves player characters' Repeat Saves to their
 * players, and the turn that ended asked for one. The server rolls it when the player, or the DM, clicks, and a
 * success ends the condition. A save the creature makes with its action, as against Otto's Irresistible Dance (D188),
 * is offered for as long as the condition lasts, spends the action, and its success ends the spell. Shows nothing for a
 * condition with no roll waiting.
 *
 * @param compact the DM's small button beside the condition, rather than the player's prompt
 */
export default function RepeatSavePrompt({ participant, condition, compact = false }: {
  participant: EncounterParticipant;
  condition: ConditionEntry;
  compact?: boolean;
}) {
  const actions = useEncounterAction();
  const [rolling, setRolling] = useState(false);
  const [error, setError] = useState('');
  const save = condition.repeatSave;
  // A save made with the creature's action, as against Otto's Irresistible Dance, waits for it on any turn (D188)
  const byAction = save?.byAction === true;
  if (!save || (!save.awaitingRoll && !byAction)) return null;

  async function roll() {
    setRolling(true);
    setError('');
    try {
      if (byAction) {
        await actions.rollActionSave(participant.id, condition.id);
      } else {
        await actions.rollRepeatSave(participant.id, condition.id);
      }
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setRolling(false);
    }
  }

  const label = byAction
    ? `Use the action: ${save.ability} save (DC ${save.dc}) to end ${condition.name}`
    : `Roll ${save.ability} save (DC ${save.dc}) to end ${condition.name}`;
  if (compact) {
    return (
      <>
        <button
          type="button"
          onClick={roll}
          disabled={rolling}
          title={label}
          className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-cls-monk bg-cls-monk-bg border border-cls-monk hover:opacity-75 disabled:opacity-50"
        >
          {rolling ? '...' : byAction ? `Action: ${save.ability} save` : `Roll ${save.ability} save`}
        </button>
        {error && <span role="alert" className="font-body text-[12.5px] font-semibold text-debuff">{error}</span>}
      </>
    );
  }

  return (
    <div role="status" className="flex items-center justify-between gap-3 px-4 py-2.5 bg-cls-monk/10 border border-cls-monk/30">
      <p className="font-heading text-[15px] font-semibold tracking-[0.01em] text-cls-monk">
        {byAction
          ? `Use your action on a ${save.ability} save (DC ${save.dc}) to end ${condition.name}`
          : `Repeat your ${save.ability} save (DC ${save.dc}) to end ${condition.name}`}
      </p>
      <Button size="sm" onClick={roll} loading={rolling} aria-label={label}>
        <Dices className="w-4 h-4" /> Roll save
      </Button>
      {error && <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{error}</p>}
    </div>
  );
}
