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
  accentColor = 'indigo',
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

  const focusRing = `focus:ring-${accentColor}-500`;
  const borderFocus = `focus-within:border-${accentColor}-500`;

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className={`w-full flex items-center justify-between gap-2 px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 text-left hover:border-gray-600 transition-colors ${selected.length > 0 ? 'text-white' : 'text-gray-400'} ${borderFocus}`}
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
              onClick={(e) => { e.stopPropagation(); onChange([]); setSearch(''); }}
              className="p-0.5 hover:bg-gray-700 rounded"
            >
              <X size={14} />
            </span>
          )}
          <ChevronDown size={16} className={`transition-transform ${open ? 'rotate-180' : ''}`} />
        </div>
      </button>

      {open && (
        <div className="absolute z-50 mt-1 w-full bg-gray-800 border border-gray-700 rounded-lg shadow-xl overflow-hidden">
          {options.length > 6 && (
            <div className="p-2 border-b border-gray-700">
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search..."
                autoFocus
                className={`w-full px-3 py-1.5 bg-gray-900 rounded border border-gray-700 text-white text-sm placeholder-gray-500 focus:outline-none ${focusRing}`}
              />
            </div>
          )}
          <div className="max-h-60 overflow-y-auto">
            {filtered.length === 0 ? (
              <p className="px-4 py-3 text-gray-500 text-sm">No matches</p>
            ) : (
              filtered.map(option => {
                const isSelected = selected.includes(option);
                return (
                  <label
                    key={option}
                    className="flex items-center gap-3 px-4 py-2 hover:bg-gray-700/50 cursor-pointer text-sm"
                  >
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => toggle(option)}
                      className="rounded border-gray-600 bg-gray-900 text-indigo-500 focus:ring-indigo-500 focus:ring-offset-0 w-4 h-4 cursor-pointer"
                    />
                    <span className={isSelected ? 'text-white' : 'text-gray-300'}>
                      {label(option)}
                    </span>
                  </label>
                );
              })
            )}
          </div>
          {selected.length > 0 && (
            <div className="border-t border-gray-700 px-4 py-2">
              <button
                onClick={() => { onChange([]); setSearch(''); }}
                className="text-xs text-gray-400 hover:text-white transition-colors"
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
