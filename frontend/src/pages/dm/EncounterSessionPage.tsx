import { Fragment } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider, useEncounter } from '../../context/EncounterContext';
import { apiErrorMessage } from '../../api/errors';
import { useCombatLog, useEncounterAction, useMonsterActionTemplates } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import SpellCastModal from '../../components/encounter/SpellCastModal';
import RepeatEffectModal from '../../components/encounter/RepeatEffectModal';
import VisibilityToggle from '../../components/encounter/VisibilityToggle';
import LairToggle from '../../components/encounter/LairToggle';
import LairTurnRow from '../../components/encounter/LairTurnRow';
import RepeatSavePrompt from '../../components/encounter/RepeatSavePrompt';
import ResourcePoolDisplay from '../../components/common/ResourcePoolDisplay';
import IconButton from '../../components/ui/IconButton';
import { getParticipantColour, getParticipantBg } from '../../utils/classColours';
import {
  ArrowLeft, Pause, Play, Flag, Copy, Check, Wifi, WifiOff,
  ChevronRight, ChevronLeft, ChevronUp, ChevronDown, Heart, HeartPulse, Shield, Skull, Swords,
  Plus, Minus, X, ScrollText, Zap, Crosshair, Sparkles, RotateCw, Castle
} from 'lucide-react';
import { useState, useEffect, useRef, type FormEvent } from 'react';

const ALL_CONDITIONS = [
  'blinded', 'charmed', 'deafened', 'frightened', 'grappled',
  'incapacitated', 'invisible', 'paralyzed', 'petrified',
  'poisoned', 'prone', 'restrained', 'stunned', 'unconscious',
];

const DAMAGE_TYPES = [
  'bludgeoning', 'piercing', 'slashing', 'acid', 'cold', 'fire',
  'force', 'lightning', 'necrotic', 'poison', 'psychic', 'radiant', 'thunder',
];

type ActionMode = 'attack' | 'damage' | 'heal' | 'set-hp' | 'condition' | 'concentration' | 'spell' | 'repeat-effect' | null;

// A new attack row: within 5 feet by default, since there is no map (D161)
const NEW_ATTACK = { attackBonus: '', damageDice: '', damageType: '', advantage: null as boolean | null, forceCrit: false, within5Ft: true };

/** Dying, Stable or Dead, from the participant's life state (D161); nothing while it is up. */
function LifeStateBadge({ participant }: { participant: EncounterParticipant }) {
  switch (participant.lifeState) {
    case 'DEAD':
      return <span className="inline-flex items-center gap-1 font-heading text-[11px] font-semibold tracking-[0.02em] text-debuff"><Skull className="w-4 h-4" />Dead</span>;
    case 'DYING':
      return <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff">Dying</span>;
    case 'STABLE':
      return <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-buff border border-buff">Stable</span>;
    default:
      return null;
  }
}

function HpBar({ participant }: { participant: EncounterParticipant }) {
  const hpMax = participant.hpMax ?? 0;
  const pct = hpMax > 0 ? ((participant.hpCurrent ?? 0) / hpMax) * 100 : 0;
  const tempPct = hpMax > 0 ? ((participant.hpTemp || 0) / hpMax) * 100 : 0;

  let barColor = 'bg-buff';
  if (pct <= 25) barColor = 'bg-debuff';
  else if (pct <= 50) barColor = 'bg-cls-monk';

  return (
    <div className="w-full bg-rule h-2 overflow-hidden flex">
      <div className={`${barColor} h-full transition-all duration-300`} style={{ width: `${Math.min(pct, 100)}%` }} />
      {tempPct > 0 && (
        <div className="bg-cls-wizard h-full transition-all duration-300" style={{ width: `${Math.min(tempPct, 100 - pct)}%` }} />
      )}
    </div>
  );
}

// The Roll button shows only while the encounter is Active, since combat needs it (D166). A Dying PC whose
// turn has started is due a death save until it rolls one (D163).
function DeathSaves({ participant, canRoll }: { participant: EncounterParticipant; canRoll: boolean }) {
  const actions = useEncounterAction();
  const [rolling, setRolling] = useState(false);

  async function handleRoll() {
    setRolling(true);
    try {
      await actions.rollDeathSave(participant.id);
    } finally {
      setRolling(false);
    }
  }

  return (
    <div className="flex items-center gap-3 mt-1">
      <div className="flex items-center gap-1">
        <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">Saves:</span>
        {[0, 1, 2].map(i => (
          <div key={`s${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveSuccesses ? 'bg-buff border-buff' : 'border-rule-light'}`} />
        ))}
      </div>
      <div className="flex items-center gap-1">
        <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">Fails:</span>
        {[0, 1, 2].map(i => (
          <div key={`f${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveFailures ? 'bg-debuff border-debuff' : 'border-rule-light'}`} />
        ))}
      </div>
      {participant.deathSaveDue && (
        <span role="status" className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff">
          Death save due
        </span>
      )}
      {canRoll && (
        <button
          onClick={handleRoll}
          disabled={rolling}
          className={`px-2 py-0.5 font-body text-[16px] font-semibold disabled:opacity-50 ${
            participant.deathSaveDue ? 'bg-debuff text-card hover:bg-debuff/90' : 'bg-page-alt hover:bg-rule text-muted'
          }`}
        >
          {rolling ? '...' : 'Roll'}
        </button>
      )}
    </div>
  );
}

// Slots are spent and restored only while the encounter is Active; otherwise they are shown read-only (D166)
function SpellSlotDisplay({ participant, canChange }: { participant: EncounterParticipant; canChange: boolean }) {
  const actions = useEncounterAction();
  const slots = participant.spellSlotsCurrent;
  if (!slots) return null;

  const levels = Object.keys(slots).sort((a, b) => parseInt(a) - parseInt(b));
  if (levels.length === 0) return null;

  async function handleUse(level: number) {
    await actions.spendSpellSlot(participant.id, level);
  }

  async function handleRestore(level: number) {
    await actions.restoreSpellSlot(participant.id, level);
  }

  return (
    <div className="flex items-center gap-2 mt-1 flex-wrap">
      <Sparkles className="w-3 h-3 text-ink" />
      {levels.map(lvl => {
        const s = slots[lvl];
        const remaining = s.max - s.used;
        return (
          <div key={lvl} className="flex items-center gap-0.5">
            <span className="font-heading text-[12px] font-semibold tracking-[0.1em] text-faint">{lvl}:</span>
            <div className="flex gap-0.5">
              {Array.from({ length: s.max }).map((_, i) => canChange ? (
                <IconButton
                  key={i}
                  label={i < remaining ? `Use level ${lvl} slot` : `Restore level ${lvl} slot`}
                  onClick={() => i < remaining ? handleUse(parseInt(lvl)) : handleRestore(parseInt(lvl))}
                  className={`w-2.5 h-2.5 rounded-full border transition-colors ${
                    i < remaining
                      ? 'bg-ink border-ink hover:bg-ink/80'
                      : 'border-rule-light hover:border-ink'
                  }`}
                />
              ) : (
                <span
                  key={i}
                  aria-hidden="true"
                  className={`w-2.5 h-2.5 rounded-full border ${i < remaining ? 'bg-ink border-ink' : 'border-rule-light'}`}
                />
              ))}
              {!canChange && <span className="sr-only">{`${remaining} of ${s.max} left`}</span>}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function ActionPanel({
  selectedTarget,
  actionMode,
  setActionMode,
}: {
  selectedTarget: EncounterParticipant | null;
  actionMode: ActionMode;
  setActionMode: (mode: ActionMode) => void;
}) {
  const actions = useEncounterAction();
  const [amount, setAmount] = useState('');
  const [damageType, setDamageType] = useState('');
  const [condition, setCondition] = useState('');
  const [spellName, setSpellName] = useState('');
  const [attacks, setAttacks] = useState([{ ...NEW_ATTACK }]);
  const [conditionDuration, setConditionDuration] = useState('');
  const [tempHp, setTempHp] = useState('');
  const [loading, setLoading] = useState(false);

  function updateAttack(index: number, field: string, value: string | boolean | null) {
    setAttacks(prev => prev.map((a, i) => i === index ? { ...a, [field]: value } : a));
  }

  function addAttackRow() {
    if (attacks.length >= 5) return;
    setAttacks(prev => [...prev, { ...NEW_ATTACK }]);
  }

  function cloneAttackRow(index: number) {
    if (attacks.length >= 5) return;
    const source = attacks[index];
    setAttacks(prev => [...prev.slice(0, index + 1), { ...source }, ...prev.slice(index + 1)]);
  }

  function removeAttackRow(index: number) {
    if (attacks.length <= 1) return;
    setAttacks(prev => prev.filter((_, i) => i !== index));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!selectedTarget || loading) return;
    setLoading(true);

    try {
      if (actionMode === 'attack') {
        for (const atk of attacks) {
          if (atk.attackBonus !== '' && atk.damageDice) {
            await actions.rollAttack(selectedTarget.id, parseInt(atk.attackBonus), atk.damageDice, atk.damageType || undefined, atk.advantage, atk.forceCrit || undefined, undefined, atk.within5Ft);
          }
        }
      } else if (actionMode === 'damage' && amount) {
        await actions.applyDamage(selectedTarget.id, parseInt(amount), damageType || undefined);
      } else if (actionMode === 'heal' && amount) {
        await actions.applyHealing(selectedTarget.id, parseInt(amount));
      } else if (actionMode === 'set-hp' && amount !== '') {
        await actions.setHp(selectedTarget.id, parseInt(amount), tempHp !== '' ? parseInt(tempHp) : undefined);
      } else if (actionMode === 'condition' && condition) {
        await actions.addCondition(selectedTarget.id, condition, conditionDuration ? parseInt(conditionDuration) : undefined);
      } else if (actionMode === 'concentration') {
        await actions.setConcentration(selectedTarget.id, spellName || null);
      }
      setAmount('');
      setDamageType('');
      setCondition('');
      setSpellName('');
      setAttacks([{ ...NEW_ATTACK }]);
      setConditionDuration('');
      setTempHp('');
    } finally {
      setLoading(false);
    }
  }

  if (!selectedTarget || !actionMode) return null;

  return (
    <div className="bg-card border border-rule p-4 mb-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="font-heading text-[15px] font-semibold tracking-[0.01em] text-muted">
          {actionMode === 'attack' && <><Crosshair className="w-4 h-4 inline mr-1 text-cls-fighter" />Attack {selectedTarget.displayName} (AC {selectedTarget.armourClass})</>}
          {actionMode === 'damage' && <><Swords className="w-4 h-4 inline mr-1 text-debuff" />Damage {selectedTarget.displayName}</>}
          {actionMode === 'heal' && <><Heart className="w-4 h-4 inline mr-1 text-buff" />Heal {selectedTarget.displayName}</>}
          {actionMode === 'set-hp' && <><HeartPulse className="w-4 h-4 inline mr-1 text-buff" />Set HP / revive {selectedTarget.displayName}</>}
          {actionMode === 'condition' && <><Zap className="w-4 h-4 inline mr-1 text-cls-monk" />Add Condition to {selectedTarget.displayName}</>}
          {actionMode === 'concentration' && <><Zap className="w-4 h-4 inline mr-1 text-cls-warlock" />Concentration for {selectedTarget.displayName}</>}
        </h3>
        <IconButton label="Close action panel" onClick={() => setActionMode(null)} className="text-faint hover:text-muted">
          <X className="w-4 h-4" />
        </IconButton>
      </div>

      <form onSubmit={handleSubmit}>
        {actionMode === 'attack' && (
          <div className="flex gap-3">
            <div className="flex-1 space-y-2">
              {attacks.map((atk, i) => (
                <div key={i} className="flex items-end gap-2">
                  <div className="w-20">
                    {i === 0 && <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Attack +</label>}
                    <input
                      type="number"
                      value={atk.attackBonus}
                      onChange={e => updateAttack(i, 'attackBonus', e.target.value)}
                      placeholder="+5"
                      className="w-full px-2 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
                      autoFocus={i === 0}
                    />
                  </div>
                  <div className="w-24">
                    {i === 0 && <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Damage</label>}
                    <input
                      type="text"
                      value={atk.damageDice}
                      onChange={e => updateAttack(i, 'damageDice', e.target.value)}
                      placeholder="1d8+3"
                      className="w-full px-2 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
                    />
                  </div>
                  <div className="w-28">
                    {i === 0 && <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Type</label>}
                    <select
                      value={atk.damageType}
                      onChange={e => updateAttack(i, 'damageType', e.target.value)}
                      className="w-full h-[38px] px-2 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink focus:outline-none focus:border-muted"
                    >
                      <option value="">&#8212;</option>
                      {DAMAGE_TYPES.map(dt => (
                        <option key={dt} value={dt}>{dt}</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    {i === 0 && <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Roll</label>}
                    <div className="flex gap-1">
                      <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === false ? null : false)}
                        className={`px-2 py-2 font-body text-[16px] font-semibold ${atk.advantage === false ? 'bg-debuff text-card' : 'bg-page-alt text-muted border border-rule'}`}>
                        Dis
                      </button>
                      <button type="button" onClick={() => updateAttack(i, 'advantage', null)}
                        className={`px-2 py-2 font-body text-[16px] font-semibold ${atk.advantage === null ? 'bg-rule text-ink' : 'bg-page-alt text-muted border border-rule'}`}>
                        Norm
                      </button>
                      <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === true ? null : true)}
                        className={`px-2 py-2 font-body text-[16px] font-semibold ${atk.advantage === true ? 'bg-buff text-card' : 'bg-page-alt text-muted border border-rule'}`}>
                        Adv
                      </button>
                    </div>
                  </div>
                  <div>
                    {i === 0 && <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">&nbsp;</label>}
                    <button type="button" onClick={() => updateAttack(i, 'forceCrit', !atk.forceCrit)} aria-pressed={atk.forceCrit}
                      className={`px-2 py-2 font-body text-[16px] font-semibold ${atk.forceCrit ? 'bg-cls-monk text-card' : 'bg-page-alt text-muted border border-rule'}`}>
                      Crit
                    </button>
                    {/* A hit on an unconscious or paralyzed target is a critical hit only from within 5 feet (D161) */}
                    <button type="button" onClick={() => updateAttack(i, 'within5Ft', !atk.within5Ft)} aria-pressed={atk.within5Ft}
                      className={`px-2 py-2 font-body text-[16px] font-semibold ${atk.within5Ft ? 'bg-cls-wizard text-card' : 'bg-page-alt text-muted border border-rule'}`}>
                      Within 5 ft
                    </button>
                  </div>
                  <div className="flex gap-1">
                    {attacks.length < 5 && (
                      <IconButton label="Clone this attack" onClick={() => cloneAttackRow(i)}
                        className="p-2 text-faint hover:text-buff">
                        <Copy className="w-4 h-4" />
                      </IconButton>
                    )}
                    {attacks.length > 1 && (
                      <IconButton label="Remove this attack" onClick={() => removeAttackRow(i)}
                        className="p-2 text-faint hover:text-debuff">
                        <X className="w-4 h-4" />
                      </IconButton>
                    )}
                  </div>
                </div>
              ))}
              {attacks.length < 5 && (
                <button type="button" onClick={addAttackRow}
                  className="flex items-center gap-1 font-body text-[15px] font-semibold text-muted hover:text-ink py-1">
                  <Plus className="w-3 h-3" /> Add attack
                </button>
              )}
            </div>
            <div className="flex items-end">
              <button
                type="submit"
                disabled={loading}
                className="px-4 py-2 bg-ink hover:bg-ink/90 disabled:bg-ink/50 text-card font-body text-[16px] font-semibold whitespace-nowrap"
              >
                {loading ? '...' : `Roll ${attacks.length > 1 ? `${attacks.length} ` : ''}Attack${attacks.length > 1 ? 's' : ''}`}
              </button>
            </div>
          </div>
        )}

        {actionMode !== 'attack' && (
          <div className="flex items-end gap-3">
            {actionMode === 'set-hp' && (
              <>
                <div className="flex-1">
                  <label htmlFor="set-hp-current" className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">HP (of {selectedTarget.hpMax})</label>
                  <input
                    id="set-hp-current"
                    type="number"
                    min="0"
                    max={selectedTarget.hpMax ?? undefined}
                    value={amount}
                    onChange={e => setAmount(e.target.value)}
                    className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
                    autoFocus
                  />
                </div>
                <div className="w-32">
                  <label htmlFor="set-hp-temp" className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Temp HP</label>
                  <input
                    id="set-hp-temp"
                    type="number"
                    min="0"
                    value={tempHp}
                    onChange={e => setTempHp(e.target.value)}
                    placeholder="unchanged"
                    className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
                  />
                </div>
              </>
            )}

            {(actionMode === 'damage' || actionMode === 'heal') && (
              <>
                <div className="flex-1">
                  <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Amount</label>
                  <input
                    type="number"
                    min="0"
                    value={amount}
                    onChange={e => setAmount(e.target.value)}
                    className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
                    autoFocus
                  />
                </div>
                {actionMode === 'damage' && (
                  <div className="flex-1">
                    <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Type</label>
                    <select
                      value={damageType}
                      onChange={e => setDamageType(e.target.value)}
                      className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink focus:outline-none focus:border-muted"
                    >
                      <option value="">&#8212;</option>
                      {DAMAGE_TYPES.map(dt => (
                        <option key={dt} value={dt}>{dt}</option>
                      ))}
                    </select>
                  </div>
                )}
              </>
            )}

            {actionMode === 'condition' && (
              <>
                <div className="flex-1">
                  <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Condition</label>
                  <select
                    value={condition}
                    onChange={e => setCondition(e.target.value)}
                    className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink focus:outline-none focus:border-muted"
                    autoFocus
                  >
                    <option value="">Select condition...</option>
                    {ALL_CONDITIONS.map(c => (
                      <option key={c} value={c}>{c}</option>
                    ))}
                  </select>
                </div>
                <div className="w-28">
                  <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Duration (rounds)</label>
                  <input
                    type="number"
                    min="1"
                    value={conditionDuration}
                    onChange={e => setConditionDuration(e.target.value)}
                    placeholder="&#8734;"
                    className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
                  />
                </div>
              </>
            )}

            {actionMode === 'concentration' && (
              <div className="flex-1">
                <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Spell Name (blank to clear)</label>
                <input
                  type="text"
                  value={spellName}
                  onChange={e => setSpellName(e.target.value)}
                  placeholder="e.g. Bless, Hold Person"
                  className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
                  autoFocus
                />
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="px-4 py-2 bg-ink hover:bg-ink/90 disabled:bg-ink/50 text-card font-body text-[16px] font-semibold"
            >
              {loading ? '...' : 'Apply'}
            </button>
          </div>
        )}
      </form>
    </div>
  );
}

function CombatLogPanel({ canUseLegendaryResistance }: { canUseLegendaryResistance: boolean }) {
  // The server sends the whole log after every change (D171)
  const logs = useCombatLog();
  const actions = useEncounterAction();
  const [expanded, setExpanded] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [isAtBottom, setIsAtBottom] = useState(true);
  const [newCount, setNewCount] = useState(0);
  const prevLogCount = useRef(0);

  const isAtBottomRef = useRef(true);
  useEffect(() => { isAtBottomRef.current = isAtBottom; }, [isAtBottom]);

  useEffect(() => {
    const added = logs.length - prevLogCount.current;
    if (prevLogCount.current === 0 && logs.length > 0) {
      if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    } else if (added > 0) {
      if (isAtBottomRef.current && scrollRef.current) {
        scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
      } else {
        setNewCount(prev => prev + added);
      }
    }
    prevLogCount.current = logs.length;
  }, [logs]);

  function handleScroll() {
    if (!scrollRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current;
    const atBottom = scrollHeight - scrollTop - clientHeight < 30;
    setIsAtBottom(atBottom);
    if (atBottom) setNewCount(0);
  }

  function scrollToBottom() {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
      setIsAtBottom(true);
      setNewCount(0);
    }
  }

  function getLogColor(actionType: string) {
    switch (actionType) {
      case 'ATTACK': return 'text-cls-fighter';
      case 'DAMAGE': case 'KILL': return 'text-debuff';
      case 'HEAL': case 'REVIVE': return 'text-buff';
      case 'CONDITION_ADD': return 'text-cls-monk';
      case 'CONDITION_REMOVE': return 'text-cls-wizard';
      case 'DEATH_SAVE': return 'text-cls-fighter';
      case 'STABILIZE': return 'text-buff';
      case 'CONCENTRATION_CHECK': return 'text-cls-warlock';
      case 'CONCENTRATION_LOST': return 'text-cls-warlock';
      case 'SPELL_CAST': return 'text-ink';
      case 'SPELL_SLOT_USE': return 'text-ink';
      case 'SPELL_SLOT_RESTORE': return 'text-ink';
      case 'TURN_ADVANCE': case 'TURN_BACK': return 'text-faint';
      // M12 action types
      case 'MONSTER_ACTION': return 'text-monster';
      case 'LEGENDARY_ACTION': return 'text-cls-monk';
      case 'LAIR_ACTION': return 'text-cls-warlock';
      case 'LEGENDARY_RESISTANCE_USED': return 'text-cls-monk';
      case 'RECHARGE_SUCCESS': return 'text-buff';
      case 'RECHARGE_FAILURE': return 'text-faint';
      case 'HP_SET': return 'text-buff';
      default: return 'text-muted';
    }
  }

  function getLogBorderColor(actionType: string) {
    switch (actionType) {
      case 'ATTACK': case 'DAMAGE': case 'KILL': case 'DEATH_SAVE':
      case 'CONDITION_ADD': case 'CONCENTRATION_LOST':
        return 'border-debuff';
      case 'HEAL': case 'REVIVE': case 'STABILIZE': case 'CONDITION_REMOVE': case 'HP_SET':
        return 'border-buff';
      // M12 action types
      case 'MONSTER_ACTION': return 'border-monster';
      case 'LEGENDARY_ACTION': case 'LEGENDARY_RESISTANCE_USED': return 'border-cls-monk';
      case 'LAIR_ACTION': return 'border-cls-warlock';
      case 'RECHARGE_SUCCESS': return 'border-buff';
      case 'RECHARGE_FAILURE': return 'border-rule';
      default:
        return 'border-muted';
    }
  }

  return (
    <div className="bg-card border border-rule overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 hover:bg-page-alt"
      >
        <div className="flex items-center gap-2">
          <ScrollText className="w-4 h-4 text-muted" />
          <span className="font-heading text-[15px] font-semibold tracking-[0.01em] text-muted">Combat Log</span>
          <span className="font-body text-[15px] font-semibold text-faint">({logs.length} entries)</span>
        </div>
        <ChevronRight className={`w-4 h-4 text-faint transition-transform ${expanded ? 'rotate-90' : ''}`} />
      </button>
      {expanded && (
        <div className="relative">
          <div ref={scrollRef} onScroll={handleScroll} aria-live="polite" aria-label="Combat log entries" className="max-h-64 overflow-y-auto border-t border-rule px-4 py-2 space-y-1">
            {logs.length === 0 ? (
              <p className="text-faint font-body text-[15px] font-semibold py-2">No actions yet</p>
            ) : (
              logs.map((log, idx) => {
                const prevLog = idx > 0 ? logs[idx - 1] : null;
                const isTurnChange = log.actionType === 'TURN_ADVANCE' || log.actionType === 'TURN_BACK';
                const showRoundHeader = !prevLog || prevLog.roundNumber !== log.roundNumber;
                const turnName = isTurnChange ? log.targetName : log.turnParticipantName;
                const prevTurnName = prevLog
                  ? (prevLog.actionType === 'TURN_ADVANCE' || prevLog.actionType === 'TURN_BACK' ? prevLog.targetName : prevLog.turnParticipantName)
                  : null;
                const showTurnHeader = turnName && (
                  !prevLog || prevTurnName !== turnName || showRoundHeader
                );

                return (
                  <div key={log.id}>
                    {showRoundHeader && (
                      <div className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-ink border-b border-rule pb-1 pt-2 mb-1">
                        Round {log.roundNumber}
                      </div>
                    )}
                    {showTurnHeader && (
                      <div className="font-body text-[15px] font-semibold text-faint py-0.5 pl-2 border-l-2 border-rule my-1">
                        Turn: {turnName}
                      </div>
                    )}
                    {!isTurnChange && (
                      <div className={`bg-page-alt border-l-2 ${getLogBorderColor(log.actionType)} px-2 py-0.5`}>
                        <span className={`font-body text-[15px] font-semibold ${getLogColor(log.actionType)}`}>{log.description}</span>
                        {/* Only entries logged since R5 record the success outcome Legendary Resistance applies (D163) */}
                        {canUseLegendaryResistance && log.legendaryResistanceEligible && !log.lrResolved && log.lrSuccessDamage != null && (
                          <button
                            className="ml-2 px-2 py-0.5 bg-cls-monk hover:bg-cls-monk/90 text-card font-heading text-[11px] font-semibold tracking-[0.02em]"
                            onClick={() => { void actions.spendLegendaryResistance(log.id); }}
                          >
                            Use Legendary Resistance
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
          {!isAtBottom && newCount > 0 && (
            <button
              onClick={scrollToBottom}
              className="absolute bottom-2 left-1/2 -translate-x-1/2 flex items-center gap-1 px-3 py-1.5 bg-ink hover:bg-ink/90 text-card font-heading text-[11px] font-semibold shadow-lg"
            >
              <ChevronLeft className="w-3 h-3 rotate-[-90deg]" />
              Scroll to bottom ({newCount} new message{newCount !== 1 ? 's' : ''})
            </button>
          )}
        </div>
      )}
    </div>
  );
}

// ── M12 Inline Monster Actions ────────────────────────────────────────

// Legendary actions are used on other creatures' turns, the Lair's included, never on the monster's own; lair
// actions only on the Lair's turn, by a monster fighting in its lair (D163). The server refuses the rest.
function InlineMonsterActions({
  participant, participants, inlineTargets, inlineResult,
  inlineExecuting, onToggleTarget, onExecute, onClearResult, isLairTurn, inLair,
}: {
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  inlineTargets: string[];
  inlineResult: any;
  inlineExecuting: boolean;
  onToggleTarget: (id: string) => void;
  onExecute: (participantId: string, actionName: string, source: 'ACTION' | 'LEGENDARY' | 'LAIR') => void;
  onClearResult: () => void;
  isLairTurn: boolean;
  inLair: boolean;
}) {
  const templatesQuery = useMonsterActionTemplates(participant.id);
  const [tab, setTab] = useState<'actions' | 'legendary' | 'lair'>('actions');
  const [selectedAction, setSelectedAction] = useState<any>(null);

  if (templatesQuery.isPending) return <div className="px-4 py-2 font-body text-[15px] font-semibold text-muted">Loading actions...</div>;
  if (templatesQuery.isError) return <div className="px-4 py-2 font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(templatesQuery.error)}</div>;
  // A monster without templates gets {"error": ...}, which the template type leaves out
  const templates: any = templatesQuery.data;
  if (!templates || templates.error) return <div className="px-4 py-2 font-body text-[15px] font-semibold text-debuff">{templates?.error || 'No data'}</div>;

  const actions: any[] = templates.actions || [];
  const legendary: any[] = templates.legendaryActions || [];
  const lair: any[] = templates.lairActions || [];
  const pools: Record<string, any> = templates.resourcePools || {};
  const hasLegendary = legendary.length > 0;
  const hasLair = lair.length > 0;
  const legendaryRemaining = pools['monster:legendary-actions']?.currentUses ?? templates.legendaryActionCount ?? 0;
  const ownTurn = participant.isCurrentTurn;
  const lairReady = isLairTurn && inLair;
  const lairReason = !inLair ? `${participant.displayName} isn't fighting in its lair` : 'Lair actions wait for the Lair\'s turn, on initiative 20';

  const currentActions = tab === 'legendary' ? legendary : tab === 'lair' ? lair : actions;

  const isRecharged = (action: any) => {
    if (!action.recharge) return true;
    const kebab = action.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
    const poolId = `monster:recharge:${kebab}`;
    return !pools[poolId] || pools[poolId].currentUses > 0;
  };

  const formatActionLabel = (a: any) => {
    const parts = [a.name];
    if (a.attackBonus != null) parts.push(`+${a.attackBonus}`);
    if (a.saveDC) parts.push(`DC${a.saveDC}`);
    if (a.effects?.find((e: any) => e.effectType === 'DAMAGE' && e.damageDice)) {
      const dmg = a.effects.find((e: any) => e.effectType === 'DAMAGE' && e.damageDice);
      parts.push(dmg.damageDice);
    }
    return parts.join(' · ');
  };

  return (
    <div className="px-4 py-2 border-t border-rule-light bg-page">
      {/* Tabs */}
      {(hasLegendary || hasLair) && (
        <div className="flex gap-2 mb-2">
          <TabBtn active={tab === 'actions'} onClick={() => { setTab('actions'); setSelectedAction(null); }} label={`Actions (${actions.length})`} />
          {hasLegendary && <TabBtn active={tab === 'legendary'} onClick={() => { setTab('legendary'); setSelectedAction(null); }} label={`Legendary (${legendaryRemaining})`} />}
          {hasLair && <TabBtn active={tab === 'lair'} onClick={() => { setTab('lair'); setSelectedAction(null); }} label={`Lair (${lair.length})`} />}
        </div>
      )}

      {tab === 'legendary' && ownTurn && (
        <p role="note" className="mb-2 font-body text-[13px] font-semibold text-faint">
          Legendary actions are used on other creatures' turns, not {participant.displayName}'s own.
        </p>
      )}
      {tab === 'lair' && !lairReady && (
        <p role="note" className="mb-2 font-body text-[13px] font-semibold text-faint">{lairReason}.</p>
      )}

      {/* Action list — lair actions get full cards, others get compact buttons */}
      {tab === 'lair' ? (
        <div className="space-y-2 mb-2">
          {currentActions.map((a: any) => {
            const isNonAutomatable = a.automatable === false || (!a.deliveryMethod && !a.effects);
            return (
              <div
                key={a.name}
                aria-disabled={!lairReady}
                title={lairReady ? undefined : lairReason}
                className={`border p-3 transition-colors ${
                  !lairReady ? 'opacity-40 cursor-not-allowed border-rule-light bg-card' :
                  selectedAction?.name === a.name
                    ? 'border-ink bg-page-alt cursor-pointer'
                    : 'border-rule-light bg-card hover:bg-page-alt cursor-pointer'
                }`}
                onClick={() => {
                  if (!lairReady) return;
                  if (isNonAutomatable) {
                    onExecute(participant.id, a.name, 'LAIR');
                  } else {
                    setSelectedAction(selectedAction?.name === a.name ? null : a);
                  }
                }}
              >
                <div className="flex items-center gap-2 mb-1">
                  <Castle className="w-4 h-4 text-cls-warlock flex-shrink-0" />
                  <span className="font-heading text-[15px] font-semibold tracking-[0.01em] text-ink">{a.name}</span>
                  {a.saveDC && <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-muted">DC {a.saveDC} {a.saveAbility?.toUpperCase()}</span>}
                  {a.effects?.find((e: any) => e.effectType === 'DAMAGE' && e.damageDice) && (
                    <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-muted">
                      {a.effects.find((e: any) => e.effectType === 'DAMAGE').damageDice} {a.effects.find((e: any) => e.effectType === 'DAMAGE').damageType}
                    </span>
                  )}
                  {isNonAutomatable && (
                    <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 bg-cls-monk-bg text-cls-monk border border-cls-monk/30 ml-auto">Manual</span>
                  )}
                  {!isNonAutomatable && selectedAction?.name === a.name && (
                    <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 bg-ink text-card ml-auto">Selected</span>
                  )}
                </div>
                <p className="font-body text-[15px] font-semibold text-muted leading-snug">{a.description}</p>
              </div>
            );
          })}
        </div>
      ) : (
        <div className="flex flex-wrap gap-1 mb-2">
          {currentActions.filter((a: any) => !a.isMultiattack).map((a: any) => {
            const recharged = isRecharged(a);
            const isNonAutomatable = a.automatable === false || (!a.deliveryMethod && !a.effects);
            const isLegendary = tab === 'legendary';
            const actionCost = a.cost || 1;
            const legendaryDepleted = isLegendary && legendaryRemaining < actionCost;
            const legendaryOnOwnTurn = isLegendary && ownTurn;
            return (
              <button
                key={a.name}
                disabled={!recharged || inlineExecuting || legendaryDepleted || legendaryOnOwnTurn}
                onClick={() => {
                  if (legendaryDepleted || legendaryOnOwnTurn || !recharged) return;
                  if (isNonAutomatable) {
                    onExecute(participant.id, a.name, tab === 'legendary' ? 'LEGENDARY' : 'ACTION');
                  } else {
                    setSelectedAction(selectedAction?.name === a.name ? null : a);
                  }
                }}
                className={`px-2 py-1 font-heading text-[12px] font-semibold tracking-[0.02em] border transition-colors
                  ${!recharged || legendaryDepleted || legendaryOnOwnTurn ? 'opacity-40 cursor-not-allowed border-rule-light bg-card text-muted' :
                    isNonAutomatable ? 'border-cls-monk bg-cls-monk-bg text-cls-monk hover:bg-cls-monk/20' :
                    selectedAction?.name === a.name ? 'border-ink bg-ink text-card' :
                    'border-monster bg-monster-bg text-monster hover:bg-monster/20'}`}
                title={legendaryOnOwnTurn ? `Not on ${participant.displayName}'s own turn`
                  : legendaryDepleted ? `Not enough legendary actions (need ${actionCost}, have ${legendaryRemaining})`
                  : (a.description || formatActionLabel(a))}
              >
                {a.name}
                {a.cost > 0 && <span className="ml-1 text-[11px] opacity-70">({a.cost})</span>}
                {a.recharge && !recharged && <span className="ml-1 text-[11px]">↻</span>}
                {a.recharge && recharged && <span className="ml-1 text-[11px] text-buff">✓</span>}
                {legendaryDepleted && <span className="ml-1 text-[11px]">✗</span>}
              </button>
            );
          })}
        </div>
      )}

      {/* Multiattack buttons */}
      {tab === 'actions' && actions.filter((a: any) => a.isMultiattack).map((a: any) => (
        <button
          key={a.name}
          disabled={inlineExecuting}
          onClick={() => onExecute(participant.id, a.name, 'ACTION')}
          className="px-2 py-1 font-heading text-[12px] font-semibold tracking-[0.02em] border border-cls-monk bg-cls-monk-bg text-cls-monk hover:bg-cls-monk/20 mr-1 mb-1"
        >
          ⚔ {a.name}
        </button>
      ))}

      {/* Target selector for automatable actions */}
      <div className="flex flex-wrap gap-1 mt-2">
        {participants.filter(t => t.id !== participant.id && t.lifeState !== 'DEAD').map(t => (
          <button
            key={t.id}
            onClick={() => onToggleTarget(t.id)}
            className={`px-2 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] border transition-colors
              ${inlineTargets.includes(t.id)
                ? 'bg-monster text-card border-monster'
                : 'bg-card border-rule-light hover:bg-monster-bg'}`}
          >
            {t.displayName}
          </button>
        ))}
      </div>

      {/* Execute button for selected automatable action */}
      {selectedAction && (
        <div className="mt-2">
          <button
            disabled={inlineTargets.length === 0 || inlineExecuting
              || (tab === 'legendary' && (ownTurn || legendaryRemaining < (selectedAction.cost || 1)))
              || (tab === 'lair' && !lairReady)}
            onClick={() => {
              if (tab === 'legendary' && (ownTurn || legendaryRemaining < (selectedAction.cost || 1))) return;
              if (tab === 'lair' && !lairReady) return;
              onExecute(participant.id, selectedAction.name, tab === 'legendary' ? 'LEGENDARY' : tab === 'lair' ? 'LAIR' : 'ACTION');
              setSelectedAction(null);
            }}
            className="px-3 py-1 bg-ink hover:bg-ink/90 disabled:bg-page-alt disabled:text-faint text-card font-heading text-[12px] font-semibold tracking-[0.02em]"
          >
            {inlineExecuting ? 'Executing...' : `Execute ${selectedAction.name}${inlineTargets.length > 0 ? ` (${inlineTargets.length} target${inlineTargets.length !== 1 ? 's' : ''})` : ''}`}
          </button>
        </div>
      )}

      {/* Execute + result */}
      {inlineResult && (
        <div className={`mt-2 p-3 border font-body text-[15px] font-semibold ${inlineResult.error ? 'bg-debuff-bg border-debuff text-debuff' : 'bg-buff-bg border-buff text-buff'}`}>
          {inlineResult.error || inlineResult.description}
          {inlineResult.targetResults?.map((tr: any) => (
            <div key={tr.targetId}>{tr.targetName}: {tr.attackOutcome}{tr.damage > 0 ? ` — ${tr.damage} ${tr.damageType}` : ''}{tr.conditionsApplied?.length > 0 ? ` — ${tr.conditionsApplied.join(', ')}` : ''}</div>
          ))}
          <button className="mt-1 font-body text-[15px] font-semibold text-muted underline hover:text-ink" onClick={onClearResult}>Clear</button>
        </div>
      )}
    </div>
  );
}

function TabBtn({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      onClick={onClick}
      className={`px-2 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] transition-colors
        ${active ? 'bg-monster text-card' : 'bg-card border border-rule-light text-muted hover:text-ink'}`}
    >
      {label}
    </button>
  );
}

// ── DmSessionView ──────────────────────────────────────────────────────

function DmSessionView() {
  const { encounter, isConnected, error } = useEncounter();
  const actions = useEncounterAction();
  const navigate = useNavigate();
  const [copiedCode, setCopiedCode] = useState(false);
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null);
  const [actionMode, setActionMode] = useState<ActionMode>(null);
  const [expandedMonsterId, setExpandedMonsterId] = useState<string | null>(null);
  const [inlineTargets, setInlineTargets] = useState<string[]>([]);
  const [inlineResult, setInlineResult] = useState<any>(null);
  const [inlineExecuting, setInlineExecuting] = useState(false);
  const [turnError, setTurnError] = useState('');

  async function executeInlineAction(participantId: string, actionName: string, source: 'ACTION' | 'LEGENDARY' | 'LAIR') {
    if (inlineTargets.length === 0) return;
    setInlineExecuting(true);
    try {
      setInlineResult(await actions.monsterAction({
        monsterParticipantId: participantId,
        actionName,
        actionSource: source,
        targetParticipantIds: inlineTargets,
      }));
    } catch (err: any) {
      const msg = err.response?.data?.message
          || err.response?.data?.error
          || (typeof err.response?.data === 'string' ? err.response.data : null)
          || err.message;
      setInlineResult({ error: msg });
    } finally {
      setInlineExecuting(false);
    }
  }

  async function toggleMonsterActions(participantId: string) {
    if (expandedMonsterId === participantId) {
      setExpandedMonsterId(null);
      setInlineTargets([]);
      setInlineResult(null);
    } else {
      setExpandedMonsterId(participantId);
      setInlineTargets([]);
      setInlineResult(null);
    }
  }

  function toggleInlineTarget(targetId: string) {
    setInlineTargets(prev =>
      prev.includes(targetId) ? prev.filter(t => t !== targetId) : [...prev, targetId]
    );
  }

  if (!encounter) {
    return (
      <div className="min-h-screen bg-page flex items-center justify-center">
        {error
          ? <p role="alert" className="text-debuff font-body text-[15px] font-semibold">{error}</p>
          : <p className="text-muted font-body text-[15px] font-semibold">Loading encounter...</p>}
      </div>
    );
  }

  function copySessionCode() {
    if (!encounter?.sessionCode) return;
    navigator.clipboard.writeText(encounter.sessionCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  }

  async function handlePause() {
    await actions.pause();
  }

  async function handleResume() {
    await actions.resume();
  }

  async function handleEnd() {
    if (!window.confirm('End this encounter? This cannot be undone.')) return;
    await actions.end();
  }

  // A turn-order change the server refuses, such as a turn when nobody can act, says why (D163)
  async function attemptTurnChange(change: () => Promise<unknown>) {
    try {
      await change();
      setTurnError('');
    } catch (err: unknown) {
      setTurnError(apiErrorMessage(err));
    }
  }

  async function handleNextTurn() {
    await attemptTurnChange(() => actions.nextTurn());
  }

  async function handlePrevTurn() {
    await attemptTurnChange(() => actions.previousTurn());
  }

  async function handleRemoveCondition(participantId: string, condition: string) {
    await actions.removeCondition(participantId, condition);
  }

  async function handleSetVisibility(participantId: string, visible: boolean) {
    await actions.setVisibility(participantId, visible);
  }

  function selectTarget(participantId: string, mode: ActionMode) {
    setSelectedTargetId(participantId);
    setActionMode(mode);
  }

  // The server computes the turn order, the Lair's turn included, and each participant's sortOrder is its
  // turn's place in it (D163)
  const sorted = [...encounter.participants].sort((a, b) => a.sortOrder - b.sortOrder);
  const participantsById = new Map(encounter.participants.map(p => [p.id, p]));
  const turns = encounter.turnOrder;
  const isLairTurn = encounter.currentTurnIndex != null && (turns[encounter.currentTurnIndex]?.lair ?? false);
  const lairMonsters = encounter.monstersInLair.flatMap(id => participantsById.get(id)?.displayName ?? []);
  const selectedTarget = sorted.find(p => p.id === selectedTargetId) ?? null;
  // Combat controls show only while the encounter is Active: a Paused one can only resume or end, and a
  // Completed one is final (D166)
  const isActive = encounter.status === 'ACTIVE';
  const isPaused = encounter.status === 'PAUSED';

  // Swaps a participant with its neighbour in a tie on initiative and DEX, which only the DM's order breaks
  const moveInTie = (index: number, offset: -1 | 1) => {
    const group = turns[index].tieGroup;
    const ids = turns.filter(turn => turn.tieGroup === group).flatMap(turn => turn.participantId ?? []);
    const at = ids.indexOf(turns[index].participantId ?? '');
    [ids[at], ids[at + offset]] = [ids[at + offset], ids[at]];
    return attemptTurnChange(() => actions.reorderTie(ids));
  };

  // The whole list of monsters fighting in their lair, with this one in it or out of it
  const setInLair = (participantId: string, inLair: boolean) => {
    const others = encounter.monstersInLair.filter(id => id !== participantId);
    return actions.setLairStatus(inLair ? [...others, participantId] : others);
  };

  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-page border-b border-rule px-6 py-3">
        <div className="flex items-center justify-between">
          <button onClick={() => navigate('/dm/encounters')} className="flex items-center gap-2 text-muted hover:text-ink transition-colors font-body text-[16px] font-semibold">
            <ArrowLeft className="w-4 h-4" /> Builder
          </button>
          <div className="flex items-center gap-4">
            {encounter.sessionCode && (
              <div className="flex items-center gap-2 bg-card border border-rule px-3 py-1.5">
                <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">Code:</span>
                <span className="text-ink font-mono font-bold tracking-wider">{encounter.sessionCode}</span>
                <IconButton label={copiedCode ? 'Session code copied' : 'Copy session code'} onClick={copySessionCode} className="text-muted hover:text-ink">
                  {copiedCode ? <Check className="w-3.5 h-3.5 text-buff" /> : <Copy className="w-3.5 h-3.5" />}
                </IconButton>
              </div>
            )}
            <div className="flex items-center gap-1.5">
              {isConnected ? <Wifi className="w-4 h-4 text-buff" /> : <WifiOff className="w-4 h-4 text-debuff" />}
              <span className="font-body text-[15px] font-semibold text-muted">{isConnected ? 'Live' : 'Disconnected'}</span>
            </div>
          </div>
        </div>
      </header>

      <main className="px-6 py-4">
        {error && <p className="text-debuff font-body text-[15px] font-semibold mb-4">{error}</p>}

        {/* Encounter info + controls bar */}
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="font-heading text-[20px] font-bold tracking-[0.02em] text-ink">{encounter.name}</h1>
            <p className="text-muted font-body text-[15px] font-semibold">
              Round {encounter.roundNumber} &middot; {encounter.participants.filter(p => p.lifeState !== 'DEAD').length}/{encounter.participants.length} alive
            </p>
          </div>
          <div className="flex items-center gap-2">
            {isActive && (
              <>
                <IconButton label="Previous turn" onClick={handlePrevTurn} className="flex items-center gap-1 px-3 py-2 bg-page-alt hover:bg-rule text-muted font-body text-[16px] font-semibold">
                  <ChevronLeft className="w-4 h-4" />
                </IconButton>
                <button onClick={handleNextTurn} className="flex items-center gap-1.5 px-4 py-2 bg-ink hover:bg-ink/90 text-card font-body text-[16px] font-semibold">
                  Next Turn <ChevronRight className="w-4 h-4" />
                </button>
              </>
            )}
            {encounter.status === 'ACTIVE' && (
              <button onClick={handlePause} className="flex items-center gap-1.5 px-3 py-2 bg-cls-monk hover:bg-cls-monk/90 text-card font-body text-[16px] font-semibold">
                <Pause className="w-4 h-4" /> Pause
              </button>
            )}
            {encounter.status === 'PAUSED' && (
              <button onClick={handleResume} className="flex items-center gap-1.5 px-3 py-2 bg-buff hover:bg-buff/90 text-card font-body text-[16px] font-semibold">
                <Play className="w-4 h-4" /> Resume
              </button>
            )}
            {(encounter.status === 'ACTIVE' || encounter.status === 'PAUSED') && (
              <button onClick={handleEnd} className="flex items-center gap-1.5 px-3 py-2 bg-debuff hover:bg-debuff/90 text-card font-body text-[16px] font-semibold">
                <Flag className="w-4 h-4" /> End
              </button>
            )}
            {encounter.status === 'COMPLETED' && (
              <span className="px-3 py-2 bg-page-alt text-muted font-body text-[16px] font-semibold">Complete</span>
            )}
          </div>
        </div>

        {isPaused && (
          <div role="status" className="flex items-center gap-2 mb-4 px-4 py-2.5 bg-cls-monk/10 border border-cls-monk/30">
            <Pause className="w-4 h-4 text-cls-monk" />
            <p className="text-cls-monk font-heading text-[15px] font-semibold tracking-[0.01em]">Paused — resume to continue</p>
          </div>
        )}

        {turnError && <p role="alert" className="text-debuff font-body text-[15px] font-semibold mb-4">{turnError}</p>}

        {/* Spell cast modal */}
        {isActive && actionMode === 'spell' && selectedTarget && (
          <SpellCastModal
            caster={selectedTarget}
            participants={encounter.participants}
            onClose={() => { setActionMode(null); setSelectedTargetId(null); }}
            allowManualModifiers
          />
        )}

        {/* Repeat spell effect modal */}
        {isActive && actionMode === 'repeat-effect' && selectedTarget && (selectedTarget.concentrationSpell || selectedTarget.activeSpell) && (
          <RepeatEffectModal
            caster={selectedTarget}
            participants={encounter.participants}
            spellName={(selectedTarget.concentrationSpell || selectedTarget.activeSpell)!}
            onClose={() => { setActionMode(null); setSelectedTargetId(null); }}
            allowManualModifiers
          />
        )}

        {/* Action panel */}
        {isActive && actionMode !== 'spell' && (
          <ActionPanel
            selectedTarget={selectedTarget}
            actionMode={actionMode}
            setActionMode={setActionMode}
          />
        )}

        {/* The initiative order as the server computes it, the Lair's turn included (D163) */}
        <div className="space-y-2 mb-4" role="list" aria-label="Initiative order">
          {turns.map((turn, index) => {
            if (turn.lair) {
              return <LairTurnRow key="lair" turn={turn} lairMonsters={lairMonsters} />;
            }
            const p = participantsById.get(turn.participantId ?? '');
            if (!p) return null;
            const tiedBefore = turn.tieGroup != null && turns[index - 1]?.tieGroup === turn.tieGroup;
            const tiedAfter = turn.tieGroup != null && turns[index + 1]?.tieGroup === turn.tieGroup;
            const conditions = p.activeConditions ?? [];
            const isSelected = p.id === selectedTargetId;
            const isMonster = p.participantType === 'MONSTER';
            const hpRatio = p.hpCurrent != null && p.hpMax ? p.hpCurrent / p.hpMax : 0;
            // Alive, Dying, Stable or Dead (D161): the dead can only be revived with Set HP
            const isUp = p.lifeState === 'ALIVE';
            const isDead = p.lifeState === 'DEAD';

            return (
              <Fragment key={p.id}>
              <div
                aria-current={turn.current ? 'step' : undefined}
                className={`bg-card border border-l-[3px] px-4 py-3 transition-all ${
                  turn.current ? 'border-t-cls-fighter border-r-cls-fighter border-b-cls-fighter ring-1 ring-cls-fighter/30' :
                  isSelected ? 'border-t-ink border-r-ink border-b-ink ring-1 ring-ink/30' :
                  'border-t-rule border-r-rule border-b-rule'
                } ${!isUp ? 'opacity-60' : ''}`}
                style={{ borderLeftColor: getParticipantColour(isMonster) }}
              >
                <div className="flex items-center gap-3">
                  {/* Turn indicator */}
                  <div className="w-5 flex-shrink-0">
                    {turn.current && <ChevronRight className="w-5 h-5 text-cls-fighter" />}
                  </div>

                  {/* Initiative */}
                  <div className="w-9 text-center flex-shrink-0">
                    <span className="font-heading text-[17px] font-bold text-ink">{p.initiative ?? '—'}</span>
                  </div>

                  {/* The DM's order among participants tied on initiative and DEX (D163) */}
                  {isActive && turn.tieGroup != null && (
                    <div className="flex flex-col flex-shrink-0 -my-1">
                      <IconButton
                        label={`Move ${p.displayName} up the tie`}
                        disabled={!tiedBefore}
                        onClick={() => moveInTie(index, -1)}
                        className="p-0.5 text-faint hover:text-ink disabled:opacity-30"
                      >
                        <ChevronUp className="w-3.5 h-3.5" />
                      </IconButton>
                      <IconButton
                        label={`Move ${p.displayName} down the tie`}
                        disabled={!tiedAfter}
                        onClick={() => moveInTie(index, 1)}
                        className="p-0.5 text-faint hover:text-ink disabled:opacity-30"
                      >
                        <ChevronDown className="w-3.5 h-3.5" />
                      </IconButton>
                    </div>
                  )}

                  {/* Name + info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span
                        className={`font-heading text-[15px] font-semibold tracking-[0.01em] text-ink ${isDead ? 'line-through' : ''}`}
                      >{p.displayName}</span>
                      <span
                        className={`font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5`}
                        style={{ color: getParticipantColour(isMonster), backgroundColor: getParticipantBg(isMonster) }}
                      >
                        {p.participantType}
                      </span>
                      {isMonster && (
                        <VisibilityToggle participant={p} onToggle={visible => handleSetVisibility(p.id, visible)} />
                      )}
                      {isActive && isMonster && p.hasLairActions && (
                        <LairToggle
                          participant={p}
                          inLair={encounter.monstersInLair.includes(p.id)}
                          onToggle={inLair => setInLair(p.id, inLair)}
                        />
                      )}
                      <LifeStateBadge participant={p} />
                    </div>

                    {/* Conditions */}
                    {conditions.length > 0 && (
                      <div className="flex gap-1 mt-1 flex-wrap">
                        {conditions.map(c => {
                          const remaining = c.duration != null ? Math.max(0, c.duration - (encounter.roundNumber - c.appliedRound)) : null;
                          const label = `${c.name}${c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}${remaining != null ? ` (${remaining})` : ''}`;
                          // A Repeat Save is rolled at the end of each of the creature's turns (D162)
                          const saveEnds = c.repeatSave ? `; ${c.repeatSave.ability} save DC ${c.repeatSave.dc} at the end of each turn ends it` : '';
                          return isActive ? (
                            <Fragment key={c.id}>
                              <button
                                onClick={() => handleRemoveCondition(p.id, c.name)}
                                className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff hover:opacity-75 cursor-pointer"
                                title={`Click to remove ${c.name}${remaining != null ? ` (${remaining} rounds left)` : ''}${saveEnds}`}
                              >
                                {label} <X className="w-2.5 h-2.5 inline" />
                              </button>
                              <RepeatSavePrompt participant={p} condition={c} compact />
                            </Fragment>
                          ) : (
                            <span
                              key={c.id}
                              className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff"
                              title={remaining != null || saveEnds ? `${remaining != null ? `${remaining} rounds left` : ''}${saveEnds}` : undefined}
                            >
                              {label}
                            </span>
                          );
                        })}
                      </div>
                    )}

                    {/* Concentration */}
                    {p.concentrationSpell && (
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <p className="text-cls-warlock font-body text-[15px] font-semibold">
                          Concentrating: {p.concentrationSpell}
                        </p>
                        {isActive && (
                          <IconButton
                            label="Repeat spell effect"
                            onClick={(e) => { e.stopPropagation(); selectTarget(p.id, 'repeat-effect'); }}
                            className="p-0.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock"
                          >
                            <RotateCw className="w-3 h-3" />
                          </IconButton>
                        )}
                      </div>
                    )}

                    {/* Active spell (non-concentration persistent) */}
                    {p.activeSpell && (
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <p className="text-cls-monk font-body text-[15px] font-semibold">
                          Active: {p.activeSpell}
                        </p>
                        {isActive && (
                          <IconButton
                            label="Repeat spell effect"
                            onClick={(e) => { e.stopPropagation(); selectTarget(p.id, 'repeat-effect'); }}
                            className="p-0.5 bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk"
                          >
                            <RotateCw className="w-3 h-3" />
                          </IconButton>
                        )}
                      </div>
                    )}

                    {/* Death saves only while Dying: a Stable PC makes none, and the dead are past them (D161) */}
                    {p.lifeState === 'DYING' && (
                      <DeathSaves participant={p} canRoll={isActive} />
                    )}

                    {/* Spell slots */}
                    {isUp && p.spellSlotsCurrent && (
                      <SpellSlotDisplay participant={p} canChange={isActive} />
                    )}

                    {/* Resource pools (M24.5) */}
                    <ResourcePoolDisplay pools={p.resourcePoolsCurrent} />
                  </div>

                  {/* HP bar + values */}
                  <div className="w-36 flex-shrink-0">
                    <div className="flex items-center justify-end gap-1.5 mb-1">
                      <Heart className={`w-3.5 h-3.5 ${hpRatio > 0.5 ? 'text-buff' : hpRatio > 0.25 ? 'text-cls-monk' : 'text-debuff'}`} />
                      <span className="font-heading text-[13px] font-semibold text-ink">{p.hpCurrent}/{p.hpMax}</span>
                      {(p.hpTemp || 0) > 0 && <span className="text-cls-wizard font-heading text-[13px] font-semibold">+{p.hpTemp}</span>}
                    </div>
                    <HpBar participant={p} />
                  </div>

                  {/* AC */}
                  <div className="flex items-center gap-1 w-12 justify-end flex-shrink-0">
                    <Shield className="w-4 h-4 text-muted" />
                    <span className="text-muted font-heading text-[13px] font-semibold">{p.armourClass}</span>
                  </div>

                  {/* Quick action buttons */}
                  {isActive && (
                    <div className="flex items-center gap-1 flex-shrink-0 ml-2">
                      {/* Monster actions toggle (M12) */}
                      {isMonster && (
                        <IconButton
                          label="Monster actions"
                          onClick={() => toggleMonsterActions(p.id)}
                          aria-expanded={expandedMonsterId === p.id}
                          className={`p-1.5 ${expandedMonsterId === p.id ? 'bg-monster/20 text-monster' : 'bg-monster/10 hover:bg-monster/20 text-monster'}`}
                        >
                          <Swords className="w-3.5 h-3.5" />
                        </IconButton>
                      )}
                      {/* The dead can't be attacked, damaged or healed; Set HP / revive brings them back (D161) */}
                      {!isDead && (
                        <IconButton
                          label="Attack roll"
                          onClick={() => selectTarget(p.id, 'attack')}
                          className="p-1.5 bg-cls-fighter/10 hover:bg-cls-fighter/20 text-cls-fighter"
                        >
                          <Crosshair className="w-3.5 h-3.5" />
                        </IconButton>
                      )}
                      {!isDead && (
                        <IconButton
                          label="Deal damage"
                          onClick={() => selectTarget(p.id, 'damage')}
                          className="p-1.5 bg-debuff/10 hover:bg-debuff/20 text-debuff"
                        >
                          <Minus className="w-3.5 h-3.5" />
                        </IconButton>
                      )}
                      {!isDead && (
                        <IconButton
                          label="Heal"
                          onClick={() => selectTarget(p.id, 'heal')}
                          className="p-1.5 bg-buff/10 hover:bg-buff/20 text-buff"
                        >
                          <Plus className="w-3.5 h-3.5" />
                        </IconButton>
                      )}
                      <IconButton
                        label="Set HP / revive"
                        onClick={() => selectTarget(p.id, 'set-hp')}
                        className="p-1.5 bg-buff/10 hover:bg-buff/20 text-buff"
                      >
                        <HeartPulse className="w-3.5 h-3.5" />
                      </IconButton>
                      <IconButton
                        label="Add condition"
                        onClick={() => selectTarget(p.id, 'condition')}
                        className="p-1.5 bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk"
                      >
                        <Zap className="w-3.5 h-3.5" />
                      </IconButton>
                      {isUp && (
                        <IconButton
                          label="Set concentration"
                          onClick={() => selectTarget(p.id, 'concentration')}
                          className="p-1.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock"
                        >
                          <Swords className="w-3.5 h-3.5" />
                        </IconButton>
                      )}
                      {isUp && (
                        <IconButton
                          label="Cast spell"
                          onClick={() => selectTarget(p.id, 'spell')}
                          className="p-1.5 bg-ink/10 hover:bg-ink/20 text-ink"
                        >
                          <Sparkles className="w-3.5 h-3.5" />
                        </IconButton>
                      )}
                    </div>
                  )}
                </div>
              </div>

              {/* M12 Inline monster action panel */}
              {isActive && expandedMonsterId === p.id && isMonster && (
                <InlineMonsterActions
                  participant={p}
                  participants={sorted}
                  inlineTargets={inlineTargets}
                  inlineResult={inlineResult}
                  inlineExecuting={inlineExecuting}
                  onToggleTarget={toggleInlineTarget}
                  onExecute={executeInlineAction}
                  onClearResult={() => setInlineResult(null)}
                  isLairTurn={isLairTurn}
                  inLair={encounter.monstersInLair.includes(p.id)}
                />
              )}
            </Fragment>
            );
          })}
        </div>

        {/* Combat log */}
        <CombatLogPanel canUseLegendaryResistance={isActive} />
      </main>
    </div>
  );
}

export default function EncounterSessionPage() {
  const { encounterId } = useParams<{ encounterId: string }>();
  if (!encounterId) return null;

  return (
    <EncounterProvider encounterId={encounterId} view="dm">
      <DmSessionView />
    </EncounterProvider>
  );
}
