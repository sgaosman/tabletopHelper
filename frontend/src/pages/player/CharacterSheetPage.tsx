import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Shield, Swords, BookOpen, Backpack, Star, ScrollText, Heart, Moon, Sun, ChevronUp, ChevronDown } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import { campaignApi } from '../../api/campaignApi';
import LevelUpModal from '../../components/character/LevelUpModal';
import AsiModal from '../../components/character/AsiModal';
import SubclassModal from '../../components/character/SubclassModal';
import ExpertiseModal from '../../components/character/ExpertiseModal';
import type { PlayerCharacter, CharacterUpdateRequest, LevelUpResponse } from '../../types/character';
import type { Campaign } from '../../types/campaign';
import { THIRD_CASTER_SUBCLASSES } from '../../utils/spellConstants';
import { abilityMod, formatMod, safeJsonParse } from '../../utils/dndRules';
import type { SpellEntry } from './sheet/types';
import StatsTab from './sheet/StatsTab';
import ActionsTab from './sheet/ActionsTab';
import SpellsTab from './sheet/SpellsTab';
import InventoryTab from './sheet/InventoryTab';
import FeaturesTab from './sheet/FeaturesTab';
import JournalTab from './sheet/JournalTab';

type Tab = 'Stats' | 'Actions' | 'Spells' | 'Inventory' | 'Features' | 'Journal';
const TABS: { key: Tab; label: string; icon: typeof Shield }[] = [
  { key: 'Stats', label: 'Stats', icon: Shield },
  { key: 'Actions', label: 'Actions', icon: Swords },
  { key: 'Spells', label: 'Spells', icon: BookOpen },
  { key: 'Inventory', label: 'Inventory', icon: Backpack },
  { key: 'Features', label: 'Features', icon: Star },
  { key: 'Journal', label: 'Journal', icon: ScrollText },
];

export default function CharacterSheetPage() {
  const { characterId } = useParams<{ characterId: string }>();
  const navigate = useNavigate();
  const [char, setChar] = useState<PlayerCharacter | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('Stats');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [restModal, setRestModal] = useState<'short' | 'long' | null>(null);
  const [shortRestDice, setShortRestDice] = useState<Record<string, number>>({});
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
    const updated = { ...hitDiceMap };
    let hpGain = 0;
    for (const [cls, count] of Object.entries(shortRestDice)) {
      if (count > 0 && updated[cls]) {
        for (let i = 0; i < count; i++) {
          const roll = Math.ceil(updated[cls].faces / 2) + 1;
          hpGain += Math.max(0, roll + abilityMod(char.constitution));
        }
        updated[cls] = { ...updated[cls], remaining: updated[cls].remaining - count };
      }
    }

    const newHp = Math.min(char.hpMax, char.hpCurrent + hpGain);
    const resetSlots: Record<string, { total: number; used: number }> = {};
    for (const [key, slot] of Object.entries(spellSlots)) {
      if (key.startsWith('pact_')) {
        resetSlots[key] = { ...slot, used: 0 };
      } else {
        resetSlots[key] = slot;
      }
    }
    const resetResources = featResources.map(r =>
      r.resetOn === 'shortRest' || r.resetOn === 'longRest' ? { ...r, currentUses: r.maxUses } : r
    );

    const updates: Record<string, unknown> = {
      hpCurrent: newHp,
      hitDiceMap: JSON.stringify(updated),
    };
    if (Object.keys(resetSlots).length > 0) updates.spellSlots = JSON.stringify(resetSlots);
    if (resetResources.length > 0) updates.featResources = JSON.stringify(resetResources);
    await saveField(updates as Record<string, string | number | undefined>);
    setShortRestDice({});
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
      r.resetOn === 'shortRest' || r.resetOn === 'longRest' ? { ...r, currentUses: r.maxUses } : r
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
          <div className="flex gap-1 overflow-x-auto flex-1" role="tablist" aria-label="Character sheet tabs">
            {TABS.map(({ key, label, icon: Icon }) => (
              <button
                key={key}
                role="tab"
                aria-selected={activeTab === key}
                aria-controls={`tabpanel-${key}`}
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
        {error && <div role="alert" className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 mt-4 text-sm">{error}</div>}
        {success && <div role="status" className="bg-green-900/50 border border-green-700 text-green-300 rounded-lg p-3 mt-4 text-sm">{success}</div>}
      </div>

      {/* Tab Content */}
      <main className="max-w-5xl mx-auto px-4 py-6" role="tabpanel" id={`tabpanel-${activeTab}`} aria-label={`${activeTab} tab content`}>
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
          <div role="dialog" aria-modal="true" aria-labelledby="level-down-title" className="bg-gray-900 border border-gray-800 rounded-xl p-6 max-w-sm w-full" onClick={e => e.stopPropagation()}>
            <h3 id="level-down-title" className="text-white font-bold text-lg mb-2">Level Down</h3>
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
        <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={() => { setRestModal(null); setShortRestDice({}); }}>
          <div role="dialog" aria-modal="true" aria-labelledby="rest-title" className="bg-gray-900 border border-gray-800 rounded-xl p-6 max-w-sm w-full" onClick={e => e.stopPropagation()}>
            <h3 id="rest-title" className="text-white font-bold text-lg mb-2">
              {restModal === 'short' ? 'Short Rest' : 'Long Rest'}
            </h3>
            {restModal === 'short' ? (
              <>
                <p className="text-gray-400 text-sm mb-3">
                  Spend hit dice to regain hit points. For each die spent, regain 1d{Object.values(hitDiceMap)[0]?.faces ?? 8} + CON modifier HP.
                  {Object.values(spellSlots).some((_, i, arr) => Object.keys(spellSlots).some(k => k.startsWith('pact_'))) ? ' Warlock pact slots will be restored.' : ''}
                </p>
                <div className="space-y-2 mb-4">
                  {Object.entries(hitDiceMap).map(([cls, hd]) => (
                    <div key={cls} className="flex items-center justify-between bg-gray-800 rounded-lg px-3 py-2">
                      <span className="text-gray-300 text-sm">{cls} (d{hd.faces})</span>
                      <div className="flex items-center gap-2">
                        <span className="text-gray-500 text-xs">{hd.remaining}/{hd.total} remaining</span>
                        <button
                          onClick={() => setShortRestDice(prev => ({ ...prev, [cls]: Math.max(0, (prev[cls] || 0) - 1) }))}
                          disabled={!shortRestDice[cls]}
                          className="w-7 h-7 rounded bg-gray-700 text-gray-300 text-sm disabled:opacity-30 hover:bg-gray-600"
                        >−</button>
                        <span className="text-white text-sm w-4 text-center">{shortRestDice[cls] || 0}</span>
                        <button
                          onClick={() => setShortRestDice(prev => ({ ...prev, [cls]: Math.min(hd.remaining, (prev[cls] || 0) + 1) }))}
                          disabled={(shortRestDice[cls] || 0) >= hd.remaining}
                          className="w-7 h-7 rounded bg-gray-700 text-gray-300 text-sm disabled:opacity-30 hover:bg-gray-600"
                        >+</button>
                      </div>
                    </div>
                  ))}
                </div>
                {Object.keys(spellSlots).some(k => k.startsWith('pact_')) && (
                  <p className="text-purple-400 text-xs mb-3">Warlock pact slots will be restored on short rest.</p>
                )}
                {featResources.some(r => r.resetOn === 'shortRest') && (
                  <p className="text-yellow-400 text-xs mb-3">Short rest abilities will be restored.</p>
                )}
              </>
            ) : (
              <p className="text-gray-400 text-sm mb-4">
                Regain all hit points, reset spell slots, and regain half your total hit dice (minimum 1).
              </p>
            )}
            <div className="flex gap-3">
              <button onClick={() => { setRestModal(null); setShortRestDice({}); }} className="flex-1 px-4 py-2 bg-gray-800 text-gray-300 rounded-lg text-sm hover:bg-gray-700 transition-colors">Cancel</button>
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
