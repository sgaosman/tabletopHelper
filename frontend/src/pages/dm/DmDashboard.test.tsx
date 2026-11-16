import { describe, it, expect, beforeEach, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { render, screen, setupAuthToken, within } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import type { RunningEncounter } from '../../types/encounter';
import DmDashboard from './DmDashboard';

const running = [
  {
    id: 'enc-active', name: 'Goblin Ambush', status: 'ACTIVE', roundNumber: 3, campaignId: 'camp-001',
    campaignName: 'Lost Mine', participantCount: 5, updatedAt: '2026-11-16T10:00:00Z',
  },
  {
    id: 'enc-paused', name: 'Bridge Toll', status: 'PAUSED', roundNumber: 1, campaignId: 'camp-002',
    campaignName: 'Curse of Strahd', participantCount: 2, updatedAt: '2026-11-16T11:00:00Z',
  },
] satisfies RunningEncounter[];

describe('DmDashboard running encounters (D236)', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('lists each running encounter with its campaign, status and round, in the order the server gives', async () => {
    server.use(http.get('/api/encounters/running', () => HttpResponse.json(running)));
    render(<DmDashboard />);

    const section = await screen.findByRole('region', { name: 'Running encounters' });
    const rows = within(section).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Goblin Ambush');
    expect(rows[0]).toHaveTextContent('Lost Mine · Active · Round 3');
    expect(rows[1]).toHaveTextContent('Bridge Toll');
    expect(rows[1]).toHaveTextContent('Curse of Strahd · Paused · Round 1');
  });

  it("links each row to its encounter's DM session page", async () => {
    server.use(http.get('/api/encounters/running', () => HttpResponse.json(running)));
    render(<DmDashboard />);

    expect(await screen.findByRole('link', { name: 'Go to session: Goblin Ambush' }))
      .toHaveAttribute('href', '/dm/encounter/enc-active/session');
    expect(screen.getByRole('link', { name: 'Go to session: Bridge Toll' }))
      .toHaveAttribute('href', '/dm/encounter/enc-paused/session');
  });

  it('shows no running encounters section when none is under way, and the cards as before', async () => {
    let asked = false;
    server.use(http.get('/api/encounters/running', () => {
      asked = true;
      return HttpResponse.json([] satisfies RunningEncounter[]);
    }));
    render(<DmDashboard />);

    expect(await screen.findByRole('button', { name: /Campaigns/ })).toBeInTheDocument();
    await vi.waitFor(() => expect(asked).toBe(true));
    expect(screen.queryByRole('region', { name: 'Running encounters' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Go to session/ })).not.toBeInTheDocument();
  });

  it("shows the server's message when the list can't be loaded", async () => {
    server.use(http.get('/api/encounters/running', () =>
      HttpResponse.json({ error: 'Access denied' }, { status: 403 })));
    render(<DmDashboard />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Access denied');
  });
});
