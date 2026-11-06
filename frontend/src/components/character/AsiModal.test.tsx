import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import type { FeatOptionResponse } from '../../types/character';
import type { Feat } from '../../types/reference';
import { queryKeys } from '../../queries/keys';
import AsiModal from './AsiModal';

describe('AsiModal', () => {
  const character = makeMockCharacter('char-001', 'Gimli', 'Fighter', 4);
  // The Fighter 4 Ability Score Improvement the character owes (D159)
  const asi = makeMockPendingChoice();

  beforeEach(() => {
    setupAuthToken();
  });

  it('renders with title and both mode buttons', () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByText('Ability Score Improvement')).toBeInTheDocument();
    expect(screen.getByText('Ability Scores')).toBeInTheDocument();
    expect(screen.getByText('Feat')).toBeInTheDocument();
  });

  it('shows all six abilities in ability score mode', () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByText('STR')).toBeInTheDocument();
    expect(screen.getByText('DEX')).toBeInTheDocument();
    expect(screen.getByText('CON')).toBeInTheDocument();
    expect(screen.getByText('INT')).toBeInTheDocument();
    expect(screen.getByText('WIS')).toBeInTheDocument();
    expect(screen.getByText('CHA')).toBeInTheDocument();
  });

  it('shows base ability scores from character', () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    // character has strength: 14, dex: 12
    const scores = screen.getAllByText(/^1[0-4]$|^10$/);
    expect(scores.length).toBeGreaterThanOrEqual(4); // multiple scores displayed
  });

  it('increments ability and decrements points remaining', async () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    expect(screen.getByText('2 points remaining')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByText('1 point remaining')).toBeInTheDocument();
  });

  it('disables + buttons after spending 2 points', async () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByText('0 points remaining')).toBeInTheDocument();
    const plusButtons = screen.getAllByRole('button', { name: /^Increase / });
    expect(plusButtons).toHaveLength(6);
    plusButtons.forEach(b => expect(b).toBeDisabled());
  });

  it('decrement restores a point', async () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByText('1 point remaining')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Decrease Strength' }));
    expect(screen.getByText('2 points remaining')).toBeInTheDocument();
  });

  it('stops each score at the maximum the server gives it', async () => {
    const nineteen = { ...character, strength: 19, baseAbilityScores: { ...character.baseAbilityScores, strength: 19 } };
    render(<AsiModal character={nineteen} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByRole('button', { name: 'Increase Strength' })).toBeDisabled();
    expect(screen.getByText('1 point remaining')).toBeInTheDocument();
    expect(screen.queryByText(/^max /)).not.toBeInTheDocument();
  });

  it("lets STR and CON go to 24 once the server gives Primal Champion's maximums", async () => {
    const champion = {
      ...character, strength: 20, baseAbilityScores: { ...character.baseAbilityScores, strength: 20 },
      abilityScoreMaximums: { ...character.abilityScoreMaximums, strength: 24, constitution: 24 },
    };
    render(<AsiModal character={champion} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    expect(screen.getAllByText('max 24')).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByText('0 points remaining')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled();
  });

  it("raises the score without the DM's adjustments, which may already pass the maximum (D158)", async () => {
    // A belt makes STR 21, but the score an improvement raises is 18
    const belted = { ...character, strength: 21, baseAbilityScores: { ...character.baseAbilityScores, strength: 18 } };
    render(<AsiModal character={belted} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByText("21 with the DM's adjustments")).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    expect(screen.getByText('0 points remaining')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Increase Strength' })).toBeDisabled();
  });

  it('disables Apply until exactly 2 points spent', () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled();
  });

  it('enables Apply when 2 points are allocated', async () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Dexterity' }));
    expect(screen.getByRole('button', { name: 'Apply' })).not.toBeDisabled();
  });

  it('calls API on Apply', async () => {
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
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
    const { queryClient } = render(<AsiModal character={character} choice={asi} onComplete={onComplete} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    const strengthPlus = screen.getByRole('button', { name: 'Increase Strength' });
    await user.click(strengthPlus);
    await user.click(strengthPlus);
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(updated));
    expect(applied).toEqual({ choiceId: 'choice-001', asi: { type: 'ability', increases: [{ ability: 'strength', bonus: 2 }] } });
    expect(queryClient.getQueryData(queryKeys.characters.detail('char-001'))).toEqual(updated);
  });

  it('shows the server\'s message when the choice is refused', async () => {
    server.use(
      http.post('/api/characters/:id/apply-choices', () =>
        HttpResponse.json({ error: 'No ability score improvement is pending' }, { status: 409 })),
    );
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Increase Strength' }));
    await user.click(screen.getByRole('button', { name: 'Increase Dexterity' }));
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No ability score improvement is pending');
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled();
  });

  it('shows the class level that owes the improvement, and Skip leaves it pending', async () => {
    const onSkip = vi.fn();
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={onSkip} onClose={() => {}} />);

    expect(screen.getByText('Fighter 4 (level 4)')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Skip' }));

    expect(onSkip).toHaveBeenCalled();
  });

  it("offers a feat in place of the improvement only when the server says the character meets its prerequisite (D159)", async () => {
    server.use(
      http.get('/api/reference/feats', () => HttpResponse.json([
        { id: 'feat-grappler', name: 'Grappler', source: 'PHB', prerequisite: [{ abilities: [{ ability: 'STR', minimum: 13 }] }] },
        { id: 'feat-alert', name: 'Alert', source: 'PHB' },
      ] satisfies Feat[])),
      http.get('/api/characters/:id/feat-options', () => HttpResponse.json([
        { featId: 'feat-grappler', name: 'Grappler', source: 'PHB', eligible: false, unmet: ['Strength 13 (you have 12)'],
          prerequisite: 'Strength 13', taken: false, repeatable: false },
        { featId: 'feat-alert', name: 'Alert', source: 'PHB', eligible: true, unmet: [], taken: true, repeatable: false },
      ] satisfies FeatOptionResponse[])),
    );
    render(<AsiModal character={character} choice={asi} onComplete={() => {}} onSkip={() => {}} onClose={() => {}} />);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Feat' }));

    expect(await screen.findByRole('button', { name: /Grappler/ })).toBeDisabled();
    expect(screen.getByText('Strength 13 (you have 12)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Alert/ })).toBeDisabled();
    expect(screen.getByText('Already taken')).toBeInTheDocument();
  });

  describe("a race's or background's feat (D159)", () => {
    const feats = ['Alert', 'Lucky', 'Tough'].map(name => ({
      id: `feat-${name.toLowerCase()}`, name, source: 'PHB', description: `${name} text`,
    } satisfies Feat));

    beforeEach(() => {
      server.use(http.get('/api/reference/feats', () => HttpResponse.json(feats)));
    });

    it('offers only a feat, and only those the background lists', async () => {
      const background = makeMockPendingChoice({
        id: 'choice-feat', type: 'FEAT', source: 'BACKGROUND', characterLevel: 1, classId: null, className: null,
        classLevel: null, options: ['Alert', 'Tough'],
      });
      render(<AsiModal character={{ ...character, backgroundName: 'Ruined' }} choice={background} onComplete={() => {}}
        onSkip={() => {}} onClose={() => {}} />);

      expect(await screen.findByText('Alert')).toBeInTheDocument();
      expect(screen.getByText('Tough')).toBeInTheDocument();
      expect(screen.queryByText('Lucky')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Ability Scores' })).not.toBeInTheDocument();
      expect(screen.getByRole('heading', { name: 'Feat' })).toBeInTheDocument();
      expect(screen.getByText('From your background (Ruined)')).toBeInTheDocument();
    });

    it("sends a race's feat as the choice's feat", async () => {
      let applied: unknown;
      server.use(
        http.post('/api/characters/:id/apply-choices', async ({ request }) => {
          applied = await request.json();
          return HttpResponse.json(character);
        }),
      );
      const race = makeMockPendingChoice({
        id: 'choice-race', type: 'FEAT', source: 'RACE', characterLevel: 1, classId: null, className: null,
        classLevel: null,
      });
      const onComplete = vi.fn();
      render(<AsiModal character={character} choice={race} onComplete={onComplete} onSkip={() => {}} onClose={() => {}} />);
      const user = userEvent.setup();

      await user.click(await screen.findByText('Lucky'));
      await user.click(screen.getByRole('button', { name: 'Apply' }));

      await waitFor(() => expect(onComplete).toHaveBeenCalledWith(character));
      expect(applied).toEqual({ choiceId: 'choice-race', feat: { type: 'feat', featId: 'feat-lucky' } });
    });
  });
});
