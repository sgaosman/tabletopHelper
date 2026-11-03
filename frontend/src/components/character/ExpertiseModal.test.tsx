import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import { queryKeys } from '../../queries/keys';
import ExpertiseModal from './ExpertiseModal';

describe('ExpertiseModal', () => {
  const character = makeMockCharacter('char-001', 'Rogue', 'Rogue', 6);
  // The Expertise a Rogue 6 owes (D159)
  const expertise = (count: number) => makeMockPendingChoice({
    id: 'choice-expertise', type: 'EXPERTISE', characterLevel: 6, className: 'Rogue', classLevel: 6, count,
  });

  beforeEach(() => {
    setupAuthToken();
  });

  it('renders with the correct count and progress indicator', () => {
    render(<ExpertiseModal character={character} choice={expertise(2)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByText('Choose Expertise')).toBeInTheDocument();
    expect(screen.getByText(/Rogue 6: select 2 skills.*0\/2/)).toBeInTheDocument();
  });

  it('shows only proficient skills minus existing expertise', () => {
    // char has 'athletics' and 'perception' as proficiencies, no expertises
    render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByText('athletics')).toBeInTheDocument();
    expect(screen.getByText('perception')).toBeInTheDocument();
  });

  it('filters out skills already marked as expertise', () => {
    const expertChar = {
      ...character,
      skillExpertises: ['athletics'],
    };
    render(<ExpertiseModal character={expertChar} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.queryByText('athletics')).not.toBeInTheDocument();
    expect(screen.getByText('perception')).toBeInTheDocument();
  });

  it('shows empty state when no eligible skills exist', () => {
    const fullExpertChar = {
      ...character,
      skillExpertises: ['athletics', 'perception'],
    };
    render(<ExpertiseModal character={fullExpertChar} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByText('No eligible skills for expertise.')).toBeInTheDocument();
  });

  it('asks for every skill left when there are fewer than the choice counts', () => {
    const oneLeft = { ...character, skillExpertises: ['athletics'] };
    render(<ExpertiseModal character={oneLeft} choice={expertise(2)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByText(/select 1 skill .*0\/1/)).toBeInTheDocument();
  });

  it('enforces max selection count', async () => {
    render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();

    await user.click(screen.getByText('athletics'));
    expect(screen.getByText(/select 1 skill.*1\/1/)).toBeInTheDocument();

    // Clicking another should not add it (count = 1 limit)
    await user.click(screen.getByText('perception'));
    expect(screen.getByText(/select 1 skill.*1\/1/)).toBeInTheDocument();
  });

  it('disables confirm when wrong number selected', () => {
    render(<ExpertiseModal character={character} choice={expertise(2)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('button', { name: 'Confirm Expertise' })).toBeDisabled();
  });

  it('enables confirm when correct count selected', async () => {
    render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByText('athletics'));
    expect(screen.getByRole('button', { name: 'Confirm Expertise' })).not.toBeDisabled();
  });

  it('makes the choice with the skills selected and caches the character the server returns', async () => {
    const updated = { ...character, skillExpertises: ['perception'] };
    let applied: unknown;
    server.use(
      http.post('/api/characters/:id/apply-choices', async ({ request }) => {
        applied = await request.json();
        return HttpResponse.json(updated);
      }),
    );
    const onComplete = vi.fn();
    const { queryClient } = render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={onComplete}
      onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByText('perception'));
    await user.click(screen.getByRole('button', { name: 'Confirm Expertise' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(updated));
    expect(applied).toEqual({ choiceId: 'choice-expertise', expertiseSkills: ['perception'] });
    expect(queryClient.getQueryData(queryKeys.characters.detail('char-001'))).toEqual(updated);
  });

  it("shows the server's message when the choice is refused", async () => {
    server.use(
      http.post('/api/characters/:id/apply-choices', () =>
        HttpResponse.json({ error: 'Rogue already has Expertise in perception' }, { status: 400 })),
    );
    render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByText('perception'));
    await user.click(screen.getByRole('button', { name: 'Confirm Expertise' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Rogue already has Expertise in perception');
  });

  it('leaves the choice pending when Skip is clicked', async () => {
    const onSkip = vi.fn();
    const onClose = vi.fn();
    render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={() => {}} onSkip={onSkip} onClose={onClose} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Skip' }));
    expect(onSkip).toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('has correct ARIA attributes', () => {
    render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Choose Expertise');
  });

  it('marks the chosen skill as pressed', async () => {
    render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'athletics' }));
    expect(screen.getByRole('button', { name: 'athletics' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'perception' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('closes on Escape', async () => {
    let closed = false;
    render(<ExpertiseModal character={character} choice={expertise(1)} onComplete={() => {}} onSkip={() => {}} onClose={() => { closed = true; }} />);
    await userEvent.setup().keyboard('{Escape}');
    expect(closed).toBe(true);
  });
});
