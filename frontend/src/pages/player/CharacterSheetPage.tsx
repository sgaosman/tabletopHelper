import { useState, useCallback, useEffect, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Shield, Swords, BookOpen, Backpack, Star, ScrollText, Heart, Moon, Sun, ChevronUp, ChevronDown, Skull } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useCampaigns } from '../../queries/campaigns';
import {
  useChangeHitPoints, useCharacter, useLevelDown, useLongRest, useRecoverPool, useSetCampaign, useShortRest,
  useSpendPool,
} from '../../queries/characters';
import LevelUpModal from '../../components/character/LevelUpModal';
import ChoiceDialog from '../../components/character/ChoiceDialog';
import PendingChoicesBanner from '../../components/character/PendingChoicesBanner';
import Button from '../../components/ui/Button';
import IconButton from '../../components/ui/IconButton';
import Input from '../../components/ui/Input';
import Modal from '../../components/ui/Modal';
import type {
  PlayerCharacter, Currency, HitDicePool, HitDieSpent, HitPointChange, LevelUpResponse, PendingChoice,
  ShortRestResponse,
} from '../../types/character';
import type { SpellSlot } from '../../types/encounter';
import { formatClasses, formatMod } from '../../utils/dndRules';
import { getClassColour } from '../../utils/classColours';
import StatsTab from './sheet/StatsTab';
import ActionsTab from './sheet/ActionsTab';
import SpellsTab from './sheet/SpellsTab';
import InventoryTab from './sheet/InventoryTab';
import FeaturesTab from './sheet/FeaturesTab';
import ResourcePoolDisplay from '../../components/common/ResourcePoolDisplay';
import ArcaneRecoveryPicker from '../../components/character/ArcaneRecoveryPicker';
import JournalTab from './sheet/JournalTab';
import type { RunCommand } from './sheet/types';
import { ARCANE_RECOVERY_POOL, arcaneRecoveryLevels, type ArcaneRecoveryChoice } from '../../utils/classFeatures';

type Tab = 'Stats' | 'Actions' | 'Spells' | 'Inventory' | 'Features' | 'Journal';
const TABS: { key: Tab; label: string; icon: typeof Shield }[] = [
  { key: 'Stats', label: 'Stats', icon: Shield },
  { key: 'Actions', label: 'Actions', icon: Swords },
  { key: 'Spells', label: 'Spells', icon: BookOpen },
  { key: 'Inventory', label: 'Inventory', icon: Backpack },
  { key: 'Features', label: 'Features', icon: Star },
  { key: 'Journal', label: 'Journal', icon: ScrollText },
];

const HIT_POINT_DIALOGS: Record<HitPointChange, { title: string; action: string }> = {
  DAMAGE: { title: 'Take Damage', action: 'Take Damage' },
  HEALING: { title: 'Heal', action: 'Heal' },
  TEMPORARY: { title: 'Temporary Hit Points', action: 'Gain' },
};

// A whole number the player entered, such as a Hit Die's roll; undefined for none, and NaN for anything else
function wholeNumber(text: string | undefined): number | undefined {
  const trimmed = text?.trim() ?? '';
  if (trimmed === '') return undefined;
  return /^\d+$/.test(trimmed) ? Number(trimmed) : NaN;
}

// A short rest's result: the hit points regained, then a line for each Hit Die spent, saying what it rolled and who
// rolled it
function shortRestMessage(hpBefore: number, { character, rolls }: ShortRestResponse): string {
  const regained = character.hpCurrent - hpBefore;
  return [
    regained > 0 ? `Short rest taken: ${regained} hit points regained` : 'Short rest taken',
    ...rolls.map(die => `${die.className} d${die.dieSize}: ${die.rolledByServer ? 'the server rolled' : 'you rolled'}`
      + ` ${die.roll}, healing ${die.healing}`),
  ].join('\n');
}

/** A run of Pending Choices whose dialogs open one after another: the one open, and those still to come. */
interface ChoiceRun {
  current: string;
  queue: string[];
}

/**
 * A character's sheet. Every change is a command to the server (D158), and the character it returns replaces the
 * cached one, so the sheet shows it without loading the character again. The sheet works nothing out itself: rests,
 * hit points, slots and pools are the server's. While the character is in an encounter that is under way, resting,
 * levelling and making choices wait for it to end, and the sheet is fetched again every 10 seconds, so it shows what
 * the encounter writes back when the DM ends it (D166). A character who died can't rest, change its hit points or
 * level until the DM revives it.
 *
 * The choices its levels owe are its Pending Choices (D159), on a banner with a button for each. Levelling up opens
 * the new level's dialogs one after another, and a subclass can bring more, such as an Eldritch Knight's spells, which
 * open next. A choice skipped stays on the banner for later.
 */
export default function CharacterSheetPage() {
  const { characterId } = useParams<{ characterId: string }>();
  const navigate = useNavigate();
  // Fetched again now and then, so the sheet shows what an encounter writes back when it ends (D166)
  const characterQuery = useCharacter(characterId, { followChanges: true });
  const char = characterQuery.data;
  const { data: campaigns = [] } = useCampaigns();
  const setCampaign = useSetCampaign();
  const changeHitPoints = useChangeHitPoints();
  const spendPool = useSpendPool();
  const recoverPool = useRecoverPool();
  const shortRest = useShortRest();
  const longRest = useLongRest();
  const levelDown = useLevelDown();
  const [activeTab, setActiveTab] = useState<Tab>('Stats');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [restModal, setRestModal] = useState<'short' | 'long' | null>(null);
  const [shortRestDice, setShortRestDice] = useState<Record<string, number>>({});
  const [shortRestRolls, setShortRestRolls] = useState<Record<string, string[]>>({});
  const [arcaneRecovery, setArcaneRecovery] = useState<ArcaneRecoveryChoice>({});
  const [hitPointDialog, setHitPointDialog] = useState<HitPointChange | null>(null);
  const [hitPointAmount, setHitPointAmount] = useState('');
  const [showLevelUp, setShowLevelUp] = useState(false);
  const [levelUpBanner, setLevelUpBanner] = useState<string | null>(null);
  const [levelDownConfirm, setLevelDownConfirm] = useState(false);
  const [choiceRun, setChoiceRun] = useState<ChoiceRun | null>(null);
  // The level gained, announced once its choices' dialogs have run
  const [levelGained, setLevelGained] = useState<number | null>(null);

  // Sends a command and shows how it went: `done` for a moment on success, a moment for each of its lines, or the
  // server's message
  const runCommand = useCallback<RunCommand>(async (command, done) => {
    setError('');
    try {
      const result = await command;
      const message = typeof done === 'function' ? done(result) : done ?? 'Saved';
      setSuccess(message);
      setTimeout(() => setSuccess(''), 2500 * message.split('\n').length);
      return true;
    } catch (err) {
      setError(apiErrorMessage(err));
      return false;
    }
  }, []);

  const savingThrows = useMemo(() => char?.savingThrowProficiencies ?? [], [char?.savingThrowProficiencies]);
  const skillProfs = useMemo(() => char?.skillProficiencies ?? [], [char?.skillProficiencies]);
  const skillExpertises = useMemo(() => char?.skillExpertises ?? [], [char?.skillExpertises]);
  const features = useMemo(() => char?.features ?? [], [char?.features]);
  const equipment = useMemo(() => char?.equipment ?? [], [char?.equipment]);
  const currency = useMemo<Currency>(() => char?.currency ?? { cp: 0, sp: 0, ep: 0, gp: 0, pp: 0 }, [char?.currency]);
  const spellsKnown = useMemo(() => char?.spellsKnown ?? [], [char?.spellsKnown]);
  const spellSlots = useMemo<Record<string, SpellSlot>>(() => char?.spellSlots ?? {}, [char?.spellSlots]);
  const resistances = useMemo(() => char?.damageResistances ?? [], [char?.damageResistances]);
  const hitDice = useMemo<Record<string, HitDicePool>>(() => char?.hitDice ?? {}, [char?.hitDice]);
  const resourcePools = useMemo(() => char?.resourcePools ?? [], [char?.resourcePools]);

  // What each rest brings back, for its dialog
  const shortRestPools = useMemo(() => resourcePools.filter(p => p.resetOn === 'shortRest' && p.currentUses < p.maxUses),
    [resourcePools]);
  const hasPactSlots = useMemo(() => Object.keys(spellSlots).some(k => k.startsWith('pact_')), [spellSlots]);
  const showShortRestAbilities = shortRestPools.length > 0 || hasPactSlots;
  const longRestPools = useMemo(() => resourcePools.filter(p => p.currentUses < p.maxUses), [resourcePools]);
  // A wizard's Arcane Recovery, once a day as a short rest ends (PHB p.115, M17)
  const wizardLevel = useMemo(() => {
    const entries = char?.multiclassEntries ?? [];
    if (entries.length > 0) {
      return entries.filter(e => e.className === 'Wizard').reduce((sum, e) => sum + e.level, 0);
    }
    return char?.characterClass === 'Wizard' ? (char.level ?? 0) : 0;
  }, [char?.multiclassEntries, char?.characterClass, char?.level]);
  const arcaneRecoveryUsed = useMemo(() => resourcePools.some(p => p.poolId === ARCANE_RECOVERY_POOL
    && p.currentUses < 1), [resourcePools]);
  const showLongRestAbilities = longRestPools.length > 0;

  const classAccent = getClassColour(char?.characterClass);

  const announceLevel = useCallback((level: number) => {
    setLevelUpBanner(`Level up to ${level} complete!`);
    setTimeout(() => setLevelUpBanner(null), 4000);
  }, []);

  // Opens the next of the run's choices still pending, or ends the run, announcing a level gained
  const continueRun = useCallback((queue: string[], pending: PendingChoice[]) => {
    const [next, ...rest] = queue.filter(id => pending.some(choice => choice.id === id));
    if (next) {
      setChoiceRun({ current: next, queue: rest });
      return;
    }
    setChoiceRun(null);
    if (levelGained !== null) {
      announceLevel(levelGained);
      setLevelGained(null);
    }
  }, [levelGained, announceLevel]);

  // A choice the run was on that is no longer pending, made in another tab say, moves the run on
  const pendingChoices = char?.pendingChoices;
  useEffect(() => {
    if (choiceRun && pendingChoices && !pendingChoices.some(choice => choice.id === choiceRun.current)) {
      continueRun(choiceRun.queue, pendingChoices);
    }
  }, [choiceRun, pendingChoices, continueRun]);

  if (!char) {
    return (
      <div className="min-h-screen bg-page flex items-center justify-center">
        {characterQuery.isPending
          ? <p className="font-body text-[16px] text-muted">Loading character...</p>
          : <p role="alert" className="font-body text-[16px] text-debuff">{apiErrorMessage(characterQuery.error)}</p>}
      </div>
    );
  }

  const waitingForEncounter = char.inLiveEncounter;
  // A character who died waits for the DM to revive them: no rests, hit point changes or levels (D166)
  const dead = char.dead;

  // Each die spent, with the roll entered or none for the server to roll; null while a roll entered can't be right
  const diceToSpend: HitDieSpent[] | null = (() => {
    const dice: HitDieSpent[] = [];
    for (const [className, count] of Object.entries(shortRestDice)) {
      for (let i = 0; i < count; i++) {
        const roll = wholeNumber(shortRestRolls[className]?.[i]);
        if (roll === undefined) {
          dice.push({ className });
        } else if (Number.isNaN(roll) || roll < 1 || roll > (hitDice[className]?.dieSize ?? 0)) {
          return null;
        } else {
          dice.push({ className, roll });
        }
      }
    }
    return dice;
  })();

  function setDiceCount(className: string, count: number) {
    setShortRestDice(prev => ({ ...prev, [className]: count }));
    setShortRestRolls(prev => ({ ...prev, [className]: (prev[className] ?? []).slice(0, count) }));
  }

  function setRoll(className: string, index: number, text: string) {
    setShortRestRolls(prev => {
      const rolls = [...(prev[className] ?? [])];
      rolls[index] = text;
      return { ...prev, [className]: rolls };
    });
  }

  function closeRestModal() {
    setRestModal(null);
    setShortRestDice({});
    setShortRestRolls({});
    setArcaneRecovery({});
  }

  async function handleShortRest() {
    if (!char || diceToSpend === null) return;
    const before = char.hpCurrent;
    const recovered = arcaneRecoveryLevels(arcaneRecovery);
    const rested = await runCommand(shortRest.mutateAsync({
      characterId: char.id,
      rest: { hitDice: diceToSpend, arcaneRecovery: recovered.length > 0 ? recovered : undefined },
    }), result => shortRestMessage(before, result));
    if (rested) closeRestModal();
  }

  async function handleLongRest() {
    if (!char) return;
    if (await runCommand(longRest.mutateAsync({ characterId: char.id }), 'Long rest taken')) {
      setRestModal(null);
    }
  }

  function openHitPointDialog(change: HitPointChange) {
    setHitPointAmount('');
    setHitPointDialog(change);
  }

  const hitPointValue = wholeNumber(hitPointAmount);
  const hitPointAmountValid = hitPointValue !== undefined && !Number.isNaN(hitPointValue) && hitPointValue >= 1;

  async function handleHitPoints() {
    if (!char || !hitPointDialog || !hitPointAmountValid || hitPointValue === undefined) return;
    if (await runCommand(changeHitPoints.mutateAsync({
      characterId: char.id, change: { kind: hitPointDialog, amount: hitPointValue },
    }))) {
      setHitPointDialog(null);
    }
  }

  async function handleCampaignChange(value: string) {
    if (!char) return;
    await runCommand(setCampaign.mutateAsync({ characterId: char.id, campaignId: value || null }), 'Campaign updated');
  }

  // The new level's choices open one after another; a level that owes none is announced at once
  function handleLevelUpComplete(response: LevelUpResponse) {
    setShowLevelUp(false);
    const level = response.character.level;
    const owed = response.character.pendingChoices.filter(choice => choice.characterLevel === level).map(c => c.id);
    if (owed.length === 0) {
      announceLevel(level);
      return;
    }
    setLevelGained(level);
    setChoiceRun({ current: owed[0], queue: owed.slice(1) });
  }

  // A choice made can bring others, such as an Eldritch Knight's spells with its subclass, which open next
  function handleChoiceComplete(updated: PlayerCharacter) {
    if (!char || !choiceRun) return;
    const before = new Set(char.pendingChoices.map(choice => choice.id));
    const brought = updated.pendingChoices.filter(choice => !before.has(choice.id)).map(choice => choice.id);
    continueRun([...brought, ...choiceRun.queue], updated.pendingChoices);
  }

  function handleChoiceSkip() {
    if (!char || !choiceRun) return;
    continueRun(choiceRun.queue, char.pendingChoices);
  }

  // Closing a dialog stops the run; its choices stay on the banner
  function handleChoiceClose() {
    setChoiceRun(null);
    if (levelGained !== null) {
      announceLevel(levelGained);
      setLevelGained(null);
    }
  }

  const currentChoice = choiceRun ? char.pendingChoices.find(choice => choice.id === choiceRun.current) : undefined;

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
              Level {char.level} {char.race} {formatClasses(char)}
            </p>
          </div>
          <div className="flex items-center gap-1">
            <IconButton
              label="Level Down"
              onClick={() => setLevelDownConfirm(true)}
              disabled={char.level <= 1 || waitingForEncounter || dead}
              className="p-2 text-debuff hover:bg-page-alt transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <ChevronDown className="w-4 h-4" />
            </IconButton>
            <IconButton
              label="Level Up"
              onClick={() => setShowLevelUp(true)}
              disabled={char.level >= 20 || waitingForEncounter || dead}
              className="p-2 text-buff hover:bg-page-alt transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            >
              <ChevronUp className="w-4 h-4" />
            </IconButton>
            <div className="w-px h-5 bg-rule mx-1" />
            <IconButton label="Short Rest" onClick={() => setRestModal('short')} disabled={waitingForEncounter || dead}
              className="p-2 text-cls-monk hover:bg-page-alt transition-colors disabled:opacity-30 disabled:cursor-not-allowed">
              <Moon className="w-4 h-4" />
            </IconButton>
            <IconButton label="Long Rest" onClick={() => setRestModal('long')} disabled={waitingForEncounter || dead}
              className="p-2 text-cls-wizard hover:bg-page-alt transition-colors disabled:opacity-30 disabled:cursor-not-allowed">
              <Sun className="w-4 h-4" />
            </IconButton>
          </div>
        </div>
        {waitingForEncounter && (
          <p className="max-w-5xl mx-auto mt-2 font-body text-[13px] font-semibold text-muted text-right">
            In an encounter that is under way: resting, levelling and choices wait until it ends.
          </p>
        )}
      </header>

      {/* Dead until the DM revives them, at 1 hit point (D166) */}
      {dead && (
        <div className="max-w-5xl mx-auto px-4 pt-3">
          <div role="status" className="flex items-start gap-2 px-4 py-2.5 bg-debuff text-card">
            <Skull className="w-4 h-4 shrink-0 mt-0.5" />
            <p className="font-body text-[15px] font-semibold">
              {char.name} died in an encounter. Until the DM revives them, they can't rest, be healed, level or join
              an encounter.
            </p>
          </div>
        </div>
      )}

      {/* HP Bar */}
      <div className="max-w-5xl mx-auto px-4 py-3">
        <div className="flex items-center gap-4 flex-wrap">
          <div className="flex items-center gap-2">
            <Heart className="w-4 h-4 text-debuff" />
            <span className="font-heading text-[14px] font-bold text-ink">{char.hpCurrent}/{char.hpMax}</span>
            {char.hpTemp > 0 && <span className="font-heading text-[13px] font-semibold text-cls-wizard">+{char.hpTemp} temp</span>}
          </div>
          <div className="flex-1 min-w-24 h-2 bg-rule overflow-hidden">
            <div
              className={`h-full transition-all ${
                char.hpCurrent / char.hpMax > 0.5 ? 'bg-buff' :
                char.hpCurrent / char.hpMax > 0.25 ? 'bg-cls-monk' : 'bg-debuff'
              }`}
              style={{ width: `${Math.min(100, (char.hpCurrent / char.hpMax) * 100)}%` }}
            />
          </div>
          <div className="flex gap-1">
            <Button size="sm" variant="secondary" onClick={() => openHitPointDialog('DAMAGE')} disabled={dead}>Damage</Button>
            <Button size="sm" variant="secondary" onClick={() => openHitPointDialog('HEALING')} disabled={dead}>Heal</Button>
            <Button size="sm" variant="secondary" onClick={() => openHitPointDialog('TEMPORARY')} disabled={dead}>+ Temp HP</Button>
          </div>
          <div className="flex gap-3">
            <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">AC <span className="font-heading text-[15px] font-bold text-ink">{char.armourClass}</span></span>
            <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Init <span className="font-heading text-[15px] font-bold text-ink">{formatMod(char.initiativeBonus)}</span></span>
            <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Speed <span className="font-heading text-[15px] font-bold text-ink">{char.speed}ft</span></span>
          </div>
        </div>

        {/* Resource pools, feats' limited uses among them (D158) */}
        <div className="mt-2">
          <ResourcePoolDisplay
            pools={resourcePools}
            editable
            onSpend={poolId => void runCommand(spendPool.mutateAsync({ characterId: char.id, use: { poolId } }))}
            onRecover={poolId => void runCommand(recoverPool.mutateAsync({ characterId: char.id, use: { poolId } }))}
          />
        </div>

        {/* The choices its levels owe (D159), which wait while it is in an encounter that is under way */}
        {char.pendingChoices.length > 0 && (
          <div className="mt-3">
            <PendingChoicesBanner
              choices={char.pendingChoices}
              disabled={waitingForEncounter}
              onChoose={choice => setChoiceRun({ current: choice.id, queue: [] })}
            />
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
            aria-label="Campaign"
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
        {success && <div role="status" className="bg-buff-bg border border-buff text-buff p-3 mt-4 font-body text-[15px] font-semibold whitespace-pre-line">{success}</div>}
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
          <SpellsTab char={char} spellsKnown={spellsKnown} spellSlots={spellSlots} runCommand={runCommand} />
        )}
        {activeTab === 'Inventory' && (
          <InventoryTab characterId={char.id} equipment={equipment} currency={currency}
            armourEntries={char.armourClassBreakdown.armourEntries} runCommand={runCommand} />
        )}
        {activeTab === 'Features' && (
          <FeaturesTab features={features} char={char} />
        )}
        {activeTab === 'Journal' && (
          <JournalTab char={char} runCommand={runCommand} />
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

      {/* The dialog of the Pending Choice the run is on; each is keyed by its choice, so each opens afresh */}
      {currentChoice && (
        <ChoiceDialog
          key={currentChoice.id}
          character={char}
          choice={currentChoice}
          onComplete={handleChoiceComplete}
          onSkip={handleChoiceSkip}
          onClose={handleChoiceClose}
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
            Remove level {char.level}? This reverses its hit points, its features and the choices made for it, and drops those it still owes.
          </p>
        </Modal>
      )}

      {/* Damage, healing and temporary hit points */}
      {hitPointDialog && (
        <Modal
          title={HIT_POINT_DIALOGS[hitPointDialog].title}
          size="sm"
          onClose={() => setHitPointDialog(null)}
          dismissible={!changeHitPoints.isPending}
          footer={(
            <>
              <Button variant="secondary" className="flex-1" onClick={() => setHitPointDialog(null)}
                disabled={changeHitPoints.isPending}>Cancel</Button>
              <Button className="flex-1" onClick={handleHitPoints} disabled={!hitPointAmountValid}
                loading={changeHitPoints.isPending}>{HIT_POINT_DIALOGS[hitPointDialog].action}</Button>
            </>
          )}
        >
          <form onSubmit={e => { e.preventDefault(); void handleHitPoints(); }} className="space-y-2">
            <Input label="Amount" type="number" inputMode="numeric" min={1} autoFocus value={hitPointAmount}
              onChange={e => setHitPointAmount(e.target.value)} />
            <p className="font-body text-[13px] text-faint">
              {hitPointDialog === 'DAMAGE' && 'Temporary hit points take the damage first, and hit points stop at 0.'}
              {hitPointDialog === 'HEALING' && 'Hit points stop at the maximum; temporary hit points stay as they are.'}
              {hitPointDialog === 'TEMPORARY' && 'Temporary hit points don\'t stack: you keep the larger amount.'}
            </p>
          </form>
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
              <Button
                className="flex-1"
                onClick={restModal === 'short' ? handleShortRest : handleLongRest}
                disabled={restModal === 'short' && diceToSpend === null}
                loading={shortRest.isPending || longRest.isPending}
              >
                {restModal === 'short' ? 'Short Rest' : 'Long Rest'}
              </Button>
            </>
          )}
        >
          {restModal === 'short' ? (
            <div className="space-y-3">
              <p className="font-body text-[15px] font-semibold text-muted">
                Spend hit dice to regain hit points. Each die heals its roll plus your CON modifier. Enter the rolls you make, or leave a die blank for the server to roll it.
              </p>
              <div className="space-y-2">
                {Object.entries(hitDice).map(([cls, hd]) => {
                  const count = shortRestDice[cls] || 0;
                  return (
                    <div key={cls} className="bg-page-alt border border-rule px-3 py-2 space-y-2">
                      <div className="flex items-center justify-between">
                        <span className="font-body text-[15px] font-semibold text-ink">{cls} (d{hd.dieSize})</span>
                        <div className="flex items-center gap-2">
                          <span className="font-body text-[13px] text-faint">{hd.remaining}/{hd.total} remaining</span>
                          <IconButton
                            label={`Spend one fewer ${cls} hit die`}
                            onClick={() => setDiceCount(cls, Math.max(0, count - 1))}
                            disabled={!count}
                            className="w-7 h-7 bg-page border border-rule text-muted font-body text-[15px] disabled:opacity-30 hover:bg-rule-light"
                          >−</IconButton>
                          <span className="font-heading text-[15px] font-bold text-ink w-4 text-center">{count}</span>
                          <IconButton
                            label={`Spend one more ${cls} hit die`}
                            onClick={() => setDiceCount(cls, Math.min(hd.remaining, count + 1))}
                            disabled={count >= hd.remaining}
                            className="w-7 h-7 bg-page border border-rule text-muted font-body text-[15px] disabled:opacity-30 hover:bg-rule-light"
                          >+</IconButton>
                        </div>
                      </div>
                      {count > 0 && (
                        <div className="grid grid-cols-3 gap-2">
                          {Array.from({ length: count }).map((_, i) => {
                            const roll = wholeNumber(shortRestRolls[cls]?.[i]);
                            const invalid = roll !== undefined && (Number.isNaN(roll) || roll < 1 || roll > hd.dieSize);
                            return (
                              <Input
                                key={i}
                                label={`${cls} die ${i + 1} roll`}
                                hideLabel
                                type="number"
                                inputMode="numeric"
                                min={1}
                                max={hd.dieSize}
                                placeholder="Roll for me"
                                value={shortRestRolls[cls]?.[i] ?? ''}
                                onChange={e => setRoll(cls, i, e.target.value)}
                                error={invalid ? `1 to ${hd.dieSize}` : undefined}
                              />
                            );
                          })}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
              {wizardLevel > 0 && (
                <ArcaneRecoveryPicker wizardLevel={wizardLevel} slots={spellSlots} usedToday={arcaneRecoveryUsed}
                  choice={arcaneRecovery} onChange={setArcaneRecovery} />
              )}
              {showShortRestAbilities && (
                <div className="space-y-1">
                  <p className="font-body text-[13px] font-semibold text-cls-monk">Restored on short rest:</p>
                  {hasPactSlots && <p className="font-body text-[13px] text-faint ml-2">• Warlock pact slots</p>}
                  {shortRestPools.map(p => (
                    <p key={p.poolId} className="font-body text-[13px] text-faint ml-2">
                      • {p.displayName} ({p.currentUses}/{p.maxUses} → {p.maxUses}/{p.maxUses})
                    </p>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <div className="space-y-3">
              <p className="font-body text-[15px] font-semibold text-muted">
                Regain all hit points, lose temporary hit points, reset spell slots, and regain half your total hit dice (minimum 1), the largest first. You need at least 1 hit point to benefit.
              </p>
              {showLongRestAbilities && (
                <div className="space-y-1">
                  <p className="font-body text-[13px] font-semibold text-cls-monk">All features restored:</p>
                  {longRestPools.map(p => (
                    <p key={p.poolId} className="font-body text-[13px] text-faint ml-2">
                      • {p.displayName} ({p.currentUses}/{p.maxUses} → {p.maxUses}/{p.maxUses})
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
