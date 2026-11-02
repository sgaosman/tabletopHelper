import { describe, it, expect, beforeEach, onTestFinished } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter } from '../../test/mocks/handlers';
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
