import type { EncounterParticipant } from '../../../types/encounter';
import type { MetamagicName, MetamagicOption } from '../../../types/combat';
import {
  chosenCost, METAMAGIC_TEXT, metamagicBlock, metamagicCombines, metamagicCost,
} from '../../../utils/metamagic';
import Button from '../../ui/Button';

interface Props {
  spellName: string;
  /** The level the spell is cast at, 0 for a cantrip, which sets Twinned Spell's cost and eligibility. */
  castLevel: number;
  /** The Metamagic options the caster knows, with what the server says of each for this spell. */
  options: MetamagicOption[];
  /** The sorcery points the caster has. */
  points: number;
  chosen: MetamagicName[];
  onToggle: (option: MetamagicName) => void;
  onContinue: () => void;
  onBack: () => void;
}

/**
 * A sorcerer's Metamagic for the spell being cast (PHB p.102, M18): each option they know, with its cost in sorcery
 * points and what it does, greyed with the reason when it can't change this spell at this level, when the points
 * left can't pay for it, or when another option is chosen (one per spell, except Empowered Spell). The server checks
 * them all again.
 */
export default function MetamagicStep({
  spellName, castLevel, options, points, chosen, onToggle, onContinue, onBack,
}: Props) {
  const spent = chosenCost(options, chosen, castLevel);
  return (
    <>
      <div className="flex items-center gap-2 mb-2">
        <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>
        <span className="font-heading text-[16px] font-semibold text-ink">{spellName}</span>
        <span className="font-body text-[13px] font-semibold text-faint">— Metamagic (optional)</span>
      </div>
      <p className="font-body text-[13px] text-muted">
        {points} sorcery point{points === 1 ? '' : 's'} left
        {spent > 0 && <>; the chosen options cost <span className="font-semibold text-ink">{spent}</span></>}
      </p>
      <ul className="space-y-1.5" aria-label="Metamagic options">
        {options.map(option => {
          const selected = chosen.includes(option.option);
          const cost = metamagicCost(option, castLevel);
          const reason = metamagicBlock(option, castLevel)
            ?? (!selected && !metamagicCombines(option.option, chosen)
              ? 'Only one option per spell, except Empowered Spell' : null)
            ?? (!selected && spent + cost > points ? `Needs ${cost} sorcery points` : null);
          const reasonId = `metamagic-${option.option}`;
          return (
            <li key={option.option}>
              <label className={`flex items-start gap-2 px-3 py-2 border border-rule
                ${reason && !selected ? 'bg-page-alt/50 opacity-60' : 'bg-page-alt'}`}>
                <input type="checkbox" checked={selected} disabled={!!reason && !selected}
                  onChange={() => onToggle(option.option)} aria-describedby={reasonId} className="mt-1" />
                <span className="min-w-0">
                  <span className="font-body text-[15px] font-semibold text-ink">{option.name}</span>
                  <span className="font-body text-[13px] font-semibold text-faint ml-2">
                    {cost} point{cost === 1 ? '' : 's'}
                  </span>
                  <span id={reasonId} className={`block font-body text-[12.5px] ${reason && !selected ? 'text-debuff font-semibold' : 'text-muted'}`}>
                    {reason && !selected ? reason : METAMAGIC_TEXT[option.option]}
                  </span>
                </span>
              </label>
            </li>
          );
        })}
      </ul>
      {chosen.includes('TWINNED') && (
        <p className="font-body text-[13px] text-muted">Choose two different creatures as the spell's targets.</p>
      )}
      <div className="flex justify-end">
        <Button onClick={onContinue}>{chosen.length > 0 ? 'Continue' : 'Continue without Metamagic'}</Button>
      </div>
    </>
  );
}

/**
 * The creatures Careful Spell protects and Heightened Spell targets, chosen among the spell's targets on the confirm
 * step (PHB p.102). Careful protects up to the sorcerer's Charisma modifier, at least one, which the server checks.
 */
export function MetamagicTargets({ chosen, targets, careful, heightened, onCareful, onHeightened }: {
  chosen: MetamagicName[];
  targets: EncounterParticipant[];
  careful: string[];
  heightened: string | null;
  onCareful: (ids: string[]) => void;
  onHeightened: (id: string | null) => void;
}) {
  if (!chosen.includes('CAREFUL') && !chosen.includes('HEIGHTENED')) return null;
  return (
    <div className="bg-page-alt border border-rule p-3 space-y-2">
      {chosen.includes('CAREFUL') && (
        <fieldset>
          <legend className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">
            Careful Spell: who succeeds on the save (up to your Charisma modifier)
          </legend>
          <div className="flex flex-wrap gap-3 mt-1">
            {targets.map(target => (
              <label key={target.id} className="flex items-center gap-1 font-body text-[14px] text-ink">
                <input type="checkbox" checked={careful.includes(target.id)}
                  onChange={() => onCareful(careful.includes(target.id)
                    ? careful.filter(id => id !== target.id) : [...careful, target.id])} />
                {target.displayName}
              </label>
            ))}
          </div>
        </fieldset>
      )}
      {chosen.includes('HEIGHTENED') && (
        <fieldset>
          <legend className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted">
            Heightened Spell: who has disadvantage on the first save
          </legend>
          <div className="flex flex-wrap gap-3 mt-1">
            {targets.map(target => (
              <label key={target.id} className="flex items-center gap-1 font-body text-[14px] text-ink">
                <input type="radio" name="heightened" checked={heightened === target.id}
                  onChange={() => onHeightened(target.id)} />
                {target.displayName}
              </label>
            ))}
          </div>
        </fieldset>
      )}
    </div>
  );
}
