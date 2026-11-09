import type { Currency, InventoryItem } from '../../../types/character';
import type { StartingEquipment, StartingEquipmentEntry } from '../../../types/reference';
import type { WizardState } from './wizardReducer';

// The starting equipment the creation wizard offers (PHB p.143) and the inventory it gives. The server reads the
// options from the class's and background's data and links their items (StartingEquipmentOptions); the wizard keeps
// the player's choices, and the server checks the inventory as it checks any other (InventoryChecks, D158).

/**
 * The starting equipment of the chosen class and background: undefined while it loads, and a kit with no lines for
 * one that couldn't load, so the player can go on without it.
 */
export interface WizardKits {
  cls?: StartingEquipment;
  background?: StartingEquipment;
}

export type KitOwner = 'class' | 'background';

/** A kit with nothing in it. */
export const EMPTY_KIT: StartingEquipment = { groups: [], goldAlternative: null };

/** The key a line's chosen option is kept under. */
export function optionKey(owner: KitOwner, line: number): string {
  return `${owner}:${line}`;
}

/** The key an entry's picks are kept under. */
export function pickKey(owner: KitOwner, line: number, option: string, entry: number): string {
  return `${owner}:${line}:${option}:${entry}`;
}

/** An option's key as the book prints it: "a" is "(a)". */
export function optionLetter(key: string): string {
  return `(${key})`;
}

/** An entry as a list shows it, such as "Chain Mail", "2 × Handaxe", "15 gp" or "2 martial weapons". */
export function entryLabel(entry: StartingEquipmentEntry): string {
  if (entry.kind === 'COINS') return entry.name;
  if (entry.kind === 'PICK') return entry.quantity > 1 ? `${entry.quantity} × ${entry.name.toLowerCase()} of your choice` : `${entry.name} of your choice`;
  const coins = entry.coinsCp > 0 ? ` (with ${coinText(entry.coinsCp)})` : '';
  return `${entry.quantity > 1 ? `${entry.quantity} × ` : ''}${entry.name}${coins}`;
}

/** An inventory entry the kit gives, such as "2 × Handaxe" or "Chain Mail (worn)". */
export function inventoryLabel(item: InventoryItem): string {
  return `${item.quantity && item.quantity > 1 ? `${item.quantity} × ` : ''}${item.name}${item.equipped ? ' (worn)' : ''}`;
}

function coinText(copper: number): string {
  if (copper >= 100 && copper % 100 === 0) return `${copper / 100} gp`;
  if (copper >= 10 && copper % 10 === 0) return `${copper / 10} sp`;
  return `${copper} cp`;
}

/** The starting wealth's lowest and highest roll in gold pieces (PHB p.143), such as 50 and 200 for 5d4 × 10. */
export function goldRange(gold: NonNullable<StartingEquipment['goldAlternative']>): { min: number; max: number } {
  return { min: gold.count * gold.multiplier, max: gold.count * gold.sides * gold.multiplier };
}

/** The starting wealth as the book writes it: "5d4 × 10 gp", or the Monk's "5d4 gp". */
export function goldDice(gold: NonNullable<StartingEquipment['goldAlternative']>): string {
  return `${gold.count}d${gold.sides}${gold.multiplier > 1 ? ` × ${gold.multiplier}` : ''} gp`;
}

/** A roll of the starting wealth. */
export function rollGold(gold: NonNullable<StartingEquipment['goldAlternative']>, random: () => number = Math.random): number {
  let total = 0;
  for (let i = 0; i < gold.count; i++) total += 1 + Math.floor(random() * gold.sides);
  return total * gold.multiplier;
}

/** The kits of the chosen class and background, each with its owner, skipping those not chosen or still loading. */
function chosenKits(state: WizardState, kits: WizardKits): Array<[KitOwner, StartingEquipment]> {
  const result: Array<[KitOwner, StartingEquipment]> = [];
  if (state.selectedClass && kits.cls) result.push(['class', kits.cls]);
  if (state.selectedBackground && kits.background) result.push(['background', kits.background]);
  return result;
}

/** The entries a kit gives with the options chosen so far, each with the key its picks are kept under. */
function chosenEntries(state: WizardState, owner: KitOwner, kit: StartingEquipment):
  Array<{ entry: StartingEquipmentEntry; picks: string }> {
  const result: Array<{ entry: StartingEquipmentEntry; picks: string }> = [];
  kit.groups.forEach((group, line) => {
    const option = group.choice
      ? group.options.find(o => o.key === state.equipmentOptions[optionKey(owner, line)])
      : group.options[0];
    option?.entries.forEach((entry, i) => result.push({ entry, picks: pickKey(owner, line, option.key, i) }));
  });
  return result;
}

/**
 * Whether the equipment is settled: gold within the class's range, or every line's option chosen and every pick
 * made. Nothing is settled while a chosen class's or background's kit loads.
 */
export function equipmentComplete(state: WizardState, kits: WizardKits): boolean {
  if ((state.selectedClass && !kits.cls) || (state.selectedBackground && !kits.background)) return false;
  const gold = kits.cls?.goldAlternative;
  if (state.startingGold != null) {
    if (!gold) return false;
    const { min, max } = goldRange(gold);
    return Number.isInteger(state.startingGold) && state.startingGold >= min && state.startingGold <= max;
  }
  return chosenKits(state, kits).every(([owner, kit]) =>
    kit.groups.every((group, line) => !group.choice || !!state.equipmentOptions[optionKey(owner, line)])
    && chosenEntries(state, owner, kit).every(({ entry, picks }) => entry.kind !== 'PICK'
      || Array.from({ length: entry.quantity }, (_, i) => state.equipmentPicks[picks]?.[i]).every(Boolean)));
}

/**
 * The inventory and coins the starting equipment gives, as the create request sends them. The same item from two
 * lines is one entry with both quantities; a pick is the item picked. The first suit of armour and the first shield
 * are equipped (one of each, PHB p.144), so the character starts with the AC they give. Gold taken in place of the
 * equipment gives only the gold.
 */
export function startingInventory(state: WizardState, kits: WizardKits): { equipment: InventoryItem[]; currency: Currency } {
  const currency: Currency = { cp: 0, sp: 0, ep: 0, gp: 0, pp: 0 };
  if (state.startingGold != null) return { equipment: [], currency: { ...currency, gp: state.startingGold } };
  const equipment: InventoryItem[] = [];
  let copper = 0;
  let wearingArmour = false;
  let carryingShield = false;
  const add = (name: string, quantity: number, itemId: string | null | undefined, armour: string | null | undefined) => {
    const same = equipment.find(e => (itemId ? e.itemId === itemId : !e.itemId && e.name === name));
    if (same) { same.quantity = (same.quantity ?? 1) + quantity; return; }
    let equipped = false;
    if (armour === 'SHIELD' && !carryingShield) equipped = carryingShield = true;
    else if (armour && armour !== 'SHIELD' && !wearingArmour) equipped = wearingArmour = true;
    equipment.push({ name, quantity, itemId: itemId ?? undefined, equipped });
  };
  for (const [owner, kit] of chosenKits(state, kits)) {
    for (const { entry, picks } of chosenEntries(state, owner, kit)) {
      copper += entry.coinsCp;
      if (entry.kind === 'COINS') continue;
      if (entry.kind === 'PICK') {
        for (const id of state.equipmentPicks[picks] ?? []) {
          const candidate = entry.candidates.find(c => c.itemId === id);
          if (candidate) add(candidate.name, 1, candidate.itemId, null);
        }
        continue;
      }
      add(entry.name, entry.quantity, entry.itemId, entry.armourCategory);
    }
  }
  currency.gp = Math.floor(copper / 100);
  currency.sp = Math.floor((copper % 100) / 10);
  currency.cp = copper % 10;
  return { equipment, currency };
}
