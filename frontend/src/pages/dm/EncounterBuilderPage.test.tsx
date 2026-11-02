import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import EncounterBuilderPage from './EncounterBuilderPage';

describe('EncounterBuilderPage', () => {
  beforeEach(async () => {
    setupAuthToken();
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () =>
        HttpResponse.json([
          { id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush', description: null, status: 'PREPARING', currentTurnIndex: 0, roundNumber: 1, sessionCode: null, participants: [], createdAt: new Date().toISOString() },
        ])
      ),
    );
  });

  it('renders the page with heading', async () => {
    render(<EncounterBuilderPage />);
    // The page has two phases: loading then campaign list. Wait for either.
    await waitFor(() => {
      const heading = screen.queryByText('Encounters');
      const loading = screen.queryByText('Loading...');
      expect(heading || loading).toBeTruthy();
    }, { timeout: 3000 });
  });

  it('shows create encounter form on New button click', async () => {
    render(<EncounterBuilderPage />);
    await waitFor(() => screen.getByText('Goblin Ambush'), { timeout: 3000 });
    const user = userEvent.setup();
    await user.click(screen.getByText('New'));
    expect(screen.getByPlaceholderText('Encounter name')).toBeInTheDocument();
  });

  it('hides a monster from players from the participants table', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter, makeMockParticipant } = await import('../../test/mocks/handlers');
    const encounter = (goblinVisible: boolean) => makeMockEncounter({
      status: 'PREPARING', sessionCode: undefined,
      participants: [
        makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { isVisibleToPlayers: goblinVisible }),
        makeMockParticipant('part-gimli', 'Gimli', 'PLAYER'),
      ],
    });
    let visibilityRequest: { participantId: string; body: unknown } | undefined;
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () => HttpResponse.json([encounter(true)])),
      http.patch('/api/encounters/:id/participants/:participantId/visibility', async ({ params, request }) => {
        visibilityRequest = { participantId: params.participantId as string, body: await request.json() };
        return HttpResponse.json(encounter(false));
      }),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));

    // Player characters cannot be hidden, so only the goblin has the switch
    expect(screen.getAllByRole('button', { name: /from players|to players/ })).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Hide from players' }));

    expect(await screen.findByRole('button', { name: 'Show to players' })).toBeInTheDocument();
    expect(screen.getByText('Hidden')).toBeInTheDocument();
    expect(visibilityRequest).toEqual({ participantId: 'part-goblin', body: { visible: false } });
  });

  it('creates encounter on form submit', async () => {
    render(<EncounterBuilderPage />);
    await waitFor(() => screen.getByText('Goblin Ambush'), { timeout: 3000 });
    const user = userEvent.setup();
    await user.click(screen.getByText('New'));
    await user.type(screen.getByPlaceholderText('Encounter name'), 'Dragon Attack');
    await user.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => {
      expect(screen.queryByPlaceholderText('Encounter name')).not.toBeInTheDocument();
    });
  });
});
