import { Sparkles } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useLiveEncounter } from '../../context/liveEncounter';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';
import { useEncounterAction, useSummonOptions } from '../../queries/encounters';
import type { EncounterParticipant, SpellEffect } from '../../types/encounter';
import { draftProblem, toSummonChoice } from '../../utils/summons';
import Button from '../ui/Button';
import Modal from '../ui/Modal';
import SummonStep from './spellflow/SummonStep';
import useSummonDraft from './spellflow/useSummonDraft';

/**
 * The DM's creatures for a summoning (D182): for one waiting for the DM, such as Summon Lesser Demons with its rolled
 * d6 (XGE p.167), or in place of a placed one's, which disappear. It starts on the option the casting chose or rolled,
 * and the DM may pick any creature listed. A failure shows as a toast and keeps the choice.
 */
export default function SummonPlaceModal({ caster, effect, onClose }: {
  caster: EncounterParticipant;
  effect: SpellEffect;
  onClose: () => void;
}) {
  const { encounterId } = useLiveEncounter();
  const actions = useEncounterAction();
  const toast = useToast();
  const placing = usePending();
  const options = useSummonOptions(encounterId, caster.id, effect.spell, effect.slotLevel ?? null);
  const [draft, setDraft] = useSummonDraft(options.data, effect.summon?.optionId);
  const awaiting = effect.summon?.status === 'AWAITING_DM';
  const problem = options.data && draft ? draftProblem(options.data, draft, true) : 'Loading';

  function place() {
    if (!options.data || !draft || problem) return;
    const choice = toSummonChoice(options.data, draft, true);
    placing.run(async () => {
      await actions.placeSummon({ effectId: effect.id, ...choice });
      onClose();
    }).catch(toast.errorFrom);
  }

  return (
    <Modal
      title={<><Sparkles className="w-5 h-5 text-ink" />{awaiting ? `Choose ${effect.spell}'s creatures` : `Replace ${effect.spell}'s creatures`}</>}
      subtitle={caster.displayName}
      size="lg"
      onClose={onClose}
      dismissible={!placing.pending}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose} disabled={placing.pending}>Cancel</Button>
          <Button onClick={place} disabled={!!problem} loading={placing.pending}>
            {awaiting ? 'Place creatures' : 'Replace creatures'}
          </Button>
        </>
      )}
    >
      {options.isError ? (
        <p role="alert" className="font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(options.error)}</p>
      ) : options.data && draft ? (
        <>
          {!awaiting && (
            <p className="mb-3 font-body text-[13px] font-semibold text-muted">
              The creatures {effect.spell} summoned now disappear for these.
            </p>
          )}
          <SummonStep options={options.data} draft={draft} onChange={setDraft} byDm rolled={effect.summon?.roll} />
          {problem && <p className="mt-2 font-body text-[13px] font-semibold text-faint">{problem}</p>}
        </>
      ) : (
        <p className="font-body text-[15px] font-semibold text-faint text-center py-4">Loading creatures...</p>
      )}
    </Modal>
  );
}
