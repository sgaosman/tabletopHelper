import { Heart, Shield } from 'lucide-react';
import type { EncounterParticipant } from '../../types/encounter';
import HpBar from './HpBar';

/**
 * A participant's exact HP, temporary HP and AC, for a row's right-hand side. The Player View sends exact HP only
 * for the player's own character, and for monsters when the campaign shows it (D157).
 */
export default function HpReadout({ participant: p }: { participant: EncounterParticipant }) {
  const hpRatio = p.hpCurrent != null && p.hpMax ? p.hpCurrent / p.hpMax : 0;
  return (
    <>
      <div className="w-36 flex-shrink-0">
        <div className="flex items-center justify-end gap-1.5 mb-1">
          <Heart className={`w-3.5 h-3.5 ${hpRatio > 0.5 ? 'text-buff' : hpRatio > 0.25 ? 'text-cls-monk' : 'text-debuff'}`} />
          <span className="font-heading text-[13px] font-semibold text-ink">{p.hpCurrent}/{p.hpMax}</span>
          {(p.hpTemp || 0) > 0 && <span className="text-cls-wizard font-heading text-[13px] font-semibold">+{p.hpTemp}</span>}
        </div>
        <HpBar participant={p} />
      </div>
      <div className="flex items-center gap-1 w-12 justify-end flex-shrink-0">
        <Shield className="w-4 h-4 text-muted" />
        <span className="text-muted font-heading text-[13px] font-semibold">{p.armourClass}</span>
      </div>
    </>
  );
}
