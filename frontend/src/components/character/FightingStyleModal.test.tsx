import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import { queryKeys } from '../../queries/keys';
import type { OptionalFeature } from '../../types/reference';
import FightingStyleModal from './FightingStyleModal';

describe('FightingStyleModal', () => {
  const character = makeMockCharacter('char-001', 'Gimli', 'Fighter', 1);
  // The Fighting Style a Fighter 1 owes, from the fighter's list (D159)
  const style = makeMockPendingChoice({
    id: 'choice-style', type: 'FIGHTING_STYLE', characterLevel: 1, classLevel: 1,
    options: ['Archery', 'Defense', 'Dueling'],
  });

  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/reference/optional-features', () => HttpResponse.json([
      { id: 'of-archery', name: 'Archery', featureType: 'FightingStyle', description: '+2 to ranged attack rolls.' },
      { id: 'of-defense', name: 'Defense', featureType: 'FightingStyle', description: '+1 AC while wearing armour.' },
    ] satisfies OptionalFeature[])));
  });

  it("lists the class's styles with their text, and confirms only once one is chosen", async () => {
    render(<FightingStyleModal character={character} choice={style} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Choose a Fighting Style' })).toBeInTheDocument();
    expect(screen.getByText('Fighter 1 (level 1)')).toBeInTheDocument();
    expect(await screen.findByText('+2 to ranged attack rolls.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Dueling/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: /Archery/ }));

    expect(screen.getByRole('button', { name: /Archery/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeEnabled();
  });

  it('makes the choice with the style and caches the character the server returns', async () => {
    const updated = { ...character, pendingChoices: [] };
    let applied: unknown;
    server.use(http.post('/api/characters/:id/apply-choices', async ({ request }) => {
      applied = await request.json();
      return HttpResponse.json(updated);
    }));
    const onComplete = vi.fn();
    const { queryClient } = render(<FightingStyleModal character={character} choice={style} onComplete={onComplete}
      onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /Defense/ }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(updated));
    expect(applied).toEqual({ choiceId: 'choice-style', fightingStyle: 'Defense' });
    expect(queryClient.getQueryData(queryKeys.characters.detail('char-001'))).toEqual(updated);
  });

  it("won't offer a style the character already has, as the Champion's second style can't repeat the first", () => {
    const champion = {
      ...character,
      features: [{ name: 'Fighting Style: Archery', description: '+2 to ranged attack rolls.', source: 'Fighter' }],
    };
    render(<FightingStyleModal character={champion} choice={style} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);

    expect(screen.getByRole('button', { name: /Archery/ })).toBeDisabled();
    expect(screen.getByText('Already taken')).toBeInTheDocument();
  });

  it('leaves the choice pending when Skip is clicked', async () => {
    const onSkip = vi.fn();
    render(<FightingStyleModal character={character} choice={style} onComplete={() => {}} onSkip={onSkip} onClose={() => {}} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Skip' }));

    expect(onSkip).toHaveBeenCalled();
  });
});
