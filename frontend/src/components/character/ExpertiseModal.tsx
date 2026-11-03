import { useState } from 'react';
import { Check } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import type { PendingChoice, PlayerCharacter } from '../../types/character';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  character: PlayerCharacter;
  /** An EXPERTISE Pending Choice, whose count says how many skills gain it (D159) */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

/**
 * Expertise in skills the character is proficient in and has no Expertise in yet: as many as the choice counts, or
 * every one left when there are fewer. The updated character replaces the cached one.
 */
export default function ExpertiseModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const [selected, setSelected] = useState<string[]>([]);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;

  const proficientSkills = character.skillProficiencies ?? [];
  const existingExpertise = character.skillExpertises ?? [];
  const available = proficientSkills.filter(s => !existingExpertise.includes(s));
  const count = Math.min(choice.count, available.length);

  function toggle(skill: string) {
    setSelected(prev =>
      prev.includes(skill) ? prev.filter(s => s !== skill) : prev.length < count ? [...prev, skill] : prev
    );
  }

  function handleConfirm() {
    applyChoices.mutate(
      { characterId: character.id, choices: { choiceId: choice.id, expertiseSkills: selected } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  return (
    <Modal
      title="Choose Expertise"
      subtitle={<>{choice.className} {choice.classLevel}: select {count} skill{count === 1 ? '' : 's'} to double your proficiency bonus ({selected.length}/{count})</>}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onSkip}>Skip</Button>
          <Button onClick={handleConfirm} loading={submitting} disabled={selected.length !== count}>Confirm Expertise</Button>
        </>
      )}
    >
      <div className="space-y-2">
        {applyChoices.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(applyChoices.error)}</div>
        )}
        {available.length === 0 ? (
          <p className="font-body text-[15px] text-faint text-center py-4">No eligible skills for expertise.</p>
        ) : (
          available.map(skill => {
            const isSelected = selected.includes(skill);
            return (
              <button
                key={skill}
                type="button"
                onClick={() => toggle(skill)}
                aria-pressed={isSelected}
                className={`w-full flex items-center gap-3 px-4 py-3 border text-left transition-colors ${
                  isSelected
                    ? 'border-2 border-ink bg-page-alt'
                    : 'border-rule bg-card hover:border-rule-light'
                }`}
              >
                <div className={`w-6 h-6 rounded flex items-center justify-center ${
                  isSelected ? 'bg-ink' : 'bg-page-alt'
                }`}>
                  {isSelected && <Check className="w-4 h-4 text-card" />}
                </div>
                <span className="font-heading text-[16px] font-semibold text-ink">
                  {skill}
                </span>
              </button>
            );
          })
        )}
      </div>
    </Modal>
  );
}
