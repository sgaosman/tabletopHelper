import { ChevronDown } from 'lucide-react';
import type { CharacterClassRef, Race, Subclass } from '../../../types/reference';
import { safeJsonParse } from '../../../utils/dndRules';
import type { ClassEntry, AbilityScores } from './types';
import { checkMulticlassEligibility, countAsiLevels, isAsiLevel } from './types';

export interface ClassStepProps {
  classes: CharacterClassRef[];
  selectedClass: CharacterClassRef | null;
  onSelectClass: (cls: CharacterClassRef | null) => void;
  classEntries: ClassEntry[];
  level: number;
  multiclassExpanded: boolean;
  setMulticlassExpanded: React.Dispatch<React.SetStateAction<boolean>>;
  addMulticlass: (cls: CharacterClassRef) => void;
  removeMulticlass: (clsId: string) => void;
  handleClassLevelChange: (clsId: string, newLevel: number) => void;
  handleEntrySubclass: (clsId: string, sc: Subclass | null) => void;
  finalScores: AbilityScores;
  classSkillChoices: { from: string[]; count: number };
  selectedClassSkills: string[];
  setSelectedClassSkills: React.Dispatch<React.SetStateAction<string[]>>;
  raceSkills: Set<string>;
  mcSkillChoicesMap: Record<string, { from: string[]; count: number }>;
  mcSkillSelections: Record<string, string[]>;
  setMcSkillSelections: React.Dispatch<React.SetStateAction<Record<string, string[]>>>;
  resolvedBgProfs: { skills: string[]; tools: string[]; languages: string[] };
  resolvedRaceChoices: { languages: string[]; skills: string[]; tools: string[]; weapons: string[]; resistances: string[]; spellAbility: string | null; feats: string[] };
  selectedExpertise: string[];
  setSelectedExpertise: React.Dispatch<React.SetStateAction<string[]>>;
  expertiseCount: number;
  selectedRace: Race | null;
}

export default function ClassStep({
  classes, selectedClass, onSelectClass,
  classEntries, level,
  multiclassExpanded, setMulticlassExpanded,
  addMulticlass, removeMulticlass,
  handleClassLevelChange, handleEntrySubclass,
  finalScores,
  classSkillChoices, selectedClassSkills, setSelectedClassSkills,
  raceSkills,
  mcSkillChoicesMap, mcSkillSelections, setMcSkillSelections,
  resolvedBgProfs, resolvedRaceChoices,
  selectedExpertise, setSelectedExpertise, expertiseCount,
  selectedRace,
}: ClassStepProps) {
  return (
    <div className="space-y-4">
      <h2 className="font-heading text-[15px] font-semibold text-ink">Choose a Class</h2>
      <p className="font-body text-[13px] font-medium text-muted">Select your primary class{level >= 2 ? '. You can optionally multiclass below.' : '.'}</p>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {classes.map(cls => {
          const saves = safeJsonParse<string[]>(cls.savingThrowProficiencies, []);
          const isSelected = selectedClass?.id === cls.id;
          return (
            <button
              key={cls.id}
              onClick={() => onSelectClass(isSelected ? null : cls)}
              className={`p-4 border text-left transition-colors ${
                isSelected
                  ? 'bg-page-alt border-ink'
                  : 'bg-card border-rule hover:border-muted'
              }`}
            >
              <div className="flex items-start justify-between">
                <h3 className="font-heading text-[13px] font-semibold text-ink">{cls.name}</h3>
                <span className="font-body text-[11px] text-faint">d{cls.hitDice}</span>
              </div>
              <p className="font-body text-[11px] font-medium text-muted mt-1">Primary: {cls.primaryAbility}</p>
              {saves.length > 0 && <p className="font-body text-[11px] font-medium text-cls-druid">Saves: {saves.join(', ')}</p>}
              {cls.isSpellcaster && <p className="font-body text-[11px] font-medium text-cls-sorcerer mt-0.5">Spellcaster ({cls.spellcastingAbility})</p>}
            </button>
          );
        })}
      </div>

      {/* Multiclass section */}
      {selectedClass && level >= 2 && (
        <div className="mt-6 bg-card border border-rule overflow-hidden">
          <button
            onClick={() => setMulticlassExpanded(prev => !prev)}
            className="w-full flex items-center justify-between px-4 py-3 hover:bg-page-alt transition-colors"
          >
            <div className="flex items-center gap-2">
              <h3 className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-muted">Multiclassing Options</h3>
              {classEntries.length > 1 && (
                <span className="font-heading text-[9px] font-medium text-buff">({classEntries.length - 1} added)</span>
              )}
            </div>
            <ChevronDown className={`w-4 h-4 text-faint transition-transform ${multiclassExpanded ? 'rotate-180' : ''}`} />
          </button>
          {multiclassExpanded && (
            <div className="px-4 pb-4 border-t border-rule">
              <p className="font-body text-[11px] text-faint my-3">
                PHB rule: you must meet the ability score prerequisites for both your current class and the new class.
              </p>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                {classes.filter(c => c.id !== selectedClass.id).map(cls => {
                  const entryEligibility = checkMulticlassEligibility(cls, finalScores);
                  const exitEligibility = checkMulticlassEligibility(selectedClass, finalScores);
                  const canMulticlass = entryEligibility.eligible && exitEligibility.eligible;
                  const isAdded = classEntries.some(e => e.cls.id === cls.id);
                  const primaryCanGive = classEntries.length > 0 && classEntries[0].level > 1;
                  const canAddMore = primaryCanGive || isAdded;
                  const saves = safeJsonParse<string[]>(cls.savingThrowProficiencies, []);

                  return (
                    <button
                      key={cls.id}
                      onClick={() => {
                        if (isAdded) {
                          removeMulticlass(cls.id);
                        } else if (canMulticlass && canAddMore) {
                          addMulticlass(cls);
                        }
                      }}
                      disabled={!canMulticlass && !isAdded}
                      className={`p-3 border text-left transition-colors ${
                        isAdded
                          ? 'bg-buff-bg border-buff-border'
                          : !canMulticlass
                          ? 'bg-page border-rule opacity-50 cursor-not-allowed'
                          : 'bg-card border-rule hover:border-muted cursor-pointer'
                      }`}
                    >
                      <div className="flex items-start justify-between">
                        <h4 className="font-heading text-[13px] font-semibold text-ink">{cls.name}</h4>
                        <span className="font-body text-[11px] text-faint">d{cls.hitDice}</span>
                      </div>
                      {saves.length > 0 && <p className="font-body text-[11px] font-medium text-cls-druid">Saves: {saves.join(', ')}</p>}
                      {cls.isSpellcaster && <p className="font-body text-[11px] font-medium text-cls-sorcerer">Spellcaster ({cls.spellcastingAbility})</p>}
                      {!canMulticlass && (
                        <p className="font-body text-[11px] font-medium text-debuff mt-1">
                          {!exitEligibility.eligible ? `Exit: ${exitEligibility.reason}` : entryEligibility.reason}
                        </p>
                      )}
                      {isAdded && <p className="font-body text-[11px] font-medium text-buff mt-1">Added to multiclass</p>}
                    </button>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Level allocation sliders */}
      {classEntries.length > 1 && (
        <div className="mt-4 bg-card border border-rule p-4">
          <h3 className="font-heading text-[15px] font-semibold text-ink mb-1">Level Allocation</h3>
          <p className="font-body text-[11px] text-faint mb-3">
            Distribute your {level} character levels across classes.
          </p>
          <div className="space-y-3">
            {classEntries.map(entry => {
              const otherTotal = classEntries.reduce((s, e) => e.cls.id === entry.cls.id ? s : s + e.level, 0);
              const maxForThis = level - otherTotal;
              return (
                <div key={entry.cls.id} className="flex items-center gap-3">
                  <span className="font-heading text-[13px] font-semibold text-ink w-24 shrink-0">{entry.cls.name}</span>
                  <input
                    type="range"
                    min={1}
                    max={Math.max(1, maxForThis)}
                    value={entry.level}
                    onChange={e => handleClassLevelChange(entry.cls.id, Number(e.target.value))}
                    className="flex-1 accent-ink"
                  />
                  <span className="font-heading text-[14px] font-bold text-ink w-6 text-center">{entry.level}</span>
                </div>
              );
            })}
          </div>
          {(() => {
            const total = classEntries.reduce((s, e) => s + e.level, 0);
            return total !== level && (
              <p className="font-body text-[11px] font-medium text-debuff mt-2">Total levels: {total}/{level} — must equal character level</p>
            );
          })()}
        </div>
      )}

      {/* Per-class subclass selection */}
      {classEntries.map(entry => {
        const subclassLvl = entry.cls.subclassLevel || 3;
        const needsSubclass = entry.level >= subclassLvl && entry.subclasses.length > 0;
        const belowSubclass = entry.level < subclassLvl && entry.subclasses.length > 0;
        return (
          <div key={`sc-${entry.cls.id}`}>
            {needsSubclass && (
              <div className="mt-4">
                <h3 className="font-heading text-[15px] font-semibold text-ink mb-3">
                  {classEntries.length > 1 ? `${entry.cls.name} Subclass` : 'Choose a Subclass'}
                </h3>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-60 overflow-y-auto">
                  {entry.subclasses.map(sc => (
                    <button
                      key={sc.id}
                      onClick={() => handleEntrySubclass(entry.cls.id, entry.subclass?.id === sc.id ? null : sc)}
                      className={`p-3 border text-left transition-colors ${
                        entry.subclass?.id === sc.id
                          ? 'bg-page-alt border-ink'
                          : 'bg-card border-rule hover:border-muted'
                      }`}
                    >
                      <h4 className="font-heading text-[13px] font-semibold text-ink">{sc.name}</h4>
                      <p className="font-body text-[11px] text-faint">{sc.source}</p>
                    </button>
                  ))}
                </div>
                {!entry.subclass && (
                  <p className="font-body text-[12px] font-medium text-hp-wounded mt-2">Subclass selection required at {entry.cls.name} level {subclassLvl}+</p>
                )}
              </div>
            )}
            {belowSubclass && classEntries.length <= 1 && (
              <p className="font-body text-[12px] text-faint mt-2">Subclass available at level {subclassLvl}</p>
            )}
          </div>
        );
      })}

      {/* ASI level preview */}
      {classEntries.length > 0 && (() => {
        const asiCount = countAsiLevels(classEntries);
        if (asiCount === 0) return null;
        const asiDetails = classEntries.flatMap(entry => {
          const levels: string[] = [];
          for (let lvl = 1; lvl <= entry.level; lvl++) {
            if (isAsiLevel(entry.cls.name, lvl)) {
              levels.push(classEntries.length > 1 ? `${entry.cls.name} ${lvl}` : `Level ${lvl}`);
            }
          }
          return levels;
        });
        return (
          <div className="mt-4 bg-cls-monk-bg border border-[#FDE68A] p-4">
            <h3 className="font-heading text-[12px] font-semibold text-cls-monk">
              {asiCount} Ability Score Improvement{asiCount > 1 ? 's' : ''}
            </h3>
            <p className="font-body text-[11px] font-medium text-muted mt-1">
              ASI at: {asiDetails.join(', ')}. You'll choose ability increases or feats after creation.
            </p>
          </div>
        );
      })()}

      {/* Class skill proficiency selection */}
      {selectedClass && classSkillChoices.count > 0 && (
        <div className="mt-4 bg-card border border-rule p-4">
          <div className="flex items-center justify-between mb-1">
            <h3 className="font-heading text-[13px] font-semibold text-ink">Class Skill Proficiencies</h3>
            <span className="font-heading text-[9px] font-medium text-faint">{selectedClassSkills.length}/{classSkillChoices.count} selected</span>
          </div>
          <p className="font-body text-[11px] text-faint mb-3">
            Choose {classSkillChoices.count} skill{classSkillChoices.count > 1 ? 's' : ''} from the {selectedClass.name} class list.
          </p>
          <div className="flex gap-1.5 flex-wrap">
            {classSkillChoices.from.map(skill => {
              const isSelected = selectedClassSkills.includes(skill);
              const alreadyFromRace = raceSkills.has(skill.toLowerCase());
              const disabled = alreadyFromRace || (!isSelected && selectedClassSkills.length >= classSkillChoices.count);
              return (
                <button
                  key={skill}
                  onClick={() => {
                    if (alreadyFromRace) return;
                    if (isSelected) {
                      setSelectedClassSkills(prev => prev.filter(s => s !== skill));
                    } else if (selectedClassSkills.length < classSkillChoices.count) {
                      setSelectedClassSkills(prev => [...prev, skill]);
                    }
                  }}
                  disabled={disabled && !isSelected}
                  className={`px-3 py-1.5 font-heading text-[9px] font-medium tracking-[0.02em] transition-colors border ${
                    alreadyFromRace
                      ? 'bg-page text-faint cursor-not-allowed line-through border-rule'
                      : isSelected
                      ? 'bg-ink text-card border-ink'
                      : disabled
                      ? 'bg-page text-faint cursor-not-allowed border-rule'
                      : 'bg-page text-muted border-rule hover:border-muted'
                  }`}
                  title={alreadyFromRace ? 'Already granted by race' : undefined}
                >
                  {skill}
                  {alreadyFromRace && ' (race)'}
                </button>
              );
            })}
          </div>
          {selectedClassSkills.length < classSkillChoices.count && (
            <p className="font-body text-[11px] font-medium text-hp-wounded mt-3">Select {classSkillChoices.count - selectedClassSkills.length} more skill{classSkillChoices.count - selectedClassSkills.length > 1 ? 's' : ''} to continue</p>
          )}
        </div>
      )}

      {/* Multiclass skill choices */}
      {Object.entries(mcSkillChoicesMap).map(([clsId, choices]) => {
        const entry = classEntries.find(e => e.cls.id === clsId);
        if (!entry) return null;
        const selected = mcSkillSelections[clsId] ?? [];
        const allTaken = new Set([
          ...raceSkills,
          ...selectedClassSkills.map(s => s.toLowerCase()),
          ...Object.entries(mcSkillSelections).filter(([k]) => k !== clsId).flatMap(([, v]) => v.map(s => s.toLowerCase())),
        ]);
        return (
          <div key={clsId} className="mt-4 bg-card border border-rule p-4">
            <div className="flex items-center justify-between mb-1">
              <h3 className="font-heading text-[13px] font-semibold text-ink">{entry.cls.name} Multiclass Skills</h3>
              <span className="font-heading text-[9px] font-medium text-faint">{selected.length}/{choices.count} selected</span>
            </div>
            <p className="font-body text-[11px] text-faint mb-3">
              Choose {choices.count} skill{choices.count > 1 ? 's' : ''} from the {entry.cls.name} multiclass list.
            </p>
            <div className="flex gap-1.5 flex-wrap">
              {choices.from.map(skill => {
                const isSelected = selected.includes(skill);
                const alreadyTaken = allTaken.has(skill.toLowerCase());
                const disabled = alreadyTaken || (!isSelected && selected.length >= choices.count);
                return (
                  <button
                    key={skill}
                    onClick={() => {
                      if (alreadyTaken) return;
                      if (isSelected) {
                        setMcSkillSelections(prev => ({ ...prev, [clsId]: (prev[clsId] ?? []).filter(s => s !== skill) }));
                      } else if (selected.length < choices.count) {
                        setMcSkillSelections(prev => ({ ...prev, [clsId]: [...(prev[clsId] ?? []), skill] }));
                      }
                    }}
                    disabled={disabled && !isSelected}
                    className={`px-3 py-1.5 font-heading text-[9px] font-medium tracking-[0.02em] transition-colors border ${
                      alreadyTaken ? 'bg-page text-faint cursor-not-allowed line-through border-rule'
                        : isSelected ? 'bg-ink text-card border-ink'
                        : disabled ? 'bg-page text-faint cursor-not-allowed border-rule'
                        : 'bg-page text-muted border-rule hover:border-muted'
                    }`}
                    title={alreadyTaken ? 'Already proficient' : undefined}
                  >
                    {skill}
                    {alreadyTaken && ' (taken)'}
                  </button>
                );
              })}
            </div>
          </div>
        );
      })}

      {/* Expertise picker */}
      {expertiseCount > 0 && (
        <div className="mt-4 bg-card border border-rule p-4">
          <div className="flex items-center justify-between mb-1">
            <h3 className="font-heading text-[13px] font-semibold text-ink">Expertise</h3>
            <span className="font-heading text-[9px] font-medium text-faint">{selectedExpertise.length}/{expertiseCount} selected</span>
          </div>
          <p className="font-body text-[11px] text-faint mb-3">
            Choose {expertiseCount} skill{expertiseCount > 1 ? 's' : ''} to gain expertise in (double proficiency bonus).
          </p>
          <div className="flex gap-1.5 flex-wrap">
            {[...selectedClassSkills, ...resolvedBgProfs.skills, ...(safeJsonParse<{ skills?: string[] }>(selectedRace?.proficiencies, {}).skills ?? []), ...resolvedRaceChoices.skills, ...Object.values(mcSkillSelections).flat()]
              .filter((s, i, a) => a.indexOf(s) === i)
              .map(skill => {
                const isSelected = selectedExpertise.includes(skill);
                const disabled = !isSelected && selectedExpertise.length >= expertiseCount;
                return (
                  <button
                    key={skill}
                    onClick={() => {
                      if (isSelected) {
                        setSelectedExpertise(prev => prev.filter(s => s !== skill));
                      } else if (selectedExpertise.length < expertiseCount) {
                        setSelectedExpertise(prev => [...prev, skill]);
                      }
                    }}
                    disabled={disabled}
                    className={`px-3 py-1.5 font-heading text-[9px] font-medium tracking-[0.02em] transition-colors border ${
                      isSelected ? 'bg-cls-monk text-white border-cls-monk'
                        : disabled ? 'bg-page text-faint cursor-not-allowed border-rule'
                        : 'bg-page text-muted border-rule hover:border-muted'
                    }`}
                  >
                    {skill}
                  </button>
                );
              })}
          </div>
          {selectedExpertise.length < expertiseCount && (
            <p className="font-body text-[11px] font-medium text-hp-wounded mt-3">Select {expertiseCount - selectedExpertise.length} more skill{expertiseCount - selectedExpertise.length > 1 ? 's' : ''}</p>
          )}
        </div>
      )}
    </div>
  );
}
