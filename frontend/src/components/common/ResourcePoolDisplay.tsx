import { useState } from 'react';
import type { EncounterParticipant, ResourcePoolEntry } from '../../types/encounter';
import { apiErrorMessage } from '../../api/errors';
import { chargeBlock, poolBudgetBlock, poolCostLabel, poolRecoveryText, poolRefusal } from '../../utils/turnEconomy';
import {
  isRaging, KI_OPTIONS, poolEffect, poolEffectText, RAGE_TEXT, type PoolEffect, type PoolUseOptions,
} from '../../utils/classFeatures';
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
  /**
   * Uses the pool through the encounter's use-pool command; only for the pools' owner and the DM. A class feature's
   * pool (M17) sends what its use needs: Lay on Hands' points and target, Ki's option, or ending a Rage.
   */
  onUse?: (poolId: string, options?: PoolUseOptions) => Promise<unknown>;
  /** The DM uses it: a use the turn can't pay for is only a warning, since the server never refuses the DM. */
  asDm?: boolean;
  /** The creatures a feature that touches another can be used on, such as Lay on Hands; the user alone without it. */
  targets?: EncounterParticipant[];
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
  targets,
}: ResourcePoolDisplayProps) {
  const entries = pools ?? [];
  const filtered = filterSourceType
    ? entries.filter((e) => filterSourceType.includes(e.sourceType))
    : entries;

  if (filtered.length === 0) return null;

  if (onUse && participant) {
    return <EncounterPools pools={filtered} participant={participant} onUse={onUse} asDm={asDm} targets={targets} />;
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

const USE_BUTTON = 'ml-1 px-1.5 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] bg-ink/10 hover:bg-ink/20 '
  + 'text-ink disabled:opacity-40 disabled:hover:bg-ink/10';
const SMALL_FIELD = 'px-1 py-0.5 bg-page-alt border border-rule font-body text-[13px] text-ink';

/**
 * The pools at the table (D174): each shows what a use costs from the participant's turn, as its spendActionType says,
 * and what it gives back where it recovers another pool, as Psionic Recovery does. A pool with no uses left, or nothing
 * to regain, can't be used by anyone; one the turn can't pay for is greyed for a player with the reason, and only
 * warned about for the DM. The server's refusal shows too. A class feature's pool (M17) also offers what its feature
 * does: Rage shows while it lasts what it gives and can be ended, Lay on Hands takes its points, use and target, and Ki
 * offers Patient Defense and Step of the Wind.
 */
function EncounterPools({ pools, participant, onUse, asDm, targets }: {
  pools: ResourcePoolEntry[];
  participant: EncounterParticipant;
  onUse: (poolId: string, options?: PoolUseOptions) => Promise<unknown>;
  asDm: boolean;
  targets?: EncounterParticipant[];
}) {
  const [using, setUsing] = useState<string | null>(null);
  const [error, setError] = useState('');
  // A recovered pool may be filtered out of what is shown, so recovery looks among all of the participant's pools
  const all = participant.resourcePoolsCurrent ?? pools;

  async function use(poolId: string, options?: PoolUseOptions) {
    setUsing(poolId);
    setError('');
    try {
      await (options ? onUse(poolId, options) : onUse(poolId));
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
          const effect = poolEffect(entry);
          const raging = effect === 'rage' && isRaging(participant);
          const refusal = raging ? null : poolRefusal(entry, all);
          // Ending a rage costs a bonus action whatever the pool says (PHB p.48)
          const budget = raging ? chargeBlock(participant, { cost: 'BONUS_ACTION' }) : poolBudgetBlock(participant, entry);
          const disabled = refusal !== null || (budget !== null && !asDm) || using !== null;
          const reason = refusal ?? (budget ? (asDm ? `Over the action economy: ${budget}` : budget) : null);
          const recovery = poolRecoveryText(entry, all);
          const effectText = raging ? null : poolEffectText(effect);
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
                {raging ? (
                  <button type="button" disabled={disabled} onClick={() => { void use(entry.poolId, { option: 'end' }); }}
                    aria-label={`End ${entry.displayName}`} aria-describedby={reason ? reasonId : undefined}
                    className={USE_BUTTON}>
                    {using === entry.poolId ? '...' : 'End'}
                  </button>
                ) : effect !== 'lay-on-hands' && (
                  <button type="button" disabled={disabled} onClick={() => { void use(entry.poolId); }}
                    aria-label={`Use ${entry.displayName}`} aria-describedby={reason ? reasonId : undefined}
                    className={USE_BUTTON}>
                    {using === entry.poolId ? '...' : 'Use'}
                  </button>
                )}
              </div>
              {raging ? (
                <p role="status" className="max-w-xs text-[11px] text-ink">
                  <span className="mr-1 px-1 font-heading font-semibold text-card bg-buff">Raging</span>
                  {RAGE_TEXT} Ending it early: bonus action.
                </p>
              ) : (
                <span className="text-[11px] text-muted">
                  {poolCostLabel(entry)}{recovery && `: ${recovery}`}{effectText && `: ${effectText}`}
                </span>
              )}
              <FeatureControls effect={effect} entry={entry} participant={participant} targets={targets}
                blocked={refusal !== null || using !== null} asDm={asDm}
                onUse={options => { void use(entry.poolId, options); }} />
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

/**
 * What a class feature's pool offers beyond a plain use (M17): Lay on Hands' points, use and creature (PHB p.84), and
 * Ki's Patient Defense and Step of the Wind, each a bonus action for 1 ki point (PHB p.78). The server checks them all.
 */
function FeatureControls({ effect, entry, participant, targets, blocked, asDm, onUse }: {
  effect: PoolEffect | null;
  entry: ResourcePoolEntry;
  participant: EncounterParticipant;
  targets?: EncounterParticipant[];
  blocked: boolean;
  asDm: boolean;
  onUse: (options: PoolUseOptions) => void;
}) {
  if (effect === 'lay-on-hands') {
    return <LayOnHandsControls entry={entry} participant={participant} targets={targets} blocked={blocked}
      asDm={asDm} onUse={onUse} />;
  }
  if (effect !== 'ki') return null;
  const budget = chargeBlock(participant, { cost: 'BONUS_ACTION' });
  const disabled = blocked || entry.currentUses < 1 || (budget !== null && !asDm);
  return (
    <div className="flex flex-wrap gap-1" role="group" aria-label={`${entry.displayName} options`}>
      {KI_OPTIONS.map(option => (
        <button key={option.option} type="button" disabled={disabled} title={option.description}
          onClick={() => onUse({ option: option.option })}
          className={USE_BUTTON}>
          {option.label}
        </button>
      ))}
    </div>
  );
}

function LayOnHandsControls({ entry, participant, targets, blocked, asDm, onUse }: {
  entry: ResourcePoolEntry;
  participant: EncounterParticipant;
  targets?: EncounterParticipant[];
  blocked: boolean;
  asDm: boolean;
  onUse: (options: PoolUseOptions) => void;
}) {
  const [points, setPoints] = useState(String(Math.min(5, Math.max(1, entry.currentUses))));
  const [mode, setMode] = useState<'heal' | 'cure'>('heal');
  const [targetId, setTargetId] = useState(participant.id);
  // The living creatures it can touch, the paladin first (PHB p.84)
  const choices = [participant, ...(targets ?? []).filter(t => t.id !== participant.id && t.lifeState !== 'DEAD')];
  const amount = Number.parseInt(points, 10);
  const budget = poolBudgetBlock(participant, entry);
  const invalid = !Number.isInteger(amount) || amount < 1 || amount > entry.currentUses
    || (mode === 'cure' && amount % 5 !== 0);
  const disabled = blocked || invalid || (budget !== null && !asDm);
  const base = `loh-${participant.id}`;
  return (
    <div className="flex flex-wrap items-center gap-1" role="group" aria-label={`${entry.displayName}: points, use and creature`}>
      <label htmlFor={`${base}-points`} className="sr-only">{entry.displayName} points</label>
      <input id={`${base}-points`} type="number" min={1} max={entry.currentUses} step={mode === 'cure' ? 5 : 1}
        value={points} onChange={e => setPoints(e.target.value)} className={`${SMALL_FIELD} w-14`} />
      <label htmlFor={`${base}-mode`} className="sr-only">{entry.displayName} use</label>
      <select id={`${base}-mode`} value={mode} onChange={e => setMode(e.target.value as 'heal' | 'cure')}
        className={SMALL_FIELD}>
        <option value="heal">Heal</option>
        <option value="cure">Cure (5 per disease or poison)</option>
      </select>
      <label htmlFor={`${base}-target`} className="sr-only">{entry.displayName} target</label>
      <select id={`${base}-target`} value={targetId} onChange={e => setTargetId(e.target.value)} className={SMALL_FIELD}>
        {choices.map(c => <option key={c.id} value={c.id}>{c.id === participant.id ? `${c.displayName} (self)` : c.displayName}</option>)}
      </select>
      <button type="button" disabled={disabled} aria-label={`Use ${entry.displayName}`}
        onClick={() => onUse({
          uses: amount,
          option: mode,
          targetIds: targetId === participant.id ? undefined : [targetId],
        })}
        className={USE_BUTTON}>
        Use
      </button>
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
