import { useState } from 'react';
import { Plus, Minus } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import type { PlayerCharacter, ApplyChoicesRequest, AsiChoice, PendingChoice } from '../../types/character';
import Button from '../ui/Button';
import IconButton from '../ui/IconButton';
import Modal from '../ui/Modal';
import FeatPicker from './FeatPicker';

const ABILITIES = [
  { key: 'strength', label: 'STR', full: 'Strength' },
  { key: 'dexterity', label: 'DEX', full: 'Dexterity' },
  { key: 'constitution', label: 'CON', full: 'Constitution' },
  { key: 'intelligence', label: 'INT', full: 'Intelligence' },
  { key: 'wisdom', label: 'WIS', full: 'Wisdom' },
  { key: 'charisma', label: 'CHA', full: 'Charisma' },
] as const;

type AbilityKey = typeof ABILITIES[number]['key'];

interface Props {
  character: PlayerCharacter;
  /** An ASI Pending Choice, or a FEAT one, a race's or background's feat at creation (D159) */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

/**
 * An ability score improvement, or a feat in its place: +2 to one score or +1 to two, none above the maximum the
 * server gives it (20, or 24 for STR and CON after Primal Champion). It raises the scores without the DM's
 * adjustments, which may take a score past the maximum (D158). For a FEAT choice only a feat can be taken, one of
 * those the background offers where it lists them. The updated character replaces the cached one.
 */
export default function AsiModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const featOnly = choice.type === 'FEAT';
  const [mode, setMode] = useState<'ability' | 'feat'>(featOnly ? 'feat' : 'ability');
  const [increases, setIncreases] = useState<Record<string, number>>({});
  const [featSelection, setFeatSelection] = useState<AsiChoice | null>(null);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;

  const pointsSpent = Object.values(increases).reduce((a, b) => a + b, 0);
  const pointsRemaining = 2 - pointsSpent;
  const maximum = (ability: AbilityKey) => character.abilityScoreMaximums[ability];

  function adjustAbility(ability: AbilityKey, delta: number) {
    const current = increases[ability] || 0;
    const newVal = current + delta;
    const currentScore = character.baseAbilityScores[ability];
    if (newVal < 0 || newVal > 2) return;
    if (currentScore + newVal > maximum(ability)) return;
    if (delta > 0 && pointsRemaining <= 0) return;
    const updated = { ...increases };
    if (newVal === 0) delete updated[ability];
    else updated[ability] = newVal;
    setIncreases(updated);
  }

  const canSubmit = mode === 'ability' ? pointsSpent === 2 : featSelection !== null;

  function handleSubmit() {
    const request: ApplyChoicesRequest = { choiceId: choice.id };
    if (featOnly && featSelection) {
      request.feat = featSelection;
    } else if (mode === 'ability') {
      request.asi = {
        type: 'ability',
        increases: Object.entries(increases).map(([ability, bonus]) => ({ ability, bonus })),
      };
    } else if (featSelection) {
      request.asi = featSelection;
    }
    applyChoices.mutate({ characterId: character.id, choices: request }, { onSuccess: updated => onComplete(updated) });
  }

  const subtitle = featOnly
    ? choice.source === 'BACKGROUND'
      ? `From your background${character.backgroundName ? ` (${character.backgroundName})` : ''}`
      : `From your race${character.raceName ? ` (${character.raceName})` : ''}`
    : `${choice.className} ${choice.classLevel} (level ${choice.characterLevel})`;

  return (
    <Modal
      title={featOnly ? 'Feat' : 'Ability Score Improvement'}
      subtitle={subtitle}
      size="lg"
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onSkip}>Skip</Button>
          <Button onClick={handleSubmit} loading={submitting} disabled={!canSubmit}>
            {submitting ? 'Applying...' : 'Apply'}
          </Button>
        </>
      )}
    >
      <div className="space-y-4">
        {applyChoices.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(applyChoices.error)}</div>
        )}

        {!featOnly && (
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => { setMode('ability'); setFeatSelection(null); }}
              aria-pressed={mode === 'ability'}
              className={`flex-1 py-2 font-body text-[16px] font-semibold transition-colors ${
                mode === 'ability' ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:text-ink'
              }`}
            >Ability Scores</button>
            <button
              type="button"
              onClick={() => { setMode('feat'); setIncreases({}); }}
              aria-pressed={mode === 'feat'}
              className={`flex-1 py-2 font-body text-[16px] font-semibold transition-colors ${
                mode === 'feat' ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:text-ink'
              }`}
            >Feat</button>
          </div>
        )}

        {mode === 'ability' && (
          <>
            <p className="font-body text-[15px] font-semibold text-muted">
              Distribute 2 points across your ability scores. No score can go above its maximum.
            </p>
            <div className="space-y-2">
              {ABILITIES.map(({ key, label, full }) => {
                const base = character.baseAbilityScores[key];
                const bonus = increases[key] || 0;
                const atMax = base + bonus >= maximum(key);
                // What the DM's adjustments make the score, shown beside the score the improvement raises
                const adjusted = character[key] !== base ? character[key] : null;
                return (
                  <div key={key} className="flex items-center justify-between bg-page-alt px-4 py-2">
                    <div className="flex items-center gap-3">
                      <span className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted w-8">{label}</span>
                      <span className="font-heading text-[17px] font-bold text-ink">{base}</span>
                      {bonus > 0 && <span className="font-body text-[15px] font-semibold text-buff">+{bonus}</span>}
                      {maximum(key) !== 20 && (
                        <span className="font-body text-[13px] font-semibold text-faint">max {maximum(key)}</span>
                      )}
                      {adjusted !== null && (
                        <span className="font-body text-[13px] font-semibold text-faint">{adjusted} with the DM's adjustments</span>
                      )}
                    </div>
                    <div className="flex items-center gap-1">
                      <IconButton label={`Decrease ${full}`} onClick={() => adjustAbility(key, -1)} disabled={bonus <= 0}
                        className="p-1 hover:bg-rule disabled:opacity-30 disabled:cursor-not-allowed text-muted">
                        <Minus className="w-4 h-4" />
                      </IconButton>
                      <IconButton label={`Increase ${full}`} onClick={() => adjustAbility(key, 1)} disabled={atMax || pointsRemaining <= 0}
                        className="p-1 hover:bg-rule disabled:opacity-30 disabled:cursor-not-allowed text-muted">
                        <Plus className="w-4 h-4" />
                      </IconButton>
                    </div>
                  </div>
                );
              })}
            </div>
            <p className="text-center font-body text-[15px] font-semibold">
              <span className={pointsRemaining === 0 ? 'text-buff' : 'text-cls-monk'}>
                {pointsRemaining} point{pointsRemaining !== 1 ? 's' : ''} remaining
              </span>
            </p>
          </>
        )}

        {mode === 'feat' && (
          <FeatPicker character={character} onSelectionChange={setFeatSelection} only={featOnly ? choice.options : null} />
        )}
      </div>
    </Modal>
  );
}
