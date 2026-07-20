import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Shield, Swords, BookOpen, Backpack, Star, ScrollText, Heart, Moon, Sun, Lock, Search, X, Plus, Check, ChevronUp, ChevronDown } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import { campaignApi } from '../../api/campaignApi';
import { searchSpells, getFeats } from '../../api/referenceApi';
import SpellCard from '../../components/reference/SpellCard';
import LevelUpModal from '../../components/character/LevelUpModal';
import AsiModal from '../../components/character/AsiModal';
import SubclassModal from '../../components/character/SubclassModal';
import ExpertiseModal from '../../components/character/ExpertiseModal';
import FormattedDescription from '../../components/FormattedDescription';
import type { PlayerCharacter, CharacterUpdateRequest, LevelUpResponse } from '../../types/character';
import type { Campaign } from '../../types/campaign';
import type { Spell, Feat } from '../../types/reference';
import { CANTRIPS_KNOWN, SPELLS_KNOWN, THIRD_CASTER_CANTRIPS, THIRD_CASTER_SPELLS, THIRD_CASTER_SUBCLASSES, THIRD_CASTER_SPELL_LIST, getPreparedCount } from '../../utils/spellConstants';
import { parseFeatOptions } from '../../utils/featSpellParser';
import type { ParsedFeatOption } from '../../utils/featSpellParser';

const ABILITIES = ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'] as const;
const ABILITY_FROM_ABBR: Record<string, string> = {
  STR: 'strength', DEX: 'dexterity', CON: 'constitution',
  INT: 'intelligence', WIS: 'wisdom', CHA: 'charisma',
};
const ABILITY_ABBR: Record<string, string> = {
  strength: 'STR', dexterity: 'DEX', constitution: 'CON',
  intelligence: 'INT', wisdom: 'WIS', charisma: 'CHA',
};

const SKILLS: Array<{ name: string; ability: typeof ABILITIES[number] }> = [
  { name: 'Acrobatics', ability: 'dexterity' }, { name: 'Animal Handling', ability: 'wisdom' },
  { name: 'Arcana', ability: 'intelligence' }, { name: 'Athletics', ability: 'strength' },
  { name: 'Deception', ability: 'charisma' }, { name: 'History', ability: 'intelligence' },
  { name: 'Insight', ability: 'wisdom' }, { name: 'Intimidation', ability: 'charisma' },
  { name: 'Investigation', ability: 'intelligence' }, { name: 'Medicine', ability: 'wisdom' },
  { name: 'Nature', ability: 'intelligence' }, { name: 'Perception', ability: 'wisdom' },
  { name: 'Performance', ability: 'charisma' }, { name: 'Persuasion', ability: 'charisma' },
  { name: 'Religion', ability: 'intelligence' }, { name: 'Sleight of Hand', ability: 'dexterity' },
  { name: 'Stealth', ability: 'dexterity' }, { name: 'Survival', ability: 'wisdom' },
];

type Tab = 'Stats' | 'Actions' | 'Spells' | 'Inventory' | 'Features' | 'Journal';
const TABS: { key: Tab; label: string; icon: typeof Shield }[] = [
  { key: 'Stats', label: 'Stats', icon: Shield },
  { key: 'Actions', label: 'Actions', icon: Swords },
  { key: 'Spells', label: 'Spells', icon: BookOpen },
  { key: 'Inventory', label: 'Inventory', icon: Backpack },
  { key: 'Features', label: 'Features', icon: Star },
  { key: 'Journal', label: 'Journal', icon: ScrollText },
];

function abilityMod(score: number): number {
  return Math.floor((score - 10) / 2);
}

function formatMod(mod: number): string {
  return mod >= 0 ? `+${mod}` : `${mod}`;
}

function safeJsonParse<T>(json: unknown, fallback: T): T {
  if (json == null) return fallback;
  if (typeof json !== 'string') return json as T;
  try { return JSON.parse(json); } catch { return fallback; }
}

export default function CharacterSheetPage() {
  const { characterId } = useParams<{ characterId: string }>();
  const navigate = useNavigate();
  const [char, setChar] = useState<PlayerCharacter | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('Stats');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [restModal, setRestModal] = useState<'short' | 'long' | null>(null);
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [showLevelUp, setShowLevelUp] = useState(false);
  const [showAsi, setShowAsi] = useState(false);
  const [subclassPrompt, setSubclassPrompt] = useState<{ classId: string; className: string } | null>(null);
  const [levelUpBanner, setLevelUpBanner] = useState<string | null>(null);
  const [levelDownConfirm, setLevelDownConfirm] = useState(false);
  const [pendingLevelClass, setPendingLevelClass] = useState<{ classId: string; className: string; subclassRequired: boolean; expertiseAvailable: boolean; expertiseCount: number; spellSelectionNeeded: boolean; spellSelectionType?: string } | null>(null);
  const [showExpertise, setShowExpertise] = useState(false);
  const [pendingSpellManage, setPendingSpellManage] = useState<{ className: string; type: 'known' | 'prepared' } | null>(null);

  useEffect(() => {
    if (characterId && characterId !== 'new') {
      characterApi.getById(characterId).then(res => setChar(res.data));
    }
    campaignApi.getAll().then(res => setCampaigns(res.data)).catch(() => {});
  }, [characterId]);

  const saveField = useCallback(async (updates: CharacterUpdateRequest) => {
    if (!characterId) return;
    setSaving(true);
    setError('');
    try {
      const res = await characterApi.update(characterId, updates);
      setChar(res.data);
      setSuccess('Saved');
      setTimeout(() => setSuccess(''), 2000);
    } catch (err: any) {
      setError(err.response?.data?.error || 'Save failed');
    } finally {
      setSaving(false);
    }
  }, [characterId]);

  async function handleCampaignChange(value: string) {
    if (!characterId) return;
    try {
      const update: CharacterUpdateRequest = value
        ? { campaignId: value }
        : { clearCampaign: true };
      const res = await characterApi.update(characterId, update);
      setChar(res.data);
      setSuccess('Campaign updated');
      setTimeout(() => setSuccess(''), 2000);
    } catch (err: any) {
      setError(err.response?.data?.error || 'Failed to update campaign');
    }
  }

  const savingThrows = useMemo(() => safeJsonParse<string[]>(char?.savingThrowProficiencies, []), [char?.savingThrowProficiencies]);
  const skillProfs = useMemo(() => safeJsonParse<string[]>(char?.skillProficiencies, []), [char?.skillProficiencies]);
  const skillExpertises = useMemo(() => safeJsonParse<string[]>(char?.skillExpertises, []), [char?.skillExpertises]);
  const features = useMemo(() => safeJsonParse<Array<{ name: string; description: string; source?: string }>>(char?.features, []), [char?.features]);
  const equipment = useMemo(() => safeJsonParse<Array<{ name: string; quantity?: number; description?: string }>>(char?.equipment, []), [char?.equipment]);
  const currency = useMemo(() => safeJsonParse<{ cp: number; sp: number; ep: number; gp: number; pp: number }>(char?.currency, { cp: 0, sp: 0, ep: 0, gp: 0, pp: 0 }), [char?.currency]);
  const spellsKnown = useMemo(() => safeJsonParse<SpellEntry[]>(char?.spellsKnown, []), [char?.spellsKnown]);
  const spellSlots = useMemo(() => safeJsonParse<Record<string, { total: number; used: number }>>(char?.spellSlots, {}), [char?.spellSlots]);
  const resistances = useMemo(() => safeJsonParse<string[]>(char?.damageResistances, []), [char?.damageResistances]);
  const hitDiceMap = useMemo(() => safeJsonParse<Record<string, { total: number; remaining: number; faces: number }>>(char?.hitDiceMap, {}), [char?.hitDiceMap]);
  const featResources = useMemo(() => safeJsonParse<Array<{ featName: string; name: string; maxUses: number; currentUses: number; resetOn: string }>>(char?.featResources, []), [char?.featResources]);

  if (!char) {
    return (
      <div className="min-h-screen bg-gray-950 flex items-center justify-center">
        <p className="text-gray-400">Loading character...</p>
      </div>
    );
  }

  async function handleShortRest() {
    if (!char) return;
    const hdEntries = Object.entries(hitDiceMap);
    if (hdEntries.length === 0) { setRestModal(null); return; }

    const updated = { ...hitDiceMap };
    let hpGain = 0;
    for (const [cls, hd] of hdEntries) {
      if (hd.remaining > 0) {
        const roll = Math.ceil(hd.faces / 2) + 1;
        hpGain += roll + abilityMod(char.constitution);
        updated[cls] = { ...hd, remaining: hd.remaining - 1 };
      }
    }
    const newHp = Math.min(char.hpMax, char.hpCurrent + hpGain);
    await saveField({ hpCurrent: newHp, hitDiceMap: JSON.stringify(updated) });
    setRestModal(null);
  }

  async function handleLongRest() {
    if (!char) return;
    const updated = { ...hitDiceMap };
    for (const [cls, hd] of Object.entries(updated)) {
      const regain = Math.max(1, Math.floor(hd.total / 2));
      updated[cls] = { ...hd, remaining: Math.min(hd.total, hd.remaining + regain) };
    }
    const resetSlots: Record<string, { total: number; used: number }> = {};
    for (const [lvl, slot] of Object.entries(spellSlots)) {
      resetSlots[lvl] = { ...slot, used: 0 };
    }
    const resetResources = featResources.map(r =>
      r.resetOn === 'longRest' ? { ...r, currentUses: r.maxUses } : r
    );

    await saveField({
      hpCurrent: char.hpMax,
      hpTemp: 0,
      hitDiceMap: JSON.stringify(updated),
      spellSlots: Object.keys(resetSlots).length > 0 ? JSON.stringify(resetSlots) : undefined,
      featResources: resetResources.length > 0 ? JSON.stringify(resetResources) : undefined,
    });
    setRestModal(null);
  }

  function finishLevelUp(level: number) {
    if (pendingLevelClass?.spellSelectionNeeded) {
      const type = (pendingLevelClass.spellSelectionType === 'known' ? 'known' : 'prepared') as 'known' | 'prepared';
      setPendingSpellManage({ className: pendingLevelClass.className, type });
      setActiveTab('Spells');
    }
    setPendingLevelClass(null);
    setLevelUpBanner(`Level up to ${level} complete!`);
    setTimeout(() => setLevelUpBanner(null), 4000);
  }

  function handleLevelUpComplete(response: LevelUpResponse, leveledClassId: string, leveledClassName: string) {
    setChar(response.character);
    setShowLevelUp(false);
    const { pendingChoices } = response;
    const pending = { classId: leveledClassId, className: leveledClassName, subclassRequired: pendingChoices.subclassRequired, expertiseAvailable: pendingChoices.expertiseAvailable, expertiseCount: pendingChoices.expertiseCount, spellSelectionNeeded: pendingChoices.spellSelectionNeeded, spellSelectionType: pendingChoices.spellSelectionType };
    setPendingLevelClass(pending);
    if (pendingChoices.asiAvailable) {
      setShowAsi(true);
    } else if (pendingChoices.subclassRequired) {
      setSubclassPrompt({ classId: leveledClassId, className: leveledClassName });
    } else if (pendingChoices.expertiseAvailable) {
      setShowExpertise(true);
    } else {
      finishLevelUp(response.character.level);
    }
  }

  function handleAsiComplete(updated: PlayerCharacter) {
    setChar(updated);
    setShowAsi(false);
    if (pendingLevelClass?.subclassRequired) {
      setSubclassPrompt({ classId: pendingLevelClass.classId, className: pendingLevelClass.className });
    } else if (pendingLevelClass?.expertiseAvailable) {
      setShowExpertise(true);
    } else {
      finishLevelUp(updated.level);
    }
  }

  function handleSubclassComplete(updated: PlayerCharacter) {
    setChar(updated);
    setSubclassPrompt(null);

    let updatedPending = pendingLevelClass;
    if (updatedPending && !updatedPending.spellSelectionNeeded) {
      const mcEntries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(updated.multiclassEntries, []);
      const entry = mcEntries.find(e => e.className === updatedPending!.className);
      if (entry?.subclassName && THIRD_CASTER_SUBCLASSES.has(entry.subclassName)) {
        updatedPending = { ...updatedPending, spellSelectionNeeded: true, spellSelectionType: 'known' };
        setPendingLevelClass(updatedPending);
      }
    }

    if (updatedPending?.expertiseAvailable) {
      setShowExpertise(true);
    } else {
      if (updatedPending?.spellSelectionNeeded) {
        const type = (updatedPending.spellSelectionType === 'known' ? 'known' : 'prepared') as 'known' | 'prepared';
        setPendingSpellManage({ className: updatedPending.className, type });
        setActiveTab('Spells');
      }
      setPendingLevelClass(null);
      setLevelUpBanner(`Level up to ${updated.level} complete!`);
      setTimeout(() => setLevelUpBanner(null), 4000);
    }
  }

  async function handleExpertiseComplete(skills: string[]) {
    if (!char) return;
    try {
      const res = await characterApi.applyChoices(char.id, { expertiseSkills: skills });
      setChar(res.data);
    } catch { /* expertise is best-effort */ }
    setShowExpertise(false);
    finishLevelUp(char.level);
  }

  function showLevelBanner(response: LevelUpResponse) {
    const features = response.pendingChoices.newFeatures;
    const msg = features.length > 0
      ? `Level ${response.character.level}! New: ${features.join(', ')}`
      : `Level up to ${response.character.level} complete!`;
    setLevelUpBanner(msg);
    setTimeout(() => setLevelUpBanner(null), 4000);
  }

  async function handleLevelDown() {
    if (!characterId || !char) return;
    setLevelDownConfirm(false);
    setError('');
    try {
      const res = await characterApi.levelDown(characterId);
      setChar(res.data);
      setSuccess(`Reverted to level ${res.data.level}`);
      setTimeout(() => setSuccess(''), 3000);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Level down failed');
    }
  }

  return (
    <div className="min-h-screen bg-gray-950">
      {/* Header */}
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-4 py-3">
        <div className="max-w-5xl mx-auto flex items-center justify-between">
          <button onClick={() => navigate('/player')} className="flex items-center gap-1 text-gray-400 hover:text-white text-sm transition-colors">
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div className="text-center">
            <h1 className="text-white font-bold">{char.name}</h1>
            <p className="text-gray-400 text-xs">
              Level {char.level} {char.race}{' '}
              {(() => {
                const entries = safeJsonParse<Array<{ className: string; level: number }>>(char.multiclassEntries, []);
                if (entries.length > 1) {
                  return entries.map(e => `${e.className} ${e.level}`).join(' / ');
                }
                return char.characterClass + (char.subclass ? ` (${char.subclass})` : '');
              })()}
            </p>
          </div>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setLevelDownConfirm(true)}
              disabled={char.level <= 1}
              className="p-2 text-red-400 hover:bg-gray-800 rounded-lg transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
              title="Level Down"
            >
              <ChevronDown className="w-4 h-4" />
            </button>
            <button
              onClick={() => setShowLevelUp(true)}
              disabled={char.level >= 20}
              className="p-2 text-green-400 hover:bg-gray-800 rounded-lg transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
              title="Level Up"
            >
              <ChevronUp className="w-4 h-4" />
            </button>
            <div className="w-px h-5 bg-gray-700 mx-1" />
            <button onClick={() => setRestModal('short')} className="p-2 text-yellow-400 hover:bg-gray-800 rounded-lg transition-colors" title="Short Rest">
              <Moon className="w-4 h-4" />
            </button>
            <button onClick={() => setRestModal('long')} className="p-2 text-blue-400 hover:bg-gray-800 rounded-lg transition-colors" title="Long Rest">
              <Sun className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      {/* HP Bar */}
      <div className="max-w-5xl mx-auto px-4 py-3">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <Heart className="w-4 h-4 text-red-400" />
            <span className="text-white font-bold">{char.hpCurrent}/{char.hpMax}</span>
            {char.hpTemp > 0 && <span className="text-cyan-400 text-sm">+{char.hpTemp} temp</span>}
          </div>
          <div className="flex-1 h-2 bg-gray-800 rounded-full overflow-hidden">
            <div
              className={`h-full rounded-full transition-all ${
                char.hpCurrent / char.hpMax > 0.5 ? 'bg-green-500' :
                char.hpCurrent / char.hpMax > 0.25 ? 'bg-yellow-500' : 'bg-red-500'
              }`}
              style={{ width: `${Math.min(100, (char.hpCurrent / char.hpMax) * 100)}%` }}
            />
          </div>
          <div className="flex gap-3 text-sm">
            <span className="text-gray-400">AC <span className="text-white font-medium">{char.armourClass}</span></span>
            <span className="text-gray-400">Init <span className="text-white font-medium">{formatMod(char.initiativeBonus)}</span></span>
            <span className="text-gray-400">Speed <span className="text-white font-medium">{char.speed}ft</span></span>
          </div>
        </div>
        {/* Feat Resources & Temp HP */}
        {(featResources.length > 0) && (
          <div className="flex items-center gap-4 mt-2 flex-wrap">
            {featResources.map((r, i) => (
              <div key={i} className="flex items-center gap-2 bg-gray-800 rounded-lg px-3 py-1">
                <span className="text-amber-400 text-xs font-medium">{r.name}</span>
                <div className="flex gap-1">
                  {Array.from({ length: r.maxUses }).map((_, j) => (
                    <button key={j}
                      onClick={() => {
                        const updated = [...featResources];
                        const newUses = j < r.currentUses ? j : j + 1;
                        updated[i] = { ...r, currentUses: newUses };
                        saveField({ featResources: JSON.stringify(updated) });
                      }}
                      className={`w-4 h-4 rounded-full border transition-colors ${
                        j < r.currentUses
                          ? 'bg-amber-400 border-amber-400'
                          : 'bg-transparent border-gray-600'
                      }`}
                    />
                  ))}
                </div>
                <span className="text-xs text-gray-500">{r.currentUses}/{r.maxUses}</span>
              </div>
            ))}
            <button
              onClick={() => {
                const amount = prompt('Add temp HP:');
                if (amount && !isNaN(Number(amount)) && Number(amount) > 0) {
                  const newTemp = Math.max(char.hpTemp, Number(amount));
                  saveField({ hpTemp: newTemp });
                }
              }}
              className="text-xs text-cyan-400 hover:text-cyan-300 bg-gray-800 px-2 py-1 rounded-lg transition-colors"
            >+ Temp HP</button>
          </div>
        )}
      </div>

      {/* Tabs */}
      <div className="max-w-5xl mx-auto px-4">
        <div className="flex items-center border-b border-gray-800">
          <div className="flex gap-1 overflow-x-auto flex-1">
            {TABS.map(({ key, label, icon: Icon }) => (
              <button
                key={key}
                onClick={() => setActiveTab(key)}
                className={`flex items-center gap-1.5 px-3 py-2 text-sm font-medium transition-colors whitespace-nowrap ${
                  activeTab === key ? 'text-indigo-400 border-b-2 border-indigo-400' : 'text-gray-500 hover:text-gray-300'
                }`}
              >
                <Icon className="w-3.5 h-3.5" /> {label}
              </button>
            ))}
          </div>
          <select
            value={char.campaignId ?? ''}
            onChange={e => handleCampaignChange(e.target.value)}
            className="ml-2 px-2 py-1.5 bg-gray-800 border border-gray-700 rounded-md text-gray-300 text-xs focus:outline-none focus:ring-1 focus:ring-indigo-500 shrink-0"
          >
            <option value="">No campaign</option>
            {campaigns.map(c => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Error / Success */}
      <div className="max-w-5xl mx-auto px-4">
        {error && <div className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 mt-4 text-sm">{error}</div>}
        {success && <div className="bg-green-900/50 border border-green-700 text-green-300 rounded-lg p-3 mt-4 text-sm">{success}</div>}
      </div>

      {/* Tab Content */}
      <main className="max-w-5xl mx-auto px-4 py-6">
        {activeTab === 'Stats' && (
          <StatsTab char={char} savingThrows={savingThrows} skillProfs={skillProfs} skillExpertises={skillExpertises} resistances={resistances} />
        )}
        {activeTab === 'Actions' && (
          <ActionsTab char={char} features={features} />
        )}
        {activeTab === 'Spells' && (
          <SpellsTab char={char} spellsKnown={spellsKnown} spellSlots={spellSlots} saveField={saveField} pendingSpellManage={pendingSpellManage} onSpellManageHandled={() => setPendingSpellManage(null)} />
        )}
        {activeTab === 'Inventory' && (
          <InventoryTab equipment={equipment} currency={currency} char={char} saveField={saveField} />
        )}
        {activeTab === 'Features' && (
          <FeaturesTab features={features} char={char} />
        )}
        {activeTab === 'Journal' && (
          <JournalTab char={char} saveField={saveField} />
        )}
      </main>

      {/* Level Up Banner */}
      {levelUpBanner && (
        <div className="fixed top-4 left-1/2 -translate-x-1/2 z-50 bg-green-900/90 border border-green-700 text-green-200 px-6 py-3 rounded-lg shadow-lg text-sm font-medium animate-pulse">
          {levelUpBanner}
        </div>
      )}

      {/* Level Up Modal */}
      {showLevelUp && (
        <LevelUpModal
          character={char}
          onComplete={handleLevelUpComplete}
          onClose={() => setShowLevelUp(false)}
        />
      )}

      {/* ASI Modal */}
      {showAsi && (
        <AsiModal
          character={char}
          onComplete={handleAsiComplete}
          onClose={() => {
            setShowAsi(false);
            setPendingLevelClass(null);
            setLevelUpBanner(`Level up to ${char.level} complete!`);
            setTimeout(() => setLevelUpBanner(null), 4000);
          }}
        />
      )}

      {/* Subclass Modal */}
      {subclassPrompt && (
        <SubclassModal
          character={char}
          classId={subclassPrompt.classId}
          className={subclassPrompt.className}
          onComplete={handleSubclassComplete}
          onClose={() => {
            setSubclassPrompt(null);
            setPendingLevelClass(null);
            setLevelUpBanner(`Level up to ${char.level} complete!`);
            setTimeout(() => setLevelUpBanner(null), 4000);
          }}
        />
      )}

      {/* Expertise Modal */}
      {showExpertise && char && pendingLevelClass && (
        <ExpertiseModal
          character={char}
          count={pendingLevelClass.expertiseCount}
          onComplete={handleExpertiseComplete}
          onClose={() => { setShowExpertise(false); setPendingLevelClass(null); setLevelUpBanner(`Level up to ${char.level} complete!`); setTimeout(() => setLevelUpBanner(null), 4000); }}
        />
      )}

      {/* Level Down Confirmation */}
      {levelDownConfirm && (
        <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={() => setLevelDownConfirm(false)}>
          <div className="bg-gray-900 border border-gray-800 rounded-xl p-6 max-w-sm w-full" onClick={e => e.stopPropagation()}>
            <h3 className="text-white font-bold text-lg mb-2">Level Down</h3>
            <p className="text-gray-400 text-sm mb-4">
              Remove level {char.level}? This will reverse HP, features, and any ASI choices made at that level.
            </p>
            <div className="flex gap-3">
              <button onClick={() => setLevelDownConfirm(false)} className="flex-1 px-4 py-2 bg-gray-800 text-gray-300 rounded-lg text-sm hover:bg-gray-700 transition-colors">
                Cancel
              </button>
              <button onClick={handleLevelDown} className="flex-1 px-4 py-2 bg-red-600 text-white rounded-lg text-sm hover:bg-red-500 transition-colors">
                Remove Level
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Rest Modal */}
      {restModal && (
        <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={() => setRestModal(null)}>
          <div className="bg-gray-900 border border-gray-800 rounded-xl p-6 max-w-sm w-full" onClick={e => e.stopPropagation()}>
            <h3 className="text-white font-bold text-lg mb-2">
              {restModal === 'short' ? 'Short Rest' : 'Long Rest'}
            </h3>
            <p className="text-gray-400 text-sm mb-4">
              {restModal === 'short'
                ? 'Spend hit dice to regain hit points. You can spend one hit die per short rest.'
                : 'Regain all hit points, reset spell slots, and regain half your total hit dice (minimum 1).'}
            </p>
            <div className="flex gap-3">
              <button onClick={() => setRestModal(null)} className="flex-1 px-4 py-2 bg-gray-800 text-gray-300 rounded-lg text-sm hover:bg-gray-700 transition-colors">Cancel</button>
              <button
                onClick={restModal === 'short' ? handleShortRest : handleLongRest}
                className="flex-1 px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm hover:bg-indigo-500 transition-colors"
              >
                {restModal === 'short' ? 'Short Rest' : 'Long Rest'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function StatsTab({ char, savingThrows, skillProfs, skillExpertises, resistances }: {
  char: PlayerCharacter;
  savingThrows: string[];
  skillProfs: string[];
  skillExpertises: string[];
  resistances: string[];
}) {
  const armorProfs = useMemo(() => safeJsonParse<string[]>(char.armorProficiencies, []), [char.armorProficiencies]);
  const weaponProfs = useMemo(() => safeJsonParse<string[]>(char.weaponProficiencies, []), [char.weaponProficiencies]);
  const toolProfs = useMemo(() => safeJsonParse<string[]>(char.toolProficiencies, []), [char.toolProficiencies]);
  const languageProfs = useMemo(() => safeJsonParse<string[]>(char.languageProficiencies, []), [char.languageProficiencies]);

  const hasProficiencies = armorProfs.length > 0 || weaponProfs.length > 0 || toolProfs.length > 0 || languageProfs.length > 0;

  return (
    <div className="space-y-6">
      {/* Ability Scores */}
      <div className="grid grid-cols-6 gap-3">
        {ABILITIES.map(ability => {
          const score = char[ability];
          const mod = abilityMod(score);
          return (
            <div key={ability} className="bg-gray-900 border border-gray-800 rounded-xl p-3 text-center">
              <p className="text-gray-500 text-xs font-medium uppercase tracking-wider">{ABILITY_ABBR[ability]}</p>
              <p className="text-white text-2xl font-bold mt-1">{score}</p>
              <p className="text-indigo-400 text-sm font-medium">{formatMod(mod)}</p>
            </div>
          );
        })}
      </div>

      {/* Saving Throws */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Saving Throws</h3>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {ABILITIES.map(ability => {
            const mod = abilityMod(char[ability]);
            const isProficient = savingThrows.some(s => s.toLowerCase() === ABILITY_ABBR[ability].toLowerCase() || s.toLowerCase() === ability);
            const total = mod + (isProficient ? char.proficiencyBonus : 0);
            return (
              <div key={ability} className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${isProficient ? 'bg-green-400' : 'bg-gray-700'}`} />
                <span className="text-gray-400 text-sm">{ABILITY_ABBR[ability]}</span>
                <span className="text-white text-sm ml-auto font-medium">{formatMod(total)}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Skills */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Skills</h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
          {SKILLS.map(skill => {
            const mod = abilityMod(char[skill.ability]);
            const isProficient = skillProfs.some(s => s.toLowerCase() === skill.name.toLowerCase());
            const isExpert = skillExpertises.some(s => s.toLowerCase() === skill.name.toLowerCase());
            const bonus = mod + (isExpert ? char.proficiencyBonus * 2 : isProficient ? char.proficiencyBonus : 0);
            return (
              <div key={skill.name} className="flex items-center gap-2 py-0.5">
                {isExpert ? (
                  <span className="text-yellow-400 text-xs leading-none" style={{ fontSize: '10px' }}>&#9733;</span>
                ) : (
                  <div className={`w-2.5 h-2.5 rounded-full border-2 ${isProficient ? 'bg-green-400 border-green-400' : 'bg-transparent border-gray-600'}`} />
                )}
                <span className={`text-sm flex-1 ${isProficient || isExpert ? 'text-white font-medium' : 'text-gray-400'}`}>
                  {skill.name} <span className="text-gray-600 text-xs">({ABILITY_ABBR[skill.ability]})</span>
                </span>
                <span className={`text-sm font-medium ${isProficient || isExpert ? 'text-white' : 'text-gray-500'}`}>{formatMod(bonus)}</span>
              </div>
            );
          })}
        </div>
        <div className="mt-3 pt-3 border-t border-gray-800 flex gap-4 text-xs text-gray-500">
          <span className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 rounded-full bg-green-400" /> Proficient</span>
          <span className="flex items-center gap-1.5"><span className="text-yellow-400" style={{ fontSize: '10px' }}>&#9733;</span> Expertise</span>
        </div>
      </div>

      {/* Proficiency & Other */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <StatCard label="Proficiency" value={formatMod(char.proficiencyBonus)} />
        <StatCard label="Passive Perception" value={String(10 + abilityMod(char.wisdom) + (skillProfs.some(s => s.toLowerCase() === 'perception') ? char.proficiencyBonus : 0))} />
        <StatCard label="Hit Dice" value={char.hitDiceTotal || `${char.level}d?`} />
        <StatCard label="XP" value={String(char.experiencePoints)} />
      </div>

      {/* Proficiencies */}
      {hasProficiencies && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Proficiencies</h3>
          <div className="space-y-2">
            {armorProfs.length > 0 && (
              <div>
                <span className="text-gray-500 text-xs font-medium">Armor: </span>
                <span className="text-gray-300 text-sm">{armorProfs.map(a => a.charAt(0).toUpperCase() + a.slice(1)).join(', ')}</span>
              </div>
            )}
            {weaponProfs.length > 0 && (
              <div>
                <span className="text-gray-500 text-xs font-medium">Weapons: </span>
                <span className="text-gray-300 text-sm">{weaponProfs.map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(', ')}</span>
              </div>
            )}
            {toolProfs.length > 0 && (
              <div>
                <span className="text-gray-500 text-xs font-medium">Tools: </span>
                <span className="text-gray-300 text-sm">{toolProfs.map(t => t.charAt(0).toUpperCase() + t.slice(1)).join(', ')}</span>
              </div>
            )}
            {languageProfs.length > 0 && (
              <div>
                <span className="text-gray-500 text-xs font-medium">Languages: </span>
                <span className="text-gray-300 text-sm">{languageProfs.join(', ')}</span>
              </div>
            )}
          </div>
        </div>
      )}

      {resistances.length > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-2">Resistances & Immunities</h3>
          <div className="flex flex-wrap gap-2">
            {resistances.map((r, i) => (
              <span key={i} className="px-2 py-1 bg-gray-800 text-gray-300 rounded text-xs">{r}</span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function ActionsTab({ char, features }: {
  char: PlayerCharacter;
  features: Array<{ name: string; description: string; source?: string }>;
}) {
  const actionFeatures = features.filter(f =>
    f.description?.toLowerCase().includes('attack') ||
    f.description?.toLowerCase().includes('action') ||
    f.description?.toLowerCase().includes('damage') ||
    f.source?.toLowerCase().includes('class')
  );

  return (
    <div className="space-y-4">
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Combat Stats</h3>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <StatCard label="Attack Bonus" value={formatMod(abilityMod(char.strength) + char.proficiencyBonus)} sub="STR melee" />
          <StatCard label="Attack Bonus" value={formatMod(abilityMod(char.dexterity) + char.proficiencyBonus)} sub="DEX ranged/finesse" />
          {char.spellSaveDc && <StatCard label="Spell Save DC" value={String(char.spellSaveDc)} />}
          {char.spellAttackBonus != null && <StatCard label="Spell Attack" value={formatMod(char.spellAttackBonus)} />}
        </div>
      </div>

      {actionFeatures.length > 0 ? (
        <div className="space-y-3">
          {actionFeatures.map((f, i) => (
            <div key={i} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
              <h4 className="text-white font-medium text-sm">{f.name}</h4>
              <FormattedDescription text={f.description} className="mt-2" />
            </div>
          ))}
        </div>
      ) : (
        <p className="text-gray-500 text-sm">No combat actions configured. Add features in the Features tab.</p>
      )}
    </div>
  );
}

interface SpellEntry {
  name: string;
  level: number;
  source?: string;
  prepared?: boolean;
  alwaysPrepared?: boolean;
  atWill?: boolean;
  usesPerLongRest?: number;
  unlocksAtLevel?: number;
}

function SpellsTab({ char, spellsKnown, spellSlots, saveField, pendingSpellManage, onSpellManageHandled }: {
  char: PlayerCharacter;
  spellsKnown: SpellEntry[];
  spellSlots: Record<string, { total: number; used: number }>;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
  pendingSpellManage?: { className: string; type: 'known' | 'prepared' } | null;
  onSpellManageHandled?: () => void;
}) {
  const [detailSpell, setDetailSpell] = useState<Spell | null>(null);
  const [loadingSpell, setLoadingSpell] = useState(false);
  const [manageModal, setManageModal] = useState<{ type: 'prepared' | 'known' | 'spellbook' | 'remove-spellbook'; className: string; spellListClass?: string; classLevel?: number } | null>(null);
  const [showAddFeat, setShowAddFeat] = useState(false);
  const [confirmRemoveFeat, setConfirmRemoveFeat] = useState<string | null>(null);

  useEffect(() => {
    if (pendingSpellManage && !manageModal) {
      const mcEntries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(char.multiclassEntries, []);
      const entry = mcEntries.find(e => e.className === pendingSpellManage.className);
      const classLevel = entry ? entry.level : char.level;
      const isThirdCaster = entry?.subclassName && THIRD_CASTER_SUBCLASSES.has(entry.subclassName);
      const spellListClass = isThirdCaster ? THIRD_CASTER_SPELL_LIST[entry!.subclassName!] : pendingSpellManage.className;
      setManageModal({ type: pendingSpellManage.type, className: pendingSpellManage.className, spellListClass, classLevel });
      onSpellManageHandled?.();
    }
  }, [pendingSpellManage]);

  const subclassAlwaysPreparedGroups = useMemo(() => {
    if (!char.subclassAlwaysPreparedSpells) return [];
    const parsed = safeJsonParse<Record<string, Record<string, string[]>>>(char.subclassAlwaysPreparedSpells, {});
    const mcEntries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(char.multiclassEntries, []);
    const groups: Array<{ subclassName: string; spells: Array<{ name: string; classLevel: number }> }> = [];
    for (const [scName, levels] of Object.entries(parsed)) {
      const mcEntry = mcEntries.find(e => e.subclassName === scName);
      const classLevel = mcEntry ? mcEntry.level : char.level;
      const unlocked: Array<{ name: string; classLevel: number }> = [];
      for (const [lvlKey, spells] of Object.entries(levels)) {
        const lvl = parseInt(lvlKey);
        if (isNaN(lvl) || lvl > classLevel) continue;
        for (const name of spells) unlocked.push({ name, classLevel: lvl });
      }
      if (unlocked.length > 0) groups.push({ subclassName: scName, spells: unlocked });
    }
    return groups;
  }, [char.subclassAlwaysPreparedSpells, char.multiclassEntries, char.level]);

  const hasSpells = char.spellcastingAbility || spellsKnown.length > 0 || subclassAlwaysPreparedGroups.length > 0;
  if (!hasSpells) {
    return <p className="text-gray-500 text-sm">This character is not a spellcaster.</p>;
  }

  const defaultSource = `class:${char.characterClass || 'Unknown'}`;
  const taggedSpells: SpellEntry[] = spellsKnown.map(s => ({
    ...s,
    source: s.source || defaultSource,
  }));

  const sourceGroups = new Map<string, SpellEntry[]>();
  for (const s of taggedSpells) {
    const key = s.source!;
    const list = sourceGroups.get(key) || [];
    list.push(s);
    sourceGroups.set(key, list);
  }

  const classGroups: Array<{ source: string; className: string; spells: SpellEntry[] }> = [];
  const raceGroups: Array<{ source: string; raceName: string; spells: SpellEntry[] }> = [];
  const featGroups: Array<{ source: string; featName: string; spells: SpellEntry[] }> = [];

  for (const [source, spells] of sourceGroups) {
    if (source.startsWith('class:')) {
      classGroups.push({ source, className: source.slice(6), spells });
    } else if (source.startsWith('race:')) {
      raceGroups.push({ source, raceName: source.slice(5), spells });
    } else if (source.startsWith('feat:')) {
      featGroups.push({ source, featName: source.slice(5), spells });
    }
  }

  const SPELLCASTER_CLASSES = ['Bard', 'Cleric', 'Druid', 'Paladin', 'Ranger', 'Sorcerer', 'Warlock', 'Wizard', 'Artificer'];

  const mcEntries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(char.multiclassEntries, []);
  for (const entry of mcEntries) {
    const source = `class:${entry.className}`;
    if (classGroups.some(g => g.source === source)) continue;
    const isCasterClass = SPELLCASTER_CLASSES.includes(entry.className);
    const isThirdCaster = entry.subclassName && THIRD_CASTER_SUBCLASSES.has(entry.subclassName) && entry.level >= 3;
    if (isCasterClass || isThirdCaster) {
      classGroups.push({ source, className: entry.className, spells: [] });
    }
  }

  const regularSlots = Object.entries(spellSlots)
    .filter(([k]) => !k.startsWith('pact_'))
    .sort((a, b) => parseInt(a[0]) - parseInt(b[0]));
  const pactSlots = Object.entries(spellSlots)
    .filter(([k]) => k.startsWith('pact_'))
    .sort((a, b) => parseInt(a[0].replace('pact_', '')) - parseInt(b[0].replace('pact_', '')));

  async function useSlot(level: string) {
    const slot = spellSlots[level];
    if (!slot || slot.used >= slot.total) return;
    const updated = { ...spellSlots, [level]: { ...slot, used: slot.used + 1 } };
    await saveField({ spellSlots: JSON.stringify(updated) });
  }

  async function viewSpellDetail(spellName: string) {
    setLoadingSpell(true);
    try {
      const res = await searchSpells({ name: spellName, size: 1 });
      if (res.content.length > 0) setDetailSpell(res.content[0]);
    } catch { /* ignore */ }
    setLoadingSpell(false);
  }

  async function togglePrepared(spell: SpellEntry) {
    if (spell.alwaysPrepared || spell.level === 0) return;
    const updated = taggedSpells.map(s =>
      s.name === spell.name && s.source === spell.source
        ? { ...s, prepared: !s.prepared }
        : s
    );
    await saveField({ spellsKnown: JSON.stringify(updated) });
  }

  async function removeFeatSpells(source: string) {
    const updated = taggedSpells.filter(s => s.source !== source);
    await saveField({ spellsKnown: JSON.stringify(updated) });
  }

  function getClassCasterInfo(className: string) {
    const isPrepared = ['Cleric', 'Druid', 'Paladin', 'Wizard', 'Artificer'].includes(className);
    const mcEntry = mcEntries.find(e => e.className === className);
    const isThirdCaster = mcEntry?.subclassName ? THIRD_CASTER_SUBCLASSES.has(mcEntry.subclassName) : false;
    const isKnown = ['Bard', 'Ranger', 'Sorcerer', 'Warlock'].includes(className) || isThirdCaster;
    const isPact = className === 'Warlock';
    const thirdCasterSubclass = isThirdCaster ? mcEntry!.subclassName! : null;
    return { isPrepared, isKnown, isPact, isThirdCaster, thirdCasterSubclass };
  }

  function renderSpellRow(spell: SpellEntry, showPrepared: boolean) {
    return (
      <button
        key={`${spell.source}-${spell.name}`}
        onClick={() => viewSpellDetail(spell.name)}
        className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-gray-800 transition-colors w-full text-left group"
      >
        {showPrepared && spell.level > 0 && (
          spell.alwaysPrepared ? (
            <Lock className="w-3 h-3 text-amber-400 flex-shrink-0" />
          ) : (
            <button
              onClick={(e) => { e.stopPropagation(); togglePrepared(spell); }}
              className={`w-3 h-3 rounded-full flex-shrink-0 border ${
                spell.prepared ? 'bg-green-400 border-green-400' : 'bg-transparent border-gray-600'
              }`}
            />
          )
        )}
        <span className="text-white text-sm group-hover:text-indigo-300 transition-colors">{spell.name}</span>
        {spell.atWill && <span className="text-xs text-emerald-400 ml-auto">At will</span>}
        {spell.usesPerLongRest && <span className="text-xs text-amber-400 ml-auto">{spell.usesPerLongRest}/long rest</span>}
        {spell.unlocksAtLevel && spell.unlocksAtLevel > char.level && (
          <span className="text-xs text-gray-600 ml-auto">Lv {spell.unlocksAtLevel}</span>
        )}
      </button>
    );
  }

  function renderSlotButtons(slots: Array<[string, { total: number; used: number }]>, label: string) {
    if (slots.length === 0) return null;
    return (
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">{label}</h3>
        <div className="grid grid-cols-3 sm:grid-cols-5 gap-3">
          {slots.map(([level, slot]) => {
            const displayLevel = level.startsWith('pact_') ? level.replace('pact_', '') : level;
            return (
              <button
                key={level}
                onClick={() => useSlot(level)}
                className="bg-gray-800 rounded-lg p-3 text-center hover:bg-gray-700 transition-colors"
              >
                <p className="text-gray-500 text-xs">{label === 'Pact Slots' ? 'Pact' : ''} Lv {displayLevel}</p>
                <p className="text-white font-bold">{slot.total - slot.used}/{slot.total}</p>
              </button>
            );
          })}
        </div>
      </div>
    );
  }

  const CASTER_ABILITY: Record<string, string> = {
    Bard: 'CHA', Cleric: 'WIS', Druid: 'WIS', Paladin: 'CHA', Ranger: 'WIS',
    Sorcerer: 'CHA', Warlock: 'CHA', Wizard: 'INT', Artificer: 'INT',
  };
  const THIRD_CASTER_ABILITY_MAP: Record<string, string> = {
    'Eldritch Knight': 'INT', 'Arcane Trickster': 'INT',
  };

  const multiclassSpellStats = useMemo(() => {
    const entries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(char.multiclassEntries, []);
    if (entries.length <= 1) return [];
    const stats: Array<{ className: string; ability: string; saveDc: number; attackBonus: number }> = [];
    const seen = new Set<string>();
    for (const e of entries) {
      let ability = CASTER_ABILITY[e.className];
      if (!ability && e.subclassName) ability = THIRD_CASTER_ABILITY_MAP[e.subclassName];
      if (!ability || seen.has(ability)) continue;
      seen.add(ability);
      const abilityKey = ABILITY_FROM_ABBR[ability] as keyof PlayerCharacter;
      const score = (char[abilityKey] as number) || 10;
      const mod = abilityMod(score);
      stats.push({
        className: e.className,
        ability,
        saveDc: 8 + char.proficiencyBonus + mod,
        attackBonus: char.proficiencyBonus + mod,
      });
    }
    return stats.length > 1 ? stats : [];
  }, [char.multiclassEntries, char.proficiencyBonus, char.strength, char.dexterity, char.constitution, char.intelligence, char.wisdom, char.charisma]);

  return (
    <div className="space-y-6">
      {/* Spellcasting stats */}
      {multiclassSpellStats.length > 0 ? (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-1">Spellcasting by Class</h3>
          {multiclassSpellStats.map(s => (
            <div key={s.className} className="grid grid-cols-4 gap-2">
              <StatCard label={s.className} value={s.ability} />
              <StatCard label="Save DC" value={String(s.saveDc)} />
              <StatCard label="Attack" value={formatMod(s.attackBonus)} />
              <div />
            </div>
          ))}
        </div>
      ) : (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Spellcasting</h3>
          <div className="grid grid-cols-3 gap-3">
            <StatCard label="Ability" value={char.spellcastingAbility || '-'} />
            {char.spellSaveDc && <StatCard label="Save DC" value={String(char.spellSaveDc)} />}
            {char.spellAttackBonus != null && <StatCard label="Attack Bonus" value={formatMod(char.spellAttackBonus)} />}
          </div>
        </div>
      )}

      {/* Spell slots */}
      {renderSlotButtons(regularSlots, 'Spell Slots')}
      {renderSlotButtons(pactSlots, 'Pact Slots')}

      {/* Class spell boxes */}
      {classGroups.map(({ className, spells }) => {
        const info = getClassCasterInfo(className);
        const cantrips = spells.filter(s => s.level === 0);
        const leveled = spells.filter(s => s.level > 0);
        const prepared = leveled.filter(s => s.prepared || s.alwaysPrepared);
        const classEntry = mcEntries.find(e => e.className === className);
        const classLevel = classEntry ? classEntry.level : char.level;
        const abilityMod_ = char.spellcastingAbility
          ? abilityMod(char[ABILITY_FROM_ABBR[char.spellcastingAbility] as keyof PlayerCharacter] as number || 10)
          : 0;
        const prepLimit = info.isPrepared ? getPreparedCount(className, classLevel, abilityMod_) : 0;
        const knownLimit = info.isThirdCaster && info.thirdCasterSubclass
          ? (THIRD_CASTER_SPELLS[info.thirdCasterSubclass]?.[classLevel] ?? 0)
          : (SPELLS_KNOWN[className]?.[classLevel] ?? 0);
        const cantripLimit = info.isThirdCaster && info.thirdCasterSubclass
          ? (THIRD_CASTER_CANTRIPS[info.thirdCasterSubclass]?.[classLevel] ?? 0)
          : (CANTRIPS_KNOWN[className]?.[classLevel]);
        const spellListClass = info.thirdCasterSubclass ? THIRD_CASTER_SPELL_LIST[info.thirdCasterSubclass] : className;
        const displayName = info.thirdCasterSubclass ? `${className} (${info.thirdCasterSubclass})` : className;

        const isWizard = className === 'Wizard';

        return (
          <div key={className} className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-white font-semibold text-sm">{displayName} Spells</h3>
              <div className="flex items-center gap-2">
                {isWizard && (
                  <>
                    <button
                      onClick={() => setManageModal({ type: 'spellbook', className })}
                      className="text-xs text-green-400 hover:text-green-300 transition-colors"
                    >
                      + Spellbook
                    </button>
                    <button
                      onClick={() => setManageModal({ type: 'remove-spellbook', className })}
                      className="text-xs text-red-400 hover:text-red-300 transition-colors"
                    >
                      - Spellbook
                    </button>
                  </>
                )}
                <span className={`text-xs px-2 py-0.5 rounded-full ${
                  info.isPrepared ? 'bg-blue-900 text-blue-300' : 'bg-purple-900 text-purple-300'
                }`}>
                  {info.isPrepared ? 'Prepared' : 'Known'}
                </span>
              </div>
            </div>

            {(cantrips.length > 0 || (cantripLimit && cantripLimit > 0)) && (
              <div>
                <h4 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-1">
                  Cantrips ({cantrips.length}{cantripLimit ? `/${cantripLimit}` : ''})
                </h4>
                <div className="space-y-0.5">{cantrips.map(s => renderSpellRow(s, false))}</div>
              </div>
            )}

            {info.isPrepared && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <h4 className="text-gray-400 text-xs font-medium uppercase tracking-wider">
                    Prepared ({prepared.length}/{prepLimit})
                  </h4>
                  <button
                    onClick={() => setManageModal({ type: 'prepared', className })}
                    className="text-xs text-indigo-400 hover:text-indigo-300 transition-colors"
                  >
                    Change Prepared
                  </button>
                </div>
                {leveled.length > 0 ? (
                  <div className="space-y-0.5">{leveled.map(s => renderSpellRow(s, true))}</div>
                ) : (
                  <p className="text-gray-600 text-xs">No spells prepared.</p>
                )}
              </div>
            )}

            {info.isKnown && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <h4 className="text-gray-400 text-xs font-medium uppercase tracking-wider">
                    Known Spells ({leveled.length}{knownLimit ? `/${knownLimit}` : ''})
                  </h4>
                  <button
                    onClick={() => setManageModal({ type: 'known', className, spellListClass, classLevel })}
                    className="text-xs text-indigo-400 hover:text-indigo-300 transition-colors"
                  >
                    Manage Known
                  </button>
                </div>
                {leveled.length > 0 ? (
                  <div className="space-y-0.5">{leveled.map(s => renderSpellRow(s, false))}</div>
                ) : (
                  <p className="text-gray-600 text-xs">No spells known.</p>
                )}
              </div>
            )}
          </div>
        );
      })}

      {/* Subclass always-prepared spells */}
      {subclassAlwaysPreparedGroups.map(group => (
        <div key={group.subclassName} className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">{group.subclassName} Spells</h3>
            <span className="text-xs px-2 py-0.5 rounded-full bg-amber-900 text-amber-300">Always Prepared</span>
          </div>
          <div className="space-y-0.5">
            {group.spells.map(s => (
              <button
                key={s.name}
                onClick={() => viewSpellDetail(s.name)}
                className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-gray-800 transition-colors w-full text-left group"
              >
                <Lock className="w-3 h-3 text-amber-400 flex-shrink-0" />
                <span className="text-white text-sm group-hover:text-indigo-300 transition-colors">{s.name}</span>
                <span className="text-xs text-gray-600 ml-auto">Lv {s.classLevel}</span>
              </button>
            ))}
          </div>
        </div>
      ))}

      {/* Race spell boxes */}
      {raceGroups.map(({ raceName, spells }) => (
        <div key={raceName} className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">{raceName} Spells</h3>
            <span className="text-xs px-2 py-0.5 rounded-full bg-emerald-900 text-emerald-300">Innate</span>
          </div>
          <div className="space-y-0.5">{spells.map(s => renderSpellRow(s, false))}</div>
        </div>
      ))}

      {/* Feat spell boxes */}
      {featGroups.map(({ featName, source, spells }) => (
        <div key={featName} className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <h3 className="text-white font-semibold text-sm">{featName}</h3>
              <span className="text-xs px-2 py-0.5 rounded-full bg-amber-900 text-amber-300">Feat</span>
            </div>
            <button
              onClick={() => setConfirmRemoveFeat(confirmRemoveFeat === source ? null : source)}
              className="text-gray-600 hover:text-red-400 transition-colors"
              title="Remove feat spells"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
          {confirmRemoveFeat === source && (
            <div className="flex items-center gap-2 bg-red-900/20 border border-red-800/50 rounded px-3 py-2">
              <p className="text-red-300 text-xs flex-1">Remove all {featName} spells?</p>
              <button
                onClick={() => { removeFeatSpells(source); setConfirmRemoveFeat(null); }}
                className="px-2 py-1 bg-red-600 hover:bg-red-500 text-white text-xs rounded transition-colors"
              >
                Remove
              </button>
              <button
                onClick={() => setConfirmRemoveFeat(null)}
                className="px-2 py-1 text-gray-400 hover:text-white text-xs transition-colors"
              >
                Cancel
              </button>
            </div>
          )}
          <div className="space-y-0.5">{spells.map(s => renderSpellRow(s, false))}</div>
        </div>
      ))}

      {/* Add Feat button */}
      <button
        onClick={() => setShowAddFeat(true)}
        className="w-full py-2 border border-dashed border-amber-800/50 rounded-lg text-amber-400 text-sm hover:bg-amber-900/20 transition-colors flex items-center justify-center gap-2"
      >
        <Plus className="w-4 h-4" /> Add Feat Spells
      </button>

      {taggedSpells.length === 0 && classGroups.every(g => g.spells.length === 0) && (
        <p className="text-gray-500 text-sm">No spells yet. Use the Change Prepared or Manage Known button to add spells.</p>
      )}

      {/* Spell Detail Modal */}
      {(detailSpell || loadingSpell) && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4" onClick={() => { setDetailSpell(null); setLoadingSpell(false); }}>
          <div className="max-w-2xl w-full max-h-[80vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
            {loadingSpell ? (
              <div className="bg-gray-900 rounded-lg p-8 text-center">
                <p className="text-gray-400">Loading spell...</p>
              </div>
            ) : detailSpell ? (
              <div className="relative">
                <button onClick={() => setDetailSpell(null)} className="absolute top-2 right-2 z-10 text-gray-400 hover:text-white">
                  <X className="w-5 h-5" />
                </button>
                <SpellCard spell={detailSpell} />
              </div>
            ) : null}
          </div>
        </div>
      )}

      {/* Manage Spells Modal */}
      {manageModal && (
        <ManageSpellsModal
          char={char}
          type={manageModal.type}
          className={manageModal.className}
          spellListClass={manageModal.spellListClass}
          classLevel={manageModal.classLevel}
          currentSpells={taggedSpells}
          saveField={saveField}
          onClose={() => setManageModal(null)}
        />
      )}

      {/* Add Feat Modal */}
      {showAddFeat && (
        <AddFeatModal
          currentSpells={taggedSpells}
          saveField={saveField}
          onClose={() => setShowAddFeat(false)}
        />
      )}
    </div>
  );
}

function ManageSpellsModal({ char, type, className, spellListClass, classLevel, currentSpells, saveField, onClose }: {
  char: PlayerCharacter;
  type: 'prepared' | 'known' | 'spellbook' | 'remove-spellbook';
  className: string;
  spellListClass?: string;
  classLevel?: number;
  currentSpells: SpellEntry[];
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
  onClose: () => void;
}) {
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<Spell[]>([]);
  const [searching, setSearching] = useState(false);
  const [selectedLevel, setSelectedLevel] = useState<number | ''>('');
  const [localSpells, setLocalSpells] = useState<SpellEntry[]>(currentSpells);
  const [saving, setSaving] = useState(false);

  const source = `class:${className}`;
  const classSpells = localSpells.filter(s => s.source === source);
  const cantrips = classSpells.filter(s => s.level === 0);
  const leveled = classSpells.filter(s => s.level > 0);
  const preparedCount = leveled.filter(s => s.prepared || s.alwaysPrepared).length;
  const abilityMod_ = char.spellcastingAbility
    ? abilityMod(char[ABILITY_FROM_ABBR[char.spellcastingAbility] as keyof PlayerCharacter] as number || 10)
    : 0;
  const prepLimit = getPreparedCount(className, char.level, abilityMod_);
  const effectiveClassLevel = classLevel ?? char.level;
  const thirdCasterSub = spellListClass && spellListClass !== className
    ? Object.entries(THIRD_CASTER_SPELL_LIST).find(([, v]) => v === spellListClass)?.[0] ?? null
    : null;
  const knownLimit = thirdCasterSub
    ? (THIRD_CASTER_SPELLS[thirdCasterSub]?.[effectiveClassLevel] ?? 0)
    : (SPELLS_KNOWN[className]?.[effectiveClassLevel] ?? 0);

  const isWizardPrepared = type === 'prepared' && className === 'Wizard';
  const isSpellbookMode = type === 'spellbook';
  const isRemoveSpellbook = type === 'remove-spellbook';
  const needsApiSearch = !isWizardPrepared && !isRemoveSpellbook;

  const slots = safeJsonParse<Record<string, { total: number; used: number }>>(char.spellSlots, {});
  const maxSlotLevel = Math.max(0, ...Object.keys(slots).map(k => {
    if (k.startsWith('pact_')) return parseInt(k.replace('pact_', ''));
    const n = parseInt(k);
    return isNaN(n) ? 0 : n;
  }));
  const canSwapCantrips = className === 'Wizard' && char.level >= 3;
  const minSpellLevel = canSwapCantrips ? 0 : 1;
  const validLevels = Array.from({ length: maxSlotLevel - minSpellLevel + 1 }, (_, i) => i + minSpellLevel);

  const spellbookSpells = useMemo(() => {
    if (!isWizardPrepared && !isRemoveSpellbook) return [];
    return classSpells.filter(s => s.level > 0).map(s => ({
      id: `spellbook-${s.name}`,
      name: s.name,
      level: s.level,
      school: null, castingTime: null, rangeDistance: null, components: null,
      duration: null, concentration: false, ritual: false, description: null,
      higherLevels: null, classes: null, damageType: null, damageDice: null,
      saveAbility: null, source: null,
    } as Spell));
  }, [isWizardPrepared, isRemoveSpellbook, classSpells]);

  const filteredSpellbook = useMemo(() => {
    let list = spellbookSpells;
    if (searchQuery.trim()) {
      const q = searchQuery.trim().toLowerCase();
      list = list.filter(s => s.name.toLowerCase().includes(q));
    }
    if (selectedLevel !== '') {
      list = list.filter(s => s.level === selectedLevel);
    }
    return list;
  }, [spellbookSpells, searchQuery, selectedLevel]);

  useEffect(() => {
    if (needsApiSearch) doSearch();
  }, [selectedLevel]);

  async function doSearch() {
    if (!needsApiSearch) return;
    setSearching(true);
    try {
      const params: Record<string, unknown> = { className: spellListClass || className, size: 50 };
      if (searchQuery.trim()) params.name = searchQuery.trim();
      if (selectedLevel !== '') {
        params.level = selectedLevel;
      } else {
        params.level = validLevels.join(',');
      }
      const res = await searchSpells(params as any);
      setSearchResults(res.content.filter(s => s.level >= minSpellLevel && s.level <= maxSlotLevel));
    } catch { /* ignore */ }
    setSearching(false);
  }

  function isAdded(spell: Spell): boolean {
    return classSpells.some(s => s.name === spell.name);
  }

  function isPreparedSpell(spell: Spell): boolean {
    return classSpells.some(s => s.name === spell.name && (s.prepared || s.alwaysPrepared));
  }

  function toggleSpell(spell: Spell) {
    if (isSpellbookMode) {
      const exists = localSpells.some(s => s.name === spell.name && s.source === source);
      if (exists) {
        setLocalSpells(localSpells.filter(s => !(s.name === spell.name && s.source === source)));
      } else {
        setLocalSpells([...localSpells, { name: spell.name, level: spell.level, source, prepared: false }]);
      }
    } else if (isRemoveSpellbook) {
      setLocalSpells(localSpells.filter(s => !(s.name === spell.name && s.source === source)));
    } else if (type === 'prepared') {
      const existing = localSpells.find(s => s.name === spell.name && s.source === source);
      if (existing) {
        if (existing.alwaysPrepared) return;
        if (existing.prepared) {
          setLocalSpells(localSpells.map(s =>
            s.name === spell.name && s.source === source ? { ...s, prepared: false } : s
          ));
        } else {
          if (preparedCount >= prepLimit) return;
          setLocalSpells(localSpells.map(s =>
            s.name === spell.name && s.source === source ? { ...s, prepared: true } : s
          ));
        }
      } else {
        if (preparedCount >= prepLimit) return;
        setLocalSpells([...localSpells, { name: spell.name, level: spell.level, source, prepared: true }]);
      }
    } else {
      const exists = localSpells.some(s => s.name === spell.name && s.source === source);
      if (exists) {
        setLocalSpells(localSpells.filter(s => !(s.name === spell.name && s.source === source)));
      } else {
        if (knownLimit > 0 && leveled.length >= knownLimit) return;
        setLocalSpells([...localSpells, { name: spell.name, level: spell.level, source }]);
      }
    }
  }

  async function handleSave() {
    setSaving(true);
    await saveField({ spellsKnown: JSON.stringify(localSpells) });
    setSaving(false);
    onClose();
  }

  const displayResults = needsApiSearch ? searchResults : filteredSpellbook;
  const modalTitle = isSpellbookMode
    ? 'Add to Spellbook'
    : isRemoveSpellbook
      ? 'Remove from Spellbook'
      : isWizardPrepared
        ? 'Prepare from Spellbook'
        : type === 'prepared'
          ? 'Change Prepared Spells'
          : 'Manage Known Spells';
  const modalSubtitle = isSpellbookMode
    ? `${leveled.length} spells in spellbook`
    : isRemoveSpellbook
      ? `${leveled.length} spells in spellbook`
      : type === 'prepared'
        ? `${preparedCount}/${prepLimit} prepared`
        : `${leveled.length}${knownLimit ? `/${knownLimit}` : ''} known`;

  return (
    <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
      <div className="bg-gray-900 border border-gray-700 rounded-lg w-full max-w-lg max-h-[85vh] flex flex-col">
        {/* Header */}
        <div className="px-4 py-3 border-b border-gray-800 flex items-center justify-between">
          <div>
            <h2 className="text-white font-semibold">{modalTitle}</h2>
            <p className="text-xs text-gray-400 mt-0.5">{modalSubtitle}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
        </div>

        {/* Search bar */}
        <div className="px-4 py-3 border-b border-gray-800 space-y-2">
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && needsApiSearch && doSearch()}
                placeholder={needsApiSearch ? "Search spells..." : "Filter spellbook..."}
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            {(isWizardPrepared || isRemoveSpellbook) && (
              <select
                value={selectedLevel}
                onChange={e => setSelectedLevel(e.target.value === '' ? '' : parseInt(e.target.value))}
                className="bg-gray-800 border border-gray-700 rounded-md px-2 py-1.5 text-sm text-white focus:outline-none focus:border-indigo-500"
              >
                <option value="">All Levels</option>
                {validLevels.filter(l => l > 0).map(l => (
                  <option key={l} value={l}>Level {l}</option>
                ))}
              </select>
            )}
            {needsApiSearch && (
              <>
                <select
                  value={selectedLevel}
                  onChange={e => setSelectedLevel(e.target.value === '' ? '' : parseInt(e.target.value))}
                  className="bg-gray-800 border border-gray-700 rounded-md px-2 py-1.5 text-sm text-white focus:outline-none focus:border-indigo-500"
                >
                  <option value="">All Levels</option>
                  {validLevels.map(l => (
                    <option key={l} value={l}>{l === 0 ? 'Cantrips' : `Level ${l}`}</option>
                  ))}
                </select>
                <button onClick={doSearch} className="bg-indigo-600 hover:bg-indigo-500 text-white text-sm px-3 rounded-md transition-colors">
                  Search
                </button>
              </>
            )}
          </div>
        </div>

        {/* Results */}
        <div className="flex-1 overflow-y-auto px-4 py-2">
          {searching ? (
            <p className="text-gray-400 text-sm py-4 text-center">Searching...</p>
          ) : displayResults.length === 0 ? (
            <p className="text-gray-500 text-sm py-4 text-center">
              {needsApiSearch
                ? `Search for ${className} spells to add them.`
                : isRemoveSpellbook
                  ? 'No spells in spellbook.'
                  : 'No spells in spellbook to prepare.'}
            </p>
          ) : (
            <div className="space-y-1">
              {displayResults.map(spell => {
                const added = isAdded(spell);
                const prepared = isPreparedSpell(spell);
                const isAlwaysPrep = classSpells.some(s => s.name === spell.name && s.alwaysPrepared);

                let disabled = false;
                if (isSpellbookMode) {
                  disabled = false;
                } else if (isRemoveSpellbook) {
                  disabled = !added;
                } else if (type === 'prepared') {
                  disabled = isAlwaysPrep || (preparedCount >= prepLimit && !prepared);
                } else {
                  disabled = isAlwaysPrep || (knownLimit > 0 && leveled.length >= knownLimit && !added);
                }

                return (
                  <button
                    key={spell.id}
                    onClick={() => !disabled && toggleSpell(spell)}
                    disabled={disabled}
                    className={`w-full flex items-center justify-between py-2 px-3 rounded-md text-sm transition-colors ${
                      isAlwaysPrep && (type === 'prepared' || isWizardPrepared)
                        ? 'bg-amber-900/20 text-amber-300 cursor-not-allowed'
                        : disabled
                          ? 'text-gray-600 cursor-not-allowed'
                          : isRemoveSpellbook
                            ? 'text-gray-300 hover:bg-red-900/30'
                            : isSpellbookMode
                              ? added
                                ? 'bg-green-900/30 text-green-200 hover:bg-green-900/50'
                                : 'text-gray-300 hover:bg-gray-800'
                              : (type === 'prepared' || isWizardPrepared ? prepared : added)
                                ? 'bg-indigo-900/30 text-indigo-200 hover:bg-indigo-900/50'
                                : 'text-gray-300 hover:bg-gray-800'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      {isAlwaysPrep && (type === 'prepared' || isWizardPrepared) && <Lock className="w-3 h-3 text-amber-400" />}
                      <span>{spell.name}</span>
                      <span className="text-xs text-gray-500">
                        {spell.level === 0 ? 'Cantrip' : `Lv ${spell.level}`}
                      </span>
                    </div>
                    {isSpellbookMode ? (
                      added ? (
                        <span className="text-xs text-green-400">In Spellbook</span>
                      ) : (
                        <Plus className="w-4 h-4 text-gray-500" />
                      )
                    ) : isRemoveSpellbook ? (
                      added ? (
                        <span className="text-xs text-red-400">Remove</span>
                      ) : (
                        <span className="text-xs text-gray-600">Removed</span>
                      )
                    ) : type === 'prepared' || isWizardPrepared ? (
                      isAlwaysPrep ? (
                        <span className="text-xs text-amber-400">Always</span>
                      ) : prepared ? (
                        <span className="text-xs text-green-400">Prepared</span>
                      ) : added ? (
                        <span className="text-xs text-gray-500">In list</span>
                      ) : (
                        <Plus className="w-4 h-4 text-gray-500" />
                      )
                    ) : added ? (
                      <span className="text-xs text-red-400">Remove</span>
                    ) : (
                      <Plus className="w-4 h-4 text-gray-500" />
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t border-gray-800 flex justify-end gap-2">
          <button onClick={onClose} className="px-3 py-1.5 text-sm text-gray-400 hover:text-white transition-colors">
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white text-sm rounded-md transition-colors disabled:opacity-50"
          >
            {saving ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </div>
    </div>
  );
}

function AddFeatModal({ currentSpells, saveField, onClose }: {
  currentSpells: SpellEntry[];
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
  onClose: () => void;
}) {
  const [allFeats, setAllFeats] = useState<Feat[]>([]);
  const [featSearch, setFeatSearch] = useState('');
  const [selectedFeat, setSelectedFeat] = useState<Feat | null>(null);
  const [selectedOptionIdx, setSelectedOptionIdx] = useState<number | null>(null);
  const [selectedAbility, setSelectedAbility] = useState<string | null>(null);
  const [chosenCantrips, setChosenCantrips] = useState<Spell[]>([]);
  const [chosenSpells, setChosenSpells] = useState<Spell[]>([]);
  const [cantripResults, setCantripResults] = useState<Spell[]>([]);
  const [spellResults, setSpellResults] = useState<Spell[]>([]);
  const [saving, setSaving] = useState(false);
  const [step, setStep] = useState<'pick' | 'configure'>('pick');

  useEffect(() => {
    getFeats().then(f => setAllFeats(f)).catch(() => {});
  }, []);

  const spellGrantingFeats = useMemo(() => {
    return allFeats.filter(f => f.grantsFeatures && f.grantsFeatures !== 'null');
  }, [allFeats]);

  const filteredFeats = useMemo(() => {
    if (!featSearch.trim()) return spellGrantingFeats;
    const q = featSearch.trim().toLowerCase();
    return spellGrantingFeats.filter(f => f.name.toLowerCase().includes(q));
  }, [spellGrantingFeats, featSearch]);

  const parsedOptions = useMemo(() => {
    if (!selectedFeat) return [];
    return parseFeatOptions(selectedFeat.grantsFeatures);
  }, [selectedFeat]);

  const selectedOption: ParsedFeatOption | null = selectedOptionIdx != null ? parsedOptions[selectedOptionIdx] ?? null : null;

  useEffect(() => {
    if (!selectedOption) return;
    if (selectedOption.cantripChoice && selectedOption.cantripChoice.classes.length > 0) {
      Promise.all(selectedOption.cantripChoice.classes.map(cls =>
        searchSpells({ className: cls, level: 0, size: 50 }).then(r => r.content)
      )).then(results => {
        const all = results.flat();
        const seen = new Set<string>();
        setCantripResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
      }).catch(() => {});
    }
    if (selectedOption.spellChoice) {
      if (selectedOption.spellChoice.fromList) {
        const fakeSpells: Spell[] = selectedOption.spellChoice.fromList.map((name, i) => ({
          id: `feat-list-${i}`, name, level: 1, school: null, castingTime: null,
          rangeDistance: null, components: null, duration: null, concentration: false,
          ritual: false, description: null, higherLevels: null, classes: null,
          damageType: null, damageDice: null, saveAbility: null, source: null,
        }));
        setSpellResults(fakeSpells);
      } else if (selectedOption.spellChoice.classes.length > 0) {
        Promise.all(selectedOption.spellChoice.classes.map(cls =>
          searchSpells({ className: cls, level: 1, size: 50 }).then(r => r.content)
        )).then(results => {
          const all = results.flat();
          const seen = new Set<string>();
          setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
        }).catch(() => {});
      }
    }
    if (selectedOption.ability) setSelectedAbility(selectedOption.ability);
  }, [selectedOptionIdx]);

  function selectFeat(feat: Feat) {
    setSelectedFeat(feat);
    setSelectedOptionIdx(null);
    setSelectedAbility(null);
    setChosenCantrips([]);
    setChosenSpells([]);
    setCantripResults([]);
    setSpellResults([]);
    const opts = parseFeatOptions(feat.grantsFeatures);
    if (opts.length === 1) {
      setSelectedOptionIdx(0);
    }
    setStep('configure');
  }

  function toggleCantrip(spell: Spell) {
    if (!selectedOption?.cantripChoice) return;
    const exists = chosenCantrips.some(s => s.name === spell.name);
    if (exists) setChosenCantrips(chosenCantrips.filter(s => s.name !== spell.name));
    else if (chosenCantrips.length < selectedOption.cantripChoice.count) setChosenCantrips([...chosenCantrips, spell]);
  }

  function toggleSpell(spell: Spell) {
    if (!selectedOption?.spellChoice) return;
    const exists = chosenSpells.some(s => s.name === spell.name);
    if (exists) setChosenSpells(chosenSpells.filter(s => s.name !== spell.name));
    else if (chosenSpells.length < selectedOption.spellChoice.count) setChosenSpells([...chosenSpells, spell]);
  }

  const canSave = useMemo(() => {
    if (!selectedFeat || !selectedOption) return false;
    if (selectedOption.abilityChoices && !selectedAbility) return false;
    if (selectedOption.cantripChoice && chosenCantrips.length < selectedOption.cantripChoice.count) return false;
    if (selectedOption.spellChoice && chosenSpells.length < selectedOption.spellChoice.count) return false;
    return true;
  }, [selectedFeat, selectedOption, selectedAbility, chosenCantrips, chosenSpells]);

  async function handleSave() {
    if (!selectedFeat || !selectedOption) return;
    setSaving(true);
    const featSource = `feat:${selectedFeat.name}`;
    const newEntries: SpellEntry[] = [];
    for (const name of selectedOption.fixedCantrips) {
      newEntries.push({ name, level: 0, source: featSource, atWill: true });
    }
    for (const s of chosenCantrips) {
      newEntries.push({ name: s.name, level: 0, source: featSource, atWill: true });
    }
    for (const { name, usesPerDay } of selectedOption.fixedSpells) {
      newEntries.push({ name, level: 1, source: featSource, usesPerLongRest: usesPerDay });
    }
    for (const s of chosenSpells) {
      newEntries.push({
        name: s.name, level: s.level, source: featSource,
        usesPerLongRest: selectedOption.spellChoice?.usesPerDay ?? 1,
      });
    }
    await saveField({ spellsKnown: JSON.stringify([...currentSpells, ...newEntries]) });
    setSaving(false);
    onClose();
  }

  return (
    <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
      <div className="bg-gray-900 border border-gray-700 rounded-lg w-full max-w-lg max-h-[85vh] flex flex-col">
        <div className="px-4 py-3 border-b border-gray-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            {step === 'configure' && (
              <button onClick={() => { setStep('pick'); setSelectedFeat(null); }} className="text-gray-400 hover:text-white">
                <ArrowLeft className="w-4 h-4" />
              </button>
            )}
            <h2 className="text-white font-semibold">
              {step === 'pick' ? 'Add Feat Spells' : selectedFeat?.name ?? ''}
            </h2>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-4 py-3 space-y-4">
          {step === 'pick' && (
            <>
              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
                <input
                  value={featSearch}
                  onChange={e => setFeatSearch(e.target.value)}
                  placeholder="Search spell-granting feats..."
                  className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-amber-500"
                />
              </div>
              <div className="space-y-1">
                {filteredFeats.map(feat => (
                  <button
                    key={feat.id}
                    onClick={() => selectFeat(feat)}
                    className="w-full flex items-center justify-between py-2 px-3 rounded-md text-sm text-gray-300 hover:bg-gray-800 transition-colors"
                  >
                    <span>{feat.name}</span>
                    <span className="text-xs text-gray-500">{feat.source}</span>
                  </button>
                ))}
                {filteredFeats.length === 0 && (
                  <p className="text-gray-500 text-sm py-4 text-center">No spell-granting feats found.</p>
                )}
              </div>
            </>
          )}

          {step === 'configure' && selectedFeat && (
            <>
              {parsedOptions.length > 1 && (
                <div>
                  <p className="text-gray-400 text-xs mb-2">Choose an option:</p>
                  <div className="grid grid-cols-2 gap-2 max-h-40 overflow-y-auto">
                    {parsedOptions.map((opt, i) => (
                      <button
                        key={i}
                        onClick={() => {
                          setSelectedOptionIdx(selectedOptionIdx === i ? null : i);
                          setSelectedAbility(null);
                          setChosenCantrips([]);
                          setChosenSpells([]);
                        }}
                        className={`px-3 py-2 rounded border text-xs text-left transition-colors ${
                          selectedOptionIdx === i
                            ? 'bg-amber-900/30 border-amber-500 text-white'
                            : 'bg-gray-800 border-gray-700 text-gray-400 hover:border-gray-500'
                        }`}
                      >
                        <span className="font-medium">{opt.name}</span>
                        {opt.fixedCantrips.length > 0 && (
                          <span className="block text-gray-500 mt-0.5">{opt.fixedCantrips.join(', ')}</span>
                        )}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {selectedOption?.abilityChoices && (
                <div>
                  <p className="text-gray-400 text-xs mb-2">Spellcasting Ability:</p>
                  <div className="flex gap-1.5">
                    {selectedOption.abilityChoices.map(a => (
                      <button
                        key={a}
                        onClick={() => setSelectedAbility(selectedAbility === a ? null : a)}
                        className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                          selectedAbility === a ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                        }`}
                      >
                        {a}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {selectedOption?.fixedCantrips.length ? (
                <div>
                  <p className="text-gray-400 text-xs mb-1">Granted cantrips:</p>
                  <div className="flex flex-wrap gap-1">
                    {selectedOption.fixedCantrips.map(name => (
                      <span key={name} className="bg-gray-800 text-amber-300 px-2 py-1 rounded text-xs">{name}</span>
                    ))}
                  </div>
                </div>
              ) : null}

              {selectedOption?.cantripChoice && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <p className="text-gray-400 text-xs">Choose {selectedOption.cantripChoice.count} cantrip{selectedOption.cantripChoice.count > 1 ? 's' : ''}:</p>
                    <span className="text-xs text-gray-500">{chosenCantrips.length}/{selectedOption.cantripChoice.count}</span>
                  </div>
                  {chosenCantrips.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {chosenCantrips.map(s => (
                        <span key={s.name} className="flex items-center gap-1 bg-amber-900/50 text-amber-200 px-2 py-1 rounded text-xs">
                          {s.name}
                          <button onClick={() => toggleCantrip(s)} className="text-amber-400 hover:text-white"><X className="w-3 h-3" /></button>
                        </span>
                      ))}
                    </div>
                  )}
                  <div className="max-h-32 overflow-y-auto space-y-1">
                    {cantripResults.map(spell => {
                      const sel = chosenCantrips.some(s => s.name === spell.name);
                      const dis = !sel && chosenCantrips.length >= selectedOption!.cantripChoice!.count;
                      return (
                        <button
                          key={spell.id}
                          onClick={() => !dis && toggleCantrip(spell)}
                          disabled={dis}
                          className={`w-full flex items-center justify-between py-1 px-2 rounded text-xs transition-colors ${
                            sel ? 'bg-amber-900/30 text-amber-200' : dis ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                          }`}
                        >
                          <span>{spell.name}</span>
                          {sel && <Check className="w-3 h-3 text-amber-400" />}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {selectedOption?.fixedSpells.length ? (
                <div>
                  <p className="text-gray-400 text-xs mb-1">Granted spells:</p>
                  <div className="flex flex-wrap gap-1">
                    {selectedOption.fixedSpells.map(s => (
                      <span key={s.name} className="bg-gray-800 text-amber-300 px-2 py-1 rounded text-xs">{s.name} ({s.usesPerDay}/day)</span>
                    ))}
                  </div>
                </div>
              ) : null}

              {selectedOption?.spellChoice && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <p className="text-gray-400 text-xs">
                      Choose {selectedOption.spellChoice.count} spell{selectedOption.spellChoice.count > 1 ? 's' : ''} ({selectedOption.spellChoice.usesPerDay}/day):
                    </p>
                    <span className="text-xs text-gray-500">{chosenSpells.length}/{selectedOption.spellChoice.count}</span>
                  </div>
                  {chosenSpells.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {chosenSpells.map(s => (
                        <span key={s.name} className="flex items-center gap-1 bg-amber-900/50 text-amber-200 px-2 py-1 rounded text-xs">
                          {s.name}
                          <button onClick={() => toggleSpell(s)} className="text-amber-400 hover:text-white"><X className="w-3 h-3" /></button>
                        </span>
                      ))}
                    </div>
                  )}
                  <div className="max-h-32 overflow-y-auto space-y-1">
                    {spellResults.map(spell => {
                      const sel = chosenSpells.some(s => s.name === spell.name);
                      const dis = !sel && chosenSpells.length >= selectedOption!.spellChoice!.count;
                      return (
                        <button
                          key={spell.id}
                          onClick={() => !dis && toggleSpell(spell)}
                          disabled={dis}
                          className={`w-full flex items-center justify-between py-1 px-2 rounded text-xs transition-colors ${
                            sel ? 'bg-amber-900/30 text-amber-200' : dis ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                          }`}
                        >
                          <div className="flex items-center gap-2">
                            <span>{spell.name}</span>
                            {spell.school && <span className="text-gray-500">{spell.school}</span>}
                          </div>
                          {sel && <Check className="w-3 h-3 text-amber-400" />}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}
            </>
          )}
        </div>

        {step === 'configure' && (
          <div className="px-4 py-3 border-t border-gray-800 flex justify-end gap-2">
            <button onClick={onClose} className="px-3 py-1.5 text-sm text-gray-400 hover:text-white transition-colors">Cancel</button>
            <button
              onClick={handleSave}
              disabled={!canSave || saving}
              className="px-4 py-1.5 bg-amber-600 hover:bg-amber-500 text-white text-sm rounded-md transition-colors disabled:opacity-50"
            >
              {saving ? 'Saving...' : 'Add Feat Spells'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

function InventoryTab({ equipment, currency, char, saveField }: {
  equipment: Array<{ name: string; quantity?: number; description?: string }>;
  currency: { cp: number; sp: number; ep: number; gp: number; pp: number };
  char: PlayerCharacter;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
}) {
  const [editCurrency, setEditCurrency] = useState(false);
  const [currencyForm, setCurrencyForm] = useState(currency);

  useEffect(() => { setCurrencyForm(currency); }, [currency]);

  async function saveCurrency() {
    await saveField({ currency: JSON.stringify(currencyForm) });
    setEditCurrency(false);
  }

  return (
    <div className="space-y-6">
      {/* Currency */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider">Currency</h3>
          <button onClick={() => editCurrency ? saveCurrency() : setEditCurrency(true)} className="text-indigo-400 text-xs hover:text-indigo-300">
            {editCurrency ? 'Save' : 'Edit'}
          </button>
        </div>
        <div className="grid grid-cols-5 gap-3">
          {(['cp', 'sp', 'ep', 'gp', 'pp'] as const).map(coin => (
            <div key={coin} className="text-center">
              <p className="text-gray-500 text-xs uppercase">{coin}</p>
              {editCurrency ? (
                <input
                  type="number"
                  min={0}
                  value={currencyForm[coin]}
                  onChange={e => setCurrencyForm({ ...currencyForm, [coin]: parseInt(e.target.value) || 0 })}
                  className="w-full text-center text-white font-bold bg-gray-800 border border-gray-700 rounded py-1 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
                />
              ) : (
                <p className="text-white font-bold">{currency[coin]}</p>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Equipment */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Equipment</h3>
        {equipment.length > 0 ? (
          <div className="space-y-2">
            {equipment.map((item, i) => (
              <div key={i} className="flex items-center gap-2 py-1 border-b border-gray-800 last:border-0">
                <span className="text-white text-sm flex-1">{item.name}</span>
                {item.quantity && item.quantity > 1 && <span className="text-gray-500 text-xs">x{item.quantity}</span>}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-gray-500 text-sm">No equipment recorded.</p>
        )}
      </div>
    </div>
  );
}

function FeaturesTab({ features, char }: {
  features: Array<{ name: string; description: string; source?: string }>;
  char: PlayerCharacter;
}) {
  return (
    <div className="space-y-3">
      {features.length > 0 ? (
        features.map((f, i) => (
          <details key={i} className="bg-gray-900 border border-gray-800 rounded-lg group">
            <summary className="px-4 py-3 cursor-pointer list-none flex items-center justify-between">
              <div>
                <h4 className="text-white font-medium text-sm">{f.name}</h4>
                {f.source && <span className="text-gray-500 text-xs">{f.source}</span>}
              </div>
              <svg className="w-4 h-4 text-gray-500 group-open:rotate-180 transition-transform" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </summary>
            <div className="px-4 pb-3">
              <FormattedDescription text={f.description} />
            </div>
          </details>
        ))
      ) : (
        <p className="text-gray-500 text-sm">No features recorded. Features will be populated based on your race, class, and background choices.</p>
      )}
    </div>
  );
}

function JournalTab({ char, saveField }: {
  char: PlayerCharacter;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
}) {
  const [editing, setEditing] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');

  const fields: Array<{ key: keyof PlayerCharacter; label: string }> = [
    { key: 'personalityTraits', label: 'Personality Traits' },
    { key: 'ideals', label: 'Ideals' },
    { key: 'bonds', label: 'Bonds' },
    { key: 'flaws', label: 'Flaws' },
    { key: 'notes', label: 'Notes' },
  ];

  async function save(key: string) {
    await saveField({ [key]: editValue });
    setEditing(null);
  }

  return (
    <div className="space-y-4">
      {fields.map(({ key, label }) => (
        <div key={key} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider">{label}</h3>
            <button
              onClick={() => {
                if (editing === key) { save(key); }
                else { setEditing(key); setEditValue((char[key] as string) || ''); }
              }}
              className="text-indigo-400 text-xs hover:text-indigo-300"
            >
              {editing === key ? 'Save' : 'Edit'}
            </button>
          </div>
          {editing === key ? (
            <textarea
              value={editValue}
              onChange={e => setEditValue(e.target.value)}
              rows={4}
              className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm resize-none focus:outline-none focus:ring-2 focus:ring-indigo-500"
              autoFocus
            />
          ) : (
            <p className="text-gray-300 text-sm whitespace-pre-line">
              {(char[key] as string) || <span className="text-gray-600 italic">Not set</span>}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}

function StatCard({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-3 text-center">
      <p className="text-gray-500 text-xs">{label}</p>
      <p className="text-white font-bold text-lg">{value}</p>
      {sub && <p className="text-gray-600 text-xs">{sub}</p>}
    </div>
  );
}
