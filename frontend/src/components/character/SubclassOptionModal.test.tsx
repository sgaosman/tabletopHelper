import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import type { Subclass } from '../../types/reference';
import SubclassOptionModal from './SubclassOptionModal';

describe('SubclassOptionModal', () => {
  const character = {
    ...makeMockCharacter('char-001', 'Faelan', 'Druid', 2),
    multiclassEntries: [{ classId: 'cls-druid', className: 'Druid', subclassId: 'sc-land',
      subclassName: 'Circle of the Land', level: 2 }],
  };
  const choice = makeMockPendingChoice({
    id: 'choice-terrain', type: 'SUBCLASS_OPTION', classId: 'cls-druid', className: 'Druid', classLevel: 2,
    characterLevel: 2, options: ['Arctic', 'Underdark'],
  });

  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/reference/classes/:classId/subclasses', () => HttpResponse.json([{
      id: 'sc-land', name: 'Circle of the Land', source: 'PHB',
      characterClass: { id: 'cls-druid', name: 'Druid' },
      // The server sends the granted spells as JSON, as the class loader stores them (D231)
      grantedSpells: {
        options: [
          { name: 'Arctic', prepared: { 3: ['hold person', 'spike growth'], 5: ['sleet storm', 'slow'] } },
          { name: 'Underdark', prepared: { 3: ['spider climb', 'web'] } },
        ],
      },
    }] as unknown as Subclass[])));
  });

  it("lists the terrains with their circle spells by druid level, and confirms only once one is picked", async () => {
    render(<SubclassOptionModal character={character} choice={choice} onComplete={() => {}} onSkip={() => {}}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Choose a Terrain' })).toBeInTheDocument();
    expect(await screen.findByText('Druid 3: hold person, spike growth')).toBeInTheDocument();
    expect(screen.getByText('Druid 5: sleet storm, slow')).toBeInTheDocument();
    expect(screen.getByText('Druid 3: spider climb, web')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: /Underdark/ }));
    expect(screen.getByRole('button', { name: /Underdark/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeEnabled();
  });

  it('makes the choice with the terrain picked, and Skip leaves it', async () => {
    const updated = { ...character, pendingChoices: [] };
    let applied: unknown;
    server.use(http.post('/api/characters/:id/apply-choices', async ({ request }) => {
      applied = await request.json();
      return HttpResponse.json(updated);
    }));
    const onComplete = vi.fn();
    const onSkip = vi.fn();
    render(<SubclassOptionModal character={character} choice={choice} onComplete={onComplete} onSkip={onSkip}
      onClose={() => {}} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Skip' }));
    expect(onSkip).toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: /Arctic/ }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(updated));
    expect(applied).toEqual({ choiceId: 'choice-terrain', subclassOption: 'Arctic' });
  });

  it("offers a Genie warlock's kinds, each with the spells it alone adds, the genie spells shown once (TCE p.73, D239)", async () => {
    const warlock = {
      ...makeMockCharacter('char-002', 'Sahar', 'Warlock', 1),
      multiclassEntries: [{ classId: 'cls-warlock', className: 'Warlock', subclassId: 'sc-genie',
        subclassName: 'The Genie', level: 1 }],
    };
    const kind = makeMockPendingChoice({
      id: 'choice-kind', type: 'SUBCLASS_OPTION', classId: 'cls-warlock', className: 'Warlock', classLevel: 1,
      characterLevel: 1, options: ['Dao', 'Djinni'],
    });
    server.use(http.get('/api/reference/classes/:classId/subclasses', () => HttpResponse.json([{
      id: 'sc-genie', name: 'The Genie', source: 'TCE',
      characterClass: { id: 'cls-warlock', name: 'Warlock' },
      grantedSpells: {
        options: [
          { name: 'Dao', expanded: ['wish', 'detect evil and good', 'sanctuary', 'spike growth'] },
          { name: 'Djinni', expanded: ['wish', 'detect evil and good', 'thunderwave', 'gust of wind'] },
        ],
      },
    }] as unknown as Subclass[])));
    render(<SubclassOptionModal character={warlock} choice={kind} onComplete={() => {}} onSkip={() => {}}
      onClose={() => {}} />);

    expect(screen.getByRole('dialog', { name: 'Choose a Genie Kind' })).toBeInTheDocument();
    expect(await screen.findByText('Every choice adds to your spell list: wish, detect evil and good'))
      .toBeInTheDocument();
    expect(screen.getByText('Adds to your spell list: sanctuary, spike growth')).toBeInTheDocument();
    expect(screen.getByText('Adds to your spell list: thunderwave, gust of wind')).toBeInTheDocument();
  });
});
