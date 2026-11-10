import { Fragment } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider } from '../../context/EncounterContext';
import { useEncounter } from '../../hooks/useEncounter';
import { apiErrorMessage } from '../../api/errors';
import { useCombatLog, useEncounterAction } from '../../queries/encounters';
import type { CombatLogEntry } from '../../types/combat';
import type { EncounterParticipant, SpellEffect, SpellTrigger } from '../../types/encounter';
import SpellCastModal from '../../components/encounter/SpellCastModal';
import RepeatEffectModal from '../../components/encounter/RepeatEffectModal';
import VisibilityToggle from '../../components/encounter/VisibilityToggle';
import LairToggle from '../../components/encounter/LairToggle';
import LairTurnRow from '../../components/encounter/LairTurnRow';
import TieOrderArrows from '../../components/encounter/TieOrderArrows';
import EndEncounterModal from '../../components/encounter/EndEncounterModal';
import UndoDialog from '../../components/encounter/UndoDialog';
import ParticipantRow from '../../components/encounter/ParticipantRow';
import LifeStateBadge from '../../components/encounter/LifeStateBadge';
import ConditionChips from '../../components/encounter/ConditionChips';
import ActiveSpells from '../../components/encounter/ActiveSpells';
import TriggerReminder from '../../components/encounter/TriggerReminder';
import DeathSaves from '../../components/encounter/DeathSaves';
import SpellSlotPips from '../../components/encounter/SpellSlotPips';
import TurnEconomyBar from '../../components/encounter/TurnEconomyBar';
import HpReadout from '../../components/encounter/HpReadout';
import CombatLog from '../../components/encounter/CombatLog';
import ConnectionStatus, { ConnectionBanner } from '../../components/encounter/ConnectionStatus';
import ConditionForm from '../../components/encounter/ConditionForm';
import ExhaustionControl from '../../components/common/ExhaustionControl';
import ConcentrationForm from '../../components/encounter/ConcentrationForm';
import ActiveSpellForm from '../../components/encounter/ActiveSpellForm';
import ManualAttackForm from '../../components/encounter/ManualAttackForm';
import InlineMonsterActions, { type MonsterActionTab } from '../../components/encounter/InlineMonsterActions';
import StandardActionMenu from '../../components/encounter/StandardActionMenu';
import ReactionMenu from '../../components/encounter/ReactionMenu';
import HitFollowUps from '../../components/encounter/HitFollowUps';
import InspirationControls from '../../components/encounter/InspirationControls';
import SummonAwaitingBanner from '../../components/encounter/SummonAwaitingBanner';
import SummonPlaceModal from '../../components/encounter/SummonPlaceModal';
import AddCompanionModal from '../../components/encounter/AddCompanionModal';
import { FIELD_INPUT, FIELD_LABEL, SUBMIT_BUTTON } from '../../components/encounter/formStyles';
import ResourcePoolDisplay from '../../components/common/ResourcePoolDisplay';
import { wildShapePoolId } from '../../utils/classFeatures';
import { formOf, reshapingSpell } from '../../utils/formSpells';
import ChangeFormControl from '../../components/encounter/ChangeFormControl';
import Button from '../../components/ui/Button';
import IconButton from '../../components/ui/IconButton';
import Modal from '../../components/ui/Modal';
import { getParticipantColour, getParticipantBg } from '../../utils/classColours';
import { DAMAGE_TYPES } from '../../utils/conditions';
import {
  ArrowLeft, Pause, Play, Flag, Copy, Check,
  ChevronRight, ChevronLeft, Heart, HeartPulse, Swords,
  Plus, Minus, X, Zap, Crosshair, Sparkles, Trash2, Footprints, ShieldAlert, Undo2, UserPlus
} from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';

// The row's quick actions: a 44px target on a phone, compact beside the row on a wider screen
const QUICK_ACTION = 'inline-flex items-center justify-center size-11 sm:size-auto sm:p-1.5';

// The encounter controls in the bar under the header, each at least 44px tall for a phone
const CONTROL = 'flex items-center min-h-11 font-body text-[16px] font-semibold disabled:opacity-50 disabled:cursor-wait';

type ActionMode = 'attack' | 'damage' | 'heal' | 'set-hp' | 'condition' | 'concentration' | 'spell'
  | 'standard' | 'reaction' | null;

/** A Spell Effect the DM is using: its caster's use, or what its area does at a moment of a turn (M15). */
interface EffectInUse {
  casterId: string;
  effectId: string;
  trigger?: SpellTrigger;
  targetId?: string;
}

/** Damage, healing and Set HP: an amount, with a damage type or temporary HP. */
function HpChangeForm({ target, mode }: { target: EncounterParticipant; mode: 'damage' | 'heal' | 'set-hp' }) {
  const actions = useEncounterAction();
  const [amount, setAmount] = useState('');
  const [damageType, setDamageType] = useState('');
  const [tempHp, setTempHp] = useState('');
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  // A failure shows as a toast and keeps the amount, so it can be sent again
  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (loading) return;
    setLoading(true);
    try {
      if (mode === 'damage' && amount) {
        await actions.applyDamage(target.id, parseInt(amount), damageType || undefined);
      } else if (mode === 'heal' && amount) {
        await actions.applyHealing(target.id, parseInt(amount));
      } else if (mode === 'set-hp' && amount !== '') {
        await actions.setHp(target.id, parseInt(amount), tempHp !== '' ? parseInt(tempHp) : undefined);
      }
      setAmount('');
      setDamageType('');
      setTempHp('');
    } catch (err: unknown) {
      toast.errorFrom(err);
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
      {mode === 'set-hp' ? (
        <>
          <div className="flex-1 min-w-32">
            <label htmlFor="set-hp-current" className={FIELD_LABEL}>HP (of {target.hpMax})</label>
            <input id="set-hp-current" type="number" min="0" max={target.hpMax ?? undefined} value={amount}
              onChange={e => setAmount(e.target.value)} className={FIELD_INPUT} autoFocus />
          </div>
          <div className="w-32">
            <label htmlFor="set-hp-temp" className={FIELD_LABEL}>Temp HP</label>
            <input id="set-hp-temp" type="number" min="0" value={tempHp} onChange={e => setTempHp(e.target.value)}
              placeholder="unchanged" className={FIELD_INPUT} />
          </div>
        </>
      ) : (
        <>
          <div className="flex-1 min-w-24">
            <label className={FIELD_LABEL}>Amount</label>
            <input type="number" min="0" value={amount} onChange={e => setAmount(e.target.value)} className={FIELD_INPUT} autoFocus />
          </div>
          {mode === 'damage' && (
            <div className="flex-1 min-w-32">
              <label className={FIELD_LABEL}>Type</label>
              <select value={damageType} onChange={e => setDamageType(e.target.value)} className={FIELD_INPUT}>
                <option value="">&#8212;</option>
                {DAMAGE_TYPES.map(dt => <option key={dt} value={dt}>{dt}</option>)}
              </select>
            </div>
          )}
        </>
      )}
      <button type="submit" disabled={loading} className={SUBMIT_BUTTON}>
        {loading ? '...' : 'Apply'}
      </button>
    </form>
  );
}

function ActionPanel({
  selectedTarget,
  actionMode,
  setActionMode,
  participants,
}: {
  selectedTarget: EncounterParticipant | null;
  actionMode: ActionMode;
  setActionMode: (mode: ActionMode) => void;
  participants: EncounterParticipant[];
}) {
  const actions = useEncounterAction();
  if (!selectedTarget || !actionMode) return null;

  return (
    <div className="bg-card border border-rule p-4 mb-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="font-heading text-[15px] font-semibold tracking-[0.01em] text-muted">
          {actionMode === 'attack' && <><Crosshair className="w-4 h-4 inline mr-1 text-cls-fighter" />Attack {selectedTarget.displayName} (AC {selectedTarget.armourClass})</>}
          {actionMode === 'damage' && <><Swords className="w-4 h-4 inline mr-1 text-debuff" />Damage {selectedTarget.displayName}</>}
          {actionMode === 'heal' && <><Heart className="w-4 h-4 inline mr-1 text-buff" />Heal {selectedTarget.displayName}</>}
          {actionMode === 'set-hp' && <><HeartPulse className="w-4 h-4 inline mr-1 text-buff" />Set HP / revive {selectedTarget.displayName}</>}
          {actionMode === 'condition' && <><Zap className="w-4 h-4 inline mr-1 text-cls-monk" />Add Condition to {selectedTarget.displayName}</>}
          {actionMode === 'concentration' && <><Zap className="w-4 h-4 inline mr-1 text-cls-warlock" />Concentration for {selectedTarget.displayName}</>}
        </h3>
        <IconButton label="Close action panel" onClick={() => setActionMode(null)} className="text-faint hover:text-muted">
          <X className="w-4 h-4" />
        </IconButton>
      </div>

      {actionMode === 'attack' && <ManualAttackForm target={selectedTarget} onRoll={request => actions.rollAttack(request)} />}
      {(actionMode === 'damage' || actionMode === 'heal' || actionMode === 'set-hp') && (
        <HpChangeForm target={selectedTarget} mode={actionMode} />
      )}
      {actionMode === 'condition' && (
        <>
          {/* Exhaustion has levels rather than a plain entry (PHB p.291, D183) */}
          <div className="mb-3">
            <ExhaustionControl
              level={selectedTarget.exhaustionLevel ?? 0}
              name={selectedTarget.displayName}
              onChange={level => actions.setExhaustion(selectedTarget.id, level)}
            />
          </div>
          <ConditionForm onSubmit={(condition, duration) => actions.addCondition(selectedTarget.id, condition, duration)} />
        </>
      )}
      {actionMode === 'concentration' && (
        <>
          <ConcentrationForm onSubmit={spellName => actions.setConcentration(selectedTarget.id, spellName)} />
          {/* A lasting spell cast before now, with its concentration and Ongoing Modifiers (D184) */}
          <div className="mt-4 pt-3 border-t border-rule">
            <ActiveSpellForm caster={selectedTarget} participants={participants}
              onSubmit={request => actions.startActiveSpell(request)} />
          </div>
        </>
      )}
    </div>
  );
}

// ── DmSessionView ──────────────────────────────────────────────────────

function DmSessionView() {
  const { encounter, connectionState, reconnect, error } = useEncounter();
  const logs = useCombatLog();
  const actions = useEncounterAction();
  const toast = useToast();
  // The turn and status controls send one change at a time, so a double click can't advance two turns
  const controls = usePending();
  const legendary = usePending();
  const navigate = useNavigate();
  const [copiedCode, setCopiedCode] = useState(false);
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null);
  const [actionMode, setActionMode] = useState<ActionMode>(null);
  const [expandedMonsterId, setExpandedMonsterId] = useState<string | null>(null);
  // The tab the monster panel opens on: Reactions when the Reaction menu sends the DM there (D174)
  const [monsterTab, setMonsterTab] = useState<MonsterActionTab>('actions');
  const [turnError, setTurnError] = useState('');
  // The participant the DM is asked to confirm removing, and why the removal failed
  const [removeTarget, setRemoveTarget] = useState<EncounterParticipant | null>(null);
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState('');
  // Whether the End dialog is open (D166)
  const [ending, setEnding] = useState(false);
  // How many of the newest combat commands the Undo dialog offers to take back, while it is open (M14)
  const [undoSteps, setUndoSteps] = useState<number | null>(null);
  // The Spell Effect the DM is using, while its pop-up is open (M15)
  const [effectUse, setEffectUse] = useState<EffectInUse | null>(null);
  // The summoning whose creatures the DM is choosing or replacing, and whether Add companion is open (D182)
  const [summonPlace, setSummonPlace] = useState<{ casterId: string; effectId: string } | null>(null);
  const [addingCompanion, setAddingCompanion] = useState(false);

  // Opening another monster's actions, or closing them, starts them afresh: no targets and no result
  function toggleMonsterActions(participantId: string) {
    setMonsterTab('actions');
    setExpandedMonsterId(expandedMonsterId === participantId ? null : participantId);
  }

  if (!encounter) {
    return (
      <div className="min-h-screen bg-page flex items-center justify-center">
        {error
          ? <p role="alert" className="text-debuff font-body text-[15px] font-semibold">{error}</p>
          : <p className="text-muted font-body text-[15px] font-semibold">Loading encounter...</p>}
      </div>
    );
  }

  async function copySessionCode() {
    if (!encounter?.sessionCode) return;
    try {
      await navigator.clipboard.writeText(encounter.sessionCode);
      setCopiedCode(true);
      setTimeout(() => setCopiedCode(false), 2000);
    } catch {
      // The browser refused the clipboard, such as on a page without permission: the code is still on screen
      toast.error(`Could not copy the code. It is ${encounter.sessionCode}.`);
    }
  }

  function handlePause() {
    controls.run(() => actions.pause()).catch(toast.errorFrom);
  }

  function handleResume() {
    controls.run(() => actions.resume()).catch(toast.errorFrom);
  }

  // Ending asks first, previewing what each character sheet takes (D166)
  function handleEnd() {
    setEnding(true);
  }

  // A turn-order change the server refuses, such as a turn when nobody can act, says why (D163)
  async function attemptTurnChange(change: () => Promise<unknown>) {
    try {
      await change();
      setTurnError('');
    } catch (err: unknown) {
      setTurnError(apiErrorMessage(err));
    }
  }

  function handleNextTurn() {
    void controls.run(() => attemptTurnChange(() => actions.nextTurn()));
  }

  function handlePrevTurn() {
    void controls.run(() => attemptTurnChange(() => actions.previousTurn()));
  }

  async function handleSetVisibility(participantId: string, visible: boolean) {
    await actions.setVisibility(participantId, visible);
  }

  function askToRemove(participant: EncounterParticipant) {
    setRemoveError('');
    setRemoveTarget(participant);
  }

  function closeRemove() {
    setRemoveTarget(null);
    setRemoveError('');
  }

  // Mid-fight the server ends the creature's concentration and passes the turn on if it was theirs (D166)
  async function confirmRemove() {
    if (!removeTarget) return;
    setRemoving(true);
    try {
      await actions.removeParticipant(removeTarget.id);
      if (selectedTargetId === removeTarget.id) {
        setSelectedTargetId(null);
        setActionMode(null);
      }
      if (expandedMonsterId === removeTarget.id) {
        setExpandedMonsterId(null);
      }
      closeRemove();
    } catch (err: unknown) {
      setRemoveError(apiErrorMessage(err));
    } finally {
      setRemoving(false);
    }
  }

  function selectTarget(participantId: string, mode: ActionMode) {
    setSelectedTargetId(participantId);
    setActionMode(mode);
  }

  // The caster's use of a Spell Effect (M15): one that only moves, as Moonbeam's action does, is sent at once, and
  // one that resolves against creatures opens its pop-up
  function directEffect(caster: EncounterParticipant, effect: SpellEffect) {
    if (effect.directedResolves) {
      setEffectUse({ casterId: caster.id, effectId: effect.id });
    } else {
      void attemptTurnChange(() => actions.repeatSpellEffect({ targetIds: [], effectId: effect.id }, caster.id));
    }
  }

  // The server computes the turn order, the Lair's turn included, and each participant's sortOrder is its
  // turn's place in it (D163)
  const sorted = [...encounter.participants].sort((a, b) => a.sortOrder - b.sortOrder);
  const participantsById = new Map(encounter.participants.map(p => [p.id, p]));
  const turns = encounter.turnOrder;
  const isLairTurn = encounter.currentTurnIndex != null && (turns[encounter.currentTurnIndex]?.lair ?? false);
  const lairMonsters = encounter.monstersInLair.flatMap(id => participantsById.get(id)?.displayName ?? []);
  const selectedTarget = sorted.find(p => p.id === selectedTargetId) ?? null;
  const effectCaster = effectUse ? participantsById.get(effectUse.casterId) : undefined;
  const effectInUse = effectCaster?.activeEffects.find(e => e.id === effectUse?.effectId);
  const summonCaster = summonPlace ? participantsById.get(summonPlace.casterId) : undefined;
  const summonEffect = summonCaster?.activeEffects.find(e => e.id === summonPlace?.effectId);
  const currentCreature = encounter.participants.find(p => p.isCurrentTurn);
  // Combat controls show only while the encounter is Active: a Paused one can only resume or end, and a
  // Completed one is final (D166)
  const isActive = encounter.status === 'ACTIVE';
  const isPaused = encounter.status === 'PAUSED';
  // The server removes participants while the encounter is being prepared or fought
  const canRemove = isActive || encounter.status === 'PREPARING';

  // The whole list of monsters fighting in their lair, with this one in it or out of it
  const setInLair = (participantId: string, inLair: boolean) => {
    const others = encounter.monstersInLair.filter(id => id !== participantId);
    return actions.setLairStatus(inLair ? [...others, participantId] : others);
  };

  // Only entries logged since R5 record the success outcome Legendary Resistance applies (D163)
  const legendaryResistance = (log: CombatLogEntry) =>
    isActive && log.legendaryResistanceEligible && !log.lrResolved && log.lrSuccessDamage != null && (
      <button
        className="ml-2 px-2 py-0.5 bg-cls-monk hover:bg-cls-monk/90 text-card font-heading text-[11px] font-semibold tracking-[0.02em] disabled:opacity-50 disabled:cursor-wait"
        disabled={legendary.pending}
        onClick={() => { legendary.run(() => actions.spendLegendaryResistance(log.id)).catch(toast.errorFrom); }}
      >
        Use Legendary Resistance
      </button>
    );

  // Undo, while the encounter is Active and has a command since the last barrier (M14)
  const undo = isActive ? encounter.undo ?? null : null;
  const undoPlace = new Map(undo?.recent.map((command, index) => [command.commandId, index]) ?? []);
  const firstEntryOf = new Map<string, string>();
  for (const entry of logs) {
    if (entry.commandId && !firstEntryOf.has(entry.commandId)) firstEntryOf.set(entry.commandId, entry.id);
  }
  // "Undo to here" on the first entry of each command undo may still take, which undoes it and everything after
  const undoToHere = (log: CombatLogEntry) => {
    const place = log.commandId != null ? undoPlace.get(log.commandId) : undefined;
    if (place === undefined || firstEntryOf.get(log.commandId!) !== log.id) return null;
    return (
      <button
        className="ml-2 px-2 py-0.5 border border-rule bg-card hover:bg-page-alt text-muted font-heading text-[11px] font-semibold tracking-[0.02em]"
        onClick={() => setUndoSteps(place + 1)}
      >
        Undo to here
      </button>
    );
  };
  const entryActions = (log: CombatLogEntry) => (
    <>
      {legendaryResistance(log)}
      {undoToHere(log)}
    </>
  );

  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-page border-b border-rule">
        <div className="flex flex-wrap items-center justify-between gap-2 px-4 sm:px-6 py-3">
          <button onClick={() => navigate('/dm/encounters')} className="flex items-center gap-2 min-h-11 text-muted hover:text-ink transition-colors font-body text-[16px] font-semibold">
            <ArrowLeft className="w-4 h-4" /> Builder
          </button>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            {encounter.sessionCode && (
              <div className="flex items-center gap-2 bg-card border border-rule px-3 py-1.5">
                <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">Code:</span>
                <span className="text-ink font-mono font-bold tracking-wider">{encounter.sessionCode}</span>
                <IconButton label={copiedCode ? 'Session code copied' : 'Copy session code'} onClick={copySessionCode} className="text-muted hover:text-ink">
                  {copiedCode ? <Check className="w-3.5 h-3.5 text-buff" /> : <Copy className="w-3.5 h-3.5" />}
                </IconButton>
              </div>
            )}
            <ConnectionStatus state={connectionState} />
          </div>
        </div>
        <ConnectionBanner state={connectionState} onRetry={reconnect} />
      </header>

      <main className="px-4 sm:px-6 py-4">
        {error && <p className="text-debuff font-body text-[15px] font-semibold mb-4">{error}</p>}

        {/* Encounter info + controls bar */}
        <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
          <div className="min-w-0">
            <h1 className="font-heading text-[20px] font-bold tracking-[0.02em] text-ink">{encounter.name}</h1>
            <p className="text-muted font-body text-[15px] font-semibold">
              Round {encounter.roundNumber} &middot; {encounter.participants.filter(p => p.lifeState !== 'DEAD').length}/{encounter.participants.length} alive
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {isActive && (
              <>
                {/* A player character's familiar, steed or other summoning, placed with no slot spent (D182) */}
                <button onClick={() => setAddingCompanion(true)} disabled={controls.pending}
                  className={`${CONTROL} gap-1.5 px-3 py-2 bg-page-alt hover:bg-rule text-muted`}>
                  <UserPlus className="w-4 h-4" /> Add companion
                </button>
                {/* Undo takes back the newest command after asking; after Next Turn it restores what Previous can't */}
                <IconButton
                  label={undo ? `Undo: ${undo.summary}` : 'Nothing to undo'}
                  onClick={() => setUndoSteps(1)}
                  disabled={!undo || controls.pending}
                  className={`${CONTROL} gap-1 px-3 py-2 bg-page-alt hover:bg-rule text-muted disabled:cursor-not-allowed`}
                >
                  <Undo2 className="w-4 h-4" />
                </IconButton>
                <IconButton
                  label={undo?.kind === 'TURN_ADVANCE'
                    ? 'Previous turn (moves the marker only; Undo restores the turn Next Turn ended)'
                    : 'Previous turn'}
                  onClick={handlePrevTurn}
                  disabled={controls.pending}
                  className={`${CONTROL} gap-1 px-3 py-2 bg-page-alt hover:bg-rule text-muted`}
                >
                  <ChevronLeft className="w-4 h-4" />
                </IconButton>
                <button onClick={handleNextTurn} disabled={controls.pending} aria-busy={controls.pending || undefined}
                  className={`${CONTROL} gap-1.5 px-4 py-2 bg-ink hover:bg-ink/90 text-card`}>
                  Next Turn <ChevronRight className="w-4 h-4" />
                </button>
              </>
            )}
            {/* Gold dark enough for the light text in every theme; the hover darkens, since fading the gold would not
                read (WCAG AA) */}
            {encounter.status === 'ACTIVE' && (
              <button onClick={handlePause} disabled={controls.pending}
                className={`${CONTROL} gap-1.5 px-3 py-2 bg-cls-paladin hover:bg-cls-fighter text-card`}>
                <Pause className="w-4 h-4" /> Pause
              </button>
            )}
            {encounter.status === 'PAUSED' && (
              <button onClick={handleResume} disabled={controls.pending}
                className={`${CONTROL} gap-1.5 px-3 py-2 bg-buff hover:bg-buff/90 text-card`}>
                <Play className="w-4 h-4" /> Resume
              </button>
            )}
            {(encounter.status === 'ACTIVE' || encounter.status === 'PAUSED') && (
              <button onClick={handleEnd} disabled={controls.pending}
                className={`${CONTROL} gap-1.5 px-3 py-2 bg-debuff hover:bg-debuff/90 text-card`}>
                <Flag className="w-4 h-4" /> End
              </button>
            )}
            {encounter.status === 'COMPLETED' && (
              <span className="px-3 py-2 bg-page-alt text-muted font-body text-[16px] font-semibold">Complete</span>
            )}
          </div>
        </div>

        {/* The darker gold with light text, as on Pause, so it reads in every theme (WCAG AA) */}
        {isPaused && (
          <div role="status" className="flex items-center gap-2 mb-4 px-4 py-2.5 bg-cls-paladin text-card">
            <Pause className="w-4 h-4" />
            <p className="font-heading text-[15px] font-semibold tracking-[0.01em]">Paused — resume to continue</p>
          </div>
        )}

        {turnError && <p role="alert" className="text-debuff font-body text-[15px] font-semibold mb-4">{turnError}</p>}

        {/* What a Spell Effect's area may do to the creature whose turn it is (M15) */}
        {isActive && (
          <TriggerReminder participants={encounter.participants} current={currentCreature}
            onApply={(caster, effect, trigger, targetId) => setEffectUse({ casterId: caster.id, effectId: effect.id, trigger, targetId })} />
        )}

        {/* Summonings waiting for the DM to choose their creatures (D182) */}
        {isActive && (
          <SummonAwaitingBanner participants={encounter.participants}
            onChoose={(caster, effect) => setSummonPlace({ casterId: caster.id, effectId: effect.id })} />
        )}
        {isActive && summonCaster && summonEffect && (
          <SummonPlaceModal caster={summonCaster} effect={summonEffect} onClose={() => setSummonPlace(null)} />
        )}
        {isActive && addingCompanion && (
          <AddCompanionModal encounterId={encounter.id} participants={encounter.participants}
            onSubmit={request => actions.placeCompanion(request)} onClose={() => setAddingCompanion(false)} />
        )}

        {/* Spell cast modal */}
        {isActive && actionMode === 'spell' && selectedTarget && (
          <SpellCastModal
            caster={selectedTarget}
            participants={encounter.participants}
            onClose={() => { setActionMode(null); setSelectedTargetId(null); }}
            allowManualModifiers
            asDm
          />
        )}

        {/* Any creature's Actions and Reactions menus: warnings only, since the server never refuses the DM (D174) */}
        {isActive && actionMode === 'standard' && selectedTarget && (
          <StandardActionMenu participant={selectedTarget} participants={encounter.participants} asDm
            onClose={() => { setActionMode(null); setSelectedTargetId(null); }} />
        )}
        {isActive && actionMode === 'reaction' && selectedTarget && (
          <ReactionMenu participant={selectedTarget} participants={encounter.participants} asDm allowManualModifiers
            onClose={() => { setActionMode(null); setSelectedTargetId(null); }}
            onOpenMonsterReactions={() => { setMonsterTab('reactions'); setExpandedMonsterId(selectedTarget.id); }} />
        )}

        {/* A Spell Effect's use, or what its area does to a creature, which the DM applies (M15) */}
        {isActive && effectUse && effectCaster && effectInUse && (
          <RepeatEffectModal
            caster={effectCaster}
            effect={effectInUse}
            participants={encounter.participants}
            trigger={effectUse.trigger}
            initialTargets={effectUse.targetId ? [effectUse.targetId] : undefined}
            onClose={() => setEffectUse(null)}
            allowManualModifiers
            asDm
          />
        )}

        {/* Asks before removing a participant; adding one is for an encounter still being prepared (D166) */}
        {removeTarget && (
          <Modal
            title={`Remove ${removeTarget.displayName}?`}
            onClose={closeRemove}
            dismissible={!removing}
            size="sm"
            footer={(
              <>
                <Button variant="secondary" onClick={closeRemove} disabled={removing}>Cancel</Button>
                <Button variant="danger" onClick={confirmRemove} loading={removing}>
                  {removing ? 'Removing...' : 'Remove'}
                </Button>
              </>
            )}
          >
            <p className="font-body text-[15px] font-semibold text-muted">
              {removeTarget.displayName} leaves the encounter.
              {isActive && ' Any spell they are concentrating on ends, and if it is their turn, the turn passes to the'
                + ' next creature. Nobody can join once the fight has started, so they can\'t be added back.'}
            </p>
            {/* A player character leaving mid-fight takes its changes to its sheet then (D166) */}
            {isActive && removeTarget.participantType === 'PLAYER' && (
              <p className="font-body text-[15px] font-semibold text-muted mt-2">
                What this fight has changed for them so far, such as hit points, spell slots and pool uses, is written
                back to their character sheet now.
              </p>
            )}
            {removeError && <p role="alert" className="text-debuff font-body text-[15px] mt-3">{removeError}</p>}
          </Modal>
        )}

        {undo && undoSteps !== null && (
          <UndoDialog undo={undo} steps={undoSteps} log={logs}
            onUndo={(expectedCommandId, steps) => actions.undo(expectedCommandId, steps)}
            onClose={() => setUndoSteps(null)} />
        )}

        {ending && (
          <EndEncounterModal encounterName={encounter.name} onEnd={applyResults => actions.end(applyResults)}
            onClose={() => setEnding(false)} />
        )}

        {/* Action panel */}
        {isActive && actionMode !== 'spell' && actionMode !== 'standard' && actionMode !== 'reaction' && (
          <ActionPanel
            selectedTarget={selectedTarget}
            actionMode={actionMode}
            setActionMode={setActionMode}
            participants={encounter.participants}
          />
        )}

        {/* The initiative order as the server computes it, the Lair's turn included (D163) */}
        <div className="space-y-2 mb-4" role="list" aria-label="Initiative order">
          {turns.map((turn, index) => {
            if (turn.lair) {
              return <LairTurnRow key="lair" turn={turn} lairMonsters={lairMonsters} />;
            }
            const p = participantsById.get(turn.participantId ?? '');
            if (!p) return null;
            const isMonster = p.participantType === 'MONSTER';
            // A monster or a summoned creature acts from its stat block (D182)
            const hasStatBlock = isMonster || (p.participantType === 'COMPANION' && p.monsterId != null);
            // Alive, Dying, Stable or Dead (D161): the dead can only be revived with Set HP
            const isUp = p.lifeState === 'ALIVE';
            const isDead = p.lifeState === 'DEAD';
            // A player character in another form acts with its form's monster actions (PHB p.67, D192)
            const inForm = formOf(p) !== null;

            return (
              <Fragment key={p.id}>
                <ParticipantRow
                  participant={p}
                  participants={encounter.participants}
                  current={turn.current}
                  highlighted={p.id === selectedTargetId}
                  summonerName={p.summonedByParticipantId ? participantsById.get(p.summonedByParticipantId)?.displayName : null}
                  onEndWildShape={isActive && p.wildShape
                    ? () => { void attemptTurnChange(() => actions.usePool({ participantId: p.id, poolId: wildShapePoolId(p), option: 'end' })); }
                    : undefined}
                  leading={isActive && (
                    // The DM's order among participants tied on initiative and DEX (D163)
                    <TieOrderArrows
                      turns={turns}
                      index={index}
                      name={p.displayName}
                      onReorder={ids => attemptTurnChange(() => actions.reorderTie(ids))}
                    />
                  )}
                  badges={(
                    <>
                      <span
                        className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5"
                        style={{ color: getParticipantColour(isMonster, p.characterClass), backgroundColor: getParticipantBg(isMonster, p.characterClass) }}
                      >
                        {p.participantType}
                      </span>
                      {isMonster && (
                        <VisibilityToggle participant={p} onToggle={visible => handleSetVisibility(p.id, visible)} />
                      )}
                      {isActive && isMonster && p.hasLairActions && (
                        <LairToggle
                          participant={p}
                          inLair={encounter.monstersInLair.includes(p.id)}
                          onToggle={inLair => setInLair(p.id, inLair)}
                        />
                      )}
                      <LifeStateBadge participant={p} />
                    </>
                  )}
                  trailing={(
                    <>
                      <HpReadout participant={p} />

                      {/* Quick action buttons */}
                      {isActive && (
                        <div className="flex flex-wrap items-center gap-1 flex-shrink-0 sm:ml-2">
                          {/* Monster actions toggle (M12), a druid in Wild Shape's beast actions (PHB p.67), and a
                              form spell's form's actions (D192) */}
                          {(hasStatBlock || inForm) && (
                            <IconButton
                              label={isMonster ? 'Monster actions' : hasStatBlock ? 'Creature actions'
                                : p.wildShape ? 'Beast actions' : 'Form actions'}
                              onClick={() => toggleMonsterActions(p.id)}
                              aria-expanded={expandedMonsterId === p.id}
                              className={`${QUICK_ACTION} ${expandedMonsterId === p.id ? 'bg-monster/20 text-monster' : 'bg-monster/10 hover:bg-monster/20 text-monster'}`}
                            >
                              <Swords className="w-3.5 h-3.5" />
                            </IconButton>
                          )}
                          {/* The dead can't be attacked, damaged or healed; Set HP / revive brings them back (D161) */}
                          {!isDead && (
                            <IconButton label="Attack roll" onClick={() => selectTarget(p.id, 'attack')}
                              className={`${QUICK_ACTION} bg-cls-fighter/10 hover:bg-cls-fighter/20 text-cls-fighter`}>
                              <Crosshair className="w-3.5 h-3.5" />
                            </IconButton>
                          )}
                          {!isDead && (
                            <IconButton label="Deal damage" onClick={() => selectTarget(p.id, 'damage')}
                              className={`${QUICK_ACTION} bg-debuff/10 hover:bg-debuff/20 text-debuff`}>
                              <Minus className="w-3.5 h-3.5" />
                            </IconButton>
                          )}
                          {!isDead && (
                            <IconButton label="Heal" onClick={() => selectTarget(p.id, 'heal')}
                              className={`${QUICK_ACTION} bg-buff/10 hover:bg-buff/20 text-buff`}>
                              <Plus className="w-3.5 h-3.5" />
                            </IconButton>
                          )}
                          <IconButton label="Set HP / revive" onClick={() => selectTarget(p.id, 'set-hp')}
                            className={`${QUICK_ACTION} bg-buff/10 hover:bg-buff/20 text-buff`}>
                            <HeartPulse className="w-3.5 h-3.5" />
                          </IconButton>
                          <IconButton label="Add condition" onClick={() => selectTarget(p.id, 'condition')}
                            className={`${QUICK_ACTION} bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk`}>
                            <Zap className="w-3.5 h-3.5" />
                          </IconButton>
                          {isUp && (
                            <IconButton label="Set concentration" onClick={() => selectTarget(p.id, 'concentration')}
                              className={`${QUICK_ACTION} bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock`}>
                              <Swords className="w-3.5 h-3.5" />
                            </IconButton>
                          )}
                          {isUp && (
                            <IconButton label="Cast spell" onClick={() => selectTarget(p.id, 'spell')}
                              className={`${QUICK_ACTION} bg-ink/10 hover:bg-ink/20 text-ink`}>
                              <Sparkles className="w-3.5 h-3.5" />
                            </IconButton>
                          )}
                          {/* Dash, Dodge, Help and the rest, and object interactions (D174) */}
                          {isUp && (
                            <IconButton label={`Actions for ${p.displayName}`} onClick={() => selectTarget(p.id, 'standard')}
                              className={`${QUICK_ACTION} bg-cls-fighter/10 hover:bg-cls-fighter/20 text-cls-fighter`}>
                              <Footprints className="w-3.5 h-3.5" />
                            </IconButton>
                          )}
                          {isUp && (
                            <IconButton label={`Reactions for ${p.displayName}`} onClick={() => selectTarget(p.id, 'reaction')}
                              className={`${QUICK_ACTION} bg-cls-rogue/10 hover:bg-cls-rogue/20 text-cls-rogue`}>
                              <ShieldAlert className="w-3.5 h-3.5" />
                            </IconButton>
                          )}
                        </div>
                      )}

                      {/* Removing a participant, mid-fight too, asks the DM first */}
                      {canRemove && (
                        <IconButton
                          label={`Remove ${p.displayName}`}
                          onClick={() => askToRemove(p)}
                          className={`${QUICK_ACTION} flex-shrink-0 text-faint hover:text-debuff hover:bg-debuff/10`}
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </IconButton>
                      )}
                    </>
                  )}
                >
                  {/* What the creature has left this turn; the DM's edit replaces it, for Haste, Slow or a fix (D174) */}
                  <TurnEconomyBar
                    participant={p}
                    variant="row"
                    onEdit={isActive ? turnEconomy => attemptTurnChange(() => actions.setTurnEconomy({ participantId: p.id, turnEconomy })) : undefined}
                  />
                  {/* Divine Smite or Stunning Strike after a player character's melee weapon hit this turn (M17) */}
                  {isActive && p.participantType === 'PLAYER' && (
                    <HitFollowUps participant={p} participants={encounter.participants} turnStamp={encounter.turnStamp} />
                  )}
                  {/* A Bardic Inspiration die the creature holds (M17) */}
                  {isActive && p.lifeState !== 'DEAD' && <InspirationControls participant={p} />}
                  <ConditionChips
                    participant={p}
                    roundNumber={encounter.roundNumber}
                    onRemove={isActive ? c => actions.removeCondition(p.id, c.name) : undefined}
                    showRepeatSave={isActive}
                  />
                  <ActiveSpells
                    participant={p}
                    roundNumber={encounter.roundNumber}
                    onUse={isActive ? effect => directEffect(p, effect) : undefined}
                    onEnd={isActive ? effect => { void attemptTurnChange(() => actions.endSpellEffect({ participantId: p.id, effectId: effect.id })); } : undefined}
                    onMove={isActive ? (effect, targetId) => { void attemptTurnChange(() => actions.moveSpellEffect({ participantId: p.id, effectId: effect.id, targetId })); } : undefined}
                    participants={encounter.participants}
                    onReplaceSummon={isActive ? effect => setSummonPlace({ casterId: p.id, effectId: effect.id }) : undefined}
                    onDismissSummon={isActive ? creature => { void attemptTurnChange(() => actions.dismissSummons({ participantIds: [creature.id] })); } : undefined}
                    onDismissAllSummons={isActive ? creatures => { void attemptTurnChange(() => actions.dismissSummons({ participantIds: creatures.map(c => c.id) })); } : undefined}
                    // The DM's command for the caster, charged to its bonus action and never refused (D174, D182)
                    onCommandSummons={isActive ? creatures => { void attemptTurnChange(() => actions.commandSummons({ casterParticipantId: p.id, participantIds: creatures.map(c => c.id) })); } : undefined}
                  />

                  {/* Death saves only while Dying: a Stable PC makes none, and the dead are past them (D161) */}
                  {p.lifeState === 'DYING' && (
                    <DeathSaves participant={p} showDue onRoll={isActive ? () => actions.rollDeathSave(p.id) : undefined} />
                  )}

                  {/* Slots are spent and restored only while the encounter is Active (D166) */}
                  {isUp && (
                    <SpellSlotPips
                      slots={p.spellSlotsCurrent}
                      variant="row"
                      onUse={isActive ? level => actions.spendSpellSlot(p.id, level) : undefined}
                      onRestore={isActive ? level => actions.restoreSpellSlot(p.id, level) : undefined}
                    />
                  )}

                  {/* Resource pools (M24.5), which the DM uses for anyone while the encounter is Active (D174) */}
                  <ResourcePoolDisplay pools={p.resourcePoolsCurrent} participant={p} asDm targets={sorted}
                    onUse={isActive ? (poolId, options) => actions.usePool({ participantId: p.id, poolId, ...options }) : undefined}
                    onFlexibleCasting={isActive
                      ? (direction, slotLevel) => actions.fontOfMagic({ participantId: p.id, direction, slotLevel })
                      : undefined}
                    onWildShapeHeal={isActive
                      ? (slotLevel, pactSlot) => actions.wildShapeHeal({ participantId: p.id, slotLevel, pactSlot })
                      : undefined} />

                  {/* Shapechange's or Animal Shapes' new form, with the caster's action (D192) */}
                  {isActive && isUp && reshapingSpell(p) && (
                    <ChangeFormControl caster={p} participants={encounter.participants}
                      onChange={(targetId, creatureId) => actions.changeForm({ participantId: p.id, targetId, creatureId })} />
                  )}
                </ParticipantRow>

                {/* M12 Inline monster action panel */}
                {isActive && expandedMonsterId === p.id && (hasStatBlock || inForm) && (
                  <InlineMonsterActions
                    key={monsterTab}
                    initialTab={monsterTab}
                    participant={p}
                    participants={sorted}
                    isLairTurn={isLairTurn}
                    inLair={encounter.monstersInLair.includes(p.id)}
                  />
                )}
              </Fragment>
            );
          })}
        </div>

        <CombatLog entries={logs} entryActions={entryActions} turnActions={undoToHere} />
      </main>
    </div>
  );
}

export default function EncounterSessionPage() {
  const { encounterId } = useParams<{ encounterId: string }>();
  if (!encounterId) return null;

  return (
    <EncounterProvider encounterId={encounterId} view="dm">
      <DmSessionView />
    </EncounterProvider>
  );
}
