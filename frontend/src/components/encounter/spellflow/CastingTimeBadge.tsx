import { castingTimeLabel, type CastingTime } from '../../../utils/turnEconomy';

/** A spell's casting time as a small badge: an action, a bonus action, a reaction, or longer than a turn (D174). */
export default function CastingTimeBadge({ castingTime }: { castingTime: CastingTime }) {
  return (
    <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 border border-rule text-muted whitespace-nowrap">
      <span className="sr-only">Casting time: </span>{castingTimeLabel(castingTime)}
    </span>
  );
}
