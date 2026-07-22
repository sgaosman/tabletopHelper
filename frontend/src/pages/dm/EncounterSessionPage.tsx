import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider, useEncounter } from '../../context/EncounterContext';
import { encounterApi } from '../../api/encounterApi';
import { combatApi } from '../../api/combatApi';
import type { EncounterParticipant, ConditionEntry, SpellSlots } from '../../types/encounter';
import type { CombatLogEntry } from '../../types/combat';
import SpellCastModal from '../../components/encounter/SpellCastModal';
import RepeatEffectModal from '../../components/encounter/RepeatEffectModal';
import { getParticipantColour, getParticipantBg } from '../../utils/classColours';
import {
  ArrowLeft, Pause, Play, Flag, Copy, Check, Wifi, WifiOff,
  ChevronRight, ChevronLeft, Heart, Shield, Skull, Swords,
  Plus, Minus, X, ScrollText, Zap, Crosshair, Sparkles, RotateCw
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

type ActionMode = 'attack' | 'damage' | 'heal' | 'condition' | 'concentration' | 'spell' | 'repeat-effect' | null;

function HpBar({ participant }: { participant: EncounterParticipant }) {
  const pct = participant.hpMax > 0 ? (participant.hpCurrent / participant.hpMax) * 100 : 0;
  const tempPct = participant.hpMax > 0 ? ((participant.hpTemp || 0) / participant.hpMax) * 100 : 0;

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

function DeathSaves({ participant, encounterId, onUpdate }: { participant: EncounterParticipant; encounterId: string; onUpdate: () => void }) {
  const [rolling, setRolling] = useState(false);

  async function handleRoll() {
    setRolling(true);
    try {
      await combatApi.rollDeathSave(encounterId, participant.id);
      onUpdate();
    } finally {
      setRolling(false);
    }
  }

  return (
    <div className="flex items-center gap-3 mt-1">
      <div className="flex items-center gap-1">
        <span className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted">Saves:</span>
        {[0, 1, 2].map(i => (
          <div key={`s${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveSuccesses ? 'bg-buff border-buff' : 'border-rule-light'}`} />
        ))}
      </div>
      <div className="flex items-center gap-1">
        <span className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted">Fails:</span>
        {[0, 1, 2].map(i => (
          <div key={`f${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveFailures ? 'bg-debuff border-debuff' : 'border-rule-light'}`} />
        ))}
      </div>
      <button
        onClick={handleRoll}
        disabled={rolling}
        className="px-2 py-0.5 font-body text-[14px] font-medium bg-page-alt hover:bg-rule text-muted disabled:opacity-50"
      >
        {rolling ? '...' : 'Roll'}
      </button>
    </div>
  );
}

function SpellSlotDisplay({ participant, encounterId, onUpdate }: { participant: EncounterParticipant; encounterId: string; onUpdate: () => void }) {
  const slots = parseSpellSlots(participant);
  if (!slots) return null;

  const levels = Object.keys(slots).sort((a, b) => parseInt(a) - parseInt(b));
  if (levels.length === 0) return null;

  async function handleUse(level: number) {
    await combatApi.useSpellSlot(encounterId, participant.id, level);
    onUpdate();
  }

  async function handleRestore(level: number) {
    await combatApi.restoreSpellSlot(encounterId, participant.id, level);
    onUpdate();
  }

  return (
    <div className="flex items-center gap-2 mt-1 flex-wrap">
      <Sparkles className="w-3 h-3 text-ink" />
      {levels.map(lvl => {
        const s = slots[lvl];
        return (
          <div key={lvl} className="flex items-center gap-0.5">
            <span className="font-heading text-[10px] font-semibold tracking-[0.1em] text-faint">{lvl}:</span>
            <div className="flex gap-0.5">
              {Array.from({ length: s.max }).map((_, i) => (
                <button
                  key={i}
                  onClick={() => i < s.remaining ? handleUse(parseInt(lvl)) : handleRestore(parseInt(lvl))}
                  className={`w-2.5 h-2.5 rounded-full border transition-colors ${
                    i < s.remaining
                      ? 'bg-ink border-ink hover:bg-ink/80'
                      : 'border-rule-light hover:border-ink'
                  }`}
                  title={i < s.remaining ? `Use level ${lvl} slot` : `Restore level ${lvl} slot`}
                />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function parseSpellSlots(p: EncounterParticipant): SpellSlots | null {
  if (!p.spellSlotsCurrent) return null;
  try {
    const parsed = JSON.parse(p.spellSlotsCurrent);
    if (Object.keys(parsed).length === 0) return null;
    return parsed;
  } catch {
    return null;
  }
}

function ActionPanel({
  encounterId,
  selectedTarget,
  actionMode,
  setActionMode,
  onUpdate,
}: {
  encounterId: string;
  selectedTarget: EncounterParticipant | null;
  actionMode: ActionMode;
  setActionMode: (mode: ActionMode) => void;
  onUpdate: () => void;
}) {
  const [amount, setAmount] = useState('');
  const [damageType, setDamageType] = useState('');
  const [condition, setCondition] = useState('');
  const [spellName, setSpellName] = useState('');
  const [attacks, setAttacks] = useState([{ attackBonus: '', damageDice: '', damageType: '', advantage: null as boolean | null, forceCrit: false, isRanged: false }]);
  const [conditionDuration, setConditionDuration] = useState('');
  const [loading, setLoading] = useState(false);

  function updateAttack(index: number, field: string, value: string | boolean | null) {
    setAttacks(prev => prev.map((a, i) => i === index ? { ...a, [field]: value } : a));
  }

  function addAttackRow() {
    if (attacks.length >= 5) return;
    setAttacks(prev => [...prev, { attackBonus: '', damageDice: '', damageType: '', advantage: null, forceCrit: false, isRanged: false }]);
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
            await combatApi.rollAttack(encounterId, selectedTarget.id, parseInt(atk.attackBonus), atk.damageDice, atk.damageType || undefined, atk.advantage, atk.forceCrit || undefined, undefined, atk.isRanged || undefined);
          }
        }
      } else if (actionMode === 'damage' && amount) {
        await combatApi.applyDamage(encounterId, selectedTarget.id, parseInt(amount), damageType || undefined);
      } else if (actionMode === 'heal' && amount) {
        await combatApi.applyHealing(encounterId, selectedTarget.id, parseInt(amount));
      } else if (actionMode === 'condition' && condition) {
        await combatApi.addCondition(encounterId, selectedTarget.id, condition, conditionDuration ? parseInt(conditionDuration) : undefined);
      } else if (actionMode === 'concentration') {
        await combatApi.setConcentration(encounterId, selectedTarget.id, spellName || null);
      }
      onUpdate();
      setAmount('');
      setDamageType('');
      setCondition('');
      setSpellName('');
      setAttacks([{ attackBonus: '', damageDice: '', damageType: '', advantage: null, forceCrit: false, isRanged: false }]);
      setConditionDuration('');
    } finally {
      setLoading(false);
    }
  }

  if (!selectedTarget || !actionMode) return null;

  return (
    <div className="bg-card border border-rule p-4 mb-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="font-heading text-[13px] font-semibold tracking-[0.01em] text-muted">
          {actionMode === 'attack' && <><Crosshair className="w-4 h-4 inline mr-1 text-cls-fighter" />Attack {selectedTarget.displayName} (AC {selectedTarget.armourClass})</>}
          {actionMode === 'damage' && <><Swords className="w-4 h-4 inline mr-1 text-debuff" />Damage {selectedTarget.displayName}</>}
          {actionMode === 'heal' && <><Heart className="w-4 h-4 inline mr-1 text-buff" />Heal {selectedTarget.displayName}</>}
          {actionMode === 'condition' && <><Zap className="w-4 h-4 inline mr-1 text-cls-monk" />Add Condition to {selectedTarget.displayName}</>}
          {actionMode === 'concentration' && <><Zap className="w-4 h-4 inline mr-1 text-cls-warlock" />Concentration for {selectedTarget.displayName}</>}
        </h3>
        <button onClick={() => setActionMode(null)} className="text-faint hover:text-muted">
          <X className="w-4 h-4" />
        </button>
      </div>

      <form onSubmit={handleSubmit}>
        {actionMode === 'attack' && (
          <div className="flex gap-3">
            <div className="flex-1 space-y-2">
              {attacks.map((atk, i) => (
                <div key={i} className="flex items-end gap-2">
                  <div className="w-20">
                    {i === 0 && <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Attack +</label>}
                    <input
                      type="number"
                      value={atk.attackBonus}
                      onChange={e => updateAttack(i, 'attackBonus', e.target.value)}
                      placeholder="+5"
                      className="w-full px-2 py-2 bg-page-alt border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
                      autoFocus={i === 0}
                    />
                  </div>
                  <div className="w-24">
                    {i === 0 && <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Damage</label>}
                    <input
                      type="text"
                      value={atk.damageDice}
                      onChange={e => updateAttack(i, 'damageDice', e.target.value)}
                      placeholder="1d8+3"
                      className="w-full px-2 py-2 bg-page-alt border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
                    />
                  </div>
                  <div className="w-28">
                    {i === 0 && <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Type</label>}
                    <select
                      value={atk.damageType}
                      onChange={e => updateAttack(i, 'damageType', e.target.value)}
                      className="w-full h-[38px] px-2 py-2 bg-page-alt border border-rule font-body text-[14px] font-medium text-ink focus:outline-none focus:border-muted"
                    >
                      <option value="">&#8212;</option>
                      {DAMAGE_TYPES.map(dt => (
                        <option key={dt} value={dt}>{dt}</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    {i === 0 && <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Roll</label>}
                    <div className="flex gap-1">
                      <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === false ? null : false)}
                        className={`px-2 py-2 font-body text-[14px] font-medium ${atk.advantage === false ? 'bg-debuff text-white' : 'bg-page-alt text-muted border border-rule'}`}>
                        Dis
                      </button>
                      <button type="button" onClick={() => updateAttack(i, 'advantage', null)}
                        className={`px-2 py-2 font-body text-[14px] font-medium ${atk.advantage === null ? 'bg-rule text-ink' : 'bg-page-alt text-muted border border-rule'}`}>
                        Norm
                      </button>
                      <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === true ? null : true)}
                        className={`px-2 py-2 font-body text-[14px] font-medium ${atk.advantage === true ? 'bg-buff text-white' : 'bg-page-alt text-muted border border-rule'}`}>
                        Adv
                      </button>
                    </div>
                  </div>
                  <div>
                    {i === 0 && <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">&nbsp;</label>}
                    <button type="button" onClick={() => updateAttack(i, 'forceCrit', !atk.forceCrit)}
                      className={`px-2 py-2 font-body text-[14px] font-medium ${atk.forceCrit ? 'bg-cls-monk text-white' : 'bg-page-alt text-muted border border-rule'}`}>
                      Crit
                    </button>
                    <button type="button" onClick={() => updateAttack(i, 'isRanged', !atk.isRanged)}
                      className={`px-2 py-2 font-body text-[14px] font-medium ${atk.isRanged ? 'bg-cls-wizard text-white' : 'bg-page-alt text-muted border border-rule'}`}>
                      Ranged
                    </button>
                  </div>
                  <div className="flex gap-1">
                    {attacks.length < 5 && (
                      <button type="button" onClick={() => cloneAttackRow(i)}
                        className="p-2 text-faint hover:text-buff" title="Clone this attack">
                        <Copy className="w-4 h-4" />
                      </button>
                    )}
                    {attacks.length > 1 && (
                      <button type="button" onClick={() => removeAttackRow(i)}
                        className="p-2 text-faint hover:text-debuff" title="Remove this attack">
                        <X className="w-4 h-4" />
                      </button>
                    )}
                  </div>
                </div>
              ))}
              {attacks.length < 5 && (
                <button type="button" onClick={addAttackRow}
                  className="flex items-center gap-1 font-body text-[13px] font-medium text-muted hover:text-ink py-1">
                  <Plus className="w-3 h-3" /> Add attack
                </button>
              )}
            </div>
            <div className="flex items-end">
              <button
                type="submit"
                disabled={loading}
                className="px-4 py-2 bg-ink hover:bg-ink/90 disabled:bg-ink/50 text-card font-body text-[14px] font-medium whitespace-nowrap"
              >
                {loading ? '...' : `Roll ${attacks.length > 1 ? `${attacks.length} ` : ''}Attack${attacks.length > 1 ? 's' : ''}`}
              </button>
            </div>
          </div>
        )}

        {actionMode !== 'attack' && (
          <div className="flex items-end gap-3">
            {(actionMode === 'damage' || actionMode === 'heal') && (
              <>
                <div className="flex-1">
                  <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Amount</label>
                  <input
                    type="number"
                    min="0"
                    value={amount}
                    onChange={e => setAmount(e.target.value)}
                    className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
                    autoFocus
                  />
                </div>
                {actionMode === 'damage' && (
                  <div className="flex-1">
                    <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Type</label>
                    <select
                      value={damageType}
                      onChange={e => setDamageType(e.target.value)}
                      className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[14px] font-medium text-ink focus:outline-none focus:border-muted"
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
                  <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Condition</label>
                  <select
                    value={condition}
                    onChange={e => setCondition(e.target.value)}
                    className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[14px] font-medium text-ink focus:outline-none focus:border-muted"
                    autoFocus
                  >
                    <option value="">Select condition...</option>
                    {ALL_CONDITIONS.map(c => (
                      <option key={c} value={c}>{c}</option>
                    ))}
                  </select>
                </div>
                <div className="w-28">
                  <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Duration (rounds)</label>
                  <input
                    type="number"
                    min="1"
                    value={conditionDuration}
                    onChange={e => setConditionDuration(e.target.value)}
                    placeholder="&#8734;"
                    className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
                  />
                </div>
              </>
            )}

            {actionMode === 'concentration' && (
              <div className="flex-1">
                <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Spell Name (blank to clear)</label>
                <input
                  type="text"
                  value={spellName}
                  onChange={e => setSpellName(e.target.value)}
                  placeholder="e.g. Bless, Hold Person"
                  className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
                  autoFocus
                />
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="px-4 py-2 bg-ink hover:bg-ink/90 disabled:bg-ink/50 text-card font-body text-[14px] font-medium"
            >
              {loading ? '...' : 'Apply'}
            </button>
          </div>
        )}
      </form>
    </div>
  );
}

function CombatLogPanel({ encounterId }: { encounterId: string }) {
  const [logs, setLogs] = useState<CombatLogEntry[]>([]);
  const [expanded, setExpanded] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [isAtBottom, setIsAtBottom] = useState(true);
  const [newCount, setNewCount] = useState(0);
  const prevLogCount = useRef(0);

  useEffect(() => {
    combatApi.getCombatLog(encounterId).then(res => setLogs(res.data));
    const interval = setInterval(() => {
      combatApi.getCombatLog(encounterId).then(res => setLogs(res.data));
    }, 3000);
    return () => clearInterval(interval);
  }, [encounterId]);

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
      default: return 'text-muted';
    }
  }

  function getLogBorderColor(actionType: string) {
    switch (actionType) {
      case 'ATTACK': case 'DAMAGE': case 'KILL': case 'DEATH_SAVE':
      case 'CONDITION_ADD': case 'CONCENTRATION_LOST':
        return 'border-debuff';
      case 'HEAL': case 'REVIVE': case 'STABILIZE': case 'CONDITION_REMOVE':
        return 'border-buff';
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
          <span className="font-heading text-[13px] font-semibold tracking-[0.01em] text-muted">Combat Log</span>
          <span className="font-body text-[13px] font-medium text-faint">({logs.length} entries)</span>
        </div>
        <ChevronRight className={`w-4 h-4 text-faint transition-transform ${expanded ? 'rotate-90' : ''}`} />
      </button>
      {expanded && (
        <div className="relative">
          <div ref={scrollRef} onScroll={handleScroll} aria-live="polite" aria-label="Combat log entries" className="max-h-64 overflow-y-auto border-t border-rule px-4 py-2 space-y-1">
            {logs.length === 0 ? (
              <p className="text-faint font-body text-[13px] font-medium py-2">No actions yet</p>
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
                      <div className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-ink border-b border-rule pb-1 pt-2 mb-1">
                        Round {log.roundNumber}
                      </div>
                    )}
                    {showTurnHeader && (
                      <div className="font-body text-[13px] font-medium text-faint py-0.5 pl-2 border-l-2 border-rule my-1">
                        Turn: {turnName}
                      </div>
                    )}
                    {!isTurnChange && (
                      <div className={`bg-page-alt border-l-2 ${getLogBorderColor(log.actionType)} px-2 py-0.5`}>
                        <span className={`font-body text-[13px] font-medium ${getLogColor(log.actionType)}`}>{log.description}</span>
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
              className="absolute bottom-2 left-1/2 -translate-x-1/2 flex items-center gap-1 px-3 py-1.5 bg-ink hover:bg-ink/90 text-card font-heading text-[9px] font-medium shadow-lg"
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

function DmSessionView() {
  const { encounter, isConnected, error, refreshEncounter } = useEncounter();
  const navigate = useNavigate();
  const [copiedCode, setCopiedCode] = useState(false);
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null);
  const [actionMode, setActionMode] = useState<ActionMode>(null);

  if (!encounter) {
    return <div className="min-h-screen bg-page flex items-center justify-center"><p className="text-muted font-body text-[13px] font-medium">Loading encounter...</p></div>;
  }

  function copySessionCode() {
    if (!encounter?.sessionCode) return;
    navigator.clipboard.writeText(encounter.sessionCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  }

  async function handlePause() {
    if (!encounter) return;
    await encounterApi.pause(encounter.id);
    refreshEncounter();
  }

  async function handleResume() {
    if (!encounter) return;
    await encounterApi.resume(encounter.id);
    refreshEncounter();
  }

  async function handleEnd() {
    if (!encounter) return;
    if (!window.confirm('End this encounter? This cannot be undone.')) return;
    await encounterApi.end(encounter.id);
    refreshEncounter();
  }

  async function handleNextTurn() {
    if (!encounter) return;
    await combatApi.advanceTurn(encounter.id);
    refreshEncounter();
  }

  async function handlePrevTurn() {
    if (!encounter) return;
    await combatApi.previousTurn(encounter.id);
    refreshEncounter();
  }

  async function handleRemoveCondition(participantId: string, condition: string) {
    if (!encounter) return;
    await combatApi.removeCondition(encounter.id, participantId, condition);
    refreshEncounter();
  }

  function selectTarget(participantId: string, mode: ActionMode) {
    setSelectedTargetId(participantId);
    setActionMode(mode);
  }

  function parseConditions(p: EncounterParticipant): ConditionEntry[] {
    if (!p.activeConditions) return [];
    try {
      const parsed = JSON.parse(p.activeConditions);
      if (parsed.length > 0 && typeof parsed[0] === 'string') {
        return parsed.map((name: string) => ({ name, duration: null, appliedRound: 1 }));
      }
      return parsed;
    } catch {
      return [];
    }
  }

  const sorted = [...encounter.participants].sort((a, b) => (a.sortOrder ?? 999) - (b.sortOrder ?? 999));
  const selectedTarget = sorted.find(p => p.id === selectedTargetId) ?? null;
  const isActive = encounter.status === 'ACTIVE';

  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-page border-b border-rule px-6 py-3">
        <div className="flex items-center justify-between">
          <button onClick={() => navigate('/dm/encounters')} className="flex items-center gap-2 text-muted hover:text-ink transition-colors font-body text-[14px] font-medium">
            <ArrowLeft className="w-4 h-4" /> Builder
          </button>
          <div className="flex items-center gap-4">
            {encounter.sessionCode && (
              <div className="flex items-center gap-2 bg-card border border-rule px-3 py-1.5">
                <span className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted">Code:</span>
                <span className="text-ink font-mono font-bold tracking-wider">{encounter.sessionCode}</span>
                <button onClick={copySessionCode} className="text-muted hover:text-ink">
                  {copiedCode ? <Check className="w-3.5 h-3.5 text-buff" /> : <Copy className="w-3.5 h-3.5" />}
                </button>
              </div>
            )}
            <div className="flex items-center gap-1.5">
              {isConnected ? <Wifi className="w-4 h-4 text-buff" /> : <WifiOff className="w-4 h-4 text-debuff" />}
              <span className="font-body text-[13px] font-medium text-muted">{isConnected ? 'Live' : 'Disconnected'}</span>
            </div>
          </div>
        </div>
      </header>

      <main className="px-6 py-4">
        {error && <p className="text-debuff font-body text-[13px] font-medium mb-4">{error}</p>}

        {/* Encounter info + controls bar */}
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="font-heading text-[20px] font-bold tracking-[0.02em] text-ink">{encounter.name}</h1>
            <p className="text-muted font-body text-[13px] font-medium">
              Round {encounter.roundNumber} &middot; {encounter.participants.filter(p => p.isAlive).length}/{encounter.participants.length} alive
            </p>
          </div>
          <div className="flex items-center gap-2">
            {isActive && (
              <>
                <button onClick={handlePrevTurn} className="flex items-center gap-1 px-3 py-2 bg-page-alt hover:bg-rule text-muted font-body text-[14px] font-medium" title="Previous turn">
                  <ChevronLeft className="w-4 h-4" />
                </button>
                <button onClick={handleNextTurn} className="flex items-center gap-1.5 px-4 py-2 bg-ink hover:bg-ink/90 text-card font-body text-[14px] font-medium">
                  Next Turn <ChevronRight className="w-4 h-4" />
                </button>
              </>
            )}
            {encounter.status === 'ACTIVE' && (
              <button onClick={handlePause} className="flex items-center gap-1.5 px-3 py-2 bg-cls-monk hover:bg-cls-monk/90 text-white font-body text-[14px] font-medium">
                <Pause className="w-4 h-4" /> Pause
              </button>
            )}
            {encounter.status === 'PAUSED' && (
              <button onClick={handleResume} className="flex items-center gap-1.5 px-3 py-2 bg-buff hover:bg-buff/90 text-white font-body text-[14px] font-medium">
                <Play className="w-4 h-4" /> Resume
              </button>
            )}
            {(encounter.status === 'ACTIVE' || encounter.status === 'PAUSED') && (
              <button onClick={handleEnd} className="flex items-center gap-1.5 px-3 py-2 bg-debuff hover:bg-debuff/90 text-white font-body text-[14px] font-medium">
                <Flag className="w-4 h-4" /> End
              </button>
            )}
            {encounter.status === 'COMPLETED' && (
              <span className="px-3 py-2 bg-page-alt text-muted font-body text-[14px] font-medium">Complete</span>
            )}
          </div>
        </div>

        {/* Spell cast modal */}
        {actionMode === 'spell' && selectedTarget && (
          <SpellCastModal
            encounterId={encounter.id}
            caster={selectedTarget}
            participants={encounter.participants}
            onUpdate={refreshEncounter}
            onClose={() => { setActionMode(null); setSelectedTargetId(null); }}
            isMonster={selectedTarget.participantType === 'MONSTER'}
          />
        )}

        {/* Repeat spell effect modal */}
        {actionMode === 'repeat-effect' && selectedTarget && (selectedTarget.concentrationSpell || selectedTarget.activeSpell) && (
          <RepeatEffectModal
            encounterId={encounter.id}
            caster={selectedTarget}
            participants={encounter.participants}
            spellName={(selectedTarget.concentrationSpell || selectedTarget.activeSpell)!}
            onUpdate={refreshEncounter}
            onClose={() => { setActionMode(null); setSelectedTargetId(null); }}
            isMonster={selectedTarget.participantType === 'MONSTER'}
          />
        )}

        {/* Action panel */}
        {actionMode !== 'spell' && (
          <ActionPanel
            encounterId={encounter.id}
            selectedTarget={selectedTarget}
            actionMode={actionMode}
            setActionMode={setActionMode}
            onUpdate={refreshEncounter}
          />
        )}

        {/* Participant list */}
        <div className="space-y-2 mb-4" role="list" aria-label="Initiative order">
          {sorted.map((p: EncounterParticipant) => {
            const conditions = parseConditions(p);
            const isSelected = p.id === selectedTargetId;
            const isMonster = p.participantType === 'MONSTER';

            return (
              <div
                key={p.id}
                className={`bg-card border border-l-[3px] px-4 py-3 transition-all ${
                  p.isCurrentTurn ? 'border-t-cls-fighter border-r-cls-fighter border-b-cls-fighter ring-1 ring-cls-fighter/30' :
                  isSelected ? 'border-t-ink border-r-ink border-b-ink ring-1 ring-ink/30' :
                  'border-t-rule border-r-rule border-b-rule'
                } ${!p.isAlive ? 'opacity-60' : ''}`}
                style={{ borderLeftColor: getParticipantColour(isMonster) }}
              >
                <div className="flex items-center gap-3">
                  {/* Turn indicator */}
                  <div className="w-5 flex-shrink-0">
                    {p.isCurrentTurn && <ChevronRight className="w-5 h-5 text-cls-fighter" />}
                  </div>

                  {/* Initiative */}
                  <div className="w-9 text-center flex-shrink-0">
                    <span className="font-heading text-[17px] font-bold text-ink">{p.initiative ?? '&#8212;'}</span>
                  </div>

                  {/* Name + info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className={`font-heading text-[13px] font-semibold tracking-[0.01em] text-ink ${!p.isAlive ? 'line-through' : ''}`}>{p.displayName}</span>
                      <span
                        className="font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5"
                        style={{ color: getParticipantColour(isMonster), backgroundColor: getParticipantBg(isMonster) }}
                      >
                        {p.participantType}
                      </span>
                      {!p.isAlive && <Skull className="w-4 h-4 text-debuff" />}
                    </div>

                    {/* Conditions */}
                    {conditions.length > 0 && (
                      <div className="flex gap-1 mt-1 flex-wrap">
                        {conditions.map(c => {
                          const remaining = c.duration != null ? Math.max(0, c.duration - (encounter.roundNumber - c.appliedRound)) : null;
                          return (
                            <button
                              key={c.name}
                              onClick={() => handleRemoveCondition(p.id, c.name)}
                              className="font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff hover:opacity-75 cursor-pointer"
                              title={`Click to remove ${c.name}${remaining != null ? ` (${remaining} rounds left)` : ''}`}
                            >
                              {c.name}{c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}{remaining != null ? ` (${remaining})` : ''} <X className="w-2.5 h-2.5 inline" />
                            </button>
                          );
                        })}
                      </div>
                    )}

                    {/* Concentration */}
                    {p.concentrationSpell && (
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <p className="text-cls-warlock font-body text-[13px] font-medium">
                          Concentrating: {p.concentrationSpell}
                        </p>
                        <button
                          onClick={(e) => { e.stopPropagation(); selectTarget(p.id, 'repeat-effect'); }}
                          className="p-0.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock"
                          title="Repeat spell effect"
                        >
                          <RotateCw className="w-3 h-3" />
                        </button>
                      </div>
                    )}

                    {/* Active spell (non-concentration persistent) */}
                    {p.activeSpell && (
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <p className="text-cls-monk font-body text-[13px] font-medium">
                          Active: {p.activeSpell}
                        </p>
                        <button
                          onClick={(e) => { e.stopPropagation(); selectTarget(p.id, 'repeat-effect'); }}
                          className="p-0.5 bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk"
                          title="Repeat spell effect"
                        >
                          <RotateCw className="w-3 h-3" />
                        </button>
                      </div>
                    )}

                    {/* Death saves for downed players */}
                    {!p.isAlive && p.participantType === 'PLAYER' && p.deathSaveFailures < 3 && (
                      <DeathSaves participant={p} encounterId={encounter.id} onUpdate={refreshEncounter} />
                    )}

                    {/* Spell slots */}
                    {p.isAlive && p.spellSlotsCurrent && (
                      <SpellSlotDisplay participant={p} encounterId={encounter.id} onUpdate={refreshEncounter} />
                    )}
                  </div>

                  {/* HP bar + values */}
                  <div className="w-36 flex-shrink-0">
                    <div className="flex items-center justify-end gap-1.5 mb-1">
                      <Heart className={`w-3.5 h-3.5 ${p.hpMax > 0 && p.hpCurrent / p.hpMax > 0.5 ? 'text-buff' : p.hpMax > 0 && p.hpCurrent / p.hpMax > 0.25 ? 'text-cls-monk' : 'text-debuff'}`} />
                      <span className="font-heading text-[11px] font-semibold text-ink">{p.hpCurrent}/{p.hpMax}</span>
                      {(p.hpTemp || 0) > 0 && <span className="text-cls-wizard font-heading text-[11px] font-semibold">+{p.hpTemp}</span>}
                    </div>
                    <HpBar participant={p} />
                  </div>

                  {/* AC */}
                  <div className="flex items-center gap-1 w-12 justify-end flex-shrink-0">
                    <Shield className="w-4 h-4 text-muted" />
                    <span className="text-muted font-heading text-[11px] font-semibold">{p.armourClass}</span>
                  </div>

                  {/* Quick action buttons */}
                  {(isActive || encounter.status === 'PAUSED') && (
                    <div className="flex items-center gap-1 flex-shrink-0 ml-2">
                      {(p.isAlive || (p.participantType === 'PLAYER' && p.deathSaveFailures < 3)) && (
                        <button
                          onClick={() => selectTarget(p.id, 'attack')}
                          className="p-1.5 bg-cls-fighter/10 hover:bg-cls-fighter/20 text-cls-fighter"
                          title="Attack roll"
                        >
                          <Crosshair className="w-3.5 h-3.5" />
                        </button>
                      )}
                      <button
                        onClick={() => selectTarget(p.id, 'damage')}
                        className="p-1.5 bg-debuff/10 hover:bg-debuff/20 text-debuff"
                        title="Deal damage"
                      >
                        <Minus className="w-3.5 h-3.5" />
                      </button>
                      {(p.isAlive || p.participantType === 'PLAYER') && (
                        <button
                          onClick={() => selectTarget(p.id, 'heal')}
                          className="p-1.5 bg-buff/10 hover:bg-buff/20 text-buff"
                          title={!p.isAlive && p.deathSaveFailures >= 3 ? "Resurrect" : "Heal"}
                        >
                          <Plus className="w-3.5 h-3.5" />
                        </button>
                      )}
                      <button
                        onClick={() => selectTarget(p.id, 'condition')}
                        className="p-1.5 bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk"
                        title="Add condition"
                      >
                        <Zap className="w-3.5 h-3.5" />
                      </button>
                      {p.isAlive && (
                        <button
                          onClick={() => selectTarget(p.id, 'concentration')}
                          className="p-1.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock"
                          title="Set concentration"
                        >
                          <Swords className="w-3.5 h-3.5" />
                        </button>
                      )}
                      {p.isAlive && (
                        <button
                          onClick={() => selectTarget(p.id, 'spell')}
                          className="p-1.5 bg-ink/10 hover:bg-ink/20 text-ink"
                          title="Cast spell"
                        >
                          <Sparkles className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        {/* Combat log */}
        <CombatLogPanel encounterId={encounter.id} />
      </main>
    </div>
  );
}

export default function EncounterSessionPage() {
  const { encounterId } = useParams<{ encounterId: string }>();
  if (!encounterId) return null;

  return (
    <EncounterProvider encounterId={encounterId}>
      <DmSessionView />
    </EncounterProvider>
  );
}
