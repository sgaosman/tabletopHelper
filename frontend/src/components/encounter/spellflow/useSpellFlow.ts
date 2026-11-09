import { useCallback, useState } from 'react';
import { useFetchSpellTargeting } from '../../../queries/reference';
import { apiErrorMessage } from '../../../api/errors';
import type { CastSpellResponse } from '../../../types/combat';
import type { SpellTargetingInfo } from '../../../types/reference';

/** The steps every spell pop-up shares; a pop-up may add its own before them. */
export type SpellFlowStep = 'target' | 'confirm';

/** The Manual Modifiers a request carries (D164); a blank one is left out. */
export interface SpellOverrides {
  overrideSpellAttackBonus?: number;
  overrideSpellSaveDC?: number;
}

/** A spell that can target nobody at all, so the target step is skipped. */
export function takesNoTargets(info: SpellTargetingInfo | null): boolean {
  return !!info && !info.selfOnly && !info.canTargetSelf && !info.canTargetAllies && !info.canTargetEnemies;
}

/**
 * The state of a spell pop-up from its targets on: the step, the targeting lookup, the chosen targets, advantage,
 * the Manual Modifiers typed in, and the request that resolves it with its result or error. `S` names any steps
 * the pop-up has before the shared ones.
 */
export default function useSpellFlow<S extends string = never>(
  initialStep: S | SpellFlowStep,
  { allowManualModifiers = false }: { allowManualModifiers?: boolean } = {},
) {
  const fetchTargeting = useFetchSpellTargeting();
  const [step, setStep] = useState<S | SpellFlowStep>(initialStep);
  const [targetingInfo, setTargetingInfo] = useState<SpellTargetingInfo | null>(null);
  // Nothing is shown as a target until a lookup has finished
  const [targetingLoaded, setTargetingLoaded] = useState(false);
  const [selectedTargets, setSelectedTargets] = useState<string[]>([]);
  // Targets beyond the spell's own limit, as Twinned Spell's second creature (PHB p.102, M18)
  const [extraTargets, setExtraTargets] = useState(0);
  const [advantage, setAdvantage] = useState<boolean | null>(null);
  const [overrideAttackBonus, setOverrideAttackBonus] = useState('');
  const [overrideSaveDC, setOverrideSaveDC] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<CastSpellResponse | null>(null);
  const [error, setError] = useState('');

  /**
   * Looks up who the spell can target at this slot level, clearing any targets chosen, then moves to the target
   * step, or straight to confirm when the spell targets nobody. A failed lookup (no effect template) leaves the
   * targets unrestricted.
   */
  const lookUpTargeting = useCallback(async (spellName: string, slotLevel: number) => {
    setTargetingLoaded(false);
    setSelectedTargets([]);
    let info: SpellTargetingInfo | null = null;
    try {
      info = await fetchTargeting(spellName, slotLevel);
    } catch {
      info = null;
    }
    setTargetingInfo(info);
    setTargetingLoaded(true);
    setStep(takesNoTargets(info) ? 'confirm' : 'target');
  }, [fetchTargeting]);

  /**
   * Skips the targeting lookup, leaving every creature still standing a choice, with these targets already chosen:
   * for what a Spell Effect's area does to whoever is in it, which the spell's own targeting doesn't limit (M15).
   */
  const chooseFreely = useCallback((targets: string[]) => {
    setTargetingInfo(null);
    setTargetingLoaded(true);
    setSelectedTargets(targets);
    setStep('target');
  }, []);

  /**
   * Goes straight to confirm with no targets and no lookup: for a summoning spell (D182), whose creatures are chosen in
   * its own step and which targets no creature.
   */
  const skipTargets = useCallback(() => {
    setTargetingInfo(null);
    setTargetingLoaded(true);
    setSelectedTargets([]);
    setStep('confirm');
  }, []);

  // A target is added only while the spell's limit allows
  const toggleTarget = useCallback((id: string) => {
    setSelectedTargets(prev => {
      if (prev.includes(id)) return prev.filter(t => t !== id);
      const max = targetingInfo?.maxTargets ?? -1;
      if (max > 0 && prev.length >= max + extraTargets) return prev;
      return [...prev, id];
    });
  }, [targetingInfo, extraTargets]);

  // The targeting the target step shows: the spell's, with its limit raised by any extra targets
  const shownTargeting: SpellTargetingInfo | null = targetingInfo && extraTargets > 0 && (targetingInfo.maxTargets ?? -1) > 0
    ? { ...targetingInfo, maxTargets: (targetingInfo.maxTargets ?? 0) + extraTargets }
    : targetingInfo;

  function overrides(): SpellOverrides {
    return {
      overrideSpellAttackBonus: allowManualModifiers && overrideAttackBonus ? parseInt(overrideAttackBonus) : undefined,
      overrideSpellSaveDC: allowManualModifiers && overrideSaveDC ? parseInt(overrideSaveDC) : undefined,
    };
  }

  /** Sends the request; its result is kept to show, or its error to retry from. */
  async function submit(request: () => Promise<CastSpellResponse>) {
    setSubmitting(true);
    setError('');
    try {
      setResult(await request());
    } catch (err) {
      setError(apiErrorMessage(err));
      setSubmitting(false);
    }
  }

  return {
    step, setStep,
    targetingInfo: shownTargeting, targetingLoading: !targetingLoaded, takesNoTargets: takesNoTargets(targetingInfo), lookUpTargeting,
    chooseFreely, skipTargets,
    selectedTargets, toggleTarget, setExtraTargets,
    advantage, setAdvantage,
    overrideAttackBonus, setOverrideAttackBonus, overrideSaveDC, setOverrideSaveDC, overrides,
    submitting, result, error, setError, submit,
  };
}
