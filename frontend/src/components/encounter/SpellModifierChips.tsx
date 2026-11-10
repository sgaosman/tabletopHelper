import type { EncounterParticipant } from '../../types/encounter';

const CHIP_BASE = 'font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 border';
const HELPS = `${CHIP_BASE} text-buff bg-buff-bg border-buff`;
const HINDERS = `${CHIP_BASE} text-debuff bg-debuff-bg border-debuff`;

/**
 * The spells whose Ongoing Modifiers are on a participant (D184), one chip each, such as "Bless" or "Faerie Fire", in
 * the buff colours or, for one that works against it, the debuff colours. Its title says what it does, and who cast it
 * when that creature is among `participants`, so a hidden caster stays unnamed (D157).
 */
export default function SpellModifierChips({ participant, participants = [] }: {
  participant: EncounterParticipant;
  participants?: EncounterParticipant[];
}) {
  const chips = participant.spellModifiers ?? [];
  if (chips.length === 0) return null;
  return (
    <ul className="flex gap-1 mt-1 flex-wrap" aria-label={`Spells on ${participant.displayName}`}>
      {chips.map(chip => {
        const caster = participants.find(p => p.id === chip.casterId);
        const from = caster && caster.id !== participant.id ? ` (from ${caster.displayName})` : '';
        return (
          <li key={chip.effectId} className={chip.hinders ? HINDERS : HELPS}
            title={`${chip.spell}${from}: ${chip.modifiers.join('; ')}`}>
            {chip.label}
          </li>
        );
      })}
    </ul>
  );
}
