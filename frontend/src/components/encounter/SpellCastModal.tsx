import { useState, useMemo, useCallback } from 'react';
import { Zap, Shield, Target, Sparkles, AlertTriangle } from 'lucide-react';
import { useEncounterAction } from '../../queries/encounters';
import { useFetchSpellTargeting } from '../../queries/reference';
import type { EncounterParticipant, KnownSpell } from '../../types/encounter';
import type { CastSpellResponse } from '../../types/combat';
import type { SpellTargetingInfo } from '../../types/reference';
import { healthLabel } from '../../utils/healthStatus';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Modal from '../ui/Modal';

interface Props {
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  onClose: () => void;
  isMonster?: boolean;
}

type Step = 'spell' | 'slot' | 'target' | 'confirm';

/** Casts a spell in the live encounter of the surrounding EncounterProvider, whose state the result updates. */
export default function SpellCastModal({ caster, participants, onClose, isMonster }: Props) {
  const actions = useEncounterAction();
  const lookUpTargeting = useFetchSpellTargeting();
  const [step, setStep] = useState<Step>('spell');
  const [search, setSearch] = useState('');
  const [selectedSpell, setSelectedSpell] = useState<KnownSpell | null>(null);
  const [selectedSlotLevel, setSelectedSlotLevel] = useState<number>(0);
  const [usePactSlot, setUsePactSlot] = useState(false);
  const [selectedTargets, setSelectedTargets] = useState<string[]>([]);
  const [advantage, setAdvantage] = useState<boolean | null>(null);
  const [casting, setCasting] = useState(false);
  const [result, setResult] = useState<CastSpellResponse | null>(null);
  const [error, setError] = useState('');

  const [overrideAttackBonus, setOverrideAttackBonus] = useState<string>('');
  const [overrideSaveDC, setOverrideSaveDC] = useState<string>('');
  const [targetingInfo, setTargetingInfo] = useState<SpellTargetingInfo | null>(null);
  const [targetingLoading, setTargetingLoading] = useState(false);

  const fetchTargeting = useCallback(async (spellName: string, slotLevel: number) => {
    setTargetingLoading(true);
    setSelectedTargets([]);
    try {
      const info = await lookUpTargeting(spellName, slotLevel);
      setTargetingInfo(info);
      if (!info.selfOnly && !info.canTargetSelf && !info.canTargetAllies && !info.canTargetEnemies) {
        setStep('confirm');
        return;
      }
    } catch {
      setTargetingInfo(null);
    } finally {
      setTargetingLoading(false);
    }
    setStep('target');
  }, [lookUpTargeting]);

  const spells = useMemo(() => caster.spellsKnown ?? [], [caster.spellsKnown]);
  const slots = useMemo(() => caster.spellSlotsCurrent ?? {}, [caster.spellSlotsCurrent]);

  const filteredSpells = useMemo(() => {
    const q = search.toLowerCase();
    const filtered = spells.filter(s =>
      (s.prepared || s.alwaysPrepared || s.atWill || s.level === 0) &&
      s.name.toLowerCase().includes(q)
    );
    return filtered.sort((a, b) => a.level - b.level || a.name.localeCompare(b.name));
  }, [spells, search]);

  const cantrips = filteredSpells.filter(s => s.level === 0);
  const leveled = filteredSpells.filter(s => s.level > 0);

  const availableSlotLevels = useMemo(() => {
    if (!selectedSpell || selectedSpell.level === 0) return [];
    const levels: { level: number; remaining: number; max: number; isPact: boolean }[] = [];

    for (const [key, val] of Object.entries(slots)) {
      const isPact = key.startsWith('pact_');
      const lvl = isPact ? parseInt(key.replace('pact_', '')) : parseInt(key);
      if (isNaN(lvl) || lvl < selectedSpell.level) continue;
      levels.push({ level: lvl, remaining: val.max - val.used, max: val.max, isPact });
    }

    return levels.sort((a, b) => {
      if (a.isPact !== b.isPact) return a.isPact ? 1 : -1;
      return a.level - b.level;
    });
  }, [selectedSpell, slots]);

  function selectSpell(spell: KnownSpell) {
    setSelectedSpell(spell);
    setError('');

    if (spell.level === 0) {
      setSelectedSlotLevel(0);
      fetchTargeting(spell.name, 0);
    } else {
      setSelectedSlotLevel(spell.level);
      setStep('slot');
    }
  }

  function selectSlot(level: number, isPact: boolean) {
    setSelectedSlotLevel(level);
    setUsePactSlot(isPact);
    if (selectedSpell) fetchTargeting(selectedSpell.name, level);
  }

  function toggleTarget(id: string) {
    setSelectedTargets(prev => {
      if (prev.includes(id)) return prev.filter(t => t !== id);
      const max = targetingInfo?.maxTargets ?? -1;
      if (max > 0 && prev.length >= max) return prev;
      return [...prev, id];
    });
  }

  function proceedToConfirm() {
    setStep('confirm');
  }

  async function cast() {
    if (!selectedSpell) return;
    setCasting(true);
    setError('');

    try {
      const attackOverride = overrideAttackBonus ? parseInt(overrideAttackBonus) : undefined;
      const saveDcOverride = overrideSaveDC ? parseInt(overrideSaveDC) : undefined;
      // Monsters cast through their own endpoint so their slots and innate uses are spent (D165)
      const cast = isMonster
        ? await actions.monsterSpell({
            monsterParticipantId: caster.id,
            spellName: selectedSpell.name,
            slotLevel: selectedSlotLevel,
            targetParticipantIds: selectedTargets,
            advantage,
            overrideAttackBonus: attackOverride,
            overrideSaveDC: saveDcOverride,
          }, caster.id)
        : await actions.castSpell({
            spellName: selectedSpell.name,
            slotLevel: selectedSlotLevel,
            targetIds: selectedTargets,
            advantage,
            usePactSlot: usePactSlot || undefined,
            overrideSpellAttackBonus: attackOverride,
            overrideSpellSaveDC: saveDcOverride,
          }, caster.id);

      setResult(cast);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to cast spell');
      setCasting(false);
    }
  }

  function resetAndClose() {
    onClose();
  }

  const otherParticipants = participants.filter(p => p.id !== caster.id && p.isAlive);

  return (
    <Modal
      title={<><Sparkles className="w-5 h-5 text-ink" />Cast Spell</>}
      subtitle={caster.displayName}
      size="lg"
      onClose={onClose}
    >
      <div className="space-y-3">
        {error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{error}</div>
        )}

        {result ? (
          <ResultView result={result} onClose={resetAndClose} />
        ) : step === 'spell' ? (
          <SpellSelectionStep
            cantrips={cantrips}
            leveled={leveled}
            search={search}
            onSearch={setSearch}
            onSelect={selectSpell}
            isMonster={isMonster}
          />
        ) : step === 'slot' ? (
          <SlotSelectionStep
            spell={selectedSpell!}
            levels={availableSlotLevels}
            onSelect={selectSlot}
            onBack={() => setStep('spell')}
          />
        ) : step === 'target' ? (
          <TargetSelectionStep
            spell={selectedSpell!}
            targets={otherParticipants}
            caster={caster}
            selectedTargets={selectedTargets}
            onToggle={toggleTarget}
            onBack={() => selectedSpell!.level === 0 ? setStep('spell') : setStep('slot')}
            onProceed={proceedToConfirm}
            targetingInfo={targetingInfo}
            targetingLoading={targetingLoading}
          />
        ) : (
          <ConfirmStep
            spell={selectedSpell!}
            slotLevel={selectedSlotLevel}
            usePactSlot={usePactSlot}
            targets={participants.filter(p => selectedTargets.includes(p.id))}
            caster={caster}
            advantage={advantage}
            onAdvantageChange={setAdvantage}
            casting={casting}
            onCast={cast}
            onBack={() => {
              const noTargets = targetingInfo && !targetingInfo.selfOnly && !targetingInfo.canTargetSelf && !targetingInfo.canTargetAllies && !targetingInfo.canTargetEnemies;
              if (noTargets) {
                setStep(selectedSpell!.level === 0 ? 'spell' : 'slot');
              } else {
                setStep('target');
              }
            }}
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

function SpellSelectionStep({ cantrips, leveled, search, onSearch, onSelect, isMonster }: {
  cantrips: KnownSpell[];
  leveled: KnownSpell[];
  search: string;
  onSearch: (s: string) => void;
  onSelect: (spell: KnownSpell) => void;
  isMonster?: boolean;
}) {
  return (
    <>
      <Input
        label="Search spells"
        hideLabel
        type="text"
        value={search}
        onChange={e => onSearch(e.target.value)}
        placeholder="Search spells..."
        autoFocus
      />

      {cantrips.length === 0 && leveled.length === 0 && (
        <p className="font-body text-[15px] font-semibold text-faint text-center py-4">
          {isMonster ? 'Enter a spell name to cast' : 'No prepared spells found'}
        </p>
      )}

      {cantrips.length > 0 && (
        <div>
          <p className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1.5">Cantrips</p>
          <div className="space-y-1">
            {cantrips.map(s => (
              <SpellRow key={s.name} spell={s} onClick={() => onSelect(s)} />
            ))}
          </div>
        </div>
      )}

      {leveled.length > 0 && (
        <div>
          <p className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1.5">Spells</p>
          <div className="space-y-1">
            {leveled.map(s => (
              <SpellRow key={s.name + s.level} spell={s} onClick={() => onSelect(s)} />
            ))}
          </div>
        </div>
      )}
    </>
  );
}

function SpellRow({ spell, onClick }: { spell: KnownSpell; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="w-full flex items-center justify-between px-3 py-2 bg-page-alt hover:bg-rule text-left transition-colors group"
    >
      <div className="flex items-center gap-2">
        <Zap className="w-3.5 h-3.5 text-ink shrink-0" />
        <span className="font-body text-[15px] font-semibold text-ink">{spell.name}</span>
      </div>
      <div className="flex items-center gap-2">
        {spell.alwaysPrepared && <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-cls-monk">always</span>}
        {spell.atWill && <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-buff">at will</span>}
        <span className="font-body text-[13px] font-semibold text-faint">
          {spell.level === 0 ? 'Cantrip' : `Lvl ${spell.level}`}
        </span>
      </div>
    </button>
  );
}

function SlotSelectionStep({ spell, levels, onSelect, onBack }: {
  spell: KnownSpell;
  levels: { level: number; remaining: number; max: number; isPact: boolean }[];
  onSelect: (level: number, isPact: boolean) => void;
  onBack: () => void;
}) {
  return (
    <>
      <div className="flex items-center gap-2 mb-2">
        <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>
        <span className="font-heading text-[16px] font-semibold text-ink">{spell.name}</span>
        <span className="font-body text-[13px] font-semibold text-faint">— Select slot level</span>
      </div>

      {levels.length === 0 && (
        <p className="font-body text-[15px] font-semibold text-debuff text-center py-4">No spell slots available at level {spell.level} or above</p>
      )}

      <div className="space-y-1.5">
        {levels.map(l => (
          <button
            key={l.isPact ? `pact_${l.level}` : String(l.level)}
            type="button"
            onClick={() => l.remaining > 0 && onSelect(l.level, l.isPact)}
            disabled={l.remaining <= 0}
            className={`w-full flex items-center justify-between px-4 py-3 text-left transition-colors
              ${l.remaining > 0
                ? 'bg-page-alt hover:bg-rule border border-rule'
                : 'bg-page-alt/50 opacity-50 cursor-not-allowed border border-rule'
              }
              ${l.level === spell.level && !l.isPact ? 'border-2 border-ink' : ''}
            `}
          >
            <div>
              <span className="font-body text-[15px] font-semibold text-ink">
                Level {l.level}{l.isPact ? ' (Pact)' : ''}
              </span>
              {l.level > spell.level && (
                <span className="font-body text-[13px] font-semibold text-muted ml-2">Upcast +{l.level - spell.level}</span>
              )}
            </div>
            <div className="flex items-center gap-1.5">
              {Array.from({ length: l.max }, (_, i) => (
                <div key={i} className={`w-2 h-2 rounded-full ${i < l.remaining ? 'bg-ink' : 'bg-rule'}`} />
              ))}
              <span className="font-body text-[13px] font-semibold text-faint ml-1">{l.remaining}/{l.max}</span>
            </div>
          </button>
        ))}
      </div>
    </>
  );
}

function TargetSelectionStep({ spell, targets, caster, selectedTargets, onToggle, onBack, onProceed, targetingInfo, targetingLoading }: {
  spell: KnownSpell;
  targets: EncounterParticipant[];
  caster: EncounterParticipant;
  selectedTargets: string[];
  onToggle: (id: string) => void;
  onBack: () => void;
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
        <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>
        <span className="font-heading text-[16px] font-semibold text-ink">{spell.name}</span>
        <span className="font-body text-[13px] font-semibold text-faint">— {targetingLoading ? 'Loading...' : targetLabel}</span>
      </div>

      {targetingLoading ? (
        <p className="font-body text-[15px] font-semibold text-faint text-center py-4">Loading targeting info...</p>
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
                  ${isSelected ? 'bg-page-alt border-2 border-ink' :
                    disabled ? 'bg-page-alt/50 border border-rule opacity-50 cursor-not-allowed' :
                    'bg-page-alt border border-rule hover:bg-rule'}
                `}
              >
                <div className="flex items-center gap-2">
                  <Target className={`w-3.5 h-3.5 ${isSelected ? 'text-ink' : 'text-faint'}`} />
                  <span className={`font-body text-[15px] font-semibold ${isSelected ? 'text-ink' : 'text-muted'}`}>{p.displayName}</span>
                  {isSelf && <span className="font-body text-[13px] font-semibold text-faint">(self)</span>}
                </div>
                <div className="flex items-center gap-2 font-body text-[13px] font-semibold text-faint">
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

function ConfirmStep({ spell, slotLevel, usePactSlot, targets, caster, advantage, onAdvantageChange, casting, onCast, onBack, isMonster, overrideAttackBonus, overrideSaveDC, onOverrideAttackBonus, onOverrideSaveDC }: {
  spell: KnownSpell;
  slotLevel: number;
  usePactSlot: boolean;
  targets: EncounterParticipant[];
  caster: EncounterParticipant;
  advantage: boolean | null;
  onAdvantageChange: (v: boolean | null) => void;
  casting: boolean;
  onCast: () => void;
  onBack: () => void;
  isMonster?: boolean;
  overrideAttackBonus: string;
  overrideSaveDC: string;
  onOverrideAttackBonus: (v: string) => void;
  onOverrideSaveDC: (v: string) => void;
}) {
  const hasConcentration = !!caster.concentrationSpell;

  return (
    <>
      <div className="flex items-center gap-2 mb-3">
        <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>
        <span className="font-heading text-[16px] font-semibold text-ink">Confirm</span>
      </div>

      <div className="bg-page-alt p-4 space-y-2">
        <div className="flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-ink" />
          <span className="font-heading text-[16px] font-semibold text-ink">{spell.name}</span>
          <span className="font-body text-[13px] font-semibold text-faint">{spell.level === 0 ? 'Cantrip' : `Level ${spell.level}`}</span>
        </div>

        {slotLevel > 0 && (
          <p className="font-body text-[15px] font-semibold text-muted">
            Using level {slotLevel}{usePactSlot ? ' pact' : ''} slot
            {slotLevel > spell.level && <span className="text-muted ml-1">(upcast +{slotLevel - spell.level})</span>}
          </p>
        )}

        <p className="font-body text-[15px] font-semibold text-muted">
          <Shield className="w-3.5 h-3.5 inline mr-1" />
          Targeting: {targets.map(t => t.displayName).join(', ') || 'None'}
        </p>

        {hasConcentration && (
          <div className="flex items-start gap-2 bg-cls-monk/10 border border-cls-monk/30 p-2">
            <AlertTriangle className="w-4 h-4 text-cls-monk shrink-0 mt-0.5" />
            <p className="font-body text-[13px] font-semibold text-cls-monk">
              Will drop concentration on <strong>{caster.concentrationSpell}</strong>
            </p>
          </div>
        )}
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
              className={`flex-1 py-1.5 font-body text-[15px] font-semibold transition-colors ${
                advantage === val
                  ? 'bg-ink text-card'
                  : 'bg-page-alt text-muted hover:text-ink'
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

      <Button size="lg" className="w-full" onClick={onCast} loading={casting}>
        <Sparkles className="w-4 h-4" />
        {casting ? 'Casting...' : 'Cast Spell'}
      </Button>
    </>
  );
}

function ResultView({ result, onClose }: { result: CastSpellResponse; onClose: () => void }) {
  return (
    <div className="space-y-3">
      <div className="bg-page-alt border border-rule p-4">
        <p className="font-heading text-[16px] font-semibold text-ink mb-1 flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-ink" />
          {result.spellName}
          {result.slotLevelUsed > 0 && (
            <span className="font-body text-[13px] font-semibold text-muted">at level {result.slotLevelUsed}</span>
          )}
        </p>
        <p className="font-body text-[15px] font-semibold text-muted">{result.resultSummary}</p>
      </div>

      {!result.autoResolved && result.manualResolutionReason && (
        <div className="bg-cls-monk/10 border border-cls-monk/30 p-3">
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
              <div className="flex items-center gap-3 font-body text-[13px] font-semibold">
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
