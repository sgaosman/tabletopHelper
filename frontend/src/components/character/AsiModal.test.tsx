import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter } from '../../test/mocks/handlers';
import { queryKeys } from '../../queries/keys';
import AsiModal from './AsiModal';

describe('AsiModal', () => {
  const character = makeMockCharacter('char-001', 'Gimli', 'Fighter', 4);

  beforeEach(() => {
    setupAuthToken();
  });

  it('renders with title and both mode buttons', () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('Ability Score Improvement')).toBeInTheDocument();
    expect(screen.getByText('Ability Scores')).toBeInTheDocument();
    expect(screen.getByText('Feat')).toBeInTheDocument();
  });

  it('shows all six abilities in ability score mode', () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('STR')).toBeInTheDocument();
    expect(screen.getByText('DEX')).toBeInTheDocument();
    expect(screen.getByText('CON')).toBeInTheDocument();
    expect(screen.getByText('INT')).toBeInTheDocument();
    expect(screen.getByText('WIS')).toBeInTheDocument();
    expect(screen.getByText('CHA')).toBeInTheDocument();
  });

  it('shows base ability scores from character', () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    // character has strength: 14, dex: 12
    const scores = screen.getAllByText(/^1[0-4]$|^10$/);
    expect(scores.length).toBeGreaterThanOrEqual(4); // multiple scores displayed
  });

  it('increments ability and decrements points remaining', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    expect(screen.getByText('2 points remaining')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByText('1 point remaining')).toBeInTheDocument();
  });

  it('disables + buttons after spending 2 points', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByText('0 points remaining')).toBeInTheDocument();
    const plusButtons = screen.getAllByRole('button', { name: /^Increase / });
    expect(plusButtons).toHaveLength(6);
    plusButtons.forEach(b => expect(b).toBeDisabled());
  });

  it('decrement restores a point', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByText('1 point remaining')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Decrease Strength' }));
    expect(screen.getByText('2 points remaining')).toBeInTheDocument();
  });

  it('disables Apply until exactly 2 points spent', () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled();
  });

  it('enables Apply when 2 points are allocated', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Dexterity' }));
    expect(screen.getByRole('button', { name: 'Apply' })).not.toBeDisabled();
  });

  it('calls API on Apply', async () => {
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Dexterity' }));
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Applying...' })).toBeDisabled();
    });
  });

  it('applies the increases and caches the character the server returns', async () => {
    const updated = { ...makeMockCharacter('char-001', 'Gimli', 'Fighter', 4), strength: 16 };
    let applied: unknown;
    server.use(
      http.post('/api/characters/:id/apply-choices', async ({ request }) => {
        applied = await request.json();
        return HttpResponse.json(updated);
      }),
    );
    const onComplete = vi.fn();
    const { queryClient } = render(<AsiModal character={character} onComplete={onComplete} onClose={() => {}} />);
    const user = userEvent.setup();
    const strengthPlus = screen.getByRole('button', { name: 'Increase Strength' });
    await user.click(strengthPlus);
    await user.click(strengthPlus);
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(updated));
    expect(applied).toEqual({ asi: { type: 'ability', increases: [{ ability: 'strength', bonus: 2 }] } });
    expect(queryClient.getQueryData(queryKeys.characters.detail('char-001'))).toEqual(updated);
  });

  it('shows the server\'s message when the choice is refused', async () => {
    server.use(
      http.post('/api/characters/:id/apply-choices', () =>
        HttpResponse.json({ error: 'No ability score improvement is pending' }, { status: 409 })),
    );
    render(<AsiModal character={character} onComplete={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Dexterity' }));
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No ability score improvement is pending');
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled();
  });
});
