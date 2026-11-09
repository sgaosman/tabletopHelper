import { useState } from 'react';
import { UserPlus } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';
import { useSummonOptions } from '../../queries/encounters';
import type { CompanionSummonRequest, EncounterParticipant } from '../../types/encounter';
import { COMPANION_SPELLS, SUMMONING_SPELLS, draftProblem, toSummonChoice } from '../../utils/summons';
import Button from '../ui/Button';
import { Select } from '../ui/Input';
import Modal from '../ui/Modal';
import SummonStep from './spellflow/SummonStep';
import useSummonDraft from './spellflow/useSummonDraft';

// The summoning spells cast in a turn, after the long-cast ones a companion usually comes from
const OTHER_SPELLS = SUMMONING_SPELLS.filter(name => !(COMPANION_SPELLS as readonly string[]).includes(name));

/**
 * The DM's Add companion (D182): a player character's summoned creature placed with no slot spent, while the
 * encounter is being prepared or fought, such as a familiar (PHB p.240) or steed (PHB p.240) found before the fight,
 * Animate Dead's undead (PHB p.212) or a tiny servant (XGE p.168), which take a minute or more to cast. The DM picks
 * the owner, the spell and its slot level, the creatures from the server's summon options, their type, and whether
 * they end with the owner's concentration (on by default for a spell that needs it). `onSubmit` sends the request;
 * a failure shows as a toast and keeps the form.
 */
export default function AddCompanionModal({ encounterId, participants, onSubmit, onClose }: {
  encounterId: string;
  participants: EncounterParticipant[];
  onSubmit: (request: CompanionSummonRequest) => Promise<unknown>;
  onClose: () => void;
}) {
  const toast = useToast();
  const adding = usePending();
  const owners = participants.filter(p => p.participantType === 'PLAYER');
  const [casterId, setCasterId] = useState(owners[0]?.id ?? '');
  const [spellName, setSpellName] = useState<string>(COMPANION_SPELLS[0]);
  const [slotLevel, setSlotLevel] = useState<number | null>(null);
  const [linked, setLinked] = useState<boolean | null>(null);
  const options = useSummonOptions(encounterId, casterId || undefined, spellName, slotLevel);
  const [draft, setDraft] = useSummonDraft(options.data);
  const problem = !casterId ? 'Choose the owner'
    : options.data && draft ? draftProblem(options.data, draft, true) : 'Loading';
  const concentrationLinked = linked ?? options.data?.concentration ?? false;

  function chooseSpell(name: string) {
    setSpellName(name);
    setSlotLevel(null);
    setLinked(null);
  }

  function add() {
    if (!options.data || !draft || problem) return;
    const request: CompanionSummonRequest = {
      casterParticipantId: casterId,
      spellName,
      slotLevel: options.data.slotLevel,
      concentrationLinked,
      ...toSummonChoice(options.data, draft, true),
    };
    adding.run(async () => {
      await onSubmit(request);
      onClose();
    }).catch(toast.errorFrom);
  }

  return (
    <Modal
      title={<><UserPlus className="w-5 h-5 text-ink" />Add companion</>}
      size="lg"
      onClose={onClose}
      dismissible={!adding.pending}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose} disabled={adding.pending}>Cancel</Button>
          <Button onClick={add} disabled={!!problem} loading={adding.pending}>Add companion</Button>
        </>
      )}
    >
      {owners.length === 0 ? (
        <p className="font-body text-[15px] font-semibold text-faint">Add a player character first: a companion belongs to one.</p>
      ) : (
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-3">
            <Select label="Owner" value={casterId} onChange={e => setCasterId(e.target.value)}>
              {owners.map(p => <option key={p.id} value={p.id}>{p.displayName}</option>)}
            </Select>
            <Select label="Spell" value={spellName} onChange={e => chooseSpell(e.target.value)}>
              <optgroup label="Cast before the fight">
                {COMPANION_SPELLS.map(name => <option key={name} value={name}>{name}</option>)}
              </optgroup>
              <optgroup label="Cast in a turn">
                {OTHER_SPELLS.map(name => <option key={name} value={name}>{name}</option>)}
              </optgroup>
            </Select>
            <Select label="Slot level" value={options.data?.slotLevel ?? ''}
              onChange={e => setSlotLevel(parseInt(e.target.value))} disabled={!options.data}>
              {options.data && Array.from({ length: 10 - options.data.spellLevel }, (_, i) => options.data.spellLevel + i)
                .map(level => <option key={level} value={level}>{level}</option>)}
            </Select>
          </div>

          {options.isError ? (
            <p role="alert" className="font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(options.error)}</p>
          ) : options.data && draft ? (
            <>
              <SummonStep options={options.data} draft={draft} onChange={setDraft} byDm />
              <label className="flex items-start gap-2 min-h-11 cursor-pointer">
                <input type="checkbox" checked={concentrationLinked} onChange={e => setLinked(e.target.checked)}
                  className="mt-1 accent-ink" />
                <span className="font-body text-[15px] font-semibold text-ink">
                  Ends with the owner&apos;s concentration
                  <span className="block font-body text-[13px] font-semibold text-faint">
                    Starts the owner&apos;s concentration on {spellName}, ending any other.
                  </span>
                </span>
              </label>
              {problem && <p className="font-body text-[13px] font-semibold text-faint">{problem}</p>}
            </>
          ) : (
            <p className="font-body text-[15px] font-semibold text-faint text-center py-4">Loading creatures...</p>
          )}
        </div>
      )}
    </Modal>
  );
}
