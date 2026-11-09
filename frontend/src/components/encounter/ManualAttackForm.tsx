import { useState, type FormEvent } from 'react';
import { Copy, Plus, X } from 'lucide-react';
import type { AttackRollRequest } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import { DAMAGE_TYPES } from '../../utils/conditions';
import IconButton from '../ui/IconButton';
import { useToast } from '../../hooks/useToast';
import { FIELD_LABEL } from './formStyles';

// A new attack row: within 5 feet by default, since there is no map (D161). The DM's numbers entered by hand are
// a Manual Modifier, which the combat log notes (D164)
const NEW_ATTACK = { attackBonus: '', damageDice: '', damageType: '', advantage: null as boolean | null, forceCrit: false, within5Ft: true };
type AttackRow = typeof NEW_ATTACK;

const MAX_ROWS = 5;
const ROW_INPUT = 'w-full px-2 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted';
const TOGGLE = 'px-2 py-2 font-body text-[16px] font-semibold';
const TOGGLE_OFF = 'bg-page-alt text-muted border border-rule';

/**
 * The DM's attacks entered by hand: up to five rows of attack bonus, damage dice and type, each with advantage,
 * a forced critical and whether it is made from within 5 feet. `onRoll` sends each complete row in turn; the rows
 * empty once they have all been sent. A failure shows as a toast and keeps the rows, so they can be sent again.
 */
export default function ManualAttackForm({ target, onRoll }: {
  target: EncounterParticipant;
  onRoll: (request: AttackRollRequest) => Promise<unknown>;
}) {
  const [attacks, setAttacks] = useState<AttackRow[]>([{ ...NEW_ATTACK }]);
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  function updateAttack<K extends keyof AttackRow>(index: number, field: K, value: AttackRow[K]) {
    setAttacks(prev => prev.map((a, i) => i === index ? { ...a, [field]: value } : a));
  }

  function addAttackRow() {
    if (attacks.length >= MAX_ROWS) return;
    setAttacks(prev => [...prev, { ...NEW_ATTACK }]);
  }

  function cloneAttackRow(index: number) {
    if (attacks.length >= MAX_ROWS) return;
    const source = attacks[index];
    setAttacks(prev => [...prev.slice(0, index + 1), { ...source }, ...prev.slice(index + 1)]);
  }

  function removeAttackRow(index: number) {
    if (attacks.length <= 1) return;
    setAttacks(prev => prev.filter((_, i) => i !== index));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (loading) return;
    setLoading(true);
    try {
      for (const atk of attacks) {
        if (atk.attackBonus !== '' && atk.damageDice) {
          await onRoll({
            targetId: target.id, attackBonus: parseInt(atk.attackBonus), damageDice: atk.damageDice,
            damageType: atk.damageType || undefined, advantage: atk.advantage ?? undefined,
            forceCrit: atk.forceCrit || undefined, within5Ft: atk.within5Ft,
          });
        }
      }
      setAttacks([{ ...NEW_ATTACK }]);
    } catch (err: unknown) {
      toast.errorFrom(err);
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col sm:flex-row gap-3">
      <div className="flex-1 space-y-2">
        {attacks.map((atk, i) => (
          <div key={i} className="flex flex-wrap items-end gap-2">
            <div className="w-20">
              {i === 0 && <label className={FIELD_LABEL}>Attack +</label>}
              <input type="number" value={atk.attackBonus} onChange={e => updateAttack(i, 'attackBonus', e.target.value)}
                placeholder="+5" className={ROW_INPUT} autoFocus={i === 0} />
            </div>
            <div className="w-24">
              {i === 0 && <label className={FIELD_LABEL}>Damage</label>}
              <input type="text" value={atk.damageDice} onChange={e => updateAttack(i, 'damageDice', e.target.value)}
                placeholder="1d8+3" className={ROW_INPUT} />
            </div>
            <div className="w-28">
              {i === 0 && <label className={FIELD_LABEL}>Type</label>}
              <select value={atk.damageType} onChange={e => updateAttack(i, 'damageType', e.target.value)}
                className="w-full h-[38px] px-2 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink focus:outline-none focus:border-muted">
                <option value="">&#8212;</option>
                {DAMAGE_TYPES.map(dt => <option key={dt} value={dt}>{dt}</option>)}
              </select>
            </div>
            <div>
              {i === 0 && <label className={FIELD_LABEL}>Roll</label>}
              <div className="flex gap-1">
                <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === false ? null : false)}
                  className={`${TOGGLE} ${atk.advantage === false ? 'bg-debuff text-card' : TOGGLE_OFF}`}>
                  Dis
                </button>
                <button type="button" onClick={() => updateAttack(i, 'advantage', null)}
                  className={`${TOGGLE} ${atk.advantage === null ? 'bg-rule text-ink' : TOGGLE_OFF}`}>
                  Norm
                </button>
                <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === true ? null : true)}
                  className={`${TOGGLE} ${atk.advantage === true ? 'bg-buff text-card' : TOGGLE_OFF}`}>
                  Adv
                </button>
              </div>
            </div>
            <div>
              {i === 0 && <label className={FIELD_LABEL}>&nbsp;</label>}
              {/* Paladin's darker gold, so the light text reads in every theme (WCAG AA) */}
              <button type="button" onClick={() => updateAttack(i, 'forceCrit', !atk.forceCrit)} aria-pressed={atk.forceCrit}
                className={`${TOGGLE} ${atk.forceCrit ? 'bg-cls-paladin text-card' : TOGGLE_OFF}`}>
                Crit
              </button>
              {/* A hit on an unconscious or paralyzed target is a critical hit only from within 5 feet (D161) */}
              <button type="button" onClick={() => updateAttack(i, 'within5Ft', !atk.within5Ft)} aria-pressed={atk.within5Ft}
                className={`${TOGGLE} ${atk.within5Ft ? 'bg-cls-wizard text-card' : TOGGLE_OFF}`}>
                Within 5 ft
              </button>
            </div>
            <div className="flex gap-1">
              {attacks.length < MAX_ROWS && (
                <IconButton label="Clone this attack" onClick={() => cloneAttackRow(i)} className="p-2 text-faint hover:text-buff">
                  <Copy className="w-4 h-4" />
                </IconButton>
              )}
              {attacks.length > 1 && (
                <IconButton label="Remove this attack" onClick={() => removeAttackRow(i)} className="p-2 text-faint hover:text-debuff">
                  <X className="w-4 h-4" />
                </IconButton>
              )}
            </div>
          </div>
        ))}
        {attacks.length < MAX_ROWS && (
          <button type="button" onClick={addAttackRow}
            className="flex items-center gap-1 font-body text-[15px] font-semibold text-muted hover:text-ink py-1">
            <Plus className="w-3 h-3" /> Add attack
          </button>
        )}
      </div>
      <div className="flex items-end">
        <button type="submit" disabled={loading}
          className="px-4 py-2 bg-ink hover:bg-ink/90 disabled:bg-ink/50 text-card font-body text-[16px] font-semibold whitespace-nowrap">
          {loading ? '...' : `Roll ${attacks.length > 1 ? `${attacks.length} ` : ''}Attack${attacks.length > 1 ? 's' : ''}`}
        </button>
      </div>
    </form>
  );
}
