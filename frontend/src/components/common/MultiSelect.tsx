import { useState, useRef, useEffect } from 'react';
import { ChevronDown, X } from 'lucide-react';

interface MultiSelectProps {
  options: string[];
  selected: string[];
  onChange: (selected: string[]) => void;
  placeholder: string;
  renderLabel?: (value: string) => string;
  accentColor?: string;
}

export default function MultiSelect({
  options,
  selected,
  onChange,
  placeholder,
  renderLabel,
}: MultiSelectProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
        setSearch('');
      }
    }
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, []);

  const label = (v: string) => renderLabel ? renderLabel(v) : v;

  const filtered = search
    ? options.filter(o => label(o).toLowerCase().includes(search.toLowerCase()))
    : options;

  function toggle(value: string) {
    if (selected.includes(value)) {
      onChange(selected.filter(s => s !== value));
    } else {
      onChange([...selected, value]);
    }
  }

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-haspopup="listbox"
        className={`w-full flex items-center justify-between gap-2 px-3 py-2 bg-card border border-rule text-left hover:border-muted transition-colors font-body text-[14px] font-medium ${selected.length > 0 ? 'text-ink' : 'text-faint'}`}
      >
        <span className="truncate">
          {selected.length === 0
            ? placeholder
            : selected.length === 1
              ? label(selected[0])
              : `${selected.length} selected`}
        </span>
        <div className="flex items-center gap-1 flex-shrink-0">
          {selected.length > 0 && (
            <span
              role="button"
              tabIndex={0}
              aria-label="Clear selection"
              onClick={(e) => { e.stopPropagation(); onChange([]); setSearch(''); }}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); onChange([]); setSearch(''); } }}
              className="p-0.5 hover:bg-page-alt text-faint hover:text-ink"
            >
              <X size={14} />
            </span>
          )}
          <ChevronDown size={16} className={`text-faint transition-transform ${open ? 'rotate-180' : ''}`} />
        </div>
      </button>

      {open && (
        <div role="listbox" aria-multiselectable="true" className="absolute z-50 mt-1 w-full bg-card border border-rule overflow-hidden">
          {options.length > 6 && (
            <div className="p-2 border-b border-rule">
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search..."
                aria-label="Filter options"
                autoFocus
                className="w-full px-3 py-1.5 bg-page border border-rule font-body text-[13px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
              />
            </div>
          )}
          <div className="max-h-60 overflow-y-auto">
            {filtered.length === 0 ? (
              <p className="px-4 py-3 font-body text-[13px] text-faint">No matches</p>
            ) : (
              filtered.map(option => {
                const isSelected = selected.includes(option);
                return (
                  <label
                    key={option}
                    className="flex items-center gap-3 px-4 py-1.5 hover:bg-page-alt cursor-pointer font-body text-[13px] font-medium"
                  >
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => toggle(option)}
                      className="border-rule bg-page w-3.5 h-3.5 cursor-pointer accent-ink"
                    />
                    <span className={isSelected ? 'text-ink' : 'text-muted'}>
                      {label(option)}
                    </span>
                  </label>
                );
              })
            )}
          </div>
          {selected.length > 0 && (
            <div className="border-t border-rule px-4 py-1.5">
              <button
                onClick={() => { onChange([]); setSearch(''); }}
                className="font-heading text-[9px] font-medium tracking-[0.04em] text-faint hover:text-ink transition-colors"
              >
                Clear all ({selected.length})
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
