import { useId, useMemo, useState } from 'react';
import { Minus, Plus, Search } from 'lucide-react';
import type { SummonOptions } from '../../../types/encounter';
import {
  INITIATIVE_MODE_LABELS, budgetUsed, candidatesFor, creatureWeight, draftProblem, fewerAllowed, isTieredConjuring,
  type SummonDraft,
} from '../../../utils/summons';
import Button from '../../ui/Button';
import IconButton from '../../ui/IconButton';
import Input from '../../ui/Input';

const HEADING = 'font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-1.5';
const NOTE = 'font-body text-[13px] font-semibold text-muted';
// A choice the caster makes, at least 44px tall for a phone
const CHOICE = 'flex items-start gap-2 min-h-11 px-3 py-2 bg-page-alt border border-rule cursor-pointer has-[:checked]:border-ink';

/**
 * A summoning spell's choices (D182), from the summon options the server lists for the caster at the slot level: the
 * option (how many creatures, up to what challenge rating), the creatures with a count each and the running total
 * against the option's, a Tasha's spirit's form with its stat preview, the type a familiar or steed counts as, and how
 * the creatures take their turns, a mode the rules don't give being labelled a house rule. When the DM chooses the
 * caster's creatures (Summon Lesser Demons always, XGE p.167; the tiered conjurings when the campaign says so, PHB
 * p.225-226) it says so, and the caster chooses only the conjuring's option. `byDm` is the DM choosing them, who may
 * pick from every creature listed. `onContinue` and `onBack` add the step's own buttons, for the cast flow.
 */
export default function SummonStep({
  options, draft, onChange, byDm = false, rolled, onContinue, onBack, continueLabel = 'Continue',
}: {
  options: SummonOptions;
  draft: SummonDraft;
  onChange: (draft: SummonDraft) => void;
  byDm?: boolean;
  /** Summon Lesser Demons' d6, rolled when it was cast (XGE p.167) */
  rolled?: number | null;
  onContinue?: () => void;
  onBack?: () => void;
  continueLabel?: string;
}) {
  const name = useId();
  const [search, setSearch] = useState('');
  // Read as a string: the generated types learn the 4th-level kinds (D185) when the API types are regenerated
  const kind: string = options.kind;
  const tiered = isTieredConjuring(options);
  const option = options.options.find(o => o.id === draft.optionId);
  const dmWillChoose = options.dmChooses && !byDm;
  const problem = draftProblem(options, draft, byDm);
  // Animate Objects counts its objects by size (PHB p.213); anything else one each
  const total = budgetUsed(options, draft);
  const shown = useMemo(() => {
    const q = search.trim().toLowerCase();
    return candidatesFor(options, option).filter(c => !q || c.name.toLowerCase().includes(q));
  }, [options, option, search]);

  function chooseOption(optionId: string) {
    const next = options.options.find(o => o.id === optionId);
    const lone = options.candidates.length === 1 ? options.candidates[0] : null;
    onChange({ ...draft, optionId, counts: lone && next ? { [lone.monsterId]: next.count } : {} });
  }

  function setCount(monsterId: string, count: number) {
    const counts = { ...draft.counts };
    if (count > 0) counts[monsterId] = count; else delete counts[monsterId];
    onChange({ ...draft, counts });
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-baseline gap-x-2">
        {onBack && <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>}
        <span className="font-heading text-[16px] font-semibold text-ink">{options.spellName}</span>
        <span className="font-body text-[13px] font-semibold text-faint">
          level {options.slotLevel}{options.multiplier > 1 && ` — ×${options.multiplier} creatures at this level`}
        </span>
      </div>

      {dmWillChoose && (
        <p role="note" className="bg-page-alt border border-rule p-3 font-body text-[15px] font-semibold text-ink">
          {options.kind === 'LESSER_DEMONS'
            ? 'A d6 decides how many demons come, and the DM chooses them (XGE p.167).'
            : `The DM chooses ${options.spellName}'s creatures${tiered ? '; choose how many come' : ''}.`}
        </p>
      )}

      {rolled != null && (
        <p className={NOTE}>Rolled d6: {rolled}{option ? ` — ${option.label}` : ''}</p>
      )}

      {/* The option: shown as choices when there are several, else as a line */}
      {options.kind !== 'SPIRIT' && !(dmWillChoose && !tiered) && (
        options.options.length > 1 ? (
          <fieldset>
            <legend className={HEADING}>How many</legend>
            <div className="grid gap-1.5 sm:grid-cols-2">
              {options.options.map(o => (
                <label key={o.id} className={CHOICE}>
                  <input type="radio" name={`${name}-option`} checked={draft.optionId === o.id}
                    onChange={() => chooseOption(o.id)} className="mt-1 accent-ink" />
                  <span className="font-body text-[15px] font-semibold text-ink">{o.label}</span>
                </label>
              ))}
            </div>
          </fieldset>
        ) : option && <p className={NOTE}>{option.label}</p>
      )}

      {options.kind === 'SPIRIT' && !dmWillChoose && (
        <SpiritChoice options={options} draft={draft} onChange={onChange} name={name} />
      )}

      {options.kind !== 'SPIRIT' && !dmWillChoose && (
        <div>
          <p className={HEADING}>Creatures</p>
          {option ? (
            <>
              <p role="status" className={`mb-1.5 font-body text-[15px] font-semibold ${problem ? 'text-cls-fighter' : 'text-buff'}`}>
                Chosen {total} of {fewerAllowed(options) ? 'up to ' : ''}{option.count}
              </p>
              {options.candidates.length > 6 && (
                <div className="relative mb-1.5">
                  <Search className="absolute left-3 top-3 w-4 h-4 text-faint" aria-hidden="true" />
                  <Input label="Search creatures" hideLabel type="text" value={search}
                    onChange={e => setSearch(e.target.value)} placeholder="Search creatures..." className="[&_input]:pl-9" />
                </div>
              )}
              <ul className="space-y-1 max-h-60 overflow-y-auto" aria-label="Creatures to summon">
                {shown.length === 0 && <li className={NOTE}>No creature fits.</li>}
                {shown.map(c => {
                  const count = draft.counts[c.monsterId] ?? 0;
                  const full = total + creatureWeight(options, c) > option.count;
                  return (
                    <li key={c.monsterId} className="flex items-center justify-between gap-2 px-3 py-1.5 bg-page-alt border border-rule">
                      <div className="min-w-0">
                        <span className="font-body text-[15px] font-semibold text-ink">{c.name}</span>
                        <span className="block font-body text-[12.5px] font-semibold text-faint">
                          {c.challengeRating != null && `CR ${c.challengeRating} · `}HP {c.hitPoints} · AC {c.armourClass}
                          {c.type && ` · ${c.type}`}
                        </span>
                      </div>
                      <div className="flex items-center gap-1 shrink-0">
                        <IconButton label={`One fewer ${c.name}`} onClick={() => setCount(c.monsterId, count - 1)}
                          disabled={count === 0}
                          className="inline-flex items-center justify-center size-11 sm:size-8 border border-rule bg-card disabled:opacity-40">
                          <Minus className="w-3.5 h-3.5" />
                        </IconButton>
                        <span aria-label={`${c.name} count`} className="w-6 text-center font-heading text-[15px] font-bold text-ink">{count}</span>
                        <IconButton label={`One more ${c.name}`} onClick={() => setCount(c.monsterId, count + 1)}
                          disabled={full}
                          className="inline-flex items-center justify-center size-11 sm:size-8 border border-rule bg-card disabled:opacity-40">
                          <Plus className="w-3.5 h-3.5" />
                        </IconButton>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </>
          ) : (
            <p className={NOTE}>Choose how many first.</p>
          )}
        </div>
      )}

      {options.creatureTypes.length > 0 && !dmWillChoose && (
        <fieldset>
          <legend className={HEADING}>Creature type</legend>
          <div className="flex flex-wrap gap-1.5">
            {options.creatureTypes.map(type => (
              <label key={type} className={CHOICE}>
                <input type="radio" name={`${name}-type`} checked={draft.creatureType === type}
                  onChange={() => onChange({ ...draft, creatureType: type })} className="mt-1 accent-ink" />
                <span className="font-body text-[15px] font-semibold text-ink capitalize">{type}</span>
              </label>
            ))}
          </div>
        </fieldset>
      )}
      {options.creatureTypes.length === 0 && options.creatureType && (
        <p className={NOTE}>They count as {options.creatureType} creatures.</p>
      )}

      {options.initiativeModes.length > 1 && !dmWillChoose && (
        <fieldset>
          <legend className={HEADING}>Initiative</legend>
          <div className="grid gap-1.5">
            {options.initiativeModes.map(mode => (
              <label key={mode} className={CHOICE}>
                <input type="radio" name={`${name}-initiative`} checked={draft.initiativeMode === mode}
                  onChange={() => onChange({ ...draft, initiativeMode: mode })} className="mt-1 accent-ink" />
                <span className="font-body text-[15px] font-semibold text-ink">
                  {INITIATIVE_MODE_LABELS[mode]}
                  {options.houseRuleModes.includes(mode) && (
                    <span className="ml-2 font-heading text-[11px] font-semibold tracking-[0.02em] text-cls-fighter">house rule</span>
                  )}
                </span>
              </label>
            ))}
          </div>
        </fieldset>
      )}

      <ul className="space-y-0.5">
        {options.controlledBy === 'DM' && (kind === 'GIANT_INSECT'
          ? <li className={NOTE}>They act on your turn and obey your commands; the DM resolves their actions and movement (PHB p.245).</li>
          : <li className={NOTE}>The DM controls them: they are hostile and act on their own.</li>)}
        {kind === 'GREATER_DEMON' && (
          <li className={NOTE}>At the end of each of its turns it makes a Charisma save against your spell save DC; on a success your control ends (XGE p.166).</li>
        )}
        {kind === 'CONJURE_ELEMENTAL' && (
          <li className={NOTE}>If your concentration is broken it doesn't disappear: it turns hostile, you can't dismiss it, and it disappears 1 hour after you summoned it (PHB p.225).</li>
        )}
        {kind === 'INFERNAL_CALLING' && (
          <li className={NOTE}>A command it dislikes needs your Charisma check against its Wisdom (Insight); on a failure it ignores your commands for the rest of the spell (XGE p.158).</li>
        )}
        {kind === 'ANIMATE_OBJECTS' && (
          <li className={NOTE}>A Medium object counts as 2, a Large as 4 and a Huge as 8 (PHB p.213).</li>
        )}
        {kind === 'DANSE_MACABRE' && (
          <li className={NOTE}>They add your spellcasting ability modifier to their attack and damage rolls (XGE p.153).</li>
        )}
        {options.cannotAttack && <li className={NOTE}>A familiar can't attack (PHB p.240).</li>}
        {options.needsCommand && <li className={NOTE}>They act only on your command, a bonus action on your turn.</li>}
        {options.concentration && <li className={NOTE}>Needs concentration: they vanish when it ends.</li>}
      </ul>

      {onContinue && (
        <div className="flex items-center justify-end gap-2">
          {problem && <span className="font-body text-[13px] font-semibold text-faint">{problem}</span>}
          <Button onClick={onContinue} disabled={!!problem}>{continueLabel}</Button>
        </div>
      )}
    </div>
  );
}

/** A Tasha's spirit's form, and its stat block at this slot level (TCE p.109-114). */
function SpiritChoice({ options, draft, onChange, name }: {
  options: SummonOptions;
  draft: SummonDraft;
  onChange: (draft: SummonDraft) => void;
  name: string;
}) {
  const spirit = options.spirit;
  const hp = spirit && draft.variant ? spirit.hitPoints[draft.variant] : undefined;
  // A variant may add to the spirit's AC, as the Celestial Spirit Defender's 2 (TCE p.110)
  const ac = spirit && draft.variant ? spirit.armourClasses?.[draft.variant] ?? spirit.armourClass
    : spirit?.armourClass;
  return (
    <>
      {/* A spirit with one form, such as the reaper spirit (BMT p.50), takes it without asking */}
      {options.variants.length > 1 && (
        <fieldset>
          <legend className={HEADING}>Spirit</legend>
          <div className="flex flex-wrap gap-1.5">
            {options.variants.map(variant => (
              <label key={variant} className={CHOICE}>
                <input type="radio" name={`${name}-variant`} checked={draft.variant === variant}
                  onChange={() => onChange({ ...draft, variant })} className="mt-1 accent-ink" />
                <span className="font-body text-[15px] font-semibold text-ink">{variant}</span>
              </label>
            ))}
          </div>
        </fieldset>
      )}
      {spirit && (
        <dl aria-label={`${spirit.name} at level ${options.slotLevel}`}
          className="grid grid-cols-2 sm:grid-cols-5 gap-2 bg-page-alt border border-rule p-3">
          <Stat label="AC" value={ac ?? spirit.armourClass} />
          <Stat label="HP" value={hp ?? '—'} />
          <Stat label="Attacks" value={spirit.attacks} />
          <Stat label="To hit" value={`${spirit.attackBonus >= 0 ? '+' : ''}${spirit.attackBonus}`} />
          <Stat label="Save DC" value={spirit.saveDc} />
        </dl>
      )}
    </>
  );
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <dt className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint">{label}</dt>
      <dd className="font-heading text-[17px] font-bold text-ink">{value}</dd>
    </div>
  );
}
