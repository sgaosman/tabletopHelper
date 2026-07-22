import { useState, useEffect, useMemo } from 'react';
import { Check, Search, X } from 'lucide-react';
import type { CharacterClassRef, Spell } from '../../../types/reference';
import { searchSpells } from '../../../api/referenceApi';
import { CANTRIPS_KNOWN, SPELLS_KNOWN, THIRD_CASTER_CANTRIPS, THIRD_CASTER_SPELLS, THIRD_CASTER_SUBCLASSES, THIRD_CASTER_SPELL_LIST, maxSpellLevel, wizardSpellbookCount } from '../../../utils/spellConstants';
import type { ParsedFeatOption } from '../../../utils/featSpellParser';
import type { ClassEntry } from './types';

export interface SpellsStepProps {
  selectedClass: CharacterClassRef | null;
  classEntries: ClassEntry[];
  level: number;
  selectedCantrips: Spell[];
  setSelectedCantrips: (s: Spell[]) => void;
  selectedSpells: Spell[];
  setSelectedSpells: (s: Spell[]) => void;
  cantripResults: Spell[];
  setCantripResults: (s: Spell[]) => void;
  spellResults: Spell[];
  setSpellResults: (s: Spell[]) => void;
  cantripSearch: string;
  setCantripSearch: (s: string) => void;
  spellSearch: string;
  setSpellSearch: (s: string) => void;
  mcSpellSelections: Record<string, { cantrips: Spell[]; spells: Spell[] }>;
  setMcSpellSelections: React.Dispatch<React.SetStateAction<Record<string, { cantrips: Spell[]; spells: Spell[] }>>>;
  selectedFeatObj: { name: string } | null;
  selectedFeatOption: ParsedFeatOption | null;
  hasFeatSpellChoices: boolean;
  featCantrips: Spell[];
  setFeatCantrips: (s: Spell[]) => void;
  featSpells: Spell[];
  setFeatSpells: (s: Spell[]) => void;
}

export default function SpellsStep({
  selectedClass, classEntries, level,
  selectedCantrips, setSelectedCantrips,
  selectedSpells, setSelectedSpells,
  cantripResults, setCantripResults,
  spellResults, setSpellResults,
  cantripSearch, setCantripSearch,
  spellSearch, setSpellSearch,
  mcSpellSelections, setMcSpellSelections,
  selectedFeatObj, selectedFeatOption, hasFeatSpellChoices,
  featCantrips, setFeatCantrips, featSpells, setFeatSpells,
}: SpellsStepProps) {
  return (
    <div className="space-y-8">
      {selectedClass?.isSpellcaster && (() => {
        const classLevel = classEntries.length > 0 ? classEntries[0].level : level;
        const maxLvl = maxSpellLevel(selectedClass.name, classLevel);
        const cantrips = CANTRIPS_KNOWN[selectedClass.name]?.[classLevel] ?? 0;
        if (maxLvl === 0 && cantrips === 0) {
          return (
            <div className="bg-card border border-rule p-4">
              <h2 className="font-heading text-[15px] font-semibold text-ink mb-2">
                {classEntries.length > 1 ? `${selectedClass.name} Spells` : 'Spells'}
              </h2>
              <p className="font-body text-[13px] font-medium text-muted">
                {selectedClass.name} does not gain spellcasting until a higher level. No spell selection is needed at level {classLevel}.
              </p>
            </div>
          );
        }
        return (
          <SpellSelectionSection
            selectedClass={selectedClass}
            level={classLevel}
            selectedCantrips={selectedCantrips}
            setSelectedCantrips={setSelectedCantrips}
            selectedSpells={selectedSpells}
            setSelectedSpells={setSelectedSpells}
            cantripResults={cantripResults}
            setCantripResults={setCantripResults}
            spellResults={spellResults}
            setSpellResults={setSpellResults}
            cantripSearch={cantripSearch}
            setCantripSearch={setCantripSearch}
            spellSearch={spellSearch}
            setSpellSearch={setSpellSearch}
            title={classEntries.length > 1 ? `${selectedClass.name} Spells` : undefined}
          />
        );
      })()}
      {classEntries.slice(1).filter(e => e.cls.isSpellcaster).map(entry => {
        const maxLvl = maxSpellLevel(entry.cls.name, entry.level);
        const cantrips = CANTRIPS_KNOWN[entry.cls.name]?.[entry.level] ?? 0;
        if (maxLvl === 0 && cantrips === 0) {
          return (
            <div key={entry.cls.id} className="bg-card border border-rule p-4">
              <h3 className="font-heading text-[13px] font-semibold text-ink mb-2">{entry.cls.name} Spells</h3>
              <p className="font-body text-[13px] font-medium text-muted">
                {entry.cls.name} does not gain spellcasting until a higher level. No spell selection is needed at level {entry.level}.
              </p>
            </div>
          );
        }
        return (
          <MulticlassSpellSection
            key={entry.cls.id}
            classEntry={entry}
            selectedCantrips={mcSpellSelections[entry.cls.id]?.cantrips ?? []}
            onCantripsChange={cantrips => setMcSpellSelections(prev => ({
              ...prev,
              [entry.cls.id]: { cantrips, spells: prev[entry.cls.id]?.spells ?? [] },
            }))}
            selectedSpells={mcSpellSelections[entry.cls.id]?.spells ?? []}
            onSpellsChange={spells => setMcSpellSelections(prev => ({
              ...prev,
              [entry.cls.id]: { cantrips: prev[entry.cls.id]?.cantrips ?? [], spells },
            }))}
          />
        );
      })}
      {classEntries.filter(e => e.subclass && THIRD_CASTER_SUBCLASSES.has(e.subclass.name) && e.level >= 3).map(entry => (
        <ThirdCasterSpellSection
          key={`third-${entry.cls.id}`}
          classEntry={entry}
          selectedCantrips={mcSpellSelections[`third:${entry.cls.id}`]?.cantrips ?? []}
          onCantripsChange={cantrips => setMcSpellSelections(prev => ({
            ...prev,
            [`third:${entry.cls.id}`]: { cantrips, spells: prev[`third:${entry.cls.id}`]?.spells ?? [] },
          }))}
          selectedSpells={mcSpellSelections[`third:${entry.cls.id}`]?.spells ?? []}
          onSpellsChange={spells => setMcSpellSelections(prev => ({
            ...prev,
            [`third:${entry.cls.id}`]: { cantrips: prev[`third:${entry.cls.id}`]?.cantrips ?? [], spells },
          }))}
        />
      ))}
      {selectedFeatObj && selectedFeatOption && hasFeatSpellChoices && (
        <FeatSpellSection
          featName={selectedFeatObj.name}
          option={selectedFeatOption}
          featCantrips={featCantrips}
          setFeatCantrips={setFeatCantrips}
          featSpells={featSpells}
          setFeatSpells={setFeatSpells}
        />
      )}
      {(() => {
        const warnings: string[] = [];
        if (selectedClass?.isSpellcaster) {
          const classLevel = classEntries.length > 0 ? classEntries[0].level : level;
          const cantripsAllowed = CANTRIPS_KNOWN[selectedClass.name]?.[classLevel] ?? 0;
          const isWiz = selectedClass.name === 'Wizard';
          const spellsAllowed = isWiz ? wizardSpellbookCount(classLevel)
            : selectedClass.isKnownCaster ? (SPELLS_KNOWN[selectedClass.name]?.[classLevel] ?? 0) : 0;
          if (cantripsAllowed > 0 && selectedCantrips.length < cantripsAllowed) {
            warnings.push(`${selectedClass.name}: ${selectedCantrips.length}/${cantripsAllowed} cantrips selected`);
          }
          if (spellsAllowed > 0 && selectedSpells.length < spellsAllowed) {
            warnings.push(`${selectedClass.name}: ${selectedSpells.length}/${spellsAllowed} spells selected`);
          }
        }
        for (const entry of classEntries) {
          if (entry.cls.id === selectedClass?.id) continue;
          if (!entry.cls.isSpellcaster) {
            if (entry.subclass && THIRD_CASTER_SUBCLASSES.has(entry.subclass.name) && entry.level >= 3) {
              const sel = mcSpellSelections[`third:${entry.cls.id}`];
              const cantripsAllowed = THIRD_CASTER_CANTRIPS[entry.subclass.name]?.[entry.level] ?? 0;
              const spellsAllowed = THIRD_CASTER_SPELLS[entry.subclass.name]?.[entry.level] ?? 0;
              const cantripCount = sel?.cantrips?.length ?? 0;
              const spellCount = sel?.spells?.length ?? 0;
              if (cantripsAllowed > 0 && cantripCount < cantripsAllowed) warnings.push(`${entry.subclass.name}: ${cantripCount}/${cantripsAllowed} cantrips`);
              if (spellsAllowed > 0 && spellCount < spellsAllowed) warnings.push(`${entry.subclass.name}: ${spellCount}/${spellsAllowed} spells`);
            }
            continue;
          }
          const sel = mcSpellSelections[entry.cls.id];
          const cantripsAllowed = CANTRIPS_KNOWN[entry.cls.name]?.[entry.level] ?? 0;
          const isWiz = entry.cls.name === 'Wizard';
          const spellsAllowed = isWiz ? wizardSpellbookCount(entry.level)
            : entry.cls.isKnownCaster ? (SPELLS_KNOWN[entry.cls.name]?.[entry.level] ?? 0) : 0;
          const cantripCount = sel?.cantrips?.length ?? 0;
          const spellCount = sel?.spells?.length ?? 0;
          if (cantripsAllowed > 0 && cantripCount < cantripsAllowed) warnings.push(`${entry.cls.name}: ${cantripCount}/${cantripsAllowed} cantrips`);
          if (spellsAllowed > 0 && spellCount < spellsAllowed) warnings.push(`${entry.cls.name}: ${spellCount}/${spellsAllowed} spells`);
        }
        if (classEntries.length > 0 && classEntries[0].subclass && THIRD_CASTER_SUBCLASSES.has(classEntries[0].subclass.name) && classEntries[0].level >= 3) {
          const sel = mcSpellSelections[`third:${classEntries[0].cls.id}`];
          const scName = classEntries[0].subclass.name;
          const cantripsAllowed = THIRD_CASTER_CANTRIPS[scName]?.[classEntries[0].level] ?? 0;
          const spellsAllowed = THIRD_CASTER_SPELLS[scName]?.[classEntries[0].level] ?? 0;
          const cantripCount = sel?.cantrips?.length ?? 0;
          const spellCount = sel?.spells?.length ?? 0;
          if (cantripsAllowed > 0 && cantripCount < cantripsAllowed) warnings.push(`${scName}: ${cantripCount}/${cantripsAllowed} cantrips`);
          if (spellsAllowed > 0 && spellCount < spellsAllowed) warnings.push(`${scName}: ${spellCount}/${spellsAllowed} spells`);
        }
        if (warnings.length === 0) return null;
        return (
          <div className="bg-buff-bg border border-buff-border p-3">
            <p className="font-body text-[11px] font-medium text-hp-wounded">
              You haven't selected all available spells. You can still proceed, but your character may be underpowered.
            </p>
            <ul className="font-body text-[11px] font-medium text-buff mt-1 list-disc list-inside">
              {warnings.map(w => <li key={w}>{w}</li>)}
            </ul>
          </div>
        );
      })()}
    </div>
  );
}

function SpellSelectionSection({
  selectedClass, level,
  selectedCantrips, setSelectedCantrips,
  selectedSpells, setSelectedSpells,
  cantripResults, setCantripResults,
  spellResults, setSpellResults,
  cantripSearch, setCantripSearch,
  spellSearch, setSpellSearch,
  title,
}: {
  selectedClass: CharacterClassRef;
  level: number;
  selectedCantrips: Spell[];
  setSelectedCantrips: (s: Spell[]) => void;
  selectedSpells: Spell[];
  setSelectedSpells: (s: Spell[]) => void;
  cantripResults: Spell[];
  setCantripResults: (s: Spell[]) => void;
  spellResults: Spell[];
  setSpellResults: (s: Spell[]) => void;
  cantripSearch: string;
  setCantripSearch: (s: string) => void;
  spellSearch: string;
  setSpellSearch: (s: string) => void;
  title?: string;
}) {
  const cantripsAllowed = CANTRIPS_KNOWN[selectedClass.name]?.[level] ?? 0;
  const isWizard = selectedClass.name === 'Wizard';
  const spellsAllowed = isWizard
    ? wizardSpellbookCount(level)
    : selectedClass.isKnownCaster
    ? (SPELLS_KNOWN[selectedClass.name]?.[level] ?? 0)
    : 0;
  const isPrepared = selectedClass.isPreparedCaster && !isWizard;
  const maxLevel = maxSpellLevel(selectedClass.name, level);

  useEffect(() => {
    if (cantripsAllowed > 0 && cantripResults.length === 0) {
      searchSpells({ className: selectedClass.name, level: 0, size: 50 })
        .then(res => setCantripResults(res.content))
        .catch(() => {});
    }
  }, [selectedClass.name]);

  useEffect(() => {
    if ((spellsAllowed > 0 || (isPrepared && !isWizard)) && maxLevel > 0) {
      Promise.all(
        Array.from({ length: maxLevel }, (_, i) => i + 1).map(lvl =>
          searchSpells({ className: selectedClass.name, level: lvl, size: 50 }).then(r => r.content)
        )
      ).then(results => {
        const all = results.flat();
        const seen = new Set<string>();
        setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
      }).catch(() => {});
    }
  }, [selectedClass.name, maxLevel]);

  function searchCantrips() {
    const params: Record<string, unknown> = { className: selectedClass.name, level: 0, size: 50 };
    if (cantripSearch.trim()) params.name = cantripSearch.trim();
    searchSpells(params as any).then(res => setCantripResults(res.content)).catch(() => {});
  }

  function searchSpellsList() {
    if (maxLevel <= 0) return;
    Promise.all(
      Array.from({ length: maxLevel }, (_, i) => i + 1).map(lvl => {
        const params: Record<string, unknown> = { className: selectedClass.name, level: lvl, size: 50 };
        if (spellSearch.trim()) params.name = spellSearch.trim();
        return searchSpells(params as any).then(r => r.content);
      })
    ).then(results => {
      const all = results.flat();
      const seen = new Set<string>();
      setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
    }).catch(() => {});
  }

  function toggleCantrip(spell: Spell) {
    const exists = selectedCantrips.some(s => s.id === spell.id);
    if (exists) {
      setSelectedCantrips(selectedCantrips.filter(s => s.id !== spell.id));
    } else if (selectedCantrips.length < cantripsAllowed) {
      setSelectedCantrips([...selectedCantrips, spell]);
    }
  }

  function toggleSpell(spell: Spell) {
    const exists = selectedSpells.some(s => s.id === spell.id);
    if (exists) {
      setSelectedSpells(selectedSpells.filter(s => s.id !== spell.id));
    } else if (spellsAllowed === 0 || selectedSpells.length < spellsAllowed) {
      setSelectedSpells([...selectedSpells, spell]);
    }
  }

  return (
    <div className="space-y-6">
      <h2 className="font-heading text-[15px] font-semibold text-ink">{title ?? 'Choose Spells'}</h2>
      <p className="font-body text-[13px] font-medium text-muted">
        {isWizard
          ? `Select spells for your starting spellbook. You begin with ${wizardSpellbookCount(level)} spells.`
          : `Select your starting ${selectedClass.name} spells.`}
      </p>

      {cantripsAllowed > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[13px] font-semibold text-ink">Cantrips</h3>
            <span className="font-heading text-[9px] font-medium text-faint">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchCantrips()}
                placeholder="Search cantrips..."
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
            <button onClick={searchCantrips} className="bg-ink text-card font-heading text-[9px] font-semibold px-3 hover:opacity-90 transition-opacity">Search</button>
          </div>

          {selectedCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedCantrips.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[11px] font-medium border border-rule">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-muted hover:text-ink"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}

          <div className="max-h-48 overflow-y-auto space-y-1">
            {cantripResults.map(spell => {
              const selected = selectedCantrips.some(s => s.id === spell.id);
              const disabled = !selected && selectedCantrips.length >= cantripsAllowed;
              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleCantrip(spell)}
                  disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[13px] font-medium transition-colors ${
                    selected ? 'bg-page-alt text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}
                >
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-ink" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {spellsAllowed > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[13px] font-semibold text-ink">
              {isWizard
                ? (maxLevel > 1 ? `Spellbook (Level 1-${maxLevel})` : 'Spellbook (Level 1)')
                : (maxLevel > 1 ? `Spells (Level 1-${maxLevel})` : 'Level 1 Spells')}
            </h3>
            <span className="font-heading text-[9px] font-medium text-faint">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchSpellsList()}
                placeholder={maxLevel > 1 ? `Search level 1-${maxLevel} spells...` : "Search level 1 spells..."}
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
            <button onClick={searchSpellsList} className="bg-ink text-card font-heading text-[9px] font-semibold px-3 hover:opacity-90 transition-opacity">Search</button>
          </div>

          {selectedSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedSpells.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[11px] font-medium border border-rule">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-muted hover:text-ink"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}

          <div className="max-h-48 overflow-y-auto space-y-1">
            {spellResults.map(spell => {
              const selected = selectedSpells.some(s => s.id === spell.id);
              const disabled = !selected && selectedSpells.length >= spellsAllowed;
              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleSpell(spell)}
                  disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[13px] font-medium transition-colors ${
                    selected ? 'bg-page-alt text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    <span className="text-[11px] text-faint">{spell.school}</span>
                  </div>
                  {selected && <Check className="w-4 h-4 text-ink" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {isPrepared && (
        <div className="bg-card border border-rule p-4">
          <h3 className="font-heading text-[13px] font-semibold text-ink mb-2">Prepared Spells</h3>
          <p className="font-body text-[13px] font-medium text-muted">
            As a {selectedClass.name}, you prepare spells each day from your class spell list.
            You can change your prepared spells from the character sheet after creation.
          </p>
        </div>
      )}
    </div>
  );
}

function MulticlassSpellSection({
  classEntry,
  selectedCantrips, onCantripsChange,
  selectedSpells, onSpellsChange,
}: {
  classEntry: ClassEntry;
  selectedCantrips: Spell[];
  onCantripsChange: (cantrips: Spell[]) => void;
  selectedSpells: Spell[];
  onSpellsChange: (spells: Spell[]) => void;
}) {
  const cls = classEntry.cls;
  const classLevel = classEntry.level;
  const isWizard = cls.name === 'Wizard';
  const cantripsAllowed = CANTRIPS_KNOWN[cls.name]?.[classLevel] ?? 0;
  const spellsAllowed = isWizard
    ? wizardSpellbookCount(classLevel)
    : cls.isKnownCaster ? (SPELLS_KNOWN[cls.name]?.[classLevel] ?? 0) : 0;
  const isPrepared = cls.isPreparedCaster && !isWizard;
  const maxLevel = maxSpellLevel(cls.name, classLevel);

  const [cantripResults, setCantripResults] = useState<Spell[]>([]);
  const [spellResults, setSpellResults] = useState<Spell[]>([]);
  const [cantripSearch, setCantripSearch] = useState('');
  const [spellSearch, setSpellSearch] = useState('');

  useEffect(() => {
    if (cantripsAllowed > 0) {
      searchSpells({ className: cls.name, level: 0, size: 50 })
        .then(res => setCantripResults(res.content))
        .catch(() => {});
    }
  }, [cls.name]);

  useEffect(() => {
    if ((spellsAllowed > 0 || (isPrepared && !isWizard)) && maxLevel > 0) {
      Promise.all(
        Array.from({ length: maxLevel }, (_, i) => i + 1).map(lvl =>
          searchSpells({ className: cls.name, level: lvl, size: 50 }).then(r => r.content)
        )
      ).then(results => {
        const all = results.flat();
        const seen = new Set<string>();
        setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
      }).catch(() => {});
    }
  }, [cls.name, maxLevel]);

  function doSearchCantrips() {
    const params: Record<string, unknown> = { className: cls.name, level: 0, size: 50 };
    if (cantripSearch.trim()) params.name = cantripSearch.trim();
    searchSpells(params as any).then(res => setCantripResults(res.content)).catch(() => {});
  }

  function doSearchSpells() {
    if (maxLevel <= 0) return;
    Promise.all(
      Array.from({ length: maxLevel }, (_, i) => i + 1).map(lvl => {
        const params: Record<string, unknown> = { className: cls.name, level: lvl, size: 50 };
        if (spellSearch.trim()) params.name = spellSearch.trim();
        return searchSpells(params as any).then(r => r.content);
      })
    ).then(results => {
      const all = results.flat();
      const seen = new Set<string>();
      setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
    }).catch(() => {});
  }

  function toggleCantrip(spell: Spell) {
    const exists = selectedCantrips.some(s => s.id === spell.id);
    if (exists) onCantripsChange(selectedCantrips.filter(s => s.id !== spell.id));
    else if (selectedCantrips.length < cantripsAllowed) onCantripsChange([...selectedCantrips, spell]);
  }

  function toggleSpell(spell: Spell) {
    const exists = selectedSpells.some(s => s.id === spell.id);
    if (exists) onSpellsChange(selectedSpells.filter(s => s.id !== spell.id));
    else if (spellsAllowed === 0 || selectedSpells.length < spellsAllowed) onSpellsChange([...selectedSpells, spell]);
  }

  return (
    <div className="space-y-6">
      <h2 className="font-heading text-[15px] font-semibold text-ink">{cls.name} Spells</h2>
      <p className="font-body text-[13px] font-medium text-muted">
        {isWizard
          ? `Select spells for your starting spellbook (${wizardSpellbookCount(classLevel)} spells at class level ${classLevel}).`
          : `Select your starting ${cls.name} spells (class level ${classLevel}).`}
      </p>

      {cantripsAllowed > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[13px] font-semibold text-ink">Cantrips</h3>
            <span className="font-heading text-[9px] font-medium text-faint">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && doSearchCantrips()}
                placeholder="Search cantrips..."
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
            <button onClick={doSearchCantrips} className="bg-ink text-card font-heading text-[9px] font-semibold px-3 hover:opacity-90 transition-opacity">Search</button>
          </div>
          {selectedCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedCantrips.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[11px] font-medium border border-rule">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-muted hover:text-ink"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}
          <div className="max-h-48 overflow-y-auto space-y-1">
            {cantripResults.map(spell => {
              const selected = selectedCantrips.some(s => s.id === spell.id);
              const disabled = !selected && selectedCantrips.length >= cantripsAllowed;
              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleCantrip(spell)}
                  disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[13px] font-medium transition-colors ${
                    selected ? 'bg-page-alt text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}
                >
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-ink" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {spellsAllowed > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[13px] font-semibold text-ink">
              {isWizard
                ? (maxLevel > 1 ? `Spellbook (Level 1-${maxLevel})` : 'Spellbook (Level 1)')
                : (maxLevel > 1 ? `Spells (Level 1-${maxLevel})` : 'Level 1 Spells')}
            </h3>
            <span className="font-heading text-[9px] font-medium text-faint">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && doSearchSpells()}
                placeholder={maxLevel > 1 ? `Search level 1-${maxLevel} spells...` : "Search level 1 spells..."}
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
            <button onClick={doSearchSpells} className="bg-ink text-card font-heading text-[9px] font-semibold px-3 hover:opacity-90 transition-opacity">Search</button>
          </div>
          {selectedSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedSpells.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[11px] font-medium border border-rule">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-muted hover:text-ink"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}
          <div className="max-h-48 overflow-y-auto space-y-1">
            {spellResults.map(spell => {
              const selected = selectedSpells.some(s => s.id === spell.id);
              const disabled = !selected && selectedSpells.length >= spellsAllowed;
              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleSpell(spell)}
                  disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[13px] font-medium transition-colors ${
                    selected ? 'bg-page-alt text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    <span className="text-[11px] text-faint">{spell.school}</span>
                  </div>
                  {selected && <Check className="w-4 h-4 text-ink" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {isPrepared && (
        <div className="bg-card border border-rule p-4">
          <h3 className="font-heading text-[13px] font-semibold text-ink mb-2">Prepared Spells</h3>
          <p className="font-body text-[13px] font-medium text-muted">
            As a {cls.name}, you prepare spells each day from your class spell list.
            You can change your prepared spells from the character sheet after creation.
          </p>
        </div>
      )}
    </div>
  );
}

function ThirdCasterSpellSection({
  classEntry,
  selectedCantrips, onCantripsChange,
  selectedSpells, onSpellsChange,
}: {
  classEntry: ClassEntry;
  selectedCantrips: Spell[];
  onCantripsChange: (cantrips: Spell[]) => void;
  selectedSpells: Spell[];
  onSpellsChange: (spells: Spell[]) => void;
}) {
  const subclassName = classEntry.subclass!.name;
  const classLevel = classEntry.level;
  const spellListClass = THIRD_CASTER_SPELL_LIST[subclassName] || 'Wizard';
  const cantripsAllowed = THIRD_CASTER_CANTRIPS[subclassName]?.[classLevel] ?? 0;
  const spellsAllowed = THIRD_CASTER_SPELLS[subclassName]?.[classLevel] ?? 0;
  const maxLevel = maxSpellLevel(classEntry.cls.name, classLevel, subclassName);

  const [cantripResults, setCantripResults] = useState<Spell[]>([]);
  const [spellResults, setSpellResults] = useState<Spell[]>([]);
  const [cantripSearch, setCantripSearch] = useState('');
  const [spellSearch, setSpellSearch] = useState('');

  useEffect(() => {
    if (cantripsAllowed > 0) {
      searchSpells({ className: spellListClass, level: 0, size: 50 })
        .then(res => setCantripResults(res.content))
        .catch(() => {});
    }
  }, [spellListClass]);

  useEffect(() => {
    if (spellsAllowed > 0 && maxLevel > 0) {
      Promise.all(
        Array.from({ length: maxLevel }, (_, i) => i + 1).map(lvl =>
          searchSpells({ className: spellListClass, level: lvl, size: 50 }).then(r => r.content)
        )
      ).then(results => {
        const all = results.flat();
        const seen = new Set<string>();
        setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
      }).catch(() => {});
    }
  }, [spellListClass, maxLevel]);

  function toggleCantrip(spell: Spell) {
    const exists = selectedCantrips.some(s => s.id === spell.id);
    if (exists) onCantripsChange(selectedCantrips.filter(s => s.id !== spell.id));
    else if (selectedCantrips.length < cantripsAllowed) onCantripsChange([...selectedCantrips, spell]);
  }

  function toggleSpell(spell: Spell) {
    const exists = selectedSpells.some(s => s.id === spell.id);
    if (exists) onSpellsChange(selectedSpells.filter(s => s.id !== spell.id));
    else if (selectedSpells.length < spellsAllowed) onSpellsChange([...selectedSpells, spell]);
  }

  return (
    <div className="space-y-6">
      <h2 className="font-heading text-[15px] font-semibold text-ink">{subclassName} Spells</h2>
      <p className="font-body text-[13px] font-medium text-muted">
        Select spells from the {spellListClass} spell list for your {subclassName} (1/3 caster).
      </p>

      {cantripsAllowed > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[13px] font-semibold text-ink">Cantrips</h3>
            <span className="font-heading text-[9px] font-medium text-faint">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter') {
                    const params: Record<string, unknown> = { className: spellListClass, level: 0, size: 50 };
                    if (cantripSearch.trim()) params.name = cantripSearch.trim();
                    searchSpells(params as any).then(res => setCantripResults(res.content)).catch(() => {});
                  }
                }}
                placeholder="Search cantrips..."
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
          </div>
          {selectedCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedCantrips.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[11px] font-medium border border-rule">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-muted hover:text-ink"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}
          <div className="max-h-48 overflow-y-auto space-y-1">
            {cantripResults.map(spell => {
              const selected = selectedCantrips.some(s => s.id === spell.id);
              const disabled = !selected && selectedCantrips.length >= cantripsAllowed;
              return (
                <button key={spell.id} onClick={() => !disabled && toggleCantrip(spell)} disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[13px] font-medium transition-colors ${
                    selected ? 'bg-page-alt text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}>
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-ink" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {spellsAllowed > 0 && maxLevel > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[13px] font-semibold text-ink">
              {maxLevel > 1 ? `Spells (Level 1-${maxLevel})` : 'Level 1 Spells'}
            </h3>
            <span className="font-heading text-[9px] font-medium text-faint">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter') {
                    Promise.all(
                      Array.from({ length: maxLevel }, (_, i) => i + 1).map(lvl => {
                        const params: Record<string, unknown> = { className: spellListClass, level: lvl, size: 50 };
                        if (spellSearch.trim()) params.name = spellSearch.trim();
                        return searchSpells(params as any).then(r => r.content);
                      })
                    ).then(results => {
                      const all = results.flat();
                      const seen = new Set<string>();
                      setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
                    }).catch(() => {});
                  }
                }}
                placeholder={maxLevel > 1 ? `Search level 1-${maxLevel} spells...` : "Search level 1 spells..."}
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
          </div>
          {selectedSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedSpells.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[11px] font-medium border border-rule">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-muted hover:text-ink"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}
          <div className="max-h-48 overflow-y-auto space-y-1">
            {spellResults.map(spell => {
              const selected = selectedSpells.some(s => s.id === spell.id);
              const disabled = !selected && selectedSpells.length >= spellsAllowed;
              return (
                <button key={spell.id} onClick={() => !disabled && toggleSpell(spell)} disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[13px] font-medium transition-colors ${
                    selected ? 'bg-page-alt text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}>
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    <span className="text-[11px] text-faint">{spell.school}</span>
                  </div>
                  {selected && <Check className="w-4 h-4 text-ink" />}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

function FeatSpellSection({
  featName, option,
  featCantrips, setFeatCantrips,
  featSpells, setFeatSpells,
}: {
  featName: string;
  option: ParsedFeatOption;
  featCantrips: Spell[];
  setFeatCantrips: (s: Spell[]) => void;
  featSpells: Spell[];
  setFeatSpells: (s: Spell[]) => void;
}) {
  const [cantripResults, setCantripResults] = useState<Spell[]>([]);
  const [spellResults, setSpellResults] = useState<Spell[]>([]);
  const [cantripSearch, setCantripSearch] = useState('');
  const [spellSearch, setSpellSearch] = useState('');

  const cantripChoice = option.cantripChoice;
  const spellChoice = option.spellChoice;

  useEffect(() => {
    if (cantripChoice && cantripChoice.classes.length > 0) {
      Promise.all(cantripChoice.classes.map(cls =>
        searchSpells({ className: cls, level: 0, size: 50 }).then(r => r.content)
      )).then(results => {
        const all = results.flat();
        const seen = new Set<string>();
        setCantripResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
      }).catch(() => {});
    }
    if (spellChoice && spellChoice.classes.length > 0) {
      Promise.all(spellChoice.classes.map(cls =>
        searchSpells({ className: cls, level: 1, size: 50 }).then(r => r.content)
      )).then(results => {
        const all = results.flat();
        const seen = new Set<string>();
        setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
      }).catch(() => {});
    }
    if (spellChoice && spellChoice.fromList) {
      const fakeSpells: Spell[] = spellChoice.fromList.map((name, i) => ({
        id: `feat-list-${i}`,
        name,
        level: 1,
        school: null,
        castingTime: null,
        rangeDistance: null,
        components: null,
        duration: null,
        concentration: false,
        ritual: false,
        description: null,
        higherLevels: null,
        classes: null,
        damageType: null,
        damageDice: null,
        saveAbility: null,
        source: null,
      }));
      setSpellResults(fakeSpells);
    }
  }, [option.name]);

  function searchCantripsList() {
    if (!cantripChoice) return;
    Promise.all(cantripChoice.classes.map(cls => {
      const params: Record<string, unknown> = { className: cls, level: 0, size: 50 };
      if (cantripSearch.trim()) params.name = cantripSearch.trim();
      return searchSpells(params as any).then(r => r.content);
    })).then(results => {
      const all = results.flat();
      const seen = new Set<string>();
      setCantripResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
    }).catch(() => {});
  }

  function searchSpellsList() {
    if (!spellChoice || spellChoice.fromList) return;
    Promise.all(spellChoice.classes.map(cls => {
      const params: Record<string, unknown> = { className: cls, level: 1, size: 50 };
      if (spellSearch.trim()) params.name = spellSearch.trim();
      return searchSpells(params as any).then(r => r.content);
    })).then(results => {
      const all = results.flat();
      const seen = new Set<string>();
      setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
    }).catch(() => {});
  }

  function toggleCantrip(spell: Spell) {
    const exists = featCantrips.some(s => s.name === spell.name);
    if (exists) {
      setFeatCantrips(featCantrips.filter(s => s.name !== spell.name));
    } else if (cantripChoice && featCantrips.length < cantripChoice.count) {
      setFeatCantrips([...featCantrips, spell]);
    }
  }

  function toggleSpell(spell: Spell) {
    const exists = featSpells.some(s => s.name === spell.name);
    if (exists) {
      setFeatSpells(featSpells.filter(s => s.name !== spell.name));
    } else if (spellChoice && featSpells.length < spellChoice.count) {
      setFeatSpells([...featSpells, spell]);
    }
  }

  const filteredSpellResults = useMemo(() => {
    if (!spellChoice?.fromList) return spellResults;
    if (!spellSearch.trim()) return spellResults;
    const q = spellSearch.trim().toLowerCase();
    return spellResults.filter(s => s.name.toLowerCase().includes(q));
  }, [spellResults, spellSearch, spellChoice]);

  return (
    <div className="space-y-6">
      <h2 className="font-heading text-[15px] font-semibold text-ink">{featName} Spells</h2>
      <p className="font-body text-[13px] font-medium text-muted">
        Select spells granted by your {featName} feat{option.name !== `Option 1` ? ` (${option.name})` : ''}.
      </p>

      {cantripChoice && (
        <div className="bg-card border border-buff-border p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[13px] font-semibold text-ink">Cantrips</h3>
            <span className="font-heading text-[9px] font-medium text-faint">{featCantrips.length}/{cantripChoice.count} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchCantripsList()}
                placeholder={`Search ${cantripChoice.classes.join('/')} cantrips...`}
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
            <button onClick={searchCantripsList} className="bg-ink text-card font-heading text-[9px] font-semibold px-3 hover:opacity-90 transition-opacity">Search</button>
          </div>

          {featCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {featCantrips.map(s => (
                <span key={s.name} className="flex items-center gap-1 bg-buff-bg text-ink px-2 py-1 font-body text-[11px] font-medium border border-buff-border">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-muted hover:text-ink"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}

          <div className="max-h-48 overflow-y-auto space-y-1">
            {cantripResults.map(spell => {
              const selected = featCantrips.some(s => s.name === spell.name);
              const disabled = !selected && featCantrips.length >= cantripChoice.count;
              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleCantrip(spell)}
                  disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[13px] font-medium transition-colors ${
                    selected ? 'bg-buff-bg text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}
                >
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-buff" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {spellChoice && (
        <div className="bg-card border border-buff-border p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[13px] font-semibold text-ink">
              {spellChoice.fromList ? 'Choose Spells' : 'Level 1 Spells'}
            </h3>
            <span className="font-heading text-[9px] font-medium text-faint">
              {featSpells.length}/{spellChoice.count} selected ({spellChoice.usesPerDay}/day each)
            </span>
          </div>

          {!spellChoice.fromList && (
            <div className="flex gap-2">
              <div className="relative flex-1">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
                <input
                  value={spellSearch}
                  onChange={e => setSpellSearch(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && searchSpellsList()}
                  placeholder={`Search ${spellChoice.classes.join('/')} spells...`}
                  className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
                />
              </div>
              <button onClick={searchSpellsList} className="bg-ink text-card font-heading text-[9px] font-semibold px-3 hover:opacity-90 transition-opacity">Search</button>
            </div>
          )}

          {spellChoice.fromList && (
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                placeholder="Filter spells..."
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
          )}

          {featSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {featSpells.map(s => (
                <span key={s.name} className="flex items-center gap-1 bg-buff-bg text-ink px-2 py-1 font-body text-[11px] font-medium border border-buff-border">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-muted hover:text-ink"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}

          <div className="max-h-48 overflow-y-auto space-y-1">
            {filteredSpellResults.map(spell => {
              const selected = featSpells.some(s => s.name === spell.name);
              const disabled = !selected && featSpells.length >= spellChoice.count;
              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleSpell(spell)}
                  disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[13px] font-medium transition-colors ${
                    selected ? 'bg-buff-bg text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    {spell.school && <span className="text-[11px] text-faint">{spell.school}</span>}
                  </div>
                  {selected && <Check className="w-4 h-4 text-buff" />}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
