import { useId, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiErrorMessage } from '../../api/errors';
import { useCampaigns, useJoinCampaign } from '../../queries/campaigns';
import { useDeleteCharacter, useMyCharacters } from '../../queries/characters';
import type { PlayerCharacter } from '../../types/character';
import { Plus, Users, ScrollText, Swords, Trash2 } from 'lucide-react';
import NavBar from '../../components/common/NavBar';
import Button from '../../components/ui/Button';
import IconButton from '../../components/ui/IconButton';
import Input from '../../components/ui/Input';
import Modal from '../../components/ui/Modal';
import { getClassColour } from '../../utils/classColours';
import { formatClasses } from '../../utils/dndRules';

export default function PlayerDashboard() {
  const navigate = useNavigate();
  const campaignsQuery = useCampaigns();
  const charactersQuery = useMyCharacters();
  const joinCampaign = useJoinCampaign();
  const deleteCharacter = useDeleteCharacter();
  const [inviteCode, setInviteCode] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<PlayerCharacter | null>(null);
  const [deleteConfirmName, setDeleteConfirmName] = useState('');
  const deleteHintId = useId();
  const campaigns = campaignsQuery.data;
  const characters = charactersQuery.data;
  const deleting = deleteCharacter.isPending;

  function handleJoin(e: FormEvent) {
    e.preventDefault();
    joinCampaign.mutate({ inviteCode: inviteCode.trim() }, { onSuccess: () => setInviteCode('') });
  }

  function openDeleteConfirmation(character: PlayerCharacter) {
    deleteCharacter.reset();
    setDeleteTarget(character);
    setDeleteConfirmName('');
  }

  function closeDeleteConfirmation() {
    setDeleteTarget(null);
    setDeleteConfirmName('');
  }

  function handleDelete() {
    if (!deleteTarget || deleteConfirmName !== deleteTarget.name) return;
    deleteCharacter.mutate(deleteTarget, {
      onSuccess: () => {
        setDeleteTarget(null);
        setDeleteConfirmName('');
      },
    });
  }

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="max-w-4xl mx-auto px-6 py-8 space-y-10">
        {/* Join Campaign */}
        <section>
          <h2 className="font-heading text-[13px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Join a Campaign</h2>
          <form onSubmit={handleJoin} className="flex gap-3">
            <input
              type="text"
              value={inviteCode}
              onChange={(e) => setInviteCode(e.target.value.toUpperCase())}
              required
              maxLength={8}
              placeholder="Enter invite code"
              className="flex-1 max-w-xs px-3 py-2 bg-card border border-rule font-body text-[16px] font-semibold text-ink tracking-wider placeholder-faint focus:border-muted focus:outline-none uppercase"
            />
            <button type="submit" disabled={joinCampaign.isPending} className="px-4 py-2 bg-ink text-card font-body text-[16px] font-semibold border border-ink hover:bg-muted hover:border-muted transition-colors disabled:opacity-50 disabled:cursor-not-allowed">
              Join
            </button>
          </form>
          {joinCampaign.error && (
            <p role="alert" className="text-debuff font-body text-[15px] mt-2">{apiErrorMessage(joinCampaign.error)}</p>
          )}
        </section>

        {/* Join Encounter */}
        <section>
          <h2 className="font-heading text-[13px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <Swords className="w-3.5 h-3.5" /> Encounters
          </h2>
          <button
            onClick={() => navigate('/player/encounter/join')}
            className="bg-card border border-rule hover:border-muted p-4 text-left transition-colors w-full max-w-xs"
            style={{ borderLeftWidth: '3px', borderLeftColor: 'var(--color-evo)' }}
          >
            <p className="font-heading text-[15px] font-semibold text-ink">Join Encounter</p>
            <p className="font-body text-[14px] font-semibold text-muted mt-0.5">Enter a session code from your DM</p>
          </button>
        </section>

        {/* My Campaigns */}
        <section>
          <h2 className="font-heading text-[13px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <Users className="w-3.5 h-3.5" /> My Campaigns
          </h2>
          {campaignsQuery.isPending ? (
            <p className="font-body text-[15px] text-muted">Loading...</p>
          ) : !campaigns ? (
            <p role="alert" className="font-body text-[15px] text-debuff">{apiErrorMessage(campaignsQuery.error)}</p>
          ) : campaigns.length === 0 ? (
            <p className="font-body text-[15px] text-faint">You haven't joined any campaigns yet. Ask your DM for an invite code.</p>
          ) : (
            <div className="space-y-2">
              {campaigns.map((c) => (
                <div key={c.id} className="bg-card border border-rule p-4 flex items-center justify-between">
                  <div>
                    <h3 className="font-heading text-[15px] font-semibold text-ink">{c.name}</h3>
                    <p className="font-body text-[14px] font-semibold text-muted">DM: {c.dmDisplayName} &middot; {c.members.length} members</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* My Characters */}
        <section>
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-heading text-[13px] font-semibold tracking-[0.1em] uppercase text-faint flex items-center gap-2">
              <ScrollText className="w-3.5 h-3.5" /> My Characters
            </h2>
            <button
              onClick={() => navigate('/player/characters/new')}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-ink text-card font-body text-[15px] font-semibold border border-ink hover:bg-muted hover:border-muted transition-colors"
            >
              <Plus className="w-3.5 h-3.5" /> New Character
            </button>
          </div>
          {charactersQuery.isPending ? (
            <p className="font-body text-[15px] text-muted">Loading...</p>
          ) : !characters ? (
            <p role="alert" className="font-body text-[15px] text-debuff">{apiErrorMessage(charactersQuery.error)}</p>
          ) : characters.length === 0 ? (
            <button
              onClick={() => navigate('/player/characters/new')}
              className="bg-page border border-dashed border-rule hover:border-muted p-6 flex flex-col items-center gap-1.5 w-full transition-colors"
            >
              <Plus className="w-6 h-6 text-faint" />
              <span className="font-heading text-[14px] font-semibold text-faint">Create New Character</span>
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
                          <span className="font-heading text-[15px] font-bold text-card">{c.name[0]}</span>
                        </div>
                        <div className="min-w-0 flex-1">
                          <h3 className="font-heading text-[16px] font-bold" style={{ color: clsColour }}>{c.name}</h3>
                          <p className="font-body text-[13px] font-semibold text-muted">
                            Level {c.level} {c.race} {formatClasses(c)}
                          </p>
                        </div>
                        <div className="text-right shrink-0">
                          <p className="font-heading text-[13px] font-semibold text-ink">HP {c.hpCurrent}/{c.hpMax}</p>
                          <p className="font-body text-[13px] text-faint">AC {c.armourClass}</p>
                        </div>
                      </div>
                    </button>
                    <IconButton
                      label="Delete character"
                      onClick={() => openDeleteConfirmation(c)}
                      className="px-3 text-faint hover:text-debuff transition-colors shrink-0"
                    >
                      <Trash2 className="w-4 h-4" />
                    </IconButton>
                  </div>
                );
              })}
              <button
                onClick={() => navigate('/player/characters/new')}
                className="bg-page border border-dashed border-rule hover:border-muted p-6 flex flex-col items-center justify-center gap-1.5 transition-colors"
              >
                <Plus className="w-5 h-5 text-faint" />
                <span className="font-heading text-[13px] font-semibold text-faint">Create New</span>
              </button>
            </div>
          )}
        </section>
      </main>

      {/* Delete Confirmation Modal */}
      {deleteTarget && (
        <Modal
          title="Delete Character"
          onClose={closeDeleteConfirmation}
          dismissible={!deleting}
          footer={(
            <>
              <Button variant="secondary" className="flex-1" onClick={closeDeleteConfirmation} disabled={deleting}>
                Cancel
              </Button>
              <Button variant="danger" className="flex-1" onClick={handleDelete} loading={deleting} disabled={deleteConfirmName !== deleteTarget.name}>
                {deleting ? 'Deleting...' : 'Delete'}
              </Button>
            </>
          )}
        >
          <p className="font-body text-[15px] font-semibold text-muted mb-1">
            Are you sure you want to delete <span className="text-ink font-semibold">{deleteTarget.name}</span>? This action cannot be undone.
          </p>
          <p id={deleteHintId} className="font-body text-[14px] text-faint mb-4">
            Type the character's name below to confirm.
          </p>
          <Input
            label="Character name"
            hideLabel
            aria-describedby={deleteHintId}
            type="text"
            value={deleteConfirmName}
            onChange={e => setDeleteConfirmName(e.target.value)}
            placeholder={deleteTarget.name}
            autoFocus
          />
          {deleteCharacter.error && (
            <p role="alert" className="text-debuff font-body text-[15px] mt-3">{apiErrorMessage(deleteCharacter.error)}</p>
          )}
        </Modal>
      )}
    </div>
  );
}
