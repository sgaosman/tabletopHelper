import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockAdjustment, makeMockCharacter } from '../../test/mocks/handlers';
import type { FeatOptionResponse } from '../../types/character';
import type { Feat } from '../../types/reference';
import DmAdjustmentsModal from './DmAdjustmentsModal';

const ring = makeMockAdjustment({ id: 'adj-ring' });
const fire = makeMockAdjustment({
  id: 'adj-fire', kind: 'DEFENCE', target: 'fire', amount: null, defence: 'RESISTANCE', reason: 'Ring of Fire Resistance',
});
const curse = makeMockAdjustment({
  id: 'adj-curse', target: 'CON', amount: -2, reason: 'Curse of the hag', removedAt: '2026-11-03T11:00:00Z',
  removedBy: 'user-001',
});
const character = { ...makeMockCharacter('char-001', 'Aria', 'Fighter', 3), dmAdjustments: [ring, fire, curse] };

function renderDialog(onClose = () => {}) {
  return render(<DmAdjustmentsModal character={character} onClose={onClose} />);
}

/** Answers the adjustment commands with the character, keeping each request's body. */
function captureAdd() {
  const bodies: unknown[] = [];
  server.use(http.post('/api/characters/:id/adjustments', async ({ request }) => {
    bodies.push(await request.json());
    return HttpResponse.json(character);
  }));
  return bodies;
}

describe('DmAdjustmentsModal', () => {
  it('lists the adjustments that count with their reasons, and the removed ones only in the collapsed history', async () => {
    renderDialog();
    const user = userEvent.setup();

    const active = screen.getByRole('region', { name: 'Active adjustments' });
    expect(within(active).getByText('+1 AC')).toBeInTheDocument();
    expect(within(active).getByText('Ring of Protection')).toBeInTheDocument();
    expect(within(active).getByText('Resistance to fire')).toBeInTheDocument();
    expect(screen.queryByText('-2 CON')).not.toBeInTheDocument();

    const history = screen.getByRole('button', { name: 'History (1)' });
    expect(history).toHaveAttribute('aria-expanded', 'false');
    await user.click(history);

    expect(history).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('-2 CON')).toBeInTheDocument();
    expect(screen.getByText(/Curse of the hag/)).toBeInTheDocument();
  });

  it('adds a stat adjustment with a signed amount and a reason', async () => {
    const bodies = captureAdd();
    renderDialog();
    const user = userEvent.setup();

    const add = screen.getByRole('button', { name: 'Add Adjustment' });
    expect(add).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Stat'), 'Strength');
    await user.type(screen.getByLabelText('Amount'), '-2');
    expect(add).toBeDisabled();
    await user.type(screen.getByLabelText('Reason'), 'Cursed with weakness');
    await user.click(add);

    await waitFor(() => expect(bodies).toEqual([
      { kind: 'STAT', target: 'STR', amount: -2, reason: 'Cursed with weakness' },
    ]));
    // The form starts afresh for the next one
    await waitFor(() => expect(screen.getByLabelText('Reason')).toHaveValue(''));
  });

  it('refuses an amount of 0', async () => {
    renderDialog();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Amount'), '0');
    await user.type(screen.getByLabelText('Reason'), 'Nothing');

    expect(screen.getByText('A whole number other than 0')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add Adjustment' })).toBeDisabled();
  });

  it('adds a damage defence: the defence and the damage type', async () => {
    const bodies = captureAdd();
    renderDialog();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Defence' }));
    await user.selectOptions(screen.getByLabelText('Defence'), 'Vulnerability');
    await user.selectOptions(screen.getByLabelText('Damage type'), 'cold');
    await user.type(screen.getByLabelText('Reason'), 'Curse of the frost giant');
    await user.click(screen.getByRole('button', { name: 'Add Adjustment' }));

    await waitFor(() => expect(bodies).toEqual([
      { kind: 'DEFENCE', defence: 'VULNERABILITY', target: 'cold', reason: 'Curse of the frost giant' },
    ]));
  });

  it("grants a feat from the feat picker, even one whose prerequisites the character doesn't meet", async () => {
    const lucky = { id: 'feat-lucky', name: 'Lucky', source: 'PHB', description: 'You have inexplicable luck.',
      effects: { resource: { name: 'Luck Points', maxUses: 3, resetOn: 'longRest' } } } satisfies Feat;
    const fortitude = { id: 'feat-fortitude', name: 'Dwarven Fortitude', source: 'XGE',
      prerequisite: [{ races: [{ name: 'dwarf' }] }] } satisfies Feat;
    server.use(
      http.get('/api/reference/feats', () => HttpResponse.json([lucky, fortitude])),
      // The server says what the character doesn't meet (D159)
      http.get('/api/characters/:id/feat-options', () => HttpResponse.json([
        { featId: 'feat-lucky', name: 'Lucky', source: 'PHB', eligible: true, unmet: [], taken: false, repeatable: false },
        { featId: 'feat-fortitude', name: 'Dwarven Fortitude', source: 'XGE', eligible: false,
          unmet: ['Being a dwarf (you are a Human)'], prerequisite: 'Being a dwarf', taken: false, repeatable: false },
      ] satisfies FeatOptionResponse[])),
    );
    const bodies = captureAdd();
    renderDialog();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Feat' }));
    // Aria is human, so an ability score improvement couldn't take it; the DM's grant may
    expect(await screen.findByRole('button', { name: /Dwarven Fortitude/ })).toBeEnabled();
    expect(screen.getByText('Prerequisite not met: Being a dwarf (you are a Human)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Lucky/ }));
    await user.type(screen.getByLabelText('Reason'), 'Blessing of Tymora');
    await user.click(screen.getByRole('button', { name: 'Add Adjustment' }));

    await waitFor(() => expect(bodies).toEqual([
      { kind: 'FEAT', feat: { type: 'feat', featId: 'feat-lucky' }, reason: 'Blessing of Tymora' },
    ]));
  });

  it('removes an adjustment only once the DM confirms', async () => {
    const removed: string[] = [];
    server.use(http.delete('/api/characters/:id/adjustments/:adjustmentId', ({ params }) => {
      removed.push(params.adjustmentId as string);
      return HttpResponse.json(character);
    }));
    renderDialog();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Remove +1 AC' }));
    expect(screen.getByText('Remove +1 AC?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText('Remove +1 AC?')).not.toBeInTheDocument();
    expect(removed).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Remove +1 AC' }));
    await user.click(screen.getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(removed).toEqual(['adj-ring']));
  });

  it("shows the server's message when a change is refused", async () => {
    server.use(http.delete('/api/characters/:id/adjustments/:adjustmentId', () =>
      HttpResponse.json({ error: 'That DM Adjustment has already been removed' }, { status: 409 })));
    renderDialog();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Remove Resistance to fire' }));
    await user.click(screen.getByRole('button', { name: 'Remove' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('That DM Adjustment has already been removed');
  });

  it('closes from Done', async () => {
    const onClose = vi.fn();
    renderDialog(onClose);
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Done' }));

    expect(onClose).toHaveBeenCalled();
  });
});
