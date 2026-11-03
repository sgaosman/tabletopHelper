import { useState } from 'react';
import { ChevronRight, Shield, Lock } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useEligibleClasses, useLevelUp } from '../../queries/characters';
import type { PlayerCharacter, LevelUpResponse } from '../../types/character';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  character: PlayerCharacter;
  /** The level was gained: what the player has to choose for it is among the character's Pending Choices (D159) */
  onComplete: (response: LevelUpResponse) => void;
  onClose: () => void;
}

/** Picks the class of a character's next level. The levelled character replaces the cached one. */
export default function LevelUpModal({ character, onComplete, onClose }: Props) {
  const classesQuery = useEligibleClasses(character.id);
  const levelUp = useLevelUp();
  const [pickedClassId, setPickedClassId] = useState<string | null>(null);

  const classes = classesQuery.data ?? [];
  const loading = classesQuery.isPending;
  // The class the character already has is selected until the player picks another
  const selectedClassId = pickedClassId ?? classes.find(c => c.currentClass)?.classId ?? null;
  // Stays disabled once the level is added, until the sheet closes the dialog
  const submitting = levelUp.isPending || levelUp.isSuccess;
  const error = levelUp.error ?? classesQuery.error;

  function handleConfirm() {
    if (!selectedClassId) return;
    levelUp.mutate(
      { characterId: character.id, classId: selectedClassId },
      { onSuccess: response => onComplete(response) },
    );
  }

  const selected = classes.find(c => c.classId === selectedClassId);
  const newCharLevel = character.level + 1;
  const newClassLevel = selected ? (selected.currentClassLevel + 1) : 1;
  const isMulticlass = selected && !selected.currentClass;

  return (
    <Modal
      title="Level Up"
      subtitle={<>Level {character.level} {'→'} Level {newCharLevel}</>}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button onClick={handleConfirm} loading={submitting} disabled={!selectedClassId || loading}>
            {submitting ? 'Leveling up...' : 'Level Up'}
          </Button>
        </>
      )}
    >
      <div className="space-y-4">
        {error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(error)}</div>
        )}

        {loading ? (
          <p className="text-muted font-body text-[15px] font-semibold text-center py-8">Loading classes...</p>
        ) : (
          <>
            <div>
              <p className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-3">Choose a class for this level</p>
              <div className="space-y-2">
                {classes.map(cls => {
                  const disabled = !cls.meetsPrerequisites;
                  const isSelected = selectedClassId === cls.classId;
                  return (
                    <button
                      key={cls.classId}
                      type="button"
                      onClick={() => !disabled && setPickedClassId(cls.classId)}
                      disabled={disabled}
                      aria-pressed={isSelected}
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
                          <span className={`font-heading text-[16px] font-semibold ${disabled ? 'text-faint' : 'text-ink'}`}>
                            {cls.className}
                          </span>
                          {cls.currentClass && (
                            <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 bg-page-alt text-ink border border-rule">Current</span>
                          )}
                          {cls.currentClassLevel > 0 && (
                            <span className="font-body text-[13px] font-semibold text-faint">Lv {cls.currentClassLevel}</span>
                          )}
                        </div>
                        {!cls.currentClass && (
                          <p className={`font-body text-[13px] font-semibold mt-0.5 ${disabled ? 'text-debuff' : 'text-muted'}`}>
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
                <h4 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-2">Preview</h4>
                <p className="font-body text-[15px] font-semibold text-ink">
                  Take <span className="font-bold text-ink">{selected.className}</span> level {newClassLevel}
                </p>
                {isMulticlass && (
                  <p className="font-body text-[13px] font-semibold text-cls-monk mt-1">
                    This will multiclass your character into {selected.className}.
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </Modal>
  );
}
