import { useState, useEffect } from 'react';
import { X, ChevronRight, Shield, Lock } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import type { PlayerCharacter, LevelUpResponse, EligibleClassResponse } from '../../types/character';

interface Props {
  character: PlayerCharacter;
  onComplete: (response: LevelUpResponse, leveledClassId: string, leveledClassName: string) => void;
  onClose: () => void;
}

export default function LevelUpModal({ character, onComplete, onClose }: Props) {
  const [classes, setClasses] = useState<EligibleClassResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedClassId, setSelectedClassId] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    characterApi.getEligibleClasses(character.id)
      .then(res => {
        setClasses(res.data);
        const current = res.data.find(c => c.currentClass);
        if (current) setSelectedClassId(current.classId);
      })
      .catch(() => setError('Failed to load eligible classes'))
      .finally(() => setLoading(false));
  }, [character.id]);

  async function handleConfirm() {
    if (!selectedClassId) return;
    setSubmitting(true);
    setError('');
    try {
      const res = await characterApi.levelUp(character.id, selectedClassId);
      const cls = classes.find(c => c.classId === selectedClassId);
      onComplete(res.data, selectedClassId, cls?.className || '');
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Level up failed');
      setSubmitting(false);
    }
  }

  const selected = classes.find(c => c.classId === selectedClassId);
  const newCharLevel = character.level + 1;
  const newClassLevel = selected ? (selected.currentClassLevel + 1) : 1;
  const isMulticlass = selected && !selected.currentClass;

  return (
    <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-labelledby="levelup-title" className="bg-card border border-rule w-full max-w-md max-h-[85vh] flex flex-col shadow-lg" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-rule flex items-center justify-between">
          <div>
            <h2 id="levelup-title" className="font-heading text-[17px] font-bold text-ink">Level Up</h2>
            <p className="font-body text-[11px] font-medium text-muted mt-0.5">
              Level {character.level} {'→'} Level {newCharLevel}
            </p>
          </div>
          <button onClick={onClose} aria-label="Close" className="text-muted hover:text-ink"><X className="w-5 h-5" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {error && (
            <div className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[13px] font-medium">{error}</div>
          )}

          {loading ? (
            <p className="text-muted font-body text-[13px] font-medium text-center py-8">Loading classes...</p>
          ) : (
            <>
              <div>
                <p className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-3">Choose a class for this level</p>
                <div className="space-y-2">
                  {classes.map(cls => {
                    const disabled = !cls.meetsPrerequisites;
                    const isSelected = selectedClassId === cls.classId;
                    return (
                      <button
                        key={cls.classId}
                        onClick={() => !disabled && setSelectedClassId(cls.classId)}
                        disabled={disabled}
                        className={`w-full flex items-center gap-3 px-4 py-3 border text-left transition-colors ${
                          disabled
                            ? 'border-rule bg-card/50 opacity-50 cursor-not-allowed'
                            : isSelected
                              ? 'border-2 border-ink bg-page-alt'
                              : 'border-rule bg-card hover:border-rule-light'
                        }`}
                      >
                        <div className={`w-8 h-8 rounded-full flex items-center justify-center ${
                          disabled ? 'bg-page-alt' : isSelected ? 'bg-ink' : 'bg-page-alt'
                        }`}>
                          {disabled ? (
                            <Lock className="w-4 h-4 text-faint" />
                          ) : (
                            <Shield className={`w-4 h-4 ${isSelected ? 'text-card' : 'text-ink'}`} />
                          )}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <span className={`font-heading text-[14px] font-semibold ${disabled ? 'text-faint' : 'text-ink'}`}>
                              {cls.className}
                            </span>
                            {cls.currentClass && (
                              <span className="font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 bg-page-alt text-ink border border-rule">Current</span>
                            )}
                            {cls.currentClassLevel > 0 && (
                              <span className="font-body text-[11px] font-medium text-faint">Lv {cls.currentClassLevel}</span>
                            )}
                          </div>
                          {!cls.currentClass && (
                            <p className={`font-body text-[11px] font-medium mt-0.5 ${disabled ? 'text-debuff' : 'text-muted'}`}>
                              {cls.prerequisiteDescription}
                            </p>
                          )}
                        </div>
                        {!disabled && (
                          <ChevronRight className={`w-4 h-4 ${isSelected ? 'text-ink' : 'text-faint'}`} />
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>

              {selected && (
                <div className="bg-page-alt border border-rule p-4">
                  <h4 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-2">Preview</h4>
                  <p className="font-body text-[13px] font-medium text-ink">
                    Take <span className="font-bold text-ink">{selected.className}</span> level {newClassLevel}
                  </p>
                  {isMulticlass && (
                    <p className="font-body text-[11px] font-medium text-cls-monk mt-1">
                      This will multiclass your character into {selected.className}.
                    </p>
                  )}
                </div>
              )}
            </>
          )}
        </div>

        <div className="px-5 py-4 border-t border-rule flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 bg-page-alt border border-rule text-muted font-body text-[13px] font-medium hover:bg-rule transition-colors">
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!selectedClassId || submitting || loading}
            className="px-5 py-2 bg-ink text-card font-body text-[14px] font-medium hover:opacity-90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? 'Leveling up...' : 'Level Up'}
          </button>
        </div>
      </div>
    </div>
  );
}
