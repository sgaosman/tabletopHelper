import { useMemo } from 'react';
import { Target } from 'lucide-react';
import type { EncounterParticipant } from '../../../types/encounter';
import type { SpellTargetingInfo } from '../../../types/reference';
import { healthLabel } from '../../../utils/healthStatus';
import { reachable } from '../../../utils/spellEffects';
import Button from '../../ui/Button';

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
}

/** Chooses a spell's targets: the caster and those the spell can reach, up to its limit. */
export default function TargetStep({
  spellName, caster, participants, selectedTargets, onToggle, targetingInfo, targetingLoading, onProceed, onBack,
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

  const targetLabel = maxTargets > 0
    ? `Select targets (${selectedTargets.length}/${maxTargets})`
    : `Select target${selectedTargets.length > 0 ? `s (${selectedTargets.length})` : '(s)'}`;

  return (
    <>
      <div className="flex items-center gap-2 mb-2">
        {onBack && <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>}
        <span className="font-heading text-[16px] font-semibold text-ink">{spellName}</span>
        <span className="font-body text-[13px] font-semibold text-faint">— {targetingLoading ? 'Loading...' : targetLabel}</span>
      </div>

      {targetingLoading ? (
        <p className="font-body text-[15px] font-semibold text-faint text-center py-4">Loading targeting info...</p>
      ) : (
        <div className="space-y-1">
          {eligible.map(p => {
            const isSelected = selectedTargets.includes(p.id);
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
                  {p.id === caster.id && <span className="font-body text-[13px] font-semibold text-faint">(self)</span>}
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
