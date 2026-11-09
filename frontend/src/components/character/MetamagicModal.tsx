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
  /** A METAMAGIC Pending Choice, whose options are the sorcerer's PHB Metamagic and count how many to choose (M18) */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

const FEATURE_PREFIX = 'Metamagic: ';

/**
 * A sorcerer's Metamagic options (PHB p.101–102): two at 3rd level and one more at 10th and 17th, from the class's
 * list, each with its text from the optional features data. An option the character knows can't be taken again. Each
 * becomes a feature of the class, which levelling down past the level takes away. The updated character replaces the
 * cached one.
 */
export default function MetamagicModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const optionsQuery = useOptionalFeatures('Metamagic');
  const [selected, setSelected] = useState<string[]>([]);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;

  const options = choice.options ?? [];
  const known = new Set((character.features ?? [])
    .filter(f => f.name.startsWith(FEATURE_PREFIX))
    .map(f => f.name.slice(FEATURE_PREFIX.length)));
  const needed = Math.min(choice.count, options.filter(option => !known.has(option)).length);
  const description = (option: string) =>
    optionsQuery.data?.find(f => f.name.toLowerCase() === option.toLowerCase())?.description ?? null;

  function toggle(option: string) {
    setSelected(current => current.includes(option)
      ? current.filter(o => o !== option)
      : current.length < needed ? [...current, option] : current);
  }

  function handleConfirm() {
    if (selected.length !== needed) return;
    applyChoices.mutate(
      { characterId: character.id, choices: { choiceId: choice.id, metamagic: selected } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  return (
    <Modal
      title={needed === 1 ? 'Choose a Metamagic Option' : `Choose ${needed} Metamagic Options`}
      subtitle={`${choice.className} ${choice.classLevel} (level ${choice.characterLevel})`}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onSkip}>Skip</Button>
          <Button onClick={handleConfirm} loading={submitting} disabled={selected.length !== needed}>
            {submitting ? 'Choosing...' : `Confirm (${selected.length}/${needed})`}
          </Button>
        </>
      )}
    >
      <div className="space-y-2">
        {applyChoices.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(applyChoices.error)}</div>
        )}
        {options.map(option => {
          const isSelected = selected.includes(option);
          const alreadyKnown = known.has(option);
          const text = description(option);
          return (
            <button
              key={option}
              type="button"
              onClick={() => toggle(option)}
              disabled={alreadyKnown || (!isSelected && selected.length >= needed)}
              aria-pressed={isSelected}
              className={`w-full flex items-start gap-3 px-4 py-3 border text-left transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                isSelected ? 'border-2 border-ink bg-page-alt' : 'border-rule bg-card hover:border-rule-light'
              }`}
            >
              <div className={`w-6 h-6 rounded flex items-center justify-center shrink-0 ${isSelected ? 'bg-ink' : 'bg-page-alt'}`}>
                {isSelected && <Check className="w-4 h-4 text-card" />}
              </div>
              <div className="min-w-0">
                <span className="font-heading text-[16px] font-semibold text-ink">{option}</span>
                {alreadyKnown && <span className="font-body text-[13px] font-semibold text-faint ml-2">Already known</span>}
                {text && <p className="font-body text-[13px] font-semibold text-muted mt-0.5 line-clamp-3">{text}</p>}
              </div>
            </button>
          );
        })}
      </div>
    </Modal>
  );
}
