import { BellRing } from 'lucide-react';
import type { EncounterParticipant, SpellEffect, SpellTrigger } from '../../types/encounter';
import { TRIGGER_LABELS } from '../../utils/spellEffects';

/** A Spell Effect whose area may act on the creature whose turn it is, and the moments it would. */
interface Reminder {
  caster: EncounterParticipant;
  effect: SpellEffect;
}

/**
 * The DM's reminder, on each creature's turn, of the Spell Effects whose areas act on a creature by themselves:
 * Moonbeam on one that enters the beam or starts its turn there, Flaming Sphere on one that ends its turn beside it
 * (M15). There is no map, so the page can't tell who is in an area: it lists every such effect with a button for each
 * of its moments, and `onApply` opens it for the DM with the current creature chosen. It is worked out from the
 * encounter's state alone and stores nothing, and it shows nothing when no effect has a trigger.
 */
export default function TriggerReminder({ participants, current, onApply }: {
  participants: EncounterParticipant[];
  /** The creature whose turn it is; nothing shows on the Lair's turn or before the encounter starts. */
  current: EncounterParticipant | undefined;
  onApply: (caster: EncounterParticipant, effect: SpellEffect, trigger: SpellTrigger, targetId: string) => void;
}) {
  if (!current) return null;
  const reminders: Reminder[] = participants.flatMap(caster => caster.activeEffects
    .filter(effect => effect.triggers.length > 0)
    .map(effect => ({ caster, effect })));
  if (reminders.length === 0) return null;

  return (
    <section aria-label="Spell effect reminders" className="bg-cls-warlock/5 border border-cls-warlock/30 px-3 py-2 mb-4 space-y-1">
      <p className="flex items-center gap-1.5 font-heading text-[13px] font-semibold tracking-[0.01em] text-cls-warlock">
        <BellRing className="w-3.5 h-3.5" aria-hidden="true" />
        Is {current.displayName} in a spell&apos;s area?
      </p>
      {reminders.map(({ caster, effect }) => (
        <div key={effect.id} className="flex flex-wrap items-center gap-2">
          <span className="font-body text-[15px] font-semibold text-ink">
            {effect.spell} <span className="text-muted">({caster.displayName})</span>
          </span>
          {effect.triggers.map(trigger => (
            <button key={trigger} type="button"
              onClick={() => onApply(caster, effect, trigger, current.id)}
              aria-label={`${effect.spell}: ${current.displayName} ${TRIGGER_LABELS[trigger].toLowerCase()}`}
              className="px-2 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] border border-cls-warlock/30 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock">
              {TRIGGER_LABELS[trigger]}
            </button>
          ))}
        </div>
      ))}
    </section>
  );
}
