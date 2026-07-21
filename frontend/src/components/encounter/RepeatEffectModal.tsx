import { useState, useMemo, useEffect, useCallback } from 'react';
import { X, Target, RotateCw, AlertTriangle, Shield } from 'lucide-react';
import { combatApi } from '../../api/combatApi';
import { getSpellTargeting } from '../../api/referenceApi';
import type { SpellTargetingInfo } from '../../api/referenceApi';
import type { EncounterParticipant } from '../../types/encounter';
import type { CastSpellResponse } from '../../types/combat';

interface Props {
  encounterId: string;
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  spellName: string;
  onUpdate: (encounterState: any) => void;
  onClose: () => void;
  isMonster?: boolean;
}

type Step = 'target' | 'confirm';

export default function RepeatEffectModal({ encounterId, caster, participants, spellName, onUpdate, onClose, isMonster }: Props) {
  const [step, setStep] = useState<Step>('target');
  const [selectedTargets, setSelectedTargets] = useState<string[]>([]);
  const [advantage, setAdvantage] = useState<boolean | null>(null);
  const [casting, setCasting] = useState(false);
  const [result, setResult] = useState<CastSpellResponse | null>(null);
  const [error, setError] = useState('');

  const [overrideAttackBonus, setOverrideAttackBonus] = useState<string>('');
  const [overrideSaveDC, setOverrideSaveDC] = useState<string>('');
  const [targetingInfo, setTargetingInfo] = useState<SpellTargetingInfo | null>(null);
  const [targetingLoading, setTargetingLoading] = useState(true);

  const fetchTargeting = useCallback(async () => {
    setTargetingLoading(true);
    try {
      const slotLevel = caster.concentrationSlotLevel || caster.activeSpellSlotLevel || 0;
      const info = await getSpellTargeting(spellName, slotLevel);
      setTargetingInfo(info);
    } catch {
      setTargetingInfo(null);
    } finally {
      setTargetingLoading(false);
    }
  }, [spellName, caster.concentrationSlotLevel, caster.activeSpellSlotLevel]);

  useEffect(() => { fetchTargeting(); }, [fetchTargeting]);

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
      const res = await combatApi.repeatSpellEffect(encounterId, {
        targetIds: selectedTargets,
        advantage,
        overrideSpellAttackBonus: overrideAttackBonus ? parseInt(overrideAttackBonus) : undefined,
        overrideSpellSaveDC: overrideSaveDC ? parseInt(overrideSaveDC) : undefined,
      }, caster.id);

      setResult(res.data);
      onUpdate(res.data.encounterState);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to repeat spell effect');
      setCasting(false);
    }
  }

  const otherParticipants = participants.filter(p => p.id !== caster.id && p.isAlive);

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="repeat-effect-title"
        className="bg-gray-900 border border-gray-800 rounded-xl w-full max-w-lg max-h-[90vh] flex flex-col"
        onClick={e => e.stopPropagation()}
      >
        <div className="px-5 py-4 border-b border-gray-800 flex items-center justify-between shrink-0">
          <div>
            <h2 id="repeat-effect-title" className="text-white font-bold text-lg flex items-center gap-2">
              <RotateCw className="w-5 h-5 text-purple-400" />
              {spellName} — Repeat Effect
            </h2>
            <p className="text-gray-400 text-xs mt-0.5">
              {caster.displayName}
              {(caster.concentrationSlotLevel || caster.activeSpellSlotLevel) && (
                <span className="text-purple-400 ml-1">(slot level {caster.concentrationSlotLevel || caster.activeSpellSlotLevel})</span>
              )}
            </p>
          </div>
          <button onClick={onClose} aria-label="Close" className="text-gray-400 hover:text-white">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="px-5 py-4 space-y-3 overflow-y-auto flex-1 min-h-0">
          <div className="flex items-center gap-2 bg-purple-900/20 border border-purple-700/30 rounded-lg px-3 py-2">
            <RotateCw className="w-3.5 h-3.5 text-purple-400 shrink-0" />
            <p className="text-purple-300 text-xs">No spell slot consumed — repeating existing concentration effect</p>
          </div>

          {error && (
            <div role="alert" className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 text-sm">{error}</div>
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
      </div>
    </div>
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
        <span className="text-white font-medium">{spellName}</span>
        <span className="text-gray-500 text-xs">— {targetingLoading ? 'Loading...' : targetLabel}</span>
      </div>

      {targetingLoading ? (
        <p className="text-gray-500 text-sm text-center py-4">Loading targeting info...</p>
      ) : (
        <div className="space-y-1">
          {filteredTargets.map(p => {
            const isSelected = selectedTargets.includes(p.id);
            const isSelf = p.id === caster.id;
            const disabled = !isSelected && atLimit;
            return (
              <button
                key={p.id}
                onClick={() => !disabled && onToggle(p.id)}
                disabled={disabled}
                className={`w-full flex items-center justify-between px-3 py-2 rounded-lg text-left transition-colors
                  ${isSelected ? 'bg-purple-900/40 border border-purple-500/50' :
                    disabled ? 'bg-gray-800/50 border border-gray-800 opacity-50 cursor-not-allowed' :
                    'bg-gray-800 border border-gray-700 hover:bg-gray-750'}
                `}
              >
                <div className="flex items-center gap-2">
                  <Target className={`w-3.5 h-3.5 ${isSelected ? 'text-purple-400' : 'text-gray-500'}`} />
                  <span className={isSelected ? 'text-white' : 'text-gray-300'}>{p.displayName}</span>
                  {isSelf && <span className="text-gray-500 text-[10px]">(self)</span>}
                </div>
                <div className="flex items-center gap-2 text-xs text-gray-500">
                  <span>AC {p.armourClass}</span>
                  <span>{p.hpCurrent}/{p.hpMax} HP</span>
                </div>
              </button>
            );
          })}
        </div>
      )}

      <button
        onClick={onProceed}
        disabled={selectedTargets.length === 0}
        className="w-full mt-2 px-4 py-2.5 bg-purple-600 hover:bg-purple-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
      >
        Continue
      </button>
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
        <button onClick={onBack} className="text-gray-400 hover:text-white text-sm">&larr; Back</button>
        <span className="text-white font-medium">Confirm</span>
      </div>

      <div className="bg-gray-800 rounded-lg p-4 space-y-2">
        <div className="flex items-center gap-2">
          <RotateCw className="w-4 h-4 text-purple-400" />
          <span className="text-white font-bold">{spellName}</span>
        </div>

        <p className="text-gray-400 text-sm">
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
              onClick={() => onAdvantageChange(val)}
              className={`flex-1 py-1.5 text-xs font-medium rounded-lg transition-colors ${
                advantage === val
                  ? 'bg-purple-600 text-white'
                  : 'bg-gray-800 text-gray-400 hover:text-white'
              }`}
            >
              {mode.charAt(0).toUpperCase() + mode.slice(1)}
            </button>
          );
        })}
      </div>

      {isMonster && (
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="text-gray-400 text-xs">Spell Attack Bonus</label>
            <input
              type="number"
              value={overrideAttackBonus}
              onChange={e => onOverrideAttackBonus(e.target.value)}
              placeholder={String(caster.spellAttackBonus ?? 0)}
              className="w-full mt-1 px-3 py-1.5 bg-gray-800 rounded border border-gray-700 text-white text-sm focus:outline-none focus:ring-1 focus:ring-purple-500"
            />
          </div>
          <div>
            <label className="text-gray-400 text-xs">Spell Save DC</label>
            <input
              type="number"
              value={overrideSaveDC}
              onChange={e => onOverrideSaveDC(e.target.value)}
              placeholder={String(caster.spellSaveDc ?? 10)}
              className="w-full mt-1 px-3 py-1.5 bg-gray-800 rounded border border-gray-700 text-white text-sm focus:outline-none focus:ring-1 focus:ring-purple-500"
            />
          </div>
        </div>
      )}

      <button
        onClick={onRepeat}
        disabled={casting}
        className="w-full px-4 py-2.5 bg-purple-600 hover:bg-purple-500 text-white text-sm font-bold rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
      >
        <RotateCw className="w-4 h-4" />
        {casting ? 'Repeating...' : 'Repeat Effect'}
      </button>
    </>
  );
}

function ResultView({ result, onClose }: { result: CastSpellResponse; onClose: () => void }) {
  return (
    <div className="space-y-3">
      <div className="bg-purple-900/20 border border-purple-700/30 rounded-lg p-4">
        <p className="text-white font-bold mb-1 flex items-center gap-2">
          <RotateCw className="w-4 h-4 text-purple-400" />
          {result.spellName}
          <span className="text-gray-400 text-xs font-normal">repeat effect</span>
        </p>
        <p className="text-gray-300 text-sm">{result.resultSummary}</p>
      </div>

      {!result.autoResolved && result.manualResolutionReason && (
        <div className="bg-amber-900/20 border border-amber-700/30 rounded-lg p-3">
          <p className="text-amber-300 text-sm flex items-center gap-2">
            <AlertTriangle className="w-4 h-4" />
            Requires DM adjudication: {result.manualResolutionReason}
          </p>
        </div>
      )}

      {result.targets.length > 0 && result.autoResolved && (
        <div className="space-y-1.5">
          {result.targets.map(t => (
            <div key={t.targetId} className="bg-gray-800 rounded-lg px-3 py-2 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${
                  t.outcome === 'miss' || t.outcome === 'saved' ? 'bg-gray-500' :
                  t.outcome === 'critical' ? 'bg-red-400' : 'bg-green-400'
                }`} />
                <span className="text-white text-sm">{t.targetName}</span>
              </div>
              <div className="flex items-center gap-3 text-xs">
                {t.attackRoll != null && (
                  <span className={t.outcome === 'miss' ? 'text-gray-500' : 'text-green-400'}>
                    Roll: {t.attackRoll}
                  </span>
                )}
                {t.saveRoll != null && (
                  <span className={t.outcome === 'saved' ? 'text-green-400' : 'text-red-400'}>
                    Save: {t.saveRoll}
                  </span>
                )}
                {t.damage != null && t.damage > 0 && (
                  <span className="text-red-400">{t.damage} dmg</span>
                )}
                {t.healing != null && t.healing > 0 && (
                  <span className="text-green-400">{t.healing} heal</span>
                )}
                {t.conditionsApplied.length > 0 && (
                  <span className="text-amber-400">{t.conditionsApplied.join(', ')}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <button
        onClick={onClose}
        className="w-full px-4 py-2 bg-gray-800 hover:bg-gray-700 text-white text-sm font-medium rounded-lg transition-colors"
      >
        Done
      </button>
    </div>
  );
}
