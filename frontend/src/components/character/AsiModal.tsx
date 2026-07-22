import { useState } from 'react';
import { X, Plus, Minus } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import type { PlayerCharacter, ApplyChoicesRequest, AsiChoice } from '../../types/character';
import FeatPicker from './FeatPicker';

const ABILITIES = [
  { key: 'strength', label: 'STR', full: 'Strength' },
  { key: 'dexterity', label: 'DEX', full: 'Dexterity' },
  { key: 'constitution', label: 'CON', full: 'Constitution' },
  { key: 'intelligence', label: 'INT', full: 'Intelligence' },
  { key: 'wisdom', label: 'WIS', full: 'Wisdom' },
  { key: 'charisma', label: 'CHA', full: 'Charisma' },
] as const;

interface Props {
  character: PlayerCharacter;
  onComplete: (updated: PlayerCharacter) => void;
  onClose: () => void;
}

export default function AsiModal({ character, onComplete, onClose }: Props) {
  const [mode, setMode] = useState<'ability' | 'feat'>('ability');
  const [increases, setIncreases] = useState<Record<string, number>>({});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [featSelection, setFeatSelection] = useState<AsiChoice | null>(null);

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

  const canSubmit = mode === 'ability' ? pointsSpent === 2 : featSelection !== null;

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
      } else if (featSelection) {
        request.asi = featSelection;
      }
      const res = await characterApi.applyChoices(character.id, request);
      onComplete(res.data);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to apply choices');
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-labelledby="asi-title" className="bg-card border border-rule w-full max-w-lg max-h-[90vh] flex flex-col shadow-lg" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-rule flex items-center justify-between shrink-0">
          <div>
            <h2 id="asi-title" className="font-heading text-[17px] font-bold text-ink">Ability Score Improvement</h2>
            <p className="font-body text-[11px] font-medium text-muted mt-0.5">Level {character.level}</p>
          </div>
          <button onClick={onClose} aria-label="Close" className="text-muted hover:text-ink"><X className="w-5 h-5" /></button>
        </div>

        <div className="px-5 py-4 space-y-4 overflow-y-auto flex-1 min-h-0">
          {error && (
            <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[13px] font-medium">{error}</div>
          )}

          <div className="flex gap-2">
            <button
              onClick={() => { setMode('ability'); setFeatSelection(null); }}
              className={`flex-1 py-2 font-body text-[14px] font-medium transition-colors ${
                mode === 'ability' ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:text-ink'
              }`}
            >Ability Scores</button>
            <button
              onClick={() => { setMode('feat'); setIncreases({}); }}
              className={`flex-1 py-2 font-body text-[14px] font-medium transition-colors ${
                mode === 'feat' ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:text-ink'
              }`}
            >Feat</button>
          </div>

          {mode === 'ability' && (
            <>
              <p className="font-body text-[13px] font-medium text-muted">
                Distribute 2 points across your ability scores. No score can exceed 20.
              </p>
              <div className="space-y-2">
                {ABILITIES.map(({ key, label }) => {
                  const base = character[key as keyof PlayerCharacter] as number;
                  const bonus = increases[key] || 0;
                  const atMax = base + bonus >= 20;
                  return (
                    <div key={key} className="flex items-center justify-between bg-page-alt px-4 py-2">
                      <div className="flex items-center gap-3">
                        <span className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted w-8">{label}</span>
                        <span className="font-heading text-[17px] font-bold text-ink">{base}</span>
                        {bonus > 0 && <span className="font-body text-[13px] font-medium text-buff">+{bonus}</span>}
                      </div>
                      <div className="flex items-center gap-1">
                        <button onClick={() => adjustAbility(key, -1)} disabled={bonus <= 0}
                          className="p-1 hover:bg-rule disabled:opacity-30 disabled:cursor-not-allowed text-muted">
                          <Minus className="w-4 h-4" />
                        </button>
                        <button onClick={() => adjustAbility(key, 1)} disabled={atMax || pointsRemaining <= 0}
                          className="p-1 hover:bg-rule disabled:opacity-30 disabled:cursor-not-allowed text-muted">
                          <Plus className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
              <p className="text-center font-body text-[13px] font-medium">
                <span className={pointsRemaining === 0 ? 'text-buff' : 'text-cls-monk'}>
                  {pointsRemaining} point{pointsRemaining !== 1 ? 's' : ''} remaining
                </span>
              </p>
            </>
          )}

          {mode === 'feat' && (
            <FeatPicker character={character} onSelectionChange={setFeatSelection} />
          )}
        </div>

        <div className="px-5 py-4 border-t border-rule flex justify-end gap-3 shrink-0">
          <button onClick={onClose} className="px-4 py-2 bg-page-alt border border-rule text-muted font-body text-[13px] font-medium hover:bg-rule transition-colors">
            Skip
          </button>
          <button
            onClick={handleSubmit}
            disabled={!canSubmit || submitting}
            className="px-5 py-2 bg-ink text-card font-body text-[14px] font-medium hover:opacity-90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? 'Applying...' : 'Apply'}
          </button>
        </div>
      </div>
    </div>
  );
}
