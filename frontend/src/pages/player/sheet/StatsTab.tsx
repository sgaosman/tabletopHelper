import { useMemo } from 'react';
import type { PlayerCharacter } from '../../../types/character';
import { ABILITIES, ABILITY_ABBR, SKILLS, abilityMod, formatMod, safeJsonParse } from '../../../utils/dndRules';
import StatCard from './StatCard';

export default function StatsTab({ char, savingThrows, skillProfs, skillExpertises, resistances }: {
  char: PlayerCharacter;
  savingThrows: string[];
  skillProfs: string[];
  skillExpertises: string[];
  resistances: string[];
}) {
  const armorProfs = useMemo(() => safeJsonParse<string[]>(char.armorProficiencies, []), [char.armorProficiencies]);
  const weaponProfs = useMemo(() => safeJsonParse<string[]>(char.weaponProficiencies, []), [char.weaponProficiencies]);
  const toolProfs = useMemo(() => safeJsonParse<string[]>(char.toolProficiencies, []), [char.toolProficiencies]);
  const languageProfs = useMemo(() => safeJsonParse<string[]>(char.languageProficiencies, []), [char.languageProficiencies]);

  const hasProficiencies = armorProfs.length > 0 || weaponProfs.length > 0 || toolProfs.length > 0 || languageProfs.length > 0;

  return (
    <div className="space-y-6">
      {/* Ability Scores */}
      <div className="grid grid-cols-6 gap-3">
        {ABILITIES.map(ability => {
          const score = char[ability];
          const mod = abilityMod(score);
          return (
            <div key={ability} className="bg-gray-900 border border-gray-800 rounded-xl p-3 text-center">
              <p className="text-gray-500 text-xs font-medium uppercase tracking-wider">{ABILITY_ABBR[ability]}</p>
              <p className="text-white text-2xl font-bold mt-1">{score}</p>
              <p className="text-indigo-400 text-sm font-medium">{formatMod(mod)}</p>
            </div>
          );
        })}
      </div>

      {/* Saving Throws */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Saving Throws</h3>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {ABILITIES.map(ability => {
            const mod = abilityMod(char[ability]);
            const isProficient = savingThrows.some(s => s.toLowerCase() === ABILITY_ABBR[ability].toLowerCase() || s.toLowerCase() === ability);
            const total = mod + (isProficient ? char.proficiencyBonus : 0);
            return (
              <div key={ability} className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${isProficient ? 'bg-green-400' : 'bg-gray-700'}`} />
                <span className="text-gray-400 text-sm">{ABILITY_ABBR[ability]}</span>
                <span className="text-white text-sm ml-auto font-medium">{formatMod(total)}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Skills */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Skills</h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
          {SKILLS.map(skill => {
            const mod = abilityMod(char[skill.ability]);
            const isProficient = skillProfs.some(s => s.toLowerCase() === skill.name.toLowerCase());
            const isExpert = skillExpertises.some(s => s.toLowerCase() === skill.name.toLowerCase());
            const bonus = mod + (isExpert ? char.proficiencyBonus * 2 : isProficient ? char.proficiencyBonus : 0);
            return (
              <div key={skill.name} className="flex items-center gap-2 py-0.5">
                {isExpert ? (
                  <span className="text-yellow-400 text-xs leading-none" style={{ fontSize: '10px' }}>&#9733;</span>
                ) : (
                  <div className={`w-2.5 h-2.5 rounded-full border-2 ${isProficient ? 'bg-green-400 border-green-400' : 'bg-transparent border-gray-600'}`} />
                )}
                <span className={`text-sm flex-1 ${isProficient || isExpert ? 'text-white font-medium' : 'text-gray-400'}`}>
                  {skill.name} <span className="text-gray-600 text-xs">({ABILITY_ABBR[skill.ability]})</span>
                </span>
                <span className={`text-sm font-medium ${isProficient || isExpert ? 'text-white' : 'text-gray-500'}`}>{formatMod(bonus)}</span>
              </div>
            );
          })}
        </div>
        <div className="mt-3 pt-3 border-t border-gray-800 flex gap-4 text-xs text-gray-500">
          <span className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 rounded-full bg-green-400" /> Proficient</span>
          <span className="flex items-center gap-1.5"><span className="text-yellow-400" style={{ fontSize: '10px' }}>&#9733;</span> Expertise</span>
        </div>
      </div>

      {/* Proficiency & Other */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <StatCard label="Proficiency" value={formatMod(char.proficiencyBonus)} />
        <StatCard label="Passive Perception" value={String(10 + abilityMod(char.wisdom) + (skillProfs.some(s => s.toLowerCase() === 'perception') ? char.proficiencyBonus : 0))} />
        <StatCard label="Hit Dice" value={char.hitDiceTotal || `${char.level}d?`} />
        <StatCard label="XP" value={String(char.experiencePoints)} />
      </div>

      {/* Proficiencies */}
      {hasProficiencies && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Proficiencies</h3>
          <div className="space-y-2">
            {armorProfs.length > 0 && (
              <div>
                <span className="text-gray-500 text-xs font-medium">Armor: </span>
                <span className="text-gray-300 text-sm">{armorProfs.map(a => a.charAt(0).toUpperCase() + a.slice(1)).join(', ')}</span>
              </div>
            )}
            {weaponProfs.length > 0 && (
              <div>
                <span className="text-gray-500 text-xs font-medium">Weapons: </span>
                <span className="text-gray-300 text-sm">{weaponProfs.map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(', ')}</span>
              </div>
            )}
            {toolProfs.length > 0 && (
              <div>
                <span className="text-gray-500 text-xs font-medium">Tools: </span>
                <span className="text-gray-300 text-sm">{toolProfs.map(t => t.charAt(0).toUpperCase() + t.slice(1)).join(', ')}</span>
              </div>
            )}
            {languageProfs.length > 0 && (
              <div>
                <span className="text-gray-500 text-xs font-medium">Languages: </span>
                <span className="text-gray-300 text-sm">{languageProfs.join(', ')}</span>
              </div>
            )}
          </div>
        </div>
      )}

      {resistances.length > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-2">Resistances & Immunities</h3>
          <div className="flex flex-wrap gap-2">
            {resistances.map((r, i) => (
              <span key={i} className="px-2 py-1 bg-gray-800 text-gray-300 rounded text-xs">{r}</span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
