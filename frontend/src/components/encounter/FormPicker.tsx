import { useMemo, useState } from 'react';
import type { FormOptions, WildShapeBeast } from '../../types/combat';
import { apiErrorMessage } from '../../api/errors';
import { formLimitText, sizeName } from '../../utils/formSpells';
import Input from '../ui/Input';

/** The most creatures listed at once; typing narrows a longer list, such as True Polymorph's. */
const SHOWN = 60;

interface Props {
  /** Names the list, such as "Form for Gimli". */
  label: string;
  /** The server's options for the creature (D192), or undefined while they load. */
  options: FormOptions | undefined;
  loading: boolean;
  error: unknown;
  /** The chosen creature's monster ID, or null. */
  selected: string | null;
  onSelect: (creature: WildShapeBeast) => void;
}

/**
 * Chooses a creature's form for a form spell (D192): the spell's limits for it, a text filter, and the creatures the
 * server lists within them, each with its challenge rating, hit points, AC and size. The list can run to hundreds for
 * True Polymorph or Shapechange, so only the first matches show until the filter narrows it.
 */
export default function FormPicker({ label, options, loading, error, selected, onSelect }: Props) {
  const [filter, setFilter] = useState('');
  const matches = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return (options?.creatures ?? []).filter(c => !q || c.name.toLowerCase().includes(q));
  }, [options, filter]);

  if (error) return <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{apiErrorMessage(error)}</p>;
  if (loading || !options) {
    return <p className="font-body text-[13px] font-semibold text-faint">Loading creatures...</p>;
  }

  const shown = matches.slice(0, SHOWN);
  return (
    <div className="space-y-1.5">
      <p className="font-body text-[13px] font-semibold text-muted">{formLimitText(options)}</p>
      <Input label={`Filter ${label}`} hideLabel type="text" value={filter} onChange={e => setFilter(e.target.value)}
        placeholder="Filter creatures..." />
      {matches.length === 0 ? (
        <p className="font-body text-[13px] font-semibold text-faint">No creature within the limits matches</p>
      ) : (
        <ul aria-label={label} className="max-h-64 overflow-y-auto space-y-1">
          {shown.map(c => {
            const chosen = c.monsterId === selected;
            return (
              <li key={c.monsterId}>
                <button type="button" aria-pressed={chosen} onClick={() => onSelect(c)}
                  className={`w-full flex flex-wrap items-center justify-between gap-x-3 gap-y-0.5 px-3 py-2 text-left transition-colors ${
                    chosen ? 'bg-page-alt border-2 border-ink' : 'bg-page-alt border border-rule hover:bg-rule'}`}>
                  <span className="font-body text-[15px] font-semibold text-ink min-w-0 break-words">
                    {c.name}
                    {c.source && c.source !== 'MM' && <span className="ml-1 text-[12px] text-faint">[{c.source}]</span>}
                  </span>
                  <span className="font-body text-[12.5px] font-semibold text-faint tabular-nums">
                    CR {c.challengeRating} · {c.hitPoints} HP · AC {c.armourClass}{sizeName(c.size) ? ` · ${sizeName(c.size)}` : ''}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {matches.length > shown.length && (
        <p className="font-body text-[12.5px] font-semibold text-faint">
          {matches.length - shown.length} more: type to narrow the list
        </p>
      )}
    </div>
  );
}
