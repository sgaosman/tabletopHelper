import { useId } from 'react';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';
import type { EncounterParticipant } from '../../types/encounter';
import type { SpellConsent } from '../../types/combat';
import { SPELL_CONSENTS } from '../../utils/spellConsent';

/**
 * Which spells a creature lets through a trait that keeps spells off it "unless it wishes to be" affected, as a
 * rakshasa's Limited Magic Immunity (MM p.257, D198): helpful ones only, such as Heal and Bless but not an ally's
 * Fireball, every spell, or none. Shown only for a creature with such a trait, to whoever controls it; each change is
 * sent at once, and a failure shows as a toast. A cast can still choose otherwise for itself.
 */
export default function SpellConsentControl({ participant, onChange, disabled = false }: {
  participant: EncounterParticipant;
  onChange: (consent: SpellConsent) => Promise<unknown>;
  disabled?: boolean;
}) {
  const id = useId();
  const { pending, run } = usePending();
  const toast = useToast();
  if (!participant.spellConsentTrait) return null;

  function choose(consent: SpellConsent) {
    run(async () => { await onChange(consent); }).catch(toast.errorFrom);
  }

  return (
    <div className="flex items-center gap-2 flex-wrap">
      <label htmlFor={id} className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">
        {participant.spellConsentTrait}: lets through
      </label>
      <select
        id={id}
        value={participant.spellConsent ?? 'HELPFUL'}
        onChange={e => choose(e.target.value as SpellConsent)}
        disabled={disabled || pending}
        className="px-2 py-1 bg-card border border-rule font-body text-[14px] font-semibold text-ink focus:outline-none focus:border-muted disabled:opacity-50"
      >
        {SPELL_CONSENTS.map(option => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </select>
    </div>
  );
}
