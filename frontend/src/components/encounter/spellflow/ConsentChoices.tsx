import type { EncounterParticipant } from '../../../types/encounter';
import { spellConsentLabel } from '../../../utils/spellConsent';

const OPTIONS = ['default', 'yes', 'no'] as const;

/**
 * For each target whose trait lets it choose to be affected, as a rakshasa's Limited Magic Immunity (MM p.257, D198),
 * and whose choice the user makes: whether this cast affects it, or its standing choice decides. Nothing shows when no
 * target has one.
 *
 * @param chosen the choices made, by participant ID: true to be affected, false not to; a target left out takes its
 *               standing choice
 */
export default function ConsentChoices({ targets, spellName, chosen, onChange }: {
  targets: EncounterParticipant[];
  spellName: string;
  chosen: Record<string, boolean>;
  onChange: (chosen: Record<string, boolean>) => void;
}) {
  if (targets.length === 0) return null;

  function choose(id: string, option: typeof OPTIONS[number]) {
    const next = { ...chosen };
    if (option === 'default') delete next[id];
    else next[id] = option === 'yes';
    onChange(next);
  }

  return (
    <div className="bg-page-alt border border-rule p-3 space-y-2">
      {targets.map(target => {
        const value = target.id in chosen ? (chosen[target.id] ? 'yes' : 'no') : 'default';
        const labels = {
          default: `Its setting (${spellConsentLabel(target.spellConsent)})`,
          yes: 'Yes, it wishes to be affected',
          no: 'No',
        };
        return (
          <fieldset key={target.id}>
            <legend className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">
              {target.displayName}&apos;s {target.spellConsentTrait}: does {spellName} affect it?
            </legend>
            <div className="flex flex-wrap gap-3 mt-1">
              {OPTIONS.map(option => (
                <label key={option} className="flex items-center gap-1 font-body text-[14px] text-ink">
                  <input type="radio" name={`consent-${target.id}`} checked={value === option}
                    onChange={() => choose(target.id, option)} />
                  {labels[option]}
                </label>
              ))}
            </div>
          </fieldset>
        );
      })}
    </div>
  );
}
