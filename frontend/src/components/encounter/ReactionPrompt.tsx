import { useState } from 'react';
import { ShieldAlert } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useEncounterAction } from '../../queries/encounters';
import type { ReactionOffer, ReactionOption } from '../../types/combat';
import { describeTrigger } from '../../utils/reactionOffers';
import { FIELD_INPUT } from './formStyles';

function OfferCard({ offer }: { offer: ReactionOffer }) {
  const actions = useEncounterAction();
  const [levels, setLevels] = useState<Record<string, number>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const headingId = `reaction-offer-${offer.id}`;

  async function run(work: () => Promise<unknown>) {
    if (busy) return;
    setError('');
    setBusy(true);
    try {
      await work();
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  // Each option goes through the command that already takes that reaction (D174), naming the offer for Shield (D216)
  function take(option: ReactionOption) {
    switch (option.kind) {
      case 'SPELL': {
        const slotLevel = levels[option.name] ?? option.slotLevels?.[0] ?? 0;
        return run(() => actions.castSpell({
          spellName: option.spellName ?? option.name,
          spellSource: option.spellSource ?? undefined,
          slotLevel,
          targetIds: option.targetIds ?? [],
          option: option.castOption ?? undefined,
          reactionOfferId: offer.id,
        }, offer.reactorId));
      }
      case 'POOL':
        return run(() => actions.usePool({
          participantId: offer.reactorId,
          poolId: option.poolId ?? '',
          amounts: option.amount != null ? [option.amount] : undefined,
        }));
      case 'MONSTER_REACTION':
        return run(() => actions.monsterAction({
          monsterParticipantId: offer.reactorId,
          actionName: option.actionName ?? option.name,
          actionSource: 'REACTION',
          targetParticipantIds: [],
        }));
    }
  }

  return (
    <li aria-labelledby={headingId} className="p-3 bg-card border border-cls-fighter/40 space-y-2">
      <div>
        <h3 id={headingId} className="font-heading text-[15px] font-semibold text-ink">
          {offer.reactorName} can react
        </h3>
        <p className="font-body text-[14px] font-semibold text-muted">{describeTrigger(offer)}</p>
      </div>
      <ul className="space-y-2">
        {offer.options.map(option => {
          const levelId = `${headingId}-${option.name.replace(/\W+/g, '-')}-level`;
          const choices = option.slotLevels ?? [];
          return (
            <li key={option.name} className="flex flex-wrap items-end gap-2">
              {choices.length > 1 && (
                <div>
                  <label htmlFor={levelId} className="sr-only">{option.name} slot level</label>
                  <select id={levelId} value={levels[option.name] ?? choices[0]} disabled={busy}
                    onChange={e => setLevels(prev => ({ ...prev, [option.name]: Number(e.target.value) }))}
                    className={`${FIELD_INPUT} w-auto`}>
                    {choices.map(level => <option key={level} value={level}>Level {level}</option>)}
                  </select>
                </div>
              )}
              <button type="button" onClick={() => { void take(option); }} disabled={busy}
                aria-busy={busy || undefined}
                className="min-h-11 px-3 py-2 bg-cls-fighter/10 hover:bg-cls-fighter/20 text-ink font-heading text-[13px] font-semibold tracking-[0.02em] border border-cls-fighter/30 disabled:opacity-50">
                {option.name}
              </button>
              {option.note && (
                <p className="basis-full font-body text-[13px] font-semibold text-muted">
                  {option.note}{!option.resolves && ' (The DM adjusts by hand.)'}
                </p>
              )}
            </li>
          );
        })}
      </ul>
      <button type="button" disabled={busy} aria-label={`Skip ${offer.reactorName}'s reaction`}
        onClick={() => { void run(() => actions.dismissReaction({ offerId: offer.id })); }}
        className="min-h-11 px-3 py-2 bg-page-alt hover:bg-ink/10 text-muted font-heading text-[13px] font-semibold tracking-[0.02em] border border-rule disabled:opacity-50">
        Skip
      </button>
      {error && <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{error}</p>}
    </li>
  );
}

/**
 * The reactions on offer after the newest combat command (D216): Shield when hit, Counterspell when an enemy casts,
 * and the like, each with its options and a Skip. The server never waits for an answer; an offer goes with the next
 * command that isn't a reaction. The DM page shows every offer, the player's page those of the creatures they control.
 */
export default function ReactionPrompt({ offers }: { offers: ReactionOffer[] }) {
  if (offers.length === 0) return null;
  return (
    <section aria-label="Reaction prompts" className="mb-4 p-3 bg-cls-fighter/10 border border-cls-fighter/30">
      <h2 className="flex items-center gap-1.5 mb-2 font-heading text-[15px] font-semibold text-ink">
        <ShieldAlert className="w-4 h-4" aria-hidden="true" /> Reactions on offer
      </h2>
      <ul className="space-y-2">
        {offers.map(offer => <OfferCard key={offer.id} offer={offer} />)}
      </ul>
    </section>
  );
}
