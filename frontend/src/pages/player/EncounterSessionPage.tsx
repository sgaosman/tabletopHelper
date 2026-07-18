import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider, useEncounter } from '../../context/EncounterContext';
import { useAuth } from '../../context/AuthContext';
import type { EncounterParticipant } from '../../types/encounter';
import { ArrowLeft, Wifi, WifiOff, Heart, Shield, Skull, ChevronRight } from 'lucide-react';

function PlayerSessionView() {
  const { encounter, isConnected, error } = useEncounter();
  const { user } = useAuth();
  const navigate = useNavigate();

  if (!encounter) {
    return <div className="min-h-screen bg-gray-950 flex items-center justify-center"><p className="text-gray-400">Loading encounter...</p></div>;
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
  const visibleParticipants = sorted.filter(p => p.isVisibleToPlayers || p.controlledByUserId === user?.userId);
  const isMyTurn = encounter.participants.some(p => p.isCurrentTurn && p.controlledByUserId === user?.userId);

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4">
        <div className="flex items-center justify-between max-w-4xl mx-auto">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors">
            <ArrowLeft className="w-4 h-4" /> Back to Dashboard
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

      <main className="max-w-4xl mx-auto px-6 py-6">
        {error && <p className="text-red-400 text-sm mb-4">{error}</p>}

        <div className="mb-6">
          <h1 className="text-2xl font-bold text-white">{encounter.name}</h1>
          <p className="text-gray-400 text-sm">Round {encounter.roundNumber}</p>
          {isMyTurn && (
            <div className="mt-2 px-4 py-2 bg-orange-900/30 border border-orange-500/50 rounded-lg">
              <p className="text-orange-300 font-semibold text-sm">It's your turn!</p>
            </div>
          )}
        </div>

        <div className="space-y-2">
          {visibleParticipants.map((p: EncounterParticipant) => {
            const isOwn = p.controlledByUserId === user?.userId;
            const conditions = parseConditions(p);

            return (
              <div
                key={p.id}
                className={`flex items-center gap-4 bg-gray-900 border rounded-lg px-4 py-3 transition-all ${
                  p.isCurrentTurn
                    ? 'border-orange-500 ring-1 ring-orange-500/30'
                    : isOwn
                      ? 'border-indigo-500/50'
                      : 'border-gray-800'
                } ${!p.isAlive ? 'opacity-50' : ''}`}
              >
                <div className="w-6 flex-shrink-0">
                  {p.isCurrentTurn && <ChevronRight className="w-5 h-5 text-orange-400" />}
                </div>

                <div className="w-10 text-center">
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
                      {conditions.map(c => (
                        <span key={c} className="px-1.5 py-0.5 rounded text-xs bg-gray-700 text-gray-300">{c}</span>
                      ))}
                    </div>
                  )}
                </div>

                {isOwn ? (
                  <>
                    <div className="flex items-center gap-1.5">
                      <Heart className={`w-4 h-4 ${getHpColor(p)}`} />
                      <span className={`font-mono text-sm ${getHpColor(p)}`}>{p.hpCurrent}/{p.hpMax}</span>
                      {p.hpTemp > 0 && <span className="text-cyan-400 text-xs">+{p.hpTemp}</span>}
                    </div>
                    <div className="flex items-center gap-1">
                      <Shield className="w-4 h-4 text-gray-400" />
                      <span className="text-gray-300 font-mono text-sm">{p.armourClass}</span>
                    </div>
                  </>
                ) : (
                  <div className="text-gray-500 text-sm">
                    {p.participantType === 'MONSTER' ? 'Monster' : 'Player'}
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

export default function PlayerEncounterSessionPage() {
  const { encounterId } = useParams<{ encounterId: string }>();
  if (!encounterId) return null;

  return (
    <EncounterProvider encounterId={encounterId}>
      <PlayerSessionView />
    </EncounterProvider>
  );
}
