import { useState, useEffect } from 'react';
import { X, ChevronRight } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import { getSubclasses } from '../../api/referenceApi';
import type { PlayerCharacter } from '../../types/character';
import type { Subclass } from '../../types/reference';

interface Props {
  character: PlayerCharacter;
  classId: string;
  className: string;
  onComplete: (updated: PlayerCharacter) => void;
  onClose: () => void;
}

export default function SubclassModal({ character, classId, className, onComplete, onClose }: Props) {
  const [subclasses, setSubclasses] = useState<Subclass[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    getSubclasses(classId)
      .then(data => setSubclasses(data))
      .catch(() => setError('Failed to load subclasses'))
      .finally(() => setLoading(false));
  }, [classId]);

  async function handleConfirm() {
    if (!selectedId) return;
    setSubmitting(true);
    setError('');
    try {
      const res = await characterApi.applyChoices(character.id, { subclassId: selectedId, classId });
      onComplete(res.data);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to select subclass');
      setSubmitting(false);
    }
  }

  function safeParseFeatures(featuresJson: string | null): Array<{ name: string; level: number; description: string }> {
    if (!featuresJson) return [];
    try { return JSON.parse(featuresJson); } catch { return []; }
  }

  const selected = subclasses.find(s => s.id === selectedId);
  const selectedFeatures = selected ? safeParseFeatures(selected.features).filter(f => f.level <= character.level) : [];

  return (
    <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-labelledby="subclass-title" className="bg-card border border-rule w-full max-w-md max-h-[85vh] flex flex-col shadow-lg" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-rule flex items-center justify-between">
          <div>
            <h2 id="subclass-title" className="font-heading text-[17px] font-bold text-ink">Choose Subclass</h2>
            <p className="font-body text-[11px] font-medium text-muted mt-0.5">{className} specialization</p>
          </div>
          <button onClick={onClose} aria-label="Close" className="text-muted hover:text-ink"><X className="w-5 h-5" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {error && (
            <div className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[13px] font-medium">{error}</div>
          )}

          {loading ? (
            <p className="text-muted font-body text-[13px] font-medium text-center py-8">Loading subclasses...</p>
          ) : (
            <div className="space-y-2">
              {subclasses.map(sc => (
                <button
                  key={sc.id}
                  onClick={() => setSelectedId(sc.id)}
                  className={`w-full flex items-center gap-3 px-4 py-3 border text-left transition-colors ${
                    selectedId === sc.id
                      ? 'border-2 border-ink bg-page-alt'
                      : 'border-rule bg-card hover:border-rule-light'
                  }`}
                >
                  <div className="flex-1 min-w-0">
                    <span className="font-heading text-[14px] font-semibold text-ink">{sc.name}</span>
                    <p className="font-body text-[11px] font-medium text-faint mt-0.5">{sc.source}</p>
                  </div>
                  <ChevronRight className={`w-4 h-4 ${selectedId === sc.id ? 'text-ink' : 'text-faint'}`} />
                </button>
              ))}
            </div>
          )}

          {selected && selectedFeatures.length > 0 && (
            <div className="bg-page-alt border border-rule p-4 space-y-2">
              <h4 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted">Features at current level</h4>
              {selectedFeatures.map((f, i) => (
                <div key={i}>
                  <p className="font-heading text-[14px] font-semibold text-ink">{f.name}</p>
                  <p className="font-body text-[11px] font-medium text-muted mt-0.5 line-clamp-2">{f.description}</p>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="px-5 py-4 border-t border-rule flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 bg-page-alt border border-rule text-muted font-body text-[13px] font-medium hover:bg-rule transition-colors">
            Skip
          </button>
          <button
            onClick={handleConfirm}
            disabled={!selectedId || submitting}
            className="px-5 py-2 bg-ink text-card font-body text-[14px] font-medium hover:opacity-90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? 'Selecting...' : 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
}
