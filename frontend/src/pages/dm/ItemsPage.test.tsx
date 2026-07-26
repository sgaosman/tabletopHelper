import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { render, setupAuthToken } from '../../test/test-utils';
import ItemsPage from './ItemsPage';

describe('ItemsPage', () => {
  beforeEach(async () => {
    setupAuthToken();
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/reference/items', () =>
        HttpResponse.json({
          content: [
            { id: 'itm-001', name: 'Longsword', type: 'Weapon', subtype: 'Martial', rarity: 'Common', description: null, properties: null, requiresAttunement: false, attunementCondition: null, weight: 3, cost: '15 gp', damageDice: '1d8', damageType: 'slashing', source: 'PHB' },
            { id: 'itm-002', name: 'Potion of Healing', type: 'Potion', subtype: null, rarity: 'Common', description: 'Heals 2d4+2', properties: null, requiresAttunement: false, attunementCondition: null, weight: 0.5, cost: '50 gp', damageDice: null, damageType: null, source: 'DMG' },
          ],
          totalElements: 2, totalPages: 1,
        })
      ),
    );
  });

  it('renders page title', async () => {
    render(<ItemsPage />);
    await waitFor(() => screen.getByText('Items'));
    expect(screen.getByText('Items')).toBeInTheDocument();
    expect(screen.getByText('Browse weapons, armour, and magic items')).toBeInTheDocument();
  });

  it('renders item table after data loads', async () => {
    render(<ItemsPage />);
    await waitFor(() => {
      expect(screen.getByText('Longsword')).toBeInTheDocument();
      expect(screen.getByText('Potion of Healing')).toBeInTheDocument();
    });
  });

  it('shows pagination when totalPages > 1', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/reference/items', () =>
        HttpResponse.json({ content: [{ id: 'itm-001', name: 'Longsword', type: 'Weapon', subtype: 'Martial', rarity: 'Common', description: null, properties: null, requiresAttunement: false, attunementCondition: null, weight: 3, cost: '15 gp', damageDice: '1d8', damageType: 'slashing', source: 'PHB' }], totalElements: 50, totalPages: 3 }),
      ),
    );
    render(<ItemsPage />);
    await waitFor(() => screen.getByText('Longsword'));
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
  });
});
