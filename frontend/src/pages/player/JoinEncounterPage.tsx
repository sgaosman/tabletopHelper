import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { encounterApi } from '../../api/encounterApi';
import { ArrowLeft, Swords } from 'lucide-react';

export default function JoinEncounterPage() {
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleJoin(e: FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await encounterApi.getBySessionCode(code.trim());
      navigate(`/player/encounter/${res.data.id}/session`);
    } catch {
      setError('Encounter not found. Check the session code and try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-card border-b border-rule px-6 py-4">
        <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-muted hover:text-ink font-body text-[13px] font-medium transition-colors">
          <ArrowLeft className="w-4 h-4" /> Back to Dashboard
        </button>
      </header>

      <main className="max-w-md mx-auto px-6 py-16 text-center">
        <Swords className="w-12 h-12 text-cls-fighter mx-auto mb-4" />
        <h1 className="font-heading text-[20px] font-bold tracking-[0.02em] text-ink mb-2">Join Encounter</h1>
        <p className="font-body text-[13px] font-medium text-muted mb-8">Enter the session code from your DM to join a live encounter.</p>

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
          {error && <p className="font-body text-[12px] text-debuff">{error}</p>}
          <button
            type="submit"
            disabled={loading || !code.trim()}
            className="w-full px-4 py-3 bg-ink text-card font-body text-[14px] font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed hover:opacity-90"
          >
            {loading ? 'Joining...' : 'Join'}
          </button>
        </form>
      </main>
    </div>
  );
}
