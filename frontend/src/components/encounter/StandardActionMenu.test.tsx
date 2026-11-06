import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ComponentProps } from 'react';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { EncounterParticipant } from '../../types/encounter';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import StandardActionMenu from './StandardActionMenu';

const FRESH = makeMockParticipant('x', 'x', 'PLAYER').turnEconomy!;

const vex = (overrides: Partial<EncounterParticipant> = {}) =>
  makeMockParticipant('part-vex', 'Vex', 'PLAYER', { isCurrentTurn: true, controlledByUserId: 'user-001', characterId: 'char-vex', ...overrides });
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER');
const ally = makeMockParticipant('part-ally', 'Gimli', 'PLAYER', { characterId: 'char-gimli' });

/** Answers the character's sheet with these features. */
function characterFeatures(...names: string[]) {
  server.use(http.get('/api/characters/:id', ({ params }) => HttpResponse.json({
    ...makeMockCharacter(params.id as string, 'Vex', 'Rogue', 3),
    features: names.map(name => ({ name, description: '', source: 'Class' })),
  })));
}

/** Records each standard action request, answering with the encounter, or with the response given. */
function standardActions(answer?: () => Response) {
  const bodies: unknown[] = [];
  server.use(http.post('/api/encounters/:id/combat/standard-action', async ({ request }) => {
    bodies.push(await request.json());
    return answer ? answer() : HttpResponse.json(makeMockEncounter());
  }));
  return bodies;
}

async function renderMenu(props: Partial<ComponentProps<typeof StandardActionMenu>> = {}) {
  const onClose = vi.fn();
  const participant = props.participant ?? vex();
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <StandardActionMenu participant={participant} participants={[participant, goblin, ally]} onClose={onClose} {...props} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return { onClose, user: userEvent.setup(), menu: screen.getByRole('dialog', { name: /Actions/ }) };
}

describe('StandardActionMenu', () => {
  beforeEach(() => {
    setupAuthToken();
    characterFeatures('Action Surge');
  });

  it('lists the eight standard actions and takes one as an action, leaving the cost out (D174)', async () => {
    const bodies = standardActions();
    const { menu, user, onClose } = await renderMenu();

    const list = within(menu).getByRole('list', { name: 'Standard actions' });
    expect(within(list).getAllByRole('button')).toHaveLength(8);
    for (const name of ['Dash', 'Disengage', 'Dodge', 'Help', 'Hide', 'Ready', 'Search', 'Use an Object']) {
      expect(within(list).getByRole('button', { name: new RegExp(`^${name}`) })).toBeEnabled();
    }
    await user.click(within(list).getByRole('button', { name: /^Dodge/ }));
    await user.click(within(menu).getByRole('button', { name: 'Take Dodge' }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(bodies).toEqual([{ participantId: 'part-vex', action: 'DODGE' }]);
  });

  it('Help names the creature it helps, and Ready needs its trigger', async () => {
    const bodies = standardActions();
    const { menu, user, onClose } = await renderMenu();

    await user.click(within(menu).getByRole('button', { name: /^Help/ }));
    expect(within(menu).getByRole('button', { name: 'Take Help' })).toBeDisabled();
    expect(within(menu).queryByRole('option', { name: 'Vex' })).not.toBeInTheDocument();
    await user.selectOptions(within(menu).getByLabelText('Creature to help'), 'Gimli');
    await user.click(within(menu).getByRole('button', { name: 'Take Help' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(bodies[0]).toEqual({ participantId: 'part-vex', action: 'HELP', targetId: 'part-ally' });

    await user.click(within(menu).getByRole('button', { name: /^Ready/ }));
    expect(within(menu).getByText('Ready needs its trigger')).toBeInTheDocument();
    expect(within(menu).getByRole('button', { name: 'Take Ready' })).toBeDisabled();
    await user.type(within(menu).getByLabelText('Trigger'), 'When the goblin opens the door');
    await user.click(within(menu).getByRole('button', { name: 'Take Ready' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).toEqual({ participantId: 'part-vex', action: 'READY', note: 'When the goblin opens the door' });
  });

  it('offers a player Dash, Disengage and Hide as a bonus action with Cunning Action, and sends that cost', async () => {
    characterFeatures('Sneak Attack', 'Cunning Action');
    const bodies = standardActions();
    const { menu, user } = await renderMenu();

    expect(await within(menu).findAllByText('bonus action with Cunning Action')).toHaveLength(3);
    await user.click(within(menu).getByRole('button', { name: /^Dash/ }));
    await user.selectOptions(within(menu).getByLabelText('Cost'), 'BONUS_ACTION');
    expect(within(menu).getByRole('option', { name: 'Bonus action (Cunning Action)' })).toBeInTheDocument();
    await user.click(within(menu).getByRole('button', { name: 'Take Dash' }));

    await waitFor(() => expect(bodies).toEqual([{ participantId: 'part-vex', action: 'DASH', cost: 'BONUS_ACTION' }]));
  });

  it('offers a player no bonus action without Cunning Action or Nimble Escape', async () => {
    const { menu, user } = await renderMenu();

    await user.click(within(menu).getByRole('button', { name: /^Dash/ }));
    expect(within(menu).queryByLabelText('Cost')).not.toBeInTheDocument();
    expect(within(menu).queryByText(/bonus action with/)).not.toBeInTheDocument();
  });

  it('greys a player\'s actions with the reason once the action is spent, leaving Cunning Action\'s bonus action', async () => {
    characterFeatures('Cunning Action');
    const bodies = standardActions();
    const { menu, user } = await renderMenu({ participant: vex({ turnEconomy: { ...FRESH, actionsUsed: 1 } }) });

    await within(menu).findAllByText('bonus action with Cunning Action');
    expect(within(menu).getByRole('button', { name: /^Dodge/ })).toBeDisabled();
    expect(within(menu).getAllByText('No action left this turn').length).toBeGreaterThan(0);
    const dash = within(menu).getByRole('button', { name: /^Dash/ });
    expect(dash).toBeEnabled();
    await user.click(dash);
    expect(within(menu).getByLabelText('Cost')).toHaveValue('BONUS_ACTION');
    await user.click(within(menu).getByRole('button', { name: 'Take Dash' }));

    await waitFor(() => expect(bodies).toEqual([{ participantId: 'part-vex', action: 'DASH', cost: 'BONUS_ACTION' }]));
  });

  it('only warns the DM, who may take anything, a bonus action included, without a feature granting it', async () => {
    const bodies = standardActions();
    const { menu, user } = await renderMenu({ asDm: true, participant: vex({ isCurrentTurn: false }) });

    expect(within(menu).getAllByText("Over the action economy: Not this creature's turn: only a reaction can be taken").length)
      .toBeGreaterThan(0);
    const hide = within(menu).getByRole('button', { name: /^Hide/ });
    expect(hide).toBeEnabled();
    await user.click(hide);
    await user.selectOptions(within(menu).getByLabelText('Cost'), 'BONUS_ACTION');
    expect(within(within(menu).getByRole('form', { name: 'Take Hide' })).getByText(/^Over the action economy/)).toBeInTheDocument();
    await user.click(within(menu).getByRole('button', { name: 'Take Hide' }));

    await waitFor(() => expect(bodies).toEqual([{ participantId: 'part-vex', action: 'HIDE', cost: 'BONUS_ACTION' }]));
  });

  it('shows the server\'s refusal and keeps the menu open', async () => {
    standardActions(() => HttpResponse.json({ error: 'It has no action left this turn' }, { status: 409 }));
    const { menu, user, onClose } = await renderMenu();

    await user.click(within(menu).getByRole('button', { name: /^Search/ }));
    await user.click(within(menu).getByRole('button', { name: 'Take Search' }));

    expect(await within(menu).findByRole('alert')).toHaveTextContent('It has no action left this turn');
    expect(onClose).not.toHaveBeenCalled();
  });

  describe('object interaction', () => {
    it('sends the first object interaction of the turn', async () => {
      let body: unknown;
      server.use(http.post('/api/encounters/:id/combat/object-interaction', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(makeMockEncounter());
      }));
      const { menu, user } = await renderMenu();

      await user.type(within(menu).getByLabelText(/Object interaction/), 'Draws a dagger');
      await user.click(within(menu).getByRole('button', { name: 'Interact' }));

      await waitFor(() => expect(body).toEqual({ participantId: 'part-vex', description: 'Draws a dagger' }));
      expect(await within(menu).findByRole('status')).toHaveTextContent('Logged: Draws a dagger');
    });

    it('tells a player a second needs the Use an Object action, and offers it', async () => {
      const { menu, user } = await renderMenu({ participant: vex({ turnEconomy: { ...FRESH, objectInteractionUsed: true } }) });

      expect(within(menu).getByText('The free object interaction is used this turn; another needs the Use an Object action'))
        .toBeInTheDocument();
      await user.type(within(menu).getByLabelText(/Object interaction/), 'Opens the chest');
      expect(within(menu).getByRole('button', { name: 'Interact' })).toBeDisabled();
      await user.click(within(menu).getByRole('button', { name: 'Take the Use an Object action instead' }));

      expect(within(menu).getByRole('button', { name: /^Use an Object/ })).toHaveAttribute('aria-pressed', 'true');
      expect(within(menu).getByLabelText('Note (optional)')).toHaveValue('Opens the chest');
    });

    it('only warns the DM about a second, and shows the server\'s refusal', async () => {
      server.use(http.post('/api/encounters/:id/combat/object-interaction', () =>
        HttpResponse.json({ error: 'Refused' }, { status: 409 })));
      const { menu, user } = await renderMenu({ asDm: true, participant: vex({ turnEconomy: { ...FRESH, objectInteractionUsed: true } }) });

      expect(within(menu).getByText(/^Over the action economy: The free object interaction is used/)).toBeInTheDocument();
      await user.type(within(menu).getByLabelText(/Object interaction/), 'Opens the chest');
      await user.click(within(menu).getByRole('button', { name: 'Interact' }));

      expect(await within(menu).findByRole('alert')).toHaveTextContent('Refused');
    });
  });
});
