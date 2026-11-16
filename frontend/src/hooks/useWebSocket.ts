import { useEffect, useRef, useState, useCallback } from 'react';
import axios from 'axios';
import { Client, ReconnectionTimeMode, type IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { refreshAccessToken } from '../api/tokenStore';
import { registerLiveSocket, tokenLifetimeMs } from '../api/liveSockets';
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

/**
 * Where the page's live connection stands: opening for the first time, live, trying again after it dropped, or
 * offline, because the browser has no network or the client has stopped (the session ended).
 */
export type ConnectionState = 'connecting' | 'connected' | 'reconnecting' | 'offline';

/** The first retry waits a second; each later one waits twice as long, up to half a minute. */
export const RECONNECT_DELAY_MS = 1000;
export const MAX_RECONNECT_DELAY_MS = 30_000;
/**
 * The server closes a socket when the access token it connected with expires (D238), so the client reconnects with a
 * fresh token this long before then. A token that lives no longer than this is renewed halfway through its life.
 */
export const RENEW_BEFORE_EXPIRY_MS = 60_000;
/** The close code the server uses when a socket's token expired or its user logged out (D238). */
export const POLICY_VIOLATION_CLOSE = 1008;

function browserOffline() {
  return typeof navigator !== 'undefined' && navigator.onLine === false;
}

export function useWebSocket({ encounterId, view = 'player', enabled = true, ...callbacks }: UseWebSocketOptions) {
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting');
  const clientRef = useRef<Client | null>(null);
  // Starts the client again at once, without waiting out the backoff; set while a client exists
  const restartRef = useRef<(() => void) | null>(null);

  // The client reads the latest callbacks from here, so a new callback never opens a new connection
  const callbacksRef = useRef<Callbacks>(callbacks);
  useEffect(() => {
    callbacksRef.current = callbacks;
  });

  /** Tries to connect again now, such as from a "Retry now" button. */
  const reconnect = useCallback(() => restartRef.current?.(), []);

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
    let disposed = false;
    // Set by logout, which stops the client for good (D238)
    let stopped = false;
    // Set while the client drops its socket on purpose to reconnect with a fresh token, so no banner shows
    let renewing = false;
    // When the socket should be renewed, from the lifetime of the token it connected with; null if unknown
    let renewAt: number | null = null;
    let renewTimer: ReturnType<typeof setTimeout> | undefined;
    // The socket closed or failed: an active client is trying again, a stopped one is not
    const markDown = () => {
      if (disposed || renewing) return;
      setConnectionState(client.active && !browserOffline() ? 'reconnecting' : 'offline');
    };
    // Reconnects with a fresh token before the server closes the socket at the token's expiry, so updates keep
    // arriving without a gap the user could notice. onReconnect then reloads what was published meanwhile.
    const renew = () => {
      if (disposed || stopped || !client.connected) return;
      renewing = true;
      void client.deactivate().then(() => {
        renewing = false;
        if (!disposed && !stopped) client.activate();
      });
    };
    const scheduleRenewal = () => {
      clearTimeout(renewTimer);
      if (renewAt !== null) renewTimer = setTimeout(renew, Math.max(renewAt - Date.now(), 0));
    };

    const client = new Client({
      webSocketFactory: () => new SockJS(`/ws`),
      // Runs before every connect and reconnect, so a reconnect never offers an expired token (D156).
      // It must not throw: the client would stop without scheduling another attempt.
      beforeConnect: async () => {
        // Unknown until a refresh succeeds: a connect on an older token is left for the server to close at expiry
        renewAt = null;
        try {
          const { accessToken } = await refreshAccessToken();
          client.connectHeaders = { Authorization: `Bearer ${accessToken}` };
          const lifetime = tokenLifetimeMs(accessToken);
          if (lifetime !== null) {
            renewAt = Date.now() + Math.max(lifetime - RENEW_BEFORE_EXPIRY_MS, lifetime / 2);
          }
        } catch (err) {
          if (!client.active) return;
          if (axios.isAxiosError(err) && err.response?.status === 401) {
            // The session is over, so retrying cannot succeed
            await client.deactivate();
            if (!disposed) setConnectionState('offline');
            callbacksRef.current.onError?.('Your session has ended. Please sign in again.');
          }
          // Otherwise the server could not be reached: this attempt fails and the client tries again
        }
      },
      // Truncated exponential backoff, reset by each successful connect
      reconnectDelay: RECONNECT_DELAY_MS,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: MAX_RECONNECT_DELAY_MS,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,

      onConnect: () => {
        setConnectionState('connected');
        scheduleRenewal();

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

      onDisconnect: markDown,
      // A dropped network closes the socket without a DISCONNECT frame, so this is what notices it. The server closes
      // with 1008 when the socket's token expired or the user logged out (D238): the client reconnects after its
      // first short delay with a fresh token, so that close shows no banner. If the refresh is refused, the session
      // is over and beforeConnect stops the client; if the reconnect fails, its own close shows the banner.
      onWebSocketClose: (event?: CloseEvent) => {
        clearTimeout(renewTimer);
        if (event?.code === POLICY_VIOLATION_CLOSE && client.active && !stopped) return;
        markDown();
      },

      onStompError: (frame) => {
        callbacksRef.current.onError?.(frame.body || 'WebSocket error');
        markDown();
      },
    });

    const restart = () => {
      if (disposed || stopped) return;
      // The socket outlived a short loss of network
      if (client.connected) {
        setConnectionState('connected');
        return;
      }
      setConnectionState(browserOffline() ? 'offline' : 'reconnecting');
      void client.deactivate().then(() => {
        if (!disposed) client.activate();
      });
    };
    // The browser knows when the network goes and comes back: say so at once, and retry as soon as it returns
    // instead of waiting out the backoff
    const handleOffline = () => {
      if (!disposed) setConnectionState('offline');
    };
    window.addEventListener('offline', handleOffline);
    window.addEventListener('online', restart);

    // Logout stops the client itself rather than waiting for this page to unmount, so it cannot reconnect in the
    // meantime with the session being ended (D238)
    const unregister = registerLiveSocket(async () => {
      stopped = true;
      clearTimeout(renewTimer);
      await client.deactivate();
      if (!disposed) setConnectionState('offline');
    });

    clientRef.current = client;
    restartRef.current = restart;
    setConnectionState('connecting');
    client.activate();

    return () => {
      disposed = true;
      unregister();
      clearTimeout(renewTimer);
      window.removeEventListener('offline', handleOffline);
      window.removeEventListener('online', restart);
      void client.deactivate();
      clientRef.current = null;
      restartRef.current = null;
    };
  }, [encounterId, view, enabled]);

  return { isConnected: connectionState === 'connected', connectionState, reconnect, sendMessage };
}
