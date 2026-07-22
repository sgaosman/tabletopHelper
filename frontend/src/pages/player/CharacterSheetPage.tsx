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
import { getClassColour } from '../../utils/classColours';
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
  const [_saving, setSaving] = useState(false);
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

  const classAccent = getClassColour(char?.characterClass);

  if (!char) {
    return (
      <div className="min-h-screen bg-page flex items-center justify-center">
        <p className="font-body text-[14px] text-muted">Loading character...</p>
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
    <div className="min-h-screen bg-page">
      {/* Header */}
      <header className="sticky top-0 z-10 bg-card border-b border-rule px-4 py-3">
        <div className="max-w-5xl mx-auto flex items-center justify-between">
          <button onClick={() => navigate('/player')} className="flex items-center gap-1 text-muted hover:text-ink font-body text-[13px] transition-colors">
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div className="text-center">
            <h1 className="font-heading text-[17px] font-bold" style={{ color: classAccent }}>{char.name}</h1>
            <p className="font-body text-[12px] font-medium text-muted">
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
              className="p-2 text-debuff hover:bg-page-alt transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
              title="Level Down"
            >
              <ChevronDown className="w-4 h-4" />
            </button>
            <button
              onClick={() => setShowLevelUp(true)}
              disabled={char.level >= 20}
              className="p-2 text-buff hover:bg-page-alt transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
              title="Level Up"
            >
              <ChevronUp className="w-4 h-4" />
            </button>
            <div className="w-px h-5 bg-rule mx-1" />
            <button onClick={() => setRestModal('short')} className="p-2 text-cls-monk hover:bg-page-alt transition-colors" title="Short Rest">
              <Moon className="w-4 h-4" />
            </button>
            <button onClick={() => setRestModal('long')} className="p-2 text-cls-wizard hover:bg-page-alt transition-colors" title="Long Rest">
              <Sun className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      {/* HP Bar */}
      <div className="max-w-5xl mx-auto px-4 py-3">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <Heart className="w-4 h-4 text-debuff" />
            <span className="font-heading text-[12px] font-bold text-ink">{char.hpCurrent}/{char.hpMax}</span>
            {char.hpTemp > 0 && <span className="font-heading text-[11px] font-medium text-cls-wizard">+{char.hpTemp} temp</span>}
          </div>
          <div className="flex-1 h-2 bg-rule overflow-hidden">
            <div
              className={`h-full transition-all ${
                char.hpCurrent / char.hpMax > 0.5 ? 'bg-buff' :
                char.hpCurrent / char.hpMax > 0.25 ? 'bg-cls-monk' : 'bg-debuff'
              }`}
              style={{ width: `${Math.min(100, (char.hpCurrent / char.hpMax) * 100)}%` }}
            />
          </div>
          <div className="flex gap-3">
            <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">AC <span className="font-heading text-[13px] font-bold text-ink">{char.armourClass}</span></span>
            <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Init <span className="font-heading text-[13px] font-bold text-ink">{formatMod(char.initiativeBonus)}</span></span>
            <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Speed <span className="font-heading text-[13px] font-bold text-ink">{char.speed}ft</span></span>
          </div>
        </div>
        {(featResources.length > 0) && (
          <div className="flex items-center gap-4 mt-2 flex-wrap">
            {featResources.map((r, i) => (
              <div key={i} className="flex items-center gap-2 bg-page-alt border border-rule px-3 py-1">
                <span className="font-heading text-[9px] font-medium tracking-[0.02em]" style={{ color: classAccent }}>{r.name}</span>
                <div className="flex gap-1">
                  {Array.from({ length: r.maxUses }).map((_, j) => (
                    <button key={j}
                      onClick={() => {
                        const updated = [...featResources];
                        const newUses = j < r.currentUses ? j : j + 1;
                        updated[i] = { ...r, currentUses: newUses };
                        saveField({ featResources: JSON.stringify(updated) });
                      }}
                      className="w-4 h-4 rounded-full border transition-colors"
                      style={{
                        backgroundColor: j < r.currentUses ? classAccent : 'transparent',
                        borderColor: j < r.currentUses ? classAccent : '#E7E5E4',
                      }}
                    />
                  ))}
                </div>
                <span className="font-body text-[11px] text-faint">{r.currentUses}/{r.maxUses}</span>
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
              className="font-body text-[12px] font-medium text-cls-wizard hover:text-ink bg-page-alt border border-rule px-2 py-1 transition-colors"
            >+ Temp HP</button>
          </div>
        )}
      </div>

      {/* Tabs */}
      <div className="max-w-5xl mx-auto px-4">
        <div className="flex items-center border-b border-rule">
          <div className="flex gap-1 overflow-x-auto flex-1" role="tablist" aria-label="Character sheet tabs">
            {TABS.map(({ key, label, icon: Icon }) => (
              <button
                key={key}
                role="tab"
                aria-selected={activeTab === key}
                aria-controls={`tabpanel-${key}`}
                onClick={() => setActiveTab(key)}
                className={`flex items-center gap-1.5 px-3 py-2 font-body text-[13px] font-medium transition-colors whitespace-nowrap ${
                  activeTab === key ? 'text-ink border-b-2' : 'text-faint hover:text-muted'
                }`}
                style={activeTab === key ? { borderBottomColor: classAccent } : undefined}
              >
                <Icon className="w-3.5 h-3.5" /> {label}
              </button>
            ))}
          </div>
          <select
            value={char.campaignId ?? ''}
            onChange={e => handleCampaignChange(e.target.value)}
            className="ml-2 px-2 py-1.5 bg-page-alt border border-rule font-body text-[12px] font-medium text-muted focus:outline-none focus:border-muted shrink-0"
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
        {error && <div role="alert" className="bg-debuff-bg border border-debuff text-debuff p-3 mt-4 font-body text-[13px] font-medium">{error}</div>}
        {success && <div role="status" className="bg-buff-bg border border-buff text-buff p-3 mt-4 font-body text-[13px] font-medium">{success}</div>}
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
        <div className="fixed top-4 left-1/2 -translate-x-1/2 z-50 bg-buff-bg border border-buff text-buff px-6 py-3 shadow-lg font-body text-[14px] font-medium animate-pulse">
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
        <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4" onClick={() => setLevelDownConfirm(false)}>
          <div role="dialog" aria-modal="true" aria-labelledby="level-down-title" className="bg-card border border-rule p-6 max-w-sm w-full shadow-lg" onClick={e => e.stopPropagation()}>
            <h3 id="level-down-title" className="font-heading text-[17px] font-bold text-ink mb-2">Level Down</h3>
            <p className="font-body text-[13px] font-medium text-muted mb-4">
              Remove level {char.level}? This will reverse HP, features, and any ASI choices made at that level.
            </p>
            <div className="flex gap-3">
              <button onClick={() => setLevelDownConfirm(false)} className="flex-1 px-4 py-2 bg-page-alt border border-rule text-muted font-body text-[13px] font-medium hover:bg-rule transition-colors">
                Cancel
              </button>
              <button onClick={handleLevelDown} className="flex-1 px-4 py-2 bg-debuff text-white font-body text-[13px] font-medium hover:opacity-90 transition-colors">
                Remove Level
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Rest Modal */}
      {restModal && (
        <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4" onClick={() => { setRestModal(null); setShortRestDice({}); }}>
          <div role="dialog" aria-modal="true" aria-labelledby="rest-title" className="bg-card border border-rule p-6 max-w-sm w-full shadow-lg" onClick={e => e.stopPropagation()}>
            <h3 id="rest-title" className="font-heading text-[17px] font-bold text-ink mb-2">
              {restModal === 'short' ? 'Short Rest' : 'Long Rest'}
            </h3>
            {restModal === 'short' ? (
              <>
                <p className="font-body text-[13px] font-medium text-muted mb-3">
                  Spend hit dice to regain hit points. For each die spent, regain 1d{Object.values(hitDiceMap)[0]?.faces ?? 8} + CON modifier HP.
                  {Object.keys(spellSlots).some(k => k.startsWith('pact_')) ? ' Warlock pact slots will be restored.' : ''}
                </p>
                <div className="space-y-2 mb-4">
                  {Object.entries(hitDiceMap).map(([cls, hd]) => (
                    <div key={cls} className="flex items-center justify-between bg-page-alt border border-rule px-3 py-2">
                      <span className="font-body text-[13px] font-medium text-ink">{cls} (d{hd.faces})</span>
                      <div className="flex items-center gap-2">
                        <span className="font-body text-[11px] text-faint">{hd.remaining}/{hd.total} remaining</span>
                        <button
                          onClick={() => setShortRestDice(prev => ({ ...prev, [cls]: Math.max(0, (prev[cls] || 0) - 1) }))}
                          disabled={!shortRestDice[cls]}
                          className="w-7 h-7 bg-page border border-rule text-muted font-body text-[13px] disabled:opacity-30 hover:bg-rule-light"
                        >−</button>
                        <span className="font-heading text-[13px] font-bold text-ink w-4 text-center">{shortRestDice[cls] || 0}</span>
                        <button
                          onClick={() => setShortRestDice(prev => ({ ...prev, [cls]: Math.min(hd.remaining, (prev[cls] || 0) + 1) }))}
                          disabled={(shortRestDice[cls] || 0) >= hd.remaining}
                          className="w-7 h-7 bg-page border border-rule text-muted font-body text-[13px] disabled:opacity-30 hover:bg-rule-light"
                        >+</button>
                      </div>
                    </div>
                  ))}
                </div>
                {Object.keys(spellSlots).some(k => k.startsWith('pact_')) && (
                  <p className="font-body text-[11px] font-medium text-cls-warlock mb-3">Warlock pact slots will be restored on short rest.</p>
                )}
                {featResources.some(r => r.resetOn === 'shortRest') && (
                  <p className="font-body text-[11px] font-medium text-cls-monk mb-3">Short rest abilities will be restored.</p>
                )}
              </>
            ) : (
              <p className="font-body text-[13px] font-medium text-muted mb-4">
                Regain all hit points, reset spell slots, and regain half your total hit dice (minimum 1).
              </p>
            )}
            <div className="flex gap-3">
              <button onClick={() => { setRestModal(null); setShortRestDice({}); }} className="flex-1 px-4 py-2 bg-page-alt border border-rule text-muted font-body text-[13px] font-medium hover:bg-rule transition-colors">Cancel</button>
              <button
                onClick={restModal === 'short' ? handleShortRest : handleLongRest}
                className="flex-1 px-4 py-2 bg-ink text-card font-body text-[13px] font-medium hover:opacity-90 transition-colors"
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
