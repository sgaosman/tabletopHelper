import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import DraconicAncestryModal from './DraconicAncestryModal';

describe('DraconicAncestryModal', () => {
  const character = { ...makeMockCharacter('char-001', 'Kriv', 'Fighter', 1), race: 'Dragonborn' };

  beforeEach(() => {
    setupAuthToken();
  });

  it("lists the choice's dragons with their damage and breath, and confirms only once one is chosen", async () => {
    const choice = makeMockPendingChoice({
      id: 'choice-ancestry', type: 'DRACONIC_ANCESTRY', source: 'RACE', characterLevel: 1,
      className: null, classLevel: null, options: ['Brass', 'Gold', 'Red'],
    });
    render(<DraconicAncestryModal character={character} choice={choice} onComplete={() => {}} onSkip={() => {}}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Choose a Draconic Ancestry' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { pressed: false })).toHaveLength(3);
    expect(screen.getAllByText('fire damage and resistance; breath: 15 ft. cone (DEX save)', { exact: false }))
      .toHaveLength(2);
    expect(screen.queryByRole('button', { name: /White/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: /Red/ }));

    expect(screen.getByRole('button', { name: /Red/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeEnabled();
  });

  it('makes the choice with the dragon', async () => {
    const choice = makeMockPendingChoice({
      id: 'choice-ancestry', type: 'DRACONIC_ANCESTRY', source: 'RACE', characterLevel: 1,
      className: null, classLevel: null, options: null,
    });
    const updated = { ...character, pendingChoices: [] };
    let applied: unknown;
    server.use(http.post('/api/characters/:id/apply-choices', async ({ request }) => {
      applied = await request.json();
      return HttpResponse.json(updated);
    }));
    const onComplete = vi.fn();
    render(<DraconicAncestryModal character={character} choice={choice} onComplete={onComplete} onSkip={() => {}}
      onClose={() => {}} />);
    const user = userEvent.setup();

    // Without options, all ten dragons
    expect(screen.getByRole('button', { name: /White/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Silver/ }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(updated));
    expect(applied).toEqual({ choiceId: 'choice-ancestry', draconicAncestry: 'Silver' });
  });
});
