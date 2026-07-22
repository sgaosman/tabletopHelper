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
    <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-labelledby="expertise-title" className="bg-card border border-rule w-full max-w-md max-h-[85vh] flex flex-col shadow-lg" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-rule flex items-center justify-between">
          <div>
            <h2 id="expertise-title" className="font-heading text-[17px] font-bold text-ink">Choose Expertise</h2>
            <p className="font-body text-[11px] font-medium text-muted mt-0.5">
              Select {count} skill{count > 1 ? 's' : ''} to double your proficiency bonus ({selected.length}/{count})
            </p>
          </div>
          <button onClick={onClose} aria-label="Close" className="text-muted hover:text-ink"><X className="w-5 h-5" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-2">
          {available.length === 0 ? (
            <p className="font-body text-[13px] text-faint text-center py-4">No eligible skills for expertise.</p>
          ) : (
            available.map(skill => {
              const isSelected = selected.includes(skill);
              return (
                <button
                  key={skill}
                  onClick={() => toggle(skill)}
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
                  <span className={`font-heading text-[14px] font-semibold ${isSelected ? 'text-ink' : 'text-ink'}`}>
                    {skill}
                  </span>
                </button>
              );
            })
          )}
        </div>

        <div className="px-5 py-4 border-t border-rule flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 bg-page-alt border border-rule text-muted font-body text-[13px] font-medium hover:bg-rule transition-colors">
            Skip
          </button>
          <button
            onClick={() => onComplete(selected)}
            disabled={selected.length !== count}
            className="px-5 py-2 bg-ink text-card font-body text-[14px] font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed hover:opacity-90"
          >
            Confirm Expertise
          </button>
        </div>
      </div>
    </div>
  );
}
