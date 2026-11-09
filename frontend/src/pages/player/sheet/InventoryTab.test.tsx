import { describe, it, expect } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render } from '../../../test/test-utils';
import { server } from '../../../test/mocks/server';
import { makeMockCharacter } from '../../../test/mocks/handlers';
import type { InventoryItem, InventoryRequest } from '../../../types/character';
import type { Item, PageResponse } from '../../../types/reference';
import type { RunCommand } from './types';
import InventoryTab from './InventoryTab';

const equipment: InventoryItem[] = [
  { name: 'Longsword', quantity: 1 },
  { name: 'Shield', quantity: 1 },
  { name: 'Healing Potion', quantity: 3, description: 'Restores 2d4+2 HP' },
];
const currency = { cp: 15, sp: 0, ep: 0, gp: 120, pp: 5 };

// Passes the command through, as the sheet does, so a test sees what reached the server
const runCommand: RunCommand = async command => {
  await command;
  return true;
};

/** Records each inventory the tab sends, answering with the character. */
function recordInventories() {
  const sent: InventoryRequest[] = [];
  server.use(http.put('/api/characters/:id/inventory', async ({ params, request }) => {
    sent.push(await request.json() as InventoryRequest);
    return HttpResponse.json(makeMockCharacter(params.id as string, 'Gimli', 'Fighter', 5));
  }));
  return sent;
}

function renderTab(items: InventoryItem[] = equipment, run: RunCommand = runCommand, armourEntries: number[] = []) {
  return render(<InventoryTab characterId="char-001" equipment={items} currency={currency}
    armourEntries={armourEntries} runCommand={run} />);
}

describe('InventoryTab', () => {
  it('shows the coins by kind and the equipment by name', () => {
    renderTab();

    expect(screen.getByText('Currency')).toBeInTheDocument();
    expect(screen.getByText('gp')).toBeInTheDocument();
    expect(screen.getByText('pp')).toBeInTheDocument();
    expect(screen.getByText('Longsword')).toBeInTheDocument();
    expect(screen.getByText('Restores 2d4+2 HP')).toBeInTheDocument();
  });

  it('shows an empty state when there is no equipment', () => {
    renderTab([]);
    expect(screen.getByText(/No equipment recorded/)).toBeInTheDocument();
  });

  it('saves the coins with the equipment as it is, as JSON (D158)', async () => {
    const sent = recordInventories();
    renderTab();
    const user = userEvent.setup();

    await user.click(screen.getByText('Edit'));
    const gold = screen.getByLabelText('gp');
    await user.clear(gold);
    await user.type(gold, '42');
    await user.click(screen.getByText('Save'));

    await waitFor(() => expect(sent).toEqual([{ equipment, currency: { ...currency, gp: 42 } }]));
  });

  it('adds an item, and changes or removes one, sending the whole inventory each time', async () => {
    const sent = recordInventories();
    renderTab();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('New item'), ' Rope ');
    await user.clear(screen.getByLabelText('Quantity'));
    await user.type(screen.getByLabelText('Quantity'), '2');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'One more Healing Potion' }));
    await waitFor(() => expect(sent).toHaveLength(2));
    await user.click(screen.getByRole('button', { name: 'Remove Shield' }));
    await waitFor(() => expect(sent).toHaveLength(3));

    expect(sent[0].equipment).toEqual([...equipment, { name: 'Rope', quantity: 2 }]);
    expect(sent[1].equipment[2]).toEqual({ name: 'Healing Potion', quantity: 4, description: 'Restores 2d4+2 HP' });
    expect(sent[2].equipment.map(item => item.name)).toEqual(['Longsword', 'Healing Potion']);
    expect(sent.every(inventory => inventory.currency.gp === 120)).toBe(true);
  });

  it('links an entry to an item from the items list, and unlinks it (D164)', async () => {
    const sent = recordInventories();
    const longsword = {
      id: 'item-longsword', name: 'Longsword', type: 'M', subtype: 'martial', rarity: 'none', description: null,
      properties: null, requiresAttunement: false, attunementCondition: null, weight: 3, cost: '15 gp',
      damageDice: '1d8', damageType: 'slashing', source: 'PHB', isHomebrew: false, stealthDisadvantage: false,
    } satisfies Item;
    server.use(http.get('/api/reference/items', () => HttpResponse.json({
      content: [longsword], totalElements: 1, totalPages: 1, number: 0, size: 8,
    } satisfies PageResponse<Item>)));
    const { rerender } = renderTab();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Link Longsword to an item' }));
    const picker = screen.getByLabelText('Item for Longsword').closest('div.bg-page-alt') as HTMLElement;
    await user.click(await within(picker).findByRole('button', { name: /Longsword/ }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0].equipment[0]).toEqual({ name: 'Longsword', quantity: 1, itemId: 'item-longsword' });

    rerender(<InventoryTab characterId="char-001" equipment={sent[0].equipment} currency={currency}
      runCommand={runCommand} />);
    expect(screen.getByTitle('Linked to an item')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Unlink Longsword from its item' }));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1].equipment[0]).toEqual({ name: 'Longsword', quantity: 1, itemId: null });
  });

  it('offers to equip only the entries the server reads as armour or a shield, and sends the change (M13.5)', async () => {
    const sent = recordInventories();
    const items: InventoryItem[] = [
      { name: 'Longsword', quantity: 1 },
      { name: 'Chain Mail', quantity: 1, itemId: 'item-chain-mail' },
      { name: 'Shield', quantity: 1, equipped: true },
    ];
    renderTab(items, runCommand, [1, 2]);
    const user = userEvent.setup();

    expect(screen.queryByRole('button', { name: /Equip Longsword|Take off Longsword/ })).not.toBeInTheDocument();
    expect(screen.getByTitle('Worn')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take off Shield' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Equip Chain Mail' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0].equipment[1]).toEqual({ name: 'Chain Mail', quantity: 1, itemId: 'item-chain-mail', equipped: true });
    expect(sent[0].equipment[0]).toEqual({ name: 'Longsword', quantity: 1 });

    await user.click(screen.getByRole('button', { name: 'Take off Shield' }));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1].equipment[2]).toEqual({ name: 'Shield', quantity: 1, equipped: false });
  });

  it("says what was equipped, and passes the server's refusal of a second armour to the sheet", async () => {
    const messages: string[] = [];
    const run: RunCommand = async (command, done) => {
      try {
        await command;
        messages.push(typeof done === 'string' ? done : 'Saved');
        return true;
      } catch (err) {
        messages.push((err as { response?: { data?: { error?: string } } }).response?.data?.error ?? 'failed');
        return false;
      }
    };
    recordInventories();
    const { unmount } = renderTab([{ name: 'Leather Armor', quantity: 1 }], run, [0]);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Equip Leather Armor' }));
    await waitFor(() => expect(messages).toEqual(['Leather Armor equipped']));
    unmount();

    server.use(http.put('/api/characters/:id/inventory', () => HttpResponse.json(
      { error: 'Only one suit of armour can be worn at a time: unequip Chain Mail before equipping Leather Armor' },
      { status: 400 })));
    renderTab([{ name: 'Chain Mail', quantity: 1, equipped: true }, { name: 'Leather Armor', quantity: 1 }], run, [0, 1]);
    await user.click(screen.getByRole('button', { name: 'Equip Leather Armor' }));
    await waitFor(() => expect(messages).toHaveLength(2));
    expect(messages[1]).toBe('Only one suit of armour can be worn at a time: unequip Chain Mail before equipping Leather Armor');
  });

  it('shows nothing changed when the server refuses the inventory', async () => {
    let commands = 0;
    const run: RunCommand = async command => {
      commands++;
      await command.catch(() => undefined);
      return false;
    };
    server.use(http.put('/api/characters/:id/inventory', () =>
      HttpResponse.json({ error: 'There can\'t be fewer than 0 of Rope' }, { status: 400 })));
    renderTab([], run);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('New item'), 'Rope');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    await waitFor(() => expect(commands).toBe(1));
    // The form keeps what was typed, so it can be corrected
    expect(screen.getByLabelText('New item')).toHaveValue('Rope');
  });
});
