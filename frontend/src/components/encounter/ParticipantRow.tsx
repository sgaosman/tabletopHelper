import type { ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import type { EncounterParticipant } from '../../types/encounter';
import { getParticipantColour } from '../../utils/classColours';
import WildShapeStatus from './WildShapeStatus';

/**
 * A participant's row in the initiative list: the left edge in its class colour (or the monster colour), the turn
 * marker, its initiative and name, with slots for what each page adds. `highlighted` rings the row, as the DM's
 * chosen target or the player's own character. A participant that is down is dimmed, and the dead are struck through.
 * A druid in Wild Shape shows the beast it has become, with the beast's and its own HP and AC (PHB p.66).
 *
 * @param leading after the initiative, such as the DM's tie arrows
 * @param badges after the name, on the same line
 * @param children under the name: conditions, spells, death saves and pools
 * @param trailing at the right: HP and AC, labels and buttons
 * @param onEndWildShape ends the druid's Wild Shape, for whoever may: the DM, or the druid's player
 */
export default function ParticipantRow({
  participant: p, current, highlighted = false, leading, badges, children, trailing, onEndWildShape,
}: {
  participant: EncounterParticipant;
  current: boolean;
  highlighted?: boolean;
  leading?: ReactNode;
  badges?: ReactNode;
  children?: ReactNode;
  trailing?: ReactNode;
  onEndWildShape?: () => void;
}) {
  const isMonster = p.participantType === 'MONSTER';
  return (
    <div
      aria-current={current ? 'step' : undefined}
      className={`bg-card border border-l-[3px] px-4 py-3 transition-all ${
        current ? 'border-t-cls-fighter border-r-cls-fighter border-b-cls-fighter ring-1 ring-cls-fighter/30' :
        highlighted ? 'border-t-ink border-r-ink border-b-ink ring-1 ring-ink/30' :
        'border-t-rule border-r-rule border-b-rule'
      } ${p.lifeState !== 'ALIVE' ? 'opacity-60' : ''}`}
      style={{ borderLeftColor: getParticipantColour(isMonster, p.characterClass) }}
    >
      <div className="flex items-center gap-3">
        <div className="w-5 flex-shrink-0">
          {current && <ChevronRight className="w-5 h-5 text-cls-fighter" />}
        </div>

        <div className="w-9 text-center flex-shrink-0">
          <span className="font-heading text-[17px] font-bold text-ink">{p.initiative ?? '—'}</span>
        </div>

        {leading}

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className={`font-heading text-[15px] font-semibold tracking-[0.01em] text-ink ${p.lifeState === 'DEAD' ? 'line-through' : ''}`}>
              {p.displayName}
            </span>
            {badges}
          </div>
          <WildShapeStatus participant={p} onEnd={onEndWildShape} />
          {children}
        </div>

        {trailing}
      </div>
    </div>
  );
}
