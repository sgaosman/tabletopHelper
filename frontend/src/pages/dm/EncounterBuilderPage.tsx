import { useState, useEffect, useCallback, useRef, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { campaignApi } from '../../api/campaignApi';
import { characterApi } from '../../api/characterApi';
import { encounterApi } from '../../api/encounterApi';
import { fuzzySearchMonsters } from '../../api/monsterApi';
import type { Campaign } from '../../types/campaign';
import type { PlayerCharacter } from '../../types/character';
import type { Monster } from '../../types/monster';
import type { Encounter, EncounterParticipant } from '../../types/encounter';
import { Plus, Trash2, Dice5, Search, Swords, ScrollText, Users, Play, Copy, Check } from 'lucide-react';
import NavBar from '../../components/common/NavBar';
import { getClassColour, getParticipantColour } from '../../utils/classColours';

const STATUS_STYLES: Record<string, { text: string; bg: string; border: string }> = {
  PREPARING: { text: '#B45309', bg: '#FFFBEB', border: '#FDE68A' },
  ACTIVE:    { text: '#166534', bg: '#F0FDF4', border: '#BBF7D0' },
  PAUSED:    { text: '#92400E', bg: '#FFF7ED', border: '#FDE68A' },
  COMPLETED: { text: '#78716C', bg: '#F5F5F0', border: '#E7E5E4' },
};

export default function EncounterBuilderPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [selectedCampaignId, setSelectedCampaignId] = useState('');
  const [encounters, setEncounters] = useState<Encounter[]>([]);
  const [selectedEncounter, setSelectedEncounter] = useState<Encounter | null>(null);
  const [loading, setLoading] = useState(true);

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const [campaignCharacters, setCampaignCharacters] = useState<PlayerCharacter[]>([]);
  const [monsterSearch, setMonsterSearch] = useState('');
  const [monsterResults, setMonsterResults] = useState<Monster[]>([]);
  const [monsterQuantities, setMonsterQuantities] = useState<Record<string, number>>({});
  const [searchingMonsters, setSearchingMonsters] = useState(false);

  const [copiedCode, setCopiedCode] = useState(false);
  const [editingInitiative, setEditingInitiative] = useState<Record<string, string>>({});
  const [editingName, setEditingName] = useState<Record<string, string>>({});

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    campaignApi.getAll().then(res => {
      const dmCampaigns = res.data.filter(c => c.dmUserId === user?.userId);
      setCampaigns(dmCampaigns);
      if (dmCampaigns.length === 1) {
        setSelectedCampaignId(dmCampaigns[0].id);
      }
    }).finally(() => setLoading(false));
  }, [user]);

  const loadEncounters = useCallback(() => {
    if (!selectedCampaignId) return;
    encounterApi.getByCampaign(selectedCampaignId).then(res => setEncounters(res.data));
    characterApi.getByCampaign(selectedCampaignId).then(res => setCampaignCharacters(res.data));
  }, [selectedCampaignId]);

  useEffect(() => { loadEncounters(); }, [loadEncounters]);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!monsterSearch.trim()) {
      setMonsterResults([]);
      setSearchingMonsters(false);
      return;
    }
    setSearchingMonsters(true);
    debounceRef.current = setTimeout(async () => {
      try {
        const results = await fuzzySearchMonsters(monsterSearch.trim(), 10);
        setMonsterResults(results);
      } finally {
        setSearchingMonsters(false);
      }
    }, 300);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
  }, [monsterSearch]);

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    const res = await encounterApi.create({
      campaignId: selectedCampaignId,
      name: newName.trim(),
      description: newDesc.trim() || undefined,
    });
    setNewName('');
    setNewDesc('');
    setShowCreateForm(false);
    setSelectedEncounter(res.data);
    loadEncounters();
  }

  async function handleAddMonster(monster: Monster) {
    if (!selectedEncounter) return;
    const qty = monsterQuantities[monster.id] || 1;
    const res = await encounterApi.addParticipant(selectedEncounter.id, {
      participantType: 'MONSTER',
      monsterId: monster.id,
      displayName: monster.name,
      quantity: qty,
    });
    setSelectedEncounter(res.data);
    loadEncounters();
  }

  async function handleAddPlayer(character: PlayerCharacter) {
    if (!selectedEncounter) return;
    const res = await encounterApi.addParticipant(selectedEncounter.id, {
      participantType: 'PLAYER',
      characterId: character.id,
      displayName: character.name,
    });
    setSelectedEncounter(res.data);
    loadEncounters();
  }

  async function handleRemoveParticipant(participantId: string) {
    if (!selectedEncounter) return;
    const res = await encounterApi.removeParticipant(selectedEncounter.id, participantId);
    setSelectedEncounter(res.data);
    loadEncounters();
  }

  async function handleRollInitiative() {
    if (!selectedEncounter) return;
    const res = await encounterApi.rollInitiatives(selectedEncounter.id);
    setSelectedEncounter(res.data);
    setEditingInitiative({});
    loadEncounters();
  }

  async function handleSetInitiative(participantId: string, value: string) {
    if (!selectedEncounter) return;
    const parsed = parseInt(value);
    if (isNaN(parsed)) return;
    const res = await encounterApi.setInitiatives(selectedEncounter.id, {
      initiatives: [{ participantId, initiative: parsed }],
    });
    setSelectedEncounter(res.data);
    setEditingInitiative(prev => { const next = { ...prev }; delete next[participantId]; return next; });
    loadEncounters();
  }

  async function handleRenameParticipant(participantId: string, newName: string) {
    if (!selectedEncounter || !newName.trim()) return;
    const res = await encounterApi.renameParticipant(selectedEncounter.id, participantId, newName.trim());
    setSelectedEncounter(res.data);
    setEditingName(prev => { const next = { ...prev }; delete next[participantId]; return next; });
    loadEncounters();
  }

  async function handleStartEncounter() {
    if (!selectedEncounter) return;
    const res = await encounterApi.start(selectedEncounter.id);
    setSelectedEncounter(res.data);
    navigate(`/dm/encounter/${selectedEncounter.id}/session`);
  }

  async function handleDeleteEncounter(id: string) {
    await encounterApi.delete(id);
    if (selectedEncounter?.id === id) setSelectedEncounter(null);
    loadEncounters();
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

  if (loading) {
    return <div className="min-h-screen bg-page flex items-center justify-center"><p className="font-body text-muted">Loading...</p></div>;
  }

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="px-6 py-8">
        <h1 className="font-heading text-[20px] font-semibold tracking-[0.02em] text-ink mb-6">Encounters</h1>

        {campaigns.length === 0 ? (
          <p className="font-body text-faint">Create a campaign first to build encounters.</p>
        ) : (
          <>
            <div className="mb-6">
              <select
                value={selectedCampaignId}
                onChange={e => { setSelectedCampaignId(e.target.value); setSelectedEncounter(null); }}
                className="px-4 py-2.5 bg-card border border-rule font-body text-[14px] font-medium text-ink focus:outline-none focus:border-muted"
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
                    <h2 className="font-heading text-[14px] font-semibold text-ink">Encounters</h2>
                    <button
                      onClick={() => setShowCreateForm(true)}
                      className="flex items-center gap-1.5 px-3 py-1.5 bg-ink text-card font-body text-[13px] font-medium transition-colors hover:opacity-90"
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
                        className="w-full px-3 py-2 bg-page border border-rule font-body text-[14px] font-medium text-ink mb-2 placeholder-faint focus:outline-none focus:border-muted"
                      />
                      <textarea
                        value={newDesc}
                        onChange={e => setNewDesc(e.target.value)}
                        placeholder="Description (optional)"
                        rows={2}
                        className="w-full px-3 py-2 bg-page border border-rule font-body text-[14px] font-medium text-ink mb-2 placeholder-faint focus:outline-none focus:border-muted resize-none"
                      />
                      <div className="flex gap-2">
                        <button type="submit" className="px-3 py-1.5 bg-ink text-card font-body text-[13px] font-medium">Create</button>
                        <button type="button" onClick={() => setShowCreateForm(false)} className="px-3 py-1.5 bg-page border border-rule font-body text-[13px] font-medium text-muted hover:border-muted">Cancel</button>
                      </div>
                    </form>
                  )}

                  <div className="space-y-2">
                    {encounters.map(enc => {
                      const status = STATUS_STYLES[enc.status];
                      return (
                        <button
                          key={enc.id}
                          onClick={() => setSelectedEncounter(enc)}
                          className={`w-full text-left bg-card border p-3 transition-colors ${
                            selectedEncounter?.id === enc.id ? 'border-ink' : 'border-rule hover:border-muted'
                          }`}
                        >
                          <div className="flex items-center justify-between mb-1">
                            <span className="font-heading text-[13px] font-semibold text-ink truncate">{enc.name}</span>
                            {status && (
                              <span
                                className="font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 border"
                                style={{ color: status.text, backgroundColor: status.bg, borderColor: status.border }}
                              >
                                {enc.status}
                              </span>
                            )}
                          </div>
                          <p className="font-body text-[11px] font-medium text-faint">{enc.participants.length} participants</p>
                        </button>
                      );
                    })}
                    {encounters.length === 0 && !showCreateForm && (
                      <p className="font-body text-[13px] text-faint">No encounters yet.</p>
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
                          <p className="font-body text-[13px] font-medium text-muted mt-1">{selectedEncounter.description}</p>
                        )}
                      </div>
                      <div className="flex items-center gap-2">
                        {selectedEncounter.sessionCode && (
                          <div className="flex items-center gap-2 bg-card border border-rule px-3 py-2">
                            <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Session</span>
                            <span className="font-heading text-[14px] font-bold tracking-wider text-ink">{selectedEncounter.sessionCode}</span>
                            <button onClick={copySessionCode} className="text-muted hover:text-ink">
                              {copiedCode ? <Check className="w-4 h-4" style={{ color: '#166534' }} /> : <Copy className="w-4 h-4" />}
                            </button>
                          </div>
                        )}
                        {selectedEncounter.status === 'ACTIVE' && (
                          <button
                            onClick={() => navigate(`/dm/encounter/${selectedEncounter.id}/session`)}
                            className="px-4 py-2 font-body text-[13px] font-medium text-white flex items-center gap-2"
                            style={{ backgroundColor: '#166534' }}
                          >
                            <Swords className="w-4 h-4" /> Go to Session
                          </button>
                        )}
                        {selectedEncounter.status === 'PREPARING' && (
                          <button
                            onClick={() => handleDeleteEncounter(selectedEncounter.id)}
                            className="p-2 text-faint hover:text-debuff transition-colors"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        )}
                      </div>
                    </div>

                    {selectedEncounter.status === 'PREPARING' && (
                      <>
                        {/* Add participants */}
                        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-6">
                          {/* Campaign PCs */}
                          <div className="bg-card border border-rule p-4">
                            <h3 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-muted mb-3 flex items-center gap-2">
                              <Users className="w-4 h-4" style={{ color: '#4F46E5' }} /> Campaign Characters
                            </h3>
                            {campaignCharacters.length === 0 ? (
                              <p className="font-body text-[13px] text-faint">No characters in this campaign.</p>
                            ) : (
                              <div className="space-y-2">
                                {campaignCharacters.map(c => {
                                  const added = addedCharacterIds.has(c.id);
                                  const classClr = getClassColour(c.characterClass);
                                  return (
                                    <div key={c.id} className="flex items-center justify-between bg-page border border-rule-light px-3 py-2">
                                      <div>
                                        <span className="font-heading text-[12px] font-semibold" style={{ color: classClr }}>{c.name}</span>
                                        <span className="font-body text-[11px] font-medium text-muted ml-2">
                                          Lv{c.level} {c.characterClass} — HP {c.hpMax} AC {c.armourClass}
                                        </span>
                                      </div>
                                      <button
                                        onClick={() => handleAddPlayer(c)}
                                        disabled={added}
                                        className={`px-2.5 py-1 font-heading text-[9px] font-medium tracking-[0.02em] border transition-colors ${
                                          added
                                            ? 'bg-page-alt text-faint border-rule cursor-not-allowed'
                                            : 'text-white border-transparent'
                                        }`}
                                        style={!added ? { backgroundColor: classClr } : undefined}
                                      >
                                        {added ? 'Added' : 'Add'}
                                      </button>
                                    </div>
                                  );
                                })}
                              </div>
                            )}
                          </div>

                          {/* Add Monsters */}
                          <div className="bg-card border border-rule p-4">
                            <h3 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-muted mb-3 flex items-center gap-2">
                              <Swords className="w-4 h-4 text-monster" /> Add Monsters
                            </h3>
                            <div className="relative mb-3">
                              <Search className="absolute left-3 top-2 text-faint" size={16} />
                              <input
                                type="text"
                                value={monsterSearch}
                                onChange={e => setMonsterSearch(e.target.value)}
                                placeholder="Search monsters (fuzzy)..."
                                className="w-full pl-9 pr-3 py-1.5 bg-page border border-rule font-body text-[13px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
                              />
                            </div>
                            <div className="space-y-2 max-h-60 overflow-y-auto">
                              {searchingMonsters && <p className="font-body text-[13px] text-muted">Searching...</p>}
                              {!searchingMonsters && monsterSearch.trim() && monsterResults.length === 0 && (
                                <p className="font-body text-[13px] text-faint">No monsters found.</p>
                              )}
                              {monsterResults.map(m => (
                                <div key={m.id} className="flex items-center justify-between bg-page border border-rule-light px-3 py-2">
                                  <div>
                                    <span className="font-heading text-[12px] font-semibold text-monster">{m.name}</span>
                                    <span className="font-body text-[11px] font-medium text-muted ml-2">
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
                                      className="w-12 px-2 py-1 bg-card border border-rule font-body text-[13px] font-medium text-ink text-center focus:outline-none focus:border-muted"
                                    />
                                    <button
                                      onClick={() => handleAddMonster(m)}
                                      className="px-2.5 py-1 text-white font-heading text-[9px] font-medium tracking-[0.02em]"
                                      style={{ backgroundColor: '#991B1B' }}
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
                              className="flex items-center gap-2 px-4 py-2 bg-page border border-rule font-body text-[13px] font-medium text-ink hover:border-muted transition-colors"
                            >
                              <Dice5 className="w-4 h-4 text-muted" /> Roll All Initiative
                            </button>
                            <button
                              onClick={handleStartEncounter}
                              disabled={!allHaveInitiative}
                              className="flex items-center gap-2 px-4 py-2 font-body text-[13px] font-medium text-white transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                              style={{ backgroundColor: '#166534' }}
                            >
                              <Play className="w-4 h-4" /> Start Encounter
                            </button>
                            {!allHaveInitiative && hasParticipants && (
                              <span className="font-body text-[12px] font-medium" style={{ color: '#B45309' }}>Set initiative for all participants before starting</span>
                            )}
                          </div>
                        )}
                      </>
                    )}

                    {/* Participants list */}
                    <div>
                      <h3 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-muted mb-3 flex items-center gap-2">
                        <ScrollText className="w-4 h-4 text-faint" /> Participants ({selectedEncounter.participants.length})
                      </h3>
                      {selectedEncounter.participants.length === 0 ? (
                        <p className="font-body text-[13px] text-faint">Add characters and monsters above.</p>
                      ) : (
                        <div className="bg-card border border-rule overflow-hidden">
                          <table className="w-full">
                            <thead>
                              <tr className="border-b border-rule text-left">
                                <th className="px-4 py-3 font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint">Name</th>
                                <th className="px-4 py-3 font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint">Type</th>
                                <th className="px-4 py-3 font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint">HP</th>
                                <th className="px-4 py-3 font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint">AC</th>
                                <th className="px-4 py-3 font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint">Initiative</th>
                                {selectedEncounter.status === 'PREPARING' && <th className="px-4 py-3"></th>}
                              </tr>
                            </thead>
                            <tbody>
                              {selectedEncounter.participants.map((p: EncounterParticipant) => {
                                const isMonster = p.participantType === 'MONSTER';
                                const pColour = getParticipantColour(isMonster, (p as any).characterClass);
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
                                          className="bg-transparent font-heading text-[13px] font-semibold border-b border-transparent hover:border-muted focus:border-ink focus:outline-none px-0 py-0 w-full"
                                          style={{ color: pColour }}
                                        />
                                      ) : (
                                        <span className="font-heading text-[13px] font-semibold" style={{ color: pColour }}>{p.displayName}</span>
                                      )}
                                    </td>
                                    <td className="px-4 py-3">
                                      <span
                                        className="font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 border"
                                        style={isMonster
                                          ? { color: '#991B1B', backgroundColor: '#FEF2F2', borderColor: '#FECACA' }
                                          : { color: '#4F46E5', backgroundColor: '#EEF2FF', borderColor: '#C7D2FE' }
                                        }
                                      >
                                        {p.participantType}
                                      </span>
                                    </td>
                                    <td className="px-4 py-3 font-heading text-[12px] font-semibold text-ink">{p.hpCurrent}/{p.hpMax}</td>
                                    <td className="px-4 py-3 font-heading text-[12px] font-semibold text-ink">{p.armourClass}</td>
                                    <td className="px-4 py-3">
                                      {selectedEncounter.status === 'PREPARING' ? (
                                        <div className="flex items-center gap-1.5">
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
                                            className="w-16 px-2 py-1 bg-page border border-rule font-heading text-[14px] font-bold text-ink text-center focus:outline-none focus:border-muted placeholder-faint"
                                          />
                                          {p.initiativeModifier != null && (
                                            <span className="font-body text-[11px] font-medium text-faint whitespace-nowrap">
                                              ({p.initiativeModifier >= 0 ? '+' : ''}{p.initiativeModifier})
                                            </span>
                                          )}
                                        </div>
                                      ) : (
                                        <span className="font-heading text-[14px] font-bold text-ink">
                                          {p.initiative != null ? p.initiative : <span className="text-faint">—</span>}
                                          {p.initiativeModifier != null && (
                                            <span className="font-body text-[11px] font-medium text-faint ml-1">
                                              ({p.initiativeModifier >= 0 ? '+' : ''}{p.initiativeModifier})
                                            </span>
                                          )}
                                        </span>
                                      )}
                                    </td>
                                    {selectedEncounter.status === 'PREPARING' && (
                                      <td className="px-4 py-3">
                                        <button
                                          onClick={() => handleRemoveParticipant(p.id)}
                                          className="text-faint hover:text-debuff transition-colors"
                                        >
                                          <Trash2 className="w-4 h-4" />
                                        </button>
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
