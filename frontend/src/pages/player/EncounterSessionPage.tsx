import { useParams, useNavigate } from 'react-router-dom';
import { EncounterProvider } from '../../context/EncounterContext';
import { useEncounter } from '../../hooks/useEncounter';
import { useAuth } from '../../hooks/useAuth';
import { useCombatLog, useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import SpellCastModal from '../../components/encounter/SpellCastModal';
import RepeatEffectModal from '../../components/encounter/RepeatEffectModal';
import RepeatSavePrompt from '../../components/encounter/RepeatSavePrompt';
import WeaponAttackForm from '../../components/encounter/WeaponAttackForm';
import LairTurnRow from '../../components/encounter/LairTurnRow';
import ParticipantRow from '../../components/encounter/ParticipantRow';
import LifeStateBadge from '../../components/encounter/LifeStateBadge';
import ConditionChips from '../../components/encounter/ConditionChips';
import ActiveSpells from '../../components/encounter/ActiveSpells';
import DeathSaves from '../../components/encounter/DeathSaves';
import SpellSlotPips from '../../components/encounter/SpellSlotPips';
import HpReadout from '../../components/encounter/HpReadout';
import CombatLog from '../../components/encounter/CombatLog';
import ConnectionStatus from '../../components/encounter/ConnectionStatus';
import ConditionForm from '../../components/encounter/ConditionForm';
import ConcentrationForm from '../../components/encounter/ConcentrationForm';
import ResourcePoolDisplay from '../../components/common/ResourcePoolDisplay';
import IconButton from '../../components/ui/IconButton';
import { healthLabel } from '../../utils/healthStatus';
import { ArrowLeft, Sparkles, Crosshair, Zap, Swords, X, RotateCw } from 'lucide-react';
import { useState } from 'react';

type PlayerAction = 'attack' | 'condition' | 'concentration' | 'spell' | 'repeat-effect' | null;

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

      {/* The weapons and numbers come from the server, with Manual Modifiers while the campaign allows them (D164) */}
      {actionMode === 'attack' && (
        <WeaponAttackForm attacker={myCharacter} target={target}
          allowManualModifiers={encounter.allowPlayerManualModifiers} onDone={onClose} />
      )}
      {actionMode === 'condition' && (
        <ConditionForm onSubmit={async (condition, duration) => {
          await actions.addCondition(target.id, condition, duration);
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
  const { encounter, isConnected, error } = useEncounter();
  // The server sends the players' log after every change, without entries about hidden monsters (D157, D171)
  const logs = useCombatLog();
  const actions = useEncounterAction();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [actionMode, setActionMode] = useState<PlayerAction>(null);
  const [attackTargetId, setAttackTargetId] = useState<string | null>(null);

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
  const isMyTurn = encounter.participants.some(p => p.isCurrentTurn && p.controlledByUserId === user?.userId);
  const myCharacter = encounter.participants.find(p => p.controlledByUserId === user?.userId);
  // Repeat Saves the campaign leaves to this player, at the end of their character's turn (D162)
  const savesToRoll = (myCharacter?.activeConditions ?? []).filter(c => c.repeatSave?.awaitingRoll);
  // Actions show only while the encounter is Active: the server refuses them while it is Paused or
  // Completed (D166)
  const isActive = encounter.status === 'ACTIVE';
  const closeAction = () => { setActionMode(null); setAttackTargetId(null); };

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
      <header className="sticky top-0 z-10 bg-page border-b border-rule px-6 py-3">
        <div className="flex items-center justify-between max-w-4xl mx-auto">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-muted hover:text-ink transition-colors font-body text-[15px] font-semibold">
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
            <ConnectionStatus isConnected={isConnected} />
          </div>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-4">
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
            onUse={isActive ? level => { void actions.spendSpellSlot(myCharacter.id, level); } : undefined}
          />
        )}

        {/* Resource pools (M24.5) */}
        {myCharacter && <ResourcePoolDisplay pools={myCharacter.resourcePoolsCurrent} />}

        {/* Player self-action buttons */}
        {isActive && myCharacter && myCharacter.lifeState === 'ALIVE' && (
          <div className="flex items-center gap-2 mb-4">
            <button onClick={() => selectSelfAction('condition')}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk font-heading text-[11px] font-semibold tracking-[0.02em] border border-cls-monk/30">
              <Zap className="w-3.5 h-3.5" /> Add Condition
            </button>
            <button onClick={() => selectSelfAction('concentration')}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock font-heading text-[11px] font-semibold tracking-[0.02em] border border-cls-warlock/30">
              <Swords className="w-3.5 h-3.5" /> {myCharacter.concentrationSpell ? 'Change' : 'Set'} Concentration
            </button>
            {myCharacter.spellsKnown && (
              <button onClick={() => selectSelfAction('spell')}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-ink/10 hover:bg-ink/20 text-ink font-heading text-[11px] font-semibold tracking-[0.02em] border border-ink/30">
                <Sparkles className="w-3.5 h-3.5" /> Cast Spell
              </button>
            )}
            {(myCharacter.concentrationSpell || myCharacter.activeSpell) && (
              <button onClick={() => selectSelfAction('repeat-effect')}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock font-heading text-[11px] font-semibold tracking-[0.02em] border border-cls-warlock/30">
                <RotateCw className="w-3.5 h-3.5" /> Repeat Effect
              </button>
            )}
          </div>
        )}

        {/* Spell cast modal */}
        {isActive && actionMode === 'spell' && myCharacter && (
          <SpellCastModal
            caster={myCharacter}
            participants={encounter.participants}
            onClose={closeAction}
            allowManualModifiers={encounter.allowPlayerManualModifiers}
          />
        )}

        {/* Repeat spell effect modal */}
        {isActive && actionMode === 'repeat-effect' && myCharacter && (myCharacter.concentrationSpell || myCharacter.activeSpell) && (
          <RepeatEffectModal
            caster={myCharacter}
            participants={encounter.participants}
            spellName={(myCharacter.concentrationSpell || myCharacter.activeSpell)!}
            onClose={closeAction}
            allowManualModifiers={encounter.allowPlayerManualModifiers}
          />
        )}

        {/* Action panel */}
        {isActive && actionMode && actionMode !== 'spell' && myCharacter && (
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
            const isMonster = p.participantType === 'MONSTER';
            const monsterHealth = isMonster ? healthLabel(p) : null;

            return (
              <ParticipantRow
                key={p.id}
                participant={p}
                current={turn.current}
                highlighted={isOwn}
                badges={(
                  <>
                    {isOwn && <span className="px-1.5 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] bg-ink/10 text-ink">You</span>}
                    <LifeStateBadge participant={p} />
                  </>
                )}
                trailing={isOwn ? <HpReadout participant={p} /> : (
                  <div className="flex items-center gap-2 flex-shrink-0">
                    <span className="text-faint font-body text-[15px] font-semibold">{isMonster ? 'Monster' : 'Player'}</span>
                    {monsterHealth && (
                      <span className="font-heading text-[13px] font-semibold text-muted">{monsterHealth}</span>
                    )}
                    {/* The dead can't be attacked; the dying can (D161) */}
                    {isActive && isMyTurn && p.lifeState !== 'DEAD' && (
                      <IconButton label="Attack" onClick={() => selectAttackTarget(p.id)}
                        className="p-1.5 bg-cls-fighter/10 hover:bg-cls-fighter/20 text-cls-fighter">
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
                  onRemove={isOwn && isActive ? c => { void actions.removeCondition(p.id, c.name); } : undefined}
                />
                <ActiveSpells participant={p} />
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
