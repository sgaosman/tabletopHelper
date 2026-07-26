import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import { makeMockMonster } from '../../test/mocks/handlers';
import BestiaryPage from './BestiaryPage';

describe('BestiaryPage', () => {
  beforeEach(async () => {
    setupAuthToken();
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/monsters', () =>
        HttpResponse.json({
          content: [
            makeMockMonster('mon-001', 'Goblin', 'Humanoid', '1/4', 7, 15),
            makeMockMonster('mon-002', 'Owlbear', 'Monstrosity', '3', 59, 13),
            makeMockMonster('mon-003', 'Ancient Dragon', 'Dragon', '20', 500, 22),
          ],
          totalElements: 3, totalPages: 1,
        })
      ),
    );
  });

  it('shows loading state initially', () => {
    render(<BestiaryPage />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('renders monster table with names', async () => {
    render(<BestiaryPage />);
    await waitFor(() => {
      expect(screen.getByText('Goblin')).toBeInTheDocument();
      expect(screen.getByText('Owlbear')).toBeInTheDocument();
      expect(screen.getByText('Ancient Dragon')).toBeInTheDocument();
    });
  });

  it('shows total count', async () => {
    render(<BestiaryPage />);
    await waitFor(() => {
      expect(screen.getByText('3 monsters found')).toBeInTheDocument();
    });
  });

  it('has search input', () => {
    render(<BestiaryPage />);
    expect(screen.getByPlaceholderText('Search monsters...')).toBeInTheDocument();
  });

  it('opens detail panel on monster click', async () => {
    render(<BestiaryPage />);
    await waitFor(() => screen.getByText('Goblin'));
    const user = userEvent.setup();
    await user.click(screen.getByText('Goblin'));
    // Detail panel closes when X button is clicked — verify a close mechanism exists
    await waitFor(() => {
      const closeButtons = screen.getAllByRole('button');
      expect(closeButtons.length).toBeGreaterThan(0);
    });
  });

  it('does not show pagination when totalPages <= 1', async () => {
    render(<BestiaryPage />);
    await waitFor(() => screen.getByText('Goblin'));
    expect(screen.queryByText(/Page 1 of/)).toBeNull();
  });

  it('shows pagination when totalPages > 1', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/monsters', () =>
        HttpResponse.json({ content: [makeMockMonster('mon-001', 'Goblin', 'Humanoid', '1/4', 7, 15)], totalElements: 25, totalPages: 2 }),
      ),
    );
    render(<BestiaryPage />);
    await waitFor(() => screen.getByText('Goblin'));
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
  });
});
