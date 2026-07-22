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
            <div key={ability} className="bg-card border border-rule p-3 text-center">
              <p className="font-heading text-[8px] font-semibold tracking-[0.1em] uppercase text-faint">{ABILITY_ABBR[ability]}</p>
              <p className="font-heading text-[20px] font-bold text-ink mt-1">{score}</p>
              <p className="font-body text-[12px] font-medium text-muted">{formatMod(mod)}</p>
            </div>
          );
        })}
      </div>

      {/* Saving Throws */}
      <div className="bg-card border border-rule p-4">
        <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Saving Throws</h3>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {ABILITIES.map(ability => {
            const mod = abilityMod(char[ability]);
            const isProficient = savingThrows.some(s => s.toLowerCase() === ABILITY_ABBR[ability].toLowerCase() || s.toLowerCase() === ability);
            const total = mod + (isProficient ? char.proficiencyBonus : 0);
            return (
              <div key={ability} className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${isProficient ? 'bg-buff' : 'bg-rule'}`} />
                <span className="font-heading text-[11px] font-medium text-muted">{ABILITY_ABBR[ability]}</span>
                <span className="font-heading text-[12px] font-bold text-ink ml-auto">{formatMod(total)}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Skills */}
      <div className="bg-card border border-rule p-4">
        <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Skills</h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
          {SKILLS.map(skill => {
            const mod = abilityMod(char[skill.ability]);
            const isProficient = skillProfs.some(s => s.toLowerCase() === skill.name.toLowerCase());
            const isExpert = skillExpertises.some(s => s.toLowerCase() === skill.name.toLowerCase());
            const bonus = mod + (isExpert ? char.proficiencyBonus * 2 : isProficient ? char.proficiencyBonus : 0);
            return (
              <div key={skill.name} className="flex items-center gap-2 py-0.5">
                {isExpert ? (
                  <span className="text-cls-cleric text-xs leading-none" style={{ fontSize: '10px' }}>&#9733;</span>
                ) : (
                  <div className={`w-2.5 h-2.5 rounded-full border-2 ${isProficient ? 'bg-buff border-buff' : 'bg-transparent border-rule'}`} />
                )}
                <span className={`font-body text-[13px] flex-1 ${isProficient || isExpert ? 'font-semibold text-ink' : 'font-medium text-muted'}`}>
                  {skill.name} <span className="text-faint text-[11px]">({ABILITY_ABBR[skill.ability]})</span>
                </span>
                <span className={`font-heading text-[12px] font-bold ${isProficient || isExpert ? 'text-ink' : 'text-faint'}`}>{formatMod(bonus)}</span>
              </div>
            );
          })}
        </div>
        <div className="mt-3 pt-3 border-t border-rule-light flex gap-4 font-body text-[11px] text-faint">
          <span className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 rounded-full bg-buff" /> Proficient</span>
          <span className="flex items-center gap-1.5"><span className="text-cls-cleric" style={{ fontSize: '10px' }}>&#9733;</span> Expertise</span>
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
        <div className="bg-card border border-rule p-4">
          <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Proficiencies</h3>
          <div className="space-y-2">
            {armorProfs.length > 0 && (
              <div>
                <span className="font-heading text-[9px] font-semibold tracking-[0.04em] uppercase text-faint">Armor: </span>
                <span className="font-body text-[13px] font-medium text-muted">{armorProfs.map(a => a.charAt(0).toUpperCase() + a.slice(1)).join(', ')}</span>
              </div>
            )}
            {weaponProfs.length > 0 && (
              <div>
                <span className="font-heading text-[9px] font-semibold tracking-[0.04em] uppercase text-faint">Weapons: </span>
                <span className="font-body text-[13px] font-medium text-muted">{weaponProfs.map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(', ')}</span>
              </div>
            )}
            {toolProfs.length > 0 && (
              <div>
                <span className="font-heading text-[9px] font-semibold tracking-[0.04em] uppercase text-faint">Tools: </span>
                <span className="font-body text-[13px] font-medium text-muted">{toolProfs.map(t => t.charAt(0).toUpperCase() + t.slice(1)).join(', ')}</span>
              </div>
            )}
            {languageProfs.length > 0 && (
              <div>
                <span className="font-heading text-[9px] font-semibold tracking-[0.04em] uppercase text-faint">Languages: </span>
                <span className="font-body text-[13px] font-medium text-muted">{languageProfs.join(', ')}</span>
              </div>
            )}
          </div>
        </div>
      )}

      {resistances.length > 0 && (
        <div className="bg-card border border-rule p-4">
          <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-2">Resistances & Immunities</h3>
          <div className="flex flex-wrap gap-2">
            {resistances.map((r, i) => (
              <span key={i} className="px-2 py-1 bg-page-alt border border-rule font-body text-[11px] font-medium text-muted">{r}</span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
