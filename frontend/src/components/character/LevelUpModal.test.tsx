import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter } from '../../test/mocks/handlers';
import { queryKeys } from '../../queries/keys';
import type { LevelUpResponse } from '../../types/character';
import LevelUpModal from './LevelUpModal';

describe('LevelUpModal', () => {
  const character = makeMockCharacter('char-001', 'Gimli', 'Fighter', 5);

  beforeEach(() => {
    setupAuthToken();
  });

  it('renders the modal with title', async () => {
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Level Up' })).toBeInTheDocument();
    });
  });

  it('shows loading state initially', () => {
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('Loading classes...')).toBeInTheDocument();
  });

  it('has cancel and confirm buttons', async () => {
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Level Up' })).toBeInTheDocument();
    });
  });

  it('has correct ARIA attributes', () => {
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Level Up');
    expect(screen.getByRole('dialog')).toHaveAccessibleDescription('Level 5 → Level 6');
  });

  it('calls onClose when Cancel is clicked', async () => {
    let closed = false;
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => { closed = true; }} />);
    await waitFor(() => screen.getByRole('button', { name: 'Cancel' }));
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(closed).toBe(true);
  });

  it('levels up in the current class unless another is picked, and caches the levelled character', async () => {
    const response = {
      character: makeMockCharacter('char-001', 'Gimli', 'Fighter', 6),
      pendingChoices: { asiAvailable: true, subclassRequired: false, expertiseAvailable: false, expertiseCount: 0, spellSelectionNeeded: false, newFeatures: [], maxSpellLevel: 0 },
    } satisfies LevelUpResponse;
    let levelUpRequest: unknown;
    server.use(
      http.post('/api/characters/:id/level-up', async ({ request }) => {
        levelUpRequest = await request.json();
        return HttpResponse.json(response);
      }),
    );
    const onComplete = vi.fn();
    const { queryClient } = render(<LevelUpModal character={character} onComplete={onComplete} onClose={() => {}} />);
    const user = userEvent.setup();
    const confirm = screen.getByRole('button', { name: 'Level Up' });
    await waitFor(() => expect(confirm).toBeEnabled());

    await user.click(confirm);

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(response, 'cls-fighter', 'Fighter'));
    expect(levelUpRequest).toEqual({ classId: 'cls-fighter' });
    expect(queryClient.getQueryData(queryKeys.characters.detail('char-001'))).toEqual(response.character);
  });

  it('shows the server\'s message when the level-up is refused', async () => {
    server.use(
      http.post('/api/characters/:id/level-up', () =>
        HttpResponse.json({ error: 'You do not meet the multiclass prerequisites' }, { status: 400 })),
    );
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Wizard'));
    await user.click(screen.getByRole('button', { name: 'Level Up' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not meet the multiclass prerequisites');
    expect(screen.getByRole('button', { name: 'Level Up' })).toBeEnabled();
  });
});
