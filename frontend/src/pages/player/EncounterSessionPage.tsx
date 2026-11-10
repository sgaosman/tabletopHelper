import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider } from '../../context/EncounterContext';
import { useEncounter } from '../../hooks/useEncounter';
import { useAuth } from '../../hooks/useAuth';
import SpellConsentControl from '../../components/encounter/SpellConsentControl';
import { useCombatLog, useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant, SpellEffect } from '../../types/encounter';
import { apiErrorMessage } from '../../api/errors';
import { chargeBlock } from '../../utils/turnEconomy';
import SpellCastModal from '../../components/encounter/SpellCastModal';
import RepeatEffectModal from '../../components/encounter/RepeatEffectModal';
import RepeatSavePrompt from '../../components/encounter/RepeatSavePrompt';
import WeaponAttackForm from '../../components/encounter/WeaponAttackForm';
import LairTurnRow from '../../components/encounter/LairTurnRow';
import ParticipantRow from '../../components/encounter/ParticipantRow';
import LifeStateBadge from '../../components/encounter/LifeStateBadge';
import ConditionChips from '../../components/encounter/ConditionChips';
import ActiveSpells from '../../components/encounter/ActiveSpells';
import TouchedEffectUses from '../../components/encounter/TouchedEffectUses';
import DeathSaves from '../../components/encounter/DeathSaves';
import SpellSlotPips from '../../components/encounter/SpellSlotPips';
import TurnEconomyBar from '../../components/encounter/TurnEconomyBar';
import HpReadout from '../../components/encounter/HpReadout';
import CombatLog from '../../components/encounter/CombatLog';
import ConnectionStatus, { ConnectionBanner } from '../../components/encounter/ConnectionStatus';
import ConditionForm from '../../components/encounter/ConditionForm';
import ConcentrationForm from '../../components/encounter/ConcentrationForm';
import StandardActionMenu from '../../components/encounter/StandardActionMenu';
import ReactionMenu from '../../components/encounter/ReactionMenu';
import HitFollowUps from '../../components/encounter/HitFollowUps';
import InspirationControls from '../../components/encounter/InspirationControls';
import ResourcePoolDisplay from '../../components/common/ResourcePoolDisplay';
import InlineMonsterActions from '../../components/encounter/InlineMonsterActions';
import FamiliarAttack from '../../components/encounter/FamiliarAttack';
import { wildShapePoolId } from '../../utils/classFeatures';
import { formOf, reshapingSpell } from '../../utils/formSpells';
import ChangeFormControl from '../../components/encounter/ChangeFormControl';
import IconButton from '../../components/ui/IconButton';
import { healthLabel } from '../../utils/healthStatus';
import { ArrowLeft, Sparkles, Crosshair, Zap, Swords, X, Footprints, ShieldAlert, Megaphone } from 'lucide-react';
import { useState } from 'react';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';

// The player's own action buttons, each at least 44px tall for a phone
const SELF_ACTION = 'flex items-center gap-1.5 min-h-11 px-3 py-1.5 font-heading text-[13px] font-semibold tracking-[0.02em] border';

type PlayerAction = 'attack' | 'condition' | 'concentration' | 'spell' | 'standard' | 'reaction' | null;

function PlayerActionPanel({ myCharacter, encounter, targetId, actionMode, onClose }: {
  myCharacter: EncounterParticipant;
  encounter: { participants: EncounterParticipant[]; allowPlayerManualModifiers: boolean };
  targetId: string | null;
  actionMode: PlayerAction;
  onClose: () => void;
}) {
  const actions = useEncounterAction();
  const target = encounter.participants.find(p => p.id === targetId);
  if (!target || !actionMode) return null;

  return (
    <div className="bg-card border border-rule p-4 mb-4">
      <div className="flex items-center justify-between mb-3">
        <div className="font-heading text-[15px] font-semibold tracking-[0.01em] text-ink">
          {actionMode === 'attack' && <><Crosshair className="w-4 h-4 inline mr-1 text-cls-fighter" />Attack {target.displayName} (AC {target.armourClass})</>}
          {actionMode === 'condition' && <><Zap className="w-4 h-4 inline mr-1 text-cls-monk" />Add Condition</>}
          {actionMode === 'concentration' && <><Swords className="w-4 h-4 inline mr-1 text-cls-warlock" />Set Concentration</>}
        </div>
        <IconButton label="Close action panel" onClick={onClose} className="text-faint hover:text-ink"><X className="w-4 h-4" /></IconButton>
      </div>

      {/* The weapons and numbers come from the server, with Manual Modifiers while the campaign allows them (D164).
          The cost follows the turn: off their turn a player's only attack is an opportunity attack, their reaction (D174) */}
      {actionMode === 'attack' && (
        <WeaponAttackForm attacker={myCharacter} target={target}
          allowManualModifiers={encounter.allowPlayerManualModifiers} onDone={onClose} />
      )}
      {actionMode === 'condition' && (
        <ConditionForm sources={encounter.participants.filter(p => p.id !== target.id)}
          onSubmit={async (condition, duration, source) => {
            await actions.addCondition(target.id, condition, duration, source);
            onClose();
          }} />
      )}
      {actionMode === 'concentration' && (
        <ConcentrationForm onSubmit={async spellName => {
          await actions.setConcentration(target.id, spellName);
          onClose();
        }} />
      )}
    </div>
  );
}

function PlayerSessionView() {
  const { encounter, connectionState, reconnect, error } = useEncounter();
  // The server sends the players' log after every change, without entries about hidden monsters (D157, D171)
  const logs = useCombatLog();
  const actions = useEncounterAction();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [actionMode, setActionMode] = useState<PlayerAction>(null);
  const [attackTargetId, setAttackTargetId] = useState<string | null>(null);
  // The player's Spell Effect in use, while its pop-up is open, and why a use or an end failed (M15)
  const [effectInUseId, setEffectInUseId] = useState<string | null>(null);
  // Another creature's Spell Effect a creature of the player's uses with its own action, as Dragon's Breath's
  // exhalation (XGE p.154), while its pop-up is open (D200)
  const [touchedUse, setTouchedUse] = useState<{ actorId: string; effectId: string } | null>(null);
  const [effectError, setEffectError] = useState('');
  // The beast's actions of the player's character in Wild Shape, shown on request (PHB p.67)
  const [showBeastActions, setShowBeastActions] = useState(false);
  // The command to the character's summoned creatures, one at a time, a failure shown as a toast (D182)
  const commanding = usePending();
  const toast = useToast();

  if (!encounter) {
    return (
      <div className="min-h-screen bg-page flex items-center justify-center">
        {error
          ? <p role="alert" className="text-debuff font-body text-[15px] font-semibold">{error}</p>
          : <p className="text-muted font-body text-[15px] font-semibold">Loading encounter...</p>}
      </div>
    );
  }

  // The Player View already leaves out participants the DM has hidden, and the Lair's turn unless a monster
  // in its lair is visible; its turn order counts only what players see (D157, D163)
  const participantsById = new Map(encounter.participants.map(p => [p.id, p]));
  const lairMonsters = encounter.monstersInLair.flatMap(id => participantsById.get(id)?.displayName ?? []);
  // The player's own character, never a creature it summoned, which the player controls too (D182)
  const myCharacter = encounter.participants.find(p => p.participantType === 'PLAYER' && p.controlledByUserId === user?.userId);
  const isMyTurn = myCharacter?.isCurrentTurn ?? false;
  // The creatures the player's character summoned and controls (D182): the one whose turn it is acts from its stat
  // block, and those that act only on command (PHB p.212, XGE p.168) await the character's bonus action
  const mySummons = encounter.participants.filter(p => p.participantType === 'COMPANION'
    && p.controlledByUserId === user?.userId);
  const summonOnTurn = mySummons.find(p => p.isCurrentTurn && p.lifeState === 'ALIVE');
  // A Pact of the Chain warlock's familiars, which may attack in place of one of its attacks (PHB p.107)
  const myFamiliars = mySummons.filter(p => p.summon?.kind === 'FAMILIAR' && p.lifeState === 'ALIVE'
    && p.summonedByParticipantId === myCharacter?.id);
  const toCommand = mySummons.filter(p => p.summon?.needsCommand && p.summon.commandedRound == null
    && p.lifeState !== 'DEAD' && p.summonedByParticipantId === myCharacter?.id);
  // A player's reactions, on any turn, while their reaction is unused; off their turn they are all a player can take
  // (D174)
  const canReact = myCharacter?.lifeState === 'ALIVE' && myCharacter.turnEconomy != null
    && !myCharacter.turnEconomy.reactionUsed;
  // Repeat Saves the campaign leaves to this player, at the end of their character's turn (D162)
  const savesToRoll = (myCharacter?.activeConditions ?? []).filter(c => c.repeatSave?.awaitingRoll || c.repeatSave?.byAction);
  // Actions show only while the encounter is Active: the server refuses them while it is Paused or
  // Completed (D166)
  const isActive = encounter.status === 'ACTIVE';
  const closeAction = () => { setActionMode(null); setAttackTargetId(null); };
  const effectInUse = myCharacter?.activeEffects.find(e => e.id === effectInUseId);
  const touchedActor = touchedUse ? participantsById.get(touchedUse.actorId) : undefined;
  const touchedEffect = touchedUse ? encounter.participants.flatMap(p => p.activeEffects)
    .find(e => e.id === touchedUse.effectId) : undefined;

  // A Spell Effect command the server may refuse, such as a use over the turn's budget, says why (M15)
  async function effectCommand(command: () => Promise<unknown>) {
    try {
      await command();
      setEffectError('');
    } catch (err: unknown) {
      setEffectError(apiErrorMessage(err));
    }
  }

  // The player's use of their Spell Effect: a move, as Moonbeam's, is sent at once; anything else opens its pop-up
  function directEffect(effect: SpellEffect) {
    if (!myCharacter) return;
    if (effect.directedResolves) {
      setEffectInUseId(effect.id);
    } else {
      void effectCommand(() => actions.repeatSpellEffect({ targetIds: [], effectId: effect.id }, myCharacter.id));
    }
  }

  // Why the player's turn can't pay for using the effect, as the server would answer (D174)
  function effectBlock(participant: EncounterParticipant, effect: SpellEffect): string | null {
    if (!effect.directedCost) return null;
    // Only the creature the spell touched uses it, with its own action, as Dragon's Breath's (XGE p.154, D200)
    const usableBy = effect.usableByIds ?? [];
    if (usableBy.length > 0 && !usableBy.includes(participant.id)) {
      const names = usableBy.map(id => participantsById.get(id)?.displayName ?? 'the creature it touched');
      return `Only ${names.join(', ')} can use it, with its own action`;
    }
    if (!participant.isCurrentTurn) return 'Only on your turn';
    return effect.directedCost === 'NONE' ? null : chargeBlock(participant, { cost: effect.directedCost });
  }

  // The character's bonus action that lets its uncommanded creatures act on their next turn (PHB p.212, XGE p.168)
  function commandSummons() {
    if (!myCharacter || toCommand.length === 0) return;
    commanding.run(() => actions.commandSummons({
      casterParticipantId: myCharacter.id, participantIds: toCommand.map(p => p.id),
    })).catch(toast.errorFrom);
  }

  function selectAttackTarget(targetId: string) {
    setAttackTargetId(targetId);
    setActionMode('attack');
  }

  function selectSelfAction(mode: PlayerAction) {
    if (!myCharacter) return;
    setAttackTargetId(myCharacter.id);
    setActionMode(mode);
  }

  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-page border-b border-rule">
        <div className="flex flex-wrap items-center justify-between gap-2 max-w-4xl mx-auto px-4 sm:px-6 py-3">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 min-h-11 text-muted hover:text-ink transition-colors font-body text-[15px] font-semibold">
            <ArrowLeft className="w-4 h-4" /> Dashboard
          </button>
          <div className="flex items-center gap-3">
            {encounter.status === 'COMPLETED' && (
              <span className="px-2.5 py-1 bg-page-alt text-muted font-heading text-[11px] font-semibold tracking-[0.02em]">Complete</span>
            )}
            {/* The darker gold with light text, as on the DM's Pause, so it reads in every theme (WCAG AA) */}
            {encounter.status === 'PAUSED' && (
              <span className="px-2.5 py-1 bg-cls-paladin text-card font-heading text-[11px] font-semibold tracking-[0.02em]">Paused</span>
            )}
            <ConnectionStatus state={connectionState} />
          </div>
        </div>
        <ConnectionBanner state={connectionState} onRetry={reconnect} />
      </header>

      <main className="max-w-4xl mx-auto px-4 sm:px-6 py-4">
        {error && <p className="text-debuff font-body text-[15px] font-semibold mb-4">{error}</p>}

        <div className="mb-4">
          <h1 className="font-heading text-[20px] font-bold tracking-[0.02em] text-ink">{encounter.name}</h1>
          <p className="text-muted font-body text-[15px] font-semibold">Round {encounter.roundNumber}</p>
          {isMyTurn && (
            <div className="mt-2 px-4 py-2.5 bg-cls-fighter/10 border border-cls-fighter/30">
              <p className="text-cls-fighter font-heading text-[15px] font-semibold tracking-[0.01em]">It&apos;s your turn!</p>
            </div>
          )}
        </div>

        {/* What the player's own character has left this turn; the Player View carries no monster's (D157, D174) */}
        {myCharacter && myCharacter.lifeState === 'ALIVE' && <TurnEconomyBar participant={myCharacter} variant="card" />}
        {/* Divine Smite or Stunning Strike after the character's melee weapon hit this turn (M17) */}
        {myCharacter && isActive && myCharacter.lifeState === 'ALIVE' && (
          <HitFollowUps participant={myCharacter} participants={encounter.participants} turnStamp={encounter.turnStamp} />
        )}
        {/* A Bardic Inspiration die the character holds, kept for a failed save or rolled now (M17) */}
        {myCharacter && isActive && myCharacter.lifeState === 'ALIVE' && (
          <InspirationControls participant={myCharacter} />
        )}

        {/* Own character at 0 HP: death saves only while Dying (D161), one due as each turn starts (D163) */}
        {myCharacter?.lifeState === 'DYING' && (
          <div className="bg-debuff/10 border border-debuff/30 p-4 mb-4">
            <p className="text-debuff font-heading text-[15px] font-semibold tracking-[0.01em] mb-1">You are dying!</p>
            {myCharacter.deathSaveDue && (
              <p role="status" className="text-debuff font-body text-[15px] font-semibold">Your turn has started: roll your death saving throw.</p>
            )}
            {/* The Roll button shows only while the encounter is Active, since combat needs it (D166) */}
            <DeathSaves participant={myCharacter} onRoll={isActive ? () => actions.rollDeathSave(myCharacter.id) : undefined} />
          </div>
        )}
        {myCharacter && isActive && savesToRoll.length > 0 && (
          <div className="space-y-2 mb-4">
            {savesToRoll.map(c => <RepeatSavePrompt key={c.id} participant={myCharacter} condition={c} />)}
          </div>
        )}
        {myCharacter?.lifeState === 'STABLE' && (
          <div role="status" className="bg-page-alt border border-rule p-4 mb-4">
            <p className="text-muted font-heading text-[15px] font-semibold tracking-[0.01em]">You are stable: unconscious at 0 HP, and making no death saves.</p>
          </div>
        )}
        {myCharacter?.lifeState === 'DEAD' && (
          <div role="status" className="bg-debuff/10 border border-debuff/30 p-4 mb-4">
            <p className="text-debuff font-heading text-[15px] font-semibold tracking-[0.01em]">You have died. Only a revival can bring you back.</p>
          </div>
        )}

        {/* Own character's spell slots, spent only while the encounter is Active (D166) */}
        {myCharacter && myCharacter.lifeState === 'ALIVE' && (
          <SpellSlotPips
            slots={myCharacter.spellSlotsCurrent}
            variant="card"
            onUse={isActive ? level => actions.spendSpellSlot(myCharacter.id, level) : undefined}
          />
        )}

        {/* Resource pools (M24.5), used at the table while the encounter is Active, each costing what its pool says from
            the turn (D174) */}
        {myCharacter && (
          <ResourcePoolDisplay pools={myCharacter.resourcePoolsCurrent} participant={myCharacter}
            targets={encounter.participants}
            onUse={isActive && myCharacter.lifeState === 'ALIVE'
              ? (poolId, options) => actions.usePool({ participantId: myCharacter.id, poolId, ...options }) : undefined}
            onFlexibleCasting={isActive && myCharacter.lifeState === 'ALIVE'
              ? (direction, slotLevel) => actions.fontOfMagic({ participantId: myCharacter.id, direction, slotLevel })
              : undefined}
            onWildShapeHeal={isActive && myCharacter.lifeState === 'ALIVE'
              ? (slotLevel, pactSlot) => actions.wildShapeHeal({ participantId: myCharacter.id, slotLevel, pactSlot })
              : undefined} />
        )}

        {/* Player self-action buttons */}
        {isActive && myCharacter && myCharacter.lifeState === 'ALIVE' && (
          <div className="flex flex-wrap items-center gap-2 mb-4">
            {/* The standard actions and object interactions are for the player's own turn (D174) */}
            {isMyTurn && (
              <button onClick={() => selectSelfAction('standard')}
                className={`${SELF_ACTION} bg-cls-fighter/10 hover:bg-cls-fighter/20 text-cls-fighter border-cls-fighter/30`}>
                <Footprints className="w-3.5 h-3.5" /> Actions
              </button>
            )}
            {canReact && (
              <button onClick={() => selectSelfAction('reaction')}
                className={`${SELF_ACTION} bg-cls-rogue/10 hover:bg-cls-rogue/20 text-cls-rogue border-cls-rogue/30`}>
                <ShieldAlert className="w-3.5 h-3.5" /> Reaction
              </button>
            )}
            <button onClick={() => selectSelfAction('condition')}
              className={`${SELF_ACTION} bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk border-cls-monk/30`}>
              <Zap className="w-3.5 h-3.5" /> Add Condition
            </button>
            <button onClick={() => selectSelfAction('concentration')}
              className={`${SELF_ACTION} bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock border-cls-warlock/30`}>
              <Swords className="w-3.5 h-3.5" /> {myCharacter.concentrationSpell ? 'Change' : 'Set'} Concentration
            </button>
            {/* A druid in Wild Shape attacks with the beast's actions (PHB p.67), and a creature a form spell
                transformed with its form's (D192) */}
            {formOf(myCharacter) && (
              <button onClick={() => setShowBeastActions(shown => !shown)} aria-expanded={showBeastActions}
                className={`${SELF_ACTION} bg-monster/10 hover:bg-monster/20 text-monster border-monster/30`}>
                <Swords className="w-3.5 h-3.5" /> {formOf(myCharacter)!.beastName} actions
              </button>
            )}
            {/* Animate Dead's undead and a tiny servant act only when commanded, with the caster's bonus action on its
                turn (PHB p.212, XGE p.168, D182) */}
            {isMyTurn && toCommand.length > 0 && (
              <button onClick={commandSummons} disabled={commanding.pending || !!chargeBlock(myCharacter, { cost: 'BONUS_ACTION' })}
                title={chargeBlock(myCharacter, { cost: 'BONUS_ACTION' }) ?? undefined}
                className={`${SELF_ACTION} bg-cls-druid/10 hover:bg-cls-druid/20 text-cls-druid border-cls-druid/30 disabled:opacity-50`}>
                <Megaphone className="w-3.5 h-3.5" /> Command {toCommand.length === 1 ? toCommand[0].displayName : `${toCommand.length} creatures`}
              </button>
            )}
            {myCharacter.spellsKnown && (
              <button onClick={() => selectSelfAction('spell')}
                className={`${SELF_ACTION} bg-ink/10 hover:bg-ink/20 text-ink border-ink/30`}>
                <Sparkles className="w-3.5 h-3.5" /> Cast Spell
              </button>
            )}
          </div>
        )}

        {/* Shapechange's or Animal Shapes' new form, with the caster's action (D192) */}
        {isActive && myCharacter && myCharacter.lifeState === 'ALIVE' && reshapingSpell(myCharacter) && (
          <ChangeFormControl caster={myCharacter} participants={encounter.participants}
            onChange={(targetId, creatureId) => actions.changeForm({ participantId: myCharacter.id, targetId, creatureId })} />
        )}

        {/* The beast's actions, bonus actions and reactions of the player's character in Wild Shape (PHB p.67), or of
            the form a spell gave it (D192) */}
        {isActive && showBeastActions && myCharacter && formOf(myCharacter) && myCharacter.lifeState === 'ALIVE' && (
          <div className="mb-4 border border-rule bg-card">
            <InlineMonsterActions participant={myCharacter} participants={encounter.participants} isLairTurn={false}
              inLair={false} />
          </div>
        )}

        {/* A creature the character summoned, on its own turn: its turn economy and its stat block's actions, as a
            druid's beast in Wild Shape (D182). A familiar's attacks are left out (PHB p.240); a creature that acts
            only when commanded waits for the command (PHB p.212, XGE p.168) */}
        {isActive && summonOnTurn && (
          <section aria-label={`${summonOnTurn.displayName}'s turn`} className="mb-4 border border-cls-druid/30 bg-card">
            <p className="px-4 pt-3 font-heading text-[15px] font-semibold tracking-[0.01em] text-cls-druid">
              It&apos;s {summonOnTurn.displayName}&apos;s turn
            </p>
            <div className="px-4 pt-2">
              <TurnEconomyBar participant={summonOnTurn} variant="card" />
            </div>
            {summonOnTurn.summon?.needsCommand && summonOnTurn.summon.commandedRound == null ? (
              <p role="note" className="px-4 pb-3 font-body text-[15px] font-semibold text-muted">
                {summonOnTurn.displayName} has no command, so it only defends itself. Command it with a bonus action on
                your turn.
              </p>
            ) : (
              <InlineMonsterActions participant={summonOnTurn} participants={encounter.participants} isLairTurn={false}
                inLair={false} noAttacks={summonOnTurn.summon?.cannotAttack ?? false} />
            )}
          </section>
        )}

        {/* Pact of the Chain (PHB p.107): on the warlock's turn its familiar attacks in place of one of its attacks */}
        {isActive && isMyTurn && myCharacter?.pactOfTheChain && myCharacter.lifeState === 'ALIVE'
          && myFamiliars.map(familiar => (
            <FamiliarAttack key={familiar.id} warlock={myCharacter} familiar={familiar}
              participants={encounter.participants} />
          ))}

        {/* Spell cast modal */}
        {isActive && actionMode === 'spell' && myCharacter && (
          <SpellCastModal
            caster={myCharacter}
            participants={encounter.participants}
            onClose={closeAction}
            allowManualModifiers={encounter.allowPlayerManualModifiers}
          />
        )}

        {/* The player's use of one of their Spell Effects (M15) */}
        {isActive && myCharacter && effectInUse && (
          <RepeatEffectModal
            caster={myCharacter}
            effect={effectInUse}
            participants={encounter.participants}
            onClose={() => setEffectInUseId(null)}
            allowManualModifiers={encounter.allowPlayerManualModifiers}
          />
        )}
        {/* A creature of the player's using another creature's Spell Effect, as it exhales Dragon's Breath (D200) */}
        {isActive && touchedActor && touchedEffect && (
          <RepeatEffectModal
            caster={touchedActor}
            effect={touchedEffect}
            participants={encounter.participants}
            onClose={() => setTouchedUse(null)}
            allowManualModifiers={encounter.allowPlayerManualModifiers}
          />
        )}
        {effectError && <p role="alert" className="text-debuff font-body text-[15px] font-semibold mb-4">{effectError}</p>}

        {/* The player's own Actions and Reactions menus; players never see another creature's (D157, D174) */}
        {isActive && actionMode === 'standard' && isMyTurn && myCharacter && (
          <StandardActionMenu participant={myCharacter} participants={encounter.participants} onClose={closeAction} />
        )}
        {isActive && actionMode === 'reaction' && myCharacter && (
          <ReactionMenu participant={myCharacter} participants={encounter.participants}
            allowManualModifiers={encounter.allowPlayerManualModifiers} onClose={closeAction} />
        )}

        {/* Action panel */}
        {isActive && actionMode && actionMode !== 'spell' && actionMode !== 'standard' && actionMode !== 'reaction' && myCharacter && (
          <PlayerActionPanel
            myCharacter={myCharacter}
            encounter={encounter}
            targetId={attackTargetId}
            actionMode={actionMode}
            onClose={closeAction}
          />
        )}

        {/* Initiative list, in the order the server computes */}
        <div className="space-y-2 mb-4" role="list" aria-label="Initiative order">
          {encounter.turnOrder.map(turn => {
            if (turn.lair) {
              return <LairTurnRow key="lair" turn={turn} lairMonsters={lairMonsters} />;
            }
            const p = participantsById.get(turn.participantId ?? '');
            if (!p) return null;
            const isOwn = p.controlledByUserId === user?.userId;
            // A summoned creature nobody plays, as Summon Lesser Demons' demons, reads as a monster (D157, D182)
            const isMonster = p.participantType === 'MONSTER' || (p.participantType === 'COMPANION' && !p.controlledByUserId);
            const kindLabel = isMonster ? 'Monster' : p.participantType === 'COMPANION' ? 'Companion' : 'Player';
            const monsterHealth = isMonster ? healthLabel(p) : null;

            return (
              <ParticipantRow
                key={p.id}
                participant={p}
                participants={encounter.participants}
                current={turn.current}
                highlighted={isOwn}
                summonerName={p.summonedByParticipantId ? participantsById.get(p.summonedByParticipantId)?.displayName : null}
                onEndWildShape={isOwn && isActive && p.wildShape
                  ? () => { void effectCommand(() => actions.usePool({ participantId: p.id, poolId: wildShapePoolId(p), option: 'end' })); }
                  : undefined}
                badges={(
                  <>
                    {p.id === myCharacter?.id && <span className="px-1.5 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] bg-ink/10 text-ink">You</span>}
                    <LifeStateBadge participant={p} />
                  </>
                )}
                trailing={isOwn ? <HpReadout participant={p} /> : (
                  <div className="flex items-center gap-2 flex-shrink-0 ml-auto">
                    <span className="text-faint font-body text-[15px] font-semibold">{kindLabel}</span>
                    {monsterHealth && (
                      <span className="font-heading text-[13px] font-semibold text-muted">{monsterHealth}</span>
                    )}
                    {/* The dead can't be attacked; the dying can (D161). Off the player's turn an opportunity attack is
                        in their Reaction menu (D174) */}
                    {isActive && isMyTurn && p.lifeState !== 'DEAD' && (
                      <IconButton label="Attack" onClick={() => selectAttackTarget(p.id)}
                        className="inline-flex items-center justify-center size-11 bg-cls-fighter/10 hover:bg-cls-fighter/20 text-cls-fighter">
                        <Crosshair className="w-3.5 h-3.5" />
                      </IconButton>
                    )}
                  </div>
                )}
              >
                {/* Only the player's own conditions can be removed here */}
                <ConditionChips
                  participant={p}
                  roundNumber={encounter.roundNumber}
                  onRemove={isOwn && isActive ? c => actions.removeCondition(p.id, c.name) : undefined}
                />
                {/* The player directs their own Spell Effects on their turn, and may end them on any (M15) */}
                <ActiveSpells
                  participant={p}
                  roundNumber={encounter.roundNumber}
                  onUse={isOwn && isActive && p.lifeState === 'ALIVE' ? effect => directEffect(effect) : undefined}
                  blockedBy={effect => effectBlock(p, effect)}
                  onEnd={isOwn && isActive ? effect => { void effectCommand(() => actions.endSpellEffect({ participantId: p.id, effectId: effect.id })); } : undefined}
                  onMove={isOwn && isActive ? (effect, targetId) => { void effectCommand(() => actions.moveSpellEffect({ participantId: p.id, effectId: effect.id, targetId })); } : undefined}
                  participants={encounter.participants}
                />
                {/* Another creature's Spell Effect the player's creature uses with its own action, as the creature
                    Dragon's Breath touched exhales it, on its turn (XGE p.154, D200) */}
                {isOwn && isActive && p.lifeState === 'ALIVE' && (
                  <TouchedEffectUses participant={p} participants={encounter.participants}
                    blockedBy={effect => effectBlock(p, effect)}
                    onUse={effect => setTouchedUse({ actorId: p.id, effectId: effect.id })} />
                )}
                {/* Which spells a creature the player controls lets through its Limited Magic Immunity (D198) */}
                {isOwn && p.spellConsentTrait && (
                  <div className="mt-2">
                    <SpellConsentControl participant={p} disabled={!isActive}
                      onChange={consent => actions.setSpellConsent(p.id, consent)} />
                  </div>
                )}
              </ParticipantRow>
            );
          })}
        </div>

        <CombatLog entries={logs} />
      </main>
    </div>
  );
}

export default function PlayerEncounterSessionPage() {
  const { encounterId } = useParams<{ encounterId: string }>();
  if (!encounterId) return null;

  return (
    <EncounterProvider encounterId={encounterId}>
      <PlayerSessionView />
    </EncounterProvider>
  );
}
