import { useId, useState, type FormEvent } from 'react';
import type { ActiveSpellRequest, EncounterParticipant } from '../../types/encounter';
import { FIELD_INPUT, FIELD_LABEL, SUBMIT_BUTTON } from './formStyles';
import { useToast } from '../../hooks/useToast';

/**
 * The DM's record that a caster's lasting spell is already going (D184), such as Mage Armor cast before the fight or
 * Bless cast just before initiative: the spell's name, the slot level it was cast with (its own when blank), an option
 * for a spell that has them, such as "reduce" or "fire", and the creatures it affects, none for a spell on its caster.
 * Nothing is spent; its concentration and Ongoing Modifiers start. `onSubmit` sends it, and the form empties once it
 * succeeds; a failure shows as a toast.
 */
export default function ActiveSpellForm({ caster, participants, onSubmit }: {
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  onSubmit: (request: ActiveSpellRequest) => Promise<unknown>;
}) {
  const id = useId();
  const [spellName, setSpellName] = useState('');
  const [slotLevel, setSlotLevel] = useState('');
  const [option, setOption] = useState('');
  const [targetIds, setTargetIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  function toggle(participantId: string) {
    setTargetIds(prev => prev.includes(participantId) ? prev.filter(t => t !== participantId) : [...prev, participantId]);
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (loading || !spellName.trim()) return;
    setLoading(true);
    try {
      await onSubmit({
        casterId: caster.id,
        spellName: spellName.trim(),
        slotLevel: slotLevel ? parseInt(slotLevel) : undefined,
        option: option.trim() || undefined,
        targetIds,
      });
      setSpellName('');
      setSlotLevel('');
      setOption('');
      setTargetIds([]);
    } catch (err: unknown) {
      toast.errorFrom(err);
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-2" aria-label={`Spell already active for ${caster.displayName}`}>
      <p className="font-body text-[13px] font-semibold text-faint">
        A spell {caster.displayName} cast before this moment: nothing is spent, and its concentration and effects start.
      </p>
      <div className="flex flex-wrap items-end gap-2">
        <div className="grow basis-40">
          <label htmlFor={`${id}-spell`} className={FIELD_LABEL}>Spell already active</label>
          <input id={`${id}-spell`} type="text" value={spellName} onChange={e => setSpellName(e.target.value)}
            placeholder="e.g. Mage Armor, Bless" className={FIELD_INPUT} />
        </div>
        <div className="w-20">
          <label htmlFor={`${id}-slot`} className={FIELD_LABEL}>Slot</label>
          <input id={`${id}-slot`} type="number" min={0} max={9} value={slotLevel}
            onChange={e => setSlotLevel(e.target.value)} placeholder="own" className={FIELD_INPUT} />
        </div>
        <div className="w-28">
          <label htmlFor={`${id}-option`} className={FIELD_LABEL}>Option</label>
          <input id={`${id}-option`} type="text" value={option} onChange={e => setOption(e.target.value)}
            placeholder="if any" className={FIELD_INPUT} />
        </div>
      </div>
      <fieldset className="flex flex-wrap gap-x-3 gap-y-1">
        <legend className={FIELD_LABEL}>On (none for a spell on the caster)</legend>
        {participants.map(p => (
          <label key={p.id} className="inline-flex items-center gap-1 font-body text-[14px] font-semibold text-ink">
            <input type="checkbox" checked={targetIds.includes(p.id)} onChange={() => toggle(p.id)} />
            {p.displayName}
          </label>
        ))}
      </fieldset>
      <button type="submit" disabled={loading || !spellName.trim()} className={SUBMIT_BUTTON}>
        {loading ? '...' : 'Start'}
      </button>
    </form>
  );
}
