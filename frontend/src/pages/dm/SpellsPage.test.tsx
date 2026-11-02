import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import SpellsPage from './SpellsPage';

describe('SpellsPage', () => {
  beforeEach(async () => {
    setupAuthToken();
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/reference/spells', () =>
        HttpResponse.json({
          content: [
            { id: 'sp-001', name: 'Fireball', level: 3, school: 'Evocation', castingTime: '1 action', rangeDistance: '150 feet', duration: 'Instantaneous', components: { verbal: true, somatic: true }, concentration: false, ritual: false, description: '...', higherLevels: null, classes: ['Wizard'], damageType: 'fire', damageDice: '8d6', saveAbility: 'DEX', source: 'PHB' },
            { id: 'sp-002', name: 'Magic Missile', level: 1, school: 'Evocation', castingTime: '1 action', rangeDistance: '120 feet', duration: 'Instantaneous', components: { verbal: true, somatic: true }, concentration: false, ritual: false, description: '...', higherLevels: null, classes: ['Wizard', 'Sorcerer'], damageType: 'force', damageDice: '3d4+3', saveAbility: null, source: 'PHB' },
          ],
          totalElements: 2, totalPages: 1,
        })
      ),
    );
  });

  it('renders page title', async () => {
    render(<SpellsPage />);
    expect(await screen.findByRole('heading', { name: 'Spells' })).toBeInTheDocument();
    expect(screen.getByText('Search the complete spell list')).toBeInTheDocument();
  });

  it('renders spell table after data loads', async () => {
    render(<SpellsPage />);
    await waitFor(() => {
      expect(screen.getByText('Fireball')).toBeInTheDocument();
      expect(screen.getByText('Magic Missile')).toBeInTheDocument();
    });
  });

  it('shows pagination when totalPages > 1', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/reference/spells', () =>
        HttpResponse.json({ content: [{ id: 'sp-001', name: 'Fireball', level: 3, school: 'Evocation', castingTime: '1 action', rangeDistance: '150 feet', duration: 'Instantaneous', components: null, concentration: false, ritual: false, description: null, higherLevels: null, classes: [], damageType: null, damageDice: null, saveAbility: null, source: null }], totalElements: 25, totalPages: 3 }),
      ),
    );
    render(<SpellsPage />);
    await waitFor(() => screen.getByText('Fireball'));
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
  });

  it('loads the filter lists once, and a class\'s subclasses the first time it is picked', async () => {
    const filterRequests: string[] = [];
    server.use(
      http.get('/api/reference/spells/filters/:filter', ({ params, request }) => {
        const className = new URL(request.url).searchParams.get('className');
        filterRequests.push(className ? `${params.filter} of ${className}` : params.filter as string);
        if (params.filter === 'classes') return HttpResponse.json(['Cleric', 'Wizard']);
        if (params.filter === 'subclasses') return HttpResponse.json([`${className} (Life)`]);
        return HttpResponse.json([]);
      }),
    );
    render(<SpellsPage />);
    const user = userEvent.setup();
    await screen.findByText('Fireball');

    await user.click(screen.getByRole('button', { name: 'All Classes' }));
    await user.click(screen.getByRole('checkbox', { name: 'Cleric' }));
    expect(await screen.findByRole('button', { name: 'All Subclasses' })).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Cleric' }));
    expect(screen.queryByRole('button', { name: 'All Subclasses' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Cleric' }));

    expect(await screen.findByRole('button', { name: 'All Subclasses' })).toBeInTheDocument();
    expect(filterRequests.sort()).toEqual(['classes', 'schools', 'sources', 'subclasses of Cleric']);
  });
});
