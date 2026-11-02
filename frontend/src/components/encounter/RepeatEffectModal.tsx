import { useState, useMemo } from 'react';
import { Target, RotateCw, AlertTriangle, Shield } from 'lucide-react';
import { useEncounterAction } from '../../queries/encounters';
import { useSpellTargeting } from '../../queries/reference';
import type { EncounterParticipant } from '../../types/encounter';
import type { CastSpellResponse } from '../../types/combat';
import type { SpellTargetingInfo } from '../../types/reference';
import { healthLabel } from '../../utils/healthStatus';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Modal from '../ui/Modal';

interface Props {
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  spellName: string;
  onClose: () => void;
  isMonster?: boolean;
}

type Step = 'target' | 'confirm';

/** Repeats a spell's effect in the live encounter of the surrounding EncounterProvider, whose state the result updates. */
export default function RepeatEffectModal({ caster, participants, spellName, onClose, isMonster }: Props) {
  const actions = useEncounterAction();
  const [step, setStep] = useState<Step>('target');
  const [selectedTargets, setSelectedTargets] = useState<string[]>([]);
  const [advantage, setAdvantage] = useState<boolean | null>(null);
  const [casting, setCasting] = useState(false);
  const [result, setResult] = useState<CastSpellResponse | null>(null);
  const [error, setError] = useState('');

  const [overrideAttackBonus, setOverrideAttackBonus] = useState<string>('');
  const [overrideSaveDC, setOverrideSaveDC] = useState<string>('');
  // A spell without an effect template has no targeting, which leaves the targets unrestricted
  const targeting = useSpellTargeting(spellName, caster.concentrationSlotLevel || caster.activeSpellSlotLevel || 0);
  const targetingInfo: SpellTargetingInfo | null = targeting.data ?? null;
  const targetingLoading = targeting.isPending;

  function toggleTarget(id: string) {
    setSelectedTargets(prev => {
      if (prev.includes(id)) return prev.filter(t => t !== id);
      const max = targetingInfo?.maxTargets ?? -1;
      if (max > 0 && prev.length >= max) return prev;
      return [...prev, id];
    });
  }

  async function repeatEffect() {
    setCasting(true);
    setError('');

    try {
      setResult(await actions.repeatSpellEffect({
        targetIds: selectedTargets,
        advantage,
        overrideSpellAttackBonus: overrideAttackBonus ? parseInt(overrideAttackBonus) : undefined,
        overrideSpellSaveDC: overrideSaveDC ? parseInt(overrideSaveDC) : undefined,
      }, caster.id));
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to repeat spell effect');
      setCasting(false);
    }
  }

  // The dead are out of reach, but the dying and stable can still be healed or harmed (D161)
  const otherParticipants = participants.filter(p => p.id !== caster.id && p.lifeState !== 'DEAD');

  return (
    <Modal
      title={<><RotateCw className="w-5 h-5 text-cls-warlock" />{spellName} — Repeat Effect</>}
      subtitle={(
        <>
          {caster.displayName}
          {(caster.concentrationSlotLevel || caster.activeSpellSlotLevel) && (
            <span className="text-cls-warlock ml-1">(slot level {caster.concentrationSlotLevel || caster.activeSpellSlotLevel})</span>
          )}
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

        {error && (
          <div role="alert" className="bg-debuff-bg border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{error}</div>
        )}

        {result ? (
          <ResultView result={result} onClose={onClose} />
        ) : step === 'target' ? (
          <TargetStep
            spellName={spellName}
            targets={otherParticipants}
            caster={caster}
            selectedTargets={selectedTargets}
            onToggle={toggleTarget}
            onProceed={() => setStep('confirm')}
            targetingInfo={targetingInfo}
            targetingLoading={targetingLoading}
          />
        ) : (
          <ConfirmStep
            spellName={spellName}
            targets={participants.filter(p => selectedTargets.includes(p.id))}
            caster={caster}
            advantage={advantage}
            onAdvantageChange={setAdvantage}
            casting={casting}
            onRepeat={repeatEffect}
            onBack={() => setStep('target')}
            isMonster={isMonster}
            overrideAttackBonus={overrideAttackBonus}
            overrideSaveDC={overrideSaveDC}
            onOverrideAttackBonus={setOverrideAttackBonus}
            onOverrideSaveDC={setOverrideSaveDC}
          />
        )}
      </div>
    </Modal>
  );
}

function TargetStep({ spellName, targets, caster, selectedTargets, onToggle, onProceed, targetingInfo, targetingLoading }: {
  spellName: string;
  targets: EncounterParticipant[];
  caster: EncounterParticipant;
  selectedTargets: string[];
  onToggle: (id: string) => void;
  onProceed: () => void;
  targetingInfo: SpellTargetingInfo | null;
  targetingLoading: boolean;
}) {
  const maxTargets = targetingInfo?.maxTargets ?? -1;
  const selfOnly = targetingInfo?.selfOnly ?? false;
  const canTargetSelf = targetingInfo?.canTargetSelf ?? true;
  const casterIsMonster = caster.participantType === 'MONSTER';

  const filteredTargets = useMemo(() => {
    if (selfOnly) return [caster];

    const eligible: EncounterParticipant[] = [];
    if (canTargetSelf) eligible.push(caster);

    for (const p of targets) {
      const pIsMonster = p.participantType === 'MONSTER';
      const isAlly = casterIsMonster === pIsMonster;
      if (isAlly && targetingInfo?.canTargetAllies !== false) eligible.push(p);
      else if (!isAlly && targetingInfo?.canTargetEnemies !== false) eligible.push(p);
    }
    return eligible;
  }, [targets, caster, targetingInfo, selfOnly, canTargetSelf, casterIsMonster]);

  const atLimit = maxTargets > 0 && selectedTargets.length >= maxTargets;

  const targetLabel = maxTargets > 0
    ? `Select targets (${selectedTargets.length}/${maxTargets})`
    : `Select target${selectedTargets.length > 0 ? `s (${selectedTargets.length})` : '(s)'}`;

  return (
    <>
      <div className="flex items-center gap-2 mb-2">
        <span className="font-heading text-[16px] font-semibold text-ink">{spellName}</span>
        <span className="font-body text-[13px] font-semibold text-faint">— {targetingLoading ? 'Loading...' : targetLabel}</span>
      </div>

      {targetingLoading ? (
        <p className="font-body text-[15px] text-faint text-center py-4">Loading targeting info...</p>
      ) : (
        <div className="space-y-1">
          {filteredTargets.map(p => {
            const isSelected = selectedTargets.includes(p.id);
            const isSelf = p.id === caster.id;
            const disabled = !isSelected && atLimit;
            return (
              <button
                key={p.id}
                type="button"
                onClick={() => !disabled && onToggle(p.id)}
                disabled={disabled}
                aria-pressed={isSelected}
                className={`w-full flex items-center justify-between px-3 py-2 text-left transition-colors
                  ${isSelected ? 'bg-page-alt border-2 border-cls-warlock' :
                    disabled ? 'bg-page-alt border border-rule opacity-50 cursor-not-allowed' :
                    'bg-page-alt border border-rule hover:border-muted'}
                `}
              >
                <div className="flex items-center gap-2">
                  <Target className={`w-3.5 h-3.5 ${isSelected ? 'text-cls-warlock' : 'text-faint'}`} />
                  <span className={`font-body text-[15px] font-semibold ${isSelected ? 'text-ink' : 'text-muted'}`}>{p.displayName}</span>
                  {isSelf && <span className="font-body text-[12px] text-faint">(self)</span>}
                </div>
                <div className="flex items-center gap-2 font-body text-[13px] text-faint">
                  <span>AC {p.armourClass}</span>
                  {healthLabel(p) && <span>{healthLabel(p)}</span>}
                </div>
              </button>
            );
          })}
        </div>
      )}

      <Button size="lg" className="w-full mt-2" onClick={onProceed} disabled={selectedTargets.length === 0}>
        Continue
      </Button>
    </>
  );
}

function ConfirmStep({ spellName, targets, caster, advantage, onAdvantageChange, casting, onRepeat, onBack, isMonster, overrideAttackBonus, overrideSaveDC, onOverrideAttackBonus, onOverrideSaveDC }: {
  spellName: string;
  targets: EncounterParticipant[];
  caster: EncounterParticipant;
  advantage: boolean | null;
  onAdvantageChange: (v: boolean | null) => void;
  casting: boolean;
  onRepeat: () => void;
  onBack: () => void;
  isMonster?: boolean;
  overrideAttackBonus: string;
  overrideSaveDC: string;
  onOverrideAttackBonus: (v: string) => void;
  onOverrideSaveDC: (v: string) => void;
}) {
  return (
    <>
      <div className="flex items-center gap-2 mb-3">
        <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>
        <span className="font-heading text-[16px] font-semibold text-ink">Confirm</span>
      </div>

      <div className="bg-page-alt border border-rule p-4 space-y-2">
        <div className="flex items-center gap-2">
          <RotateCw className="w-4 h-4 text-cls-warlock" />
          <span className="font-heading text-[16px] font-bold text-ink">{spellName}</span>
        </div>

        <p className="font-body text-[15px] font-semibold text-muted">
          <Shield className="w-3.5 h-3.5 inline mr-1" />
          Targeting: {targets.map(t => t.displayName).join(', ') || 'None'}
        </p>
      </div>

      <div className="flex gap-2">
        {(['normal', 'advantage', 'disadvantage'] as const).map(mode => {
          const val = mode === 'normal' ? null : mode === 'advantage';
          return (
            <button
              key={mode}
              type="button"
              onClick={() => onAdvantageChange(val)}
              aria-pressed={advantage === val}
              className={`flex-1 py-1.5 font-body text-[14px] font-semibold transition-colors ${
                advantage === val
                  ? 'bg-ink text-card'
                  : 'bg-page-alt border border-rule text-muted hover:text-ink'
              }`}
            >
              {mode.charAt(0).toUpperCase() + mode.slice(1)}
            </button>
          );
        })}
      </div>

      {isMonster && (
        <div className="grid grid-cols-2 gap-2">
          <Input
            label="Spell Attack Bonus"
            type="number"
            value={overrideAttackBonus}
            onChange={e => onOverrideAttackBonus(e.target.value)}
            placeholder={String(caster.spellAttackBonus ?? 0)}
          />
          <Input
            label="Spell Save DC"
            type="number"
            value={overrideSaveDC}
            onChange={e => onOverrideSaveDC(e.target.value)}
            placeholder={String(caster.spellSaveDc ?? 10)}
          />
        </div>
      )}

      <Button size="lg" className="w-full" onClick={onRepeat} loading={casting}>
        <RotateCw className="w-4 h-4" />
        {casting ? 'Repeating...' : 'Repeat Effect'}
      </Button>
    </>
  );
}

function ResultView({ result, onClose }: { result: CastSpellResponse; onClose: () => void }) {
  return (
    <div className="space-y-3">
      <div className="bg-page-alt border border-cls-warlock/30 p-4">
        <p className="font-heading text-[16px] font-bold text-ink mb-1 flex items-center gap-2">
          <RotateCw className="w-4 h-4 text-cls-warlock" />
          {result.spellName}
          <span className="font-body text-[13px] font-normal text-faint">repeat effect</span>
        </p>
        <p className="font-body text-[15px] font-semibold text-muted">{result.resultSummary}</p>
      </div>

      {!result.autoResolved && result.manualResolutionReason && (
        <div className="bg-page-alt border border-cls-monk/30 p-3">
          <p className="font-body text-[15px] font-semibold text-cls-monk flex items-center gap-2">
            <AlertTriangle className="w-4 h-4" />
            Requires DM adjudication: {result.manualResolutionReason}
          </p>
        </div>
      )}

      {result.targets.length > 0 && result.autoResolved && (
        <div className="space-y-1.5">
          {result.targets.map(t => (
            <div key={t.targetId} className="bg-page-alt border border-rule px-3 py-2 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${
                  t.outcome === 'miss' || t.outcome === 'saved' ? 'bg-faint' :
                  t.outcome === 'critical' ? 'bg-debuff' : 'bg-buff'
                }`} />
                <span className="font-body text-[15px] font-semibold text-ink">{t.targetName}</span>
              </div>
              <div className="flex items-center gap-3 font-body text-[13px]">
                {t.attackRoll != null && (
                  <span className={t.outcome === 'miss' ? 'text-faint' : 'text-buff'}>
                    Roll: {t.attackRoll}
                  </span>
                )}
                {t.saveRoll != null && (
                  <span className={t.outcome === 'saved' ? 'text-buff' : 'text-debuff'}>
                    Save: {t.saveRoll}
                  </span>
                )}
                {t.damage != null && t.damage > 0 && (
                  <span className="text-debuff">{t.damage} dmg</span>
                )}
                {t.healing != null && t.healing > 0 && (
                  <span className="text-buff">{t.healing} heal</span>
                )}
                {t.conditionsApplied.length > 0 && (
                  <span className="text-cls-monk">{t.conditionsApplied.join(', ')}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <Button variant="secondary" className="w-full" onClick={onClose}>
        Done
      </Button>
    </div>
  );
}
