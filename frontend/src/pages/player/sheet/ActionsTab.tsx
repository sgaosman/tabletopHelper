import type { PlayerCharacter } from '../../../types/character';
import FormattedDescription from '../../../components/FormattedDescription';
import { abilityMod, formatMod } from '../../../utils/dndRules';
import StatCard from './StatCard';

export default function ActionsTab({ char, features }: {
  char: PlayerCharacter;
  features: Array<{ name: string; description: string; source?: string }>;
}) {
  const actionFeatures = features.filter(f =>
    f.description?.toLowerCase().includes('attack') ||
    f.description?.toLowerCase().includes('action') ||
    f.description?.toLowerCase().includes('damage') ||
    f.source?.toLowerCase().includes('class')
  );

  return (
    <div className="space-y-4">
      <div className="bg-card border border-rule p-4">
        <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Combat Stats</h3>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <StatCard label="Attack Bonus" value={formatMod(abilityMod(char.strength) + char.proficiencyBonus)} sub="STR melee" />
          <StatCard label="Attack Bonus" value={formatMod(abilityMod(char.dexterity) + char.proficiencyBonus)} sub="DEX ranged/finesse" />
          {char.spellSaveDc && <StatCard label="Spell Save DC" value={String(char.spellSaveDc)} />}
          {char.spellAttackBonus != null && <StatCard label="Spell Attack" value={formatMod(char.spellAttackBonus)} />}
        </div>
      </div>

      {actionFeatures.length > 0 ? (
        <div className="space-y-3">
          {actionFeatures.map((f, i) => (
            <div key={i} className="bg-card border border-rule p-4">
              <h4 className="font-heading text-[13px] font-semibold text-ink">{f.name}</h4>
              <FormattedDescription text={f.description} className="mt-2 font-body text-[13px] [&_p]:text-muted [&_strong]:text-ink" />
            </div>
          ))}
        </div>
      ) : (
        <p className="font-body text-[13px] text-faint">No combat actions configured. Add features in the Features tab.</p>
      )}
    </div>
  );
}
