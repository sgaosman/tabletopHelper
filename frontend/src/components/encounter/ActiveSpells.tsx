import type { EncounterParticipant, SpellEffect } from '../../types/encounter';
import { summonedCreatures } from '../../utils/summons';
import SpellEffectCard from './SpellEffectCard';
import SummonGroupCard from './SummonGroupCard';

/**
 * The spell a participant is concentrating on, and a card for each of its Spell Effects, such as Spiritual Weapon or
 * Moonbeam, indented beneath it (M15). `onUse` adds the caster's button to direct an effect, greyed with
 * `blockedBy`'s reason where the turn can't pay for it; `onEnd` adds the button that ends one by hand. A summoning's
 * effect has its creatures beneath it, found among `participants` (D182), where its End button goes, with the DM's
 * `onReplaceSummon` and `onDismissSummon`.
 */
export default function ActiveSpells({
  participant: p, roundNumber, onUse, blockedBy, onEnd, participants = [], onReplaceSummon, onDismissSummon,
}: {
  participant: EncounterParticipant;
  roundNumber: number;
  onUse?: (effect: SpellEffect) => void;
  blockedBy?: (effect: SpellEffect) => string | null;
  onEnd?: (effect: SpellEffect) => void;
  participants?: EncounterParticipant[];
  onReplaceSummon?: (effect: SpellEffect) => void;
  onDismissSummon?: (creature: EncounterParticipant) => void;
}) {
  return (
    <>
      {p.concentrationSpell && (
        <p className="text-cls-warlock font-body text-[15px] font-semibold mt-0.5">Concentrating: {p.concentrationSpell}</p>
      )}
      {p.activeEffects.map(effect => effect.summon ? (
        <div key={effect.id}>
          <SpellEffectCard effect={effect} roundNumber={roundNumber} />
          <SummonGroupCard
            effect={effect}
            creatures={summonedCreatures(effect, participants)}
            onEnd={onEnd ? () => onEnd(effect) : undefined}
            onReplace={onReplaceSummon ? () => onReplaceSummon(effect) : undefined}
            onDismiss={onDismissSummon}
          />
        </div>
      ) : (
        <SpellEffectCard
          key={effect.id}
          effect={effect}
          roundNumber={roundNumber}
          onUse={onUse ? () => onUse(effect) : undefined}
          blockedReason={blockedBy?.(effect) ?? null}
          onEnd={onEnd ? () => onEnd(effect) : undefined}
        />
      ))}
    </>
  );
}
