import { useState } from 'react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import type { PendingChoice, PlayerCharacter } from '../../types/character';
import Button from '../ui/Button';
import Modal from '../ui/Modal';
import SpellPicker from './SpellPicker';

interface Props {
  character: PlayerCharacter;
  /** A SUBCLASS_CANTRIP Pending Choice: its options by name, or the spell list it chooses from */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();

/**
 * A cantrip a subclass lets the player choose when it is taken, through the SpellPicker: one of the choice's options,
 * as an Arcane Archer's prestidigitation or druidcraft (XGE p.28) or a Path of the Giant barbarian's druidcraft or
 * thaumaturgy (BGG p.11), or any cantrip on its spell list, as a Nature Domain cleric's druid cantrip (PHB p.61). A
 * cantrip the character knows already, from any source, can't be chosen; with fewer options left than the count, the
 * rest are taken. The cantrip doesn't count against the class's cantrips known, and the server records it on the
 * level that owes it, so levelling down forgets it.
 */
export default function SubclassCantripModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const [selected, setSelected] = useState<string[]>([]);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;

  const known = (name: string) => (character.spellsKnown ?? []).some(spell => sameName(spell.name, name));
  const options = choice.options ?? null;
  const needed = options ? Math.min(choice.count, options.filter(option => !known(option)).length) : choice.count;

  function handleConfirm() {
    if (selected.length !== needed) return;
    applyChoices.mutate(
      { characterId: character.id, choices: { choiceId: choice.id, spells: selected } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  const from = options ? options.join(' or ') : `the ${choice.spellList ?? ''} spell list`;

  return (
    <Modal
      title={needed === 1 ? 'Choose a Cantrip' : `Choose ${needed} Cantrips`}
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
        <p className="font-body text-[14px] font-semibold text-muted">
          Your subclass teaches you a cantrip from {from}. It doesn't count against your class's cantrips known.
        </p>
        {needed === 0 ? (
          <p className="font-body text-[14px] font-semibold text-muted">
            You know every cantrip offered already, so there is nothing to choose. Confirm to finish the choice.
          </p>
        ) : (
          <SpellPicker
            value={selected}
            onChange={setSelected}
            max={needed}
            filter={{ className: options ? undefined : choice.spellList ?? undefined, levels: [0], names: options ?? undefined }}
            statusOf={spell => known(spell.name) ? { status: 'Known', disabled: true } : undefined}
            placeholder="Search cantrips..."
            emptyText="No cantrips found."
          />
        )}
      </div>
    </Modal>
  );
}
