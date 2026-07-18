import { useState, useEffect, useCallback, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { campaignApi } from '../../api/campaignApi';
import { characterApi } from '../../api/characterApi';
import { encounterApi } from '../../api/encounterApi';
import { searchMonsters } from '../../api/monsterApi';
import type { Campaign } from '../../types/campaign';
import type { PlayerCharacter } from '../../types/character';
import type { Monster } from '../../types/monster';
import type { Encounter, EncounterParticipant } from '../../types/encounter';
import { ArrowLeft, Plus, Trash2, Dice5, Search, Swords, ScrollText, Users, Play, Copy, Check } from 'lucide-react';

const STATUS_COLORS: Record<string, string> = {
  PREPARING: 'bg-yellow-900/50 text-yellow-400',
  ACTIVE: 'bg-green-900/50 text-green-400',
  PAUSED: 'bg-orange-900/50 text-orange-400',
  COMPLETED: 'bg-gray-800 text-gray-400',
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

  async function handleSearchMonsters() {
    if (!monsterSearch.trim()) return;
    setSearchingMonsters(true);
    try {
      const result = await searchMonsters({ name: monsterSearch, size: 10 });
      setMonsterResults(result.content);
    } finally {
      setSearchingMonsters(false);
    }
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
    return <div className="min-h-screen bg-gray-950 flex items-center justify-center"><p className="text-gray-400">Loading...</p></div>;
  }

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4">
        <button onClick={() => navigate('/dm')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors">
          <ArrowLeft className="w-4 h-4" /> Back to Dashboard
        </button>
      </header>

      <main className="max-w-6xl mx-auto px-6 py-8">
        <h1 className="text-3xl font-bold text-white mb-6">Encounters</h1>

        {/* Campaign selector */}
        {campaigns.length === 0 ? (
          <p className="text-gray-500">Create a campaign first to build encounters.</p>
        ) : (
          <>
            <div className="mb-6">
              <select
                value={selectedCampaignId}
                onChange={e => { setSelectedCampaignId(e.target.value); setSelectedEncounter(null); }}
                className="px-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white focus:outline-none focus:ring-2 focus:ring-orange-500"
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
                    <h2 className="text-lg font-semibold text-white">Encounters</h2>
                    <button
                      onClick={() => setShowCreateForm(true)}
                      className="flex items-center gap-1.5 px-3 py-1.5 bg-orange-600 hover:bg-orange-500 text-white rounded-lg text-sm transition-colors"
                    >
                      <Plus className="w-4 h-4" /> New
                    </button>
                  </div>

                  {showCreateForm && (
                    <form onSubmit={handleCreate} className="bg-gray-900 border border-gray-800 rounded-lg p-4 mb-3">
                      <input
                        type="text"
                        value={newName}
                        onChange={e => setNewName(e.target.value)}
                        placeholder="Encounter name"
                        required
                        className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white mb-2 placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-orange-500"
                      />
                      <textarea
                        value={newDesc}
                        onChange={e => setNewDesc(e.target.value)}
                        placeholder="Description (optional)"
                        rows={2}
                        className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white mb-2 placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-orange-500 resize-none"
                      />
                      <div className="flex gap-2">
                        <button type="submit" className="px-3 py-1.5 bg-orange-600 hover:bg-orange-500 text-white rounded-lg text-sm">Create</button>
                        <button type="button" onClick={() => setShowCreateForm(false)} className="px-3 py-1.5 bg-gray-800 hover:bg-gray-700 text-gray-400 rounded-lg text-sm">Cancel</button>
                      </div>
                    </form>
                  )}

                  <div className="space-y-2">
                    {encounters.map(enc => (
                      <button
                        key={enc.id}
                        onClick={() => setSelectedEncounter(enc)}
                        className={`w-full text-left bg-gray-900 border rounded-lg p-3 transition-colors ${
                          selectedEncounter?.id === enc.id ? 'border-orange-500' : 'border-gray-800 hover:border-gray-600'
                        }`}
                      >
                        <div className="flex items-center justify-between mb-1">
                          <span className="text-white font-medium truncate">{enc.name}</span>
                          <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[enc.status]}`}>
                            {enc.status}
                          </span>
                        </div>
                        <p className="text-gray-500 text-xs">{enc.participants.length} participants</p>
                      </button>
                    ))}
                    {encounters.length === 0 && !showCreateForm && (
                      <p className="text-gray-500 text-sm">No encounters yet.</p>
                    )}
                  </div>
                </div>

                {/* Encounter builder panel */}
                {selectedEncounter && (
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between mb-4">
                      <div>
                        <h2 className="text-2xl font-bold text-white">{selectedEncounter.name}</h2>
                        {selectedEncounter.description && (
                          <p className="text-gray-400 text-sm mt-1">{selectedEncounter.description}</p>
                        )}
                      </div>
                      <div className="flex items-center gap-2">
                        {selectedEncounter.sessionCode && (
                          <div className="flex items-center gap-2 bg-gray-900 border border-gray-800 px-3 py-2 rounded-lg">
                            <span className="text-gray-400 text-xs">Session:</span>
                            <span className="text-white font-mono font-bold tracking-wider">{selectedEncounter.sessionCode}</span>
                            <button onClick={copySessionCode} className="text-gray-400 hover:text-white">
                              {copiedCode ? <Check className="w-4 h-4 text-green-400" /> : <Copy className="w-4 h-4" />}
                            </button>
                          </div>
                        )}
                        {selectedEncounter.status === 'ACTIVE' && (
                          <button
                            onClick={() => navigate(`/dm/encounter/${selectedEncounter.id}/session`)}
                            className="px-4 py-2 bg-green-600 hover:bg-green-500 text-white rounded-lg text-sm flex items-center gap-2"
                          >
                            <Swords className="w-4 h-4" /> Go to Session
                          </button>
                        )}
                        {selectedEncounter.status === 'PREPARING' && (
                          <button
                            onClick={() => handleDeleteEncounter(selectedEncounter.id)}
                            className="p-2 text-gray-400 hover:text-red-400 transition-colors"
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
                          <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
                            <h3 className="text-white font-semibold mb-3 flex items-center gap-2">
                              <Users className="w-4 h-4 text-blue-400" /> Campaign Characters
                            </h3>
                            {campaignCharacters.length === 0 ? (
                              <p className="text-gray-500 text-sm">No characters in this campaign.</p>
                            ) : (
                              <div className="space-y-2">
                                {campaignCharacters.map(c => {
                                  const added = addedCharacterIds.has(c.id);
                                  return (
                                    <div key={c.id} className="flex items-center justify-between bg-gray-800 rounded-lg px-3 py-2">
                                      <div>
                                        <span className="text-white text-sm font-medium">{c.name}</span>
                                        <span className="text-gray-400 text-xs ml-2">
                                          Lv{c.level} {c.characterClass} — HP {c.hpMax} AC {c.armourClass}
                                        </span>
                                      </div>
                                      <button
                                        onClick={() => handleAddPlayer(c)}
                                        disabled={added}
                                        className={`px-2.5 py-1 rounded text-xs font-medium transition-colors ${
                                          added
                                            ? 'bg-gray-700 text-gray-500 cursor-not-allowed'
                                            : 'bg-blue-600 hover:bg-blue-500 text-white'
                                        }`}
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
                          <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
                            <h3 className="text-white font-semibold mb-3 flex items-center gap-2">
                              <Swords className="w-4 h-4 text-red-400" /> Add Monsters
                            </h3>
                            <div className="flex gap-2 mb-3">
                              <div className="relative flex-1">
                                <Search className="absolute left-3 top-2 text-gray-400" size={16} />
                                <input
                                  type="text"
                                  value={monsterSearch}
                                  onChange={e => setMonsterSearch(e.target.value)}
                                  onKeyDown={e => e.key === 'Enter' && handleSearchMonsters()}
                                  placeholder="Search monsters..."
                                  className="w-full pl-9 pr-3 py-1.5 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-red-500"
                                />
                              </div>
                              <button
                                onClick={handleSearchMonsters}
                                className="px-3 py-1.5 bg-red-600 hover:bg-red-500 text-white rounded-lg text-sm"
                              >
                                Search
                              </button>
                            </div>
                            <div className="space-y-2 max-h-60 overflow-y-auto">
                              {searchingMonsters && <p className="text-gray-400 text-sm">Searching...</p>}
                              {monsterResults.map(m => (
                                <div key={m.id} className="flex items-center justify-between bg-gray-800 rounded-lg px-3 py-2">
                                  <div>
                                    <span className="text-white text-sm font-medium">{m.name}</span>
                                    <span className="text-gray-400 text-xs ml-2">
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
                                      className="w-12 px-2 py-1 bg-gray-700 border border-gray-600 rounded text-white text-sm text-center"
                                    />
                                    <button
                                      onClick={() => handleAddMonster(m)}
                                      className="px-2.5 py-1 bg-red-600 hover:bg-red-500 text-white rounded text-xs font-medium"
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
                              className="flex items-center gap-2 px-4 py-2 bg-purple-600 hover:bg-purple-500 text-white rounded-lg text-sm transition-colors"
                            >
                              <Dice5 className="w-4 h-4" /> Roll All Initiative
                            </button>
                            <button
                              onClick={handleStartEncounter}
                              disabled={!allHaveInitiative}
                              className="flex items-center gap-2 px-4 py-2 bg-green-600 hover:bg-green-500 text-white rounded-lg text-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                              <Play className="w-4 h-4" /> Start Encounter
                            </button>
                            {!allHaveInitiative && hasParticipants && (
                              <span className="text-yellow-400 text-xs">Roll initiative before starting</span>
                            )}
                          </div>
                        )}
                      </>
                    )}

                    {/* Participants list */}
                    <div>
                      <h3 className="text-lg font-semibold text-white mb-3 flex items-center gap-2">
                        <ScrollText className="w-4 h-4 text-gray-400" /> Participants ({selectedEncounter.participants.length})
                      </h3>
                      {selectedEncounter.participants.length === 0 ? (
                        <p className="text-gray-500 text-sm">Add characters and monsters above.</p>
                      ) : (
                        <div className="bg-gray-900 border border-gray-800 rounded-lg overflow-hidden">
                          <table className="w-full">
                            <thead>
                              <tr className="border-b border-gray-700 text-left text-gray-400 text-xs uppercase tracking-wider">
                                <th className="px-4 py-3">Name</th>
                                <th className="px-4 py-3">Type</th>
                                <th className="px-4 py-3">HP</th>
                                <th className="px-4 py-3">AC</th>
                                <th className="px-4 py-3">Init</th>
                                {selectedEncounter.status === 'PREPARING' && <th className="px-4 py-3"></th>}
                              </tr>
                            </thead>
                            <tbody>
                              {selectedEncounter.participants.map((p: EncounterParticipant) => (
                                <tr key={p.id} className="border-b border-gray-700/50">
                                  <td className="px-4 py-3 text-white font-medium">{p.displayName}</td>
                                  <td className="px-4 py-3">
                                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                                      p.participantType === 'PLAYER'
                                        ? 'bg-blue-900/50 text-blue-400'
                                        : 'bg-red-900/50 text-red-400'
                                    }`}>
                                      {p.participantType}
                                    </span>
                                  </td>
                                  <td className="px-4 py-3 text-gray-300">{p.hpCurrent}/{p.hpMax}</td>
                                  <td className="px-4 py-3 text-gray-300">{p.armourClass}</td>
                                  <td className="px-4 py-3 text-gray-300">
                                    {p.initiative != null ? p.initiative : <span className="text-gray-600">—</span>}
                                    {p.initiativeModifier != null && (
                                      <span className="text-gray-500 text-xs ml-1">
                                        ({p.initiativeModifier >= 0 ? '+' : ''}{p.initiativeModifier})
                                      </span>
                                    )}
                                  </td>
                                  {selectedEncounter.status === 'PREPARING' && (
                                    <td className="px-4 py-3">
                                      <button
                                        onClick={() => handleRemoveParticipant(p.id)}
                                        className="text-gray-500 hover:text-red-400 transition-colors"
                                      >
                                        <Trash2 className="w-4 h-4" />
                                      </button>
                                    </td>
                                  )}
                                </tr>
                              ))}
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
