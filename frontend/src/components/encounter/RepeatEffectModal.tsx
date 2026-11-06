import { useEffect } from 'react';
import { RotateCw } from 'lucide-react';
import { useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import Modal from '../ui/Modal';
import useSpellFlow from './spellflow/useSpellFlow';
import TargetStep from './spellflow/TargetStep';
import ConfirmStep from './spellflow/ConfirmStep';
import ResultView from './spellflow/ResultView';
import { chargeBlock, costPhrase } from '../../utils/turnEconomy';

interface Props {
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  spellName: string;
  onClose: () => void;
  /** Offers a spell attack bonus and save DC typed by hand: always for the DM, for players while the campaign allows it (D164). */
  allowManualModifiers?: boolean;
  /** The DM repeats it: the server never refuses the DM over the action economy (D174). */
  asDm?: boolean;
}

/**
 * Repeats a spell's effect in the live encounter of the surrounding EncounterProvider, whose state the result
 * updates. It goes through the steps every spell pop-up shares; no slot is chosen, since the repeat spends none. It
 * says what the repeat costs from the caster's turn, as the spell's data has it, and why the turn can't pay for it
 * where the page can tell; the server stays the authority (D174).
 */
export default function RepeatEffectModal({ caster, participants, spellName, onClose, allowManualModifiers = false, asDm = false }: Props) {
  const actions = useEncounterAction();
  const flow = useSpellFlow('target', { allowManualModifiers });
  // The effect repeats at the level the spell was cast with
  const slotLevel = caster.concentrationSlotLevel || caster.activeSpellSlotLevel || 0;
  const { lookUpTargeting } = flow;
  // Known once the spell's targeting has loaded; an effect the data doesn't price costs an action, as on the server
  const repeatCost = flow.targetingInfo ? (flow.targetingInfo.repeatActionType ?? 'ACTION') : null;
  const blocked = repeatCost === null ? null
    : repeatCost === 'NONE'
      ? (!asDm && !caster.isCurrentTurn ? 'Only on your turn; on another creature\'s turn the DM applies it' : null)
      : chargeBlock(caster, { cost: repeatCost });
  const economyWarning = blocked && asDm ? `Over the action economy: ${blocked}` : blocked;

  useEffect(() => {
    lookUpTargeting(spellName, slotLevel);
  }, [lookUpTargeting, spellName, slotLevel]);

  function repeatEffect() {
    flow.submit(() => actions.repeatSpellEffect({
      targetIds: flow.selectedTargets,
      advantage: flow.advantage,
      ...flow.overrides(),
    }, caster.id));
  }

  return (
    <Modal
      title={<><RotateCw className="w-5 h-5 text-cls-warlock" />{spellName} — Repeat Effect</>}
      subtitle={(
        <>
          {caster.displayName}
          {slotLevel > 0 && <span className="text-cls-warlock ml-1">(slot level {slotLevel})</span>}
        </>
      )}
      size="lg"
      onClose={onClose}
    >
      <div className="space-y-3">
        <div className="flex items-center gap-2 bg-page-alt border border-cls-warlock/30 px-3 py-2">
          <RotateCw className="w-3.5 h-3.5 text-cls-warlock shrink-0" />
          <p className="font-body text-[13px] font-semibold text-cls-warlock">No spell slot consumed — repeating existing concentration effect</p>
        </div>

        {repeatCost && (
          <p className="font-body text-[15px] font-semibold text-muted">Repeating it costs {costPhrase(repeatCost)}</p>
        )}

        {flow.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{flow.error}</div>
        )}

        {flow.result ? (
          <ResultView result={flow.result} onClose={onClose} />
        ) : flow.step === 'target' ? (
          <TargetStep
            spellName={spellName}
            caster={caster}
            participants={participants}
            selectedTargets={flow.selectedTargets}
            onToggle={flow.toggleTarget}
            targetingInfo={flow.targetingInfo}
            targetingLoading={flow.targetingLoading}
            onProceed={() => flow.setStep('confirm')}
          />
        ) : (
          <ConfirmStep
            spellName={spellName}
            caster={caster}
            targets={participants.filter(p => flow.selectedTargets.includes(p.id))}
            economyWarning={economyWarning}
            advantage={flow.advantage}
            onAdvantageChange={flow.setAdvantage}
            allowManualModifiers={allowManualModifiers}
            overrideAttackBonus={flow.overrideAttackBonus}
            overrideSaveDC={flow.overrideSaveDC}
            onOverrideAttackBonus={flow.setOverrideAttackBonus}
            onOverrideSaveDC={flow.setOverrideSaveDC}
            submitLabel="Repeat Effect"
            busyLabel="Repeating..."
            submitIcon={RotateCw}
            submitting={flow.submitting}
            onSubmit={repeatEffect}
            onBack={flow.takesNoTargets ? undefined : () => flow.setStep('target')}
          />
        )}
      </div>
    </Modal>
  );
}
