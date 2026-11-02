import { ChevronDown, ChevronUp } from 'lucide-react';
import IconButton from '../ui/IconButton';
import type { TurnEntry } from '../../types/encounter';

interface TieOrderArrowsProps {
  /** The encounter's turnOrder, as the server computes it */
  turns: TurnEntry[];
  /** The place in turns of the participant these arrows move */
  index: number;
  /** The participant's name, for the arrows' labels */
  name: string;
  /** Gets every ID in the tie, in the new order, as PUT /initiatives/tie-order takes them */
  onReorder: (participantIds: string[]) => void;
}

/**
 * The DM's up and down arrows for a participant tied with others on initiative and DEX, where only the DM's order
 * decides who acts first (D163). Each swaps the participant with its neighbour in the tie. Nothing shows for a turn
 * tied with no other.
 */
export default function TieOrderArrows({ turns, index, name, onReorder }: TieOrderArrowsProps) {
  const turn = turns[index];
  if (turn?.tieGroup == null || turn.participantId == null) return null;
  const participantId = turn.participantId;
  const tiedBefore = turns[index - 1]?.tieGroup === turn.tieGroup;
  const tiedAfter = turns[index + 1]?.tieGroup === turn.tieGroup;

  function move(offset: -1 | 1) {
    const ids = turns.filter(t => t.tieGroup === turn.tieGroup).flatMap(t => t.participantId ?? []);
    const at = ids.indexOf(participantId);
    [ids[at], ids[at + offset]] = [ids[at + offset], ids[at]];
    onReorder(ids);
  }

  return (
    <div className="flex flex-col flex-shrink-0 -my-1">
      <IconButton
        label={`Move ${name} up the tie`}
        disabled={!tiedBefore}
        onClick={() => move(-1)}
        className="p-0.5 text-faint hover:text-ink disabled:opacity-30"
      >
        <ChevronUp className="w-3.5 h-3.5" />
      </IconButton>
      <IconButton
        label={`Move ${name} down the tie`}
        disabled={!tiedAfter}
        onClick={() => move(1)}
        className="p-0.5 text-faint hover:text-ink disabled:opacity-30"
      >
        <ChevronDown className="w-3.5 h-3.5" />
      </IconButton>
    </div>
  );
}
