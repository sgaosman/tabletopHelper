import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider, useEncounter } from '../../context/EncounterContext';
import { encounterApi } from '../../api/encounterApi';
import type { EncounterParticipant } from '../../types/encounter';
import { ArrowLeft, Pause, Play, Flag, Copy, Check, Wifi, WifiOff, ChevronRight, ChevronLeft, Heart, Shield, Skull } from 'lucide-react';
import { useState } from 'react';

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

function DmSessionView() {
  const { encounter, isConnected, error, refreshEncounter } = useEncounter();
  const navigate = useNavigate();
  const [copiedCode, setCopiedCode] = useState(false);

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
    await encounterApi.end(encounter.id);
    refreshEncounter();
  }

  function getHpColor(p: EncounterParticipant) {
    const pct = p.hpMax > 0 ? p.hpCurrent / p.hpMax : 0;
    if (pct > 0.5) return 'text-green-400';
    if (pct > 0.25) return 'text-yellow-400';
    return 'text-red-400';
  }

  function parseConditions(p: EncounterParticipant): string[] {
    if (!p.activeConditions) return [];
    try {
      return JSON.parse(p.activeConditions);
    } catch {
      return [];
    }
  }

  const sorted = [...encounter.participants].sort((a, b) => (b.initiative ?? 0) - (a.initiative ?? 0));

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4">
        <div className="flex items-center justify-between max-w-5xl mx-auto">
          <button onClick={() => navigate('/dm/encounters')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors">
            <ArrowLeft className="w-4 h-4" /> Back to Builder
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

      <main className="max-w-5xl mx-auto px-6 py-6">
        {error && <p className="text-red-400 text-sm mb-4">{error}</p>}

        {/* Encounter info bar */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold text-white">{encounter.name}</h1>
            <p className="text-gray-400 text-sm">
              Round {encounter.roundNumber} &middot; {encounter.participants.length} participants
            </p>
          </div>
          <div className="flex items-center gap-2">
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
              <button onClick={handleEnd} className="flex items-center gap-1.5 px-3 py-2 bg-red-600 hover:bg-red-500 text-white rounded-lg text-sm">
                <Flag className="w-4 h-4" /> End
              </button>
            )}
            {encounter.status === 'COMPLETED' && (
              <span className="px-3 py-2 bg-gray-800 text-gray-400 rounded-lg text-sm">Encounter Complete</span>
            )}
          </div>
        </div>

        {/* Initiative order */}
        <div className="space-y-2">
          {sorted.map((p: EncounterParticipant) => {
            const conditions = parseConditions(p);
            return (
              <div
                key={p.id}
                className={`flex items-center gap-4 bg-gray-900 border rounded-lg px-4 py-3 transition-all ${
                  p.isCurrentTurn ? 'border-orange-500 ring-1 ring-orange-500/30' : 'border-gray-800'
                } ${!p.isAlive ? 'opacity-50' : ''}`}
              >
                {/* Turn indicator */}
                <div className="w-6 flex-shrink-0">
                  {p.isCurrentTurn && <ChevronRight className="w-5 h-5 text-orange-400" />}
                </div>

                {/* Initiative */}
                <div className="w-10 text-center">
                  <span className="text-white font-bold text-lg">{p.initiative ?? '—'}</span>
                </div>

                {/* Name + type */}
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
                  {conditions.length > 0 && (
                    <div className="flex gap-1 mt-1 flex-wrap">
                      {conditions.map(c => (
                        <span key={c} className={`px-1.5 py-0.5 rounded text-xs ${CONDITION_COLORS[c] || 'bg-gray-700 text-gray-300'}`}>
                          {c}
                        </span>
                      ))}
                    </div>
                  )}
                  {p.concentrationSpell && (
                    <p className="text-purple-400 text-xs mt-0.5">Concentrating: {p.concentrationSpell}</p>
                  )}
                </div>

                {/* HP */}
                <div className="flex items-center gap-1.5 w-24 justify-end">
                  <Heart className={`w-4 h-4 ${getHpColor(p)}`} />
                  <span className={`font-mono text-sm ${getHpColor(p)}`}>
                    {p.hpCurrent}/{p.hpMax}
                  </span>
                  {p.hpTemp > 0 && <span className="text-cyan-400 text-xs">+{p.hpTemp}</span>}
                </div>

                {/* AC */}
                <div className="flex items-center gap-1 w-12 justify-end">
                  <Shield className="w-4 h-4 text-gray-400" />
                  <span className="text-gray-300 font-mono text-sm">{p.armourClass}</span>
                </div>

                {/* Death saves */}
                {!p.isAlive && p.participantType === 'PLAYER' && (
                  <div className="w-20 text-right">
                    <span className="text-green-400 text-xs">{p.deathSaveSuccesses}/3</span>
                    <span className="text-gray-500 text-xs mx-1">|</span>
                    <span className="text-red-400 text-xs">{p.deathSaveFailures}/3</span>
                  </div>
                )}
              </div>
            );
          })}
        </div>
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
