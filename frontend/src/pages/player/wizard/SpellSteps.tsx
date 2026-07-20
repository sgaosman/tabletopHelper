import { useState, useEffect, useMemo } from 'react';
import { Search, X, Check } from 'lucide-react';
import { searchSpells } from '../../../api/referenceApi';
import type { Spell, CharacterClassRef } from '../../../types/reference';
import type { ParsedFeatOption } from '../../../utils/featSpellParser';
import { CANTRIPS_KNOWN, SPELLS_KNOWN, THIRD_CASTER_CANTRIPS, THIRD_CASTER_SPELLS, THIRD_CASTER_SPELL_LIST, maxSpellLevel, wizardSpellbookCount } from '../../../utils/spellConstants';
import type { ClassEntry } from './constants';

export function FeatSpellSelectionStep({
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
      <h2 className="text-xl font-semibold text-white">{featName} Spells</h2>
      <p className="text-gray-400 text-sm">
        Select spells granted by your {featName} feat{option.name !== `Option 1` ? ` (${option.name})` : ''}.
      </p>

      {cantripChoice && (
        <div className="bg-gray-900 border border-amber-800/50 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">Cantrips</h3>
            <span className="text-xs text-gray-400">{featCantrips.length}/{cantripChoice.count} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchCantripsList()}
                placeholder={`Search ${cantripChoice.classes.join('/')} cantrips...`}
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-amber-500"
              />
            </div>
            <button onClick={searchCantripsList} className="bg-amber-600 hover:bg-amber-500 text-white text-xs px-3 rounded-md">Search</button>
          </div>

          {featCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {featCantrips.map(s => (
                <span key={s.name} className="flex items-center gap-1 bg-amber-900/50 text-amber-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-amber-400 hover:text-white"><X className="w-3 h-3" /></button>
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-amber-900/30 text-amber-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}
                >
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-amber-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {spellChoice && (
        <div className="bg-gray-900 border border-amber-800/50 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">
              {spellChoice.fromList ? 'Choose Spells' : 'Level 1 Spells'}
            </h3>
            <span className="text-xs text-gray-400">
              {featSpells.length}/{spellChoice.count} selected ({spellChoice.usesPerDay}/day each)
            </span>
          </div>

          {!spellChoice.fromList && (
            <div className="flex gap-2">
              <div className="relative flex-1">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
                <input
                  value={spellSearch}
                  onChange={e => setSpellSearch(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && searchSpellsList()}
                  placeholder={`Search ${spellChoice.classes.join('/')} spells...`}
                  className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-amber-500"
                />
              </div>
              <button onClick={searchSpellsList} className="bg-amber-600 hover:bg-amber-500 text-white text-xs px-3 rounded-md">Search</button>
            </div>
          )}

          {spellChoice.fromList && (
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                placeholder="Filter spells..."
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-amber-500"
              />
            </div>
          )}

          {featSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {featSpells.map(s => (
                <span key={s.name} className="flex items-center gap-1 bg-amber-900/50 text-amber-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-amber-400 hover:text-white"><X className="w-3 h-3" /></button>
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-amber-900/30 text-amber-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    {spell.school && <span className="text-xs text-gray-500">{spell.school}</span>}
                  </div>
                  {selected && <Check className="w-4 h-4 text-amber-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

export function MulticlassSpellSelectionStep({
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
      <h2 className="text-xl font-semibold text-white">{cls.name} Spells</h2>
      <p className="text-gray-400 text-sm">
        {isWizard
          ? `Select spells for your starting spellbook (${wizardSpellbookCount(classLevel)} spells at class level ${classLevel}).`
          : `Select your starting ${cls.name} spells (class level ${classLevel}).`}
      </p>

      {cantripsAllowed > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">Cantrips</h3>
            <span className="text-xs text-gray-400">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && doSearchCantrips()}
                placeholder="Search cantrips..."
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <button onClick={doSearchCantrips} className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs px-3 rounded-md">Search</button>
          </div>
          {selectedCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedCantrips.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-indigo-900/50 text-indigo-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-indigo-400 hover:text-white"><X className="w-3 h-3" /></button>
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-indigo-900/30 text-indigo-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}
                >
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-indigo-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {spellsAllowed > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">
              {isWizard
                ? (maxLevel > 1 ? `Spellbook (Level 1-${maxLevel})` : 'Spellbook (Level 1)')
                : (maxLevel > 1 ? `Spells (Level 1-${maxLevel})` : 'Level 1 Spells')}
            </h3>
            <span className="text-xs text-gray-400">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && doSearchSpells()}
                placeholder={maxLevel > 1 ? `Search level 1-${maxLevel} spells...` : "Search level 1 spells..."}
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <button onClick={doSearchSpells} className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs px-3 rounded-md">Search</button>
          </div>
          {selectedSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedSpells.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-indigo-900/50 text-indigo-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-indigo-400 hover:text-white"><X className="w-3 h-3" /></button>
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-indigo-900/30 text-indigo-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    <span className="text-xs text-gray-500">{spell.school}</span>
                  </div>
                  {selected && <Check className="w-4 h-4 text-indigo-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {isPrepared && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-white font-semibold text-sm mb-2">Prepared Spells</h3>
          <p className="text-gray-400 text-sm">
            As a {cls.name}, you prepare spells each day from your class spell list.
            You can change your prepared spells from the character sheet after creation.
          </p>
        </div>
      )}
    </div>
  );
}

export function ThirdCasterSpellSelectionStep({
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
      <h2 className="text-xl font-semibold text-white">{subclassName} Spells</h2>
      <p className="text-gray-400 text-sm">
        Select spells from the {spellListClass} spell list for your {subclassName} (1/3 caster).
      </p>

      {cantripsAllowed > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">Cantrips</h3>
            <span className="text-xs text-gray-400">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
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
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
          </div>
          {selectedCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedCantrips.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-indigo-900/50 text-indigo-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-indigo-400 hover:text-white"><X className="w-3 h-3" /></button>
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-indigo-900/30 text-indigo-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}>
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-indigo-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {spellsAllowed > 0 && maxLevel > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">
              {maxLevel > 1 ? `Spells (Level 1-${maxLevel})` : 'Level 1 Spells'}
            </h3>
            <span className="text-xs text-gray-400">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
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
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
          </div>
          {selectedSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedSpells.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-indigo-900/50 text-indigo-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-indigo-400 hover:text-white"><X className="w-3 h-3" /></button>
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-indigo-900/30 text-indigo-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}>
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    <span className="text-xs text-gray-500">{spell.school}</span>
                  </div>
                  {selected && <Check className="w-4 h-4 text-indigo-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

export function SpellSelectionStep({
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
      <h2 className="text-xl font-semibold text-white">{title ?? 'Choose Spells'}</h2>
      <p className="text-gray-400 text-sm">
        {isWizard
          ? `Select spells for your starting spellbook. You begin with ${wizardSpellbookCount(level)} spells.`
          : `Select your starting ${selectedClass.name} spells.`}
      </p>

      {cantripsAllowed > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">Cantrips</h3>
            <span className="text-xs text-gray-400">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchCantrips()}
                placeholder="Search cantrips..."
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <button onClick={searchCantrips} className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs px-3 rounded-md">Search</button>
          </div>

          {selectedCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedCantrips.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-indigo-900/50 text-indigo-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-indigo-400 hover:text-white"><X className="w-3 h-3" /></button>
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-indigo-900/30 text-indigo-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}
                >
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-indigo-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {spellsAllowed > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">
              {isWizard
                ? (maxLevel > 1 ? `Spellbook (Level 1-${maxLevel})` : 'Spellbook (Level 1)')
                : (maxLevel > 1 ? `Spells (Level 1-${maxLevel})` : 'Level 1 Spells')}
            </h3>
            <span className="text-xs text-gray-400">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchSpellsList()}
                placeholder={maxLevel > 1 ? `Search level 1-${maxLevel} spells...` : "Search level 1 spells..."}
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <button onClick={searchSpellsList} className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs px-3 rounded-md">Search</button>
          </div>

          {selectedSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedSpells.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-indigo-900/50 text-indigo-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-indigo-400 hover:text-white"><X className="w-3 h-3" /></button>
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-indigo-900/30 text-indigo-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    <span className="text-xs text-gray-500">{spell.school}</span>
                  </div>
                  {selected && <Check className="w-4 h-4 text-indigo-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {isPrepared && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-white font-semibold text-sm mb-2">Prepared Spells</h3>
          <p className="text-gray-400 text-sm">
            As a {selectedClass.name}, you prepare spells each day from your class spell list.
            You can change your prepared spells from the character sheet after creation.
          </p>
        </div>
      )}
    </div>
  );
}
