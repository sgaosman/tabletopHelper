import { useState, useMemo, useCallback } from 'react';
import { Zap, Shield, Target, Sparkles, AlertTriangle } from 'lucide-react';
import { useEncounterAction, useSpellOptions } from '../../queries/encounters';
import { useFetchSpellTargeting } from '../../queries/reference';
import { apiErrorMessage } from '../../api/errors';
import type { EncounterParticipant, SpellSlot } from '../../types/encounter';
import type { CastSpellResponse, SpellOption, SpellSource } from '../../types/combat';
import type { SpellTargetingInfo } from '../../types/reference';
import { healthLabel } from '../../utils/healthStatus';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Modal from '../ui/Modal';

interface Props {
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  onClose: () => void;
  /** Offers a spell attack bonus and save DC typed by hand: always for the DM, for players while the campaign allows it (D164). */
  allowManualModifiers?: boolean;
}

type Step = 'spell' | 'slot' | 'target' | 'confirm';

/** How the chosen spell is paid for: a Spell Source, and the level that casts it at (D165). */
interface CastChoice {
  source: SpellSource;
  level: number;
}

/** A way to pay for the spell, as the source step shows it; one that is spent is shown but can't be picked. */
interface ChoiceRow extends CastChoice {
  key: string;
  label: string;
  available: boolean;
  left?: number;
  max?: number;
}

/**
 * Casts a spell in the live encounter of the surrounding EncounterProvider, whose state the result updates. The
 * server lists what the caster can cast now and what can pay for each spell, for player characters and monsters
 * alike, so only those are offered (D164, D165).
 */
export default function SpellCastModal({ caster, participants, onClose, allowManualModifiers = false }: Props) {
  const actions = useEncounterAction();
  const lookUpTargeting = useFetchSpellTargeting();
  const spellOptions = useSpellOptions(caster.id);
  const [step, setStep] = useState<Step>('spell');
  const [search, setSearch] = useState('');
  const [selectedSpell, setSelectedSpell] = useState<SpellOption | null>(null);
  const [choice, setChoice] = useState<CastChoice | null>(null);
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

  const filteredSpells = useMemo(() => {
    const q = search.toLowerCase();
    return (spellOptions.data ?? []).filter(s => s.name.toLowerCase().includes(q));
  }, [spellOptions.data, search]);

  const cantrips = filteredSpells.filter(s => s.level === 0);
  const leveled = filteredSpells.filter(s => s.level > 0);

  const choiceRows = useMemo(
    () => (selectedSpell ? rowsFor(selectedSpell, caster.spellSlotsCurrent ?? {}) : []),
    [selectedSpell, caster.spellSlotsCurrent],
  );

  function choose(spell: SpellOption, next: CastChoice) {
    setChoice(next);
    fetchTargeting(spell.name, next.level);
  }

  // A spell that only costs nothing, or only an innate use, has nothing to choose
  function selectSpell(spell: SpellOption) {
    setSelectedSpell(spell);
    setError('');
    const available = rowsFor(spell, caster.spellSlotsCurrent ?? {}).filter(row => row.available);
    if (available.length === 1 && (available[0].source === 'NONE' || available[0].source === 'INNATE')) {
      choose(spell, available[0]);
    } else {
      setStep('slot');
    }
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
    if (!selectedSpell || !choice) return;
    setCasting(true);
    setError('');

    try {
      const attackOverride = allowManualModifiers && overrideAttackBonus ? parseInt(overrideAttackBonus) : undefined;
      const saveDcOverride = allowManualModifiers && overrideSaveDC ? parseInt(overrideSaveDC) : undefined;
      // Player characters and monsters cast through the one endpoint, which spends the chosen source (D165)
      const response = await actions.castSpell({
        spellName: selectedSpell.name,
        spellSource: choice.source,
        slotLevel: choice.level,
        targetIds: selectedTargets,
        advantage,
        overrideSpellAttackBonus: attackOverride,
        overrideSpellSaveDC: saveDcOverride,
      }, caster.id);

      setResult(response);
    } catch (err) {
      setError(apiErrorMessage(err));
      setCasting(false);
    }
  }

  // Back from targeting or confirm: to the source step when there was a choice, else to the spells
  function backToSource() {
    const available = choiceRows.filter(row => row.available);
    const skipped = available.length === 1 && (available[0].source === 'NONE' || available[0].source === 'INNATE');
    setStep(skipped ? 'spell' : 'slot');
  }

  // The dead are out of reach, but the dying and stable can still be healed or harmed (D161)
  const otherParticipants = participants.filter(p => p.id !== caster.id && p.lifeState !== 'DEAD');

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
          <ResultView result={result} onClose={onClose} />
        ) : step === 'spell' ? (
          <SpellSelectionStep
            cantrips={cantrips}
            leveled={leveled}
            search={search}
            onSearch={setSearch}
            onSelect={selectSpell}
            loading={spellOptions.isPending}
            loadError={spellOptions.isError ? apiErrorMessage(spellOptions.error) : null}
          />
        ) : step === 'slot' ? (
          <SourceSelectionStep
            spell={selectedSpell!}
            rows={choiceRows}
            onSelect={row => choose(selectedSpell!, row)}
            onBack={() => setStep('spell')}
          />
        ) : step === 'target' ? (
          <TargetSelectionStep
            spell={selectedSpell!}
            targets={otherParticipants}
            caster={caster}
            selectedTargets={selectedTargets}
            onToggle={toggleTarget}
            onBack={backToSource}
            onProceed={proceedToConfirm}
            targetingInfo={targetingInfo}
            targetingLoading={targetingLoading}
          />
        ) : (
          <ConfirmStep
            spell={selectedSpell!}
            choice={choice!}
            choiceRow={choiceRows.find(row => row.source === choice!.source && row.level === choice!.level)}
            targets={participants.filter(p => selectedTargets.includes(p.id))}
            caster={caster}
            advantage={advantage}
            onAdvantageChange={setAdvantage}
            casting={casting}
            onCast={cast}
            onBack={() => {
              const noTargets = targetingInfo && !targetingInfo.selfOnly && !targetingInfo.canTargetSelf && !targetingInfo.canTargetAllies && !targetingInfo.canTargetEnemies;
              if (noTargets) {
                backToSource();
              } else {
                setStep('target');
              }
            }}
            allowManualModifiers={allowManualModifiers}
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

/**
 * Each way to pay for the spell: its slot levels from its own up, with the slots each has left (spent ones too,
 * to show why they can't be picked), the pact slot, an innate use, or nothing.
 */
function rowsFor(spell: SpellOption, slots: Record<string, SpellSlot>): ChoiceRow[] {
  const rows: ChoiceRow[] = [];
  for (const option of spell.sources) {
    if (option.source === 'SLOT') {
      const levels = Object.entries(slots)
        .filter(([key, slot]) => !key.startsWith('pact_') && parseInt(key) >= spell.level && slot.max > 0)
        .sort((a, b) => parseInt(a[0]) - parseInt(b[0]));
      for (const [key, slot] of levels) {
        const level = parseInt(key);
        rows.push({
          key, source: 'SLOT', level, label: `Level ${level}`,
          available: option.levels.includes(level), left: slot.max - slot.used, max: slot.max,
        });
      }
    } else if (option.source === 'PACT_SLOT') {
      const pactKey = Object.keys(slots).find(key => key.startsWith('pact_'));
      const level = option.levels[0] ?? (pactKey ? parseInt(pactKey.replace('pact_', '')) : spell.level);
      rows.push({
        key: `pact_${level}`, source: 'PACT_SLOT', level, label: `Level ${level} (Pact)`,
        available: option.levels.length > 0, left: option.usesLeft ?? 0, max: option.usesMax ?? 0,
      });
    } else if (option.source === 'INNATE') {
      rows.push({
        key: 'innate', source: 'INNATE', level: spell.level, label: 'Innate use',
        available: option.levels.length > 0, left: option.usesLeft ?? 0, max: option.usesMax ?? 0,
      });
    } else {
      rows.push({
        key: 'none', source: 'NONE', level: spell.level, label: spell.level === 0 ? 'Cantrip' : 'At will',
        available: true,
      });
    }
  }
  return rows;
}

function SpellSelectionStep({ cantrips, leveled, search, onSearch, onSelect, loading, loadError }: {
  cantrips: SpellOption[];
  leveled: SpellOption[];
  search: string;
  onSearch: (s: string) => void;
  onSelect: (spell: SpellOption) => void;
  loading: boolean;
  loadError: string | null;
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

      {loadError && (
        <p role="alert" className="font-body text-[15px] font-semibold text-debuff text-center py-4">{loadError}</p>
      )}

      {loading && (
        <p className="font-body text-[15px] font-semibold text-faint text-center py-4">Loading spells...</p>
      )}

      {!loading && !loadError && cantrips.length === 0 && leveled.length === 0 && (
        <p className="font-body text-[15px] font-semibold text-faint text-center py-4">No spells to cast</p>
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
              <SpellRow key={s.name} spell={s} onClick={() => onSelect(s)} />
            ))}
          </div>
        </div>
      )}
    </>
  );
}

// A spell whose every source is spent stays listed, so the caster can see why it can't be cast
function SpellRow({ spell, onClick }: { spell: SpellOption; onClick: () => void }) {
  const castable = spell.sources.some(s => s.levels.length > 0);
  const atWill = spell.level > 0 && spell.sources.some(s => s.source === 'NONE');
  const innate = spell.sources.find(s => s.source === 'INNATE');
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={!castable}
      className={`w-full flex items-center justify-between px-3 py-2 text-left transition-colors group ${
        castable ? 'bg-page-alt hover:bg-rule' : 'bg-page-alt/50 opacity-50 cursor-not-allowed'
      }`}
    >
      <div className="flex items-center gap-2">
        <Zap className="w-3.5 h-3.5 text-ink shrink-0" />
        <span className="font-body text-[15px] font-semibold text-ink">{spell.name}</span>
      </div>
      <div className="flex items-center gap-2">
        {atWill && <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-buff">at will</span>}
        {innate && (
          <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-cls-monk">
            innate {innate.usesLeft ?? 0}/{innate.usesMax ?? 0}
          </span>
        )}
        <span className="font-body text-[13px] font-semibold text-faint">
          {spell.level === 0 ? 'Cantrip' : `Lvl ${spell.level}`}
        </span>
      </div>
    </button>
  );
}

function SourceSelectionStep({ spell, rows, onSelect, onBack }: {
  spell: SpellOption;
  rows: ChoiceRow[];
  onSelect: (row: ChoiceRow) => void;
  onBack: () => void;
}) {
  return (
    <>
      <div className="flex items-center gap-2 mb-2">
        <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>
        <span className="font-heading text-[16px] font-semibold text-ink">{spell.name}</span>
        <span className="font-body text-[13px] font-semibold text-faint">— Choose how to cast it</span>
      </div>

      {rows.every(row => !row.available) && (
        <p className="font-body text-[15px] font-semibold text-debuff text-center py-4">Nothing left to cast {spell.name} with</p>
      )}

      <div className="space-y-1.5">
        {rows.map(row => (
          <button
            key={row.key}
            type="button"
            onClick={() => row.available && onSelect(row)}
            disabled={!row.available}
            className={`w-full flex items-center justify-between px-4 py-3 text-left transition-colors
              ${row.available
                ? 'bg-page-alt hover:bg-rule border border-rule'
                : 'bg-page-alt/50 opacity-50 cursor-not-allowed border border-rule'
              }
              ${row.source === 'SLOT' && row.level === spell.level ? 'border-2 border-ink' : ''}
            `}
          >
            <div>
              <span className="font-body text-[15px] font-semibold text-ink">{row.label}</span>
              {row.level > spell.level && (
                <span className="font-body text-[13px] font-semibold text-muted ml-2">Upcast +{row.level - spell.level}</span>
              )}
            </div>
            {row.max !== undefined && row.left !== undefined && (
              <div className="flex items-center gap-1.5">
                {Array.from({ length: row.max }, (_, i) => (
                  <div key={i} className={`w-2 h-2 rounded-full ${i < row.left! ? 'bg-ink' : 'bg-rule'}`} />
                ))}
                <span className="font-body text-[13px] font-semibold text-faint ml-1">{row.left}/{row.max}</span>
              </div>
            )}
          </button>
        ))}
      </div>
    </>
  );
}

function TargetSelectionStep({ spell, targets, caster, selectedTargets, onToggle, onBack, onProceed, targetingInfo, targetingLoading }: {
  spell: SpellOption;
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

// What the chosen source spends, as the confirm step words it
function sourceText(choice: CastChoice, spell: SpellOption, row: ChoiceRow | undefined): string | null {
  switch (choice.source) {
    case 'SLOT':
      return `Using level ${choice.level} slot`;
    case 'PACT_SLOT':
      return `Using level ${choice.level} pact slot`;
    case 'INNATE':
      return `Using an innate use (${row?.left ?? 0}/${row?.max ?? 0} left)`;
    default:
      return spell.level > 0 ? 'Cast at will' : null;
  }
}

function ConfirmStep({ spell, choice, choiceRow, targets, caster, advantage, onAdvantageChange, casting, onCast, onBack, allowManualModifiers, overrideAttackBonus, overrideSaveDC, onOverrideAttackBonus, onOverrideSaveDC }: {
  spell: SpellOption;
  choice: CastChoice;
  choiceRow: ChoiceRow | undefined;
  targets: EncounterParticipant[];
  caster: EncounterParticipant;
  advantage: boolean | null;
  onAdvantageChange: (v: boolean | null) => void;
  casting: boolean;
  onCast: () => void;
  onBack: () => void;
  allowManualModifiers: boolean;
  overrideAttackBonus: string;
  overrideSaveDC: string;
  onOverrideAttackBonus: (v: string) => void;
  onOverrideSaveDC: (v: string) => void;
}) {
  const hasConcentration = !!caster.concentrationSpell;
  const paidWith = sourceText(choice, spell, choiceRow);

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

        {paidWith && (
          <p className="font-body text-[15px] font-semibold text-muted">
            {paidWith}
            {choice.level > spell.level && <span className="text-muted ml-1">(upcast +{choice.level - spell.level})</span>}
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

      {allowManualModifiers && (
        <div className="space-y-1">
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
          <p className="font-body text-[13px] font-semibold text-faint">
            Manual Modifiers: leave blank to use the caster&apos;s own. Any you enter is noted in the combat log.
          </p>
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
