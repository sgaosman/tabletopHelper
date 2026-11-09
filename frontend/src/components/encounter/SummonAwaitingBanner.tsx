import { Hourglass } from 'lucide-react';
import type { EncounterParticipant, SpellEffect } from '../../types/encounter';
import Button from '../ui/Button';

/**
 * The DM's reminder of each summoning waiting for them to choose its creatures (D182): Summon Lesser Demons always,
 * with the d6 its casting rolled (XGE p.167), Conjure Animals while the campaign leaves its creatures to the DM (PHB
 * p.225), and any cast that named none. `onChoose` opens the picker. Nothing shows while none waits.
 */
export default function SummonAwaitingBanner({ participants, onChoose }: {
  participants: EncounterParticipant[];
  onChoose: (caster: EncounterParticipant, effect: SpellEffect) => void;
}) {
  const waiting = participants.flatMap(caster => caster.activeEffects
    .filter(effect => effect.summon?.status === 'AWAITING_DM')
    .map(effect => ({ caster, effect })));
  if (waiting.length === 0) return null;
  return (
    <div role="status" aria-label="Summonings waiting for you" className="mb-4 space-y-2">
      {waiting.map(({ caster, effect }) => (
        <div key={effect.id}
          className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 bg-cls-druid/10 border border-cls-druid/30">
          <p className="flex items-center gap-2 font-body text-[15px] font-semibold text-ink">
            <Hourglass className="w-4 h-4 text-cls-druid" aria-hidden="true" />
            <span>
              {caster.displayName}&apos;s {effect.spell} waits for you to choose its creatures
              {effect.summon?.roll != null && (
                <> — rolled d6: {effect.summon.roll}{effect.summon.optionId ? `, ${countOf(effect.summon.optionId)}` : ''}</>
              )}
            </span>
          </p>
          <Button size="sm" onClick={() => onChoose(caster, effect)} className="min-h-11">
            Choose creatures
          </Button>
        </div>
      ))}
    </div>
  );
}

// An option ID such as "8xCR1/4" in words: eight creatures of challenge rating 1/4 or lower
function countOf(optionId: string): string {
  const match = /^(\d+)xCR(.+)$/.exec(optionId);
  return match ? `${match[1]} of CR ${match[2]} or lower` : `${optionId} creatures`;
}
