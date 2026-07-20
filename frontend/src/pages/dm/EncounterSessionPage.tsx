import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider, useEncounter } from '../../context/EncounterContext';
import { encounterApi } from '../../api/encounterApi';
import { combatApi } from '../../api/combatApi';
import type { EncounterParticipant, ConditionEntry, SpellSlots } from '../../types/encounter';
import type { CombatLogEntry } from '../../types/combat';
import SpellCastModal from '../../components/encounter/SpellCastModal';
import {
  ArrowLeft, Pause, Play, Flag, Copy, Check, Wifi, WifiOff,
  ChevronRight, ChevronLeft, Heart, Shield, Skull, Swords,
  Plus, Minus, X, ScrollText, Zap, Crosshair, Sparkles
} from 'lucide-react';
import { useState, useEffect, useRef, type FormEvent } from 'react';

const CONDITION_COLORS: Record<string, string> = {
  blinded: 'bg-gray-700 text-gray-300',
  charmed: 'bg-pink-900/60 text-pink-300',
  deafened: 'bg-gray-700 text-gray-300',
  frightened: 'bg-yellow-900/60 text-yellow-300',
  grappled: 'bg-orange-900/60 text-orange-300',
  incapacitated: 'bg-red-900/60 text-red-300',
  invisible: 'bg-blue-900/60 text-blue-300',
  paralyzed: 'bg-red-900/60 text-red-300',
  petrified: 'bg-stone-800 text-stone-300',
  poisoned: 'bg-green-900/60 text-green-300',
  prone: 'bg-amber-900/60 text-amber-300',
  restrained: 'bg-orange-900/60 text-orange-300',
  stunned: 'bg-yellow-900/60 text-yellow-300',
  unconscious: 'bg-red-900/60 text-red-300',
};

const ALL_CONDITIONS = Object.keys(CONDITION_COLORS);

const DAMAGE_TYPES = [
  'bludgeoning', 'piercing', 'slashing', 'acid', 'cold', 'fire',
  'force', 'lightning', 'necrotic', 'poison', 'psychic', 'radiant', 'thunder',
];

type ActionMode = 'attack' | 'damage' | 'heal' | 'condition' | 'concentration' | 'spell' | null;

function HpBar({ participant }: { participant: EncounterParticipant }) {
  const pct = participant.hpMax > 0 ? (participant.hpCurrent / participant.hpMax) * 100 : 0;
  const tempPct = participant.hpMax > 0 ? ((participant.hpTemp || 0) / participant.hpMax) * 100 : 0;

  let barColor = 'bg-green-500';
  if (pct <= 25) barColor = 'bg-red-500';
  else if (pct <= 50) barColor = 'bg-yellow-500';

  return (
    <div className="w-full bg-gray-800 rounded-full h-2 overflow-hidden flex">
      <div className={`${barColor} h-full transition-all duration-300`} style={{ width: `${Math.min(pct, 100)}%` }} />
      {tempPct > 0 && (
        <div className="bg-cyan-500 h-full transition-all duration-300" style={{ width: `${Math.min(tempPct, 100 - pct)}%` }} />
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
        <span className="text-xs text-gray-400">Saves:</span>
        {[0, 1, 2].map(i => (
          <div key={`s${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveSuccesses ? 'bg-green-500 border-green-400' : 'border-gray-600'}`} />
        ))}
      </div>
      <div className="flex items-center gap-1">
        <span className="text-xs text-gray-400">Fails:</span>
        {[0, 1, 2].map(i => (
          <div key={`f${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveFailures ? 'bg-red-500 border-red-400' : 'border-gray-600'}`} />
        ))}
      </div>
      <button
        onClick={handleRoll}
        disabled={rolling}
        className="px-2 py-0.5 text-xs bg-gray-700 hover:bg-gray-600 text-gray-300 rounded disabled:opacity-50"
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
      <Sparkles className="w-3 h-3 text-indigo-400" />
      {levels.map(lvl => {
        const s = slots[lvl];
        return (
          <div key={lvl} className="flex items-center gap-0.5">
            <span className="text-xs text-gray-500">{lvl}:</span>
            <div className="flex gap-0.5">
              {Array.from({ length: s.max }).map((_, i) => (
                <button
                  key={i}
                  onClick={() => i < s.remaining ? handleUse(parseInt(lvl)) : handleRestore(parseInt(lvl))}
                  className={`w-2.5 h-2.5 rounded-full border transition-colors ${
                    i < s.remaining
                      ? 'bg-indigo-500 border-indigo-400 hover:bg-indigo-700'
                      : 'border-gray-600 hover:border-indigo-500'
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
    <div className="bg-gray-900 border border-gray-700 rounded-lg p-4 mb-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-gray-300">
          {actionMode === 'attack' && <><Crosshair className="w-4 h-4 inline mr-1 text-orange-400" />Attack {selectedTarget.displayName} (AC {selectedTarget.armourClass})</>}
          {actionMode === 'damage' && <><Swords className="w-4 h-4 inline mr-1 text-red-400" />Damage {selectedTarget.displayName}</>}
          {actionMode === 'heal' && <><Heart className="w-4 h-4 inline mr-1 text-green-400" />Heal {selectedTarget.displayName}</>}
          {actionMode === 'condition' && <><Zap className="w-4 h-4 inline mr-1 text-yellow-400" />Add Condition to {selectedTarget.displayName}</>}
          {actionMode === 'concentration' && <><Zap className="w-4 h-4 inline mr-1 text-purple-400" />Concentration for {selectedTarget.displayName}</>}
        </h3>
        <button onClick={() => setActionMode(null)} className="text-gray-500 hover:text-gray-300">
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
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">Attack +</label>}
                    <input
                      type="number"
                      value={atk.attackBonus}
                      onChange={e => updateAttack(i, 'attackBonus', e.target.value)}
                      placeholder="+5"
                      className="w-full px-2 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                      autoFocus={i === 0}
                    />
                  </div>
                  <div className="w-24">
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">Damage</label>}
                    <input
                      type="text"
                      value={atk.damageDice}
                      onChange={e => updateAttack(i, 'damageDice', e.target.value)}
                      placeholder="1d8+3"
                      className="w-full px-2 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    />
                  </div>
                  <div className="w-28">
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">Type</label>}
                    <select
                      value={atk.damageType}
                      onChange={e => updateAttack(i, 'damageType', e.target.value)}
                      className="w-full h-[38px] px-2 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    >
                      <option value="">—</option>
                      {DAMAGE_TYPES.map(dt => (
                        <option key={dt} value={dt}>{dt}</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">Roll</label>}
                    <div className="flex gap-1">
                      <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === false ? null : false)}
                        className={`px-2 py-2 rounded text-xs font-medium ${atk.advantage === false ? 'bg-red-700 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>
                        Dis
                      </button>
                      <button type="button" onClick={() => updateAttack(i, 'advantage', null)}
                        className={`px-2 py-2 rounded text-xs font-medium ${atk.advantage === null ? 'bg-gray-600 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>
                        Norm
                      </button>
                      <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === true ? null : true)}
                        className={`px-2 py-2 rounded text-xs font-medium ${atk.advantage === true ? 'bg-green-700 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>
                        Adv
                      </button>
                    </div>
                  </div>
                  <div>
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">&nbsp;</label>}
                    <button type="button" onClick={() => updateAttack(i, 'forceCrit', !atk.forceCrit)}
                      className={`px-2 py-2 rounded text-xs font-medium ${atk.forceCrit ? 'bg-yellow-600 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>
                      Crit
                    </button>
                    <button type="button" onClick={() => updateAttack(i, 'isRanged', !atk.isRanged)}
                      className={`px-2 py-2 rounded text-xs font-medium ${atk.isRanged ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>
                      Ranged
                    </button>
                  </div>
                  <div className="flex gap-1">
                    {attacks.length < 5 && (
                      <button type="button" onClick={() => cloneAttackRow(i)}
                        className="p-2 text-gray-500 hover:text-green-400" title="Clone this attack">
                        <Copy className="w-4 h-4" />
                      </button>
                    )}
                    {attacks.length > 1 && (
                      <button type="button" onClick={() => removeAttackRow(i)}
                        className="p-2 text-gray-500 hover:text-red-400" title="Remove this attack">
                        <X className="w-4 h-4" />
                      </button>
                    )}
                  </div>
                </div>
              ))}
              {attacks.length < 5 && (
                <button type="button" onClick={addAttackRow}
                  className="flex items-center gap-1 text-xs text-gray-400 hover:text-indigo-400 py-1">
                  <Plus className="w-3 h-3" /> Add attack
                </button>
              )}
            </div>
            <div className="flex items-end">
              <button
                type="submit"
                disabled={loading}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:bg-indigo-800 text-white text-sm rounded-lg whitespace-nowrap"
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
                  <label className="block text-xs text-gray-400 mb-1">Amount</label>
                  <input
                    type="number"
                    min="0"
                    value={amount}
                    onChange={e => setAmount(e.target.value)}
                    className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    autoFocus
                  />
                </div>
                {actionMode === 'damage' && (
                  <div className="flex-1">
                    <label className="block text-xs text-gray-400 mb-1">Type</label>
                    <select
                      value={damageType}
                      onChange={e => setDamageType(e.target.value)}
                      className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    >
                      <option value="">—</option>
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
                  <label className="block text-xs text-gray-400 mb-1">Condition</label>
                  <select
                    value={condition}
                    onChange={e => setCondition(e.target.value)}
                    className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    autoFocus
                  >
                    <option value="">Select condition...</option>
                    {ALL_CONDITIONS.map(c => (
                      <option key={c} value={c}>{c}</option>
                    ))}
                  </select>
                </div>
                <div className="w-28">
                  <label className="block text-xs text-gray-400 mb-1">Duration (rounds)</label>
                  <input
                    type="number"
                    min="1"
                    value={conditionDuration}
                    onChange={e => setConditionDuration(e.target.value)}
                    placeholder="∞"
                    className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </>
            )}

            {actionMode === 'concentration' && (
              <div className="flex-1">
                <label className="block text-xs text-gray-400 mb-1">Spell Name (blank to clear)</label>
                <input
                  type="text"
                  value={spellName}
                  onChange={e => setSpellName(e.target.value)}
                  placeholder="e.g. Bless, Hold Person"
                  className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  autoFocus
                />
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:bg-indigo-800 text-white text-sm rounded-lg"
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
      case 'ATTACK': return 'text-orange-400';
      case 'DAMAGE': case 'KILL': return 'text-red-400';
      case 'HEAL': case 'REVIVE': return 'text-green-400';
      case 'CONDITION_ADD': return 'text-yellow-400';
      case 'CONDITION_REMOVE': return 'text-blue-400';
      case 'DEATH_SAVE': return 'text-orange-400';
      case 'STABILIZE': return 'text-green-300';
      case 'CONCENTRATION_CHECK': return 'text-purple-400';
      case 'CONCENTRATION_LOST': return 'text-purple-300';
      case 'SPELL_CAST': return 'text-indigo-400';
      case 'SPELL_SLOT_USE': return 'text-indigo-400';
      case 'SPELL_SLOT_RESTORE': return 'text-indigo-300';
      case 'TURN_ADVANCE': case 'TURN_BACK': return 'text-gray-500';
      default: return 'text-gray-400';
    }
  }

  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 hover:bg-gray-800/50"
      >
        <div className="flex items-center gap-2">
          <ScrollText className="w-4 h-4 text-gray-400" />
          <span className="text-sm font-semibold text-gray-300">Combat Log</span>
          <span className="text-xs text-gray-500">({logs.length} entries)</span>
        </div>
        <ChevronRight className={`w-4 h-4 text-gray-500 transition-transform ${expanded ? 'rotate-90' : ''}`} />
      </button>
      {expanded && (
        <div className="relative">
          <div ref={scrollRef} onScroll={handleScroll} aria-live="polite" aria-label="Combat log entries" className="max-h-64 overflow-y-auto border-t border-gray-800 px-4 py-2 space-y-1">
            {logs.length === 0 ? (
              <p className="text-gray-500 text-xs py-2">No actions yet</p>
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
                      <div className="text-xs font-semibold text-indigo-400 border-b border-gray-800 pb-1 pt-2 mb-1">
                        Round {log.roundNumber}
                      </div>
                    )}
                    {showTurnHeader && (
                      <div className="text-xs text-gray-500 font-medium py-0.5 pl-2 border-l-2 border-gray-700 my-1">
                        Turn: {turnName}
                      </div>
                    )}
                    {!isTurnChange && (
                      <div className="flex items-start gap-2 text-xs pl-2">
                        <span className={getLogColor(log.actionType)}>{log.description}</span>
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
              className="absolute bottom-2 left-1/2 -translate-x-1/2 flex items-center gap-1 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white text-xs rounded-full shadow-lg"
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
    return <div className="min-h-screen bg-gray-950 flex items-center justify-center"><p className="text-gray-400">Loading encounter...</p></div>;
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
    if (mode === 'attack') {
      const target = encounter.participants.find(p => p.id === participantId);
      const isDowned = target && !target.isAlive && target.participantType === 'PLAYER';
      setAttacks([{ attackBonus: '', damageDice: '', damageType: '', advantage: isDowned ? true : null, forceCrit: false, isRanged: false }]);
    }
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
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-3">
        <div className="flex items-center justify-between">
          <button onClick={() => navigate('/dm/encounters')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors text-sm">
            <ArrowLeft className="w-4 h-4" /> Builder
          </button>
          <div className="flex items-center gap-4">
            {encounter.sessionCode && (
              <div className="flex items-center gap-2 bg-gray-900 border border-gray-800 px-3 py-1.5 rounded-lg">
                <span className="text-gray-400 text-xs">Code:</span>
                <span className="text-white font-mono font-bold tracking-wider">{encounter.sessionCode}</span>
                <button onClick={copySessionCode} className="text-gray-400 hover:text-white">
                  {copiedCode ? <Check className="w-3.5 h-3.5 text-green-400" /> : <Copy className="w-3.5 h-3.5" />}
                </button>
              </div>
            )}
            <div className="flex items-center gap-1.5">
              {isConnected ? <Wifi className="w-4 h-4 text-green-400" /> : <WifiOff className="w-4 h-4 text-red-400" />}
              <span className="text-xs text-gray-400">{isConnected ? 'Live' : 'Disconnected'}</span>
            </div>
          </div>
        </div>
      </header>

      <main className="px-6 py-4">
        {error && <p className="text-red-400 text-sm mb-4">{error}</p>}

        {/* Encounter info + controls bar */}
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="text-xl font-bold text-white">{encounter.name}</h1>
            <p className="text-gray-400 text-sm">
              Round {encounter.roundNumber} &middot; {encounter.participants.filter(p => p.isAlive).length}/{encounter.participants.length} alive
            </p>
          </div>
          <div className="flex items-center gap-2">
            {isActive && (
              <>
                <button onClick={handlePrevTurn} className="flex items-center gap-1 px-3 py-2 bg-gray-800 hover:bg-gray-700 text-gray-300 rounded-lg text-sm" title="Previous turn">
                  <ChevronLeft className="w-4 h-4" />
                </button>
                <button onClick={handleNextTurn} className="flex items-center gap-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-sm font-medium">
                  Next Turn <ChevronRight className="w-4 h-4" />
                </button>
              </>
            )}
            {encounter.status === 'ACTIVE' && (
              <button onClick={handlePause} className="flex items-center gap-1.5 px-3 py-2 bg-yellow-600 hover:bg-yellow-500 text-white rounded-lg text-sm">
                <Pause className="w-4 h-4" /> Pause
              </button>
            )}
            {encounter.status === 'PAUSED' && (
              <button onClick={handleResume} className="flex items-center gap-1.5 px-3 py-2 bg-green-600 hover:bg-green-500 text-white rounded-lg text-sm">
                <Play className="w-4 h-4" /> Resume
              </button>
            )}
            {(encounter.status === 'ACTIVE' || encounter.status === 'PAUSED') && (
              <button onClick={handleEnd} className="flex items-center gap-1.5 px-3 py-2 bg-red-700 hover:bg-red-600 text-white rounded-lg text-sm">
                <Flag className="w-4 h-4" /> End
              </button>
            )}
            {encounter.status === 'COMPLETED' && (
              <span className="px-3 py-2 bg-gray-800 text-gray-400 rounded-lg text-sm">Complete</span>
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

            return (
              <div
                key={p.id}
                className={`bg-gray-900 border rounded-lg px-4 py-3 transition-all ${
                  p.isCurrentTurn ? 'border-orange-500 ring-1 ring-orange-500/30' :
                  isSelected ? 'border-indigo-500 ring-1 ring-indigo-500/30' :
                  'border-gray-800'
                } ${!p.isAlive ? 'opacity-60' : ''}`}
              >
                <div className="flex items-center gap-3">
                  {/* Turn indicator */}
                  <div className="w-5 flex-shrink-0">
                    {p.isCurrentTurn && <ChevronRight className="w-5 h-5 text-orange-400" />}
                  </div>

                  {/* Initiative */}
                  <div className="w-9 text-center flex-shrink-0">
                    <span className="text-white font-bold text-lg">{p.initiative ?? '—'}</span>
                  </div>

                  {/* Name + info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className={`text-white font-semibold ${!p.isAlive ? 'line-through' : ''}`}>{p.displayName}</span>
                      <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${
                        p.participantType === 'PLAYER' ? 'bg-blue-900/50 text-blue-400' : 'bg-red-900/50 text-red-400'
                      }`}>
                        {p.participantType}
                      </span>
                      {!p.isAlive && <Skull className="w-4 h-4 text-red-400" />}
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
                              className={`px-1.5 py-0.5 rounded text-xs ${CONDITION_COLORS[c.name] || 'bg-gray-700 text-gray-300'} hover:opacity-75 cursor-pointer`}
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
                      <p className="text-purple-400 text-xs mt-0.5">
                        Concentrating: {p.concentrationSpell}
                      </p>
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
                      <Heart className={`w-3.5 h-3.5 ${p.hpMax > 0 && p.hpCurrent / p.hpMax > 0.5 ? 'text-green-400' : p.hpMax > 0 && p.hpCurrent / p.hpMax > 0.25 ? 'text-yellow-400' : 'text-red-400'}`} />
                      <span className="font-mono text-sm text-white">{p.hpCurrent}/{p.hpMax}</span>
                      {(p.hpTemp || 0) > 0 && <span className="text-cyan-400 text-xs font-mono">+{p.hpTemp}</span>}
                    </div>
                    <HpBar participant={p} />
                  </div>

                  {/* AC */}
                  <div className="flex items-center gap-1 w-12 justify-end flex-shrink-0">
                    <Shield className="w-4 h-4 text-gray-400" />
                    <span className="text-gray-300 font-mono text-sm">{p.armourClass}</span>
                  </div>

                  {/* Quick action buttons */}
                  {(isActive || encounter.status === 'PAUSED') && (
                    <div className="flex items-center gap-1 flex-shrink-0 ml-2">
                      {(p.isAlive || (p.participantType === 'PLAYER' && p.deathSaveFailures < 3)) && (
                        <button
                          onClick={() => selectTarget(p.id, 'attack')}
                          className="p-1.5 bg-orange-900/30 hover:bg-orange-900/60 text-orange-400 rounded"
                          title="Attack roll"
                        >
                          <Crosshair className="w-3.5 h-3.5" />
                        </button>
                      )}
                      <button
                        onClick={() => selectTarget(p.id, 'damage')}
                        className="p-1.5 bg-red-900/30 hover:bg-red-900/60 text-red-400 rounded"
                        title="Deal damage"
                      >
                        <Minus className="w-3.5 h-3.5" />
                      </button>
                      {(p.isAlive || p.participantType === 'PLAYER') && (
                        <button
                          onClick={() => selectTarget(p.id, 'heal')}
                          className="p-1.5 bg-green-900/30 hover:bg-green-900/60 text-green-400 rounded"
                          title={!p.isAlive && p.deathSaveFailures >= 3 ? "Resurrect" : "Heal"}
                        >
                          <Plus className="w-3.5 h-3.5" />
                        </button>
                      )}
                      <button
                        onClick={() => selectTarget(p.id, 'condition')}
                        className="p-1.5 bg-yellow-900/30 hover:bg-yellow-900/60 text-yellow-400 rounded"
                        title="Add condition"
                      >
                        <Zap className="w-3.5 h-3.5" />
                      </button>
                      {p.isAlive && (
                        <button
                          onClick={() => selectTarget(p.id, 'concentration')}
                          className="p-1.5 bg-purple-900/30 hover:bg-purple-900/60 text-purple-400 rounded"
                          title="Set concentration"
                        >
                          <Swords className="w-3.5 h-3.5" />
                        </button>
                      )}
                      {p.isAlive && (
                        <button
                          onClick={() => selectTarget(p.id, 'spell')}
                          className="p-1.5 bg-indigo-900/30 hover:bg-indigo-900/60 text-indigo-400 rounded"
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
