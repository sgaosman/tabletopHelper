import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter } from '../../test/mocks/handlers';
import { queryKeys } from '../../queries/keys';
import SubclassModal from './SubclassModal';

describe('SubclassModal', () => {
  const character = makeMockCharacter('char-001', 'Gimli', 'Fighter', 3);
  const onComplete = () => {};
  const onClose = () => {};

  beforeEach(() => {
    setupAuthToken();
  });

  it('renders the modal with class name context', () => {
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={onClose} />);
    expect(screen.getByText('Choose Subclass')).toBeInTheDocument();
    expect(screen.getByText('Fighter specialization')).toBeInTheDocument();
  });

  it('shows loading state initially', () => {
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={onClose} />);
    expect(screen.getByText('Loading subclasses...')).toBeInTheDocument();
  });

  it('loads and displays subclasses', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/reference/classes/:classId/subclasses', () =>
        HttpResponse.json([
          { id: 'sc-champion', name: 'Champion', source: 'PHB', features: '[]' },
          { id: 'sc-battlemaster', name: 'Battle Master', source: 'PHB', features: '[{"name":"Combat Superiority","level":3,"description":"Gain superiority dice."}]' },
        ])
      )
    );
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={onClose} />);
    await waitFor(() => {
      expect(screen.getByText('Champion')).toBeInTheDocument();
      expect(screen.getByText('Battle Master')).toBeInTheDocument();
    });
  });

  it('enables confirm only after selection', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/reference/classes/:classId/subclasses', () =>
        HttpResponse.json([{ id: 'sc-champion', name: 'Champion', source: 'PHB', features: '[]' }])
      )
    );
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={onClose} />);
    await waitFor(() => screen.getByText('Champion'));

    const confirmBtn = screen.getByRole('button', { name: 'Confirm' });
    expect(confirmBtn).toBeDisabled();

    const user = userEvent.setup();
    await user.click(screen.getByText('Champion'));
    expect(confirmBtn).not.toBeDisabled();
  });

  it('shows submitting state in button text', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/reference/classes/:classId/subclasses', () =>
        HttpResponse.json([{ id: 'sc-champion', name: 'Champion', source: 'PHB', features: '[]' }])
      )
    );
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={onClose} />);
    await waitFor(() => screen.getByText('Champion'));
    const user = userEvent.setup();
    await user.click(screen.getByText('Champion'));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Selecting...' })).toBeDisabled();
    });
  });

  it('shows error when API fails', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/reference/classes/:classId/subclasses', () =>
        HttpResponse.json([{ id: 'sc-champion', name: 'Champion', source: 'PHB', features: '[]' }])
      ),
      http.post('/api/characters/:id/apply-choices', () =>
        HttpResponse.json({ error: 'Already has a subclass' }, { status: 409 })
      )
    );
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={onClose} />);
    await waitFor(() => screen.getByText('Champion'));
    const user = userEvent.setup();
    await user.click(screen.getByText('Champion'));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => {
      expect(screen.getByText('Already has a subclass')).toBeInTheDocument();
    });
  });

  it('has correct ARIA attributes', () => {
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={onClose} />);
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Choose Subclass');
  });

  it('calls onClose when Skip is clicked', async () => {
    let closed = false;
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={() => { closed = true; }} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Skip' }));
    expect(closed).toBe(true);
  });

  it('previews the features up to the character\'s level, which the server sends as JSON rather than a string', async () => {
    server.use(
      http.get('/api/reference/classes/:classId/subclasses', () =>
        HttpResponse.json([{
          id: 'sc-battlemaster', name: 'Battle Master', source: 'PHB',
          features: [
            { name: 'Combat Superiority', level: 3, description: 'Gain superiority dice.' },
            { name: 'Relentless', level: 15, description: 'Regain a superiority die.' },
          ],
        }])),
    );
    render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={onComplete} onClose={onClose} />);
    const user = userEvent.setup();

    await user.click(await screen.findByText('Battle Master'));

    expect(screen.getByText('Features at current level')).toBeInTheDocument();
    expect(screen.getByText('Combat Superiority')).toBeInTheDocument();
    expect(screen.queryByText('Relentless')).not.toBeInTheDocument();
  });

  it('applies the subclass and caches the character the server returns', async () => {
    const updated = { ...makeMockCharacter('char-001', 'Gimli', 'Fighter', 3), subclass: 'Champion' };
    let applied: unknown;
    server.use(
      http.get('/api/reference/classes/:classId/subclasses', () =>
        HttpResponse.json([{ id: 'sc-champion', name: 'Champion', source: 'PHB', features: null }])),
      http.post('/api/characters/:id/apply-choices', async ({ request }) => {
        applied = await request.json();
        return HttpResponse.json(updated);
      }),
    );
    const completed = vi.fn();
    const { queryClient } = render(<SubclassModal character={character} classId="cls-fighter" className="Fighter" onComplete={completed} onClose={onClose} />);
    const user = userEvent.setup();

    await user.click(await screen.findByText('Champion'));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(completed).toHaveBeenCalledWith(updated));
    expect(applied).toEqual({ subclassId: 'sc-champion', classId: 'cls-fighter' });
    expect(queryClient.getQueryData(queryKeys.characters.detail('char-001'))).toEqual(updated);
  });
});
