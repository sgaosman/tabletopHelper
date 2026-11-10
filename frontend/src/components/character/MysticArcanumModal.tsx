import { useState } from 'react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import type { PendingChoice, PlayerCharacter } from '../../types/character';
import { mysticArcanumTitle } from '../../utils/mysticArcanum';
import Button from '../ui/Button';
import Modal from '../ui/Modal';
import SpellPicker from './SpellPicker';

interface Props {
  character: PlayerCharacter;
  /** A MYSTIC_ARCANUM Pending Choice: its maxSpellLevel is the arcanum's level, its spellList the class's */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

/**
 * A warlock's Mystic Arcanum (PHB p.108): one warlock spell of exactly the arcanum's level, 6th at Warlock 11 up to 9th
 * at 17, through the SpellPicker, from the warlock's list and its patron's expanded spells, such as the Genie's wish
 * (TCE p.73). The spell is cast once without a slot between long rests and doesn't count against the warlock's spells
 * known; the server records it on the level that owes it, so levelling down forgets it.
 */
export default function MysticArcanumModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const [selected, setSelected] = useState<string[]>([]);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;

  const level = choice.maxSpellLevel ?? 6;
  const className = choice.className ?? 'Warlock';
  const spellList = choice.spellList ?? className;
  const subclassName = character.spellcasting.find(c => c.className === className)?.subclassName;

  function handleConfirm() {
    if (selected.length !== 1) return;
    applyChoices.mutate(
      { characterId: character.id, choices: { choiceId: choice.id, spells: selected } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  return (
    <Modal
      title={mysticArcanumTitle(level)}
      subtitle={`${className} ${choice.classLevel} (level ${choice.characterLevel})`}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onSkip}>Skip</Button>
          <Button onClick={handleConfirm} loading={submitting} disabled={selected.length !== 1}>
            {submitting ? 'Choosing...' : 'Choose Arcanum'}
          </Button>
        </>
      )}
    >
      <div className="space-y-4">
        {applyChoices.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(applyChoices.error)}</div>
        )}
        <p className="font-body text-[14px] font-semibold text-muted">
          Choose one level {level} {className.toLowerCase()} spell. You can cast it once without a spell slot, and
          regain the use when you finish a long rest. It doesn't count against your spells known.
        </p>
        <SpellPicker
          value={selected}
          onChange={setSelected}
          max={1}
          filter={{
            className: spellList,
            subclass: subclassName ? `${className} (${subclassName})` : undefined,
            levels: [level],
          }}
          placeholder={`Search level ${level} spells...`}
          emptyText={`No level ${level} ${className.toLowerCase()} spells found.`}
        />
      </div>
    </Modal>
  );
}
