import { useAttackRollPreview } from '../../queries/encounters';
import type { AttackRollPreview } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';

type Source = AttackRollPreview['sources'][number];

function reasons(sources: Source[], mode: Source['mode']) {
  return sources.filter(s => s.mode === mode).map(s => s.reason);
}

/**
 * What the attacker's and the target's conditions do to the attack roll, which the server adds by itself (PHB appendix
 * A): "Advantage: target restrained", and when both kinds are there, that they cancel (PHB p.173). The attacker's own
 * choice of advantage or disadvantage still combines with them. Nothing shows when there are none, or while it loads;
 * a null attacker is the DM's own attack entered by hand. `named` starts it with the target's name, for an attack at
 * several targets.
 */
export default function AttackRollSources({ attacker, target, withinFiveFeet, named = false }: {
  attacker: EncounterParticipant | null;
  target: EncounterParticipant;
  withinFiveFeet: boolean;
  named?: boolean;
}) {
  const preview = useAttackRollPreview(attacker, target, withinFiveFeet);
  const sources = preview.data?.sources ?? [];
  if (sources.length === 0) return null;
  const advantage = reasons(sources, 'ADVANTAGE');
  const disadvantage = reasons(sources, 'DISADVANTAGE');
  return (
    <p role="note" aria-label="Automatic advantage and disadvantage"
      className="w-full font-body text-[13px] font-semibold text-muted">
      {named && <span>{target.displayName}: </span>}
      {advantage.length > 0 && <span className="text-buff">Advantage: {advantage.join(', ')}</span>}
      {advantage.length > 0 && disadvantage.length > 0 && <span>; </span>}
      {disadvantage.length > 0 && <span className="text-debuff">Disadvantage: {disadvantage.join(', ')}</span>}
      {advantage.length > 0 && disadvantage.length > 0 && <span> (they cancel)</span>}
      {preview.data?.hitIsCritical && <span>; a hit is a critical hit</span>}
    </p>
  );
}
