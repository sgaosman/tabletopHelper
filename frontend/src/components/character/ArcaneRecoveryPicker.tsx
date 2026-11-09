import IconButton from '../ui/IconButton';
import {
  arcaneRecoveryBudget, arcaneRecoveryLevels, recoverableSlots, type ArcaneRecoveryChoice,
} from '../../utils/classFeatures';

/**
 * A wizard's Arcane Recovery in the short rest dialog (PHB p.115, M17): once a day, as the rest ends, expended spell
 * slots of 5th level or lower whose levels add up to half the wizard level, rounded up. The picker offers only expended
 * slots and stops at the budget; `usedToday` greys it out until the next long rest. The server checks it all again.
 */
export default function ArcaneRecoveryPicker({ wizardLevel, slots, usedToday, choice, onChange }: {
  wizardLevel: number;
  slots: Record<string, { max: number; used: number }> | null | undefined;
  usedToday: boolean;
  choice: ArcaneRecoveryChoice;
  onChange: (choice: ArcaneRecoveryChoice) => void;
}) {
  const budget = arcaneRecoveryBudget(wizardLevel);
  const expended = recoverableSlots(slots);
  const spent = arcaneRecoveryLevels(choice).reduce((sum, level) => sum + level, 0);
  const levels = Object.keys(expended).map(Number).sort((a, b) => a - b);

  return (
    <section aria-label="Arcane Recovery" className="bg-page-alt border border-rule px-3 py-2 space-y-2">
      <p className="font-body text-[15px] font-semibold text-ink">
        Arcane Recovery <span className="font-normal text-[13px] text-faint">({spent}/{budget} slot levels)</span>
      </p>
      {usedToday ? (
        <p className="font-body text-[13px] text-faint">Used since your last long rest.</p>
      ) : levels.length === 0 ? (
        <p className="font-body text-[13px] text-faint">No expended slots of 5th level or lower to recover.</p>
      ) : (
        <div className="flex flex-wrap gap-3">
          {levels.map(level => {
            const count = choice[level] ?? 0;
            return (
              <div key={level} className="flex items-center gap-1.5">
                <span className="font-body text-[13px] text-ink">Level {level}</span>
                <IconButton label={`Recover one fewer level ${level} slot`}
                  onClick={() => onChange({ ...choice, [level]: Math.max(0, count - 1) })}
                  disabled={count === 0}
                  className="w-7 h-7 bg-page border border-rule text-muted font-body text-[15px] disabled:opacity-30 hover:bg-rule-light">
                  −
                </IconButton>
                <span className="font-heading text-[15px] font-bold text-ink w-4 text-center">{count}</span>
                <IconButton label={`Recover one more level ${level} slot`}
                  onClick={() => onChange({ ...choice, [level]: count + 1 })}
                  disabled={count >= expended[level] || spent + level > budget}
                  className="w-7 h-7 bg-page border border-rule text-muted font-body text-[15px] disabled:opacity-30 hover:bg-rule-light">
                  +
                </IconButton>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
