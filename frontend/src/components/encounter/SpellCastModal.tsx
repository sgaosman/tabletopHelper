import { useState, useMemo } from 'react';
import { Zap, Sparkles } from 'lucide-react';
import { useEncounterAction, useSpellOptions, useSummonOptions } from '../../queries/encounters';
import { useLiveEncounter } from '../../context/liveEncounter';
import { apiErrorMessage } from '../../api/errors';
import type { EncounterParticipant, SpellSlot } from '../../types/encounter';
import type { MetamagicChoice, MetamagicName, SpellOption, SpellSource } from '../../types/combat';
import { spellCastBlock, type CastingTime } from '../../utils/turnEconomy';
import { chosenCost, metamagicBlock, slotsLeft, slotTotal, sorceryPoints } from '../../utils/metamagic';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Modal from '../ui/Modal';
import useSpellFlow from './spellflow/useSpellFlow';
import TargetStep from './spellflow/TargetStep';
import ConfirmStep from './spellflow/ConfirmStep';
import ResultView from './spellflow/ResultView';
import CastingTimeBadge from './spellflow/CastingTimeBadge';
import MetamagicStep, { MetamagicTargets } from './spellflow/MetamagicStep';
import SummonStep from './spellflow/SummonStep';
import useSummonDraft from './spellflow/useSummonDraft';
import { draftProblem, isSummoningSpell, summonSummary, toSummonChoice } from '../../utils/summons';
import { mysticArcanumTitle } from '../../utils/mysticArcanum';
import FormStep, { type FormChoice } from './spellflow/FormStep';
import { formSubjects, isFormSpell } from '../../utils/formSpells';
import { useAuth } from '../../hooks/useAuth';
import { consentTargets } from '../../utils/spellConsent';
import ConsentChoices from './spellflow/ConsentChoices';

interface Props {
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  onClose: () => void;
  /** Offers a spell attack bonus and save DC typed by hand: always for the DM, for players while the campaign allows it (D164). */
  allowManualModifiers?: boolean;
  /**
   * The DM casts: the server never refuses the DM over the action economy, so a spell the turn can't pay for stays
   * castable with the reason as a warning, and one longer than a turn costs an action (D174).
   */
  asDm?: boolean;
  /** Only spells with these casting times, such as the reaction spells the Reaction menu offers (D174). */
  castingTimes?: CastingTime[];
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
 * alike, so only those are offered (D164, D165). Each shows its casting time, and one the caster's turn can't pay
 * for is greyed out for a player with the reason, worked out from the caster's turn economy; the server stays the
 * authority and its refusal shows too (D174). Its own steps choose the spell and how to pay for it; the targets,
 * confirm and result are the steps every spell pop-up shares. A sorcerer who knows Metamagic (PHB p.102, M18) chooses
 * it after how to pay, each option showing its cost and why it can't change the spell; Quickened Spell makes the cast a
 * bonus action, Twinned Spell allows a second target, and Careful and Heightened Spell choose their creatures among the
 * targets on the confirm step. A summoning spell (D182) chooses its creatures in the summon step after how to pay, from
 * the options the server lists at that slot level, and the cast carries the choice. A form spell (D192) chooses each
 * creature's form in the form step after the targets (the caster's own for Shapechange), and the cast carries them.
 */
export default function SpellCastModal({
  caster, participants, onClose, allowManualModifiers = false, asDm = false, castingTimes,
}: Props) {
  const actions = useEncounterAction();
  const spellOptions = useSpellOptions(caster.id);
  const flow = useSpellFlow<'spell' | 'slot' | 'metamagic' | 'summon' | 'form'>('spell', { allowManualModifiers });
  const { encounterId } = useLiveEncounter();
  const [search, setSearch] = useState('');
  const [selectedSpell, setSelectedSpell] = useState<SpellOption | null>(null);
  const [choice, setChoice] = useState<CastChoice | null>(null);
  const [metamagic, setMetamagic] = useState<MetamagicName[]>([]);
  // The option a spell with options is cast with, such as Enlarge or Reduce (D184)
  const [castOption, setCastOption] = useState<string | null>(null);
  const [careful, setCareful] = useState<string[]>([]);
  const [heightened, setHeightened] = useState<string | null>(null);
  const points = sorceryPoints(caster.resourcePoolsCurrent)?.currentUses ?? 0;
  // Metamagic needs the options the server lists for the spell and the sorcery points to pay for them
  const offersMetamagic = (spell: SpellOption) => (spell.metamagic?.length ?? 0) > 0
    && sorceryPoints(caster.resourcePoolsCurrent) !== null;
  const quickened = metamagic.includes('QUICKENED');
  // A summoning spell's options at the chosen slot level, and the caster's choice among them (D182)
  const summoning = isSummoningSpell(selectedSpell?.name) && choice !== null;
  const summonOptions = useSummonOptions(encounterId, caster.id, summoning ? selectedSpell?.name : undefined,
    choice?.level ?? null);
  const [summonDraft, setSummonDraft] = useSummonDraft(summoning ? summonOptions.data : undefined);
  // A form spell's creatures and the form each takes, by participant ID (D192)
  const formSpell = isFormSpell(selectedSpell?.name);
  const [forms, setForms] = useState<Record<string, FormChoice>>({});
  const subjects = formSpell && selectedSpell
    ? formSubjects(selectedSpell.name, caster, flow.selectedTargets, participants) : [];
  const formsMissing = subjects.some(s => !forms[s.id]);
  // Whether this cast affects each target whose trait lets it choose, as a rakshasa's Limited Magic Immunity (D198),
  // chosen by whoever controls it; a target left out takes its standing choice
  const { user } = useAuth();
  const [consents, setConsents] = useState<Record<string, boolean>>({});
  const choosing = consentTargets(participants.filter(p => flow.selectedTargets.includes(p.id)), user?.userId, asDm);

  const filteredSpells = useMemo(() => {
    const q = search.toLowerCase();
    return (spellOptions.data ?? []).filter(s => s.name.toLowerCase().includes(q)
      && (!castingTimes || castingTimes.includes(s.castingTime)));
  }, [spellOptions.data, search, castingTimes]);

  // Why the caster's turn can't pay for a spell now, if it can't (D174); a spell Quickened Spell could cast as a bonus
  // action instead isn't blocked by a spent action (PHB p.102)
  const blockFor = (spell: SpellOption) => {
    const blocked = spellCastBlock(caster, spell.castingTime, spell.level, { dm: asDm });
    if (!blocked) return null;
    const quicken = spell.metamagic?.find(option => option.option === 'QUICKENED');
    const canQuicken = quicken && !metamagicBlock(quicken, spell.level) && points >= quicken.cost
      && spellCastBlock(caster, 'BONUS_ACTION', spell.level, { dm: asDm }) === null;
    return canQuicken ? null : blocked;
  };
  // The turn's block for the cast as chosen, a bonus action with Quickened Spell
  const castBlock = (spell: SpellOption) => quickened
    ? spellCastBlock(caster, 'BONUS_ACTION', spell.level, { dm: asDm })
    : spellCastBlock(caster, spell.castingTime, spell.level, { dm: asDm });

  const cantrips = filteredSpells.filter(s => s.level === 0);
  const leveled = filteredSpells.filter(s => s.level > 0);

  const choiceRows = useMemo(
    () => (selectedSpell ? rowsFor(selectedSpell, caster.spellSlotsCurrent ?? {}) : []),
    [selectedSpell, caster.spellSlotsCurrent],
  );

  function choose(spell: SpellOption, next: CastChoice) {
    setChoice(next);
    setMetamagic([]);
    setCareful([]);
    setHeightened(null);
    flow.setExtraTargets(0);
    if (offersMetamagic(spell)) {
      flow.setStep('metamagic');
    } else {
      afterSource(spell, next.level);
    }
  }

  // On to the creatures for a summoning spell, else to the targets
  function afterSource(spell: SpellOption, level: number) {
    if (isSummoningSpell(spell.name)) {
      flow.setStep('summon');
    } else {
      flow.lookUpTargeting(spell.name, level);
    }
  }

  function toggleMetamagic(option: MetamagicName) {
    setMetamagic(chosen => chosen.includes(option) ? chosen.filter(o => o !== option) : [...chosen, option]);
  }

  // On from Metamagic to the targets, with Twinned Spell's second creature allowed
  function afterMetamagic() {
    if (!selectedSpell || !choice) return;
    flow.setExtraTargets(metamagic.includes('TWINNED') ? 1 : 0);
    afterSource(selectedSpell, choice.level);
  }

  // The Metamagic the request carries, with the creatures Careful and Heightened Spell name among the targets
  function metamagicRequest(): MetamagicChoice[] | undefined {
    if (metamagic.length === 0) return undefined;
    return metamagic.map(option => {
      if (option === 'CAREFUL') {
        return { option, targetIds: careful.filter(id => flow.selectedTargets.includes(id)) };
      }
      if (option === 'HEIGHTENED') {
        return { option, targetIds: heightened && flow.selectedTargets.includes(heightened) ? [heightened] : [] };
      }
      return { option };
    });
  }

  // A spell that only costs nothing, or only an innate use or a Mystic Arcanum, has nothing to choose
  function selectSpell(spell: SpellOption) {
    setSelectedSpell(spell);
    setCastOption(spell.castOptions?.[0] ?? null);
    setForms({});
    flow.setError('');
    const available = rowsFor(spell, caster.spellSlotsCurrent ?? {}).filter(row => row.available);
    if (nothingToChoose(available)) {
      choose(spell, available[0]);
    } else {
      flow.setStep('slot');
    }
  }

  function cast() {
    if (!selectedSpell || !choice || formsMissing) return;
    // Player characters and monsters cast through the one endpoint, which spends the chosen source (D165)
    flow.submit(() => actions.castSpell({
      spellName: selectedSpell.name,
      spellSource: choice.source,
      slotLevel: choice.level,
      targetIds: flow.selectedTargets,
      advantage: flow.advantage,
      metamagic: metamagicRequest(),
      option: castOption ?? undefined,
      summon: summoning && summonOptions.data && summonDraft
        ? toSummonChoice(summonOptions.data, summonDraft) : undefined,
      forms: formSpell
        ? Object.fromEntries(subjects.map(s => [s.id, forms[s.id].monsterId])) : undefined,
      spellConsents: consentRequest(),
      ...flow.overrides(),
    }, caster.id));
  }

  // The choices made for the cast's targets still chosen, or nothing when none was made
  function consentRequest() {
    const made = Object.entries(consents).filter(([id]) => choosing.some(target => target.id === id));
    return made.length > 0 ? Object.fromEntries(made) : undefined;
  }

  // Back from targeting or confirm: to the creatures of a summoning spell, else to Metamagic when it was offered, else
  // to the source step when there was a choice, else to the spells
  function backToSource() {
    if (selectedSpell && isSummoningSpell(selectedSpell.name)) {
      flow.setStep('summon');
      return;
    }
    backBeforeSummon();
  }

  function backBeforeSummon() {
    if (selectedSpell && offersMetamagic(selectedSpell)) {
      flow.setStep('metamagic');
      return;
    }
    backBeforeMetamagic();
  }

  function backBeforeMetamagic() {
    const available = choiceRows.filter(row => row.available);
    const skipped = nothingToChoose(available);
    flow.setStep(skipped ? 'spell' : 'slot');
  }

  return (
    <Modal
      title={<><Sparkles className="w-5 h-5 text-ink" />{castingTimes?.length === 1 && castingTimes[0] === 'REACTION' ? 'Cast a Reaction Spell' : 'Cast Spell'}</>}
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
            blockFor={blockFor}
            asDm={asDm}
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
        ) : flow.step === 'metamagic' ? (
          <MetamagicStep
            spellName={selectedSpell!.name}
            castLevel={choice!.level}
            options={selectedSpell!.metamagic ?? []}
            points={points}
            chosen={metamagic}
            onToggle={toggleMetamagic}
            onContinue={afterMetamagic}
            onBack={backBeforeMetamagic}
          />
        ) : flow.step === 'summon' ? (
          summonOptions.isError ? (
            <>
              <p role="alert" className="font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(summonOptions.error)}</p>
              <Button variant="ghost" size="sm" onClick={backBeforeSummon}>&larr; Back</Button>
            </>
          ) : summonOptions.data && summonDraft ? (
            <SummonStep
              options={summonOptions.data}
              draft={summonDraft}
              onChange={setSummonDraft}
              onBack={backBeforeSummon}
              onContinue={() => {
                if (draftProblem(summonOptions.data, summonDraft)) return;
                // A summoning targets no creature: its creatures were just chosen
                flow.skipTargets();
              }}
            />
          ) : (
            <p className="font-body text-[15px] font-semibold text-faint text-center py-4">Loading creatures...</p>
          )
        ) : flow.step === 'target' ? (
          <TargetStep
            spellName={selectedSpell!.name}
            caster={caster}
            participants={participants}
            selectedTargets={flow.selectedTargets}
            onToggle={flow.toggleTarget}
            targetingInfo={flow.targetingInfo}
            targetingLoading={flow.targetingLoading}
            onProceed={() => flow.setStep(formSpell ? 'form' : 'confirm')}
            onBack={backToSource}
          />
        ) : flow.step === 'form' ? (
          <FormStep
            spellName={selectedSpell!.name}
            caster={caster}
            subjects={subjects}
            forms={forms}
            onChoose={(id, form) => setForms(chosen => {
              const next = { ...chosen };
              if (form) next[id] = form;
              else delete next[id];
              return next;
            })}
            onContinue={() => flow.setStep('confirm')}
            onBack={() => (flow.takesNoTargets ? backToSource() : flow.setStep('target'))}
          />
        ) : (
          <>
          <MetamagicTargets
            chosen={metamagic}
            targets={participants.filter(p => flow.selectedTargets.includes(p.id))}
            careful={careful}
            heightened={heightened}
            onCareful={setCareful}
            onHeightened={setHeightened}
          />
          <ConsentChoices targets={choosing} spellName={selectedSpell!.name} chosen={consents}
            onChange={setConsents} />
          {summoning && summonOptions.data && summonDraft && (
            <p className="font-body text-[15px] font-semibold text-ink">
              Summons: {summonSummary(summonOptions.data, summonDraft)}
            </p>
          )}
          {formSpell && subjects.length > 0 && (
            <p className="font-body text-[15px] font-semibold text-ink">
              Forms: {subjects.map(s => `${s.displayName} as ${forms[s.id]?.name ?? '?'}`).join(', ')}
            </p>
          )}
          <ConfirmStep
            spellName={selectedSpell!.name}
            levelLabel={selectedSpell!.level === 0 ? 'Cantrip' : `Level ${selectedSpell!.level}`}
            castingTime={quickened ? 'BONUS_ACTION' : selectedSpell!.castingTime}
            reactionTrigger={selectedSpell!.reactionTrigger}
            economyWarning={overBudget(castBlock(selectedSpell!), asDm)}
            cost={costLine(choice!, selectedSpell!, choiceRows, metamagic,
              chosenCost(selectedSpell!.metamagic ?? [], metamagic, choice!.level))}
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
            submitDisabled={formsMissing}
            onBack={() => (formSpell ? flow.setStep('form')
              : flow.takesNoTargets ? backToSource() : flow.setStep('target'))}
            castOptions={selectedSpell!.castOptions}
            option={castOption}
            onOptionChange={setCastOption}
            duplicatesUpTo={selectedSpell!.duplicatesUpTo}
          />
          </>
        )}
      </div>
    </Modal>
  );
}

// Only one way to pay, and it is nothing, an innate use or a Mystic Arcanum: a source step would have nothing to choose
function nothingToChoose(available: ChoiceRow[]): boolean {
  return available.length === 1 && ['NONE', 'INNATE', 'MYSTIC_ARCANUM'].includes(available[0].source);
}

// The DM goes over the action economy rather than being refused, so the reason reads as a warning (D174)
function overBudget(blocked: string | null, asDm: boolean) {
  return blocked && asDm ? `Over the action economy: ${blocked}` : blocked;
}

/**
 * Each way to pay for the spell: its slot levels from its own up, with the slots each has left (spent ones too,
 * to show why they can't be picked), the pact slot, an innate use, a warlock's Mystic Arcanum, or nothing.
 */
function rowsFor(spell: SpellOption, slots: Record<string, SpellSlot>): ChoiceRow[] {
  const rows: ChoiceRow[] = [];
  for (const option of spell.sources) {
    if (option.source === 'SLOT') {
      const levels = Object.entries(slots)
        .filter(([key, slot]) => !key.startsWith('pact_') && parseInt(key) >= spell.level && slotTotal(slot) > 0)
        .sort((a, b) => parseInt(a[0]) - parseInt(b[0]));
      for (const [key, slot] of levels) {
        const level = parseInt(key);
        rows.push({
          key, source: 'SLOT', level, label: `Level ${level}`,
          available: option.levels.includes(level), left: slotsLeft(slot), max: slotTotal(slot),
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
    } else if (option.source === 'MYSTIC_ARCANUM') {
      // A warlock's arcanum casts the spell at its own level without a slot, once between long rests (PHB p.108)
      rows.push({
        key: 'arcanum', source: 'MYSTIC_ARCANUM', level: spell.level, label: mysticArcanumTitle(spell.level),
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

function SpellSelectionStep({ cantrips, leveled, search, onSearch, onSelect, blockFor, asDm, loading, loadError }: {
  cantrips: SpellOption[];
  leveled: SpellOption[];
  search: string;
  onSearch: (s: string) => void;
  onSelect: (spell: SpellOption) => void;
  blockFor: (spell: SpellOption) => string | null;
  asDm: boolean;
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
              <SpellRow key={s.name} spell={s} blocked={blockFor(s)} asDm={asDm} onClick={() => onSelect(s)} />
            ))}
          </div>
        </div>
      )}

      {leveled.length > 0 && (
        <div>
          <p className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1.5">Spells</p>
          <div className="space-y-1">
            {leveled.map(s => (
              <SpellRow key={s.name} spell={s} blocked={blockFor(s)} asDm={asDm} onClick={() => onSelect(s)} />
            ))}
          </div>
        </div>
      )}
    </>
  );
}

/**
 * A spell to pick. One whose every source is spent stays listed, so the caster can see why it can't be cast, and so
 * does one the caster's turn can't pay for, with the reason: greyed out for a player, a warning for the DM (D174).
 * One the server says the caster can't cast at all, such as in armour it isn't proficient with (PHB p.144), is greyed
 * out for the DM too, with the server's reason.
 */
function SpellRow({ spell, blocked, asDm, onClick }: {
  spell: SpellOption;
  blocked: string | null;
  asDm: boolean;
  onClick: () => void;
}) {
  const refused = spell.unavailableReason ?? null;
  const castable = !refused && spell.sources.some(s => s.levels.length > 0) && (asDm || !blocked);
  const atWill = spell.level > 0 && spell.sources.some(s => s.source === 'NONE');
  const innate = spell.sources.find(s => s.source === 'INNATE');
  const arcanum = spell.sources.find(s => s.source === 'MYSTIC_ARCANUM');
  const reasonId = `spell-block-${spell.name.replace(/[^A-Za-z0-9]+/g, '-')}`;
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={!castable}
      aria-describedby={refused || blocked ? reasonId : undefined}
      className={`w-full flex items-center justify-between px-3 py-2 text-left transition-colors group ${
        castable ? 'bg-page-alt hover:bg-rule' : 'bg-page-alt/50 opacity-50 cursor-not-allowed'
      }`}
    >
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <Zap className="w-3.5 h-3.5 text-ink shrink-0" />
          <span className="font-body text-[15px] font-semibold text-ink">{spell.name}</span>
        </div>
        {refused ? (
          <p id={reasonId} className="font-body text-[12.5px] font-semibold ml-5 text-debuff">{refused}</p>
        ) : blocked && (
          <p id={reasonId} className={`font-body text-[12.5px] font-semibold ml-5 ${asDm ? 'text-cls-fighter' : 'text-debuff'}`}>
            {overBudget(blocked, asDm)}
          </p>
        )}
      </div>
      <div className="flex items-center gap-2">
        <CastingTimeBadge castingTime={spell.castingTime} />
        {atWill && <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-buff">at will</span>}
        {innate && (
          <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-cls-monk">
            innate {innate.usesLeft ?? 0}/{innate.usesMax ?? 0}
          </span>
        )}
        {arcanum && (
          <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-cls-warlock">
            arcanum {arcanum.usesLeft ?? 0}/{arcanum.usesMax ?? 0}
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
    case 'MYSTIC_ARCANUM':
      return `Using your ${mysticArcanumTitle(spell.level)} (${row?.left ?? 0}/${row?.max ?? 0} left)`;
    default:
      return spell.level > 0 ? 'Cast at will' : null;
  }
}

// The confirm step's cost line, noting an upcast and the Metamagic's sorcery points; a cantrip cast for nothing with no
// Metamagic has none
function costLine(choice: CastChoice, spell: SpellOption, rows: ChoiceRow[], metamagic: MetamagicName[],
  metamagicPoints: number) {
  const paidWith = sourceText(choice, spell, rows.find(row => row.source === choice.source && row.level === choice.level));
  const names = (spell.metamagic ?? []).filter(option => metamagic.includes(option.option)).map(option => option.name);
  if (!paidWith && names.length === 0) return undefined;
  return (
    <>
      {paidWith}
      {choice.level > spell.level && <span className="text-muted ml-1">(upcast +{choice.level - spell.level})</span>}
      {names.length > 0 && (
        <span className="block">
          {names.join(' and ')}: {metamagicPoints} sorcery point{metamagicPoints === 1 ? '' : 's'}
        </span>
      )}
    </>
  );
}
