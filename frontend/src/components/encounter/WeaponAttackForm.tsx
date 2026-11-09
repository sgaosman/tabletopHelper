import { useState, type FormEvent } from 'react';
import { Copy, Plus, X } from 'lucide-react';
import IconButton from '../ui/IconButton';
import { apiErrorMessage } from '../../api/errors';
import { useAttackOptions, useEncounterAction } from '../../queries/encounters';
import type { AttackOption, AttackRollRequest } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import { chargeBlock } from '../../utils/turnEconomy';
import { isReckless, RECKLESS_TEXT } from '../../utils/classFeatures';

const DAMAGE_TYPES = [
  'bludgeoning', 'piercing', 'slashing', 'acid', 'cold', 'fire',
  'force', 'lightning', 'necrotic', 'poison', 'psychic', 'radiant', 'thunder',
];

// The weapon choice for an attack whose numbers are entered by hand, a Manual Modifier (D164)
const ENTERED = '';

const LABEL = 'block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1';
const FIELD = 'w-full px-2 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted';
const TOGGLE_OFF = 'bg-page-alt text-muted border border-rule';

/** What an attack costs from the attacker's turn (D174). */
type AttackCost = NonNullable<AttackRollRequest['cost']>;

// An attack of the Attack action; an off-hand attack, the bonus action two-weapon fighting gives after the Attack
// action (PHB p.195); or an opportunity attack, a reaction that may be made on another creature's turn
const COSTS: { value: AttackCost; label: string }[] = [
  { value: 'ACTION', label: 'Attack' },
  { value: 'BONUS_ACTION', label: 'Off-hand (bonus action)' },
  { value: 'REACTION', label: 'Opportunity attack (reaction)' },
];

/**
 * The cost an attack starts on (D174): on the attacker's turn the Attack action while it can pay, then an off-hand
 * attack, and off its turn an opportunity attack. When nothing can pay, Attack on its turn, so the row says why.
 */
function automaticCost(attacker: EncounterParticipant): AttackCost {
  const candidates: AttackCost[] = attacker.isCurrentTurn ? ['ACTION', 'BONUS_ACTION'] : ['REACTION'];
  return candidates.find(cost => chargeBlock(attacker, { cost, attack: true }) === null) ?? candidates[0];
}

/** One attack the player rolls: a weapon from their attack options, or numbers entered by hand. */
interface AttackRow {
  /** The cost the player picked, or null to follow the turn's budget as it changes (automaticCost). */
  cost: AttackCost | null;
  weapon: string;
  powerAttack: boolean;
  /** Reckless Attack, chosen on the first attack of the barbarian's turn (PHB p.48). */
  reckless: boolean;
  advantage: boolean | null;
  within5Ft: boolean;
  forceCrit: boolean;
  extraAttackBonus: string;
  extraDamageDice: string;
  extraDamageType: string;
  attackBonus: string;
  damageDice: string;
  damageType: string;
}

// A ranged weapon attacks from beyond 5 feet unless the player says otherwise; there is no map (D161)
function newRow(option: AttackOption | undefined, cost: AttackCost | null): AttackRow {
  return {
    cost, weapon: option?.weapon ?? ENTERED, powerAttack: false, reckless: false, advantage: null, within5Ft: !(option?.ranged ?? false),
    forceCrit: false, extraAttackBonus: '', extraDamageDice: '', extraDamageType: '',
    attackBonus: '', damageDice: '', damageType: '',
  };
}

function signed(value: number) {
  return value < 0 ? `${value}` : `+${value}`;
}

/** The numbers an option shows, with Great Weapon Master's or Sharpshooter's trade when the player takes it. */
function summary(option: AttackOption, powerAttack: boolean) {
  const traded = powerAttack && option.powerAttack;
  const bonus = traded ? option.powerAttack!.attackBonus : option.attackBonus;
  const damage = traded ? option.powerAttack!.damage : option.damage;
  return `${signed(bonus)} to hit · ${damage}${option.damageType ? ` ${option.damageType}` : ''}`;
}

/**
 * The request for a row, or null for an attack entered by hand without both numbers. Manual Modifiers are sent only
 * while the campaign allows them; the server refuses them otherwise (D164).
 */
function attackRequest(row: AttackRow & { cost: AttackCost }, option: AttackOption | undefined, targetId: string,
  allowManualModifiers: boolean): AttackRollRequest | null {
  const common = {
    targetId,
    // Left out, an attack is one of the Attack action's (D174)
    cost: row.cost === 'ACTION' ? undefined : row.cost,
    advantage: row.advantage ?? undefined,
    within5Ft: row.within5Ft,
    forceCrit: allowManualModifiers && row.forceCrit ? true : undefined,
  };
  if (row.weapon === ENTERED) {
    if (!allowManualModifiers || row.attackBonus === '' || !row.damageDice.trim()) return null;
    return {
      ...common, attackBonus: parseInt(row.attackBonus), damageDice: row.damageDice.trim(),
      damageType: row.damageType || undefined,
    };
  }
  const extraBonus = allowManualModifiers && row.extraAttackBonus !== '' ? parseInt(row.extraAttackBonus) : 0;
  const extraDamage = allowManualModifiers ? row.extraDamageDice.trim() : '';
  return {
    ...common,
    weapon: row.weapon,
    powerAttack: option?.powerAttack && row.powerAttack ? true : undefined,
    recklessAttack: option?.recklessAttack && row.reckless ? true : undefined,
    extraAttackBonus: extraBonus ? extraBonus : undefined,
    extraDamageDice: extraDamage || undefined,
    extraDamageType: extraDamage && row.extraDamageType ? row.extraDamageType : undefined,
  };
}

/**
 * A player's attack (D164): a weapon from their inventory, or an unarmed strike, with the attack bonus and damage
 * the server works out, and Great Weapon Master's or Sharpshooter's trade where the character may take it. While the
 * campaign allows Manual Modifiers, the player may add a magic weapon's bonus or other extra damage, force a critical
 * hit, or enter a whole attack by hand for a weapon the server doesn't know. Up to five attacks roll in one go.
 * Each attack has a cost (D174): an attack of the Attack action, an off-hand attack or an opportunity attack. It is
 * `defaultCost` when one is given; otherwise it follows the attacker's turn (automaticCost), moving on to an off-hand
 * attack once the Attack action is spent, until the player picks a cost, which then stays. Where the attacker's turn
 * can't pay for it the row says why, and a refusal from the server shows its reason.
 */
export default function WeaponAttackForm({ attacker, target, allowManualModifiers, onDone, defaultCost }: {
  attacker: EncounterParticipant;
  target: EncounterParticipant;
  allowManualModifiers: boolean;
  onDone: () => void;
  defaultCost?: AttackCost;
}) {
  const actions = useEncounterAction();
  const optionsQuery = useAttackOptions(attacker.id);
  const [rows, setRows] = useState<AttackRow[] | null>(null);
  const [rolling, setRolling] = useState(false);
  const [error, setError] = useState('');

  if (optionsQuery.isPending) {
    return <p className="font-body text-[15px] font-semibold text-muted">Loading weapons...</p>;
  }
  if (optionsQuery.isError) {
    return <p role="alert" className="font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(optionsQuery.error)}</p>;
  }
  const options = optionsQuery.data;
  const optionFor = (weapon: string) => options.find(o => o.weapon === weapon);
  const current = rows ?? [newRow(options[0], defaultCost ?? null)];
  const followed = automaticCost(attacker);
  const costOf = (row: AttackRow): AttackCost => row.cost ?? followed;

  function update(index: number, change: Partial<AttackRow>) {
    setRows(current.map((row, i) => i === index ? { ...row, ...change } : row));
  }

  // A new weapon brings its own reach and drops a trade it doesn't allow
  function chooseWeapon(index: number, weapon: string) {
    const option = optionFor(weapon);
    update(index, { weapon, powerAttack: false, reckless: false, within5Ft: !(option?.ranged ?? false) });
  }

  // Reckless Attack is chosen on the first attack of the barbarian's own turn, and lasts the turn (PHB p.48)
  const alreadyReckless = attacker.isCurrentTurn && isReckless(attacker);
  const recklessBlock = !attacker.isCurrentTurn ? 'Reckless Attack is chosen on your own turn'
    : attacker.turnEconomy?.attackActionTaken && !alreadyReckless
      ? 'Reckless Attack is chosen on the first attack of the turn' : null;

  function addRow() {
    if (current.length < 5) setRows([...current, newRow(options[0], defaultCost ?? null)]);
  }

  function cloneRow(index: number) {
    if (current.length < 5) setRows([...current.slice(0, index + 1), { ...current[index] }, ...current.slice(index + 1)]);
  }

  function removeRow(index: number) {
    if (current.length > 1) setRows(current.filter((_, i) => i !== index));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (rolling) return;
    const requests = current
      .map(row => attackRequest({ ...row, cost: costOf(row) }, optionFor(row.weapon), target.id, allowManualModifiers))
      .filter((request): request is AttackRollRequest => request !== null);
    if (requests.length === 0) return;
    setRolling(true);
    setError('');
    try {
      for (const request of requests) {
        await actions.rollAttack(request, attacker.id);
      }
      onDone();
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setRolling(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      {current.map((row, i) => {
        const option = optionFor(row.weapon);
        const entered = row.weapon === ENTERED;
        // Why the attacker's turn can't pay for this attack, as far as the page can tell; the server decides (D174)
        const cost = costOf(row);
        const blocked = chargeBlock(attacker, { cost, attack: true });
        return (
          <div key={i} role="group" aria-label={`Attack ${i + 1}`} className="flex flex-wrap items-end gap-2 pb-3 border-b border-rule-light last:border-0">
            <div className="w-56">
              <label htmlFor={`cost-${i}`} className={LABEL}>Cost</label>
              <select id={`cost-${i}`} value={cost} onChange={e => update(i, { cost: e.target.value as AttackCost })}
                className={`${FIELD} h-[42px]`}>
                {COSTS.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
            </div>

            <div className="w-56">
              <label htmlFor={`weapon-${i}`} className={LABEL}>Weapon</label>
              <select id={`weapon-${i}`} value={row.weapon} onChange={e => chooseWeapon(i, e.target.value)}
                className={`${FIELD} h-[42px]`}>
                {options.map(o => <option key={o.weapon} value={o.weapon}>{o.weapon}</option>)}
                {allowManualModifiers && <option value={ENTERED}>Other: enter the numbers</option>}
              </select>
            </div>

            {option && (
              <p className="font-heading text-[13px] font-semibold text-ink pb-2.5">
                {summary(option, row.powerAttack)}{!option.proficient && <span className="text-faint"> (not proficient)</span>}
              </p>
            )}

            {entered && (
              <>
                <div className="w-20">
                  <label className={LABEL}>Attack +</label>
                  <input type="number" value={row.attackBonus} onChange={e => update(i, { attackBonus: e.target.value })}
                    placeholder="+5" className={FIELD} />
                </div>
                <div className="w-24">
                  <label className={LABEL}>Damage</label>
                  <input type="text" value={row.damageDice} onChange={e => update(i, { damageDice: e.target.value })}
                    placeholder="1d8+3" className={FIELD} />
                </div>
                <div className="w-28">
                  <label className={LABEL}>Type</label>
                  <select value={row.damageType} onChange={e => update(i, { damageType: e.target.value })} className={`${FIELD} h-[42px]`}>
                    <option value="">---</option>
                    {DAMAGE_TYPES.map(dt => <option key={dt} value={dt}>{dt}</option>)}
                  </select>
                </div>
              </>
            )}

            <div>
              <span className={LABEL}>Roll</span>
              <div className="flex gap-1">
                <button type="button" onClick={() => update(i, { advantage: row.advantage === false ? null : false })}
                  className={`px-2 py-2 font-body text-[12.5px] font-semibold ${row.advantage === false ? 'bg-debuff text-card' : TOGGLE_OFF}`}>Dis</button>
                <button type="button" onClick={() => update(i, { advantage: null })}
                  className={`px-2 py-2 font-body text-[12.5px] font-semibold ${row.advantage === null ? 'bg-rule text-ink' : TOGGLE_OFF}`}>Norm</button>
                {/* Light text on the green, so it reads in every theme (WCAG AA) */}
                <button type="button" onClick={() => update(i, { advantage: row.advantage === true ? null : true })}
                  className={`px-2 py-2 font-body text-[12.5px] font-semibold ${row.advantage === true ? 'bg-buff text-card' : TOGGLE_OFF}`}>Adv</button>
              </div>
            </div>

            <div className="flex gap-1">
              {/* Great Weapon Master's or Sharpshooter's trade, only where the server allows it (D164) */}
              {option?.powerAttack && (
                <IconButton label={`${option.powerAttack.feat}: -5 to hit, +10 damage`}
                  onClick={() => update(i, { powerAttack: !row.powerAttack })} aria-pressed={row.powerAttack}
                  className={`px-2 py-2 font-body text-[12.5px] font-semibold ${row.powerAttack ? 'bg-cls-fighter text-card' : TOGGLE_OFF}`}>
                  -5 / +10
                </IconButton>
              )}
              {/* Reckless Attack, where the server offers it: a barbarian's melee attack using Strength (PHB p.48) */}
              {option?.recklessAttack && (alreadyReckless ? (
                <span className="self-center px-2 font-body text-[12.5px] font-semibold text-buff" title={RECKLESS_TEXT}>
                  Reckless: advantage
                </span>
              ) : (
                <button type="button" onClick={() => update(i, { reckless: !row.reckless })} aria-pressed={row.reckless}
                  disabled={recklessBlock !== null} title={recklessBlock ?? RECKLESS_TEXT}
                  className={`px-2 py-2 font-body text-[12.5px] font-semibold disabled:opacity-40 ${row.reckless ? 'bg-buff text-card' : TOGGLE_OFF}`}>
                  Reckless
                </button>
              ))}
              {/* A hit on an unconscious or paralyzed target is a critical hit only from within 5 feet (D161) */}
              <button type="button" onClick={() => update(i, { within5Ft: !row.within5Ft })} aria-pressed={row.within5Ft}
                className={`px-2 py-2 font-body text-[12.5px] font-semibold ${row.within5Ft ? 'bg-cls-wizard text-card' : TOGGLE_OFF}`}>Within 5 ft</button>
              {/* A forced critical is a Manual Modifier, which the combat log notes (D164). Paladin's darker gold, so
                  the light text reads in every theme (WCAG AA) */}
              {allowManualModifiers && (
                <button type="button" onClick={() => update(i, { forceCrit: !row.forceCrit })} aria-pressed={row.forceCrit}
                  className={`px-2 py-2 font-body text-[12.5px] font-semibold ${row.forceCrit ? 'bg-cls-paladin text-card' : TOGGLE_OFF}`}>Crit</button>
              )}
            </div>

            {/* A magic weapon's bonus or other extra damage is a Manual Modifier, which the combat log notes (D164) */}
            {allowManualModifiers && !entered && (
              <div className="flex items-end gap-2">
                <div className="w-20">
                  <label htmlFor={`extra-bonus-${i}`} className={LABEL}>Extra +</label>
                  <input id={`extra-bonus-${i}`} type="number" value={row.extraAttackBonus}
                    onChange={e => update(i, { extraAttackBonus: e.target.value })} placeholder="+0" className={FIELD} />
                </div>
                <div className="w-24">
                  <label htmlFor={`extra-damage-${i}`} className={LABEL}>Extra dmg</label>
                  <input id={`extra-damage-${i}`} type="text" value={row.extraDamageDice}
                    onChange={e => update(i, { extraDamageDice: e.target.value })} placeholder="1d6" className={FIELD} />
                </div>
                <div className="w-28">
                  <label htmlFor={`extra-type-${i}`} className={LABEL}>Its type</label>
                  <select id={`extra-type-${i}`} value={row.extraDamageType}
                    onChange={e => update(i, { extraDamageType: e.target.value })} className={`${FIELD} h-[42px]`}>
                    <option value="">weapon's</option>
                    {DAMAGE_TYPES.map(dt => <option key={dt} value={dt}>{dt}</option>)}
                  </select>
                </div>
              </div>
            )}

            {blocked && (
              <p className="basis-full font-body text-[13px] font-semibold text-debuff">{blocked}</p>
            )}

            <div className="flex gap-1 ml-auto">
              {current.length < 5 && (
                <IconButton label="Clone this attack" onClick={() => cloneRow(i)} className="p-2 text-faint hover:text-buff">
                  <Copy className="w-4 h-4" />
                </IconButton>
              )}
              {current.length > 1 && (
                <IconButton label="Remove this attack" onClick={() => removeRow(i)} className="p-2 text-faint hover:text-debuff">
                  <X className="w-4 h-4" />
                </IconButton>
              )}
            </div>
          </div>
        );
      })}

      {error && <p role="alert" className="font-body text-[15px] font-semibold text-debuff">{error}</p>}

      <div className="flex items-center justify-between">
        {current.length < 5 ? (
          <button type="button" onClick={addRow} className="flex items-center gap-1 font-body text-[12.5px] font-semibold text-muted hover:text-ink py-1">
            <Plus className="w-3 h-3" /> Add attack
          </button>
        ) : <span />}
        <button type="submit" disabled={rolling}
          className="px-4 py-2 bg-ink hover:bg-ink/80 disabled:bg-page-alt text-card font-body text-[16px] font-semibold whitespace-nowrap">
          {rolling ? '...' : `Roll ${current.length > 1 ? `${current.length} ` : ''}Attack${current.length > 1 ? 's' : ''}`}
        </button>
      </div>
    </form>
  );
}
