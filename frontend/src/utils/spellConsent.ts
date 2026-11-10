import type { EncounterParticipant } from '../types/encounter';
import type { SpellConsent } from '../types/combat';

/** The standing choices, in the order the control offers them, with how each reads (D198). */
export const SPELL_CONSENTS: { value: SpellConsent; label: string }[] = [
  { value: 'HELPFUL', label: 'Helpful spells only' },
  { value: 'ALWAYS', label: 'Every spell' },
  { value: 'NEVER', label: 'No spells' },
];

/** How a standing choice reads, as "Helpful spells only". */
export function spellConsentLabel(consent: string | null | undefined): string {
  return SPELL_CONSENTS.find(option => option.value === consent)?.label ?? 'Helpful spells only';
}

/**
 * The cast's targets whose trait lets them choose to be affected and whose choice the user may make for this cast
 * (D198): every one for the DM, and for a player only those the player controls, as a dominated rakshasa.
 */
export function consentTargets(targets: EncounterParticipant[], userId: string | undefined, asDm: boolean) {
  return targets.filter(target => target.spellConsentTrait
    && (asDm || (userId !== undefined && target.controlledByUserId === userId)));
}
