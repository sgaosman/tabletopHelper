import type { ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import type { EncounterParticipant } from '../../types/encounter';
import { getParticipantColour } from '../../utils/classColours';
import { brokeFree } from '../../utils/summons';
import { BANISHED, hasCondition } from '../../utils/spellEffects';
import WildShapeStatus from './WildShapeStatus';
import SpellModifierChips from './SpellModifierChips';

/**
 * A participant's row in the initiative list: the left edge in its class colour (or the monster colour), the turn
 * marker, its initiative and name, with slots for what each page adds. `highlighted` rings the row, as the DM's
 * chosen target or the player's own character. A participant that is down is dimmed, and the dead are struck through.
 * A druid in Wild Shape shows the beast it has become, with the beast's and its own HP and AC (PHB p.66), and a
 * creature in another form, by Wild Shape or a form spell such as Polymorph (D192), shows it after its name.
 *
 * @param leading after the initiative, such as the DM's tie arrows
 * @param badges after the name, on the same line
 * @param children under the name: conditions, spells, death saves and pools
 * @param trailing at the right: HP and AC, labels and buttons
 * @param onEndWildShape ends the druid's Wild Shape, for whoever may: the DM, or the druid's player
 * @param summonerName the name of the creature that summoned this one, for its "Summoned by" badge (D182); a summon
 *   that acts right after its caster is indented under it
 * @param participants everyone in the encounter, so a spell chip can name its caster (D184)
 *
 * On a phone the trailing part moves to a line of its own when it does not fit beside the name.
 */
export default function ParticipantRow({
  participant: p, current, highlighted = false, leading, badges, children, trailing, onEndWildShape, summonerName,
  participants,
}: {
  participant: EncounterParticipant;
  current: boolean;
  highlighted?: boolean;
  leading?: ReactNode;
  badges?: ReactNode;
  children?: ReactNode;
  trailing?: ReactNode;
  onEndWildShape?: () => void;
  summonerName?: string | null;
  participants?: EncounterParticipant[];
}) {
  const isMonster = p.participantType === 'MONSTER';
  // A summon that acts right after its caster sits under it in the order (TCE p.109, D182)
  const follower = p.summon?.initiativeMode === 'FOLLOWS_CASTER';
  // Banishment takes it out of the fight until the spell ends (PHB p.217, D185)
  const banished = hasCondition(p, BANISHED);
  return (
    <div
      aria-current={current ? 'step' : undefined}
      className={`bg-card border border-l-[3px] px-3 sm:px-4 py-3 transition-all ${follower ? 'ml-6 sm:ml-10' : ''} ${
        current ? 'border-t-cls-fighter border-r-cls-fighter border-b-cls-fighter ring-1 ring-cls-fighter/30' :
        highlighted ? 'border-t-ink border-r-ink border-b-ink ring-1 ring-ink/30' :
        'border-t-rule border-r-rule border-b-rule'
      } ${p.lifeState !== 'ALIVE' || banished ? 'opacity-60' : ''}`}
      style={{ borderLeftColor: getParticipantColour(isMonster, p.characterClass) }}
    >
      <div className="flex flex-wrap sm:flex-nowrap items-center gap-x-3 gap-y-2">
        <div className="w-5 flex-shrink-0">
          {current && <ChevronRight className="w-5 h-5 text-cls-fighter" />}
        </div>

        <div className="w-9 text-center flex-shrink-0">
          <span className="font-heading text-[17px] font-bold text-ink">{p.initiative ?? '—'}</span>
        </div>

        {leading}

        <div className="grow shrink basis-48 min-w-0 sm:flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`font-heading text-[15px] font-semibold tracking-[0.01em] text-ink ${p.lifeState === 'DEAD' ? 'line-through' : ''}`}>
              {p.displayName}
              {p.formName && <span className="font-body text-[13px] font-semibold text-muted"> (as {p.formName})</span>}
            </span>
            {badges}
            {p.summon && (
              <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 bg-cls-druid/10 text-cls-druid">
                {summonerName ? `Summoned by ${summonerName}` : 'Summoned'}{p.summon.spell ? ` (${p.summon.spell})` : ''}
              </span>
            )}
            {banished && (
              <span title="Removed from the fight until Banishment ends: it takes no turns and can't be targeted"
                className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 bg-cls-warlock/10 text-cls-warlock">
                Banished
              </span>
            )}
            {/* Summon Greater Demon's demon after a successful Charisma save (XGE p.166, D185) */}
            {brokeFree(p.summon) && (
              <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 bg-debuff/10 text-debuff">
                Broke free
              </span>
            )}
          </div>
          <WildShapeStatus participant={p} onEnd={onEndWildShape} />
          {/* The spells whose Ongoing Modifiers are on it, such as Bless or Faerie Fire (D184) */}
          <SpellModifierChips participant={p} participants={participants} />
          {children}
        </div>

        {trailing}
      </div>
    </div>
  );
}
