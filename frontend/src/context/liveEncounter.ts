import { createContext, useContext, type RefObject } from 'react';
import type { ConnectionState } from '../hooks/useWebSocket';
import type { CombatLogEntry } from '../types/combat';
import type { Encounter, EncounterView } from '../types/encounter';

/** What useEncounter() gives the components of a session page. */
export interface EncounterContextType {
  encounter: Encounter | null;
  isConnected: boolean;
  /** Connected, or connecting for the first time, or trying again after a drop, or offline. */
  connectionState: ConnectionState;
  /** Tries to connect again now instead of waiting for the next retry. */
  reconnect: () => void;
  error: string | null;
  sendMessage: (destination: string, body: unknown) => void;
}

/** Everything an EncounterProvider shares, including what the live encounter's hooks in queries/ need. */
export interface LiveEncounter extends EncounterContextType {
  encounterId: string;
  view: EncounterView;
  /** The view's combat log, oldest entry first. */
  log: CombatLogEntry[];
  /**
   * Counts the state and log messages the WebSocket has delivered. A request that reads it before and
   * after can tell whether a message arrived while it was in flight (D171).
   */
  socketMessages: RefObject<number>;
}

export const LiveEncounterContext = createContext<LiveEncounter | null>(null);

export function useLiveEncounter(): LiveEncounter {
  const context = useContext(LiveEncounterContext);
  if (!context) {
    throw new Error('useEncounter must be used within an EncounterProvider');
  }
  return context;
}
