import type {
  Client, IFrame, IMessage, IPublishParams, StompConfig, StompHeaders, StompSubscription,
  frameCallbackType, messageCallbackType,
} from '@stomp/stompjs';

export interface RecordedSubscription {
  id: string;
  destination: string;
  headers: StompHeaders;
  callback: messageCallbackType;
  active: boolean;
}

export interface PublishedMessage {
  destination: string;
  body: string;
  headers: StompHeaders;
}

function makeFrame(command: string, headers: StompHeaders, body: string): IFrame {
  return { command, headers, body, isBinaryBody: false, binaryBody: new TextEncoder().encode(body) };
}

/**
 * Stands in for the @stomp/stompjs Client in every test (see vitest.setup.ts), so no test opens a
 * socket. It records what the code subscribes to and publishes; tests push server frames with
 * emit() and fail().
 */
export class MockStompClient {
  static instances: MockStompClient[] = [];

  /** The most recently created client. */
  static latest(): MockStompClient {
    const client = MockStompClient.instances.at(-1);
    if (!client) throw new Error('No STOMP client has been created');
    return client;
  }

  static reset() {
    MockStompClient.instances = [];
  }

  connectHeaders: StompHeaders = {};
  webSocketFactory?: () => unknown;
  beforeConnect?: (client: Client) => void | Promise<void>;
  onConnect: frameCallbackType = () => {};
  onDisconnect: frameCallbackType = () => {};
  onStompError: frameCallbackType = () => {};
  /** Runs whenever an open socket closes, however it closed, as the real client's does. */
  onWebSocketClose: (event: CloseEvent) => void = () => {};

  connected = false;
  active = false;
  readonly subscriptions: RecordedSubscription[] = [];
  readonly published: PublishedMessage[] = [];
  private nextId = 0;

  constructor(config: StompConfig = {}) {
    Object.assign(this, config);
    MockStompClient.instances.push(this);
  }

  /** Connects on a later microtask, after beforeConnect (if set) has finished, as the real client does. */
  activate(): void {
    this.active = true;
    void this.connect();
  }

  private async connect() {
    await this.beforeConnect?.(this as unknown as Client);
    if (!this.active) return;
    this.webSocketFactory?.();
    this.connected = true;
    this.onConnect(makeFrame('CONNECTED', { version: '1.2' }, ''));
  }

  async deactivate(): Promise<void> {
    const wasConnected = this.connected;
    this.active = false;
    this.closeConnection();
    if (wasConnected) {
      this.onDisconnect(makeFrame('RECEIPT', {}, ''));
      this.socketClosed();
    }
  }

  subscribe(destination: string, callback: messageCallbackType, headers: StompHeaders = {}): StompSubscription {
    this.requireConnection();
    const subscription: RecordedSubscription = { id: `sub-${this.nextId++}`, destination, headers, callback, active: true };
    this.subscriptions.push(subscription);
    return { id: subscription.id, unsubscribe: () => { subscription.active = false; } };
  }

  unsubscribe(id: string): void {
    const subscription = this.subscriptions.find(s => s.id === id);
    if (subscription) subscription.active = false;
  }

  publish({ destination, body = '', headers = {} }: IPublishParams): void {
    this.requireConnection();
    this.published.push({ destination, body, headers });
  }

  isSubscribed(destination: string): boolean {
    return this.subscriptions.some(s => s.active && s.destination === destination);
  }

  /** Delivers a MESSAGE frame to each active subscription on the destination. Objects are sent as JSON. */
  emit(destination: string, body: unknown): void {
    const targets = this.subscriptions.filter(s => s.active && s.destination === destination);
    if (targets.length === 0) throw new Error(`No active subscription to ${destination}`);
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    for (const subscription of targets) {
      const headers = { destination, subscription: subscription.id, 'message-id': `msg-${this.nextId++}` };
      const message: IMessage = { ...makeFrame('MESSAGE', headers, text), ack: () => {}, nack: () => {} };
      subscription.callback(message);
    }
  }

  /** Sends an ERROR frame; like a real broker, the connection is closed afterwards. */
  fail(message: string): void {
    this.closeConnection();
    this.onStompError(makeFrame('ERROR', { message }, message));
  }

  /**
   * Closes the socket without a DISCONNECT, as a dropped network does. An active client then
   * reconnects, running beforeConnect again first. The real client waits reconnectDelay; this one
   * reconnects at once, and the promise settles when that attempt has finished.
   */
  async drop(): Promise<void> {
    this.goDown();
    if (this.active) await this.connect();
  }

  /**
   * Closes the socket without a DISCONNECT and leaves it closed, as while the real client waits out its backoff
   * before the next attempt. reconnectNow() makes that attempt.
   */
  goDown(): void {
    this.closeConnection();
    this.socketClosed();
  }

  /** The attempt after goDown(): an active client connects again, running beforeConnect first. */
  async reconnectNow(): Promise<void> {
    if (this.active && !this.connected) await this.connect();
  }

  private socketClosed() {
    this.onWebSocketClose({ code: 1006, reason: '', wasClean: false } as CloseEvent);
  }

  private closeConnection() {
    this.connected = false;
    this.subscriptions.forEach(s => { s.active = false; });
  }

  private requireConnection() {
    if (!this.connected) throw new TypeError('There is no underlying STOMP connection');
  }
}
