import { describe, it, expect, vi } from 'vitest';
import { Client } from '@stomp/stompjs';
import { MockStompClient } from './stomp';

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>(r => { resolve = r; });
  return { promise, resolve };
}

async function connectedClient(config: ConstructorParameters<typeof MockStompClient>[0] = {}) {
  const onConnect = vi.fn();
  const client = new MockStompClient({ onConnect, ...config });
  client.activate();
  await vi.waitFor(() => expect(onConnect).toHaveBeenCalledTimes(1));
  return client;
}

describe('MockStompClient', () => {
  it('replaces the stompjs Client in every test', () => {
    expect(Client).toBe(MockStompClient);
  });

  it('connects after activate and records subscriptions and published messages', async () => {
    const client = await connectedClient();

    client.subscribe('/topic/encounter/enc-001/state', vi.fn());
    client.publish({ destination: '/app/encounter/enc-001/join', body: '{}' });

    expect(client.connected).toBe(true);
    expect(client.isSubscribed('/topic/encounter/enc-001/state')).toBe(true);
    expect(client.published).toEqual([{ destination: '/app/encounter/enc-001/join', body: '{}', headers: {} }]);
  });

  it('waits for beforeConnect to finish before connecting', async () => {
    const tokenFetched = deferred();
    const onConnect = vi.fn();
    const client = new MockStompClient({
      onConnect,
      beforeConnect: async (c) => {
        await tokenFetched.promise;
        c.connectHeaders = { Authorization: 'Bearer fresh-token' };
      },
    });

    client.activate();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(onConnect).not.toHaveBeenCalled();

    tokenFetched.resolve();
    await vi.waitFor(() => expect(onConnect).toHaveBeenCalledTimes(1));
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer fresh-token' });
  });

  it('delivers emitted bodies to subscribers, as JSON for objects', async () => {
    const client = await connectedClient();
    const onMessage = vi.fn();
    client.subscribe('/topic/encounter/enc-001/state', onMessage);

    client.emit('/topic/encounter/enc-001/state', { roundNumber: 2 });
    client.emit('/topic/encounter/enc-001/state', 'plain text');

    expect(onMessage.mock.calls.map(([message]) => message.body)).toEqual(['{"roundNumber":2}', 'plain text']);
    expect(() => client.emit('/topic/nobody-listens', {})).toThrow('No active subscription to /topic/nobody-listens');
  });

  it('sends an error frame on fail and drops the connection', async () => {
    const onStompError = vi.fn();
    const client = await connectedClient({ onStompError });
    client.subscribe('/topic/encounter/enc-001/state', vi.fn());

    client.fail('Access denied');

    expect(onStompError).toHaveBeenCalledWith(expect.objectContaining({ command: 'ERROR', body: 'Access denied' }));
    expect(client.connected).toBe(false);
    expect(client.isSubscribed('/topic/encounter/enc-001/state')).toBe(false);
    expect(() => client.publish({ destination: '/app/x' })).toThrow('There is no underlying STOMP connection');
  });

  it('disconnects on deactivate, and never connects if deactivated first', async () => {
    const onDisconnect = vi.fn();
    const client = await connectedClient({ onDisconnect });
    await client.deactivate();
    expect(client.connected).toBe(false);
    expect(onDisconnect).toHaveBeenCalledTimes(1);

    const onConnect = vi.fn();
    const cancelled = new MockStompClient({ onConnect });
    cancelled.activate();
    await cancelled.deactivate();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(onConnect).not.toHaveBeenCalled();
  });
});
