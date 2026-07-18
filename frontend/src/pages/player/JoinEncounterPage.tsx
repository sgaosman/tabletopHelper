import { useState, FormEvent } from 'react';
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
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4">
        <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors">
          <ArrowLeft className="w-4 h-4" /> Back to Dashboard
        </button>
      </header>

      <main className="max-w-md mx-auto px-6 py-16 text-center">
        <Swords className="w-12 h-12 text-orange-400 mx-auto mb-4" />
        <h1 className="text-2xl font-bold text-white mb-2">Join Encounter</h1>
        <p className="text-gray-400 text-sm mb-8">Enter the session code from your DM to join a live encounter.</p>

        <form onSubmit={handleJoin} className="space-y-4">
          <input
            type="text"
            value={code}
            onChange={e => setCode(e.target.value.toUpperCase())}
            placeholder="Session code"
            required
            maxLength={8}
            className="w-full px-4 py-3 bg-gray-800 border border-gray-700 rounded-lg text-white text-center font-mono text-xl tracking-widest placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-orange-500 uppercase"
          />
          {error && <p className="text-red-400 text-sm">{error}</p>}
          <button
            type="submit"
            disabled={loading || !code.trim()}
            className="w-full px-4 py-3 bg-orange-600 hover:bg-orange-500 text-white rounded-lg font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? 'Joining...' : 'Join'}
          </button>
        </form>
      </main>
    </div>
  );
}
