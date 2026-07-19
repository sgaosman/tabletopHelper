import { useState } from 'react';
import { X, Check } from 'lucide-react';
import type { PlayerCharacter } from '../../types/character';

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
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-gray-900 border border-gray-800 rounded-xl w-full max-w-md max-h-[85vh] flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-gray-800 flex items-center justify-between">
          <div>
            <h2 className="text-white font-bold text-lg">Choose Expertise</h2>
            <p className="text-gray-400 text-xs mt-0.5">
              Select {count} skill{count > 1 ? 's' : ''} to double your proficiency bonus ({selected.length}/{count})
            </p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-2">
          {available.length === 0 ? (
            <p className="text-gray-400 text-sm text-center py-4">No eligible skills for expertise.</p>
          ) : (
            available.map(skill => {
              const isSelected = selected.includes(skill);
              return (
                <button
                  key={skill}
                  onClick={() => toggle(skill)}
                  className={`w-full flex items-center gap-3 px-4 py-3 rounded-lg border text-left transition-colors ${
                    isSelected
                      ? 'border-yellow-500 bg-yellow-900/20'
                      : 'border-gray-800 bg-gray-900 hover:border-gray-600'
                  }`}
                >
                  <div className={`w-6 h-6 rounded flex items-center justify-center ${
                    isSelected ? 'bg-yellow-600' : 'bg-gray-800'
                  }`}>
                    {isSelected && <Check className="w-4 h-4 text-white" />}
                  </div>
                  <span className={`text-sm font-medium ${isSelected ? 'text-yellow-200' : 'text-white'}`}>
                    {skill}
                  </span>
                </button>
              );
            })
          )}
        </div>

        <div className="px-5 py-4 border-t border-gray-800 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors">
            Skip
          </button>
          <button
            onClick={() => onComplete(selected)}
            disabled={selected.length !== count}
            className="px-5 py-2 bg-yellow-600 hover:bg-yellow-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Confirm Expertise
          </button>
        </div>
      </div>
    </div>
  );
}
