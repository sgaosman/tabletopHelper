import type { EncounterParticipant, SpellEffect } from '../../types/encounter';
import { summonedCreatures } from '../../utils/summons';
import SpellEffectCard from './SpellEffectCard';
import SummonGroupCard from './SummonGroupCard';

/**
 * The spell a participant is concentrating on, and a card for each of its Spell Effects, such as Spiritual Weapon or
 * Moonbeam, indented beneath it (M15). `onUse` adds the caster's button to direct an effect, greyed with
 * `blockedBy`'s reason where the turn can't pay for it; `onEnd` adds the button that ends one by hand. A summoning's
 * effect has its creatures beneath it, found among `participants` (D182), where its End button goes, with the DM's
 * `onReplaceSummon`, `onDismissSummon`, `onDismissAllSummons` and `onCommandSummons`. `onMove` moves Hunter's Mark or
 * Hex to another living creature (D184).
 */
export default function ActiveSpells({
  participant: p, roundNumber, onUse, blockedBy, onEnd, participants = [], onReplaceSummon, onDismissSummon,
  onDismissAllSummons, onCommandSummons, onMove,
}: {
  participant: EncounterParticipant;
  roundNumber: number;
  onUse?: (effect: SpellEffect) => void;
  blockedBy?: (effect: SpellEffect) => string | null;
  onEnd?: (effect: SpellEffect) => void;
  participants?: EncounterParticipant[];
  onReplaceSummon?: (effect: SpellEffect) => void;
  onDismissSummon?: (creature: EncounterParticipant) => void;
  onDismissAllSummons?: (creatures: EncounterParticipant[]) => void;
  onCommandSummons?: (creatures: EncounterParticipant[]) => void;
  onMove?: (effect: SpellEffect, targetId: string) => void;
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
            onDismissAll={onDismissAllSummons}
            onCommand={onCommandSummons}
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
          moveTargets={participants
            .filter(other => other.lifeState !== 'DEAD' && !(effect.affectedIds ?? []).includes(other.id))
            .map(other => ({ id: other.id, name: other.displayName }))}
          onMove={onMove ? targetId => onMove(effect, targetId) : undefined}
          affectedNames={(effect.affectedIds ?? [])
            .map(id => participants.find(other => other.id === id)?.displayName)
            .filter((name): name is string => !!name)}
        />
      ))}
    </>
  );
}
