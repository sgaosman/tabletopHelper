import { useState, useMemo } from 'react';
import { X, Plus, Minus } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import type { PlayerCharacter, ApplyChoicesRequest } from '../../types/character';

const ABILITIES = [
  { key: 'strength', label: 'STR' },
  { key: 'dexterity', label: 'DEX' },
  { key: 'constitution', label: 'CON' },
  { key: 'intelligence', label: 'INT' },
  { key: 'wisdom', label: 'WIS' },
  { key: 'charisma', label: 'CHA' },
] as const;

interface Props {
  character: PlayerCharacter;
  onComplete: (updated: PlayerCharacter) => void;
  onClose: () => void;
}

export default function AsiModal({ character, onComplete, onClose }: Props) {
  const [mode, setMode] = useState<'ability' | 'feat'>('ability');
  const [increases, setIncreases] = useState<Record<string, number>>({});
  const [featName, setFeatName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const pointsSpent = Object.values(increases).reduce((a, b) => a + b, 0);
  const pointsRemaining = 2 - pointsSpent;

  function adjustAbility(ability: string, delta: number) {
    const current = increases[ability] || 0;
    const newVal = current + delta;
    const currentScore = character[ability as keyof PlayerCharacter] as number;

    if (newVal < 0 || newVal > 2) return;
    if (currentScore + newVal > 20) return;
    if (delta > 0 && pointsRemaining <= 0) return;

    const updated = { ...increases };
    if (newVal === 0) delete updated[ability];
    else updated[ability] = newVal;
    setIncreases(updated);
  }

  const canSubmit = useMemo(() => {
    if (mode === 'ability') return pointsSpent === 2;
    if (mode === 'feat') return featName.trim().length > 0;
    return false;
  }, [mode, pointsSpent, featName]);

  async function handleSubmit() {
    setSubmitting(true);
    setError('');
    try {
      const request: ApplyChoicesRequest = {};
      if (mode === 'ability') {
        request.asi = {
          type: 'ability',
          increases: Object.entries(increases).map(([ability, bonus]) => ({ ability, bonus })),
        };
      } else {
        request.asi = {
          type: 'feat',
          featName: featName.trim(),
        };
      }
      const res = await characterApi.applyChoices(character.id, request);
      onComplete(res.data);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to apply choices');
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-gray-900 border border-gray-800 rounded-xl w-full max-w-sm" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-gray-800 flex items-center justify-between">
          <div>
            <h2 className="text-white font-bold text-lg">Ability Score Improvement</h2>
            <p className="text-gray-400 text-xs mt-0.5">Level {character.level}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
        </div>

        <div className="px-5 py-4 space-y-4">
          {error && (
            <div className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 text-sm">{error}</div>
          )}

          <div className="flex gap-2">
            <button
              onClick={() => { setMode('ability'); setFeatName(''); }}
              className={`flex-1 py-2 text-sm font-medium rounded-lg transition-colors ${
                mode === 'ability' ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
              }`}
            >
              Ability Scores
            </button>
            <button
              onClick={() => { setMode('feat'); setIncreases({}); }}
              className={`flex-1 py-2 text-sm font-medium rounded-lg transition-colors ${
                mode === 'feat' ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
              }`}
            >
              Feat
            </button>
          </div>

          {mode === 'ability' && (
            <>
              <p className="text-gray-400 text-xs">
                Distribute 2 points across your ability scores. No score can exceed 20.
              </p>
              <div className="space-y-2">
                {ABILITIES.map(({ key, label }) => {
                  const base = character[key as keyof PlayerCharacter] as number;
                  const bonus = increases[key] || 0;
                  const atMax = base + bonus >= 20;
                  return (
                    <div key={key} className="flex items-center justify-between bg-gray-800 rounded-lg px-4 py-2">
                      <div className="flex items-center gap-3">
                        <span className="text-gray-400 text-xs font-medium w-8">{label}</span>
                        <span className="text-white font-bold">{base}</span>
                        {bonus > 0 && (
                          <span className="text-green-400 text-sm font-medium">+{bonus}</span>
                        )}
                      </div>
                      <div className="flex items-center gap-1">
                        <button
                          onClick={() => adjustAbility(key, -1)}
                          disabled={bonus <= 0}
                          className="p-1 rounded hover:bg-gray-700 disabled:opacity-30 disabled:cursor-not-allowed text-gray-400"
                        >
                          <Minus className="w-4 h-4" />
                        </button>
                        <button
                          onClick={() => adjustAbility(key, 1)}
                          disabled={atMax || pointsRemaining <= 0}
                          className="p-1 rounded hover:bg-gray-700 disabled:opacity-30 disabled:cursor-not-allowed text-gray-400"
                        >
                          <Plus className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
              <p className="text-center text-sm">
                <span className={pointsRemaining === 0 ? 'text-green-400' : 'text-amber-400'}>
                  {pointsRemaining} point{pointsRemaining !== 1 ? 's' : ''} remaining
                </span>
              </p>
            </>
          )}

          {mode === 'feat' && (
            <>
              <p className="text-gray-400 text-xs">
                Enter the name of the feat you are taking instead of an ASI.
              </p>
              <input
                value={featName}
                onChange={e => setFeatName(e.target.value)}
                placeholder="Feat name..."
                className="w-full bg-gray-800 border border-gray-700 rounded-lg px-4 py-2 text-white text-sm placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                autoFocus
              />
            </>
          )}
        </div>

        <div className="px-5 py-4 border-t border-gray-800 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors">
            Skip
          </button>
          <button
            onClick={handleSubmit}
            disabled={!canSubmit || submitting}
            className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? 'Applying...' : 'Apply'}
          </button>
        </div>
      </div>
    </div>
  );
}
