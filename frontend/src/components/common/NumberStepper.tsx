import { useEffect, useState } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';

interface NumberStepperProps {
  value: number;
  min: number;
  max: number;
  onChange: (value: number) => void;
  /** Accessible name for the number box, e.g. "Starting level" */
  label: string;
}

/**
 * A number you can type or step with up/down buttons (or the arrow keys), kept within min–max.
 * Sits beside a slider for anyone who finds dragging awkward.
 */
export default function NumberStepper({ value, min, max, onChange, label }: NumberStepperProps) {
  const [draft, setDraft] = useState(String(value));

  // Follow changes made elsewhere, such as the slider next to it
  useEffect(() => {
    setDraft(String(value));
  }, [value]);

  function commit(next: number) {
    const clamped = Math.min(max, Math.max(min, Number.isNaN(next) ? value : next));
    setDraft(String(clamped));
    if (clamped !== value) onChange(clamped);
  }

  function handleTyping(raw: string) {
    const digits = raw.replace(/\D/g, '').slice(0, String(max).length);
    setDraft(digits);
    const typed = Number(digits);
    // Apply as soon as the typed number is valid; anything else is settled on blur or Enter
    if (digits !== '' && typed >= min && typed <= max && typed !== value) onChange(typed);
  }

  const settleDraft = () => commit(draft === '' ? value : Number(draft));

  return (
    <div className="flex items-stretch h-11 border border-rule bg-card focus-within:border-muted">
      <input
        type="text"
        inputMode="numeric"
        aria-label={label}
        value={draft}
        onChange={e => handleTyping(e.target.value)}
        onBlur={settleDraft}
        onKeyDown={e => {
          if (e.key === 'ArrowUp') {
            e.preventDefault();
            commit(value + 1);
          } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            commit(value - 1);
          } else if (e.key === 'Enter') {
            settleDraft();
          }
        }}
        className="w-12 text-center bg-transparent font-heading text-[17px] font-bold text-ink focus:outline-none"
      />
      <div className="flex flex-col border-l border-rule">
        <button
          type="button"
          aria-label={`Increase ${label.toLowerCase()}`}
          onClick={() => commit(value + 1)}
          disabled={value >= max}
          className="flex-1 px-2 text-muted hover:text-ink hover:bg-page-alt disabled:opacity-30 disabled:hover:bg-transparent"
        >
          <ChevronUp size={14} />
        </button>
        <button
          type="button"
          aria-label={`Decrease ${label.toLowerCase()}`}
          onClick={() => commit(value - 1)}
          disabled={value <= min}
          className="flex-1 px-2 border-t border-rule text-muted hover:text-ink hover:bg-page-alt disabled:opacity-30 disabled:hover:bg-transparent"
        >
          <ChevronDown size={14} />
        </button>
      </div>
    </div>
  );
}
