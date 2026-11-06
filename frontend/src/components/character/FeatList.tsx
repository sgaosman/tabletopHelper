import { useMemo, useState } from 'react';
import { Lock, Search } from 'lucide-react';
import type { FeatOptionResponse } from '../../types/character';
import type { Feat } from '../../types/reference';
import { abilityIncreasePreview } from '../../utils/featData';

interface FeatListProps {
  feats: Feat[];
  /** What the server says of each feat for this character, by feat ID (D159) */
  options: FeatOptionResponse[];
  onPick: (feat: Feat) => void;
  /** For a DM's grant (D158): a feat whose prerequisites the character doesn't meet can be picked, with them shown */
  ignorePrerequisites: boolean;
  /** For a background's feat (D159): only the feats it offers, by name in any case, are listed */
  only?: string[] | null;
}

interface Row {
  feat: Feat;
  pickable: boolean;
  /** Why it can't be picked, or what a DM's grant would overlook */
  reason: string | null;
  prerequisite: string | null;
}

/**
 * The feats to pick from, searched by name, those the character can take first. Whether it meets each prerequisite,
 * and whether it has the feat already, is the server's answer (D159); a feat it has can be taken again only when the
 * feat says so.
 */
export default function FeatList({ feats, options, onPick, ignorePrerequisites, only }: FeatListProps) {
  const [search, setSearch] = useState('');

  const rows = useMemo((): Row[] => {
    const byId = new Map(options.map(option => [option.featId, option]));
    const offered = only ? new Set(only.map(name => name.trim().toLowerCase())) : null;
    return feats
      .filter(feat => !offered || offered.has(feat.name.trim().toLowerCase()))
      .map(feat => {
        const option = byId.get(feat.id);
        const eligible = option?.eligible ?? true;
        const alreadyTaken = !!option?.taken && !option.repeatable;
        const unmet = option?.unmet.join('; ') || null;
        return {
          feat,
          pickable: (eligible || ignorePrerequisites) && !alreadyTaken,
          reason: alreadyTaken ? 'Already taken' : unmet,
          prerequisite: option?.prerequisite ?? null,
        };
      })
      .sort((a, b) => a.pickable !== b.pickable ? (a.pickable ? -1 : 1) : a.feat.name.localeCompare(b.feat.name));
  }, [feats, options, ignorePrerequisites, only]);

  const shown = useMemo(() => {
    const query = search.trim().toLowerCase();
    return query ? rows.filter(row => row.feat.name.toLowerCase().includes(query)) : rows;
  }, [rows, search]);

  return (
    <>
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
        <input
          value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Search feats..."
          aria-label="Search feats"
          className="w-full bg-page-alt border border-rule pl-9 pr-4 py-2 font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
          autoFocus
        />
      </div>
      <div className="space-y-1 max-h-[50vh] overflow-y-auto">
        {shown.map(({ feat, pickable, reason, prerequisite }) => (
          <button
            key={feat.id}
            type="button"
            onClick={() => pickable && onPick(feat)}
            disabled={!pickable}
            className={`w-full text-left px-3 py-2 transition-colors ${
              pickable ? 'hover:bg-page-alt text-ink cursor-pointer' : 'text-faint cursor-not-allowed'
            }`}
          >
            <div className="flex items-center justify-between">
              <div>
                <span className="font-heading text-[16px] font-semibold">{feat.name}</span>
                <span className="font-body text-[13px] text-faint ml-2">{feat.source}</span>
              </div>
              {!pickable && <Lock className="w-3.5 h-3.5 text-faint" />}
            </div>
            {!pickable && reason && (
              <p className="font-body text-[13px] text-debuff/70 mt-0.5">{reason}</p>
            )}
            {pickable && reason && (
              <p className="font-body text-[13px] text-faint mt-0.5">Prerequisite not met: {reason}</p>
            )}
            {pickable && !reason && prerequisite && (
              <p className="font-body text-[13px] text-faint mt-0.5">Prerequisite: {prerequisite}</p>
            )}
            {pickable && feat.abilityScoreIncrease && (
              <p className="font-body text-[13px] text-buff/70 mt-0.5">{abilityIncreasePreview(feat)}</p>
            )}
          </button>
        ))}
      </div>
    </>
  );
}
