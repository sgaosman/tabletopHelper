import { describe, it, expect, beforeEach, onTestFinished } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockResourcePool } from '../../test/mocks/handlers';
import CharacterSheetPage from './CharacterSheetPage';

function renderSheet(characterId = 'char-001') {
  window.history.pushState({}, '', `/player/characters/${characterId}`);
  return render(
    <Routes>
      <Route path="/player/characters/:characterId" element={<CharacterSheetPage />} />
    </Routes>,
  );
}

/** Records the method and path of every request from now until the test ends. */
function recordRequests() {
  const requests: string[] = [];
  const record = ({ request }: { request: Request }) => {
    requests.push(`${request.method} ${new URL(request.url).pathname}`);
  };
  server.events.on('request:start', record);
  onTestFinished(() => server.events.removeListener('request:start', record));
  return requests;
}

describe('CharacterSheetPage', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('shows the character a save returns without loading it again', async () => {
    let savedChanges: unknown;
    server.use(
      http.get('/api/characters/:id', ({ params }) =>
        HttpResponse.json({ ...makeMockCharacter(params.id as string, 'Gimli', 'Fighter', 5), hpCurrent: 12 })),
      http.put('/api/characters/:id', async ({ params, request }) => {
        savedChanges = await request.json();
        return HttpResponse.json(makeMockCharacter(params.id as string, 'Gimli', 'Fighter', 5));
      }),
    );
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('12/30')).toBeInTheDocument();
    const requests = recordRequests();

    await user.click(screen.getByTitle('Long Rest'));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Long Rest' }));

    expect(await screen.findByText('30/30')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Saved');
    expect(savedChanges).toMatchObject({ hpCurrent: 30, hpTemp: 0 });
    expect(requests).toEqual(['PUT /api/characters/char-001']);
  });

  it('shows the levelled character a level-up returns without loading it again', async () => {
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('Test Character')).toBeInTheDocument();
    const requests = recordRequests();

    await user.click(screen.getByTitle('Level Up'));
    const confirm = within(screen.getByRole('dialog', { name: 'Level Up' })).getByRole('button', { name: 'Level Up' });
    // Enabled once the classes have loaded, with the character's current class selected
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await screen.findByText('Level up to 6 complete!')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Gimli' })).toBeInTheDocument();
    expect(requests).toEqual([
      'GET /api/characters/char-001/eligible-classes',
      'POST /api/characters/char-001/level-up',
    ]);
  });

  it('names the hit dice steppers in the short rest dialog, which Escape closes', async () => {
    server.use(
      http.get('/api/characters/:id', ({ params }) =>
        HttpResponse.json({ ...makeMockCharacter(params.id as string, 'Gimli', 'Fighter', 5), hpCurrent: 12 })),
    );
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('12/30')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Short Rest' }));
    const dialog = screen.getByRole('dialog', { name: 'Short Rest' });
    await user.click(within(dialog).getByRole('button', { name: 'Spend one more Fighter hit die' }));
    expect(within(dialog).getByRole('button', { name: 'Spend one fewer Fighter hit die' })).toBeEnabled();
    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Short Rest' })).toHaveFocus();
  });

  it('rests send the hit dice, slots and pools as JSON, worked out as before', async () => {
    const saved: unknown[] = [];
    const warlock = {
      ...makeMockCharacter('char-001', 'Gimli', 'Warlock', 5), hpCurrent: 12, constitution: 14,
      hitDice: { Fighter: { total: 3, remaining: 1, dieSize: 10 }, Warlock: { total: 2, remaining: 2, dieSize: 8 } },
      spellSlots: { '1': { max: 4, used: 3 }, pact_1: { max: 1, used: 1 } },
      resourcePools: [
        makeMockResourcePool('class:fighter-second-wind', 'Second Wind', { maxUses: 1, currentUses: 0, resetOn: 'shortRest' }),
        makeMockResourcePool('class:warlock-hexblade', 'Hexblade Curse', { maxUses: 1, currentUses: 0, resetOn: 'longRest' }),
      ],
    };
    server.use(
      http.get('/api/characters/:id', () => HttpResponse.json(warlock)),
      http.put('/api/characters/:id', async ({ request }) => {
        saved.push(await request.json());
        return HttpResponse.json(warlock);
      }),
    );
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('12/30')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Short Rest' }));
    const shortRest = screen.getByRole('dialog', { name: 'Short Rest' });
    await user.click(within(shortRest).getByRole('button', { name: 'Spend one more Fighter hit die' }));
    await user.click(within(shortRest).getByRole('button', { name: 'Short Rest' }));
    await waitFor(() => expect(saved).toHaveLength(1));

    // A d10 heals 6 plus the CON modifier; only pact slots and short-rest pools come back
    expect(saved[0]).toEqual({
      hpCurrent: 20,
      hitDice: { Fighter: { total: 3, remaining: 0, dieSize: 10 }, Warlock: { total: 2, remaining: 2, dieSize: 8 } },
      spellSlots: { '1': { max: 4, used: 3 }, pact_1: { max: 1, used: 0 } },
      resourcePools: [
        expect.objectContaining({ poolId: 'class:fighter-second-wind', currentUses: 1 }),
        expect.objectContaining({ poolId: 'class:warlock-hexblade', currentUses: 0 }),
      ],
    });

    await user.click(screen.getByRole('button', { name: 'Long Rest' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Long Rest' })).getByRole('button', { name: 'Long Rest' }));
    await waitFor(() => expect(saved).toHaveLength(2));

    // Each class regains half its own Hit Dice, at least one
    expect(saved[1]).toEqual({
      hpCurrent: 30,
      hpTemp: 0,
      hitDice: { Fighter: { total: 3, remaining: 2, dieSize: 10 }, Warlock: { total: 2, remaining: 2, dieSize: 8 } },
      spellSlots: { '1': { max: 4, used: 0 }, pact_1: { max: 1, used: 0 } },
      resourcePools: [
        expect.objectContaining({ poolId: 'class:fighter-second-wind', currentUses: 1 }),
        expect.objectContaining({ poolId: 'class:warlock-hexblade', currentUses: 1 }),
      ],
    });
  });

  it('spending a spell slot sends the slots as JSON', async () => {
    let saved: unknown;
    const wizard = {
      ...makeMockCharacter('char-001', 'Elminster', 'Wizard', 3), spellcastingAbility: 'INT',
      spellSlots: { '1': { max: 4, used: 1 }, '2': { max: 2, used: 0 } },
    };
    server.use(
      http.get('/api/characters/:id', () => HttpResponse.json(wizard)),
      http.put('/api/characters/:id', async ({ request }) => {
        saved = await request.json();
        return HttpResponse.json(wizard);
      }),
    );
    renderSheet();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: /Spells/ }));

    expect(screen.getByText('3/4')).toBeInTheDocument();
    await user.click(screen.getByText('3/4'));

    await waitFor(() => expect(saved).toEqual({ spellSlots: { '1': { max: 4, used: 2 }, '2': { max: 2, used: 0 } } }));
  });

  it('shows the server\'s message when the character cannot be loaded', async () => {
    server.use(
      http.get('/api/characters/:id', () =>
        HttpResponse.json({ error: 'You do not have access to this character' }, { status: 403 })),
    );
    renderSheet();

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have access to this character');
    expect(screen.queryByText('Loading character...')).not.toBeInTheDocument();
  });
});
