import { Minus, Plus } from 'lucide-react';
import IconButton from '../ui/IconButton';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';
import { exhaustionEffects, MAX_EXHAUSTION } from '../../utils/exhaustion';

/**
 * A creature's exhaustion level, 0 to 6, with a step down and up (PHB p.291, D183): the DM's control on a participant,
 * and the owner's on their sheet. Each step sends one change and waits for it; a failure shows as a toast. Level 6 is
 * death, so the step up to it says so.
 *
 * @param name the creature, for the buttons' names
 */
export default function ExhaustionControl({ level, name, onChange, disabled = false }: {
  level: number;
  name: string;
  onChange: (level: number) => Promise<unknown>;
  disabled?: boolean;
}) {
  const { pending, run } = usePending();
  const toast = useToast();
  const effects = exhaustionEffects(level);

  function step(next: number) {
    run(async () => { await onChange(next); }).catch(toast.errorFrom);
  }

  return (
    <div className="flex items-center gap-2 flex-wrap">
      <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Exhaustion</span>
      <div className="flex items-stretch border border-rule bg-card">
        <IconButton
          label={`Lower ${name}'s exhaustion`}
          onClick={() => step(level - 1)}
          disabled={disabled || pending || level <= 0}
          className="px-2 text-muted hover:text-ink hover:bg-page-alt disabled:opacity-30"
        >
          <Minus size={14} />
        </IconButton>
        <span aria-live="polite" aria-label={`${name}'s exhaustion level`}
          className="w-8 text-center font-heading text-[15px] font-bold text-ink py-0.5 border-x border-rule">
          {level}
        </span>
        <IconButton
          label={level + 1 >= MAX_EXHAUSTION ? `Raise ${name}'s exhaustion to 6 (death)` : `Raise ${name}'s exhaustion`}
          onClick={() => step(level + 1)}
          disabled={disabled || pending || level >= MAX_EXHAUSTION}
          className="px-2 text-muted hover:text-ink hover:bg-page-alt disabled:opacity-30"
        >
          <Plus size={14} />
        </IconButton>
      </div>
      {effects.length > 0 && (
        <span className="font-body text-[13px] font-semibold text-muted">{effects.join('; ')}</span>
      )}
    </div>
  );
}
