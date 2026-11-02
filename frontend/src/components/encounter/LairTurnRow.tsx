import { Castle, ChevronRight } from 'lucide-react';
import type { TurnEntry } from '../../types/encounter';

/**
 * The Lair's turn in the initiative list (D163): on initiative 20, losing ties, while a monster fighting in its
 * lair is alive. It is not a participant: lair actions are taken by the monsters in their lair.
 */
export default function LairTurnRow({ turn, lairMonsters }: { turn: TurnEntry; lairMonsters: string[] }) {
  return (
    <div
      aria-current={turn.current ? 'step' : undefined}
      className={`bg-page-alt border border-l-[3px] border-l-cls-warlock px-4 py-2 transition-all ${
        turn.current ? 'border-t-cls-fighter border-r-cls-fighter border-b-cls-fighter ring-1 ring-cls-fighter/30' : 'border-t-rule border-r-rule border-b-rule'
      }`}
    >
      <div className="flex items-center gap-3">
        <div className="w-5 flex-shrink-0">
          {turn.current && <ChevronRight className="w-5 h-5 text-cls-fighter" />}
        </div>
        <div className="w-9 text-center flex-shrink-0">
          <span className="font-heading text-[17px] font-bold text-ink">{turn.initiative}</span>
        </div>
        <Castle className="w-4 h-4 text-cls-warlock flex-shrink-0" />
        <span className="font-heading text-[15px] font-semibold tracking-[0.01em] text-ink">{turn.name}</span>
        {lairMonsters.length > 0 && (
          <span className="font-body text-[13px] font-semibold text-faint truncate">{lairMonsters.join(', ')}</span>
        )}
      </div>
    </div>
  );
}
