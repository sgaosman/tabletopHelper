import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider, useEncounter } from '../../context/EncounterContext';
import { useAuth } from '../../context/AuthContext';
import { combatApi } from '../../api/combatApi';
import type { EncounterParticipant, ConditionEntry, SpellSlots } from '../../types/encounter';
import type { CombatLogEntry } from '../../types/combat';
import {
  ArrowLeft, Wifi, WifiOff, Heart, Shield, Skull,
  ChevronRight, ScrollText, Sparkles
} from 'lucide-react';
import { useState, useEffect, useRef } from 'react';

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

  useEffect(() => {
    combatApi.getCombatLog(encounterId).then(res => setLogs(res.data));
    const interval = setInterval(() => {
      combatApi.getCombatLog(encounterId).then(res => setLogs(res.data));
    }, 3000);
    return () => clearInterval(interval);
  }, [encounterId]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [logs]);

  function getLogColor(actionType: string) {
    switch (actionType) {
      case 'ATTACK': return 'text-orange-400';
      case 'DAMAGE': case 'KILL': return 'text-red-400';
      case 'HEAL': case 'REVIVE': return 'text-green-400';
      case 'DEATH_SAVE': return 'text-orange-400';
      case 'STABILIZE': return 'text-green-300';
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
        </div>
        <ChevronRight className={`w-4 h-4 text-gray-500 transition-transform ${expanded ? 'rotate-90' : ''}`} />
      </button>
      {expanded && (
        <div ref={scrollRef} className="max-h-48 overflow-y-auto border-t border-gray-800 px-4 py-2 space-y-1">
          {logs.length === 0 ? (
            <p className="text-gray-500 text-xs py-2">No actions yet</p>
          ) : (
            logs.map(log => (
              <div key={log.id} className="flex items-start gap-2 text-xs">
                <span className="text-gray-600 font-mono shrink-0">R{log.roundNumber}</span>
                <span className={getLogColor(log.actionType)}>{log.description}</span>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}

function PlayerSessionView() {
  const { encounter, isConnected, error, refreshEncounter } = useEncounter();
  const { user } = useAuth();
  const navigate = useNavigate();

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
                          return (
                            <span key={c.name} className={`px-1.5 py-0.5 rounded text-xs ${CONDITION_COLORS[c.name] || 'bg-gray-700 text-gray-300'}`}>
                              {c.name}{remaining != null ? ` (${remaining})` : ''}
                            </span>
                          );
                        })}
                      </div>
                    )}
                    {p.concentrationSpell && isOwn && (
                      <p className="text-purple-400 text-xs mt-0.5">Concentrating: {p.concentrationSpell}</p>
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
                    <div className="text-gray-500 text-sm flex-shrink-0">
                      {p.participantType === 'MONSTER' ? 'Monster' : 'Player'}
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
