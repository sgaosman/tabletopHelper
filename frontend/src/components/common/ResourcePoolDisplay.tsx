import { useState } from 'react';
import type { EncounterParticipant, ResourcePoolEntry } from '../../types/encounter';
import { apiErrorMessage } from '../../api/errors';
import { poolBudgetBlock, poolCostLabel, poolRecoveryText, poolRefusal } from '../../utils/turnEconomy';
import IconButton from '../ui/IconButton';

interface ResourcePoolDisplayProps {
  pools?: ResourcePoolEntry[] | null;
  /** Only show pools matching these sourceTypes (e.g. ['CLASS', 'FEAT']) */
  filterSourceType?: string[];
  /** Enable increment/decrement buttons for out-of-encounter management */
  editable?: boolean;
  onSpend?: (poolId: string) => void;
  onRecover?: (poolId: string) => void;
  /**
   * Encounter mode (D174): the participant whose pools these are, whose turn pays for each use. Given with onUse, each
   * pool shows what a use costs from the turn and a Use button.
   */
  participant?: EncounterParticipant;
  /** Uses one of the pool, through the encounter's use-pool command; only for the pools' owner and the DM. */
  onUse?: (poolId: string) => Promise<unknown>;
  /** The DM uses it: a use the turn can't pay for is only a warning, since the server never refuses the DM. */
  asDm?: boolean;
}

export default function ResourcePoolDisplay({
  pools,
  filterSourceType,
  editable = false,
  onSpend,
  onRecover,
  participant,
  onUse,
  asDm = false,
}: ResourcePoolDisplayProps) {
  const entries = pools ?? [];
  const filtered = filterSourceType
    ? entries.filter((e) => filterSourceType.includes(e.sourceType))
    : entries;

  if (filtered.length === 0) return null;

  if (onUse && participant) {
    return <EncounterPools pools={filtered} participant={participant} onUse={onUse} asDm={asDm} />;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {filtered.map((entry) => {
        const unlimited = isUnlimited(entry);
        const die = poolDie(entry);
        const empty = !unlimited && entry.currentUses === 0;
        return (
          <div
            key={entry.poolId}
            className={`inline-flex items-center gap-1.5 rounded px-2 py-1 text-sm
              ${empty
                ? 'bg-rule-light text-muted'
                : 'bg-page-alt text-ink'
              }`}
            title={`${entry.displayName} — ${entry.sourceName || entry.sourceType} — resets on ${entry.resetOn}${entry.resetCheck ? ` (recharge ${entry.resetCheck})` : ''}`}
          >
            {/* Icon placeholder — mapped from lucide-react icon name */}
            <span className="text-xs opacity-60">{iconChar(entry.icon)}</span>

            <span className="font-semibold">{entry.displayName}</span>

            {die && <span className="text-xs text-muted">{die}</span>}

            {unlimited ? (
              <span>Unlimited</span>
            ) : (
              <span className={`tabular-nums ${empty ? 'text-debuff' : ''}`}>
                {entry.currentUses}/{entry.maxUses}
              </span>
            )}

            {editable && (
              <>
                <IconButton
                  label={`Spend one ${entry.displayName} use`}
                  className="ml-1 rounded-sm px-1 text-xs hover:bg-rule disabled:opacity-30"
                  disabled={empty}
                  onClick={() => onSpend?.(entry.poolId)}
                >
                  −
                </IconButton>
                {!unlimited && (
                  <IconButton
                    label={`Recover one ${entry.displayName} use`}
                    className="rounded-sm px-1 text-xs hover:bg-rule disabled:opacity-30"
                    disabled={entry.currentUses >= entry.maxUses}
                    onClick={() => onRecover?.(entry.poolId)}
                  >
                    +
                  </IconButton>
                )}
              </>
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * The pools at the table (D174): each shows what a use costs from the participant's turn, as its spendActionType says,
 * and what it gives back where it recovers another pool, as Psionic Recovery does. A pool with no uses left, or nothing
 * to regain, can't be used by anyone; one the turn can't pay for is greyed for a player with the reason, and only
 * warned about for the DM. The server's refusal shows too.
 */
function EncounterPools({ pools, participant, onUse, asDm }: {
  pools: ResourcePoolEntry[];
  participant: EncounterParticipant;
  onUse: (poolId: string) => Promise<unknown>;
  asDm: boolean;
}) {
  const [using, setUsing] = useState<string | null>(null);
  const [error, setError] = useState('');
  // A recovered pool may be filtered out of what is shown, so recovery looks among all of the participant's pools
  const all = participant.resourcePoolsCurrent ?? pools;

  async function use(poolId: string) {
    setUsing(poolId);
    setError('');
    try {
      await onUse(poolId);
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setUsing(null);
    }
  }

  return (
    <div className="mb-2">
      <ul className="flex flex-wrap gap-2" aria-label={`${participant.displayName}'s resource pools`}>
        {pools.map((entry) => {
          const unlimited = isUnlimited(entry);
          const die = poolDie(entry);
          const refusal = poolRefusal(entry, all);
          const budget = poolBudgetBlock(participant, entry);
          const disabled = refusal !== null || (budget !== null && !asDm) || using !== null;
          const reason = refusal ?? (budget ? (asDm ? `Over the action economy: ${budget}` : budget) : null);
          const recovery = poolRecoveryText(entry, all);
          const reasonId = `pool-reason-${participant.id}-${entry.poolId}`;
          return (
            <li key={entry.poolId}
              className={`flex flex-col gap-0.5 rounded px-2 py-1 text-sm ${refusal ? 'bg-rule-light text-muted' : 'bg-page-alt text-ink'}`}>
              <div className="inline-flex items-center gap-1.5">
                <span className="text-xs opacity-60">{iconChar(entry.icon)}</span>
                <span className="font-semibold">{entry.displayName}</span>
                {die && <span className="text-xs text-muted">{die}</span>}
                {unlimited ? <span>Unlimited</span> : (
                  <span className={`tabular-nums ${entry.currentUses === 0 ? 'text-debuff' : ''}`}>
                    {entry.currentUses}/{entry.maxUses}
                  </span>
                )}
                <button type="button" disabled={disabled} onClick={() => { void use(entry.poolId); }}
                  aria-label={`Use ${entry.displayName}`} aria-describedby={reason ? reasonId : undefined}
                  className="ml-1 px-1.5 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] bg-ink/10 hover:bg-ink/20 text-ink disabled:opacity-40 disabled:hover:bg-ink/10">
                  {using === entry.poolId ? '...' : 'Use'}
                </button>
              </div>
              <span className="text-[11px] text-muted">
                {poolCostLabel(entry)}{recovery && `: ${recovery}`}
              </span>
              {reason && (
                <span id={reasonId} className={`text-[11px] font-semibold ${refusal || !asDm ? 'text-debuff' : 'text-cls-fighter'}`}>
                  {reason}
                </span>
              )}
            </li>
          );
        })}
      </ul>
      {error && <p role="alert" className="font-body text-[13px] font-semibold text-debuff mt-1">{error}</p>}
    </div>
  );
}

/** The pool's metadata as the server writes it (D169): its die, whether it is unlimited, the pool it recovers. */
function metadataOf(entry: ResourcePoolEntry): Record<string, unknown> {
  const metadata: unknown = entry.metadata;
  return metadata && typeof metadata === 'object' ? (metadata as Record<string, unknown>) : {};
}

/** A pool with no limit, such as Rage at Barbarian 20: shown as Unlimited, and its spending never refused. */
function isUnlimited(entry: ResourcePoolEntry): boolean {
  return metadataOf(entry).unlimited === true;
}

/** The pool's die at the character's level, such as "d8", or null. */
function poolDie(entry: ResourcePoolEntry): string | null {
  const die = metadataOf(entry).die;
  return typeof die === 'string' ? die : null;
}

/** Quick icon map for common lucide-react icon names → single-char fallback. */
function iconChar(icon?: string | null): string {
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
