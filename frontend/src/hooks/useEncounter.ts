import { useLiveEncounter, type EncounterContextType } from '../context/liveEncounter';

/** The live encounter from the nearest EncounterProvider (context/EncounterContext.tsx). */
export function useEncounter(): EncounterContextType {
  return useLiveEncounter();
}
