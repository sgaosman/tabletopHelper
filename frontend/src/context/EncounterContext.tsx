import { createContext, useContext, useReducer, useCallback, useEffect, ReactNode } from 'react';
import { useWebSocket } from '../hooks/useWebSocket';
import { encounterApi } from '../api/encounterApi';
import type { Encounter } from '../types/encounter';

interface EncounterState {
  encounter: Encounter | null;
  isConnected: boolean;
  error: string | null;
}

type EncounterAction =
  | { type: 'SET_ENCOUNTER'; payload: Encounter }
  | { type: 'SET_CONNECTED'; payload: boolean }
  | { type: 'SET_ERROR'; payload: string }
  | { type: 'CLEAR' };

const initialState: EncounterState = {
  encounter: null,
  isConnected: false,
  error: null,
};

function encounterReducer(state: EncounterState, action: EncounterAction): EncounterState {
  switch (action.type) {
    case 'SET_ENCOUNTER':
      return { ...state, encounter: action.payload, error: null };
    case 'SET_CONNECTED':
      return { ...state, isConnected: action.payload };
    case 'SET_ERROR':
      return { ...state, error: action.payload };
    case 'CLEAR':
      return initialState;
    default:
      return state;
  }
}

interface EncounterContextType extends EncounterState {
  refreshEncounter: () => void;
  sendMessage: (destination: string, body: unknown) => void;
}

const EncounterContext = createContext<EncounterContextType | null>(null);

export function EncounterProvider({ encounterId, children }: { encounterId: string; children: ReactNode }) {
  const [state, dispatch] = useReducer(encounterReducer, initialState);

  const handleStateUpdate = useCallback((encounter: Encounter) => {
    dispatch({ type: 'SET_ENCOUNTER', payload: encounter });
  }, []);

  const handleError = useCallback((error: string) => {
    dispatch({ type: 'SET_ERROR', payload: error });
  }, []);

  const { isConnected, sendMessage } = useWebSocket({
    encounterId,
    onStateUpdate: handleStateUpdate,
    onError: handleError,
    enabled: true,
  });

  useEffect(() => {
    dispatch({ type: 'SET_CONNECTED', payload: isConnected });
  }, [isConnected]);

  const refreshEncounter = useCallback(() => {
    encounterApi.getById(encounterId).then(res => {
      dispatch({ type: 'SET_ENCOUNTER', payload: res.data });
    });
  }, [encounterId]);

  useEffect(() => {
    refreshEncounter();
  }, [refreshEncounter]);

  return (
    <EncounterContext.Provider value={{ ...state, refreshEncounter, sendMessage }}>
      {children}
    </EncounterContext.Provider>
  );
}

export function useEncounter() {
  const context = useContext(EncounterContext);
  if (!context) {
    throw new Error('useEncounter must be used within an EncounterProvider');
  }
  return context;
}
