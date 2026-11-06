import { useEffect } from 'react';
import { Check, Search, X } from 'lucide-react';
import type { CharacterClassRef, Spell } from '../../../types/reference';
import { searchSpells } from '../../../api/referenceApi';
import { useTypeAhead } from '../../../hooks/useTypeAhead';
import { useClassTables } from '../../../queries/reference';
import {
  type ClassTables, cantripsKnown, highestSpellLevel, spellbookSize, spellsKnown,
} from '../../../utils/classTables';
import type { ClassEntry } from './types';

// The wizard chooses the 1st level's spells; every later level's are Pending Choices made on the sheet (D159)
const FIRST_LEVEL = 1;

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
}

/**
 * The cantrips and spells of the first class's 1st level, as many as that level gives whatever the character's level.
 * The cantrips and spells of every later level, of a second class, and an Eldritch Knight's or Arcane Trickster's are
 * Pending Choices, made on the character sheet after creation (D159), as are a feat's spells, which come with the
 * feat.
 */
export default function SpellsStep({
  selectedClass, classEntries, level,
  selectedCantrips, setSelectedCantrips,
  selectedSpells, setSelectedSpells,
  cantripResults, setCantripResults,
  spellResults, setSpellResults,
  cantripSearch, setCantripSearch,
  spellSearch, setSpellSearch,
}: SpellsStepProps) {
  const later = level > 1 || classEntries.length > 1;
  const { data: tables, isError } = useClassTables();
  if (!tables) {
    return (
      <p className="font-body text-[15px] font-semibold text-muted">
        {isError ? 'The class tables could not be loaded. Reload the page to try again.' : 'Loading the class tables…'}
      </p>
    );
  }
  return (
    <div className="space-y-8">
      {selectedClass?.isSpellcaster && (() => {
        const maxLvl = highestSpellLevel(tables, selectedClass.name, FIRST_LEVEL);
        const cantrips = cantripsKnown(tables, selectedClass.name, FIRST_LEVEL);
        if (maxLvl === 0 && cantrips === 0) {
          return (
            <div className="bg-card border border-rule p-4">
              <h2 className="font-heading text-[17px] font-semibold text-ink mb-2">
                {classEntries.length > 1 ? `${selectedClass.name} Spells` : 'Spells'}
              </h2>
              <p className="font-body text-[15px] font-semibold text-muted">
                {selectedClass.name} does not gain spellcasting at level 1, so no spell selection is needed now.
                {later && ' The spells of later levels are chosen on your character sheet after creation.'}
              </p>
            </div>
          );
        }
        return (
          <SpellSelectionSection
            tables={tables}
            selectedClass={selectedClass}
            level={FIRST_LEVEL}
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
      {later && (
        <div className="bg-cls-monk-bg border border-div-border p-4">
          <p className="font-body text-[13px] font-semibold text-muted">
            The cantrips and spells of your levels above the 1st{classEntries.length > 1 ? ', and of your other classes,' : ''}
            {' '}are chosen on your character sheet after creation.
          </p>
        </div>
      )}
      {(() => {
        const warnings: string[] = [];
        if (selectedClass?.isSpellcaster) {
          const cantripsAllowed = cantripsKnown(tables, selectedClass.name, FIRST_LEVEL);
          const isWiz = selectedClass.name === 'Wizard';
          const spellsAllowed = isWiz ? spellbookSize(tables, selectedClass.name, FIRST_LEVEL)
            : selectedClass.isKnownCaster ? spellsKnown(tables, selectedClass.name, FIRST_LEVEL) : 0;
          if (cantripsAllowed > 0 && selectedCantrips.length < cantripsAllowed) {
            warnings.push(`${selectedClass.name}: ${selectedCantrips.length}/${cantripsAllowed} cantrips selected`);
          }
          if (spellsAllowed > 0 && selectedSpells.length < spellsAllowed) {
            warnings.push(`${selectedClass.name}: ${selectedSpells.length}/${spellsAllowed} spells selected`);
          }
        }
        if (warnings.length === 0) return null;
        return (
          <div className="bg-buff-bg border border-buff-border p-3">
            <p className="font-body text-[13px] font-semibold text-hp-wounded">
              You haven't selected all available spells. You can still proceed, but your character may be underpowered.
            </p>
            <ul className="font-body text-[13px] font-semibold text-buff mt-1 list-disc list-inside">
              {warnings.map(w => <li key={w}>{w}</li>)}
            </ul>
          </div>
        );
      })()}
    </div>
  );
}

function SpellSelectionSection({
  tables, selectedClass, level,
  selectedCantrips, setSelectedCantrips,
  selectedSpells, setSelectedSpells,
  cantripResults, setCantripResults,
  spellResults, setSpellResults,
  cantripSearch, setCantripSearch,
  spellSearch, setSpellSearch,
  title,
}: {
  tables: ClassTables;
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
  const cantripsAllowed = cantripsKnown(tables, selectedClass.name, level);
  const isWizard = selectedClass.name === 'Wizard';
  const spellbook = spellbookSize(tables, selectedClass.name, level);
  const spellsAllowed = isWizard
    ? spellbook
    : selectedClass.isKnownCaster
    ? spellsKnown(tables, selectedClass.name, level)
    : 0;
  const isPrepared = selectedClass.isPreparedCaster && !isWizard;
  const maxLevel = highestSpellLevel(tables, selectedClass.name, level);

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

  // Results follow the search boxes as you type; Enter searches straight away
  const { searchNow: searchCantrips } = useTypeAhead(cantripSearch, () => {
    const params: Record<string, unknown> = { className: selectedClass.name, level: 0, size: 50 };
    if (cantripSearch.trim()) params.name = cantripSearch.trim();
    return searchSpells(params as any).then(res => res.content);
  }, setCantripResults);

  const { searchNow: searchSpellsList } = useTypeAhead(spellSearch, () => {
    if (maxLevel <= 0) return;
    return Promise.all(
      Array.from({ length: maxLevel }, (_, i) => i + 1).map(lvl => {
        const params: Record<string, unknown> = { className: selectedClass.name, level: lvl, size: 50 };
        if (spellSearch.trim()) params.name = spellSearch.trim();
        return searchSpells(params as any).then(r => r.content);
      })
    ).then(results => {
      const all = results.flat();
      const seen = new Set<string>();
      return all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; });
    });
  }, setSpellResults);

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
      <h2 className="font-heading text-[17px] font-semibold text-ink">{title ?? 'Choose Spells'}</h2>
      <p className="font-body text-[15px] font-semibold text-muted">
        {isWizard
          ? `Select spells for your starting spellbook. You begin with ${spellbook} spells.`
          : `Select your starting ${selectedClass.name} spells.`}
      </p>

      {cantripsAllowed > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[15px] font-semibold text-ink">Cantrips</h3>
            <span className="font-heading text-[11px] font-semibold text-faint">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchCantrips()}
                placeholder="Search cantrips..."
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[15px] font-semibold text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
          </div>

          {selectedCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedCantrips.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[13px] font-semibold border border-rule">
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[15px] font-semibold transition-colors ${
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
            <h3 className="font-heading text-[15px] font-semibold text-ink">
              {isWizard
                ? (maxLevel > 1 ? `Spellbook (Level 1-${maxLevel})` : 'Spellbook (Level 1)')
                : (maxLevel > 1 ? `Spells (Level 1-${maxLevel})` : 'Level 1 Spells')}
            </h3>
            <span className="font-heading text-[11px] font-semibold text-faint">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchSpellsList()}
                placeholder={maxLevel > 1 ? `Search level 1-${maxLevel} spells...` : "Search level 1 spells..."}
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[15px] font-semibold text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
          </div>

          {selectedSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedSpells.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[13px] font-semibold border border-rule">
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
                  className={`w-full flex items-center justify-between py-1.5 px-3 font-body text-[15px] font-semibold transition-colors ${
                    selected ? 'bg-page-alt text-ink' : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    <span className="text-[13px] text-faint">{spell.school}</span>
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
          <h3 className="font-heading text-[15px] font-semibold text-ink mb-2">Prepared Spells</h3>
          <p className="font-body text-[15px] font-semibold text-muted">
            As a {selectedClass.name}, you prepare spells each day from your class spell list.
            You can change your prepared spells from the character sheet after creation.
          </p>
        </div>
      )}
    </div>
  );
}
