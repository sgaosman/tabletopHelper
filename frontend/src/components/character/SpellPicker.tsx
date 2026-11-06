import { useEffect, useState } from 'react';
import { Lock, Plus, Search } from 'lucide-react';
import { useSpellSearch } from '../../queries/reference';
import type { Spell, SpellSearchParams } from '../../types/reference';

/** What a row says beside its spell. With none, a chosen spell says Chosen and the others show a plus. */
export type SpellStatus = 'Known' | 'In Spellbook' | 'Always' | 'Prepared' | 'Chosen' | 'In list' | 'Remove' | 'Removed';

/** A spell's row as the picker's user sees it: what it says, and whether it can be clicked. */
export interface SpellState {
  status?: SpellStatus;
  disabled?: boolean;
}

/** Which spells the picker offers. */
export interface SpellFilter {
  /** The spell list searched, as the spells table names it, such as "Wizard"; none searches every spell */
  className?: string;
  /** The spell levels offered, 0 for cantrips; the level filter lists them when there are two or more */
  levels: number[];
  /** Only spells of these schools */
  schools?: string[];
  /** Only these spells, by name in any case */
  names?: string[];
}

interface SpellPickerProps {
  /** The chosen spells, by name */
  value: string[];
  /** The chosen spells after a click, and the spell clicked */
  onChange: (value: string[], spell: Spell) => void;
  /** The most that can be chosen; the others wait until one goes */
  max?: number;
  filter: SpellFilter;
  /** What a spell's row says, and whether it is disabled, as the spell's place on the sheet decides */
  statusOf?: (spell: Spell, chosen: boolean) => SpellState | undefined;
  /** For the spellbook modes: these spells are filtered on the page, and the spell list isn't searched */
  localSpells?: Spell[];
  /** The search box's placeholder and name */
  placeholder?: string;
  /** What the list says when nothing matches */
  emptyText?: string;
  /** How long typing has to stop before the search runs; Enter runs it at once */
  debounceMs?: number;
}

// A page large enough for a named list, which the server can't filter by name, to come back whole
const PAGE_SIZE = 50;
const NAMED_PAGE_SIZE = 1000;

const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();

function rowClass(state: SpellState, chosen: boolean, disabled: boolean): string {
  if (state.status === 'Always') return 'bg-cls-cleric-bg text-cls-cleric cursor-not-allowed';
  if (disabled) return 'text-faint cursor-not-allowed';
  if (state.status === 'Remove') return 'text-muted hover:bg-debuff-bg';
  if (chosen && state.status === 'In Spellbook') return 'bg-buff-bg text-buff hover:opacity-80';
  if (chosen) return 'bg-cls-wizard-bg text-cls-wizard hover:opacity-80';
  return 'text-muted hover:bg-page-alt';
}

function labelClass(status: SpellStatus, disabled: boolean): string {
  switch (status) {
    case 'Always': return 'text-cls-cleric';
    case 'Remove': return 'text-debuff';
    case 'In list':
    case 'Removed': return 'text-faint';
    default: return disabled ? 'text-faint' : 'text-buff';
  }
}

/**
 * Picks spells (D171): from a class's spell list, searched as you type and filtered by level, or for the spellbook
 * modes from a list it is given. The last results stay up while a new search runs. The levels, schools and names it
 * offers come from the filter, and each row says what statusOf says of it, such as Known or Prepared; the server
 * checks every choice again. Used by the creation wizard's spells, the sheet's spells dialog in all its modes, and
 * a feat's spells.
 */
export default function SpellPicker({
  value, onChange, max, filter, statusOf, localSpells, placeholder = 'Search spells...',
  emptyText = 'No spells found.', debounceMs = 250,
}: SpellPickerProps) {
  const [text, setText] = useState('');
  // The name the search sends: the box's text once typing stops, or at once on Enter
  const [searchName, setSearchName] = useState('');
  const [level, setLevel] = useState<number | ''>('');

  useEffect(() => {
    const timer = setTimeout(() => setSearchName(text.trim()), debounceMs);
    return () => clearTimeout(timer);
  }, [text, debounceMs]);

  const { className, levels, schools, names } = filter;
  const levelList = level !== '' ? [level] : levels;
  const params: SpellSearchParams = {
    size: names ? NAMED_PAGE_SIZE : PAGE_SIZE,
    sort: 'name',
    level: levelList.join(','),
    ...(className ? { className } : {}),
    ...(schools && schools.length > 0 ? { school: schools.join(',') } : {}),
    ...(searchName ? { name: searchName } : {}),
  };
  const search = useSpellSearch(params, { enabled: !localSpells && levels.length > 0 });

  const named = names ? new Set(names.map(n => n.trim().toLowerCase())) : null;
  const offered = (spell: Spell) => levelList.includes(spell.level) && (!named || named.has(spell.name.toLowerCase()));
  const query = text.trim().toLowerCase();
  const results = localSpells
    ? localSpells.filter(spell => offered(spell) && (!query || spell.name.toLowerCase().includes(query)))
    : (search.data?.content ?? []).filter(offered);

  const full = max !== undefined && value.length >= max;

  function toggle(spell: Spell, chosen: boolean) {
    if (chosen) {
      onChange(value.filter(name => !sameName(name, spell.name)), spell);
    } else if (!full) {
      onChange([...value, spell.name], spell);
    }
  }

  return (
    <div>
      <div className="pb-3 border-b border-rule flex gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
          <input
            value={text}
            onChange={e => setText(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') setSearchName(text.trim()); }}
            placeholder={placeholder}
            aria-label={placeholder.replace(/\.+$/, '')}
            className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[15px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
          />
        </div>
        {levels.length > 1 && (
          <select
            value={level}
            onChange={e => setLevel(e.target.value === '' ? '' : Number(e.target.value))}
            aria-label="Spell level"
            className="bg-page border border-rule px-2 py-1.5 font-body text-[15px] font-semibold text-ink focus:outline-none focus:border-muted"
          >
            <option value="">All Levels</option>
            {levels.map(l => <option key={l} value={l}>{l === 0 ? 'Cantrips' : `Level ${l}`}</option>)}
          </select>
        )}
      </div>

      <div className="py-2 max-h-72 overflow-y-auto">
        {results.length === 0 ? (
          <p className="font-body text-[15px] text-faint py-4 text-center">
            {search.isFetching ? 'Searching...' : emptyText}
          </p>
        ) : (
          <div className="space-y-1">
            {results.map(spell => {
              const chosen = value.some(name => sameName(name, spell.name));
              const state = statusOf?.(spell, chosen) ?? {};
              const disabled = !!state.disabled || (!chosen && full);
              const status = state.status ?? (chosen ? 'Chosen' : undefined);
              return (
                <button
                  key={spell.id}
                  type="button"
                  onClick={() => toggle(spell, chosen)}
                  disabled={disabled}
                  aria-pressed={chosen}
                  className={`w-full flex items-center justify-between gap-2 py-2 px-3 font-body text-[15px] font-semibold transition-colors ${rowClass(state, chosen, disabled)}`}
                >
                  <span className="flex items-center gap-2 min-w-0">
                    {status === 'Always' && <Lock className="w-3 h-3 text-cls-cleric shrink-0" />}
                    <span>{spell.name}</span>
                    {/* The level filter says the level; the name stays the button's name */}
                    <span aria-hidden="true" className="text-[13px] text-faint truncate">
                      {spell.level === 0 ? 'Cantrip' : `Lv ${spell.level}`}{spell.school ? ` · ${spell.school}` : ''}
                    </span>
                  </span>
                  {status
                    ? <span className={`text-[13px] shrink-0 ${labelClass(status, disabled)}`}>{status}</span>
                    : <Plus className="w-4 h-4 text-faint shrink-0" />}
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
