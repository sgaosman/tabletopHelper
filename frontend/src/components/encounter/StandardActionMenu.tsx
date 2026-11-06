import { useState, type FormEvent } from 'react';
import { Footprints } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useBonusActionFeatures, useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import {
  STANDARD_ACTIONS, bonusActionGrant, bonusActionRefusal, canBeBonusAction, chargeBlock, type StandardAction,
} from '../../utils/turnEconomy';
import Modal from '../ui/Modal';
import ObjectInteractionBox from './ObjectInteractionBox';
import { FIELD_INPUT, FIELD_LABEL, SUBMIT_BUTTON } from './formStyles';

type ActionCost = 'ACTION' | 'BONUS_ACTION';

/** What the action could cost the participant, and why its turn can't pay for each, as far as the page can tell. */
interface ActionChoice {
  action: StandardAction;
  label: string;
  summary: string;
  actionBlock: string | null;
  /** Whether a bonus action is offered: with Cunning Action or Nimble Escape, and always to the DM. */
  bonusOffered: boolean;
  /** What grants the bonus action, or null when nothing the page knows of does. */
  grant: string | null;
  bonusBlock: string | null;
}

function choicesFor(participant: EncounterParticipant, features: string[] | null, asDm: boolean): ActionChoice[] {
  const actionBlock = chargeBlock(participant, { cost: 'ACTION' });
  const bonusBudget = chargeBlock(participant, { cost: 'BONUS_ACTION' });
  return STANDARD_ACTIONS.map(({ action, label, summary }) => {
    const grant = bonusActionGrant(action, features ?? []);
    const bonusOffered = canBeBonusAction(action) && (grant !== null || asDm);
    return {
      action, label, summary, actionBlock, bonusOffered, grant,
      bonusBlock: bonusBudget ?? (grant ? null : bonusActionRefusal(action)),
    };
  });
}

/**
 * The standard actions other than Attack and Cast a Spell (PHB p.192–193, D174): Dash, Disengage, Dodge, Help (naming
 * the creature helped), Hide, Ready (naming its trigger), Search and Use an Object, each an action, or a bonus action
 * for Dash, Disengage and Hide where Cunning Action or Nimble Escape grants it. A player is offered the bonus action
 * only when their character's features include one of them; the DM always is, and the server checks. What the turn
 * can't pay for, worked out with utils/turnEconomy, is greyed for a player with the reason and only warned about for
 * the DM, whom the server never refuses; the server's refusal shows too. Below them is the object interaction box.
 */
export default function StandardActionMenu({ participant, participants, asDm = false, onClose }: {
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  asDm?: boolean;
  onClose: () => void;
}) {
  const actions = useEncounterAction();
  const features = useBonusActionFeatures(participant, { dm: asDm });
  const [chosen, setChosen] = useState<StandardAction | null>(null);
  const [cost, setCost] = useState<ActionCost>('ACTION');
  const [targetId, setTargetId] = useState('');
  const [note, setNote] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');

  const choices = choicesFor(participant, features, asDm);
  const choice = choices.find(c => c.action === chosen) ?? null;
  // A player's action is out of reach only when neither an action nor an offered bonus action can pay for it
  const unavailable = (c: ActionChoice) => !asDm && c.actionBlock !== null && (!c.bonusOffered || c.bonusBlock !== null);
  const helpable = participants.filter(p => p.id !== participant.id && p.lifeState !== 'DEAD');

  function choose(c: ActionChoice, presetNote = '') {
    setChosen(c.action);
    // A player who can't pay with the action but can with the bonus action starts on it
    setCost(!asDm && c.actionBlock && c.bonusOffered && !c.bonusBlock ? 'BONUS_ACTION' : 'ACTION');
    setTargetId('');
    setNote(presetNote);
    setError('');
  }

  const block = choice ? (cost === 'ACTION' ? choice.actionBlock : choice.bonusBlock) : null;
  const missing = choice?.action === 'READY' && !note.trim() ? 'Ready needs its trigger'
    : choice?.action === 'HELP' && !targetId ? 'Choose the creature to help' : null;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!choice || sending || missing || (block && !asDm)) return;
    setSending(true);
    setError('');
    try {
      await actions.standardAction({
        participantId: participant.id,
        action: choice.action,
        // Left out, the action costs an action
        cost: cost === 'BONUS_ACTION' ? 'BONUS_ACTION' : undefined,
        targetId: choice.action === 'HELP' ? targetId : undefined,
        note: note.trim() || undefined,
      });
      onClose();
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setSending(false);
    }
  }

  const useAnObject = (description: string) => {
    const c = choices.find(x => x.action === 'USE_OBJECT');
    if (c) choose(c, description);
  };

  return (
    <Modal title={<><Footprints className="w-5 h-5 text-ink" />Actions</>} subtitle={participant.displayName}
      size="lg" onClose={onClose}>
      <div className="space-y-4">
        <ul className="grid grid-cols-1 sm:grid-cols-2 gap-2" aria-label="Standard actions">
          {choices.map(c => {
            const off = unavailable(c);
            const reason = c.actionBlock && (!c.bonusOffered || c.bonusBlock) ? c.actionBlock : null;
            const reasonId = `standard-reason-${c.action}`;
            return (
              <li key={c.action}>
                <button type="button" onClick={() => choose(c)} disabled={off} aria-pressed={chosen === c.action}
                  aria-describedby={reason ? reasonId : undefined}
                  className={`w-full text-left px-3 py-2 border font-body ${chosen === c.action ? 'border-ink bg-ink/10' : 'border-rule bg-page-alt hover:bg-rule'} disabled:opacity-50 disabled:hover:bg-page-alt`}>
                  <span className="block font-heading text-[15px] font-semibold text-ink">
                    {c.label}
                    {c.bonusOffered && c.grant && <span className="ml-2 text-[11px] text-muted">bonus action with {c.grant}</span>}
                  </span>
                  <span className="block text-[12.5px] font-semibold text-muted">{c.summary}</span>
                </button>
                {reason && (
                  <p id={reasonId} className={`font-body text-[12.5px] font-semibold mt-0.5 ${asDm ? 'text-cls-fighter' : 'text-debuff'}`}>
                    {asDm ? `Over the action economy: ${reason}` : reason}
                  </p>
                )}
              </li>
            );
          })}
        </ul>

        {choice && (
          <form onSubmit={handleSubmit} className="space-y-3 border-t border-rule pt-3" aria-label={`Take ${choice.label}`}>
            {choice.bonusOffered && (
              <div>
                <label htmlFor="standard-cost" className={FIELD_LABEL}>Cost</label>
                <select id="standard-cost" value={cost} onChange={e => setCost(e.target.value as ActionCost)} className={FIELD_INPUT}>
                  <option value="ACTION">Action</option>
                  <option value="BONUS_ACTION">Bonus action{choice.grant ? ` (${choice.grant})` : ''}</option>
                </select>
              </div>
            )}
            {choice.action === 'HELP' && (
              <div>
                <label htmlFor="standard-help-target" className={FIELD_LABEL}>Creature to help</label>
                <select id="standard-help-target" value={targetId} onChange={e => setTargetId(e.target.value)} className={FIELD_INPUT}>
                  <option value="">Choose...</option>
                  {helpable.map(p => <option key={p.id} value={p.id}>{p.displayName}</option>)}
                </select>
              </div>
            )}
            {choice.action !== 'DASH' && choice.action !== 'DISENGAGE' && choice.action !== 'DODGE' && choice.action !== 'HIDE' && (
              <div>
                <label htmlFor="standard-note" className={FIELD_LABEL}>{choice.action === 'READY' ? 'Trigger' : 'Note (optional)'}</label>
                <input id="standard-note" type="text" value={note} onChange={e => setNote(e.target.value)} maxLength={200}
                  placeholder={choice.action === 'READY' ? 'When the goblin comes through the door, I attack it' : ''}
                  className={FIELD_INPUT} />
              </div>
            )}
            {block && (
              <p className={`font-body text-[13px] font-semibold ${asDm ? 'text-cls-fighter' : 'text-debuff'}`}>
                {asDm ? `Over the action economy: ${block}` : block}
              </p>
            )}
            {missing && <p className="font-body text-[13px] font-semibold text-muted">{missing}</p>}
            {error && <p role="alert" className="font-body text-[15px] font-semibold text-debuff">{error}</p>}
            <button type="submit" disabled={sending || missing !== null || (block !== null && !asDm)} className={SUBMIT_BUTTON}>
              {sending ? '...' : `Take ${choice.label}`}
            </button>
          </form>
        )}

        <div className="border-t border-rule pt-3">
          <ObjectInteractionBox participant={participant} asDm={asDm} onUseAnObject={useAnObject} />
        </div>
      </div>
    </Modal>
  );
}
