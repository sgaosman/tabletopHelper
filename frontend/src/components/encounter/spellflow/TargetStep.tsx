import { useMemo } from 'react';
import { Minus, Plus, Target } from 'lucide-react';
import type { EncounterParticipant } from '../../../types/encounter';
import type { SpellTargetingInfo } from '../../../types/reference';
import { healthLabel } from '../../../utils/healthStatus';
import { reachable } from '../../../utils/spellEffects';
import { addDart, dartCounts, makeMain, pickedHint, removeDart, roleLabel, type TargetRoleMode } from '../../../utils/targetRoles';
import Button from '../../ui/Button';
import IconButton from '../../ui/IconButton';

interface Props {
  spellName: string;
  caster: EncounterParticipant;
  /** Everyone in the encounter; the step offers those the spell can reach. */
  participants: EncounterParticipant[];
  selectedTargets: string[];
  onToggle: (id: string) => void;
  /** Who the spell can target, or null when that is unknown, which leaves the targets unrestricted. */
  targetingInfo: SpellTargetingInfo | null;
  targetingLoading: boolean;
  onProceed: () => void;
  /** Back to the step before; without it there is no Back button. */
  onBack?: () => void;
  /**
   * What each chosen target is to the spell (D200): the main target and the creatures picked near it, as Ice Knife's,
   * or the darts shared out, as Magic Missile's. Left out for a spell whose targets are all alike.
   */
  roles?: TargetRoleMode | null;
  /** Sets the targets in order, with repeats for darts; needed with `roles`. */
  onChangeTargets?: (ids: string[]) => void;
}

/**
 * Chooses a spell's targets: the caster and those the spell can reach, up to its limit. With `roles`, a spell whose
 * first targets are its own and the rest creatures the caster picks labels each chosen one, the first chosen being the
 * main target, and a picked one can be made the main target; a spell of darts gives each creature a dart counter whose
 * darts must add up to the cast's, each dart sent as one entry of the targets (D199, D200).
 */
export default function TargetStep({
  spellName, caster, participants, selectedTargets, onToggle, targetingInfo, targetingLoading, onProceed, onBack,
  roles = null, onChangeTargets,
}: Props) {
  const maxTargets = targetingInfo?.maxTargets ?? -1;

  const eligible = useMemo(() => {
    if (targetingInfo?.selfOnly) return [caster];

    const list: EncounterParticipant[] = [];
    if (targetingInfo?.canTargetSelf ?? true) list.push(caster);

    // The dead are out of reach, but the dying and stable can still be healed or harmed (D161)
    const casterIsMonster = caster.participantType === 'MONSTER';
    for (const p of participants) {
      // Nor can a banished creature be reached, or one across Otiluke's Resilient Sphere (D185)
      if (p.id === caster.id || p.lifeState === 'DEAD' || !reachable(caster, p)) continue;
      const isAlly = casterIsMonster === (p.participantType === 'MONSTER');
      if (isAlly && targetingInfo?.canTargetAllies !== false) list.push(p);
      else if (!isAlly && targetingInfo?.canTargetEnemies !== false) list.push(p);
    }
    return list;
  }, [participants, caster, targetingInfo]);

  const atLimit = maxTargets > 0 && selectedTargets.length >= maxTargets;
  const darts = roles?.kind === 'darts' ? roles : null;
  const split = roles?.kind === 'split' ? roles : null;
  const counts = darts ? dartCounts(selectedTargets) : null;

  const targetLabel = darts
    ? `Assign darts (${selectedTargets.length}/${darts.count})`
    : maxTargets > 0
      ? `Select targets (${selectedTargets.length}/${maxTargets})`
      : `Select target${selectedTargets.length > 0 ? `s (${selectedTargets.length})` : '(s)'}`;
  // Every dart goes somewhere before the spell is cast
  const ready = darts ? selectedTargets.length === darts.count : selectedTargets.length > 0;
  const change = (ids: string[]) => onChangeTargets?.(ids);

  return (
    <>
      <div className="flex items-center gap-2 mb-2">
        {onBack && <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>}
        <span className="font-heading text-[16px] font-semibold text-ink">{spellName}</span>
        <span className="font-body text-[13px] font-semibold text-faint">— {targetingLoading ? 'Loading...' : targetLabel}</span>
      </div>

      {!targetingLoading && split && (
        <p className="font-body text-[13px] font-semibold text-muted mb-1">{pickedHint(split)}</p>
      )}
      {!targetingLoading && darts && (
        <p className="font-body text-[13px] font-semibold text-muted mb-1">
          {`Share ${darts.count} darts among the targets: one creature may take several.`}
        </p>
      )}

      {targetingLoading ? (
        <p className="font-body text-[15px] font-semibold text-faint text-center py-4">Loading targeting info...</p>
      ) : darts && counts ? (
        <div className="space-y-1">
          {eligible.map(p => {
            const taken = counts.get(p.id) ?? 0;
            return (
              <div key={p.id} role="group" aria-label={`Darts at ${p.displayName}`}
                className={`w-full flex items-center justify-between px-3 py-2 ${taken > 0 ? 'bg-page-alt border-2 border-ink' : 'bg-page-alt border border-rule'}`}>
                <div className="flex items-center gap-2">
                  <Target className={`w-3.5 h-3.5 ${taken > 0 ? 'text-ink' : 'text-faint'}`} />
                  <span className={`font-body text-[15px] font-semibold ${taken > 0 ? 'text-ink' : 'text-muted'}`}>{p.displayName}</span>
                  {p.id === caster.id && <span className="font-body text-[13px] font-semibold text-faint">(self)</span>}
                  <span className="font-body text-[13px] font-semibold text-faint">AC {p.armourClass}</span>
                </div>
                <div className="flex items-center gap-1">
                  <IconButton label={`One dart fewer at ${p.displayName}`} disabled={taken === 0}
                    onClick={() => change(removeDart(selectedTargets, p.id))}
                    className="p-1 border border-rule text-ink hover:bg-rule disabled:opacity-40">
                    <Minus className="w-3.5 h-3.5" />
                  </IconButton>
                  <span className="w-8 text-center font-heading text-[15px] font-semibold text-ink">{taken}</span>
                  <IconButton label={`One more dart at ${p.displayName}`} disabled={selectedTargets.length >= darts.count}
                    onClick={() => change(addDart(selectedTargets, p.id, darts.count))}
                    className="p-1 border border-rule text-ink hover:bg-rule disabled:opacity-40">
                    <Plus className="w-3.5 h-3.5" />
                  </IconButton>
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        <div className="space-y-1">
          {eligible.map(p => {
            const isSelected = selectedTargets.includes(p.id);
            const disabled = !isSelected && atLimit;
            const place = selectedTargets.indexOf(p.id);
            const role = split && isSelected ? roleLabel(split, place) : null;
            const row = (
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
                  {p.id === caster.id && <span className="font-body text-[13px] font-semibold text-faint">(self)</span>}
                  {role && (
                    <span className={`px-1.5 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] ${
                      place < (split?.main ?? 0) ? 'bg-ink text-card' : 'bg-rule text-ink'}`}>{role}</span>
                  )}
                </div>
                <div className="flex items-center gap-2 font-body text-[13px] font-semibold text-faint">
                  <span>AC {p.armourClass}</span>
                  {healthLabel(p) && <span>{healthLabel(p)}</span>}
                </div>
              </button>
            );
            if (!split) return row;
            // A picked creature can be made the main target instead (D200)
            const canBeMain = split.main > 0 && isSelected && place >= split.main;
            return (
              <div key={p.id} className="flex items-stretch gap-1">
                <div className="flex-1 min-w-0">{row}</div>
                {canBeMain && (
                  <Button variant="ghost" size="sm" onClick={() => change(makeMain(selectedTargets, p.id))}
                    aria-label={`Make ${p.displayName} the main target`}>
                    Make main
                  </Button>
                )}
              </div>
            );
          })}
        </div>
      )}

      <Button size="lg" className="w-full mt-2" onClick={onProceed} disabled={!ready}>
        Continue
      </Button>
    </>
  );
}
