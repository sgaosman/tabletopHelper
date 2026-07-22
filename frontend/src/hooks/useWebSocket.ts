import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, type IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { Encounter } from '../types/encounter';

interface UseWebSocketOptions {
  encounterId: string;
  onStateUpdate?: (state: Encounter) => void;
  onError?: (error: string) => void;
  enabled?: boolean;
}

export function useWebSocket({ encounterId, onStateUpdate, onError, enabled = true }: UseWebSocketOptions) {
  const [isConnected, setIsConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);

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

    const token = localStorage.getItem('accessToken');
    if (!token) return;

    const client = new Client({
      webSocketFactory: () => new SockJS(`/ws`),
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,

      onConnect: () => {
        setIsConnected(true);

        client.subscribe(`/topic/encounter/${encounterId}/state`, (message: IMessage) => {
          const state: Encounter = JSON.parse(message.body);
          onStateUpdate?.(state);
        });

        client.subscribe(`/user/queue/encounter/errors`, (message: IMessage) => {
          onError?.(message.body);
        });

        client.publish({
          destination: `/app/encounter/${encounterId}/join`,
          body: '{}',
        });
      },

      onDisconnect: () => {
        setIsConnected(false);
      },

      onStompError: (frame) => {
        onError?.(frame.body || 'WebSocket error');
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
  }, [encounterId, enabled]);

  return { isConnected, sendMessage };
}
