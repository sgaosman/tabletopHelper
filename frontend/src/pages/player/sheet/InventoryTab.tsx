import { useState, useEffect } from 'react';
import { Link2, Link2Off, X } from 'lucide-react';
import { useUpdateInventory } from '../../../queries/characters';
import { useItemDetail, useItemSearch } from '../../../queries/reference';
import Button from '../../../components/ui/Button';
import IconButton from '../../../components/ui/IconButton';
import Input from '../../../components/ui/Input';
import type { Currency, InventoryItem } from '../../../types/character';
import type { Item } from '../../../types/reference';
import type { RunCommand } from './types';

/**
 * The character's coins and equipment. Each change sends the whole inventory to the server, which checks it (D158):
 * entries are added and removed, their quantities changed, and an entry can be linked to an item from the items list,
 * which weapon attacks match before the entry's name (D164). An entry the server reads as armour or a shield can be
 * equipped or taken off, which changes the AC (M13.5); the server refuses a second armour or shield, and the sheet shows
 * why. A linked item that requires attunement can be attuned to, three at most (DMG p.138), which its charges need, and
 * a linked spell scroll records the spell it holds, which the Use an Item dialog reads it with (D210). Each item carried
 * counts towards the three, so an attuned entry of two rings is two. While the character is in an encounter that is
 * under way the inventory can't change, since the encounter reads it (D210): the tab shows it without its controls.
 */
export default function InventoryTab({ characterId, equipment, currency, armourEntries = [], runCommand, locked = false }: {
  characterId: string;
  equipment: InventoryItem[];
  currency: Currency;
  /** The positions of the entries the server reads as armour or a shield. */
  armourEntries?: number[];
  runCommand: RunCommand;
  /** True while the character is in an encounter that is under way, when the inventory waits for it to end. */
  locked?: boolean;
}) {
  const updateInventory = useUpdateInventory();
  const [editCurrency, setEditCurrency] = useState(false);
  const [currencyForm, setCurrencyForm] = useState(currency);
  const [newName, setNewName] = useState('');
  const [newQuantity, setNewQuantity] = useState('1');
  const [linking, setLinking] = useState<number | null>(null);

  useEffect(() => { setCurrencyForm(currency); }, [currency]);
  // The sheet locking mid-edit, as the character joins a live encounter (D218), closes the coin editor and the item
  // picker, whose saves the server would refuse
  const [wasLocked, setWasLocked] = useState(locked);
  if (locked !== wasLocked) {
    setWasLocked(locked);
    if (locked) {
      setEditCurrency(false);
      setLinking(null);
      setCurrencyForm(currency);
    }
  }

  function save(nextEquipment: InventoryItem[], nextCurrency: Currency = currency, done?: string) {
    return runCommand(updateInventory.mutateAsync({
      characterId, inventory: { equipment: nextEquipment, currency: nextCurrency },
    }), done);
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

  function toggleEquipped(index: number) {
    const entry = equipment[index];
    const equipped = !entry.equipped;
    return save(equipment.map((item, i) => (i === index ? { ...entry, equipped } : item)), currency,
      `${entry.name} ${equipped ? 'equipped' : 'taken off'}`);
  }

  // Each item carried counts, as the server counts them (DMG p.138)
  const attunedCount = equipment.filter(entry => entry.attuned).reduce((sum, entry) => sum + carried(entry), 0);

  function toggleAttuned(index: number) {
    const entry = equipment[index];
    const attuned = !entry.attuned;
    return save(equipment.map((item, i) => (i === index ? { ...entry, attuned } : item)), currency,
      `${attuned ? 'Attuned to' : 'No longer attuned to'} ${entry.name}`);
  }

  function setScrollSpell(index: number, spell: string) {
    const entry = equipment[index];
    return save(equipment.map((item, i) => (i === index ? { ...entry, spell: spell.trim() || null } : item)), currency,
      spell.trim() ? `${entry.name} holds ${spell.trim()}` : undefined);
  }

  // The item picker is kept by the entry's place, so removing an entry above it moves it with its entry, and
  // removing its own entry closes it, rather than leaving it open on the entry that slides into the place
  async function removeAt(index: number) {
    if (await save(equipment.filter((_, j) => j !== index))) {
      setLinking(open => (open === null || open < index ? open : open === index ? null : open - 1));
    }
  }

  // Unlinking an item ends attunement to it, which only a linked item can have
  async function link(index: number, item: Item | null) {
    const entry = { ...equipment[index], itemId: item?.id ?? null };
    if (!item) delete entry.attuned;
    if (await setEntry(index, entry)) setLinking(null);
  }

  const busy = updateInventory.isPending || locked;

  return (
    <div className="space-y-6">
      {locked && (
        <p role="status" className="font-body text-[13px] font-semibold text-muted">
          In an encounter that is under way: the inventory can't change until it ends.
        </p>
      )}
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
                    <span className="font-body text-[15px] font-semibold text-ink flex-1 min-w-0 break-words">
                      {item.name}
                      {item.itemId && (
                        <span className="ml-2 font-heading text-[11px] font-semibold text-buff" title="Linked to an item">
                          Linked
                        </span>
                      )}
                      {armourEntries.includes(i) && item.equipped && (
                        <span className="ml-2 font-heading text-[11px] font-semibold text-ink" title="Worn">
                          Equipped
                        </span>
                      )}
                    </span>
                    {armourEntries.includes(i) && (
                      <Button size="sm" variant="secondary" disabled={busy} onClick={() => void toggleEquipped(i)}
                        aria-label={`${item.equipped ? 'Take off' : 'Equip'} ${item.name}`}>
                        {item.equipped ? 'Take off' : 'Equip'}
                      </Button>
                    )}
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
                      onClick={() => void removeAt(i)}
                      className="p-1 text-faint hover:text-debuff disabled:opacity-30">
                      <X className="w-4 h-4" />
                    </IconButton>
                  </div>
                  {item.description && <p className="font-body text-[13px] text-faint mt-0.5">{item.description}</p>}
                  {item.itemId && (
                    <MagicItemControls entry={item} busy={busy} attunedCount={attunedCount}
                      onToggleAttuned={() => void toggleAttuned(i)} onSpell={spell => setScrollSpell(i, spell)} />
                  )}
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

        {!locked && <form className="flex items-end gap-2 mt-4" onSubmit={e => { e.preventDefault(); void addItem(); }}>
          <Input label="New item" value={newName} onChange={e => setNewName(e.target.value)} className="flex-1"
            placeholder="Rope, hempen (50 feet)" />
          <Input label="Quantity" type="number" min={0} value={newQuantity} onChange={e => setNewQuantity(e.target.value)}
            className="w-24" />
          <Button type="submit" disabled={!canAdd} loading={updateInventory.isPending}>Add</Button>
        </form>}
      </div>
    </div>
  );
}

/**
 * What a linked entry's item offers beyond its link (D210): an Attune toggle for an item that requires attunement, with
 * no more than three attuned at once (DMG p.138), and for a spell scroll the spell it holds.
 */
function MagicItemControls({ entry, busy, attunedCount, onToggleAttuned, onSpell }: {
  entry: InventoryItem;
  busy: boolean;
  attunedCount: number;
  onToggleAttuned: () => void;
  onSpell: (spell: string) => Promise<boolean> | void;
}) {
  const detail = useItemDetail(entry.itemId);
  const [spell, setSpell] = useState(entry.spell ?? '');
  useEffect(() => { setSpell(entry.spell ?? ''); }, [entry.spell]);
  const item = detail.data;
  if (!item) return null;
  const attunable = item.requiresAttunement;
  const scroll = item.spellScrollLevel != null;
  if (!attunable && !scroll) return null;
  const full = !entry.attuned && attunedCount + carried(entry) > 3;

  return (
    <div className="flex flex-wrap items-end gap-2 mt-1.5">
      {attunable && (
        <>
          <Button size="sm" variant={entry.attuned ? 'primary' : 'secondary'} disabled={busy || full}
            onClick={onToggleAttuned} aria-pressed={!!entry.attuned}
            aria-label={`${entry.attuned ? 'End attunement to' : 'Attune to'} ${entry.name}`}>
            {entry.attuned ? 'Attuned' : 'Attune'}
          </Button>
          <span className="font-body text-[12.5px] font-semibold text-faint">
            {full ? (attunedCount >= 3 ? 'Already attuned to 3 items (DMG p.138)'
              : `Attuning to ${carried(entry)} more would pass 3 items (DMG p.138)`)
              : `Requires attunement${item.attunementCondition ? ` ${item.attunementCondition}` : ''}`}
          </span>
        </>
      )}
      {scroll && (
        <form className="flex items-end gap-2" onSubmit={e => { e.preventDefault(); void onSpell(spell); }}>
          <Input label={`Spell on ${entry.name}`} value={spell} onChange={e => setSpell(e.target.value)}
            placeholder={item.spellScrollLevel === 0 ? 'A cantrip' : `A level ${item.spellScrollLevel} spell`} />
          <Button type="submit" size="sm" variant="secondary" disabled={busy || spell.trim() === (entry.spell ?? '')}>
            Save spell
          </Button>
        </form>
      )}
    </div>
  );
}

// How many of the entry are carried: one without a quantity, as the server reads it
function carried(entry: InventoryItem) {
  return entry.quantity != null ? Math.max(0, entry.quantity) : 1;
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
