import type { EncounterParticipant } from '../../types/encounter';

/** Current HP as a bar, green, then amber at half and red at a quarter, with any temporary HP after it. */
export default function HpBar({ participant }: { participant: EncounterParticipant }) {
  const hpMax = participant.hpMax ?? 0;
  const pct = hpMax > 0 ? ((participant.hpCurrent ?? 0) / hpMax) * 100 : 0;
  const tempPct = hpMax > 0 ? ((participant.hpTemp || 0) / hpMax) * 100 : 0;

  let barColor = 'bg-buff';
  if (pct <= 25) barColor = 'bg-debuff';
  else if (pct <= 50) barColor = 'bg-cls-monk';

  return (
    <div className="w-full bg-rule h-2 overflow-hidden flex">
      <div className={`${barColor} h-full transition-all duration-300`} style={{ width: `${Math.min(pct, 100)}%` }} />
      {tempPct > 0 && (
        <div className="bg-cls-wizard h-full transition-all duration-300" style={{ width: `${Math.min(tempPct, 100 - pct)}%` }} />
      )}
    </div>
  );
}
