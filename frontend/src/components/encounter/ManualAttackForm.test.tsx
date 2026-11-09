import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ComponentProps } from 'react';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCondition, makeMockParticipant } from '../../test/mocks/handlers';
import { EncounterProvider } from '../../context/EncounterContext';
import ManualAttackForm from './ManualAttackForm';

const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER');

function renderForm(props: ComponentProps<typeof ManualAttackForm>) {
  return render(
    <EncounterProvider encounterId="enc-001" view="dm">
      <ManualAttackForm {...props} />
    </EncounterProvider>,
  );
}

describe('ManualAttackForm', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('sends each complete row in turn, within 5 feet unless switched off, and empties', async () => {
    const onRoll = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderForm({ target: goblin, onRoll });

    await user.type(screen.getByPlaceholderText('+5'), '4');
    await user.type(screen.getByPlaceholderText('1d8+3'), '1d6+2');
    await user.selectOptions(screen.getByRole('combobox'), 'fire');
    await user.click(screen.getByRole('button', { name: 'Adv' }));
    await user.click(screen.getByRole('button', { name: 'Clone this attack' }));
    await user.click(screen.getAllByRole('button', { name: 'Within 5 ft' })[1]);
    await user.click(screen.getAllByRole('button', { name: 'Crit' })[1]);
    await user.click(screen.getByRole('button', { name: /Add attack/ }));
    await user.click(screen.getByRole('button', { name: 'Roll 3 Attacks' }));

    expect(onRoll.mock.calls.map(([request]) => request)).toEqual([
      { targetId: 'part-goblin', attackBonus: 4, damageDice: '1d6+2', damageType: 'fire', advantage: true, forceCrit: undefined, within5Ft: true },
      { targetId: 'part-goblin', attackBonus: 4, damageDice: '1d6+2', damageType: 'fire', advantage: true, forceCrit: true, within5Ft: false },
    ]);
    expect(screen.getByRole('button', { name: 'Roll Attack' })).toBeInTheDocument();
    expect(screen.getByPlaceholderText('+5')).toHaveValue(null);
  });

  it('holds at most five rows and at least one', async () => {
    const user = userEvent.setup();
    renderForm({ target: goblin, onRoll: vi.fn() });

    expect(screen.queryByRole('button', { name: 'Remove this attack' })).not.toBeInTheDocument();
    for (let i = 0; i < 4; i++) await user.click(screen.getByRole('button', { name: /Add attack/ }));
    expect(screen.getAllByPlaceholderText('+5')).toHaveLength(5);
    expect(screen.queryByRole('button', { name: /Add attack/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clone this attack' })).not.toBeInTheDocument();

    await user.click(screen.getAllByRole('button', { name: 'Remove this attack' })[0]);
    expect(screen.getAllByPlaceholderText('+5')).toHaveLength(4);
  });

  it("shows what the target's conditions add by themselves, asking without an attacker, by the row's reach", async () => {
    const asked: string[] = [];
    server.use(http.get('/api/encounters/:id/combat/attack-roll-preview', ({ request }) => {
      const url = new URL(request.url);
      asked.push(url.search);
      const within = url.searchParams.get('withinFiveFeet') === 'true';
      return HttpResponse.json({
        rollMode: within ? 'ADVANTAGE' : 'NORMAL',
        sources: [{ mode: 'ADVANTAGE', reason: 'target unconscious' },
          within ? { mode: 'ADVANTAGE', reason: 'target prone within 5 ft' }
            : { mode: 'DISADVANTAGE', reason: 'target prone beyond 5 ft' }],
        hitIsCritical: within,
      });
    }));
    const user = userEvent.setup();
    const downed = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', {
      activeConditions: [makeMockCondition('unconscious')],
    });
    renderForm({ target: downed, onRoll: vi.fn() });

    expect(await screen.findByRole('note', { name: 'Automatic advantage and disadvantage' }))
      .toHaveTextContent('Advantage: target unconscious, target prone within 5 ft; a hit is a critical hit');
    expect(asked[0]).toBe('?targetId=part-goblin&withinFiveFeet=true');

    await user.click(screen.getByRole('button', { name: 'Within 5 ft' }));
    expect(await screen.findByText('Disadvantage: target prone beyond 5 ft')).toBeInTheDocument();
    expect(screen.getByRole('note', { name: 'Automatic advantage and disadvantage' }))
      .toHaveTextContent('Advantage: target unconscious; Disadvantage: target prone beyond 5 ft (they cancel)');
  });
});
