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
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Combat Stats</h3>
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
            <div key={i} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
              <h4 className="text-white font-medium text-sm">{f.name}</h4>
              <FormattedDescription text={f.description} className="mt-2" />
            </div>
          ))}
        </div>
      ) : (
        <p className="text-gray-500 text-sm">No combat actions configured. Add features in the Features tab.</p>
      )}
    </div>
  );
}
