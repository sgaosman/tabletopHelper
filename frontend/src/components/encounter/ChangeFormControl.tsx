import { useState } from 'react';
import { Shuffle } from 'lucide-react';
import { useFormOptions } from '../../queries/encounters';
import { apiErrorMessage } from '../../api/errors';
import type { EncounterParticipant } from '../../types/encounter';
import type { WildShapeBeast } from '../../types/combat';
import { reshapingSpell, SHAPECHANGE } from '../../utils/formSpells';
import Button from '../ui/Button';
import { Select } from '../ui/Input';
import FormPicker from './FormPicker';

interface Props {
  /** The creature concentrating on Shapechange or Animal Shapes. */
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  /** Sends the change-form command; a refusal shows here. */
  onChange: (targetId: string, creatureId: string) => Promise<unknown>;
}

/**
 * A new form with the caster's action while it keeps up Shapechange (its own form, PHB p.274) or Animal Shapes (a
 * creature it transformed, PHB p.212), from the options the server lists for that creature (D192). Shown to whoever
 * controls the caster: the DM, or the caster's player. Nothing shows when the spell has no creature to change.
 */
export default function ChangeFormControl({ caster, participants, onChange }: Props) {
  const spell = reshapingSpell(caster);
  const targets = spell === SHAPECHANGE
    ? [caster]
    : participants.filter(p => p.form?.spell === spell && p.lifeState !== 'DEAD');
  const [open, setOpen] = useState(false);
  const [targetId, setTargetId] = useState('');
  const [creature, setCreature] = useState<WildShapeBeast | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const target = targets.find(t => t.id === targetId) ?? targets[0];
  const options = useFormOptions(caster.id, open && spell ? spell : undefined,
    spell === SHAPECHANGE ? undefined : target?.id);
  if (!spell || !target) return null;

  const send = async () => {
    if (!creature) return;
    setSending(true);
    setError('');
    try {
      await onChange(target.id, creature.monsterId);
      setOpen(false);
      setCreature(null);
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="mt-2" role="group" aria-label={`${caster.displayName}: new form (${spell})`}>
      <button type="button" onClick={() => setOpen(shown => !shown)} aria-expanded={open}
        className="inline-flex items-center gap-1 px-2 py-1 font-heading text-[12px] font-semibold tracking-[0.02em] bg-buff/10 hover:bg-buff/20 text-buff border border-buff/30">
        <Shuffle className="w-3.5 h-3.5" /> New form (action)
      </button>
      {open && (
        <div className="mt-2 space-y-2 border border-rule bg-card p-2 sm:p-3 max-w-full">
          {targets.length > 1 && (
            <Select label="Creature to change" value={target.id}
              onChange={e => { setTargetId(e.target.value); setCreature(null); }}>
              {targets.map(t => (
                <option key={t.id} value={t.id}>{t.displayName}{t.formName ? ` (as ${t.formName})` : ''}</option>
              ))}
            </Select>
          )}
          <FormPicker label={`New form for ${target.displayName}`} options={options.data} loading={options.isPending}
            error={options.error} selected={creature?.monsterId ?? null} onSelect={setCreature} />
          <Button size="sm" onClick={() => { void send(); }} disabled={!creature} loading={sending}>
            {creature ? `Change ${target.displayName} into ${creature.name}` : 'Choose a creature'}
          </Button>
          {error && <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{error}</p>}
        </div>
      )}
    </div>
  );
}
