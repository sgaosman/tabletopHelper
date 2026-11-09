import { useEffect, useState } from 'react';
import { RotateCw } from 'lucide-react';
import { useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant, SpellEffect, SpellTrigger } from '../../types/encounter';
import Modal from '../ui/Modal';
import useSpellFlow from './spellflow/useSpellFlow';
import TargetStep from './spellflow/TargetStep';
import ConfirmStep from './spellflow/ConfirmStep';
import ResultView from './spellflow/ResultView';
import { chargeBlock, costPhrase } from '../../utils/turnEconomy';
import { FIELD_INPUT, FIELD_LABEL } from './formStyles';

interface Props {
  caster: EncounterParticipant;
  /** The caster's Spell Effect to use (M15). */
  effect: SpellEffect;
  participants: EncounterParticipant[];
  /**
   * The DM applying what the effect's area does by itself at this moment of a turn, such as Moonbeam's damage to a
   * creature starting its turn in the beam: any creature may be chosen, and it costs nothing. Left out for the
   * caster's own use.
   */
  trigger?: SpellTrigger;
  /** The targets chosen when it opens, such as the creature whose turn it is. */
  initialTargets?: string[];
  onClose: () => void;
  /** Offers a spell attack bonus and save DC typed by hand: always for the DM, for players while the campaign allows it (D164). */
  allowManualModifiers?: boolean;
  /** The DM uses it: the server never refuses the DM over the action economy (D174). */
  asDm?: boolean;
}

const MOMENTS: Record<SpellTrigger, string> = {
  ENTER: 'enters it for the first time on a turn',
  START_OF_TURN: 'starts its turn in it',
  END_OF_TURN: 'ends its turn in it',
};

/**
 * Uses one of a caster's Spell Effects in the live encounter of the surrounding EncounterProvider, whose state the
 * result updates (M15). It goes through the steps every spell pop-up shares; no slot is chosen, since it spends none.
 * The caster's own use says what it costs from their turn, as the server reports it, and why the turn can't pay where
 * the page can tell, the server staying the authority (D174); an effect with uses, such as Melf's Minute Meteors,
 * asks how many to spend. A trigger is the DM's: what the area does to the creatures it reaches, at no cost.
 */
export default function RepeatEffectModal({
  caster, effect, participants, trigger, initialTargets = [], onClose, allowManualModifiers = false, asDm = false,
}: Props) {
  const actions = useEncounterAction();
  const flow = useSpellFlow('target', { allowManualModifiers });
  const slotLevel = effect.slotLevel ?? 0;
  const { lookUpTargeting, chooseFreely } = flow;
  const [uses, setUses] = useState(1);
  const mostUses = trigger || effect.usesLeft == null ? 1
    : Math.max(1, Math.min(effect.maxUsesEachTurn ?? 1, effect.usesLeft));
  const cost = trigger ? null : (effect.directedCost ?? 'ACTION');
  const blocked = cost === null ? null
    : cost === 'NONE'
      ? (!asDm && !caster.isCurrentTurn ? 'Only on your turn' : null)
      : chargeBlock(caster, { cost });
  const economyWarning = blocked && asDm ? `Over the action economy: ${blocked}` : blocked;
  const initialKey = initialTargets.join(',');

  useEffect(() => {
    if (trigger) {
      chooseFreely(initialKey ? initialKey.split(',') : []);
    } else {
      void lookUpTargeting(effect.spell, slotLevel);
    }
  }, [trigger, chooseFreely, lookUpTargeting, effect.spell, slotLevel, initialKey]);

  function applyEffect() {
    flow.submit(() => actions.repeatSpellEffect({
      targetIds: flow.selectedTargets,
      advantage: flow.advantage,
      effectId: effect.id,
      ...(trigger ? { trigger } : {}),
      ...(uses > 1 ? { uses } : {}),
      ...flow.overrides(),
    }, caster.id));
  }

  return (
    <Modal
      title={<><RotateCw className="w-5 h-5 text-cls-warlock" />{effect.spell} — {trigger ? 'Apply Effect' : 'Repeat Effect'}</>}
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
          <p className="font-body text-[13px] font-semibold text-cls-warlock">
            {trigger
              ? `Applies to each creature that ${MOMENTS[trigger]}; it costs ${caster.displayName} nothing`
              : 'No spell slot consumed — using the spell\'s lasting effect'}
          </p>
        </div>

        {cost && (
          <p className="font-body text-[15px] font-semibold text-muted">Repeating it costs {costPhrase(cost)}</p>
        )}

        {mostUses > 1 && !flow.result && (
          <div>
            <label htmlFor="effect-uses" className={FIELD_LABEL}>How many to spend ({effect.usesLeft} left)</label>
            <select id="effect-uses" className={FIELD_INPUT} value={uses} onChange={e => setUses(Number(e.target.value))}>
              {Array.from({ length: mostUses }, (_, i) => i + 1).map(n => <option key={n} value={n}>{n}</option>)}
            </select>
          </div>
        )}

        {flow.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{flow.error}</div>
        )}

        {flow.result ? (
          <ResultView result={flow.result} onClose={onClose} />
        ) : flow.step === 'target' ? (
          <TargetStep
            spellName={effect.spell}
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
            spellName={effect.spell}
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
            submitLabel={trigger ? 'Apply Effect' : 'Repeat Effect'}
            busyLabel={trigger ? 'Applying...' : 'Repeating...'}
            submitIcon={RotateCw}
            submitting={flow.submitting}
            onSubmit={applyEffect}
            onBack={flow.takesNoTargets ? undefined : () => flow.setStep('target')}
          />
        )}
      </div>
    </Modal>
  );
}
