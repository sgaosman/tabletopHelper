import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { characterApi } from '../../api/characterApi';
import { campaignApi } from '../../api/campaignApi';
import type { Campaign } from '../../types/campaign';
import type { PlayerCharacter } from '../../types/character';
import { Plus, Users, ScrollText, Swords, Trash2 } from 'lucide-react';
import NavBar from '../../components/common/NavBar';
import { getClassColour } from '../../utils/classColours';

export default function PlayerDashboard() {
  const navigate = useNavigate();
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [characters, setCharacters] = useState<PlayerCharacter[]>([]);
  const [inviteCode, setInviteCode] = useState('');
  const [joinError, setJoinError] = useState('');
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<PlayerCharacter | null>(null);
  const [deleteConfirmName, setDeleteConfirmName] = useState('');
  const [deleteError, setDeleteError] = useState('');
  const [deleting, setDeleting] = useState(false);

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

  async function handleDelete() {
    if (!deleteTarget || deleteConfirmName !== deleteTarget.name) return;
    setDeleting(true);
    setDeleteError('');
    try {
      await characterApi.delete(deleteTarget.id);
      setCharacters(prev => prev.filter(c => c.id !== deleteTarget.id));
      setDeleteTarget(null);
      setDeleteConfirmName('');
    } catch (err: any) {
      setDeleteError(err.response?.data?.error || 'Failed to delete character');
    } finally {
      setDeleting(false);
    }
  }

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="max-w-4xl mx-auto px-6 py-8 space-y-10">
        {/* Join Campaign */}
        <section>
          <h2 className="font-heading text-[11px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Join a Campaign</h2>
          <form onSubmit={handleJoin} className="flex gap-3">
            <input
              type="text"
              value={inviteCode}
              onChange={(e) => setInviteCode(e.target.value.toUpperCase())}
              required
              maxLength={8}
              placeholder="Enter invite code"
              className="flex-1 max-w-xs px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink tracking-wider placeholder-faint focus:border-muted focus:outline-none uppercase"
            />
            <button type="submit" className="px-4 py-2 bg-ink text-card font-body text-[14px] font-medium border border-ink hover:bg-muted hover:border-muted transition-colors">
              Join
            </button>
          </form>
          {joinError && <p role="alert" className="text-debuff font-body text-[13px] mt-2">{joinError}</p>}
        </section>

        {/* Join Encounter */}
        <section>
          <h2 className="font-heading text-[11px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <Swords className="w-3.5 h-3.5" /> Encounters
          </h2>
          <button
            onClick={() => navigate('/player/encounter/join')}
            className="bg-card border border-rule hover:border-muted p-4 text-left transition-colors w-full max-w-xs"
            style={{ borderLeftWidth: '3px', borderLeftColor: '#DC2626' }}
          >
            <p className="font-heading text-[13px] font-semibold text-ink">Join Encounter</p>
            <p className="font-body text-[12px] font-medium text-muted mt-0.5">Enter a session code from your DM</p>
          </button>
        </section>

        {/* My Campaigns */}
        <section>
          <h2 className="font-heading text-[11px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <Users className="w-3.5 h-3.5" /> My Campaigns
          </h2>
          {loading ? (
            <p className="font-body text-[13px] text-muted">Loading...</p>
          ) : campaigns.length === 0 ? (
            <p className="font-body text-[13px] text-faint">You haven't joined any campaigns yet. Ask your DM for an invite code.</p>
          ) : (
            <div className="space-y-2">
              {campaigns.map((c) => (
                <div key={c.id} className="bg-card border border-rule p-4 flex items-center justify-between">
                  <div>
                    <h3 className="font-heading text-[13px] font-semibold text-ink">{c.name}</h3>
                    <p className="font-body text-[12px] font-medium text-muted">DM: {c.dmDisplayName} &middot; {c.members.length} members</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* My Characters */}
        <section>
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-heading text-[11px] font-semibold tracking-[0.1em] uppercase text-faint flex items-center gap-2">
              <ScrollText className="w-3.5 h-3.5" /> My Characters
            </h2>
            <button
              onClick={() => navigate('/player/characters/new')}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-ink text-card font-body text-[13px] font-medium border border-ink hover:bg-muted hover:border-muted transition-colors"
            >
              <Plus className="w-3.5 h-3.5" /> New Character
            </button>
          </div>
          {loading ? (
            <p className="font-body text-[13px] text-muted">Loading...</p>
          ) : characters.length === 0 ? (
            <button
              onClick={() => navigate('/player/characters/new')}
              className="bg-page border border-dashed border-rule hover:border-muted p-6 flex flex-col items-center gap-1.5 w-full transition-colors"
            >
              <Plus className="w-6 h-6 text-faint" />
              <span className="font-heading text-[12px] font-medium text-faint">Create New Character</span>
            </button>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {characters.map((c) => {
                const clsColour = getClassColour(c.characterClass);
                return (
                  <div
                    key={c.id}
                    className="group bg-card border border-rule hover:bg-page-alt transition-colors flex"
                    style={{ borderLeftWidth: '3px', borderLeftColor: clsColour }}
                  >
                    <button
                      onClick={() => navigate(`/player/characters/${c.id}`)}
                      className="flex-1 p-3 text-left min-w-0"
                    >
                      <div className="flex items-center gap-3">
                        <div
                          className="w-9 h-9 flex items-center justify-center shrink-0"
                          style={{ backgroundColor: clsColour }}
                        >
                          <span className="font-heading text-[13px] font-bold text-white">{c.name[0]}</span>
                        </div>
                        <div className="min-w-0 flex-1">
                          <h3 className="font-heading text-[14px] font-bold" style={{ color: clsColour }}>{c.name}</h3>
                          <p className="font-body text-[11px] font-medium text-muted">
                            Level {c.level} {c.race} {c.characterClass}
                          </p>
                        </div>
                        <div className="text-right shrink-0">
                          <p className="font-heading text-[11px] font-semibold text-ink">HP {c.hpCurrent}/{c.hpMax}</p>
                          <p className="font-body text-[11px] text-faint">AC {c.armourClass}</p>
                        </div>
                      </div>
                    </button>
                    <button
                      onClick={() => { setDeleteTarget(c); setDeleteConfirmName(''); setDeleteError(''); }}
                      className="px-3 text-faint hover:text-debuff transition-colors shrink-0"
                      title="Delete character"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                );
              })}
              <button
                onClick={() => navigate('/player/characters/new')}
                className="bg-page border border-dashed border-rule hover:border-muted p-6 flex flex-col items-center justify-center gap-1.5 transition-colors"
              >
                <Plus className="w-5 h-5 text-faint" />
                <span className="font-heading text-[11px] font-medium text-faint">Create New</span>
              </button>
            </div>
          )}
        </section>
      </main>

      {/* Delete Confirmation Modal */}
      {deleteTarget && (
        <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4" onClick={() => { if (!deleting) { setDeleteTarget(null); setDeleteConfirmName(''); } }}>
          <div role="dialog" aria-modal="true" aria-labelledby="delete-title" className="bg-card border border-rule p-6 max-w-md w-full" onClick={e => e.stopPropagation()}>
            <h3 id="delete-title" className="font-heading text-[15px] font-semibold text-ink mb-2">Delete Character</h3>
            <p className="font-body text-[13px] font-medium text-muted mb-1">
              Are you sure you want to delete <span className="text-ink font-semibold">{deleteTarget.name}</span>? This action cannot be undone.
            </p>
            <p className="font-body text-[12px] text-faint mb-4">
              Type the character's name below to confirm.
            </p>
            <input
              type="text"
              value={deleteConfirmName}
              onChange={e => setDeleteConfirmName(e.target.value)}
              placeholder={deleteTarget.name}
              className="w-full px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none mb-4"
              autoFocus
            />
            {deleteError && <p role="alert" className="text-debuff font-body text-[13px] mb-3">{deleteError}</p>}
            <div className="flex gap-3">
              <button
                onClick={() => { setDeleteTarget(null); setDeleteConfirmName(''); }}
                disabled={deleting}
                className="flex-1 px-4 py-2 bg-page border border-rule font-body text-[14px] font-medium text-muted hover:border-muted transition-colors disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleDelete}
                disabled={deleteConfirmName !== deleteTarget.name || deleting}
                className="flex-1 px-4 py-2 bg-debuff text-white font-body text-[14px] font-medium border border-debuff hover:opacity-80 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {deleting ? 'Deleting...' : 'Delete'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
