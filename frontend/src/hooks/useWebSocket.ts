import { useEffect, useRef, useState, useCallback } from 'react';
import axios from 'axios';
import { Client, type IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { refreshAccessToken } from '../api/tokenStore';
import type { CombatLogEntry } from '../types/combat';
import type { Encounter, EncounterView } from '../types/encounter';

interface UseWebSocketOptions {
  encounterId: string;
  /**
   * The DM View is published on dm-state and the whole combat log on dm-log; the Player View on state and
   * the players' log on log. The server lets only the DM subscribe to the dm- topics (D157, D171).
   */
  view?: EncounterView;
  onStateUpdate?: (state: Encounter) => void;
  /** Receives the view's whole combat log, which the server sends after every change (D171). */
  onLogUpdate?: (log: CombatLogEntry[]) => void;
  /** Runs when the connection comes back after dropping, once the subscriptions are in place. */
  onReconnect?: () => void;
  onError?: (error: string) => void;
  enabled?: boolean;
}

type Callbacks = Pick<UseWebSocketOptions, 'onStateUpdate' | 'onLogUpdate' | 'onReconnect' | 'onError'>;

export function useWebSocket({ encounterId, view = 'player', enabled = true, ...callbacks }: UseWebSocketOptions) {
  const [isConnected, setIsConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);

  // The client reads the latest callbacks from here, so a new callback never opens a new connection
  const callbacksRef = useRef<Callbacks>(callbacks);
  useEffect(() => {
    callbacksRef.current = callbacks;
  });

  const sendMessage = useCallback((destination: string, body: unknown) => {
    if (clientRef.current?.connected) {
      clientRef.current.publish({
        destination,
        body: JSON.stringify(body),
      });
    }
  }, []);

  useEffect(() => {
    if (!enabled || !encounterId) return;

    const topics = view === 'dm' ? { state: 'dm-state', log: 'dm-log' } : { state: 'state', log: 'log' };
    let connectedBefore = false;

    const client = new Client({
      webSocketFactory: () => new SockJS(`/ws`),
      // Runs before every connect and reconnect, so a reconnect never offers an expired token (D156).
      // It must not throw: the client would stop without scheduling another attempt.
      beforeConnect: async () => {
        try {
          const { accessToken } = await refreshAccessToken();
          client.connectHeaders = { Authorization: `Bearer ${accessToken}` };
        } catch (err) {
          if (!client.active) return;
          if (axios.isAxiosError(err) && err.response?.status === 401) {
            // The session is over, so retrying cannot succeed
            await client.deactivate();
            callbacksRef.current.onError?.('Your session has ended. Please sign in again.');
          }
          // Otherwise the server could not be reached: this attempt fails and the client tries again
        }
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,

      onConnect: () => {
        setIsConnected(true);

        client.subscribe(`/topic/encounter/${encounterId}/${topics.state}`, (message: IMessage) => {
          const state: Encounter = JSON.parse(message.body);
          callbacksRef.current.onStateUpdate?.(state);
        });

        client.subscribe(`/topic/encounter/${encounterId}/${topics.log}`, (message: IMessage) => {
          const log: CombatLogEntry[] = JSON.parse(message.body);
          callbacksRef.current.onLogUpdate?.(log);
        });

        client.subscribe(`/user/queue/encounter/errors`, (message: IMessage) => {
          callbacksRef.current.onError?.(message.body);
        });

        client.publish({
          destination: `/app/encounter/${encounterId}/join`,
          body: '{}',
        });

        if (connectedBefore) callbacksRef.current.onReconnect?.();
        connectedBefore = true;
      },

      onDisconnect: () => {
        setIsConnected(false);
      },

      onStompError: (frame) => {
        callbacksRef.current.onError?.(frame.body || 'WebSocket error');
        setIsConnected(false);
      },
    });

    clientRef.current = client;
    client.activate();

    return () => {
      client.deactivate();
      clientRef.current = null;
      setIsConnected(false);
    };
  }, [encounterId, view, enabled]);

  return { isConnected, sendMessage };
}
