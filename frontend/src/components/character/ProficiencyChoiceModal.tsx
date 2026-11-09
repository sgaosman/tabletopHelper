import { useState } from 'react';
import { Check } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import type { PendingChoice, PlayerCharacter } from '../../types/character';
import {
  proficiencyKindTitle, proficiencyOptions, proficiencyTaken, type ProficiencyKind, type ProficiencyOption,
} from '../../utils/pendingChoices';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  character: PlayerCharacter;
  /** A PROFICIENCY Pending Choice: the bonus proficiencies a subclass lets the player choose, with its options by kind */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

/**
 * The bonus proficiencies a subclass lets the player choose when it is taken, such as the College of Lore's three
 * skills (PHB p.54), the Knowledge Domain's two languages and two skills with Expertise (PHB p.59), or the Cavalier's
 * skill or language (XGE p.30). The options are grouped by kind; one the character has already can't be chosen, except
 * that a Knowledge Domain's skill it is proficient in may still gain Expertise. With fewer options left than the count,
 * the rest are taken. The server records the choice on the subclass's level, so levelling down takes it away.
 */
export default function ProficiencyChoiceModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const [selected, setSelected] = useState<string[]>([]);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;

  const options = proficiencyOptions(choice);
  const taken = (option: ProficiencyOption) => proficiencyTaken(character, choice, option);
  const needed = Math.min(choice.count, options.filter(option => !taken(option)).length);
  const groups = options.reduce<Partial<Record<ProficiencyKind, ProficiencyOption[]>>>((byKind, option) => {
    (byKind[option.kind] ??= []).push(option);
    return byKind;
  }, {});

  function toggle(name: string) {
    setSelected(current => current.includes(name)
      ? current.filter(n => n !== name)
      : current.length < needed ? [...current, name] : current);
  }

  function handleConfirm() {
    if (selected.length !== needed) return;
    applyChoices.mutate(
      { characterId: character.id, choices: { choiceId: choice.id, proficiencies: selected } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  const title = needed === 1 ? 'Choose a Proficiency' : `Choose ${needed} Proficiencies`;

  return (
    <Modal
      title={title}
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
      <div className="space-y-4">
        {applyChoices.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(applyChoices.error)}</div>
        )}
        {choice.expertise && (
          <p className="font-body text-[14px] font-semibold text-muted">
            Your proficiency bonus is doubled for ability checks with the skills you choose.
          </p>
        )}
        {needed === 0 && (
          <p className="font-body text-[14px] font-semibold text-muted">
            You have every proficiency offered already, so there is nothing to choose. Confirm to finish the choice.
          </p>
        )}
        {(Object.keys(groups) as ProficiencyKind[]).map(kind => (
          <section key={kind} className="space-y-2">
            <h3 className="font-heading text-[15px] font-semibold text-ink">{proficiencyKindTitle(kind)}</h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {groups[kind]?.map(option => {
                const isSelected = selected.includes(option.name);
                const already = taken(option);
                return (
                  <button
                    key={option.name}
                    type="button"
                    onClick={() => toggle(option.name)}
                    disabled={already || (!isSelected && selected.length >= needed)}
                    aria-pressed={isSelected}
                    className={`w-full flex items-center gap-3 px-3 py-2 border text-left transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                      isSelected ? 'border-2 border-ink bg-page-alt' : 'border-rule bg-card hover:border-rule-light'
                    }`}
                  >
                    <div className={`w-5 h-5 rounded flex items-center justify-center shrink-0 ${isSelected ? 'bg-ink' : 'bg-page-alt'}`}>
                      {isSelected && <Check className="w-3.5 h-3.5 text-card" />}
                    </div>
                    <span className="font-body text-[15px] font-semibold text-ink">{option.name}</span>
                    {already && (
                      <span className="font-body text-[13px] font-semibold text-faint ml-auto">
                        {choice.expertise ? 'Already expert' : 'Already proficient'}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </section>
        ))}
      </div>
    </Modal>
  );
}
