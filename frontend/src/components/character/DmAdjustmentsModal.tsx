import { useState, type FormEvent } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useAddAdjustment, useRemoveAdjustment } from '../../queries/characters';
import type {
  AsiChoice, DamageDefence, DmAdjustment, DmAdjustmentKind, DmAdjustmentRequest, PlayerCharacter,
} from '../../types/character';
import {
  ADJUSTABLE_STATS, ADJUSTMENT_KINDS, DAMAGE_DEFENCES, DAMAGE_TYPES, activeAdjustments, describeAdjustment,
  removedAdjustments,
} from '../../utils/dmAdjustments';
import Button from '../ui/Button';
import Input, { Select } from '../ui/Input';
import Modal from '../ui/Modal';
import FeatPicker from './FeatPicker';

interface Props {
  /** A character in the DM's campaign, as the campaign's character list has it */
  character: PlayerCharacter;
  onClose: () => void;
}

// A signed whole number the DM entered, such as "-2" or "+1"; undefined for none, and NaN for anything else
function signedWholeNumber(text: string): number | undefined {
  const trimmed = text.trim();
  if (trimmed === '') return undefined;
  return /^[+-]?\d+$/.test(trimmed) ? Number(trimmed) : NaN;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/**
 * The DM's adjustments to a character in their campaign (D158): those that count, each removable after a
 * confirmation, a form to add another, and the removed ones as a history. A stat changes by an amount, a defence is
 * a resistance, immunity or vulnerability to a damage type (D161), and a feat is granted with the choices an ability
 * score improvement's feat takes. The character's player sees each one and its reason.
 */
export default function DmAdjustmentsModal({ character, onClose }: Props) {
  const addAdjustment = useAddAdjustment();
  const removeAdjustment = useRemoveAdjustment();
  const [kind, setKind] = useState<DmAdjustmentKind>('STAT');
  const [stat, setStat] = useState(ADJUSTABLE_STATS[0].target);
  const [amount, setAmount] = useState('');
  const [defence, setDefence] = useState<DamageDefence>('RESISTANCE');
  const [damageType, setDamageType] = useState('fire');
  const [feat, setFeat] = useState<AsiChoice | null>(null);
  // A new key starts the feat picker afresh once a feat is granted
  const [featPickerKey, setFeatPickerKey] = useState(0);
  const [reason, setReason] = useState('');
  const [confirming, setConfirming] = useState<string | null>(null);
  const [showHistory, setShowHistory] = useState(false);

  const campaignId = character.campaignId ?? '';
  const active = activeAdjustments(character.dmAdjustments);
  const removed = removedAdjustments(character.dmAdjustments);
  const busy = addAdjustment.isPending || removeAdjustment.isPending;
  const error = addAdjustment.error ?? removeAdjustment.error;

  const amountValue = signedWholeNumber(amount);
  const amountInvalid = amountValue !== undefined && (Number.isNaN(amountValue) || amountValue === 0);
  const ready = reason.trim() !== '' && (
    kind === 'STAT' ? amountValue !== undefined && !amountInvalid
      : kind === 'FEAT' ? feat !== null
        : true);

  function chooseKind(next: DmAdjustmentKind) {
    setKind(next);
    setFeat(null);
  }

  function request(): DmAdjustmentRequest {
    switch (kind) {
      case 'STAT': return { kind, target: stat, amount: amountValue, reason: reason.trim() };
      case 'DEFENCE': return { kind, defence, target: damageType, reason: reason.trim() };
      case 'FEAT': return { kind, feat, reason: reason.trim() };
    }
  }

  function handleAdd(e: FormEvent) {
    e.preventDefault();
    if (!ready || busy) return;
    removeAdjustment.reset();
    addAdjustment.mutate({ characterId: character.id, campaignId, adjustment: request() }, {
      onSuccess: () => {
        setAmount('');
        setReason('');
        setFeat(null);
        setFeatPickerKey(k => k + 1);
      },
    });
  }

  function handleRemove(adjustment: DmAdjustment) {
    addAdjustment.reset();
    removeAdjustment.mutate({ characterId: character.id, campaignId, adjustmentId: adjustment.id }, {
      onSettled: () => setConfirming(null),
    });
  }

  return (
    <Modal
      title="DM Adjustments"
      subtitle={character.name}
      size="lg"
      onClose={onClose}
      dismissible={!busy}
      footer={<Button variant="secondary" onClick={onClose} disabled={busy}>Done</Button>}
    >
      <div className="space-y-5">
        {error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">
            {apiErrorMessage(error)}
          </div>
        )}

        <section aria-label="Active adjustments">
          <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-2">Active</h3>
          {active.length === 0 ? (
            <p className="font-body text-[15px] text-faint">No adjustments yet.</p>
          ) : (
            <ul className="space-y-1.5">
              {active.map(adjustment => (
                <li key={adjustment.id} className="bg-page-alt border border-rule px-3 py-2">
                  {confirming === adjustment.id ? (
                    <div className="flex items-center justify-between gap-3 flex-wrap">
                      <p className="font-body text-[15px] font-semibold text-ink">
                        Remove {describeAdjustment(adjustment)}?
                        {adjustment.kind === 'FEAT' && <span className="text-muted"> Its effects are taken back.</span>}
                      </p>
                      <div className="flex gap-2">
                        <Button size="sm" variant="secondary" onClick={() => setConfirming(null)}
                          disabled={removeAdjustment.isPending}>Cancel</Button>
                        <Button size="sm" variant="danger" onClick={() => handleRemove(adjustment)}
                          loading={removeAdjustment.isPending}>Remove</Button>
                      </div>
                    </div>
                  ) : (
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="font-heading text-[15px] font-bold text-ink">{describeAdjustment(adjustment)}</p>
                        <p className="font-body text-[14px] font-semibold text-muted">{adjustment.reason}</p>
                      </div>
                      <Button size="sm" variant="ghost" onClick={() => setConfirming(adjustment.id)} disabled={busy}
                        aria-label={`Remove ${describeAdjustment(adjustment)}`}>Remove</Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>

        <form onSubmit={handleAdd} className="space-y-3 border-t border-rule pt-4" aria-label="Add an adjustment">
          <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint">Add an adjustment</h3>
          <div className="flex gap-2">
            {ADJUSTMENT_KINDS.map(option => (
              <button
                key={option.kind}
                type="button"
                onClick={() => chooseKind(option.kind)}
                aria-pressed={kind === option.kind}
                className={`flex-1 py-2 font-body text-[16px] font-semibold transition-colors ${
                  kind === option.kind ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:text-ink'
                }`}
              >{option.label}</button>
            ))}
          </div>

          {kind === 'STAT' && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <Select label="Stat" value={stat} onChange={e => setStat(e.target.value)}>
                {ADJUSTABLE_STATS.map(s => <option key={s.target} value={s.target}>{s.label}</option>)}
              </Select>
              <Input label="Amount" type="number" step={1} placeholder="1, or -2 to lower it" value={amount}
                onChange={e => setAmount(e.target.value)}
                error={amountInvalid ? 'A whole number other than 0' : undefined} />
            </div>
          )}

          {kind === 'DEFENCE' && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <Select label="Defence" value={defence} onChange={e => setDefence(e.target.value as DamageDefence)}>
                {DAMAGE_DEFENCES.map(d => <option key={d.defence} value={d.defence}>{d.label}</option>)}
              </Select>
              <Select label="Damage type" value={damageType} onChange={e => setDamageType(e.target.value)}>
                {DAMAGE_TYPES.map(type => <option key={type} value={type}>{type}</option>)}
              </Select>
            </div>
          )}

          {kind === 'FEAT' && (
            <div className="space-y-3">
              <FeatPicker key={featPickerKey} character={character} onSelectionChange={setFeat} ignorePrerequisites />
            </div>
          )}

          <Input label="Reason" placeholder="Ring of Protection" maxLength={200} value={reason}
            onChange={e => setReason(e.target.value)} />
          <p className="font-body text-[13px] text-faint">
            {character.ownerDisplayName} sees each adjustment and its reason on their sheet.
          </p>
          <div className="flex justify-end">
            <Button type="submit" disabled={!ready} loading={addAdjustment.isPending}>Add Adjustment</Button>
          </div>
        </form>

        {removed.length > 0 && (
          <section className="border-t border-rule pt-4">
            <button
              type="button"
              onClick={() => setShowHistory(open => !open)}
              aria-expanded={showHistory}
              aria-controls="dm-adjustment-history"
              className="flex items-center gap-1 font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint hover:text-ink"
            >
              {showHistory ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
              History ({removed.length})
            </button>
            {showHistory && (
              <ul id="dm-adjustment-history" className="space-y-1.5 mt-2">
                {removed.map(adjustment => (
                  <li key={adjustment.id} className="px-3 py-2 border border-rule-light">
                    <p className="font-heading text-[14px] font-semibold text-muted line-through">{describeAdjustment(adjustment)}</p>
                    <p className="font-body text-[13px] text-faint">
                      {adjustment.reason} · added {formatDate(adjustment.addedAt)}
                      {adjustment.removedAt && `, removed ${formatDate(adjustment.removedAt)}`}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
      </div>
    </Modal>
  );
}
