import { useState } from 'react';
import { Sparkles, Zap } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import { followableHit, kiLeft, smiteDice, smiteSlots } from '../../utils/classFeatures';
import { FIELD_LABEL } from './formStyles';

const BUTTON = 'flex items-center gap-1.5 px-3 py-1.5 font-heading text-[12px] font-semibold tracking-[0.02em] border disabled:opacity-50';

/**
 * What a melee weapon hit lets its attacker do next, once it knows it hit (M17): Divine Smite, spending a spell slot of
 * the player's choice for radiant damage, doubled on a critical hit (PHB p.85); and Stunning Strike, spending 1 ki for
 * a Constitution save against the stun (PHB p.79). It shows while the participant's latest hit is on the turn the
 * encounter is on and something can still follow it, for the player on their own character and for the DM on any
 * row; the server decides, and its refusal shows.
 */
export default function HitFollowUps({ participant, participants, turnStamp }: {
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  turnStamp: string | null | undefined;
}) {
  const actions = useEncounterAction();
  const [slotKey, setSlotKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const hit = followableHit(participant, turnStamp);
  if (!hit) return null;
  const canSmite = hit.divineSmiteOffered && !hit.smitten;
  const canStun = hit.stunningStrikeOffered && !hit.stunningStrikeUsed;
  if (!canSmite && !canStun) return null;

  const target = participants.find(p => p.id === hit.targetId);
  const slots = smiteSlots(participant);
  const slot = slots.find(s => s.key === slotKey) ?? slots[0] ?? null;
  const ki = kiLeft(participant) ?? 0;
  const attackLogId = hit.attackLogId ?? undefined;

  async function run(request: () => Promise<unknown>) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await request();
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label="After the hit" className="mt-2 p-3 bg-page-alt border border-rule space-y-2">
      <p className="font-body text-[13px] font-semibold text-ink">
        {hit.weapon} hit {target?.displayName ?? 'the target'}{hit.critical ? ' (critical hit)' : ''}
      </p>
      <div className="flex flex-wrap items-end gap-2">
        {canSmite && (slot ? (
          <>
            <div className="w-48">
              <label htmlFor={`smite-slot-${participant.id}`} className={FIELD_LABEL}>Smite slot</label>
              <select id={`smite-slot-${participant.id}`} value={slot.key} onChange={e => setSlotKey(e.target.value)}
                className="w-full px-2 py-1.5 bg-card border border-rule font-body text-[15px] font-semibold text-ink">
                {slots.map(s => (
                  <option key={s.key} value={s.key}>
                    Level {s.level}{s.pact ? ' pact' : ''} slot ({s.remaining} left)
                  </option>
                ))}
              </select>
            </div>
            {/* Paladin's darker gold, so the text reads in every theme (WCAG AA) */}
            <button type="button" disabled={busy}
              onClick={() => run(() => actions.divineSmite({
                participantId: participant.id, slotLevel: slot.level, pactSlot: slot.pact ? true : undefined, attackLogId,
              }))}
              className={`${BUTTON} bg-cls-paladin text-card border-cls-paladin`}>
              <Sparkles className="w-3.5 h-3.5" /> Divine Smite ({smiteDice(slot.level, hit.critical)} radiant)
            </button>
          </>
        ) : (
          <p className="font-body text-[13px] font-semibold text-faint">No spell slots left for Divine Smite</p>
        ))}
        {canStun && (
          <button type="button" disabled={busy || ki < 1} title={ki < 1 ? 'No ki points left' : undefined}
            onClick={() => run(() => actions.stunningStrike({ participantId: participant.id, attackLogId }))}
            className={`${BUTTON} bg-ink/10 hover:bg-ink/20 text-ink border-ink/30`}>
            <Zap className="w-3.5 h-3.5" /> Stunning Strike (1 ki, {ki} left)
          </button>
        )}
      </div>
      {error && <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{error}</p>}
    </section>
  );
}
