import { useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiErrorMessage } from '../api/errors';
import { useWebSocket } from '../hooks/useWebSocket';
import { liveCombatLogQuery, liveEncounterQuery } from '../queries/encounters';
import { queryKeys } from '../queries/keys';
import type { CombatLogEntry } from '../types/combat';
import type { EncounterView } from '../types/encounter';
import { LiveEncounterContext, type LiveEncounter } from './liveEncounter';

const NO_LOG: CombatLogEntry[] = [];

/**
 * Holds a live encounter for a session page. The encounter and its combat log are cached queries that the
 * WebSocket's messages replace as they arrive (D171). `view` picks the WebSocket topics: the DM page asks
 * for the DM View, every other page gets the Player View (D157).
 */
export function EncounterProvider({ encounterId, view = 'player', children }: { encounterId: string; view?: EncounterView; children: ReactNode }) {
  const queryClient = useQueryClient();
  const socketMessages = useRef(0);
  const [socketError, setSocketError] = useState<string | null>(null);
  const encounterQuery = useQuery(liveEncounterQuery(encounterId, view, socketMessages));
  const logQuery = useQuery(liveCombatLogQuery(encounterId, view, socketMessages));

  const { isConnected, sendMessage } = useWebSocket({
    encounterId,
    view,
    onStateUpdate: encounter => {
      socketMessages.current += 1;
      queryClient.setQueryData(queryKeys.encounters.detail(encounterId, view), encounter);
      setSocketError(null);
    },
    onLogUpdate: log => {
      socketMessages.current += 1;
      queryClient.setQueryData(queryKeys.encounters.log(encounterId, view), log);
    },
    // Messages published while the connection was down never arrive, so both load again; the log's key
    // starts with the encounter's
    onReconnect: () => void queryClient.invalidateQueries({ queryKey: queryKeys.encounters.detail(encounterId, view) }),
    onError: setSocketError,
  });

  const encounter = encounterQuery.data ?? null;
  const log = logQuery.data ?? NO_LOG;
  const error = socketError ?? (encounterQuery.error ? apiErrorMessage(encounterQuery.error) : null);
  const value = useMemo<LiveEncounter>(
    () => ({ encounter, log, isConnected, error, sendMessage, encounterId, view, socketMessages }),
    [encounter, log, isConnected, error, sendMessage, encounterId, view],
  );

  return (
    <LiveEncounterContext.Provider value={value}>
      {children}
    </LiveEncounterContext.Provider>
  );
}
