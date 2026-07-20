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
      <h2 className="text-xl font-semibold text-white">Ability Scores</h2>

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
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              abilityMethod === m ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
            }`}
          >
            {m === 'standard' ? 'Standard Array' : m === 'pointbuy' ? 'Point Buy' : 'Manual'}
          </button>
        ))}
      </div>

      {abilityMethod === 'standard' && (
        <div>
          <p className="text-gray-400 text-sm mb-4">Assign each value to an ability: {STANDARD_ARRAY.join(', ')}</p>
          <StandardArrayAssigner assignments={standardAssignments} onChange={setStandardAssignments} racialBonuses={racialBonuses} />
        </div>
      )}

      {abilityMethod === 'pointbuy' && (
        <div>
          <p className="text-gray-400 text-sm mb-2">Points spent: <span className={pointBuyTotal > 27 ? 'text-red-400' : 'text-cyan-400'}>{pointBuyTotal}/27</span></p>
          {pointBuyTotal < 27 && pointBuyTotal > 0 && (
            <p className="text-amber-400 text-xs mb-2">You have {27 - pointBuyTotal} unspent point{27 - pointBuyTotal !== 1 ? 's' : ''}. You can still proceed.</p>
          )}
          <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
            {ABILITIES.map(a => (
              <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
                <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
                <div className="flex items-center justify-center gap-3">
                  <button
                    onClick={() => setScores(s => ({ ...s, [a]: Math.max(8, s[a] - 1) }))}
                    className="w-8 h-8 rounded bg-gray-800 text-gray-400 hover:bg-gray-700 flex items-center justify-center"
                  >-</button>
                  <span className="text-white text-xl font-bold w-8 text-center">{scores[a]}</span>
                  <button
                    onClick={() => setScores(s => ({ ...s, [a]: Math.min(15, s[a] + 1) }))}
                    className="w-8 h-8 rounded bg-gray-800 text-gray-400 hover:bg-gray-700 flex items-center justify-center"
                  >+</button>
                </div>
                {racialBonuses[a] && (
                  <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
                )}
                <p className="text-gray-500 text-xs mt-1">
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
            <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
              <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
              <input
                type="number"
                min={1}
                max={30}
                value={scores[a]}
                onChange={e => setScores(s => ({ ...s, [a]: Math.min(30, Math.max(1, parseInt(e.target.value) || 1)) }))}
                className="w-full text-center text-xl font-bold bg-gray-800 border border-gray-700 rounded-lg py-2 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
              {racialBonuses[a] && (
                <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
              )}
              <p className="text-gray-500 text-xs mt-1">
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
        <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
          <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
          <select
            value={assignments[a] ?? ''}
            onChange={e => {
              const val = e.target.value ? parseInt(e.target.value) : null;
              onChange({ ...assignments, [a]: val });
            }}
            className="w-full text-center text-lg font-bold bg-gray-800 border border-gray-700 rounded-lg py-2 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
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
            <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
          )}
          {assignments[a] !== null && (
            <p className="text-gray-500 text-xs mt-1">
              Final: {(assignments[a] ?? 0) + (racialBonuses[a] || 0)} ({formatMod(abilityMod((assignments[a] ?? 0) + (racialBonuses[a] || 0)))})
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
