import { useState } from 'react';
import type { SummonOptions } from '../../../types/encounter';
import { initialDraft, type SummonDraft } from '../../../utils/summons';

/**
 * The choices of a summon step, started afresh from each new set of summon options (another spell or slot level), on
 * `optionId` when given, as the option a waiting summoning's casting chose or rolled. Null until the options load.
 */
export default function useSummonDraft(options: SummonOptions | undefined, optionId?: string | null) {
  const key = options ? `${options.spellName}:${options.slotLevel}:${optionId ?? ''}` : null;
  const [state, setState] = useState<{ key: string | null; draft: SummonDraft | null }>({ key: null, draft: null });
  let current = state;
  if (key !== state.key) {
    current = { key, draft: options ? initialDraft(options, optionId) : null };
    setState(current);
  }
  const setDraft = (draft: SummonDraft) => setState({ key, draft });
  return [current.draft, setDraft] as const;
}
