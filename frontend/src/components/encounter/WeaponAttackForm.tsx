import { useState, type FormEvent } from 'react';
import { Copy, Plus, X } from 'lucide-react';
import IconButton from '../ui/IconButton';
import AttackRollSources from './AttackRollSources';
import { apiErrorMessage } from '../../api/errors';
import { useAttackOptions, useEncounterAction } from '../../queries/encounters';
import type { AttackGrip, AttackOption, AttackRollRequest } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import { useEncounter } from '../../hooks/useEncounter';
import { chargeBlock } from '../../utils/turnEconomy';
import {
  guidedStrikeBlock, heldInspiration, isReckless, kiLeft, monkStrikeBlock, RECKLESS_TEXT, SNEAK_ATTACK_TEXT,
  sneakAttackUsed,
} from '../../utils/classFeatures';

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
// action (PHB p.195); an opportunity attack, a reaction that may be made on another creature's turn; and a monk's
// bonus-action unarmed strikes, Martial Arts' and Flurry of Blows' (PHB p.78), offered only to a monk
const COSTS: { value: AttackCost; label: string }[] = [
  { value: 'ACTION', label: 'Attack' },
  { value: 'BONUS_ACTION', label: 'Off-hand (bonus action)' },
  { value: 'REACTION', label: 'Opportunity attack (reaction)' },
  { value: 'MARTIAL_ARTS', label: 'Martial Arts strike (bonus action)' },
  { value: 'FLURRY_OF_BLOWS', label: 'Flurry of Blows (1 ki, two strikes)' },
];

const UNARMED = 'Unarmed Strike';

/** Whether a cost is one of a monk's bonus-action unarmed strikes (PHB p.78). */
function monkStrike(cost: AttackCost): cost is 'MARTIAL_ARTS' | 'FLURRY_OF_BLOWS' {
  return cost === 'MARTIAL_ARTS' || cost === 'FLURRY_OF_BLOWS';
}

/** Sneak Attack as the player sets it (PHB p.96): left to the roll's advantage, claimed for an ally, or declined. */
type SneakChoice = 'auto' | 'claim' | 'decline';

const GRIPS: { value: AttackGrip; label: string }[] = [
  { value: 'ONE_HANDED', label: 'One hand' },
  { value: 'TWO_HANDED', label: 'Two hands' },
  { value: 'TWO_WEAPONS', label: 'A weapon in each hand' },
];

/**
 * The cost an attack starts on (D174): on the attacker's turn the Attack action while it can pay, then an off-hand
 * attack, and off its turn an opportunity attack. When nothing can pay, Attack on its turn, so the row says why.
 */
function automaticCost(attacker: EncounterParticipant): AttackCost {
  const candidates: ('ACTION' | 'BONUS_ACTION' | 'REACTION')[] = attacker.isCurrentTurn ? ['ACTION', 'BONUS_ACTION'] : ['REACTION'];
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
  /** How the weapons are held, or null for the server's default (M17). */
  grip: AttackGrip | null;
  /** Sneak Attack, for a rogue's finesse or ranged weapon (PHB p.96). */
  sneak: SneakChoice;
  /** Roll the held Bardic Inspiration die if the attack would miss (PHB p.53, M17). */
  inspiration: boolean;
  /** Guided Strike's +10 if the attack would miss and that makes it hit (PHB p.63, M17). */
  guidedStrike: boolean;
  /** A Kensei monk's Unerring Accuracy: reroll the attack if it misses, once on each of its turns (XGE p.35). */
  unerring: boolean;
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
    cost, weapon: option?.weapon ?? ENTERED, powerAttack: false, reckless: false, grip: null, sneak: 'auto',
    inspiration: false, guidedStrike: false, unerring: false, advantage: null, within5Ft: !(option?.ranged ?? false),
    forceCrit: false, extraAttackBonus: '', extraDamageDice: '', extraDamageType: '',
    attackBonus: '', damageDice: '', damageType: '',
  };
}

function signed(value: number) {
  return value < 0 ? `${value}` : `+${value}`;
}

/**
 * The numbers an option shows, with Great Weapon Master's or Sharpshooter's trade when the player takes it, a versatile
 * weapon's two-handed damage when held in two hands, and the class features in them, such as "Dueling +2" (M17).
 */
function summary(option: AttackOption, powerAttack: boolean, grip: AttackGrip | null) {
  const traded = powerAttack && option.powerAttack;
  const bonus = traded ? option.powerAttack!.attackBonus : option.attackBonus;
  const twoHanded = grip === 'TWO_HANDED' && option.versatileDamage;
  const damage = traded ? option.powerAttack!.damage : twoHanded ? option.versatileDamage : option.damage;
  // The listed features are those of the usual grip; another grip changes Dueling and Great Weapon Fighting
  const features = grip ? [] : option.features;
  return `${signed(bonus)} to hit · ${damage}${option.damageType ? ` ${option.damageType}` : ''}`
    + `${features.length ? ` (${features.join(', ')})` : ''}`;
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
    // Decided once the d20 is seen: the server adds them only to a roll that would miss (M17)
    useInspiration: row.inspiration ? true : undefined,
    guidedStrike: row.guidedStrike ? true : undefined,
  };
  // Unerring Accuracy goes with a monk weapon, which the server knows (XGE p.35)
  const unerringAccuracy = row.unerring && option?.unerringAccuracy ? true : undefined;
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
    grip: row.grip ?? undefined,
    sneakAttack: !option?.sneakAttack || row.sneak === 'auto' ? undefined : row.sneak === 'claim',
    unerringAccuracy,
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
  const { encounter } = useEncounter();
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
  // A monk's bonus-action strikes: Martial Arts while it applies to the unarmed strike, Flurry of Blows with a ki pool
  const martialArts = options.some(o => o.weapon === UNARMED && o.martialArts);
  const flurry = kiLeft(attacker) !== null;
  const costs = COSTS.filter(c => c.value === 'MARTIAL_ARTS' ? martialArts
    : c.value === 'FLURRY_OF_BLOWS' ? flurry : true);
  const sneakUsed = sneakAttackUsed(attacker, encounter?.turnStamp);

  function update(index: number, change: Partial<AttackRow>) {
    setRows(current.map((row, i) => i === index ? { ...row, ...change } : row));
  }

  // A new weapon brings its own reach and drops a trade, grip and Sneak Attack choice it may not allow
  function chooseWeapon(index: number, weapon: string) {
    const option = optionFor(weapon);
    update(index, {
      weapon, powerAttack: false, reckless: false, grip: null, sneak: 'auto', within5Ft: !(option?.ranged ?? false),
    });
  }

  // A monk's bonus-action strikes are unarmed strikes (PHB p.78)
  function chooseCost(index: number, cost: AttackCost) {
    if (monkStrike(cost)) {
      update(index, { cost, weapon: UNARMED, powerAttack: false, reckless: false, grip: null, sneak: 'auto', within5Ft: true });
    } else {
      update(index, { cost });
    }
  }

  // A held Bardic Inspiration die and a War cleric's Guided Strike, added only when the roll would miss (M17)
  const inspirationDie = heldInspiration(attacker)?.heldDie ?? null;
  const guidedStrikeOffered = guidedStrikeBlock(attacker) === null;

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
        const blocked = monkStrike(cost) ? monkStrikeBlock(attacker, cost) : chargeBlock(attacker, { cost, attack: true });
        // Grip matters for a melee weapon held in one hand or two, not for a two-handed weapon or an off-hand attack
        const gripChoices = option && !option.ranged && option.weapon !== UNARMED && !option.twoHanded
          && cost !== 'BONUS_ACTION' && !monkStrike(cost)
          ? GRIPS.filter(g => g.value !== 'TWO_HANDED' || option.versatileDamage) : [];
        return (
          <div key={i} role="group" aria-label={`Attack ${i + 1}`} className="flex flex-wrap items-end gap-2 pb-3 border-b border-rule-light last:border-0">
            <div className="w-56">
              <label htmlFor={`cost-${i}`} className={LABEL}>Cost</label>
              <select id={`cost-${i}`} value={cost} onChange={e => chooseCost(i, e.target.value as AttackCost)}
                className={`${FIELD} h-[42px]`}>
                {costs.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
            </div>

            <div className="w-56">
              <label htmlFor={`weapon-${i}`} className={LABEL}>Weapon</label>
              <select id={`weapon-${i}`} value={row.weapon} onChange={e => chooseWeapon(i, e.target.value)}
                disabled={monkStrike(cost)} className={`${FIELD} h-[42px]`}>
                {options.map(o => <option key={o.weapon} value={o.weapon}>{o.weapon}</option>)}
                {allowManualModifiers && <option value={ENTERED}>Other: enter the numbers</option>}
              </select>
            </div>

            {option && (
              <p className="font-heading text-[13px] font-semibold text-ink pb-2.5">
                {summary(option, row.powerAttack, row.grip)}{!option.proficient && <span className="text-faint"> (not proficient)</span>}
              </p>
            )}

            {gripChoices.length > 0 && (
              <div className="w-44">
                <label htmlFor={`grip-${i}`} className={LABEL}>Grip</label>
                <select id={`grip-${i}`} value={row.grip ?? 'ONE_HANDED'}
                  onChange={e => update(i, { grip: e.target.value as AttackGrip })} className={`${FIELD} h-[42px]`}>
                  {gripChoices.map(g => <option key={g.value} value={g.value}>{g.label}</option>)}
                </select>
              </div>
            )}

            {/* Sneak Attack, where the server offers it: a rogue's finesse or ranged weapon (PHB p.96) */}
            {option?.sneakAttack && (
              <div className="w-56">
                <label htmlFor={`sneak-${i}`} className={LABEL}>Sneak Attack {option.sneakAttack}</label>
                {sneakUsed ? (
                  <p className="font-body text-[13px] font-semibold text-faint py-2" title={SNEAK_ATTACK_TEXT}>
                    Already dealt this turn
                  </p>
                ) : (
                  <select id={`sneak-${i}`} value={row.sneak} title={SNEAK_ATTACK_TEXT}
                    onChange={e => update(i, { sneak: e.target.value as SneakChoice })} className={`${FIELD} h-[42px]`}>
                    <option value="auto">On a hit with advantage</option>
                    <option value="claim">An ally is within 5 ft of the target</option>
                    <option value="decline">Decline</option>
                  </select>
                )}
              </div>
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

            {/* What the conditions add by themselves, which the player's choice combines with (PHB p.173) */}
            <AttackRollSources attacker={attacker} target={target} withinFiveFeet={row.within5Ft} />

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
              {/* Bardic Inspiration (PHB p.53) and Guided Strike (PHB p.63), added only if the roll would miss */}
              {inspirationDie && (
                <button type="button" onClick={() => update(i, { inspiration: !row.inspiration })}
                  aria-pressed={row.inspiration}
                  title={`Roll the Bardic Inspiration d${inspirationDie.sides} and add it if the attack would miss`}
                  className={`px-2 py-2 font-body text-[12.5px] font-semibold ${row.inspiration ? 'bg-buff text-card' : TOGGLE_OFF}`}>
                  Inspiration d{inspirationDie.sides}
                </button>
              )}
              {guidedStrikeOffered && (
                <button type="button" onClick={() => update(i, { guidedStrike: !row.guidedStrike })}
                  aria-pressed={row.guidedStrike}
                  title="Channel Divinity: +10 if the attack would miss and that makes it hit; the use is spent only then"
                  className={`px-2 py-2 font-body text-[12.5px] font-semibold ${row.guidedStrike ? 'bg-buff text-card' : TOGGLE_OFF}`}>
                  Guided Strike
                </button>
              )}
              {/* Unerring Accuracy (XGE p.35), rerolled only if the attack misses, once on each of the monk's turns */}
              {option?.unerringAccuracy && attacker.isCurrentTurn && !attacker.turnEconomy?.unerringAccuracyUsed && (
                <button type="button" onClick={() => update(i, { unerring: !row.unerring })}
                  aria-pressed={row.unerring}
                  title="Reroll the attack if it misses, once on each of your turns; the new roll stands"
                  className={`min-h-11 px-2 py-2 font-body text-[12.5px] font-semibold ${row.unerring ? 'bg-buff text-card' : TOGGLE_OFF}`}>
                  Unerring Accuracy
                </button>
              )}
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
              <div className="flex flex-wrap items-end gap-2">
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
