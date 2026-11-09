import { useState } from 'react';
import { apiErrorMessage } from '../../api/errors';
import type { FontOfMagicRequest } from '../../types/character';
import type { ResourcePoolEntry, SpellSlot } from '../../types/encounter';
import { creationCost, expendableLevels, MAX_CREATED_SLOT_LEVEL } from '../../utils/metamagic';

type Direction = FontOfMagicRequest['direction'];

interface Props {
  /** The sorcerer's Sorcery Points pool. */
  points: ResourcePoolEntry;
  /** Their spell slots, pact slots included. */
  slots: Record<string, SpellSlot> | null | undefined;
  /** Makes one exchange; the server checks it and answers with the changed character or encounter. */
  onExchange: (direction: Direction, slotLevel: number) => Promise<unknown>;
  /** Why the turn can't pay the bonus action at the table, if it can't; null on the sheet. */
  blocked?: string | null;
  /** The DM goes over the action economy with a warning rather than being refused (D174). */
  asDm?: boolean;
}

const BUTTON = 'px-1.5 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] bg-ink/10 hover:bg-ink/20 '
  + 'text-ink disabled:opacity-40 disabled:hover:bg-ink/10';
const FIELD = 'px-1 py-0.5 bg-page-alt border border-rule font-body text-[13px] text-ink';

/**
 * Font of Magic's Flexible Casting (PHB p.101, M18): expend a spell slot for sorcery points equal to its level, or
 * spend points to create a slot of 1st to 5th level by the Creating Spell Slots table (2, 3, 5, 6, 7). At the table
 * each is a bonus action; created slots vanish on a long rest. The server is the authority, and its refusal shows.
 */
export default function FlexibleCastingControls({ points, slots, onExchange, blocked = null, asDm = false }: Props) {
  const expendable = expendableLevels(slots);
  const [expendLevel, setExpendLevel] = useState<number | null>(null);
  const [createLevel, setCreateLevel] = useState(1);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const level = expendLevel !== null && expendable.includes(expendLevel) ? expendLevel : expendable[0] ?? null;
  const full = points.currentUses >= points.maxUses;
  const cost = creationCost(createLevel) ?? 0;
  const turnBlocked = blocked !== null && !asDm;

  async function exchange(direction: Direction, slotLevel: number) {
    setBusy(true);
    setError('');
    try {
      await onExchange(direction, slotLevel);
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div role="group" aria-label="Flexible Casting" className="flex flex-col gap-1 text-[12px]">
      <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">
        Flexible Casting{blocked !== null || asDm ? ' (bonus action)' : ''}
      </span>
      <div className="flex flex-wrap items-center gap-1">
        <label className="sr-only" htmlFor={`expend-${points.poolId}`}>Slot level to convert</label>
        <select id={`expend-${points.poolId}`} className={FIELD} value={level ?? ''} disabled={expendable.length === 0}
          onChange={e => setExpendLevel(parseInt(e.target.value))}>
          {expendable.length === 0 && <option value="">No slots</option>}
          {expendable.map(l => <option key={l} value={l}>Level {l} slot</option>)}
        </select>
        <button type="button" className={BUTTON} disabled={busy || level === null || full || turnBlocked}
          title={full ? 'Sorcery points are at their maximum' : undefined}
          onClick={() => { if (level !== null) void exchange('SLOT_TO_POINTS', level); }}>
          {level !== null && !full ? `Convert to ${Math.min(level, points.maxUses - points.currentUses)} points` : 'Convert'}
        </button>
      </div>
      <div className="flex flex-wrap items-center gap-1">
        <label className="sr-only" htmlFor={`create-${points.poolId}`}>Slot level to create</label>
        <select id={`create-${points.poolId}`} className={FIELD} value={createLevel}
          onChange={e => setCreateLevel(parseInt(e.target.value))}>
          {Array.from({ length: MAX_CREATED_SLOT_LEVEL }, (_, i) => i + 1).map(l => (
            <option key={l} value={l}>Level {l} slot ({creationCost(l)} points)</option>
          ))}
        </select>
        <button type="button" className={BUTTON} disabled={busy || points.currentUses < cost || turnBlocked}
          title={points.currentUses < cost ? `Needs ${cost} sorcery points` : undefined}
          onClick={() => { void exchange('POINTS_TO_SLOT', createLevel); }}>
          Create slot
        </button>
      </div>
      {blocked && (
        <span className={`text-[11px] font-semibold ${asDm ? 'text-cls-fighter' : 'text-debuff'}`}>
          {asDm ? `Over the action economy: ${blocked}` : blocked}
        </span>
      )}
      {error && <span role="alert" className="text-[11px] font-semibold text-debuff">{error}</span>}
    </div>
  );
}
