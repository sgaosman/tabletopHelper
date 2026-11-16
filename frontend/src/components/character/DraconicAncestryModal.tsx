import { useState } from 'react';
import { Check } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import type { PendingChoice, PlayerCharacter } from '../../types/character';
import { ancestryOf, DRACONIC_ANCESTRIES } from '../../utils/draconicAncestry';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  character: PlayerCharacter;
  /** A DRACONIC_ANCESTRY Pending Choice, whose options are the dragons it offers (D208) */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

/**
 * A Dragonborn's draconic ancestry (PHB p.34, D208): one of the dragons the choice offers, each with its damage type and
 * breath. The server records it on the 1st level and gives the resistance to its damage type; the Breath Weapon reads
 * it in encounters. A character made before the choice existed is offered the dragons of the resistance it has.
 */
export default function DraconicAncestryModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const [selected, setSelected] = useState<string | null>(null);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;
  const offered = (choice.options ?? DRACONIC_ANCESTRIES.map(a => a.dragon))
    .map(dragon => ancestryOf(dragon) ?? { dragon, damageType: '', breath: '' });

  function handleConfirm() {
    if (!selected) return;
    applyChoices.mutate(
      { characterId: character.id, choices: { choiceId: choice.id, draconicAncestry: selected } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  return (
    <Modal
      title="Choose a Draconic Ancestry"
      subtitle={`${character.race} (level ${choice.characterLevel})`}
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
        <p className="font-body text-[13px] font-semibold text-muted">
          Your breath weapon's damage and shape, and the damage you resist, follow the dragon.
        </p>
        {applyChoices.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(applyChoices.error)}</div>
        )}
        {offered.map(ancestry => {
          const isSelected = selected === ancestry.dragon;
          return (
            <button
              key={ancestry.dragon}
              type="button"
              onClick={() => setSelected(ancestry.dragon)}
              aria-pressed={isSelected}
              className={`w-full flex items-start gap-3 px-4 py-3 border text-left transition-colors ${
                isSelected ? 'border-2 border-ink bg-page-alt' : 'border-rule bg-card hover:border-rule-light'
              }`}
            >
              <div className={`w-6 h-6 rounded flex items-center justify-center shrink-0 ${isSelected ? 'bg-ink' : 'bg-page-alt'}`}>
                {isSelected && <Check className="w-4 h-4 text-card" />}
              </div>
              <div className="min-w-0">
                <span className="font-heading text-[16px] font-semibold text-ink">{ancestry.dragon}</span>
                {ancestry.damageType && (
                  <p className="font-body text-[13px] font-semibold text-muted mt-0.5">
                    {ancestry.damageType} damage and resistance; breath: {ancestry.breath}
                  </p>
                )}
              </div>
            </button>
          );
        })}
      </div>
    </Modal>
  );
}
