import { proficiencyBonusForLevel } from '../../../utils/spellConstants';
import { ALIGNMENTS } from './types';

export interface BasicInfoStepProps {
  name: string;
  setName: (v: string) => void;
  alignment: string;
  setAlignment: (v: string) => void;
  level: number;
  setLevel: (v: number) => void;
}

export default function BasicInfoStep({
  name, setName, alignment, setAlignment, level, setLevel,
}: BasicInfoStepProps) {
  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold text-white">Basic Information</h2>
      <div>
        <label className="block text-sm font-medium text-gray-300 mb-1">Character Name *</label>
        <input
          type="text"
          value={name}
          onChange={e => setName(e.target.value)}
          maxLength={200}
          autoFocus
          className="w-full max-w-md px-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          placeholder="Enter character name"
        />
      </div>
      <div>
        <label className="block text-sm font-medium text-gray-300 mb-1">Alignment</label>
        <div className="grid grid-cols-3 gap-2 max-w-md">
          {ALIGNMENTS.map(a => (
            <button
              key={a}
              onClick={() => setAlignment(alignment === a ? '' : a)}
              className={`px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                alignment === a ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
              }`}
            >{a}</button>
          ))}
        </div>
      </div>
      <div>
        <label className="block text-sm font-medium text-gray-300 mb-2">Starting Level</label>
        <div className="flex items-center gap-4 max-w-md">
          <input
            type="range"
            min={1}
            max={20}
            value={level}
            onChange={e => setLevel(Number(e.target.value))}
            className="flex-1 accent-indigo-500"
          />
          <span className="text-white font-bold text-lg w-8 text-center">{level}</span>
        </div>
        <p className="text-xs text-gray-500 mt-1">
          Proficiency bonus: +{proficiencyBonusForLevel(level)}
          {level > 1 && ` · HP, features, and spell slots auto-calculated`}
        </p>
      </div>
    </div>
  );
}
