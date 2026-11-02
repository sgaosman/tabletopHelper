import { useState } from 'react';
import { Check } from 'lucide-react';
import type { PlayerCharacter } from '../../types/character';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  character: PlayerCharacter;
  count: number;
  onComplete: (skills: string[]) => void;
  onClose: () => void;
}

export default function ExpertiseModal({ character, count, onComplete, onClose }: Props) {
  const [selected, setSelected] = useState<string[]>([]);

  const proficientSkills: string[] = character.skillProficiencies ? JSON.parse(character.skillProficiencies) : [];
  const existingExpertise: string[] = character.skillExpertises ? JSON.parse(character.skillExpertises) : [];
  const available = proficientSkills.filter(s => !existingExpertise.includes(s));

  function toggle(skill: string) {
    setSelected(prev =>
      prev.includes(skill) ? prev.filter(s => s !== skill) : prev.length < count ? [...prev, skill] : prev
    );
  }

  return (
    <Modal
      title="Choose Expertise"
      subtitle={<>Select {count} skill{count > 1 ? 's' : ''} to double your proficiency bonus ({selected.length}/{count})</>}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose}>Skip</Button>
          <Button onClick={() => onComplete(selected)} disabled={selected.length !== count}>Confirm Expertise</Button>
        </>
      )}
    >
      <div className="space-y-2">
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
                <span className={`font-heading text-[16px] font-semibold ${isSelected ? 'text-ink' : 'text-ink'}`}>
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
