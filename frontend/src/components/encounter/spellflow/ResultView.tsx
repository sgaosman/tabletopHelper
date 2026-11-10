import { Sparkles, AlertTriangle } from 'lucide-react';
import type { CastSpellResponse } from '../../../types/combat';
import Button from '../../ui/Button';

interface Props {
  result: CastSpellResponse;
  onClose: () => void;
}

/** What a cast or a repeated effect did: the summary, any call for the DM, and each target's rolls and effects. */
export default function ResultView({ result, onClose }: Props) {
  return (
    <div className="space-y-3">
      <div className="bg-page-alt border border-rule p-4">
        <p className="font-heading text-[16px] font-semibold text-ink mb-1 flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-ink" />
          {result.spellName}
          {result.slotLevelUsed > 0 && (
            <span className="font-body text-[13px] font-semibold text-muted">at level {result.slotLevelUsed}</span>
          )}
        </p>
        <p className="font-body text-[15px] font-semibold text-muted">{result.resultSummary}</p>
      </div>

      {!result.autoResolved && result.manualResolutionReason && (
        <div className="bg-cls-monk/10 border border-cls-monk/30 p-3">
          <p className="font-body text-[15px] font-semibold text-cls-monk flex items-center gap-2">
            <AlertTriangle className="w-4 h-4" />
            Requires DM adjudication: {result.manualResolutionReason}
          </p>
        </div>
      )}

      {result.targets.length > 0 && result.autoResolved && (
        <div className="space-y-1.5">
          {/* One row per outcome: Magic Missile's darts give one creature several rows (D200), so the index keeps
              each key unique */}
          {result.targets.map((t, index) => (
            <div key={`${t.targetId}-${index}`} className="bg-page-alt border border-rule px-3 py-2 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${
                  t.outcome === 'miss' || t.outcome === 'saved' ? 'bg-faint' :
                  t.outcome === 'critical' ? 'bg-debuff' : 'bg-buff'
                }`} />
                <span className="font-body text-[15px] font-semibold text-ink">{t.targetName}</span>
              </div>
              <div className="flex items-center gap-3 font-body text-[13px] font-semibold">
                {t.attackRoll != null && (
                  <span className={t.outcome === 'miss' ? 'text-faint' : 'text-buff'}>
                    Roll: {t.attackRoll}
                  </span>
                )}
                {t.saveRoll != null && (
                  <span className={t.outcome === 'saved' ? 'text-buff' : 'text-debuff'}>
                    Save: {t.saveRoll}
                  </span>
                )}
                {t.damage != null && t.damage > 0 && (
                  <span className="text-debuff">{t.damage} dmg</span>
                )}
                {t.healing != null && t.healing > 0 && (
                  <span className="text-buff">{t.healing} heal</span>
                )}
                {t.conditionsApplied.length > 0 && (
                  <span className="text-cls-monk">{t.conditionsApplied.join(', ')}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <Button variant="secondary" className="w-full" onClick={onClose}>
        Done
      </Button>
    </div>
  );
}
