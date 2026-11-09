import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import ProficiencyChoiceModal from './ProficiencyChoiceModal';

describe('ProficiencyChoiceModal', () => {
  const character = {
    ...makeMockCharacter('char-004', 'Brannoc', 'Fighter', 3),
    skillProficiencies: ['Athletics', 'History'],
    languageProficiencies: ['Common'],
  };
  // A Cavalier's Bonus Proficiency: one of five skills, or a language (XGE p.30)
  const cavalier = makeMockPendingChoice({
    id: 'choice-cavalier', type: 'PROFICIENCY', characterLevel: 3, classLevel: 3, className: 'Fighter', count: 1,
    proficiencyOptions: { skills: ['Animal Handling', 'History', 'Insight'], languages: ['Common', 'Draconic'] },
  });

  beforeEach(() => {
    setupAuthToken();
  });

  it('groups the options by kind, marks those held, and sends the one chosen', async () => {
    let applied: unknown;
    server.use(http.post('/api/characters/:id/apply-choices', async ({ request }) => {
      applied = await request.json();
      return HttpResponse.json({ ...character, pendingChoices: [] });
    }));
    const onComplete = vi.fn();
    render(<ProficiencyChoiceModal character={character} choice={cavalier} onComplete={onComplete} onSkip={() => {}}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Choose a Proficiency' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Skills' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Languages' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /History/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Common/ })).toBeDisabled();
    expect(screen.getAllByText('Already proficient')).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: /Draconic/ }));
    // Only one can be chosen
    expect(screen.getByRole('button', { name: /Insight/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Confirm (1/1)' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(applied).toEqual({ choiceId: 'choice-cavalier', proficiencies: ['Draconic'] });
  });

  it("lets a Knowledge Domain's skill the cleric is proficient in gain Expertise (PHB p.59)", async () => {
    const knowledge = makeMockPendingChoice({
      id: 'choice-knowledge', type: 'PROFICIENCY', characterLevel: 1, classLevel: 1, className: 'Cleric', count: 2,
      proficiencyOptions: { skills: ['Arcana', 'History', 'Nature', 'Religion'] }, expertise: true,
    });
    render(<ProficiencyChoiceModal character={character} choice={knowledge} onComplete={() => {}} onSkip={() => {}}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Choose 2 Proficiencies' })).toBeInTheDocument();
    expect(screen.getByText(/proficiency bonus is doubled/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /History/ }));
    await user.click(screen.getByRole('button', { name: /Arcana/ }));
    expect(screen.getByRole('button', { name: 'Confirm (2/2)' })).toBeEnabled();
  });

  it('asks for none when every option is held, and Skip leaves the choice for later', async () => {
    const held = makeMockPendingChoice({
      type: 'PROFICIENCY', className: 'Fighter', classLevel: 3, characterLevel: 3,
      proficiencyOptions: { languages: ['Common'] },
    });
    const onSkip = vi.fn();
    render(<ProficiencyChoiceModal character={character} choice={held} onComplete={() => {}} onSkip={onSkip}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByText(/nothing to choose/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm (0/0)' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Skip' }));
    expect(onSkip).toHaveBeenCalled();
  });
});
