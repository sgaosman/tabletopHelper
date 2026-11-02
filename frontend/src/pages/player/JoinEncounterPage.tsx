import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiErrorMessage } from '../../api/errors';
import { useFindEncounterBySessionCode } from '../../queries/encounters';
import { ArrowLeft, Swords } from 'lucide-react';

export default function JoinEncounterPage() {
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const findEncounter = useFindEncounterBySessionCode();
  const loading = findEncounter.isPending;

  function handleJoin(e: FormEvent) {
    e.preventDefault();
    findEncounter.mutate(code.trim(), {
      onSuccess: encounter => navigate(`/player/encounter/${encounter.id}/session`),
    });
  }

  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-card border-b border-rule px-6 py-4">
        <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-muted hover:text-ink font-body text-[15px] font-semibold transition-colors">
          <ArrowLeft className="w-4 h-4" /> Back to Dashboard
        </button>
      </header>

      <main className="max-w-md mx-auto px-6 py-16 text-center">
        <Swords className="w-12 h-12 text-cls-fighter mx-auto mb-4" />
        <h1 className="font-heading text-[20px] font-bold tracking-[0.02em] text-ink mb-2">Join Encounter</h1>
        <p className="font-body text-[15px] font-semibold text-muted mb-8">Enter the session code from your DM to join a live encounter.</p>

        <form onSubmit={handleJoin} className="space-y-4">
          <input
            type="text"
            value={code}
            onChange={e => setCode(e.target.value.toUpperCase())}
            placeholder="Session code"
            required
            maxLength={8}
            className="w-full px-4 py-3 bg-page-alt border border-rule text-ink text-center font-heading text-[20px] font-bold tracking-[0.2em] placeholder-faint focus:outline-none focus:border-muted uppercase"
          />
          {findEncounter.error && (
            <p role="alert" className="font-body text-[14px] text-debuff">{apiErrorMessage(findEncounter.error)}</p>
          )}
          <button
            type="submit"
            disabled={loading || !code.trim()}
            className="w-full px-4 py-3 bg-ink text-card font-body text-[16px] font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed hover:opacity-90"
          >
            {loading ? 'Joining...' : 'Join'}
          </button>
        </form>
      </main>
    </div>
  );
}
