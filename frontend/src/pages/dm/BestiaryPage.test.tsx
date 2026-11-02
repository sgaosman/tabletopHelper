import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockMonster } from '../../test/mocks/handlers';
import BestiaryPage from './BestiaryPage';

/** The pager's next-page button, which only has an icon. */
function nextPageButton(): HTMLElement {
  return screen.getByRole('button', { name: 'Next page' });
}

/** A page of the bestiary with one monster on it, out of two pages. */
function monsterPage(page: number, name: string) {
  return { content: [makeMockMonster(`mon-${page}`, name, 'Dragon', '5', 60, 15)], totalElements: 21, totalPages: 2, number: page, size: 20 };
}

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
    // The desktop panel closes from its named close button
    await user.click(await screen.findByRole('button', { name: 'Close monster details' }));
    expect(screen.queryByRole('button', { name: 'Close monster details' })).not.toBeInTheDocument();
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

  it('loads each filter list once while the results are filtered and paged', async () => {
    const filterRequests: string[] = [];
    const pageRequests: string[] = [];
    server.use(
      http.get('/api/monsters/filters/:filter', ({ params }) => {
        filterRequests.push(params.filter as string);
        return HttpResponse.json(params.filter === 'types' ? ['Dragon', 'Humanoid'] : []);
      }),
      http.get('/api/monsters', ({ request }) => {
        const query = new URL(request.url).searchParams;
        pageRequests.push(`${query.get('type') ?? 'any'} ${query.get('page')}`);
        return HttpResponse.json(monsterPage(Number(query.get('page')), 'Young Red Dragon'));
      }),
    );
    render(<BestiaryPage />);
    const user = userEvent.setup();
    await screen.findByText('Young Red Dragon');

    await user.click(screen.getByRole('button', { name: 'All Types' }));
    await user.click(screen.getByRole('checkbox', { name: 'Dragon' }));
    await user.click(nextPageButton());

    await waitFor(() => expect(pageRequests).toEqual(['any 0', 'Dragon 0', 'Dragon 1']));
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    expect(filterRequests.sort()).toEqual(['challenge-ratings', 'sources', 'types']);
  });

  it('keeps the current page on screen, without the loading message, while the next page loads', async () => {
    let sendSecondPage = () => {};
    const secondPageSent = new Promise<void>(resolve => { sendSecondPage = resolve; });
    server.use(
      http.get('/api/monsters', async ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'));
        if (page === 1) await secondPageSent;
        return HttpResponse.json(monsterPage(page, page === 0 ? 'Goblin' : 'Wyvern'));
      }),
    );
    render(<BestiaryPage />);
    const user = userEvent.setup();
    await screen.findByText('Goblin');

    await user.click(nextPageButton());

    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    expect(screen.getByText('Goblin')).toBeInTheDocument();
    expect(screen.queryByText('Loading...')).not.toBeInTheDocument();
    expect(screen.getByRole('table').parentElement).toHaveAttribute('aria-busy', 'true');

    sendSecondPage();
    expect(await screen.findByText('Wyvern')).toBeInTheDocument();
    expect(screen.queryByText('Goblin')).not.toBeInTheDocument();
    expect(screen.getByRole('table').parentElement).toHaveAttribute('aria-busy', 'false');
  });
});
