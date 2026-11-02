import { useState, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Shield, Swords, BookOpen, Backpack, Star, ScrollText, Heart, Moon, Sun, ChevronUp, ChevronDown } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useCampaigns } from '../../queries/campaigns';
import { useApplyChoices, useCharacter, useLevelDown, useUpdateCharacter } from '../../queries/characters';
import LevelUpModal from '../../components/character/LevelUpModal';
import AsiModal from '../../components/character/AsiModal';
import SubclassModal from '../../components/character/SubclassModal';
import ExpertiseModal from '../../components/character/ExpertiseModal';
import Button from '../../components/ui/Button';
import IconButton from '../../components/ui/IconButton';
import Modal from '../../components/ui/Modal';
import type { PlayerCharacter, CharacterUpdateRequest, LevelUpResponse } from '../../types/character';
import { THIRD_CASTER_SUBCLASSES } from '../../utils/spellConstants';
import { abilityMod, formatMod, safeJsonParse } from '../../utils/dndRules';
import { getClassColour } from '../../utils/classColours';
import type { SpellEntry } from './sheet/types';
import StatsTab from './sheet/StatsTab';
import ActionsTab from './sheet/ActionsTab';
import SpellsTab from './sheet/SpellsTab';
import InventoryTab from './sheet/InventoryTab';
import FeaturesTab from './sheet/FeaturesTab';
import ResourcePoolDisplay from '../../components/common/ResourcePoolDisplay';
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

/**
 * A character's sheet. Every change goes to the server, and the character it returns replaces the cached one,
 * so the sheet shows it without loading the character again.
 */
export default function CharacterSheetPage() {
  const { characterId } = useParams<{ characterId: string }>();
  const navigate = useNavigate();
  const characterQuery = useCharacter(characterId);
  const char = characterQuery.data;
  const { data: campaigns = [] } = useCampaigns();
  const { mutateAsync: updateCharacter } = useUpdateCharacter();
  const levelDown = useLevelDown();
  const applyChoices = useApplyChoices();
  const [activeTab, setActiveTab] = useState<Tab>('Stats');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [restModal, setRestModal] = useState<'short' | 'long' | null>(null);
  const [shortRestDice, setShortRestDice] = useState<Record<string, number>>({});
  const [showLevelUp, setShowLevelUp] = useState(false);
  const [showAsi, setShowAsi] = useState(false);
  const [subclassPrompt, setSubclassPrompt] = useState<{ classId: string; className: string } | null>(null);
  const [levelUpBanner, setLevelUpBanner] = useState<string | null>(null);
  const [levelDownConfirm, setLevelDownConfirm] = useState(false);
  const [pendingLevelClass, setPendingLevelClass] = useState<{ classId: string; className: string; subclassRequired: boolean; expertiseAvailable: boolean; expertiseCount: number; spellSelectionNeeded: boolean; spellSelectionType?: string | null } | null>(null);
  const [showExpertise, setShowExpertise] = useState(false);
  const [pendingSpellManage, setPendingSpellManage] = useState<{ className: string; type: 'known' | 'prepared' } | null>(null);

  const saveField = useCallback(async (updates: CharacterUpdateRequest) => {
    if (!characterId) return;
    setError('');
    try {
      await updateCharacter({ characterId, updates });
      setSuccess('Saved');
      setTimeout(() => setSuccess(''), 2000);
    } catch (err) {
      setError(apiErrorMessage(err));
    }
  }, [characterId, updateCharacter]);

  async function handleCampaignChange(value: string) {
    if (!characterId) return;
    try {
      const update: CharacterUpdateRequest = value
        ? { campaignId: value }
        : { clearCampaign: true };
      await updateCharacter({ characterId, updates: update });
      setSuccess('Campaign updated');
      setTimeout(() => setSuccess(''), 2000);
    } catch (err) {
      setError(apiErrorMessage(err));
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
  const resourcePools = useMemo(() => safeJsonParse<import('../../types/encounter').ResourcePoolEntry[]>(char?.resourcePools, []), [char?.resourcePools]);

  // Pre-compute rest modal contents (avoids IIFE parse errors in JSX)
  const shortRestPools = useMemo(() => resourcePools.filter(p => p.resetOn === 'shortRest' && p.currentUses < p.maxUses),
    [resourcePools]);
  const shortRestFeats = useMemo(() => featResources.filter(r => r.resetOn === 'shortRest' && r.currentUses < r.maxUses),
    [featResources]);
  const hasPactSlots = useMemo(() => Object.keys(spellSlots).some(k => k.startsWith('pact_')), [spellSlots]);
  const showShortRestAbilities = shortRestPools.length > 0 || shortRestFeats.length > 0 || hasPactSlots;

  const longRestPools = useMemo(() => resourcePools.filter(p => p.currentUses < p.maxUses), [resourcePools]);
  const longRestFeats = useMemo(() => featResources.filter(r => r.currentUses < r.maxUses), [featResources]);
  const showLongRestAbilities = longRestPools.length > 0 || longRestFeats.length > 0;

  const classAccent = getClassColour(char?.characterClass);

  if (!char) {
    return (
      <div className="min-h-screen bg-page flex items-center justify-center">
        {characterQuery.isPending
          ? <p className="font-body text-[16px] text-muted">Loading character...</p>
          : <p role="alert" className="font-body text-[16px] text-debuff">{apiErrorMessage(characterQuery.error)}</p>}
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
      r.resetOn === 'shortRest' ? { ...r, currentUses: r.maxUses } : r
    );
    const resetPools = resourcePools.map(p =>
      p.resetOn === 'shortRest' ? { ...p, currentUses: p.maxUses } : p
    );

    const updates: CharacterUpdateRequest = {
      hpCurrent: newHp,
      hitDiceMap: JSON.stringify(updated),
    };
    if (Object.keys(resetSlots).length > 0) updates.spellSlots = JSON.stringify(resetSlots);
    if (resetResources.length > 0) updates.featResources = JSON.stringify(resetResources);
    if (resetPools.length > 0) updates.resourcePools = JSON.stringify(resetPools);
    await saveField(updates);
    setShortRestDice({});
    setRestModal(null);
  }

  function closeRestModal() {
    setRestModal(null);
    setShortRestDice({});
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
    const resetPools = resourcePools.map(p => ({ ...p, currentUses: p.maxUses }));

    await saveField({
      hpCurrent: char.hpMax,
      hpTemp: 0,
      hitDiceMap: JSON.stringify(updated),
      spellSlots: Object.keys(resetSlots).length > 0 ? JSON.stringify(resetSlots) : undefined,
      featResources: resetResources.length > 0 ? JSON.stringify(resetResources) : undefined,
      resourcePools: resetPools.length > 0 ? JSON.stringify(resetPools) : undefined,
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
    // The level is kept either way; a failure only leaves the expertise unchosen
    try {
      await applyChoices.mutateAsync({ characterId: char.id, choices: { expertiseSkills: skills } });
    } catch (err) {
      setError(apiErrorMessage(err));
    }
    setShowExpertise(false);
    finishLevelUp(char.level);
  }

  async function handleLevelDown() {
    if (!characterId || !char) return;
    setLevelDownConfirm(false);
    setError('');
    try {
      const reverted = await levelDown.mutateAsync(characterId);
      setSuccess(`Reverted to level ${reverted.level}`);
      setTimeout(() => setSuccess(''), 3000);
    } catch (err) {
      setError(apiErrorMessage(err));
    }
  }

  return (
    <div className="min-h-screen bg-page">
      {/* Header */}
      <header className="sticky top-0 z-10 bg-card border-b border-rule px-4 py-3">
        <div className="max-w-5xl mx-auto flex items-center justify-between">
          <IconButton label="Back to dashboard" onClick={() => navigate('/player')} className="flex items-center gap-1 text-muted hover:text-ink font-body text-[15px] transition-colors">
            <ArrowLeft className="w-4 h-4" />
          </IconButton>
          <div className="text-center">
            <h1 className="font-heading text-[17px] font-bold" style={{ color: classAccent }}>{char.name}</h1>
            <p className="font-body text-[14px] font-semibold text-muted">
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
            <IconButton
              label="Level Down"
              onClick={() => setLevelDownConfirm(true)}
              disabled={char.level <= 1}
              className="p-2 text-debuff hover:bg-page-alt transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <ChevronDown className="w-4 h-4" />
            </IconButton>
            <IconButton
              label="Level Up"
              onClick={() => setShowLevelUp(true)}
              disabled={char.level >= 20}
              className="p-2 text-buff hover:bg-page-alt transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <ChevronUp className="w-4 h-4" />
            </IconButton>
            <div className="w-px h-5 bg-rule mx-1" />
            <IconButton label="Short Rest" onClick={() => setRestModal('short')} className="p-2 text-cls-monk hover:bg-page-alt transition-colors">
              <Moon className="w-4 h-4" />
            </IconButton>
            <IconButton label="Long Rest" onClick={() => setRestModal('long')} className="p-2 text-cls-wizard hover:bg-page-alt transition-colors">
              <Sun className="w-4 h-4" />
            </IconButton>
          </div>
        </div>
      </header>

      {/* HP Bar */}
      <div className="max-w-5xl mx-auto px-4 py-3">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <Heart className="w-4 h-4 text-debuff" />
            <span className="font-heading text-[14px] font-bold text-ink">{char.hpCurrent}/{char.hpMax}</span>
            {char.hpTemp > 0 && <span className="font-heading text-[13px] font-semibold text-cls-wizard">+{char.hpTemp} temp</span>}
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
            <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">AC <span className="font-heading text-[15px] font-bold text-ink">{char.armourClass}</span></span>
            <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Init <span className="font-heading text-[15px] font-bold text-ink">{formatMod(char.initiativeBonus)}</span></span>
            <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Speed <span className="font-heading text-[15px] font-bold text-ink">{char.speed}ft</span></span>
          </div>
        </div>
        {(featResources.length > 0) && (
          <div className="flex items-center gap-4 mt-2 flex-wrap">
            {featResources.map((r, i) => (
              <div key={i} className="flex items-center gap-2 bg-page-alt border border-rule px-3 py-1">
                <span className="font-heading text-[11px] font-semibold tracking-[0.02em]" style={{ color: classAccent }}>{r.name}</span>
                <div className="flex gap-1">
                  {Array.from({ length: r.maxUses }).map((_, j) => {
                    const newUses = j < r.currentUses ? j : j + 1;
                    return (
                      <IconButton key={j}
                        label={`Set ${r.name} to ${newUses} of ${r.maxUses} uses`}
                        onClick={() => {
                          const updated = [...featResources];
                          updated[i] = { ...r, currentUses: newUses };
                          saveField({ featResources: JSON.stringify(updated) });
                        }}
                        className="w-4 h-4 rounded-full border transition-colors"
                        style={{
                          backgroundColor: j < r.currentUses ? classAccent : 'transparent',
                          borderColor: j < r.currentUses ? classAccent : 'var(--color-rule-light)',
                        }}
                      />
                    );
                  })}
                </div>
                <span className="font-body text-[13px] text-faint">{r.currentUses}/{r.maxUses}</span>
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
              className="font-body text-[14px] font-semibold text-cls-wizard hover:text-ink bg-page-alt border border-rule px-2 py-1 transition-colors"
            >+ Temp HP</button>
          </div>
        )}

        {/* Resource Pools (M24.5) */}
        <ResourcePoolDisplay
          pools={char.resourcePools}
          editable
          onSpend={(poolId) => {
            const updated = resourcePools.map(p =>
              p.poolId === poolId ? { ...p, currentUses: Math.max(0, p.currentUses - 1) } : p
            );
            saveField({ resourcePools: JSON.stringify(updated) });
          }}
          onRecover={(poolId) => {
            const updated = resourcePools.map(p =>
              p.poolId === poolId ? { ...p, currentUses: Math.min(p.maxUses, p.currentUses + 1) } : p
            );
            saveField({ resourcePools: JSON.stringify(updated) });
          }}
        />

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
                className={`flex items-center gap-1.5 px-3 py-2 font-body text-[15px] font-semibold transition-colors whitespace-nowrap ${
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
            className="ml-2 px-2 py-1.5 bg-page-alt border border-rule font-body text-[14px] font-semibold text-muted focus:outline-none focus:border-muted shrink-0"
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
        {error && <div role="alert" className="bg-debuff-bg border border-debuff text-debuff p-3 mt-4 font-body text-[15px] font-semibold">{error}</div>}
        {success && <div role="status" className="bg-buff-bg border border-buff text-buff p-3 mt-4 font-body text-[15px] font-semibold">{success}</div>}
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
        <div className="fixed top-4 left-1/2 -translate-x-1/2 z-50 bg-buff-bg border border-buff text-buff px-6 py-3 shadow-lg font-body text-[16px] font-semibold animate-pulse">
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
        <Modal
          title="Level Down"
          size="sm"
          onClose={() => setLevelDownConfirm(false)}
          footer={(
            <>
              <Button variant="secondary" className="flex-1" onClick={() => setLevelDownConfirm(false)}>Cancel</Button>
              <Button variant="danger" className="flex-1" onClick={handleLevelDown}>Remove Level</Button>
            </>
          )}
        >
          <p className="font-body text-[15px] font-semibold text-muted">
            Remove level {char.level}? This will reverse HP, features, and any ASI choices made at that level.
          </p>
        </Modal>
      )}

      {/* Rest Modal */}
      {restModal && (
        <Modal
          title={restModal === 'short' ? 'Short Rest' : 'Long Rest'}
          size="sm"
          onClose={closeRestModal}
          footer={(
            <>
              <Button variant="secondary" className="flex-1" onClick={closeRestModal}>Cancel</Button>
              <Button className="flex-1" onClick={restModal === 'short' ? handleShortRest : handleLongRest}>
                {restModal === 'short' ? 'Short Rest' : 'Long Rest'}
              </Button>
            </>
          )}
        >
          {restModal === 'short' ? (
            <div className="space-y-3">
              <p className="font-body text-[15px] font-semibold text-muted">
                Spend hit dice to regain hit points. For each die spent, regain 1d{Object.values(hitDiceMap)[0]?.faces ?? 8} + CON modifier HP.
              </p>
              <div className="space-y-2">
                {Object.entries(hitDiceMap).map(([cls, hd]) => (
                  <div key={cls} className="flex items-center justify-between bg-page-alt border border-rule px-3 py-2">
                    <span className="font-body text-[15px] font-semibold text-ink">{cls} (d{hd.faces})</span>
                    <div className="flex items-center gap-2">
                      <span className="font-body text-[13px] text-faint">{hd.remaining}/{hd.total} remaining</span>
                      <IconButton
                        label={`Spend one fewer ${cls} hit die`}
                        onClick={() => setShortRestDice(prev => ({ ...prev, [cls]: Math.max(0, (prev[cls] || 0) - 1) }))}
                        disabled={!shortRestDice[cls]}
                        className="w-7 h-7 bg-page border border-rule text-muted font-body text-[15px] disabled:opacity-30 hover:bg-rule-light"
                      >−</IconButton>
                      <span className="font-heading text-[15px] font-bold text-ink w-4 text-center">{shortRestDice[cls] || 0}</span>
                      <IconButton
                        label={`Spend one more ${cls} hit die`}
                        onClick={() => setShortRestDice(prev => ({ ...prev, [cls]: Math.min(hd.remaining, (prev[cls] || 0) + 1) }))}
                        disabled={(shortRestDice[cls] || 0) >= hd.remaining}
                        className="w-7 h-7 bg-page border border-rule text-muted font-body text-[15px] disabled:opacity-30 hover:bg-rule-light"
                      >+</IconButton>
                    </div>
                  </div>
                ))}
              </div>
              {showShortRestAbilities && (
                <div className="space-y-1">
                  <p className="font-body text-[13px] font-semibold text-cls-monk">Restored on short rest:</p>
                  {hasPactSlots && <p className="font-body text-[13px] text-faint ml-2">• Warlock pact slots</p>}
                  {shortRestPools.map(p => (
                    <p key={p.poolId} className="font-body text-[13px] text-faint ml-2">
                      • {p.displayName} ({p.currentUses}/{p.maxUses} → {p.maxUses}/{p.maxUses})
                    </p>
                  ))}
                  {shortRestFeats.map(r => (
                    <p key={r.featName + r.name} className="font-body text-[13px] text-faint ml-2">
                      • {r.name} ({r.currentUses}/{r.maxUses} → {r.maxUses}/{r.maxUses})
                    </p>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <div className="space-y-3">
              <p className="font-body text-[15px] font-semibold text-muted">
                Regain all hit points, reset spell slots, and regain half your total hit dice (minimum 1).
              </p>
              {showLongRestAbilities && (
                <div className="space-y-1">
                  <p className="font-body text-[13px] font-semibold text-cls-monk">All features restored:</p>
                  {longRestPools.map(p => (
                    <p key={p.poolId} className="font-body text-[13px] text-faint ml-2">
                      • {p.displayName} ({p.currentUses}/{p.maxUses} → {p.maxUses}/{p.maxUses})
                    </p>
                  ))}
                  {longRestFeats.map(r => (
                    <p key={r.featName + r.name} className="font-body text-[13px] text-faint ml-2">
                      • {r.name} ({r.currentUses}/{r.maxUses} → {r.maxUses}/{r.maxUses})
                    </p>
                  ))}
                </div>
              )}
            </div>
          )}
        </Modal>
      )}
    </div>
  );
}
