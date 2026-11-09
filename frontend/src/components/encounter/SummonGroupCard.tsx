import { Heart, Hourglass, Megaphone, Replace, X } from 'lucide-react';
import type { EncounterParticipant, SpellEffect } from '../../types/encounter';
import { healthLabel } from '../../utils/healthStatus';
import Button from '../ui/Button';
import IconButton from '../ui/IconButton';

/**
 * The creatures a summoning brought, under its Spell Effect on the caster (D182): each with its HP, or a summoning
 * still waiting for the DM to choose them, with Summon Lesser Demons' d6 (XGE p.167). `onEnd` ends the spell, which
 * dismisses them all; the DM's `onReplace` chooses new creatures in place of these, or the waiting ones, `onDismiss`
 * sends one away while the spell goes on, and `onDismissAll` sends them all away. The DM's `onCommand` spends the
 * caster's bonus action commanding those that act only on command and aren't commanded yet, as Animate Dead's undead
 * (PHB p.212) and a tiny servant (XGE p.168): for a monster caster, or to help a player (D182).
 */
export default function SummonGroupCard({ effect, creatures, onEnd, onReplace, onDismiss, onDismissAll, onCommand }: {
  effect: SpellEffect;
  creatures: EncounterParticipant[];
  onEnd?: () => void;
  onReplace?: () => void;
  onDismiss?: (creature: EncounterParticipant) => void;
  onDismissAll?: (creatures: EncounterParticipant[]) => void;
  onCommand?: (creatures: EncounterParticipant[]) => void;
}) {
  const awaiting = effect.summon?.status === 'AWAITING_DM';
  const toCommand = creatures.filter(c => c.summon?.needsCommand && c.summon.commandedRound == null
    && c.lifeState !== 'DEAD');
  const commandFor = onCommand && toCommand.length > 0 ? onCommand : undefined;
  const dismissAllFor = onDismissAll && creatures.length > 1 ? onDismissAll : undefined;
  return (
    <div role="group" aria-label={`${effect.spell} creatures`}
      className="ml-6 mt-1 pl-2 py-1 border-l-2 border-cls-druid/40 bg-cls-druid/5">
      {awaiting ? (
        <p className="flex items-center gap-1.5 font-body text-[13px] font-semibold text-muted">
          <Hourglass className="w-3.5 h-3.5" aria-hidden="true" />
          Waiting for the DM to choose the creatures
          {effect.summon?.roll != null && ` (rolled d6: ${effect.summon.roll})`}
        </p>
      ) : creatures.length === 0 ? (
        <p className="font-body text-[13px] font-semibold text-faint">No creatures left</p>
      ) : (
        <ul className="space-y-0.5">
          {creatures.map(c => (
            <li key={c.id} className="flex items-center gap-2">
              <span className={`font-body text-[14px] font-semibold text-ink ${c.lifeState === 'DEAD' ? 'line-through' : ''}`}>
                {c.displayName}
              </span>
              <span className="inline-flex items-center gap-1 font-heading text-[12px] font-semibold text-muted">
                <Heart className="w-3 h-3" aria-hidden="true" />
                {c.hpCurrent != null ? `${c.hpCurrent}/${c.hpMax}` : healthLabel(c)}
              </span>
              {c.summon?.needsCommand && c.summon.commandedRound != null && (
                <span className="font-heading text-[12px] font-semibold text-cls-druid">Commanded</span>
              )}
              {onDismiss && (
                <IconButton label={`Dismiss ${c.displayName}`} onClick={() => onDismiss(c)}
                  className="inline-flex items-center justify-center size-11 sm:size-7 text-faint hover:text-debuff hover:bg-debuff/10">
                  <X className="w-3.5 h-3.5" />
                </IconButton>
              )}
            </li>
          ))}
        </ul>
      )}
      {(onEnd || onReplace || commandFor || dismissAllFor) && (
        <div className="flex flex-wrap gap-1.5 mt-1">
          {commandFor && (
            <Button variant="secondary" size="sm" onClick={() => commandFor(toCommand)} className="min-h-11 sm:min-h-0"
              title="The caster's bonus action">
              <Megaphone className="w-3.5 h-3.5" aria-hidden="true" />
              Command {toCommand.length === 1 ? toCommand[0].displayName : `${toCommand.length} creatures`}
            </Button>
          )}
          {onReplace && (
            <Button variant="secondary" size="sm" onClick={onReplace} className="min-h-11 sm:min-h-0">
              <Replace className="w-3.5 h-3.5" aria-hidden="true" /> {awaiting ? 'Choose creatures' : 'Replace creatures'}
            </Button>
          )}
          {dismissAllFor && (
            <Button variant="ghost" size="sm" onClick={() => dismissAllFor(creatures)} className="min-h-11 sm:min-h-0">
              Dismiss all
            </Button>
          )}
          {onEnd && (
            <Button variant="ghost" size="sm" onClick={onEnd} className="min-h-11 sm:min-h-0">
              End {effect.spell}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
