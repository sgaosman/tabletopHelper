import type { ResourcePoolEntry } from '../../types/encounter';

function safeParsePools(json?: string): ResourcePoolEntry[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

interface ResourcePoolDisplayProps {
  pools?: string; // JSON string of ResourcePoolEntry[]
  /** Only show pools matching these sourceTypes (e.g. ['CLASS', 'FEAT']) */
  filterSourceType?: string[];
  /** Enable increment/decrement buttons for out-of-encounter management */
  editable?: boolean;
  onSpend?: (poolId: string) => void;
  onRecover?: (poolId: string) => void;
}

export default function ResourcePoolDisplay({
  pools,
  filterSourceType,
  editable = false,
  onSpend,
  onRecover,
}: ResourcePoolDisplayProps) {
  const entries = safeParsePools(pools);
  const filtered = filterSourceType
    ? entries.filter((e) => filterSourceType.includes(e.sourceType))
    : entries;

  if (filtered.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-2">
      {filtered.map((entry) => (
        <div
          key={entry.poolId}
          className={`inline-flex items-center gap-1.5 rounded px-2 py-1 text-sm
            ${entry.currentUses === 0
              ? 'bg-rule text-muted'
              : 'bg-page-alt text-ink'
            }`}
          title={`${entry.displayName} — ${entry.sourceName || entry.sourceType} — resets on ${entry.resetOn}${entry.resetCheck ? ` (recharge ${entry.resetCheck})` : ''}`}
        >
          {/* Icon placeholder — mapped from lucide-react icon name */}
          <span className="text-xs opacity-60">{iconChar(entry.icon)}</span>

          <span className="font-semibold">{entry.displayName}</span>

          <span className={`tabular-nums ${entry.currentUses === 0 ? 'text-red-700' : ''}`}>
            {entry.currentUses}/{entry.maxUses}
          </span>

          {editable && (
            <>
              <button
                type="button"
                className="ml-1 rounded-sm px-1 text-xs hover:bg-rule disabled:opacity-30"
                disabled={entry.currentUses <= 0}
                onClick={() => onSpend?.(entry.poolId)}
                title={`Spend one ${entry.displayName} use`}
              >
                −
              </button>
              <button
                type="button"
                className="rounded-sm px-1 text-xs hover:bg-rule disabled:opacity-30"
                disabled={entry.currentUses >= entry.maxUses}
                onClick={() => onRecover?.(entry.poolId)}
                title={`Recover one ${entry.displayName} use`}
              >
                +
              </button>
            </>
          )}
        </div>
      ))}
    </div>
  );
}

/** Quick icon map for common lucide-react icon names → single-char fallback. */
function iconChar(icon?: string): string {
  switch (icon) {
    case 'zap': return '⚡';
    case 'flame': return '🔥';
    case 'heart': return '❤️';
    case 'sun': return '☀️';
    case 'music': return '🎵';
    case 'shield-check': return '🛡️';
    case 'crown': return '👑';
    case 'wind': return '💨';
    case 'sparkles': return '✨';
    case 'paw-print': return '🐾';
    default: return '●';
  }
}
