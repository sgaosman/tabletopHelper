import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { AxiosError, AxiosHeaders } from 'axios';
import { render as renderWithProviders, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockFormOptions, makeMockParticipant } from '../../test/mocks/handlers';
import { EncounterProvider } from '../../context/EncounterContext';
import type { EncounterParticipant } from '../../types/encounter';
import ChangeFormControl from './ChangeFormControl';

const wolfForm = (spell: string) => ({
  monsterId: 'monster-wolf', beastName: 'Wolf', spell, armourClass: 13, hpMax: 11, strength: 12, dexterity: 15,
  constitution: 12, druidLevel: 0, normalHpCurrent: 30, normalHpMax: 30, normalArmourClass: 14,
}) satisfies NonNullable<EncounterParticipant['form']>;

const druid = makeMockParticipant('part-druid', 'Radagast', 'PLAYER', { concentrationSpell: 'Animal Shapes' });
const ally = makeMockParticipant('part-002', 'Gimli', 'PLAYER', { form: wolfForm('Animal Shapes'), formName: 'Wolf' });

function renderControl(caster: EncounterParticipant, participants: EncounterParticipant[],
  onChange = vi.fn().mockResolvedValue(undefined)) {
  renderWithProviders(
    <EncounterProvider encounterId="enc-001">
      <ChangeFormControl caster={caster} participants={participants} onChange={onChange} />
    </EncounterProvider>,
  );
  return onChange;
}

describe('ChangeFormControl', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('gives a creature Animal Shapes transformed a new beast form from its options (D192)', async () => {
    const asked: (string | null)[][] = [];
    server.use(http.get('/api/encounters/:id/combat/form-options', ({ request }) => {
      const params = new URL(request.url).searchParams;
      asked.push([params.get('participantId'), params.get('spell'), params.get('targetId')]);
      return HttpResponse.json(makeMockFormOptions({ spell: 'Animal Shapes', maxChallengeRating: '4', largestSize: 'Large' }));
    }));
    const user = userEvent.setup();
    const onChange = renderControl(druid, [druid, ally]);

    await user.click(await screen.findByRole('button', { name: /New form/ }));
    expect(await screen.findByText('A beast of CR 4 or lower, Large or smaller')).toBeInTheDocument();
    const list = screen.getByRole('list', { name: 'New form for Gimli' });
    expect(screen.getByRole('button', { name: 'Choose a creature' })).toBeDisabled();
    await user.click(within(list).getByRole('button', { name: /Brown Bear/ }));
    await user.click(screen.getByRole('button', { name: 'Change Gimli into Brown Bear' }));

    await waitFor(() => expect(onChange).toHaveBeenCalledWith('part-002', 'monster-brown-bear'));
    expect(asked).toEqual([['part-druid', 'Animal Shapes', 'part-002']]);
    // Done: the picker closes
    await waitFor(() => expect(screen.queryByRole('list')).not.toBeInTheDocument());
  });

  it('changes Shapechange\'s caster\'s own form, and shows a refusal', async () => {
    const shapechanger = makeMockParticipant('part-wiz', 'Elminster', 'PLAYER', {
      concentrationSpell: 'Shapechange', form: wolfForm('Shapechange'), formName: 'Wolf',
    });
    const user = userEvent.setup();
    const onChange = renderControl(shapechanger, [shapechanger],
      vi.fn().mockRejectedValue(new AxiosError('Conflict', '409', undefined, undefined, {
        status: 409, statusText: 'Conflict', headers: {}, config: { headers: new AxiosHeaders() },
        data: { error: 'You have already taken your action' },
      })));

    await user.click(await screen.findByRole('button', { name: /New form/ }));
    const list = await screen.findByRole('list', { name: 'New form for Elminster' });
    await user.click(within(list).getByRole('button', { name: /Giant Ape/ }));
    await user.click(screen.getByRole('button', { name: 'Change Elminster into Giant Ape' }));

    expect(onChange).toHaveBeenCalledWith('part-wiz', 'monster-giant-ape');
    expect(await screen.findByRole('alert')).toHaveTextContent('You have already taken your action');
  });

  it('shows nothing while the caster keeps up another spell, or has no creature to change', () => {
    renderControl(makeMockParticipant('part-x', 'Merlin', 'PLAYER', { concentrationSpell: 'Polymorph' }), [ally]);
    renderControl(druid, [druid]);
    expect(screen.queryByRole('button', { name: /New form/ })).not.toBeInTheDocument();
  });
});
