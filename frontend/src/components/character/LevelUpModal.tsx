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
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-gray-900 border border-gray-800 rounded-xl w-full max-w-md max-h-[85vh] flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-gray-800 flex items-center justify-between">
          <div>
            <h2 className="text-white font-bold text-lg">Level Up</h2>
            <p className="text-gray-400 text-xs mt-0.5">
              Level {character.level} {'→'} Level {newCharLevel}
            </p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {error && (
            <div className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 text-sm">{error}</div>
          )}

          {loading ? (
            <p className="text-gray-400 text-sm text-center py-8">Loading classes...</p>
          ) : (
            <>
              <div>
                <p className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Choose a class for this level</p>
                <div className="space-y-2">
                  {classes.map(cls => {
                    const disabled = !cls.meetsPrerequisites;
                    const isSelected = selectedClassId === cls.classId;
                    return (
                      <button
                        key={cls.classId}
                        onClick={() => !disabled && setSelectedClassId(cls.classId)}
                        disabled={disabled}
                        className={`w-full flex items-center gap-3 px-4 py-3 rounded-lg border text-left transition-colors ${
                          disabled
                            ? 'border-gray-800 bg-gray-900/50 opacity-50 cursor-not-allowed'
                            : isSelected
                              ? 'border-indigo-500 bg-indigo-900/20'
                              : 'border-gray-800 bg-gray-900 hover:border-gray-600'
                        }`}
                      >
                        <div className={`w-8 h-8 rounded-full flex items-center justify-center ${
                          disabled ? 'bg-gray-800' : isSelected ? 'bg-indigo-600' : 'bg-gray-800'
                        }`}>
                          {disabled ? (
                            <Lock className="w-4 h-4 text-gray-500" />
                          ) : (
                            <Shield className="w-4 h-4 text-white" />
                          )}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <span className={`font-medium text-sm ${disabled ? 'text-gray-500' : 'text-white'}`}>
                              {cls.className}
                            </span>
                            {cls.currentClass && (
                              <span className="text-xs px-1.5 py-0.5 bg-indigo-900/50 text-indigo-300 rounded">Current</span>
                            )}
                            {cls.currentClassLevel > 0 && (
                              <span className="text-xs text-gray-500">Lv {cls.currentClassLevel}</span>
                            )}
                          </div>
                          {!cls.currentClass && (
                            <p className={`text-xs mt-0.5 ${disabled ? 'text-red-400/70' : 'text-gray-400'}`}>
                              {cls.prerequisiteDescription}
                            </p>
                          )}
                        </div>
                        {!disabled && (
                          <ChevronRight className={`w-4 h-4 ${isSelected ? 'text-indigo-400' : 'text-gray-600'}`} />
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>

              {selected && (
                <div className="bg-gray-800/50 border border-gray-700 rounded-lg p-4">
                  <h4 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-2">Preview</h4>
                  <p className="text-white text-sm">
                    Take <span className="font-bold text-indigo-300">{selected.className}</span> level {newClassLevel}
                  </p>
                  {isMulticlass && (
                    <p className="text-amber-400 text-xs mt-1">
                      This will multiclass your character into {selected.className}.
                    </p>
                  )}
                </div>
              )}
            </>
          )}
        </div>

        <div className="px-5 py-4 border-t border-gray-800 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors">
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!selectedClassId || submitting || loading}
            className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? 'Leveling up...' : 'Level Up'}
          </button>
        </div>
      </div>
    </div>
  );
}
