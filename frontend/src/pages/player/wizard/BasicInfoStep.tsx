import NumberStepper from '../../../components/common/NumberStepper';
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
      <h2 className="font-heading text-[17px] font-semibold text-ink">Basic Information</h2>
      <div>
        <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">Character Name *</label>
        <input
          type="text"
          value={name}
          onChange={e => setName(e.target.value)}
          maxLength={200}
          autoFocus
          className="w-full max-w-md px-3 py-2 bg-card border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:border-muted focus:outline-none"
          placeholder="Enter character name"
        />
      </div>
      <div>
        <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">Alignment</label>
        <div className="grid grid-cols-3 gap-2 max-w-md">
          {ALIGNMENTS.map(a => (
            <button
              key={a}
              onClick={() => setAlignment(alignment === a ? '' : a)}
              className={`px-3 py-2 font-heading text-[11px] font-semibold tracking-[0.04em] transition-colors border ${
                alignment === a
                  ? 'bg-ink text-card border-ink'
                  : 'bg-card text-muted border-rule hover:border-muted'
              }`}
            >{a}</button>
          ))}
        </div>
      </div>
      <div>
        <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-2">Starting Level</label>
        <div className="flex items-center gap-4 max-w-md">
          <input
            type="range"
            min={1}
            max={20}
            value={level}
            onChange={e => setLevel(Number(e.target.value))}
            aria-label="Starting level slider"
            className="flex-1 accent-ink"
          />
          <NumberStepper value={level} min={1} max={20} onChange={setLevel} label="Starting level" />
        </div>
        <p className="font-body text-[13px] font-semibold text-faint mt-1">
          Proficiency bonus: +{proficiencyBonusForLevel(level)}
          {level > 1 && ` · HP, features, and spell slots auto-calculated`}
        </p>
      </div>
    </div>
  );
}
