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
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-gray-900 border border-gray-800 rounded-xl w-full max-w-md max-h-[85vh] flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-gray-800 flex items-center justify-between">
          <div>
            <h2 className="text-white font-bold text-lg">Choose Subclass</h2>
            <p className="text-gray-400 text-xs mt-0.5">{className} specialization</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {error && (
            <div className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 text-sm">{error}</div>
          )}

          {loading ? (
            <p className="text-gray-400 text-sm text-center py-8">Loading subclasses...</p>
          ) : (
            <div className="space-y-2">
              {subclasses.map(sc => (
                <button
                  key={sc.id}
                  onClick={() => setSelectedId(sc.id)}
                  className={`w-full flex items-center gap-3 px-4 py-3 rounded-lg border text-left transition-colors ${
                    selectedId === sc.id
                      ? 'border-indigo-500 bg-indigo-900/20'
                      : 'border-gray-800 bg-gray-900 hover:border-gray-600'
                  }`}
                >
                  <div className="flex-1 min-w-0">
                    <span className="text-white font-medium text-sm">{sc.name}</span>
                    <p className="text-gray-500 text-xs mt-0.5">{sc.source}</p>
                  </div>
                  <ChevronRight className={`w-4 h-4 ${selectedId === sc.id ? 'text-indigo-400' : 'text-gray-600'}`} />
                </button>
              ))}
            </div>
          )}

          {selected && selectedFeatures.length > 0 && (
            <div className="bg-gray-800/50 border border-gray-700 rounded-lg p-4 space-y-2">
              <h4 className="text-gray-400 text-xs font-medium uppercase tracking-wider">Features at current level</h4>
              {selectedFeatures.map((f, i) => (
                <div key={i}>
                  <p className="text-white text-sm font-medium">{f.name}</p>
                  <p className="text-gray-400 text-xs mt-0.5 line-clamp-2">{f.description}</p>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="px-5 py-4 border-t border-gray-800 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors">
            Skip
          </button>
          <button
            onClick={handleConfirm}
            disabled={!selectedId || submitting}
            className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? 'Selecting...' : 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
}
