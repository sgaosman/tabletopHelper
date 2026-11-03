import { useState, useEffect } from 'react';
import { Link2, Link2Off, X } from 'lucide-react';
import { useUpdateInventory } from '../../../queries/characters';
import { useItemSearch } from '../../../queries/reference';
import Button from '../../../components/ui/Button';
import IconButton from '../../../components/ui/IconButton';
import Input from '../../../components/ui/Input';
import type { Currency, InventoryItem } from '../../../types/character';
import type { Item } from '../../../types/reference';
import type { RunCommand } from './types';

/**
 * The character's coins and equipment. Each change sends the whole inventory to the server, which checks it (D158):
 * entries are added and removed, their quantities changed, and an entry can be linked to an item from the items list,
 * which weapon attacks match before the entry's name (D164).
 */
export default function InventoryTab({ characterId, equipment, currency, runCommand }: {
  characterId: string;
  equipment: InventoryItem[];
  currency: Currency;
  runCommand: RunCommand;
}) {
  const updateInventory = useUpdateInventory();
  const [editCurrency, setEditCurrency] = useState(false);
  const [currencyForm, setCurrencyForm] = useState(currency);
  const [newName, setNewName] = useState('');
  const [newQuantity, setNewQuantity] = useState('1');
  const [linking, setLinking] = useState<number | null>(null);

  useEffect(() => { setCurrencyForm(currency); }, [currency]);

  function save(nextEquipment: InventoryItem[], nextCurrency: Currency = currency) {
    return runCommand(updateInventory.mutateAsync({
      characterId, inventory: { equipment: nextEquipment, currency: nextCurrency },
    }));
  }

  async function saveCurrency() {
    if (await save(equipment, currencyForm)) setEditCurrency(false);
  }

  const quantity = /^\d+$/.test(newQuantity.trim()) ? Number(newQuantity.trim()) : NaN;
  const canAdd = newName.trim() !== '' && !Number.isNaN(quantity);

  async function addItem() {
    if (!canAdd) return;
    if (await save([...equipment, { name: newName.trim(), quantity }])) {
      setNewName('');
      setNewQuantity('1');
    }
  }

  function setEntry(index: number, entry: InventoryItem) {
    return save(equipment.map((item, i) => (i === index ? entry : item)));
  }

  async function link(index: number, item: Item | null) {
    if (await setEntry(index, { ...equipment[index], itemId: item?.id ?? null })) setLinking(null);
  }

  const busy = updateInventory.isPending;

  return (
    <div className="space-y-6">
      {/* Currency */}
      <div className="bg-card border border-rule p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint">Currency</h3>
          <button onClick={() => editCurrency ? saveCurrency() : setEditCurrency(true)} disabled={busy}
            className="font-body text-[14px] font-semibold text-muted hover:text-ink transition-colors disabled:opacity-50">
            {editCurrency ? 'Save' : 'Edit'}
          </button>
        </div>
        <div className="grid grid-cols-5 gap-3">
          {(['cp', 'sp', 'ep', 'gp', 'pp'] as const).map(coin => (
            <div key={coin} className="text-center">
              <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">{coin}</p>
              {editCurrency ? (
                <input
                  type="number"
                  min={0}
                  aria-label={coin}
                  value={currencyForm[coin]}
                  onChange={e => setCurrencyForm({ ...currencyForm, [coin]: Math.max(0, parseInt(e.target.value) || 0) })}
                  className="w-full text-center font-heading text-[17px] font-bold text-ink bg-page border border-rule py-1 focus:outline-none focus:border-muted"
                />
              ) : (
                <p className="font-heading text-[17px] font-bold text-ink">{currency[coin]}</p>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Equipment */}
      <div className="bg-card border border-rule p-4">
        <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Equipment</h3>
        {equipment.length > 0 ? (
          <div className="space-y-0">
            {equipment.map((item, i) => {
              const count = item.quantity ?? 1;
              return (
                <div key={i} className="py-2 border-b border-rule-light last:border-0">
                  <div className="flex items-center gap-2">
                    <span className="font-body text-[15px] font-semibold text-ink flex-1">
                      {item.name}
                      {item.itemId && (
                        <span className="ml-2 font-heading text-[11px] font-semibold text-buff" title="Linked to an item">
                          Linked
                        </span>
                      )}
                    </span>
                    <IconButton label={`One fewer ${item.name}`} disabled={busy || count <= 0}
                      onClick={() => setEntry(i, { ...item, quantity: count - 1 })}
                      className="w-7 h-7 bg-page border border-rule text-muted font-body text-[15px] disabled:opacity-30 hover:bg-rule-light">
                      −
                    </IconButton>
                    <span className="font-heading text-[13px] font-semibold text-ink w-6 text-center">{count}</span>
                    <IconButton label={`One more ${item.name}`} disabled={busy}
                      onClick={() => setEntry(i, { ...item, quantity: count + 1 })}
                      className="w-7 h-7 bg-page border border-rule text-muted font-body text-[15px] disabled:opacity-30 hover:bg-rule-light">
                      +
                    </IconButton>
                    {item.itemId ? (
                      <IconButton label={`Unlink ${item.name} from its item`} disabled={busy} onClick={() => link(i, null)}
                        className="p-1 text-faint hover:text-ink disabled:opacity-30">
                        <Link2Off className="w-4 h-4" />
                      </IconButton>
                    ) : (
                      <IconButton label={`Link ${item.name} to an item`} disabled={busy}
                        onClick={() => setLinking(linking === i ? null : i)}
                        className="p-1 text-faint hover:text-ink disabled:opacity-30">
                        <Link2 className="w-4 h-4" />
                      </IconButton>
                    )}
                    <IconButton label={`Remove ${item.name}`} disabled={busy}
                      onClick={() => save(equipment.filter((_, j) => j !== i))}
                      className="p-1 text-faint hover:text-debuff disabled:opacity-30">
                      <X className="w-4 h-4" />
                    </IconButton>
                  </div>
                  {item.description && <p className="font-body text-[13px] text-faint mt-0.5">{item.description}</p>}
                  {linking === i && (
                    <ItemLinkPicker entryName={item.name} onPick={picked => link(i, picked)} onCancel={() => setLinking(null)} />
                  )}
                </div>
              );
            })}
          </div>
        ) : (
          <p className="font-body text-[15px] text-faint">No equipment recorded.</p>
        )}

        <form className="flex items-end gap-2 mt-4" onSubmit={e => { e.preventDefault(); void addItem(); }}>
          <Input label="New item" value={newName} onChange={e => setNewName(e.target.value)} className="flex-1"
            placeholder="Rope, hempen (50 feet)" />
          <Input label="Quantity" type="number" min={0} value={newQuantity} onChange={e => setNewQuantity(e.target.value)}
            className="w-24" />
          <Button type="submit" disabled={!canAdd} loading={busy}>Add</Button>
        </form>
      </div>
    </div>
  );
}

/** Finds an item in the items list for an inventory entry to be linked to. */
function ItemLinkPicker({ entryName, onPick, onCancel }: {
  entryName: string;
  onPick: (item: Item) => void;
  onCancel: () => void;
}) {
  const [query, setQuery] = useState(entryName);
  const search = useItemSearch({ name: query.trim() || undefined, size: 8 });
  const items = search.data?.content ?? [];

  return (
    <div className="mt-2 bg-page-alt border border-rule p-3 space-y-2">
      <div className="flex items-end gap-2">
        <Input label={`Item for ${entryName}`} value={query} onChange={e => setQuery(e.target.value)} className="flex-1" />
        <Button variant="ghost" onClick={onCancel}>Cancel</Button>
      </div>
      {items.length > 0 ? (
        <ul className="space-y-1">
          {items.map(item => (
            <li key={item.id}>
              <button type="button" onClick={() => onPick(item)}
                className="w-full flex items-center justify-between px-2 py-1 font-body text-[14px] font-semibold text-muted hover:bg-page hover:text-ink">
                <span>{item.name}</span>
                <span className="text-[12px] text-faint">{[item.type, item.rarity, item.source].filter(Boolean).join(' · ')}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="font-body text-[13px] text-faint">{search.isFetching ? 'Searching...' : 'No items match.'}</p>
      )}
    </div>
  );
}
