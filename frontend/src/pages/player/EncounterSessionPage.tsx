import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider, useEncounter } from '../../context/EncounterContext';
import { useAuth } from '../../context/AuthContext';
import { combatApi } from '../../api/combatApi';
import type { EncounterParticipant, ConditionEntry, SpellSlots } from '../../types/encounter';
import type { CombatLogEntry } from '../../types/combat';
import SpellCastModal from '../../components/encounter/SpellCastModal';
import RepeatEffectModal from '../../components/encounter/RepeatEffectModal';
import {
  ArrowLeft, Wifi, WifiOff, Heart, Shield, Skull, Copy,
  ChevronRight, ChevronLeft, ScrollText, Sparkles, Crosshair, Zap, Swords, X, Plus, RotateCw
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

type PlayerAction = 'attack' | 'condition' | 'concentration' | 'spell' | 'repeat-effect' | null;

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
    <div className="flex items-center gap-3 mt-2">
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
        {rolling ? '...' : 'Roll Death Save'}
      </button>
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

function PlayerSpellSlots({ participant, encounterId, onUpdate }: { participant: EncounterParticipant; encounterId: string; onUpdate: () => void }) {
  const slots = parseSpellSlots(participant);
  if (!slots) return null;

  const levels = Object.keys(slots).sort((a, b) => parseInt(a) - parseInt(b));
  if (levels.length === 0) return null;

  async function handleUse(level: number) {
    await combatApi.useSpellSlot(encounterId, participant.id, level);
    onUpdate();
  }

  return (
    <div className="bg-gray-900 border border-indigo-900/50 rounded-lg p-4 mb-4">
      <div className="flex items-center gap-2 mb-2">
        <Sparkles className="w-4 h-4 text-indigo-400" />
        <span className="text-sm font-semibold text-gray-300">Spell Slots</span>
      </div>
      <div className="flex items-center gap-4 flex-wrap">
        {levels.map(lvl => {
          const s = slots[lvl];
          return (
            <div key={lvl} className="flex items-center gap-1">
              <span className="text-xs text-gray-400 w-6">Lv{lvl}</span>
              <div className="flex gap-1">
                {Array.from({ length: s.max }).map((_, i) => (
                  <button
                    key={i}
                    onClick={() => i < s.remaining ? handleUse(parseInt(lvl)) : undefined}
                    disabled={i >= s.remaining}
                    className={`w-3.5 h-3.5 rounded-full border transition-colors ${
                      i < s.remaining
                        ? 'bg-indigo-500 border-indigo-400 hover:bg-indigo-700 cursor-pointer'
                        : 'border-gray-600 cursor-default'
                    }`}
                    title={i < s.remaining ? `Use level ${lvl} slot` : `Used`}
                  />
                ))}
              </div>
              <span className="text-xs text-gray-500">{s.remaining}/{s.max}</span>
            </div>
          );
        })}
      </div>
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
  const isAtBottomRef = useRef(true);

  useEffect(() => {
    combatApi.getCombatLog(encounterId).then(res => setLogs(res.data));
    const interval = setInterval(() => {
      combatApi.getCombatLog(encounterId).then(res => setLogs(res.data));
    }, 3000);
    return () => clearInterval(interval);
  }, [encounterId]);

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
      case 'DEATH_SAVE': return 'text-orange-400';
      case 'STABILIZE': return 'text-green-300';
      case 'CONCENTRATION_CHECK': return 'text-purple-400';
      case 'CONCENTRATION_LOST': return 'text-purple-300';
      case 'SPELL_CAST': return 'text-indigo-400';
      case 'SPELL_SLOT_USE': return 'text-indigo-400';
      case 'SPELL_SLOT_RESTORE': return 'text-indigo-300';
      case 'CONDITION_ADD': return 'text-yellow-400';
      case 'CONDITION_REMOVE': return 'text-blue-400';
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
          <div ref={scrollRef} onScroll={handleScroll} aria-live="polite" aria-label="Combat log entries" className="max-h-48 overflow-y-auto border-t border-gray-800 px-4 py-2 space-y-1">
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
                const showTurnHeader = turnName && (!prevLog || prevTurnName !== turnName || showRoundHeader);

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

function PlayerActionPanel({ myCharacter, encounter, onUpdate, targetId, actionMode, onClose }: {
  myCharacter: EncounterParticipant;
  encounter: { id: string; roundNumber: number; participants: EncounterParticipant[] };
  onUpdate: () => void;
  targetId: string | null;
  actionMode: PlayerAction;
  onClose: () => void;
}) {
  const [condition, setCondition] = useState('');
  const [conditionDuration, setConditionDuration] = useState('');
  const [spellName, setSpellName] = useState('');
  const [attacks, setAttacks] = useState([{ attackBonus: '', damageDice: '', damageType: '', advantage: null as boolean | null, forceCrit: false, isRanged: false }]);
  const [loading, setLoading] = useState(false);

  const target = encounter.participants.find(p => p.id === targetId);
  if (!target || !actionMode) return null;

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
    if (!targetId || loading) return;
    setLoading(true);
    try {
      if (actionMode === 'attack') {
        for (const atk of attacks) {
          if (atk.attackBonus !== '' && atk.damageDice) {
            await combatApi.rollAttack(encounter.id, targetId, parseInt(atk.attackBonus), atk.damageDice, atk.damageType || undefined, atk.advantage, atk.forceCrit || undefined, myCharacter.id, atk.isRanged || undefined);
          }
        }
      } else if (actionMode === 'condition' && condition) {
        await combatApi.addCondition(encounter.id, targetId, condition, conditionDuration ? parseInt(conditionDuration) : undefined);
      } else if (actionMode === 'concentration') {
        await combatApi.setConcentration(encounter.id, targetId, spellName || null);
      }
      onUpdate();
      onClose();
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="bg-gray-900 border border-indigo-500/50 rounded-lg p-4 mb-4">
      <div className="flex items-center justify-between mb-3">
        <div className="text-sm font-semibold text-gray-200">
          {actionMode === 'attack' && <><Crosshair className="w-4 h-4 inline mr-1 text-orange-400" />Attack {target.displayName} (AC {target.armourClass})</>}
          {actionMode === 'condition' && <><Zap className="w-4 h-4 inline mr-1 text-yellow-400" />Add Condition</>}
          {actionMode === 'concentration' && <><Swords className="w-4 h-4 inline mr-1 text-purple-400" />Set Concentration</>}
        </div>
        <button onClick={onClose} className="text-gray-500 hover:text-white"><X className="w-4 h-4" /></button>
      </div>

      <form onSubmit={handleSubmit}>
        {actionMode === 'attack' && (
          <div className="flex gap-3">
            <div className="flex-1 space-y-2">
              {attacks.map((atk, i) => (
                <div key={i} className="flex items-end gap-2">
                  <div className="w-20">
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">Attack +</label>}
                    <input type="number" value={atk.attackBonus} onChange={e => updateAttack(i, 'attackBonus', e.target.value)}
                      placeholder="+5" className="w-full px-2 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" autoFocus={i === 0} />
                  </div>
                  <div className="w-24">
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">Damage</label>}
                    <input type="text" value={atk.damageDice} onChange={e => updateAttack(i, 'damageDice', e.target.value)}
                      placeholder="1d8+3" className="w-full px-2 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
                  </div>
                  <div className="w-28">
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">Type</label>}
                    <select value={atk.damageType} onChange={e => updateAttack(i, 'damageType', e.target.value)}
                      className="w-full h-[38px] px-2 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500">
                      <option value="">—</option>
                      {DAMAGE_TYPES.map(dt => <option key={dt} value={dt}>{dt}</option>)}
                    </select>
                  </div>
                  <div>
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">Roll</label>}
                    <div className="flex gap-1">
                      <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === false ? null : false)}
                        className={`px-2 py-2 rounded text-xs font-medium ${atk.advantage === false ? 'bg-red-700 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>Dis</button>
                      <button type="button" onClick={() => updateAttack(i, 'advantage', null)}
                        className={`px-2 py-2 rounded text-xs font-medium ${atk.advantage === null ? 'bg-gray-600 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>Norm</button>
                      <button type="button" onClick={() => updateAttack(i, 'advantage', atk.advantage === true ? null : true)}
                        className={`px-2 py-2 rounded text-xs font-medium ${atk.advantage === true ? 'bg-green-700 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>Adv</button>
                    </div>
                  </div>
                  <div>
                    {i === 0 && <label className="block text-xs text-gray-400 mb-1">&nbsp;</label>}
                    <button type="button" onClick={() => updateAttack(i, 'forceCrit', !atk.forceCrit)}
                      className={`px-2 py-2 rounded text-xs font-medium ${atk.forceCrit ? 'bg-yellow-600 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>Crit</button>
                    <button type="button" onClick={() => updateAttack(i, 'isRanged', !atk.isRanged)}
                      className={`px-2 py-2 rounded text-xs font-medium ${atk.isRanged ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 border border-gray-700'}`}>Ranged</button>
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
                <button type="button" onClick={addAttackRow} className="flex items-center gap-1 text-xs text-gray-400 hover:text-indigo-400 py-1">
                  <Plus className="w-3 h-3" /> Add attack
                </button>
              )}
            </div>
            <div className="flex items-end">
              <button type="submit" disabled={loading}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:bg-indigo-800 text-white text-sm rounded-lg whitespace-nowrap">
                {loading ? '...' : `Roll ${attacks.length > 1 ? `${attacks.length} ` : ''}Attack${attacks.length > 1 ? 's' : ''}`}
              </button>
            </div>
          </div>
        )}

        {actionMode === 'condition' && (
          <div className="flex items-end gap-3">
            <div className="flex-1">
              <label className="block text-xs text-gray-400 mb-1">Condition</label>
              <select value={condition} onChange={e => setCondition(e.target.value)}
                className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500">
                <option value="">Select...</option>
                {ALL_CONDITIONS.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            <div className="w-24">
              <label className="block text-xs text-gray-400 mb-1">Duration</label>
              <input type="number" min="1" value={conditionDuration} onChange={e => setConditionDuration(e.target.value)}
                placeholder="∞" className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
            </div>
            <button type="submit" disabled={loading || !condition}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:bg-indigo-800 text-white text-sm rounded-lg">
              {loading ? '...' : 'Apply'}
            </button>
          </div>
        )}

        {actionMode === 'concentration' && (
          <div className="flex items-end gap-3">
            <div className="flex-1">
              <label className="block text-xs text-gray-400 mb-1">Spell Name</label>
              <input type="text" value={spellName} onChange={e => setSpellName(e.target.value)}
                placeholder="Leave empty to clear" className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" autoFocus />
            </div>
            <button type="submit" disabled={loading}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:bg-indigo-800 text-white text-sm rounded-lg">
              {loading ? '...' : spellName ? 'Set' : 'Clear'}
            </button>
          </div>
        )}
      </form>
    </div>
  );
}

function PlayerSessionView() {
  const { encounter, isConnected, error, refreshEncounter } = useEncounter();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [actionMode, setActionMode] = useState<PlayerAction>(null);
  const [attackTargetId, setAttackTargetId] = useState<string | null>(null);

  if (!encounter) {
    return <div className="min-h-screen bg-gray-950 flex items-center justify-center"><p className="text-gray-400">Loading encounter...</p></div>;
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
  const visibleParticipants = sorted.filter(p => p.isVisibleToPlayers || p.controlledByUserId === user?.userId);
  const isMyTurn = encounter.participants.some(p => p.isCurrentTurn && p.controlledByUserId === user?.userId);
  const myCharacter = encounter.participants.find(p => p.controlledByUserId === user?.userId);

  function selectAttackTarget(targetId: string) {
    setAttackTargetId(targetId);
    setActionMode('attack');
  }

  function selectSelfAction(mode: PlayerAction) {
    if (!myCharacter) return;
    setAttackTargetId(myCharacter.id);
    setActionMode(mode);
  }

  async function removeCondition(condition: string) {
    if (!myCharacter) return;
    await combatApi.removeCondition(encounter.id, myCharacter.id, condition);
    refreshEncounter();
  }

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-3">
        <div className="flex items-center justify-between max-w-4xl mx-auto">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors text-sm">
            <ArrowLeft className="w-4 h-4" /> Dashboard
          </button>
          <div className="flex items-center gap-3">
            {encounter.status === 'COMPLETED' && (
              <span className="px-2.5 py-1 bg-gray-800 text-gray-400 rounded-lg text-xs">Complete</span>
            )}
            {encounter.status === 'PAUSED' && (
              <span className="px-2.5 py-1 bg-yellow-900/50 text-yellow-400 rounded-lg text-xs">Paused</span>
            )}
            <div className="flex items-center gap-1.5">
              {isConnected ? <Wifi className="w-4 h-4 text-green-400" /> : <WifiOff className="w-4 h-4 text-red-400" />}
              <span className="text-xs text-gray-400">{isConnected ? 'Live' : 'Disconnected'}</span>
            </div>
          </div>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-4">
        {error && <p className="text-red-400 text-sm mb-4">{error}</p>}

        <div className="mb-4">
          <h1 className="text-xl font-bold text-white">{encounter.name}</h1>
          <p className="text-gray-400 text-sm">Round {encounter.roundNumber}</p>
          {isMyTurn && (
            <div className="mt-2 px-4 py-2.5 bg-orange-900/30 border border-orange-500/50 rounded-lg">
              <p className="text-orange-300 font-semibold text-sm">It's your turn!</p>
            </div>
          )}
        </div>

        {/* Own character death saves */}
        {myCharacter && !myCharacter.isAlive && myCharacter.deathSaveFailures < 3 && (
          <div className="bg-red-950/30 border border-red-900/50 rounded-lg p-4 mb-4">
            <p className="text-red-300 font-semibold text-sm mb-1">You are dying!</p>
            <DeathSaves participant={myCharacter} encounterId={encounter.id} onUpdate={refreshEncounter} />
          </div>
        )}

        {/* Own character spell slots */}
        {myCharacter && myCharacter.isAlive && myCharacter.spellSlotsCurrent && (
          <PlayerSpellSlots participant={myCharacter} encounterId={encounter.id} onUpdate={refreshEncounter} />
        )}

        {/* Player self-action buttons */}
        {myCharacter && myCharacter.isAlive && (
          <div className="flex items-center gap-2 mb-4">
            <button onClick={() => selectSelfAction('condition')}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-yellow-900/30 hover:bg-yellow-900/60 text-yellow-400 rounded-lg text-xs font-medium border border-yellow-900/50">
              <Zap className="w-3.5 h-3.5" /> Add Condition
            </button>
            <button onClick={() => selectSelfAction('concentration')}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-purple-900/30 hover:bg-purple-900/60 text-purple-400 rounded-lg text-xs font-medium border border-purple-900/50">
              <Swords className="w-3.5 h-3.5" /> {myCharacter.concentrationSpell ? 'Change' : 'Set'} Concentration
            </button>
            {myCharacter.spellsKnown && (
              <button onClick={() => selectSelfAction('spell')}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-900/30 hover:bg-indigo-900/60 text-indigo-400 rounded-lg text-xs font-medium border border-indigo-900/50">
                <Sparkles className="w-3.5 h-3.5" /> Cast Spell
              </button>
            )}
            {(myCharacter.concentrationSpell || myCharacter.activeSpell) && (
              <button onClick={() => selectSelfAction('repeat-effect')}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-purple-900/30 hover:bg-purple-900/60 text-purple-400 rounded-lg text-xs font-medium border border-purple-900/50">
                <RotateCw className="w-3.5 h-3.5" /> Repeat Effect
              </button>
            )}
          </div>
        )}

        {/* Spell cast modal */}
        {actionMode === 'spell' && myCharacter && (
          <SpellCastModal
            encounterId={encounter.id}
            caster={myCharacter}
            participants={encounter.participants}
            onUpdate={refreshEncounter}
            onClose={() => { setActionMode(null); setAttackTargetId(null); }}
          />
        )}

        {/* Repeat spell effect modal */}
        {actionMode === 'repeat-effect' && myCharacter && (myCharacter.concentrationSpell || myCharacter.activeSpell) && (
          <RepeatEffectModal
            encounterId={encounter.id}
            caster={myCharacter}
            participants={encounter.participants}
            spellName={(myCharacter.concentrationSpell || myCharacter.activeSpell)!}
            onUpdate={refreshEncounter}
            onClose={() => { setActionMode(null); setAttackTargetId(null); }}
          />
        )}

        {/* Action panel */}
        {actionMode && actionMode !== 'spell' && myCharacter && (
          <PlayerActionPanel
            myCharacter={myCharacter}
            encounter={encounter}
            onUpdate={refreshEncounter}
            targetId={attackTargetId}
            actionMode={actionMode}
            onClose={() => { setActionMode(null); setAttackTargetId(null); }}
          />
        )}

        {/* Initiative list */}
        <div className="space-y-2 mb-4">
          {visibleParticipants.map((p: EncounterParticipant) => {
            const isOwn = p.controlledByUserId === user?.userId;
            const conditions = parseConditions(p);

            return (
              <div
                key={p.id}
                className={`bg-gray-900 border rounded-lg px-4 py-3 transition-all ${
                  p.isCurrentTurn
                    ? 'border-orange-500 ring-1 ring-orange-500/30'
                    : isOwn
                      ? 'border-indigo-500/50'
                      : 'border-gray-800'
                } ${!p.isAlive ? 'opacity-60' : ''}`}
              >
                <div className="flex items-center gap-3">
                  <div className="w-5 flex-shrink-0">
                    {p.isCurrentTurn && <ChevronRight className="w-5 h-5 text-orange-400" />}
                  </div>

                  <div className="w-9 text-center flex-shrink-0">
                    <span className="text-white font-bold text-lg">{p.initiative ?? '—'}</span>
                  </div>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className={`text-white font-semibold ${!p.isAlive ? 'line-through' : ''}`}>
                        {p.displayName}
                      </span>
                      {isOwn && <span className="px-1.5 py-0.5 rounded text-xs bg-indigo-900/50 text-indigo-400">You</span>}
                      {!p.isAlive && <Skull className="w-4 h-4 text-red-400" />}
                    </div>
                    {conditions.length > 0 && (
                      <div className="flex gap-1 mt-1 flex-wrap">
                        {conditions.map(c => {
                          const remaining = c.duration != null ? Math.max(0, c.duration - (encounter.roundNumber - c.appliedRound)) : null;
                          return isOwn ? (
                            <button key={c.name} onClick={() => removeCondition(c.name)}
                              className={`px-1.5 py-0.5 rounded text-xs hover:opacity-70 ${CONDITION_COLORS[c.name] || 'bg-gray-700 text-gray-300'}`}
                              title="Click to remove">
                              {c.name}{c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}{remaining != null ? ` (${remaining})` : ''} ×
                            </button>
                          ) : (
                            <span key={c.name} className={`px-1.5 py-0.5 rounded text-xs ${CONDITION_COLORS[c.name] || 'bg-gray-700 text-gray-300'}`}>
                              {c.name}{c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}{remaining != null ? ` (${remaining})` : ''}
                            </span>
                          );
                        })}
                      </div>
                    )}
                    {p.concentrationSpell && (
                      <p className="text-purple-400 text-xs mt-0.5">Concentrating: {p.concentrationSpell}</p>
                    )}
                    {p.activeSpell && (
                      <p className="text-amber-400 text-xs mt-0.5">Active: {p.activeSpell}</p>
                    )}
                  </div>

                  {isOwn ? (
                    <>
                      <div className="w-28 flex-shrink-0">
                        <div className="flex items-center justify-end gap-1.5 mb-1">
                          <Heart className={`w-3.5 h-3.5 ${p.hpMax > 0 && p.hpCurrent / p.hpMax > 0.5 ? 'text-green-400' : p.hpMax > 0 && p.hpCurrent / p.hpMax > 0.25 ? 'text-yellow-400' : 'text-red-400'}`} />
                          <span className="font-mono text-sm text-white">{p.hpCurrent}/{p.hpMax}</span>
                          {(p.hpTemp || 0) > 0 && <span className="text-cyan-400 text-xs">+{p.hpTemp}</span>}
                        </div>
                        <HpBar participant={p} />
                      </div>
                      <div className="flex items-center gap-1 w-12 justify-end flex-shrink-0">
                        <Shield className="w-4 h-4 text-gray-400" />
                        <span className="text-gray-300 font-mono text-sm">{p.armourClass}</span>
                      </div>
                    </>
                  ) : (
                    <div className="flex items-center gap-2 flex-shrink-0">
                      <span className="text-gray-500 text-sm">{p.participantType === 'MONSTER' ? 'Monster' : 'Player'}</span>
                      {isMyTurn && p.isAlive && !isOwn && (
                        <button onClick={() => selectAttackTarget(p.id)}
                          className="p-1.5 bg-orange-900/30 hover:bg-orange-900/60 text-orange-400 rounded" title="Attack">
                          <Crosshair className="w-3.5 h-3.5" />
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

export default function PlayerEncounterSessionPage() {
  const { encounterId } = useParams<{ encounterId: string }>();
  if (!encounterId) return null;

  return (
    <EncounterProvider encounterId={encounterId}>
      <PlayerSessionView />
    </EncounterProvider>
  );
}
