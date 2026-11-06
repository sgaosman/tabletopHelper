import { useState, useMemo } from 'react';
import { Zap, Sparkles } from 'lucide-react';
import { useEncounterAction, useSpellOptions } from '../../queries/encounters';
import { apiErrorMessage } from '../../api/errors';
import type { EncounterParticipant, SpellSlot } from '../../types/encounter';
import type { SpellOption, SpellSource } from '../../types/combat';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Modal from '../ui/Modal';
import useSpellFlow from './spellflow/useSpellFlow';
import TargetStep from './spellflow/TargetStep';
import ConfirmStep from './spellflow/ConfirmStep';
import ResultView from './spellflow/ResultView';

interface Props {
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  onClose: () => void;
  /** Offers a spell attack bonus and save DC typed by hand: always for the DM, for players while the campaign allows it (D164). */
  allowManualModifiers?: boolean;
}

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
 * alike, so only those are offered (D164, D165). Its own steps choose the spell and how to pay for it; the
 * targets, confirm and result are the steps every spell pop-up shares.
 */
export default function SpellCastModal({ caster, participants, onClose, allowManualModifiers = false }: Props) {
  const actions = useEncounterAction();
  const spellOptions = useSpellOptions(caster.id);
  const flow = useSpellFlow<'spell' | 'slot'>('spell', { allowManualModifiers });
  const [search, setSearch] = useState('');
  const [selectedSpell, setSelectedSpell] = useState<SpellOption | null>(null);
  const [choice, setChoice] = useState<CastChoice | null>(null);

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
    flow.lookUpTargeting(spell.name, next.level);
  }

  // A spell that only costs nothing, or only an innate use, has nothing to choose
  function selectSpell(spell: SpellOption) {
    setSelectedSpell(spell);
    flow.setError('');
    const available = rowsFor(spell, caster.spellSlotsCurrent ?? {}).filter(row => row.available);
    if (available.length === 1 && (available[0].source === 'NONE' || available[0].source === 'INNATE')) {
      choose(spell, available[0]);
    } else {
      flow.setStep('slot');
    }
  }

  function cast() {
    if (!selectedSpell || !choice) return;
    // Player characters and monsters cast through the one endpoint, which spends the chosen source (D165)
    flow.submit(() => actions.castSpell({
      spellName: selectedSpell.name,
      spellSource: choice.source,
      slotLevel: choice.level,
      targetIds: flow.selectedTargets,
      advantage: flow.advantage,
      ...flow.overrides(),
    }, caster.id));
  }

  // Back from targeting or confirm: to the source step when there was a choice, else to the spells
  function backToSource() {
    const available = choiceRows.filter(row => row.available);
    const skipped = available.length === 1 && (available[0].source === 'NONE' || available[0].source === 'INNATE');
    flow.setStep(skipped ? 'spell' : 'slot');
  }

  return (
    <Modal
      title={<><Sparkles className="w-5 h-5 text-ink" />Cast Spell</>}
      subtitle={caster.displayName}
      size="lg"
      onClose={onClose}
    >
      <div className="space-y-3">
        {flow.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{flow.error}</div>
        )}

        {flow.result ? (
          <ResultView result={flow.result} onClose={onClose} />
        ) : flow.step === 'spell' ? (
          <SpellSelectionStep
            cantrips={cantrips}
            leveled={leveled}
            search={search}
            onSearch={setSearch}
            onSelect={selectSpell}
            loading={spellOptions.isPending}
            loadError={spellOptions.isError ? apiErrorMessage(spellOptions.error) : null}
          />
        ) : flow.step === 'slot' ? (
          <SourceSelectionStep
            spell={selectedSpell!}
            rows={choiceRows}
            onSelect={row => choose(selectedSpell!, row)}
            onBack={() => flow.setStep('spell')}
          />
        ) : flow.step === 'target' ? (
          <TargetStep
            spellName={selectedSpell!.name}
            caster={caster}
            participants={participants}
            selectedTargets={flow.selectedTargets}
            onToggle={flow.toggleTarget}
            targetingInfo={flow.targetingInfo}
            targetingLoading={flow.targetingLoading}
            onProceed={() => flow.setStep('confirm')}
            onBack={backToSource}
          />
        ) : (
          <ConfirmStep
            spellName={selectedSpell!.name}
            levelLabel={selectedSpell!.level === 0 ? 'Cantrip' : `Level ${selectedSpell!.level}`}
            cost={costLine(choice!, selectedSpell!, choiceRows)}
            caster={caster}
            targets={participants.filter(p => flow.selectedTargets.includes(p.id))}
            warnConcentration
            advantage={flow.advantage}
            onAdvantageChange={flow.setAdvantage}
            allowManualModifiers={allowManualModifiers}
            overrideAttackBonus={flow.overrideAttackBonus}
            overrideSaveDC={flow.overrideSaveDC}
            onOverrideAttackBonus={flow.setOverrideAttackBonus}
            onOverrideSaveDC={flow.setOverrideSaveDC}
            submitLabel="Cast Spell"
            busyLabel="Casting..."
            submitIcon={Sparkles}
            submitting={flow.submitting}
            onSubmit={cast}
            onBack={() => (flow.takesNoTargets ? backToSource() : flow.setStep('target'))}
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

// The confirm step's cost line, noting an upcast; a cantrip cast for nothing has none
function costLine(choice: CastChoice, spell: SpellOption, rows: ChoiceRow[]) {
  const paidWith = sourceText(choice, spell, rows.find(row => row.source === choice.source && row.level === choice.level));
  if (!paidWith) return undefined;
  return (
    <>
      {paidWith}
      {choice.level > spell.level && <span className="text-muted ml-1">(upcast +{choice.level - spell.level})</span>}
    </>
  );
}
