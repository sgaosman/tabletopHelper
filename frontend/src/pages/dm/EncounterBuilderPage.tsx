import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { apiErrorMessage } from '../../api/errors';
import { useCampaigns } from '../../queries/campaigns';
import { useCampaignCharacters } from '../../queries/characters';
import {
  useAddParticipant, useCampaignEncounters, useCreateEncounter, useDeleteEncounter, useRemoveParticipant,
  useRenameParticipant, useReorderTie, useRollInitiatives, useSetInitiatives, useSetParticipantVisibility,
  useStartEncounter,
} from '../../queries/encounters';
import { useMonsterSearch } from '../../queries/monsters';
import type { PlayerCharacter } from '../../types/character';
import type { Monster } from '../../types/monster';
import type { Encounter, EncounterParticipant } from '../../types/encounter';
import { Plus, Trash2, Dice5, Search, Swords, ScrollText, Users, Play, Copy, Check } from 'lucide-react';
import NavBar from '../../components/common/NavBar';
import TieOrderArrows from '../../components/encounter/TieOrderArrows';
import VisibilityToggle from '../../components/encounter/VisibilityToggle';
import IconButton from '../../components/ui/IconButton';
import { getClassColour, getParticipantColour } from '../../utils/classColours';
import { formatClasses } from '../../utils/dndRules';

const STATUS_STYLES: Record<string, { text: string; bg: string; border: string }> = {
  PREPARING: { text: 'var(--color-trans)', bg: 'var(--color-trans-bg)', border: 'var(--color-trans-border)' },
  ACTIVE:    { text: 'var(--color-buff)', bg: 'var(--color-buff-bg)', border: 'var(--color-buff-border)' },
  PAUSED:    { text: 'var(--color-cls-fighter)', bg: 'var(--color-cls-fighter-bg)', border: 'var(--color-div-border)' },
  COMPLETED: { text: 'var(--color-faint)', bg: 'var(--color-page-alt)', border: 'var(--color-rule-light)' },
};

export default function EncounterBuilderPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const campaignsQuery = useCampaigns();
  // null until the DM picks a campaign; a DM with only one starts on it
  const [chosenCampaignId, setChosenCampaignId] = useState<string | null>(null);
  const [selectedEncounterId, setSelectedEncounterId] = useState<string | null>(null);

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const [monsterSearch, setMonsterSearch] = useState('');
  const [monsterSearchTerm, setMonsterSearchTerm] = useState('');
  const [monsterQuantities, setMonsterQuantities] = useState<Record<string, number>>({});

  const [copiedCode, setCopiedCode] = useState(false);
  const [editingInitiative, setEditingInitiative] = useState<Record<string, string>>({});
  const [editingName, setEditingName] = useState<Record<string, string>>({});
  // Why the last change to the selected encounter failed; the next change clears it
  const [actionError, setActionError] = useState<unknown>(null);

  const campaigns = (campaignsQuery.data ?? []).filter(c => c.dmUserId === user?.userId);
  const selectedCampaignId = chosenCampaignId ?? (campaigns.length === 1 ? campaigns[0].id : '');

  const encountersQuery = useCampaignEncounters(selectedCampaignId || undefined);
  const charactersQuery = useCampaignCharacters(selectedCampaignId || undefined);
  const encounters = encountersQuery.data ?? [];
  const campaignCharacters = charactersQuery.data ?? [];
  // Read from the list, which every change writes its result into
  const selectedEncounter = encounters.find(e => e.id === selectedEncounterId) ?? null;

  const createEncounter = useCreateEncounter();
  const deleteEncounter = useDeleteEncounter();
  const addParticipant = useAddParticipant();
  const removeParticipant = useRemoveParticipant();
  const renameParticipant = useRenameParticipant();
  const setParticipantVisibility = useSetParticipantVisibility();
  const setInitiatives = useSetInitiatives();
  const rollInitiatives = useRollInitiatives();
  const reorderTie = useReorderTie();
  const startEncounter = useStartEncounter();

  // Searches once typing pauses
  useEffect(() => {
    const timer = setTimeout(() => setMonsterSearchTerm(monsterSearch.trim()), 300);
    return () => clearTimeout(timer);
  }, [monsterSearch]);

  const monsterSearchQuery = useMonsterSearch(monsterSearchTerm);
  const hasMonsterSearch = monsterSearch.trim() !== '';
  const monsterResults = hasMonsterSearch ? monsterSearchQuery.data ?? [] : [];
  const searchingMonsters = hasMonsterSearch
    && (monsterSearch.trim() !== monsterSearchTerm || monsterSearchQuery.isFetching);

  function selectCampaign(campaignId: string) {
    setChosenCampaignId(campaignId);
    selectEncounter(null);
  }

  function selectEncounter(encounterId: string | null) {
    setSelectedEncounterId(encounterId);
    setActionError(null);
  }

  // Opening or closing the form forgets why the last attempt failed
  function toggleCreateForm(show: boolean) {
    createEncounter.reset();
    setShowCreateForm(show);
  }

  /** Runs a change to the selected encounter. Resolves with its result, or undefined once the error is shown. */
  async function attempt<T>(change: () => Promise<T>): Promise<T | undefined> {
    setActionError(null);
    try {
      return await change();
    } catch (err) {
      setActionError(err);
      return undefined;
    }
  }

  function handleCreate(e: FormEvent) {
    e.preventDefault();
    createEncounter.mutate({
      campaignId: selectedCampaignId,
      name: newName.trim(),
      description: newDesc.trim() || undefined,
    }, {
      onSuccess: encounter => {
        setNewName('');
        setNewDesc('');
        setShowCreateForm(false);
        selectEncounter(encounter.id);
      },
    });
  }

  async function handleAddMonster(monster: Monster) {
    if (!selectedEncounter) return;
    const qty = monsterQuantities[monster.id] || 1;
    await attempt(() => addParticipant.mutateAsync({
      encounter: selectedEncounter,
      request: {
        participantType: 'MONSTER',
        monsterId: monster.id,
        displayName: monster.name,
        quantity: qty,
      },
    }));
  }

  async function handleAddPlayer(character: PlayerCharacter) {
    if (!selectedEncounter) return;
    await attempt(() => addParticipant.mutateAsync({
      encounter: selectedEncounter,
      request: {
        participantType: 'PLAYER',
        characterId: character.id,
        displayName: character.name,
      },
    }));
  }

  async function handleRemoveParticipant(participantId: string) {
    if (!selectedEncounter) return;
    await attempt(() => removeParticipant.mutateAsync({ encounter: selectedEncounter, participantId }));
  }

  async function handleRollInitiative() {
    if (!selectedEncounter) return;
    const rolled = await attempt(() => rollInitiatives.mutateAsync({ encounter: selectedEncounter }));
    if (rolled) setEditingInitiative({});
  }

  async function handleSetInitiative(participantId: string, value: string) {
    if (!selectedEncounter) return;
    const parsed = parseInt(value);
    if (isNaN(parsed)) return;
    const saved = await attempt(() => setInitiatives.mutateAsync({
      encounter: selectedEncounter,
      initiatives: [{ participantId, initiative: parsed }],
    }));
    if (saved) setEditingInitiative(prev => { const next = { ...prev }; delete next[participantId]; return next; });
  }

  async function handleReorderTie(participantIds: string[]) {
    if (!selectedEncounter) return;
    await attempt(() => reorderTie.mutateAsync({ encounter: selectedEncounter, participantIds }));
  }

  async function handleRenameParticipant(participantId: string, newName: string) {
    if (!selectedEncounter || !newName.trim()) return;
    const saved = await attempt(() => renameParticipant.mutateAsync({
      encounter: selectedEncounter,
      participantId,
      displayName: newName.trim(),
    }));
    if (saved) setEditingName(prev => { const next = { ...prev }; delete next[participantId]; return next; });
  }

  // Rejects on failure, and VisibilityToggle shows why beside the switch
  async function handleSetVisibility(participantId: string, visible: boolean) {
    if (!selectedEncounter) return;
    await setParticipantVisibility.mutateAsync({ encounter: selectedEncounter, participantId, visible });
  }

  async function handleStartEncounter() {
    if (!selectedEncounter) return;
    const started = await attempt(() => startEncounter.mutateAsync({ encounter: selectedEncounter }));
    if (started) navigate(`/dm/encounter/${started.id}/session`);
  }

  async function handleDeleteEncounter(encounter: Encounter) {
    const deleted = await attempt(() => deleteEncounter.mutateAsync(encounter));
    if (deleted) setSelectedEncounterId(current => (current === encounter.id ? null : current));
  }

  function copySessionCode() {
    if (!selectedEncounter?.sessionCode) return;
    navigator.clipboard.writeText(selectedEncounter.sessionCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  }

  const addedCharacterIds = new Set(
    selectedEncounter?.participants
      .filter(p => p.characterId)
      .map(p => p.characterId) ?? []
  );

  const allHaveInitiative = selectedEncounter?.participants.every(p => p.initiative != null) ?? false;
  const hasParticipants = (selectedEncounter?.participants.length ?? 0) > 0;
  // The server takes the DM's order for a tie while the encounter is being prepared or fought (D163)
  const canReorderTies = selectedEncounter?.status === 'PREPARING' || selectedEncounter?.status === 'ACTIVE';

  if (campaignsQuery.isPending) {
    return <div className="min-h-screen bg-page flex items-center justify-center"><p className="font-body text-muted">Loading...</p></div>;
  }

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="px-6 py-8">
        <h1 className="font-heading text-[20px] font-semibold tracking-[0.02em] text-ink mb-6">Encounters</h1>

        {campaignsQuery.isLoadingError ? (
          <p role="alert" className="font-body text-debuff">{apiErrorMessage(campaignsQuery.error)}</p>
        ) : campaigns.length === 0 ? (
          <p className="font-body text-faint">Create a campaign first to build encounters.</p>
        ) : (
          <>
            <div className="mb-6">
              <select
                value={selectedCampaignId}
                onChange={e => selectCampaign(e.target.value)}
                className="px-4 py-2.5 bg-card border border-rule font-body text-[16px] font-semibold text-ink focus:outline-none focus:border-muted"
              >
                <option value="">Select a campaign...</option>
                {campaigns.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>

            {selectedCampaignId && (
              <div className="flex gap-6">
                {/* Encounter list */}
                <div className="w-80 flex-shrink-0">
                  <div className="flex items-center justify-between mb-4">
                    <h2 className="font-heading text-[16px] font-semibold text-ink">Encounters</h2>
                    <button
                      onClick={() => toggleCreateForm(true)}
                      className="flex items-center gap-1.5 px-3 py-1.5 bg-ink text-card font-body text-[15px] font-semibold transition-colors hover:opacity-90"
                    >
                      <Plus className="w-4 h-4" /> New
                    </button>
                  </div>

                  {showCreateForm && (
                    <form onSubmit={handleCreate} className="bg-card border border-rule p-4 mb-3">
                      <input
                        type="text"
                        value={newName}
                        onChange={e => setNewName(e.target.value)}
                        placeholder="Encounter name"
                        required
                        className="w-full px-3 py-2 bg-page border border-rule font-body text-[16px] font-semibold text-ink mb-2 placeholder-faint focus:outline-none focus:border-muted"
                      />
                      <textarea
                        value={newDesc}
                        onChange={e => setNewDesc(e.target.value)}
                        placeholder="Description (optional)"
                        rows={2}
                        className="w-full px-3 py-2 bg-page border border-rule font-body text-[16px] font-semibold text-ink mb-2 placeholder-faint focus:outline-none focus:border-muted resize-none"
                      />
                      {createEncounter.error && (
                        <p role="alert" className="font-body text-[15px] text-debuff mb-2">{apiErrorMessage(createEncounter.error)}</p>
                      )}
                      <div className="flex gap-2">
                        <button type="submit" disabled={createEncounter.isPending} className="px-3 py-1.5 bg-ink text-card font-body text-[15px] font-semibold disabled:opacity-50 disabled:cursor-not-allowed">Create</button>
                        <button type="button" onClick={() => toggleCreateForm(false)} className="px-3 py-1.5 bg-page border border-rule font-body text-[15px] font-semibold text-muted hover:border-muted">Cancel</button>
                      </div>
                    </form>
                  )}

                  <div className="space-y-2">
                    {encountersQuery.isPending && <p className="font-body text-[15px] text-muted">Loading...</p>}
                    {encountersQuery.isLoadingError && (
                      <p role="alert" className="font-body text-[15px] text-debuff">{apiErrorMessage(encountersQuery.error)}</p>
                    )}
                    {encounters.map(enc => {
                      const status = STATUS_STYLES[enc.status];
                      return (
                        <button
                          key={enc.id}
                          onClick={() => selectEncounter(enc.id)}
                          className={`w-full text-left bg-card border p-3 transition-colors ${
                            selectedEncounter?.id === enc.id ? 'border-ink' : 'border-rule hover:border-muted'
                          }`}
                        >
                          <div className="flex items-center justify-between mb-1">
                            <span className="font-heading text-[15px] font-semibold text-ink truncate">{enc.name}</span>
                            {status && (
                              <span
                                className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 border"
                                style={{ color: status.text, backgroundColor: status.bg, borderColor: status.border }}
                              >
                                {enc.status}
                              </span>
                            )}
                          </div>
                          <p className="font-body text-[13px] font-semibold text-faint">{enc.participants.length} participants</p>
                        </button>
                      );
                    })}
                    {encountersQuery.data?.length === 0 && !showCreateForm && (
                      <p className="font-body text-[15px] text-faint">No encounters yet.</p>
                    )}
                  </div>
                </div>

                {/* Encounter builder panel */}
                {selectedEncounter && (
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between mb-4">
                      <div>
                        <h2 className="font-heading text-[19px] font-semibold text-ink">{selectedEncounter.name}</h2>
                        {selectedEncounter.description && (
                          <p className="font-body text-[15px] font-semibold text-muted mt-1">{selectedEncounter.description}</p>
                        )}
                      </div>
                      <div className="flex items-center gap-2">
                        {selectedEncounter.sessionCode && (
                          <div className="flex items-center gap-2 bg-card border border-rule px-3 py-2">
                            <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Session</span>
                            <span className="font-heading text-[16px] font-bold tracking-wider text-ink">{selectedEncounter.sessionCode}</span>
                            <IconButton label={copiedCode ? 'Session code copied' : 'Copy session code'} onClick={copySessionCode} className="text-muted hover:text-ink">
                              {copiedCode ? <Check className="w-4 h-4" style={{ color: 'var(--color-buff)' }} /> : <Copy className="w-4 h-4" />}
                            </IconButton>
                          </div>
                        )}
                        {selectedEncounter.status === 'ACTIVE' && (
                          <button
                            onClick={() => navigate(`/dm/encounter/${selectedEncounter.id}/session`)}
                            className="px-4 py-2 font-body text-[15px] font-semibold text-card flex items-center gap-2"
                            style={{ backgroundColor: 'var(--color-buff)' }}
                          >
                            <Swords className="w-4 h-4" /> Go to Session
                          </button>
                        )}
                        {selectedEncounter.status === 'PREPARING' && (
                          <IconButton
                            label="Delete encounter"
                            onClick={() => handleDeleteEncounter(selectedEncounter)}
                            className="p-2 text-faint hover:text-debuff transition-colors"
                          >
                            <Trash2 className="w-4 h-4" />
                          </IconButton>
                        )}
                      </div>
                    </div>

                    {actionError != null && (
                      <p role="alert" className="font-body text-[15px] text-debuff mb-4">{apiErrorMessage(actionError)}</p>
                    )}

                    {selectedEncounter.status === 'PREPARING' && (
                      <>
                        {/* Add participants */}
                        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-6">
                          {/* Campaign PCs */}
                          <div className="bg-card border border-rule p-4">
                            <h3 className="font-heading text-[13px] font-semibold tracking-[0.08em] uppercase text-muted mb-3 flex items-center gap-2">
                              <Users className="w-4 h-4" style={{ color: 'var(--color-cls-wizard)' }} /> Campaign Characters
                            </h3>
                            {charactersQuery.isPending ? (
                              <p className="font-body text-[15px] text-muted">Loading...</p>
                            ) : charactersQuery.isLoadingError ? (
                              <p role="alert" className="font-body text-[15px] text-debuff">{apiErrorMessage(charactersQuery.error)}</p>
                            ) : campaignCharacters.length === 0 ? (
                              <p className="font-body text-[15px] text-faint">No characters in this campaign.</p>
                            ) : (
                              <div className="space-y-2">
                                {campaignCharacters.map(c => {
                                  const added = addedCharacterIds.has(c.id);
                                  // A character who died can't join until the DM revives them (D166)
                                  const unavailable = added || c.dead;
                                  const classClr = getClassColour(c.characterClass);
                                  return (
                                    <div key={c.id} className="flex items-center justify-between bg-page border border-rule-light px-3 py-2">
                                      <div>
                                        <span className="font-heading text-[14px] font-semibold" style={{ color: classClr }}>{c.name}</span>
                                        <span className="font-body text-[13px] font-semibold text-muted ml-2">
                                          Lv{c.level} {formatClasses(c)} — HP {c.hpCurrent}/{c.hpMax} AC {c.armourClass}
                                        </span>
                                      </div>
                                      <button
                                        onClick={() => handleAddPlayer(c)}
                                        disabled={unavailable}
                                        title={c.dead && !added ? 'Dead until you revive them on the campaign page' : undefined}
                                        className={`px-2.5 py-1 font-heading text-[11px] font-semibold tracking-[0.02em] border transition-colors ${
                                          unavailable
                                            ? 'bg-page-alt text-faint border-rule cursor-not-allowed'
                                            : 'text-card border-transparent'
                                        }`}
                                        style={!unavailable ? { backgroundColor: classClr } : undefined}
                                      >
                                        {added ? 'Added' : c.dead ? 'Dead' : 'Add'}
                                      </button>
                                    </div>
                                  );
                                })}
                              </div>
                            )}
                          </div>

                          {/* Add Monsters */}
                          <div className="bg-card border border-rule p-4">
                            <h3 className="font-heading text-[13px] font-semibold tracking-[0.08em] uppercase text-muted mb-3 flex items-center gap-2">
                              <Swords className="w-4 h-4 text-monster" /> Add Monsters
                            </h3>
                            <div className="relative mb-3">
                              <Search className="absolute left-3 top-2 text-faint" size={16} />
                              <input
                                type="text"
                                value={monsterSearch}
                                onChange={e => setMonsterSearch(e.target.value)}
                                placeholder="Search monsters (fuzzy)..."
                                className="w-full pl-9 pr-3 py-1.5 bg-page border border-rule font-body text-[15px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
                              />
                            </div>
                            <div className="space-y-2 max-h-60 overflow-y-auto">
                              {searchingMonsters && <p className="font-body text-[15px] text-muted">Searching...</p>}
                              {!searchingMonsters && hasMonsterSearch && monsterSearchQuery.isError && (
                                <p role="alert" className="font-body text-[15px] text-debuff">{apiErrorMessage(monsterSearchQuery.error)}</p>
                              )}
                              {!searchingMonsters && hasMonsterSearch && !monsterSearchQuery.isError && monsterResults.length === 0 && (
                                <p className="font-body text-[15px] text-faint">No monsters found.</p>
                              )}
                              {monsterResults.map(m => (
                                <div key={m.id} className="flex items-center justify-between bg-page border border-rule-light px-3 py-2">
                                  <div>
                                    <span className="font-heading text-[14px] font-semibold text-monster">{m.name}</span>
                                    <span className="font-body text-[13px] font-semibold text-muted ml-2">
                                      CR {m.challengeRating} — HP {m.hitPoints} AC {m.armourClass}
                                    </span>
                                  </div>
                                  <div className="flex items-center gap-2">
                                    <input
                                      type="number"
                                      min={1}
                                      max={20}
                                      value={monsterQuantities[m.id] || 1}
                                      onChange={e => setMonsterQuantities(q => ({ ...q, [m.id]: parseInt(e.target.value) || 1 }))}
                                      className="w-12 px-2 py-1 bg-card border border-rule font-body text-[15px] font-semibold text-ink text-center focus:outline-none focus:border-muted"
                                    />
                                    <button
                                      onClick={() => handleAddMonster(m)}
                                      className="px-2.5 py-1 text-card font-heading text-[11px] font-semibold tracking-[0.02em]"
                                      style={{ backgroundColor: 'var(--color-monster)' }}
                                    >
                                      Add
                                    </button>
                                  </div>
                                </div>
                              ))}
                            </div>
                          </div>
                        </div>

                        {/* Initiative + Start */}
                        {hasParticipants && (
                          <div className="flex items-center gap-3 mb-6">
                            <button
                              onClick={handleRollInitiative}
                              className="flex items-center gap-2 px-4 py-2 bg-page border border-rule font-body text-[15px] font-semibold text-ink hover:border-muted transition-colors"
                            >
                              <Dice5 className="w-4 h-4 text-muted" /> Roll All Initiative
                            </button>
                            <button
                              onClick={handleStartEncounter}
                              disabled={!allHaveInitiative}
                              className="flex items-center gap-2 px-4 py-2 font-body text-[15px] font-semibold text-card transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                              style={{ backgroundColor: 'var(--color-buff)' }}
                            >
                              <Play className="w-4 h-4" /> Start Encounter
                            </button>
                            {!allHaveInitiative && hasParticipants && (
                              <span className="font-body text-[14px] font-semibold" style={{ color: 'var(--color-hp-wounded)' }}>Set initiative for all participants before starting</span>
                            )}
                          </div>
                        )}
                      </>
                    )}

                    {/* Participants list */}
                    <div>
                      <h3 className="font-heading text-[13px] font-semibold tracking-[0.08em] uppercase text-muted mb-3 flex items-center gap-2">
                        <ScrollText className="w-4 h-4 text-faint" /> Participants ({selectedEncounter.participants.length})
                      </h3>
                      {selectedEncounter.participants.length === 0 ? (
                        <p className="font-body text-[15px] text-faint">Add characters and monsters above.</p>
                      ) : (
                        <div className="bg-card border border-rule overflow-hidden">
                          <table className="w-full">
                            <thead>
                              <tr className="border-b border-rule text-left">
                                <th className="px-4 py-3 font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint">Name</th>
                                <th className="px-4 py-3 font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint">Type</th>
                                <th className="px-4 py-3 font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint">HP</th>
                                <th className="px-4 py-3 font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint">AC</th>
                                <th className="px-4 py-3 font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint">Initiative</th>
                                {selectedEncounter.status === 'PREPARING' && <th className="px-4 py-3"></th>}
                              </tr>
                            </thead>
                            <tbody>
                              {selectedEncounter.participants.map((p: EncounterParticipant) => {
                                const isMonster = p.participantType === 'MONSTER';
                                const pColour = getParticipantColour(isMonster, p.characterClass);
                                return (
                                  <tr key={p.id} className="border-b border-rule-light">
                                    <td className="px-4 py-3">
                                      {selectedEncounter.status === 'PREPARING' ? (
                                        <input
                                          type="text"
                                          value={editingName[p.id] ?? p.displayName}
                                          onChange={e => setEditingName(prev => ({ ...prev, [p.id]: e.target.value }))}
                                          onBlur={e => {
                                            const val = e.target.value.trim();
                                            if (val && val !== p.displayName) {
                                              handleRenameParticipant(p.id, val);
                                            } else {
                                              setEditingName(prev => { const next = { ...prev }; delete next[p.id]; return next; });
                                            }
                                          }}
                                          onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
                                          className="bg-transparent font-heading text-[15px] font-semibold border-b border-transparent hover:border-muted focus:border-ink focus:outline-none px-0 py-0 w-full"
                                          style={{ color: pColour }}
                                        />
                                      ) : (
                                        <span className="font-heading text-[15px] font-semibold" style={{ color: pColour }}>{p.displayName}</span>
                                      )}
                                    </td>
                                    <td className="px-4 py-3">
                                      <div className="flex items-center gap-2">
                                        <span
                                          className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 border"
                                          style={isMonster
                                            ? { color: 'var(--color-monster)', backgroundColor: 'var(--color-monster-bg)', borderColor: 'var(--color-debuff-border)' }
                                            : { color: 'var(--color-cls-wizard)', backgroundColor: 'var(--color-cls-wizard-bg)', borderColor: 'var(--color-abj-border)' }
                                          }
                                        >
                                          {p.participantType}
                                        </span>
                                        {isMonster && (
                                          <VisibilityToggle participant={p} onToggle={visible => handleSetVisibility(p.id, visible)} />
                                        )}
                                      </div>
                                    </td>
                                    <td className="px-4 py-3 font-heading text-[14px] font-semibold text-ink">{p.hpCurrent}/{p.hpMax}</td>
                                    <td className="px-4 py-3 font-heading text-[14px] font-semibold text-ink">{p.armourClass}</td>
                                    <td className="px-4 py-3">
                                      <div className="flex items-center gap-1.5">
                                        {selectedEncounter.status === 'PREPARING' ? (
                                          <>
                                            <input
                                              type="number"
                                              value={editingInitiative[p.id] ?? (p.initiative != null ? String(p.initiative) : '')}
                                              onChange={e => setEditingInitiative(prev => ({ ...prev, [p.id]: e.target.value }))}
                                              onBlur={e => {
                                                const val = e.target.value;
                                                if (val && !isNaN(parseInt(val))) {
                                                  handleSetInitiative(p.id, val);
                                                } else {
                                                  setEditingInitiative(prev => { const next = { ...prev }; delete next[p.id]; return next; });
                                                }
                                              }}
                                              onKeyDown={e => {
                                                if (e.key === 'Enter') {
                                                  (e.target as HTMLInputElement).blur();
                                                }
                                              }}
                                              placeholder="—"
                                              className="w-16 px-2 py-1 bg-page border border-rule font-heading text-[16px] font-bold text-ink text-center focus:outline-none focus:border-muted placeholder-faint"
                                            />
                                            {p.initiativeModifier != null && (
                                              <span className="font-body text-[13px] font-semibold text-faint whitespace-nowrap">
                                                ({p.initiativeModifier >= 0 ? '+' : ''}{p.initiativeModifier})
                                              </span>
                                            )}
                                          </>
                                        ) : (
                                          <span className="font-heading text-[16px] font-bold text-ink">
                                            {p.initiative != null ? p.initiative : <span className="text-faint">—</span>}
                                            {p.initiativeModifier != null && (
                                              <span className="font-body text-[13px] font-semibold text-faint ml-1">
                                                ({p.initiativeModifier >= 0 ? '+' : ''}{p.initiativeModifier})
                                              </span>
                                            )}
                                          </span>
                                        )}
                                        {/* The DM's order among participants tied on initiative and DEX, as on the session page */}
                                        {canReorderTies && (
                                          <TieOrderArrows
                                            turns={selectedEncounter.turnOrder}
                                            index={selectedEncounter.turnOrder.findIndex(turn => turn.participantId === p.id)}
                                            name={p.displayName}
                                            onReorder={handleReorderTie}
                                          />
                                        )}
                                      </div>
                                    </td>
                                    {selectedEncounter.status === 'PREPARING' && (
                                      <td className="px-4 py-3">
                                        <IconButton
                                          label={`Remove ${p.displayName}`}
                                          onClick={() => handleRemoveParticipant(p.id)}
                                          className="text-faint hover:text-debuff transition-colors"
                                        >
                                          <Trash2 className="w-4 h-4" />
                                        </IconButton>
                                      </td>
                                    )}
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </main>
    </div>
  );
}
