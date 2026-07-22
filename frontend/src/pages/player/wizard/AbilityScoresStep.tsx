import { ABILITIES, abilityMod, formatMod, ABILITY_ABBR as ABILITY_LABELS } from '../../../utils/dndRules';
import { STANDARD_ARRAY, type AbilityScores } from './types';

export interface AbilityScoresStepProps {
  abilityMethod: 'standard' | 'pointbuy' | 'manual';
  setAbilityMethod: (m: 'standard' | 'pointbuy' | 'manual') => void;
  scores: AbilityScores;
  setScores: React.Dispatch<React.SetStateAction<AbilityScores>>;
  standardAssignments: Record<string, number | null>;
  setStandardAssignments: (a: Record<string, number | null>) => void;
  racialBonuses: Record<string, number>;
  pointBuyTotal: number;
}

export default function AbilityScoresStep({
  abilityMethod, setAbilityMethod,
  scores, setScores,
  standardAssignments, setStandardAssignments,
  racialBonuses, pointBuyTotal,
}: AbilityScoresStepProps) {
  return (
    <div className="space-y-6">
      <h2 className="font-heading text-[15px] font-semibold text-ink">Ability Scores</h2>

      <div className="flex gap-2">
        {(['standard', 'pointbuy', 'manual'] as const).map(m => (
          <button
            key={m}
            onClick={() => {
              setAbilityMethod(m);
              if (m === 'pointbuy') {
                setScores(s => {
                  const clamped = { ...s };
                  for (const a of ABILITIES) clamped[a] = Math.max(8, Math.min(15, s[a]));
                  return clamped;
                });
              }
            }}
            className={`px-4 py-2 font-heading text-[11px] font-semibold tracking-[0.04em] transition-colors border ${
              abilityMethod === m ? 'bg-ink text-card border-ink' : 'bg-card text-muted border-rule hover:border-muted'
            }`}
          >
            {m === 'standard' ? 'Standard Array' : m === 'pointbuy' ? 'Point Buy' : 'Manual'}
          </button>
        ))}
      </div>

      {abilityMethod === 'standard' && (
        <div>
          <p className="font-body text-[13px] font-medium text-muted mb-4">Assign each value to an ability: {STANDARD_ARRAY.join(', ')}</p>
          <StandardArrayAssigner assignments={standardAssignments} onChange={setStandardAssignments} racialBonuses={racialBonuses} />
        </div>
      )}

      {abilityMethod === 'pointbuy' && (
        <div>
          <p className="font-body text-[13px] font-medium text-muted mb-2">Points spent: <span className={pointBuyTotal > 27 ? 'text-debuff' : 'text-cls-druid'}>{pointBuyTotal}/27</span></p>
          {pointBuyTotal < 27 && pointBuyTotal > 0 && (
            <p className="font-body text-[11px] font-medium text-hp-wounded mb-2">You have {27 - pointBuyTotal} unspent point{27 - pointBuyTotal !== 1 ? 's' : ''}. You can still proceed.</p>
          )}
          <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
            {ABILITIES.map(a => (
              <div key={a} className="bg-card border border-rule p-4 text-center">
                <label className="font-heading text-[9px] font-semibold tracking-[0.1em] uppercase text-faint block mb-2">{ABILITY_LABELS[a]}</label>
                <div className="flex items-center justify-center gap-3">
                  <button
                    onClick={() => setScores(s => ({ ...s, [a]: Math.max(8, s[a] - 1) }))}
                    className="w-8 h-8 bg-page border border-rule text-muted hover:border-muted flex items-center justify-center font-heading text-[14px] font-bold"
                  >-</button>
                  <span className="font-heading text-[17px] font-bold text-ink w-8 text-center">{scores[a]}</span>
                  <button
                    onClick={() => setScores(s => ({ ...s, [a]: Math.min(15, s[a] + 1) }))}
                    className="w-8 h-8 bg-page border border-rule text-muted hover:border-muted flex items-center justify-center font-heading text-[14px] font-bold"
                  >+</button>
                </div>
                {racialBonuses[a] && (
                  <p className="font-body text-[11px] font-medium text-cls-druid mt-1">+{racialBonuses[a]} racial</p>
                )}
                <p className="font-body text-[11px] font-medium text-faint mt-1">
                  Final: {scores[a] + (racialBonuses[a] || 0)} ({formatMod(abilityMod(scores[a] + (racialBonuses[a] || 0)))})
                </p>
              </div>
            ))}
          </div>
        </div>
      )}

      {abilityMethod === 'manual' && (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          {ABILITIES.map(a => (
            <div key={a} className="bg-card border border-rule p-4 text-center">
              <label className="font-heading text-[9px] font-semibold tracking-[0.1em] uppercase text-faint block mb-2">{ABILITY_LABELS[a]}</label>
              <input
                type="number"
                min={1}
                max={30}
                value={scores[a]}
                onChange={e => setScores(s => ({ ...s, [a]: Math.min(30, Math.max(1, parseInt(e.target.value) || 1)) }))}
                className="w-full text-center font-heading text-[17px] font-bold bg-page border border-rule py-2 text-ink focus:border-muted focus:outline-none"
              />
              {racialBonuses[a] && (
                <p className="font-body text-[11px] font-medium text-cls-druid mt-1">+{racialBonuses[a]} racial</p>
              )}
              <p className="font-body text-[11px] font-medium text-faint mt-1">
                Final: {scores[a] + (racialBonuses[a] || 0)} ({formatMod(abilityMod(scores[a] + (racialBonuses[a] || 0)))})
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function StandardArrayAssigner({
  assignments,
  onChange,
  racialBonuses,
}: {
  assignments: Record<string, number | null>;
  onChange: (a: Record<string, number | null>) => void;
  racialBonuses: Record<string, number>;
}) {
  const usedValues = Object.values(assignments).filter((v): v is number => v !== null);
  const available = STANDARD_ARRAY.filter(v => {
    const usedCount = usedValues.filter(u => u === v).length;
    const totalCount = STANDARD_ARRAY.filter(a => a === v).length;
    return usedCount < totalCount;
  });

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
      {ABILITIES.map(a => (
        <div key={a} className="bg-card border border-rule p-4 text-center">
          <label className="font-heading text-[9px] font-semibold tracking-[0.1em] uppercase text-faint block mb-2">{ABILITY_LABELS[a]}</label>
          <select
            value={assignments[a] ?? ''}
            onChange={e => {
              const val = e.target.value ? parseInt(e.target.value) : null;
              onChange({ ...assignments, [a]: val });
            }}
            className="w-full text-center font-heading text-[15px] font-bold bg-page border border-rule py-2 text-ink focus:border-muted focus:outline-none"
          >
            <option value="">—</option>
            {STANDARD_ARRAY.map(v => {
              const isCurrentValue = assignments[a] === v;
              const isAvailable = available.includes(v) || isCurrentValue;
              return (
                <option key={`${a}-${v}`} value={v} disabled={!isAvailable}>{v}</option>
              );
            })}
          </select>
          {racialBonuses[a] && (
            <p className="font-body text-[11px] font-medium text-cls-druid mt-1">+{racialBonuses[a]} racial</p>
          )}
          {assignments[a] !== null && (
            <p className="font-body text-[11px] font-medium text-faint mt-1">
              Final: {(assignments[a] ?? 0) + (racialBonuses[a] || 0)} ({formatMod(abilityMod((assignments[a] ?? 0) + (racialBonuses[a] || 0)))})
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
