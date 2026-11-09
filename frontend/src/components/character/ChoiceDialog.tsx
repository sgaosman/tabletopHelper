import type { PendingChoice, PlayerCharacter } from '../../types/character';
import AsiModal from './AsiModal';
import ExpertiseModal from './ExpertiseModal';
import FightingStyleModal from './FightingStyleModal';
import ManageSpellsModal from './ManageSpellsModal';
import MetamagicModal from './MetamagicModal';
import ProficiencyChoiceModal from './ProficiencyChoiceModal';
import SubclassCantripModal from './SubclassCantripModal';
import SubclassModal from './SubclassModal';

interface Props {
  character: PlayerCharacter;
  choice: PendingChoice;
  /** The choice was made: the character the server answered with */
  onComplete: (updated: PlayerCharacter) => void;
  /** The choice is left pending for later */
  onSkip: () => void;
  /** The dialog was closed, which stops the run of choices */
  onClose: () => void;
}

/**
 * The dialog that makes one Pending Choice (D159), by its type: an Ability Score Improvement or a feat, a subclass,
 * Expertise, a Fighting Style, a sorcerer's Metamagic options (M18), the bonus proficiencies or the cantrip a subclass
 * lets the player choose, or the cantrips and spells a class learns, through the spells dialog's SpellPicker,
 * from the spell list the choice names. Each dialog applies the choice itself, and each can be skipped, leaving the
 * choice on the sheet's banner.
 */
export default function ChoiceDialog({ character, choice, onComplete, onSkip, onClose }: Props) {
  switch (choice.type) {
    case 'ASI':
    case 'FEAT':
      return <AsiModal character={character} choice={choice} onComplete={onComplete} onSkip={onSkip} onClose={onClose} />;
    case 'SUBCLASS':
      return <SubclassModal character={character} choice={choice} onComplete={onComplete} onSkip={onSkip} onClose={onClose} />;
    case 'EXPERTISE':
      return <ExpertiseModal character={character} choice={choice} onComplete={onComplete} onSkip={onSkip} onClose={onClose} />;
    case 'FIGHTING_STYLE':
      return <FightingStyleModal character={character} choice={choice} onComplete={onComplete} onSkip={onSkip} onClose={onClose} />;
    case 'METAMAGIC':
      return <MetamagicModal character={character} choice={choice} onComplete={onComplete} onSkip={onSkip} onClose={onClose} />;
    case 'PROFICIENCY':
      return <ProficiencyChoiceModal character={character} choice={choice} onComplete={onComplete} onSkip={onSkip} onClose={onClose} />;
    case 'SUBCLASS_CANTRIP':
      return <SubclassCantripModal character={character} choice={choice} onComplete={onComplete} onSkip={onSkip} onClose={onClose} />;
    case 'CANTRIPS':
    case 'SPELLS': {
      const className = choice.className ?? '';
      const casting = character.spellcasting.find(c => c.className === className);
      return (
        <ManageSpellsModal
          char={character}
          type="choice"
          className={className}
          casting={casting}
          currentSpells={(character.spellsKnown ?? []).map(s => ({ ...s, source: s.source || `class:${character.characterClass}` }))}
          choice={choice}
          onComplete={onComplete}
          onSkip={onSkip}
          onClose={onClose}
        />
      );
    }
  }
}
