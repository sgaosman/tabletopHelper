import { Sparkles } from 'lucide-react';
import type { PendingChoice } from '../../types/character';
import { choiceLabel, choicesToMake } from '../../utils/pendingChoices';
import Button from '../ui/Button';

interface Props {
  choices: PendingChoice[];
  /** True while the character is in an encounter that is under way, when choices wait for it to end (D166) */
  disabled?: boolean;
  onChoose: (choice: PendingChoice) => void;
}

/**
 * The Pending Choices a character's levels owe (D159), each with a button that opens its dialog. A choice skipped or
 * left for later stays here, and the player may level up or join an encounter meanwhile. Nothing shows while there are
 * none.
 */
export default function PendingChoicesBanner({ choices, disabled = false, onChoose }: Props) {
  if (choices.length === 0) return null;
  return (
    <section aria-label="Choices to make" className="bg-cls-monk-bg border border-div-border px-4 py-3">
      <h2 className="font-heading text-[14px] font-semibold text-ink flex items-center gap-2">
        <Sparkles className="w-4 h-4" /> {choicesToMake(choices.length)}
      </h2>
      <div className="flex flex-wrap gap-2 mt-2">
        {choices.map(choice => (
          <Button key={choice.id} size="sm" variant="secondary" onClick={() => onChoose(choice)} disabled={disabled}>
            {choiceLabel(choice)}
          </Button>
        ))}
      </div>
    </section>
  );
}
