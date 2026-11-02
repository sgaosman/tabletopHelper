import { useState } from 'react';
import { ChevronRight } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import { useSubclasses } from '../../queries/reference';
import type { PlayerCharacter } from '../../types/character';
import { safeJsonParse } from '../../utils/dndRules';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  character: PlayerCharacter;
  classId: string;
  className: string;
  onComplete: (updated: PlayerCharacter) => void;
  onClose: () => void;
}

/** A feature in a subclass's features, which arrive as JSON. */
interface SubclassFeature {
  name: string;
  level: number;
  description: string;
}

/** Picks the subclass of one of a character's classes. The updated character replaces the cached one. */
export default function SubclassModal({ character, classId, className, onComplete, onClose }: Props) {
  const subclassesQuery = useSubclasses(classId);
  const applyChoices = useApplyChoices();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const subclasses = subclassesQuery.data ?? [];
  const loading = subclassesQuery.isPending;
  // Stays disabled once the subclass is chosen, until the sheet closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;
  const error = applyChoices.error ?? subclassesQuery.error;

  function handleConfirm() {
    if (!selectedId) return;
    applyChoices.mutate(
      { characterId: character.id, choices: { subclassId: selectedId, classId } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  const selected = subclasses.find(s => s.id === selectedId);
  // The server sends the features as JSON rather than a string, so they go through safeJsonParse
  const selectedFeatures = selected
    ? safeJsonParse<SubclassFeature[]>(selected.features, []).filter(f => f.level <= character.level)
    : [];

  return (
    <Modal
      title="Choose Subclass"
      subtitle={`${className} specialization`}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose}>Skip</Button>
          <Button onClick={handleConfirm} loading={submitting} disabled={!selectedId}>
            {submitting ? 'Selecting...' : 'Confirm'}
          </Button>
        </>
      )}
    >
      <div className="space-y-4">
        {error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(error)}</div>
        )}

        {loading ? (
          <p className="text-muted font-body text-[15px] font-semibold text-center py-8">Loading subclasses...</p>
        ) : (
          <div className="space-y-2">
            {subclasses.map(sc => (
              <button
                key={sc.id}
                type="button"
                onClick={() => setSelectedId(sc.id)}
                aria-pressed={selectedId === sc.id}
                className={`w-full flex items-center gap-3 px-4 py-3 border text-left transition-colors ${
                  selectedId === sc.id
                    ? 'border-2 border-ink bg-page-alt'
                    : 'border-rule bg-card hover:border-rule-light'
                }`}
              >
                <div className="flex-1 min-w-0">
                  <span className="font-heading text-[16px] font-semibold text-ink">{sc.name}</span>
                  <p className="font-body text-[13px] font-semibold text-faint mt-0.5">{sc.source}</p>
                </div>
                <ChevronRight className={`w-4 h-4 ${selectedId === sc.id ? 'text-ink' : 'text-faint'}`} />
              </button>
            ))}
          </div>
        )}

        {selected && selectedFeatures.length > 0 && (
          <div className="bg-page-alt border border-rule p-4 space-y-2">
            <h4 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">Features at current level</h4>
            {selectedFeatures.map((f, i) => (
              <div key={i}>
                <p className="font-heading text-[16px] font-semibold text-ink">{f.name}</p>
                <p className="font-body text-[13px] font-semibold text-muted mt-0.5 line-clamp-2">{f.description}</p>
              </div>
            ))}
          </div>
        )}
      </div>
    </Modal>
  );
}
