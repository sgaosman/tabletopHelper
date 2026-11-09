import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCondition, makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { EncounterParticipant } from '../../types/encounter';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import InspirationControls from './InspirationControls';

function holder(onFailedSave: boolean | null): EncounterParticipant {
  return makeMockParticipant('part-vex', 'Vex', 'PLAYER', {
    controlledByUserId: 'user-001',
    activeConditions: onFailedSave === null ? [] : [makeMockCondition('inspired', {
      duration: 100, heldDie: { sides: 8, onFailedSave },
    })],
  });
}

async function renderControls(participant: EncounterParticipant) {
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <InspirationControls participant={participant} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return userEvent.setup();
}

function inspirationRequests(answer?: () => Response) {
  const bodies: unknown[] = [];
  server.use(http.post('/api/encounters/:id/combat/bardic-inspiration', async ({ request }) => {
    bodies.push(await request.json());
    return answer ? answer() : HttpResponse.json(makeMockEncounter());
  }));
  return bodies;
}

describe('InspirationControls (PHB p.53, M17)', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('shows nothing for a creature without a die', async () => {
    await renderControls(holder(null));
    expect(screen.queryByRole('region', { name: "Vex's Bardic Inspiration" })).not.toBeInTheDocument();
  });

  it('keeps the die for the next failed save, and rolls it now for a check', async () => {
    const bodies = inspirationRequests();
    const user = await renderControls(holder(false));

    expect(screen.getByText(/Bardic Inspiration d8: kept for an attack or a roll by hand/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Use on next failed save' }));
    await waitFor(() => expect(bodies[0]).toEqual({ participantId: 'part-vex', use: 'ON_FAILED_SAVE' }));
    await user.click(screen.getByRole('button', { name: 'Roll now (check or save by hand)' }));
    await waitFor(() => expect(bodies[1]).toEqual({ participantId: 'part-vex', use: 'ROLL' }));
  });

  it('takes back the failed-save choice, and shows a refusal', async () => {
    const bodies = inspirationRequests(() => HttpResponse.json({ error: 'Vex holds no Bardic Inspiration die' },
      { status: 409 }));
    const user = await renderControls(holder(true));

    const toggle = screen.getByRole('button', { name: 'Use on next failed save' });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await user.click(toggle);

    await waitFor(() => expect(bodies[0]).toEqual({ participantId: 'part-vex', use: 'KEEP' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Vex holds no Bardic Inspiration die');
  });
});
