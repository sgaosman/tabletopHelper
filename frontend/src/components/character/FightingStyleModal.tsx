import { useState } from 'react';
import { Check } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import { useOptionalFeatures } from '../../queries/reference';
import type { PendingChoice, PlayerCharacter } from '../../types/character';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  character: PlayerCharacter;
  /** A FIGHTING_STYLE Pending Choice, whose options are the styles its class chooses from (D159) */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

/**
 * A Fighting Style from the class's list, with each style's text from the optional features data where it has it. A
 * style the character already has can't be taken again. It becomes a feature of the class, which levelling down past
 * the level takes away. The updated character replaces the cached one.
 */
export default function FightingStyleModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const stylesQuery = useOptionalFeatures('FightingStyle');
  const [selected, setSelected] = useState<string | null>(null);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;

  const options = choice.options ?? [];
  // The styles the character has, as features named after them
  const taken = new Set((character.features ?? [])
    .filter(f => f.name.startsWith('Fighting Style: '))
    .map(f => f.name.slice('Fighting Style: '.length)));
  const description = (style: string) =>
    stylesQuery.data?.find(f => f.name.toLowerCase() === style.toLowerCase())?.description ?? null;

  function handleConfirm() {
    if (!selected) return;
    applyChoices.mutate(
      { characterId: character.id, choices: { choiceId: choice.id, fightingStyle: selected } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  return (
    <Modal
      title="Choose a Fighting Style"
      subtitle={`${choice.className} ${choice.classLevel} (level ${choice.characterLevel})`}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onSkip}>Skip</Button>
          <Button onClick={handleConfirm} loading={submitting} disabled={!selected}>
            {submitting ? 'Choosing...' : 'Confirm'}
          </Button>
        </>
      )}
    >
      <div className="space-y-2">
        {applyChoices.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(applyChoices.error)}</div>
        )}
        {options.map(style => {
          const isSelected = selected === style;
          const alreadyTaken = taken.has(style);
          const text = description(style);
          return (
            <button
              key={style}
              type="button"
              onClick={() => setSelected(style)}
              disabled={alreadyTaken}
              aria-pressed={isSelected}
              className={`w-full flex items-start gap-3 px-4 py-3 border text-left transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                isSelected ? 'border-2 border-ink bg-page-alt' : 'border-rule bg-card hover:border-rule-light'
              }`}
            >
              <div className={`w-6 h-6 rounded flex items-center justify-center shrink-0 ${isSelected ? 'bg-ink' : 'bg-page-alt'}`}>
                {isSelected && <Check className="w-4 h-4 text-card" />}
              </div>
              <div className="min-w-0">
                <span className="font-heading text-[16px] font-semibold text-ink">{style}</span>
                {alreadyTaken && <span className="font-body text-[13px] font-semibold text-faint ml-2">Already taken</span>}
                {text && <p className="font-body text-[13px] font-semibold text-muted mt-0.5 line-clamp-3">{text}</p>}
              </div>
            </button>
          );
        })}
      </div>
    </Modal>
  );
}
