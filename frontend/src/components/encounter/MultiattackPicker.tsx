import { useState } from 'react';
import { apiErrorMessage } from '../../api/errors';
import { useMultiattackOptions } from '../../queries/encounters';
import { Select } from '../ui/Input';
import type { EncounterParticipant } from '../../types/encounter';

const CHIP = 'px-2 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] border transition-colors';
const CHIP_ON = 'bg-monster text-card border-monster';
const CHIP_OFF = 'bg-card border-rule-light hover:bg-monster-bg';

/**
 * The DM's target for each attack of a monster's multiattack (D165): the bite at one creature and each claw at
 * another, as the stat block allows. The attacks are the ones the server reads from the monster's action data, in
 * order; one with no target is skipped, and one the DM resolves by hand takes none. When the server can't read the
 * attacks, the multiattack is the DM's: its stat block wording shows, and the monster's actions are used one at a
 * time. An attack whose damage type the monster chooses, as each of a dracohydra's Bites (FTD), takes a damage type
 * too, and the multiattack waits until every such attack with a target has one (D207).
 *
 * @param describe the numbers of one of the monster's actions, such as "+14 · 2d10+8", for each attack's label
 * @param onExecute sends the targets of each attack in order, or null to log a multiattack the DM resolves, and the
 *                  damage type of each attack in order ("" for none) when any attack has damage options
 */
export default function MultiattackPicker({ monster, participants, actionName, describe, executing, onExecute }: {
  monster: EncounterParticipant;
  participants: EncounterParticipant[];
  actionName: string;
  describe: (actionName: string) => string | undefined;
  executing: boolean;
  onExecute: (componentTargetIds: string[][] | null, componentDamageTypes?: string[]) => void;
}) {
  const optionsQuery = useMultiattackOptions(monster.id);
  const [chosen, setChosen] = useState<string[][] | null>(null);
  const [types, setTypes] = useState<Record<number, string>>({});

  if (optionsQuery.isPending) {
    return <p className="mt-2 font-body text-[15px] font-semibold text-muted">Loading attacks...</p>;
  }
  if (optionsQuery.isError) {
    return <p role="alert" className="mt-2 font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(optionsQuery.error)}</p>;
  }
  const option = optionsQuery.data.find(o => o.name === actionName);
  if (!option) {
    return <p role="alert" className="mt-2 font-body text-[15px] font-semibold text-debuff">{actionName} isn't one of {monster.displayName}'s multiattacks</p>;
  }

  // The dead can't be attacked (D161)
  const candidates = participants.filter(p => p.id !== monster.id && p.lifeState !== 'DEAD');
  const components = option.components;

  if (components.length === 0) {
    return (
      <section aria-label={`${actionName} attacks`} className="mt-2 p-3 border border-cls-monk/40 bg-card space-y-2">
        {option.description && <p className="font-body text-[15px] font-semibold text-muted leading-snug">{option.description}</p>}
        <p role="note" className="font-body text-[13px] font-semibold text-cls-monk">
          {option.manualReason}: resolve it by hand, or use {monster.displayName}'s actions one at a time.
        </p>
        <button type="button" disabled={executing} onClick={() => onExecute(null)}
          className="px-3 py-1 bg-ink hover:bg-ink/90 disabled:bg-page-alt disabled:text-faint text-card font-heading text-[12px] font-semibold tracking-[0.02em]">
          {executing ? 'Executing...' : `Log ${actionName} for the DM to resolve`}
        </button>
      </section>
    );
  }

  // Choices made for a different list of attacks, such as another multiattack's, are dropped rather than read by index
  const current = chosen && chosen.length === components.length ? chosen : components.map(() => []);

  // One target replaces another for an attack that takes one; otherwise up to the attack's limit, if it has one
  function toggle(index: number, targetId: string) {
    const component = components[index];
    const targets = current[index];
    let next: string[];
    if (targets.includes(targetId)) {
      next = targets.filter(id => id !== targetId);
    } else if (component.maxTargets === 1) {
      next = [targetId];
    } else if (component.maxTargets != null && targets.length >= component.maxTargets) {
      return;
    } else {
      next = [...targets, targetId];
    }
    setChosen(current.map((ids, i) => (i === index ? next : ids)));
  }

  function sameTargetForAll(targetId: string) {
    setChosen(components.map(component => (component.automated ? [targetId] : [])));
  }

  const attacks = current.filter((ids, i) => components[i].automated && ids.length > 0).length;
  const hasOptions = (i: number) => components[i].automated && (components[i].damageOptions?.length ?? 0) > 0;
  // An attack made with damage options needs its type first (D207)
  const untyped = current.filter((ids, i) => hasOptions(i) && ids.length > 0 && !types[i]).length;
  const damageTypes = components.some((_, i) => hasOptions(i))
    ? current.map((ids, i) => (hasOptions(i) && ids.length > 0 ? types[i] ?? '' : ''))
    : undefined;

  return (
    <section aria-label={`${actionName} attacks`} className="mt-2 p-3 border border-cls-monk/40 bg-card space-y-2">
      <p className="font-heading text-[13px] font-semibold tracking-[0.01em] text-ink">Choose a target for each attack; one without a target is skipped</p>
      {option.description && <p className="font-body text-[15px] font-semibold text-muted leading-snug">{option.description}</p>}

      <div className="flex flex-wrap items-center gap-1">
        <span className="w-44 font-body text-[12.5px] font-semibold text-faint">Same target for every attack</span>
        <div role="group" aria-label="Same target for every attack" className="flex flex-wrap gap-1">
          {candidates.map(target => (
            <button key={target.id} type="button" onClick={() => sameTargetForAll(target.id)} className={`${CHIP} ${CHIP_OFF}`}>
              {target.displayName}
            </button>
          ))}
        </div>
      </div>

      {components.map((component, i) => {
        const label = `${i + 1}. ${component.name}`;
        const numbers = describe(component.name);
        return (
          <div key={i} className="flex flex-wrap items-center gap-1">
            <span className="w-44 font-body text-[12.5px] font-semibold text-ink">
              {label}{numbers ? <span className="text-faint"> {numbers}</span> : null}
              {component.automated && component.maxTargets == null && <span className="text-faint"> (any number)</span>}
            </span>
            {hasOptions(i) && (
              <Select label={`Damage type for ${label}`} hideLabel className="w-40" value={types[i] ?? ''}
                onChange={e => setTypes(prev => ({ ...prev, [i]: e.target.value }))}>
                <option value="">Damage type</option>
                {component.damageOptions.map(type => <option key={type} value={type}>{type}</option>)}
              </Select>
            )}
            {component.automated ? (
              <div role="group" aria-label={`Targets for ${label}`} className="flex flex-wrap gap-1">
                {candidates.map(target => {
                  const on = current[i].includes(target.id);
                  return (
                    <button key={target.id} type="button" aria-pressed={on} onClick={() => toggle(i, target.id)}
                      className={`${CHIP} ${on ? CHIP_ON : CHIP_OFF}`}>
                      {target.displayName}
                    </button>
                  );
                })}
              </div>
            ) : (
              <span className="font-body text-[12.5px] font-semibold text-cls-monk">The DM resolves this: {component.manualReason}</span>
            )}
          </div>
        );
      })}

      {untyped > 0 && (
        <p role="note" className="font-body text-[12.5px] font-semibold text-faint">
          Choose a damage type for each attack that has a target.
        </p>
      )}
      <button type="button" disabled={attacks === 0 || untyped > 0 || executing}
        onClick={() => onExecute(current.map((ids, i) => (components[i].automated ? ids : [])), damageTypes)}
        className="px-3 py-1 bg-ink hover:bg-ink/90 disabled:bg-page-alt disabled:text-faint text-card font-heading text-[12px] font-semibold tracking-[0.02em]">
        {executing ? 'Executing...' : `Execute ${actionName} (${attacks} attack${attacks !== 1 ? 's' : ''})`}
      </button>
    </section>
  );
}
