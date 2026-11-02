import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter } from '../../test/mocks/handlers';
import JoinEncounterPage from './JoinEncounterPage';

describe('JoinEncounterPage', () => {
  beforeEach(() => {
    setupAuthToken();
    window.history.pushState({}, '', '/player/encounter/join');
  });

  it('opens the session page of the encounter the code belongs to', async () => {
    let requestedCode: string | undefined;
    server.use(
      http.get('/api/encounters/join/:code', ({ params }) => {
        requestedCode = params.code as string;
        return HttpResponse.json(makeMockEncounter({ id: 'enc-join' }));
      }),
    );
    render(<JoinEncounterPage />);
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText('Session code'), 'xyz999aa');
    await user.click(screen.getByRole('button', { name: 'Join' }));

    await waitFor(() => expect(window.location.pathname).toBe('/player/encounter/enc-join/session'));
    expect(requestedCode).toBe('XYZ999AA');
  });

  it("shows the server's message beside the form when the code is wrong", async () => {
    server.use(
      http.get('/api/encounters/join/:code', () =>
        HttpResponse.json({ error: 'Invalid session code' }, { status: 404 })),
    );
    render(<JoinEncounterPage />);
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText('Session code'), 'WRONG');
    await user.click(screen.getByRole('button', { name: 'Join' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid session code');
    expect(screen.getByRole('button', { name: 'Join' })).toBeEnabled();
    expect(window.location.pathname).toBe('/player/encounter/join');
  });
});
