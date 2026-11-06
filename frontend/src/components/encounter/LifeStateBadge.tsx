import { Skull } from 'lucide-react';
import type { EncounterParticipant } from '../../types/encounter';

/** Dying, Stable or Dead, from the participant's life state (D161); nothing while it is up. */
export default function LifeStateBadge({ participant }: { participant: EncounterParticipant }) {
  switch (participant.lifeState) {
    case 'DEAD':
      return <span className="inline-flex items-center gap-1 font-heading text-[11px] font-semibold tracking-[0.02em] text-debuff"><Skull className="w-4 h-4" />Dead</span>;
    case 'DYING':
      return <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-debuff bg-debuff-bg border border-debuff">Dying</span>;
    case 'STABLE':
      return <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-buff border border-buff">Stable</span>;
    default:
      return null;
  }
}
