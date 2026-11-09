import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import type { OptionalFeature } from '../../types/reference';
import MetamagicModal from './MetamagicModal';

describe('MetamagicModal', () => {
  const character = makeMockCharacter('char-002', 'Sorin', 'Sorcerer', 3);
  // The two options Sorcerer 3 owes (PHB p.101-102)
  const metamagic = makeMockPendingChoice({
    id: 'choice-metamagic', type: 'METAMAGIC', characterLevel: 3, classLevel: 3, className: 'Sorcerer', count: 2,
    options: ['Careful Spell', 'Quickened Spell', 'Twinned Spell'],
  });

  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/reference/optional-features', () => HttpResponse.json([
      { id: 'of-quickened', name: 'Quickened Spell', featureType: 'Metamagic', description: 'Cast it as a bonus action.' },
    ] satisfies OptionalFeature[])));
  });

  it('asks for as many options as the choice counts, and sends them', async () => {
    let applied: unknown;
    server.use(http.post('/api/characters/:id/apply-choices', async ({ request }) => {
      applied = await request.json();
      return HttpResponse.json({ ...character, pendingChoices: [] });
    }));
    const onComplete = vi.fn();
    render(<MetamagicModal character={character} choice={metamagic} onComplete={onComplete} onSkip={() => {}}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Choose 2 Metamagic Options' })).toBeInTheDocument();
    expect(await screen.findByText('Cast it as a bonus action.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Quickened Spell/ }));
    expect(screen.getByRole('button', { name: 'Confirm (1/2)' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /Twinned Spell/ }));
    // A third can't be picked
    expect(screen.getByRole('button', { name: /Careful Spell/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Confirm (2/2)' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(applied).toEqual({ choiceId: 'choice-metamagic', metamagic: ['Quickened Spell', 'Twinned Spell'] });
  });

  it('marks an option the sorcerer knows as known, so a later choice takes one of the others', () => {
    const known = {
      ...character,
      features: [{ name: 'Metamagic: Twinned Spell', description: 'Twin it.', source: 'Sorcerer' }],
    };
    const tenth = { ...metamagic, count: 1, classLevel: 10 };
    render(<MetamagicModal character={known} choice={tenth} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);

    expect(screen.getByRole('dialog', { name: 'Choose a Metamagic Option' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Twinned Spell/ })).toBeDisabled();
    expect(screen.getByText('Already known')).toBeInTheDocument();
  });
});
