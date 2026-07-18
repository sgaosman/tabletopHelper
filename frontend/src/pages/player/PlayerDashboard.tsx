import { useState, useEffect, FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { campaignApi } from '../../api/campaignApi';
import { characterApi } from '../../api/characterApi';
import type { Campaign } from '../../types/campaign';
import type { PlayerCharacter } from '../../types/character';
import { Plus, Users, ScrollText, Zap, Swords } from 'lucide-react';

export default function PlayerDashboard() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [characters, setCharacters] = useState<PlayerCharacter[]>([]);
  const [inviteCode, setInviteCode] = useState('');
  const [joinError, setJoinError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadData();
  }, []);

  async function loadData() {
    try {
      const [campRes, charRes] = await Promise.all([
        campaignApi.getAll(),
        characterApi.getMine(),
      ]);
      setCampaigns(campRes.data);
      setCharacters(charRes.data);
    } finally {
      setLoading(false);
    }
  }

  async function handleJoin(e: FormEvent) {
    e.preventDefault();
    setJoinError('');
    try {
      await campaignApi.join({ inviteCode: inviteCode.trim() });
      setInviteCode('');
      loadData();
    } catch (err: any) {
      setJoinError(err.response?.data?.error || 'Failed to join campaign');
    }
  }

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">QuestKeeper</h1>
          <p className="text-sm text-gray-400">Player — {user?.displayName}</p>
        </div>
        <div className="flex items-center gap-4">
          <button onClick={() => navigate('/player/quickref')} className="flex items-center gap-1.5 text-gray-400 hover:text-cyan-400 text-sm transition-colors">
            <Zap className="w-4 h-4" /> Quick Rules Reference
          </button>
          <button onClick={() => navigate('/select-role')} className="text-gray-400 hover:text-white text-sm transition-colors">Switch Role</button>
          <button onClick={logout} className="text-gray-400 hover:text-white text-sm transition-colors">Sign Out</button>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-8 space-y-10">
        {/* Join Campaign */}
        <section>
          <h2 className="text-xl font-semibold text-white mb-4">Join a Campaign</h2>
          <form onSubmit={handleJoin} className="flex gap-3">
            <input
              type="text"
              value={inviteCode}
              onChange={(e) => setInviteCode(e.target.value.toUpperCase())}
              required
              maxLength={8}
              placeholder="Enter invite code"
              className="flex-1 max-w-xs px-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white font-mono tracking-wider placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 uppercase"
            />
            <button type="submit" className="px-4 py-2.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-sm transition-colors">Join</button>
          </form>
          {joinError && <p className="text-red-400 text-sm mt-2">{joinError}</p>}
        </section>

        {/* Join Encounter */}
        <section>
          <h2 className="text-xl font-semibold text-white mb-4 flex items-center gap-2">
            <Swords className="w-5 h-5 text-orange-400" /> Encounters
          </h2>
          <button
            onClick={() => navigate('/player/encounter/join')}
            className="px-4 py-3 bg-gray-900 border border-gray-800 hover:border-orange-500 rounded-lg text-left transition-colors w-full max-w-xs"
          >
            <p className="text-white font-medium">Join Encounter</p>
            <p className="text-gray-400 text-xs mt-0.5">Enter a session code from your DM</p>
          </button>
        </section>

        {/* My Campaigns */}
        <section>
          <h2 className="text-xl font-semibold text-white mb-4 flex items-center gap-2">
            <Users className="w-5 h-5 text-gray-400" /> My Campaigns
          </h2>
          {loading ? (
            <p className="text-gray-400">Loading...</p>
          ) : campaigns.length === 0 ? (
            <p className="text-gray-500 text-sm">You haven't joined any campaigns yet. Ask your DM for an invite code.</p>
          ) : (
            <div className="space-y-3">
              {campaigns.map((c) => (
                <div key={c.id} className="bg-gray-900 border border-gray-800 rounded-lg p-4 flex items-center justify-between">
                  <div>
                    <h3 className="text-white font-medium">{c.name}</h3>
                    <p className="text-gray-400 text-sm">DM: {c.dmDisplayName} &middot; {c.members.length} members</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* My Characters */}
        <section>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xl font-semibold text-white flex items-center gap-2">
              <ScrollText className="w-5 h-5 text-gray-400" /> My Characters
            </h2>
            <button
              onClick={() => navigate('/player/characters/new')}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-sm transition-colors"
            >
              <Plus className="w-4 h-4" /> New Character
            </button>
          </div>
          {loading ? (
            <p className="text-gray-400">Loading...</p>
          ) : characters.length === 0 ? (
            <p className="text-gray-500 text-sm">No characters yet. Create one to get started.</p>
          ) : (
            <div className="space-y-3">
              {characters.map((c) => (
                <button
                  key={c.id}
                  onClick={() => navigate(`/player/characters/${c.id}`)}
                  className="w-full bg-gray-900 border border-gray-800 hover:border-gray-600 rounded-lg p-4 text-left transition-colors"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <h3 className="text-white font-semibold">{c.name}</h3>
                      <p className="text-gray-400 text-sm">
                        Level {c.level} {c.race} {c.characterClass}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="text-white text-sm">HP {c.hpCurrent}/{c.hpMax}</p>
                      <p className="text-gray-400 text-xs">AC {c.armourClass}</p>
                    </div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
