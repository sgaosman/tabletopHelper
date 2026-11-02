import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider, useEncounter } from '../../context/EncounterContext';
import { useAuth } from '../../context/AuthContext';
import { useCombatLog, useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import SpellCastModal from '../../components/encounter/SpellCastModal';
import RepeatEffectModal from '../../components/encounter/RepeatEffectModal';
import RepeatSavePrompt from '../../components/encounter/RepeatSavePrompt';
import WeaponAttackForm from '../../components/encounter/WeaponAttackForm';
import LairTurnRow from '../../components/encounter/LairTurnRow';
import ResourcePoolDisplay from '../../components/common/ResourcePoolDisplay';
import IconButton from '../../components/ui/IconButton';
import { getParticipantColour } from '../../utils/classColours';
import { healthLabel } from '../../utils/healthStatus';
import {
  ArrowLeft, Wifi, WifiOff, Heart, Shield, Skull,
  ChevronRight, ChevronLeft, ScrollText, Sparkles, Crosshair, Zap, Swords, X, RotateCw
} from 'lucide-react';
import { useState, useEffect, useRef, type FormEvent } from 'react';

const ALL_CONDITIONS = [
  'blinded', 'charmed', 'deafened', 'frightened', 'grappled',
  'incapacitated', 'invisible', 'paralyzed', 'petrified',
  'poisoned', 'prone', 'restrained', 'stunned', 'unconscious',
];

type PlayerAction = 'attack' | 'condition' | 'concentration' | 'spell' | 'repeat-effect' | null;

const CONDITION_BADGE = 'font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff';

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
        <div className="bg-ink/40 h-full transition-all duration-300" style={{ width: `${Math.min(tempPct, 100 - pct)}%` }} />
      )}
    </div>
  );
}

// The Roll button shows only while the encounter is Active, since combat needs it (D166)
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
    <div className="flex items-center gap-3 mt-2">
      <div className="flex items-center gap-1">
        <span className="font-body text-[12.5px] font-semibold text-muted">Saves:</span>
        {[0, 1, 2].map(i => (
          <div key={`s${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveSuccesses ? 'bg-buff border-buff' : 'border-rule-light'}`} />
        ))}
      </div>
      <div className="flex items-center gap-1">
        <span className="font-body text-[12.5px] font-semibold text-muted">Fails:</span>
        {[0, 1, 2].map(i => (
          <div key={`f${i}`} className={`w-3 h-3 rounded-full border ${i < participant.deathSaveFailures ? 'bg-debuff border-debuff' : 'border-rule-light'}`} />
        ))}
      </div>
      {canRoll && (
        <button
          onClick={handleRoll}
          disabled={rolling}
          className="px-2 py-0.5 font-body text-[12.5px] font-semibold bg-rule hover:bg-page-alt text-muted disabled:opacity-50"
        >
          {rolling ? '...' : 'Roll Death Save'}
        </button>
      )}
    </div>
  );
}

// Slots are spent only while the encounter is Active; otherwise they are shown read-only (D166)
function PlayerSpellSlots({ participant, canChange }: { participant: EncounterParticipant; canChange: boolean }) {
  const actions = useEncounterAction();
  const slots = participant.spellSlotsCurrent;
  if (!slots) return null;

  const levels = Object.keys(slots).sort((a, b) => parseInt(a) - parseInt(b));
  if (levels.length === 0) return null;

  async function handleUse(level: number) {
    await actions.spendSpellSlot(participant.id, level);
  }

  return (
    <div className="bg-card border border-rule p-4 mb-4">
      <div className="flex items-center gap-2 mb-2">
        <Sparkles className="w-4 h-4 text-ink" />
        <span className="font-heading text-[15px] font-semibold tracking-[0.01em] text-muted">Spell Slots</span>
      </div>
      <div className="flex items-center gap-4 flex-wrap">
        {levels.map(lvl => {
          const s = slots[lvl];
          const remaining = s.max - s.used;
          return (
            <div key={lvl} className="flex items-center gap-1">
              <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted w-6">Lv{lvl}</span>
              <div className="flex gap-1">
                {Array.from({ length: s.max }).map((_, i) => canChange ? (
                  <IconButton
                    key={i}
                    label={i < remaining ? `Use level ${lvl} slot` : `Level ${lvl} slot used`}
                    onClick={() => i < remaining ? handleUse(parseInt(lvl)) : undefined}
                    disabled={i >= remaining}
                    className={`w-3.5 h-3.5 rounded-full border transition-colors ${
                      i < remaining
                        ? 'bg-ink border-ink hover:bg-ink/80 cursor-pointer'
                        : 'border-rule-light cursor-default'
                    }`}
                  />
                ) : (
                  <span
                    key={i}
                    aria-hidden="true"
                    className={`w-3.5 h-3.5 rounded-full border ${i < remaining ? 'bg-ink border-ink' : 'border-rule-light'}`}
                  />
                ))}
              </div>
              <span className="font-body text-[12.5px] font-semibold text-faint">{remaining}/{s.max}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function CombatLogPanel() {
  // The server sends the players' log after every change, without entries about hidden monsters (D157, D171)
  const logs = useCombatLog();
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
      case 'DEATH_SAVE': return 'text-cls-fighter';
      case 'STABILIZE': return 'text-buff';
      case 'CONCENTRATION_CHECK': return 'text-cls-warlock';
      case 'CONCENTRATION_LOST': return 'text-cls-warlock';
      case 'SPELL_CAST': return 'text-ink';
      case 'SPELL_SLOT_USE': return 'text-ink';
      case 'SPELL_SLOT_RESTORE': return 'text-ink';
      case 'CONDITION_ADD': return 'text-cls-monk';
      case 'CONDITION_REMOVE': return 'text-cls-wizard';
      case 'TURN_ADVANCE': case 'TURN_BACK': return 'text-faint';
      case 'HP_SET': return 'text-buff';
      default: return 'text-muted';
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
          <span className="font-body text-[12.5px] font-semibold text-faint">({logs.length} entries)</span>
        </div>
        <ChevronRight className={`w-4 h-4 text-faint transition-transform ${expanded ? 'rotate-90' : ''}`} />
      </button>
      {expanded && (
        <div className="relative">
          <div ref={scrollRef} onScroll={handleScroll} aria-live="polite" aria-label="Combat log entries" className="max-h-48 overflow-y-auto border-t border-rule px-4 py-2 space-y-1">
            {logs.length === 0 ? (
              <p className="text-faint font-body text-[12.5px] font-semibold py-2">No actions yet</p>
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
                      <div className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-ink border-b border-rule pb-1 pt-2 mb-1">
                        Round {log.roundNumber}
                      </div>
                    )}
                    {showTurnHeader && (
                      <div className="font-body text-[12.5px] font-semibold text-faint py-0.5 pl-2 border-l-2 border-rule my-1">
                        Turn: {turnName}
                      </div>
                    )}
                    {!isTurnChange && (
                      <div className="flex items-start gap-2 font-body text-[12.5px] font-semibold pl-2">
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
              className="absolute bottom-2 left-1/2 -translate-x-1/2 flex items-center gap-1 px-3 py-1.5 bg-ink hover:bg-ink/80 text-card font-body text-[12.5px] font-semibold shadow-lg"
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

function PlayerActionPanel({ myCharacter, encounter, targetId, actionMode, onClose }: {
  myCharacter: EncounterParticipant;
  encounter: { participants: EncounterParticipant[]; allowPlayerManualModifiers: boolean };
  targetId: string | null;
  actionMode: PlayerAction;
  onClose: () => void;
}) {
  const actions = useEncounterAction();
  const [condition, setCondition] = useState('');
  const [conditionDuration, setConditionDuration] = useState('');
  const [spellName, setSpellName] = useState('');
  const [loading, setLoading] = useState(false);

  const target = encounter.participants.find(p => p.id === targetId);
  if (!target || !actionMode) return null;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!targetId || loading) return;
    setLoading(true);
    try {
      if (actionMode === 'condition' && condition) {
        await actions.addCondition(targetId, condition, conditionDuration ? parseInt(conditionDuration) : undefined);
      } else if (actionMode === 'concentration') {
        await actions.setConcentration(targetId, spellName || null);
      }
      onClose();
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="bg-card border border-rule p-4 mb-4">
      <div className="flex items-center justify-between mb-3">
        <div className="font-heading text-[15px] font-semibold tracking-[0.01em] text-ink">
          {actionMode === 'attack' && <><Crosshair className="w-4 h-4 inline mr-1 text-cls-fighter" />Attack {target.displayName} (AC {target.armourClass})</>}
          {actionMode === 'condition' && <><Zap className="w-4 h-4 inline mr-1 text-cls-monk" />Add Condition</>}
          {actionMode === 'concentration' && <><Swords className="w-4 h-4 inline mr-1 text-cls-warlock" />Set Concentration</>}
        </div>
        <IconButton label="Close action panel" onClick={onClose} className="text-faint hover:text-ink"><X className="w-4 h-4" /></IconButton>
      </div>

      {/* The weapons and numbers come from the server, with Manual Modifiers while the campaign allows them (D164) */}
      {actionMode === 'attack' && (
        <WeaponAttackForm attacker={myCharacter} target={target}
          allowManualModifiers={encounter.allowPlayerManualModifiers} onDone={onClose} />
      )}

      {actionMode !== 'attack' && <form onSubmit={handleSubmit}>
        {actionMode === 'condition' && (
          <div className="flex items-end gap-3">
            <div className="flex-1">
              <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Condition</label>
              <select value={condition} onChange={e => setCondition(e.target.value)}
                className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink focus:outline-none focus:border-muted">
                <option value="">Select...</option>
                {ALL_CONDITIONS.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            <div className="w-24">
              <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Duration</label>
              <input type="number" min="1" value={conditionDuration} onChange={e => setConditionDuration(e.target.value)}
                placeholder="&#8734;" className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted" />
            </div>
            <button type="submit" disabled={loading || !condition}
              className="px-4 py-2 bg-ink hover:bg-ink/80 disabled:bg-page-alt text-card font-body text-[16px] font-semibold">
              {loading ? '...' : 'Apply'}
            </button>
          </div>
        )}

        {actionMode === 'concentration' && (
          <div className="flex items-end gap-3">
            <div className="flex-1">
              <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1">Spell Name</label>
              <input type="text" value={spellName} onChange={e => setSpellName(e.target.value)}
                placeholder="Leave empty to clear" className="w-full px-3 py-2 bg-page-alt border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted" autoFocus />
            </div>
            <button type="submit" disabled={loading}
              className="px-4 py-2 bg-ink hover:bg-ink/80 disabled:bg-page-alt text-card font-body text-[16px] font-semibold">
              {loading ? '...' : spellName ? 'Set' : 'Clear'}
            </button>
          </div>
        )}
      </form>}
    </div>
  );
}

function PlayerSessionView() {
  const { encounter, isConnected, error } = useEncounter();
  const actions = useEncounterAction();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [actionMode, setActionMode] = useState<PlayerAction>(null);
  const [attackTargetId, setAttackTargetId] = useState<string | null>(null);

  if (!encounter) {
    return (
      <div className="min-h-screen bg-page flex items-center justify-center">
        {error
          ? <p role="alert" className="text-debuff font-body text-[15px] font-semibold">{error}</p>
          : <p className="text-muted font-body text-[15px] font-semibold">Loading encounter...</p>}
      </div>
    );
  }

  // The Player View already leaves out participants the DM has hidden, and the Lair's turn unless a monster
  // in its lair is visible; its turn order counts only what players see (D157, D163)
  const participantsById = new Map(encounter.participants.map(p => [p.id, p]));
  const lairMonsters = encounter.monstersInLair.flatMap(id => participantsById.get(id)?.displayName ?? []);
  const isMyTurn = encounter.participants.some(p => p.isCurrentTurn && p.controlledByUserId === user?.userId);
  const myCharacter = encounter.participants.find(p => p.controlledByUserId === user?.userId);
  // Repeat Saves the campaign leaves to this player, at the end of their character's turn (D162)
  const savesToRoll = (myCharacter?.activeConditions ?? []).filter(c => c.repeatSave?.awaitingRoll);
  // Actions show only while the encounter is Active: the server refuses them while it is Paused or
  // Completed (D166)
  const isActive = encounter.status === 'ACTIVE';

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
    await actions.removeCondition(myCharacter.id, condition);
  }

  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-page border-b border-rule px-6 py-3">
        <div className="flex items-center justify-between max-w-4xl mx-auto">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-muted hover:text-ink transition-colors font-body text-[15px] font-semibold">
            <ArrowLeft className="w-4 h-4" /> Dashboard
          </button>
          <div className="flex items-center gap-3">
            {encounter.status === 'COMPLETED' && (
              <span className="px-2.5 py-1 bg-page-alt text-muted font-heading text-[11px] font-semibold tracking-[0.02em]">Complete</span>
            )}
            {encounter.status === 'PAUSED' && (
              <span className="px-2.5 py-1 bg-cls-monk/20 text-cls-monk font-heading text-[11px] font-semibold tracking-[0.02em]">Paused</span>
            )}
            <div className="flex items-center gap-1.5">
              {isConnected ? <Wifi className="w-4 h-4 text-buff" /> : <WifiOff className="w-4 h-4 text-debuff" />}
              <span className="font-body text-[12.5px] font-semibold text-muted">{isConnected ? 'Live' : 'Disconnected'}</span>
            </div>
          </div>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-4">
        {error && <p className="text-debuff font-body text-[15px] font-semibold mb-4">{error}</p>}

        <div className="mb-4">
          <h1 className="font-heading text-[20px] font-bold tracking-[0.02em] text-ink">{encounter.name}</h1>
          <p className="text-muted font-body text-[15px] font-semibold">Round {encounter.roundNumber}</p>
          {isMyTurn && (
            <div className="mt-2 px-4 py-2.5 bg-cls-fighter/10 border border-cls-fighter/30">
              <p className="text-cls-fighter font-heading text-[15px] font-semibold tracking-[0.01em]">It&apos;s your turn!</p>
            </div>
          )}
        </div>

        {/* Own character at 0 HP: death saves only while Dying (D161), one due as each turn starts (D163) */}
        {myCharacter?.lifeState === 'DYING' && (
          <div className="bg-debuff/10 border border-debuff/30 p-4 mb-4">
            <p className="text-debuff font-heading text-[15px] font-semibold tracking-[0.01em] mb-1">You are dying!</p>
            {myCharacter.deathSaveDue && (
              <p role="status" className="text-debuff font-body text-[15px] font-semibold">Your turn has started: roll your death saving throw.</p>
            )}
            <DeathSaves participant={myCharacter} canRoll={isActive} />
          </div>
        )}
        {myCharacter && isActive && savesToRoll.length > 0 && (
          <div className="space-y-2 mb-4">
            {savesToRoll.map(c => <RepeatSavePrompt key={c.id} participant={myCharacter} condition={c} />)}
          </div>
        )}
        {myCharacter?.lifeState === 'STABLE' && (
          <div role="status" className="bg-page-alt border border-rule p-4 mb-4">
            <p className="text-muted font-heading text-[15px] font-semibold tracking-[0.01em]">You are stable: unconscious at 0 HP, and making no death saves.</p>
          </div>
        )}
        {myCharacter?.lifeState === 'DEAD' && (
          <div role="status" className="bg-debuff/10 border border-debuff/30 p-4 mb-4">
            <p className="text-debuff font-heading text-[15px] font-semibold tracking-[0.01em]">You have died. Only a revival can bring you back.</p>
          </div>
        )}

        {/* Own character spell slots */}
        {myCharacter && myCharacter.lifeState === 'ALIVE' && myCharacter.spellSlotsCurrent && (
          <PlayerSpellSlots participant={myCharacter} canChange={isActive} />
        )}

        {/* Resource pools (M24.5) */}
        {myCharacter && <ResourcePoolDisplay pools={myCharacter.resourcePoolsCurrent} />}

        {/* Player self-action buttons */}
        {isActive && myCharacter && myCharacter.lifeState === 'ALIVE' && (
          <div className="flex items-center gap-2 mb-4">
            <button onClick={() => selectSelfAction('condition')}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk font-heading text-[11px] font-semibold tracking-[0.02em] border border-cls-monk/30">
              <Zap className="w-3.5 h-3.5" /> Add Condition
            </button>
            <button onClick={() => selectSelfAction('concentration')}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock font-heading text-[11px] font-semibold tracking-[0.02em] border border-cls-warlock/30">
              <Swords className="w-3.5 h-3.5" /> {myCharacter.concentrationSpell ? 'Change' : 'Set'} Concentration
            </button>
            {myCharacter.spellsKnown && (
              <button onClick={() => selectSelfAction('spell')}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-ink/10 hover:bg-ink/20 text-ink font-heading text-[11px] font-semibold tracking-[0.02em] border border-ink/30">
                <Sparkles className="w-3.5 h-3.5" /> Cast Spell
              </button>
            )}
            {(myCharacter.concentrationSpell || myCharacter.activeSpell) && (
              <button onClick={() => selectSelfAction('repeat-effect')}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock font-heading text-[11px] font-semibold tracking-[0.02em] border border-cls-warlock/30">
                <RotateCw className="w-3.5 h-3.5" /> Repeat Effect
              </button>
            )}
          </div>
        )}

        {/* Spell cast modal */}
        {isActive && actionMode === 'spell' && myCharacter && (
          <SpellCastModal
            caster={myCharacter}
            participants={encounter.participants}
            onClose={() => { setActionMode(null); setAttackTargetId(null); }}
            allowManualModifiers={encounter.allowPlayerManualModifiers}
          />
        )}

        {/* Repeat spell effect modal */}
        {isActive && actionMode === 'repeat-effect' && myCharacter && (myCharacter.concentrationSpell || myCharacter.activeSpell) && (
          <RepeatEffectModal
            caster={myCharacter}
            participants={encounter.participants}
            spellName={(myCharacter.concentrationSpell || myCharacter.activeSpell)!}
            onClose={() => { setActionMode(null); setAttackTargetId(null); }}
            allowManualModifiers={encounter.allowPlayerManualModifiers}
          />
        )}

        {/* Action panel */}
        {isActive && actionMode && actionMode !== 'spell' && myCharacter && (
          <PlayerActionPanel
            myCharacter={myCharacter}
            encounter={encounter}
            targetId={attackTargetId}
            actionMode={actionMode}
            onClose={() => { setActionMode(null); setAttackTargetId(null); }}
          />
        )}

        {/* Initiative list, in the order the server computes */}
        <div className="space-y-2 mb-4" role="list" aria-label="Initiative order">
          {encounter.turnOrder.map(turn => {
            if (turn.lair) {
              return <LairTurnRow key="lair" turn={turn} lairMonsters={lairMonsters} />;
            }
            const p = participantsById.get(turn.participantId ?? '');
            if (!p) return null;
            const isOwn = p.controlledByUserId === user?.userId;
            const conditions = p.activeConditions ?? [];
            const isMonster = p.participantType === 'MONSTER';
            const participantColour = getParticipantColour(isMonster, p.characterClass);
            const hpRatio = p.hpCurrent != null && p.hpMax ? p.hpCurrent / p.hpMax : 0;
            const monsterHealth = isMonster ? healthLabel(p) : null;

            return (
              <div
                key={p.id}
                aria-current={turn.current ? 'step' : undefined}
                className={`bg-card border px-4 py-3 transition-all ${
                  turn.current
                    ? 'border-cls-fighter ring-1 ring-cls-fighter/30'
                    : isOwn
                      ? 'border-ink/50'
                      : 'border-rule'
                } ${p.lifeState !== 'ALIVE' ? 'opacity-60' : ''}`}
              >
                <div className="flex items-center gap-3">
                  <div className="w-5 flex-shrink-0">
                    {turn.current && <ChevronRight className="w-5 h-5 text-cls-fighter" />}
                  </div>

                  <div className="w-9 text-center flex-shrink-0">
                    <span className="font-heading text-[17px] font-bold text-ink">{p.initiative ?? '---'}</span>
                  </div>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span
                        className={`font-heading text-[15px] font-semibold tracking-[0.01em] ${p.lifeState === 'DEAD' ? 'line-through' : ''}`}
                        style={{ color: participantColour }}
                      >
                        {p.displayName}
                      </span>
                      {isOwn && <span className="px-1.5 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] bg-ink/10 text-ink">You</span>}
                      <LifeStateBadge participant={p} />
                    </div>
                    {conditions.length > 0 && (
                      <div className="flex gap-1 mt-1 flex-wrap">
                        {conditions.map(c => {
                          const remaining = c.duration != null ? Math.max(0, c.duration - (encounter.roundNumber - c.appliedRound)) : null;
                          return isOwn && isActive ? (
                            <button key={c.id} onClick={() => removeCondition(c.name)}
                              className={`${CONDITION_BADGE} hover:opacity-70`}
                              title="Click to remove">
                              {c.name}{c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}{remaining != null ? ` (${remaining})` : ''} x
                            </button>
                          ) : (
                            <span key={c.id} className={CONDITION_BADGE}>
                              {c.name}{c.sourceSpellName ? ` (${c.sourceSpellName})` : ''}{remaining != null ? ` (${remaining})` : ''}
                            </span>
                          );
                        })}
                      </div>
                    )}
                    {p.concentrationSpell && (
                      <p className="text-cls-warlock font-body text-[12.5px] font-semibold mt-0.5">Concentrating: {p.concentrationSpell}</p>
                    )}
                    {p.activeSpell && (
                      <p className="text-cls-monk font-body text-[12.5px] font-semibold mt-0.5">Active: {p.activeSpell}</p>
                    )}
                  </div>

                  {isOwn ? (
                    <>
                      <div className="w-28 flex-shrink-0">
                        <div className="flex items-center justify-end gap-1.5 mb-1">
                          <Heart className={`w-3.5 h-3.5 ${hpRatio > 0.5 ? 'text-buff' : hpRatio > 0.25 ? 'text-cls-monk' : 'text-debuff'}`} />
                          <span className="font-heading text-[13px] font-semibold text-ink">{p.hpCurrent}/{p.hpMax}</span>
                          {(p.hpTemp || 0) > 0 && <span className="text-cls-wizard font-heading text-[13px] font-semibold">+{p.hpTemp}</span>}
                        </div>
                        <HpBar participant={p} />
                      </div>
                      <div className="flex items-center gap-1 w-12 justify-end flex-shrink-0">
                        <Shield className="w-4 h-4 text-muted" />
                        <span className="text-muted font-heading text-[13px] font-semibold">{p.armourClass}</span>
                      </div>
                    </>
                  ) : (
                    <div className="flex items-center gap-2 flex-shrink-0">
                      <span className="text-faint font-body text-[15px] font-semibold">{p.participantType === 'MONSTER' ? 'Monster' : 'Player'}</span>
                      {monsterHealth && (
                        <span className="font-heading text-[13px] font-semibold text-muted">{monsterHealth}</span>
                      )}
                      {/* The dead can't be attacked; the dying can (D161) */}
                      {isActive && isMyTurn && p.lifeState !== 'DEAD' && !isOwn && (
                        <IconButton label="Attack" onClick={() => selectAttackTarget(p.id)}
                          className="p-1.5 bg-cls-fighter/10 hover:bg-cls-fighter/20 text-cls-fighter">
                          <Crosshair className="w-3.5 h-3.5" />
                        </IconButton>
                      )}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        {/* Combat log */}
        <CombatLogPanel />
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
