import { describe, it, expect } from 'vitest';
import type { StartingEquipment, StartingEquipmentEntry } from '../../../../types/reference';
import { initialWizardState, wizardReducer, type WizardAction, type WizardState } from '../wizardReducer';
import { buildCreateRequest, canAdvance } from '../selectors';
import {
  EMPTY_KIT, entryLabel, equipmentComplete, goldDice, goldRange, optionKey, pickKey, rollGold, startingInventory,
} from '../startingEquipment';
import { fighter, human, soldier } from './fixtures';

// The kits as StartingEquipmentOptions serves the PHB's Fighter (two of its four lines) and Soldier (PHB p.72, p.141)

function item(name: string, itemId: string | null, armourCategory: StartingEquipmentEntry['armourCategory'] = null,
  quantity = 1, coinsCp = 0): StartingEquipmentEntry {
  return { kind: 'ITEM', name, quantity, itemId, armourCategory, coinsCp, candidates: [] };
}

const MARTIAL = [{ name: 'Longsword', itemId: 'longsword' }, { name: 'Warhammer', itemId: 'warhammer' }];

const FIGHTER_KIT: StartingEquipment = {
  groups: [
    { choice: true, options: [
      { key: 'a', entries: [item('Chain Mail', 'chain-mail', 'HEAVY')] },
      { key: 'b', entries: [item('Leather Armor', 'leather', 'LIGHT'), item('Longbow', 'longbow')] },
    ] },
    { choice: true, options: [
      { key: 'a', entries: [
        { kind: 'PICK', name: 'Martial weapon', quantity: 1, coinsCp: 0, candidates: MARTIAL },
        item('Shield', 'shield', 'SHIELD'),
      ] },
      { key: 'b', entries: [{ kind: 'PICK', name: 'Martial weapon', quantity: 2, coinsCp: 0, candidates: MARTIAL }] },
    ] },
  ],
  goldAlternative: { count: 5, sides: 4, multiplier: 10 },
};

const SOLDIER_KIT: StartingEquipment = {
  groups: [
    { choice: false, options: [{ key: '_', entries: [
      item('Insignia of rank', null), item('Common Clothes', 'clothes'), item('Pouch', 'pouch', null, 1, 1000),
      { kind: 'COINS', name: '5 sp', quantity: 1, coinsCp: 50, candidates: [] },
    ] }] },
    { choice: true, options: [
      { key: 'a', entries: [item('Bone dice set', 'dice')] },
      { key: 'b', entries: [item('Playing Card Set', 'cards')] },
    ] },
  ],
  goldAlternative: null,
};

const KITS = { cls: FIGHTER_KIT, background: SOLDIER_KIT };

function reduce(state: WizardState, ...actions: WizardAction[]): WizardState {
  return actions.reduce(wizardReducer, state);
}

const chosen = reduce(initialWizardState,
  { type: 'setName', name: 'Brienne' },
  { type: 'selectRace', race: human },
  { type: 'selectClass', cls: fighter },
  { type: 'primaryClassLoaded', cls: fighter, subclasses: [] },
  { type: 'selectBackground', background: soldier });

const CHAIN_MAIL_AND_SHIELD: WizardAction[] = [
  { type: 'chooseEquipmentOption', key: optionKey('class', 0), option: 'a' },
  { type: 'chooseEquipmentOption', key: optionKey('class', 1), option: 'a' },
  { type: 'pickEquipment', key: pickKey('class', 1, 'a', 0), index: 0, count: 1, itemId: 'longsword' },
  { type: 'chooseEquipmentOption', key: optionKey('background', 1), option: 'b' },
];

describe('starting equipment', () => {
  it('is settled once every line has its option and every pick is made', () => {
    expect(equipmentComplete(chosen, KITS)).toBe(false);
    const almost = reduce(chosen, ...CHAIN_MAIL_AND_SHIELD.filter(a => a.type !== 'pickEquipment'));
    expect(equipmentComplete(almost, KITS)).toBe(false);
    expect(equipmentComplete(reduce(chosen, ...CHAIN_MAIL_AND_SHIELD), KITS)).toBe(true);
  });

  it('is not settled while a chosen class\'s or background\'s kit loads, and a kit that failed counts as none', () => {
    expect(equipmentComplete(chosen, { cls: EMPTY_KIT })).toBe(false);
    expect(equipmentComplete(chosen, { cls: EMPTY_KIT, background: EMPTY_KIT })).toBe(true);
  });

  it('needs both picks of "two martial weapons"', () => {
    const base = reduce(chosen,
      { type: 'chooseEquipmentOption', key: optionKey('class', 0), option: 'b' },
      { type: 'chooseEquipmentOption', key: optionKey('class', 1), option: 'b' },
      { type: 'chooseEquipmentOption', key: optionKey('background', 1), option: 'a' },
      { type: 'pickEquipment', key: pickKey('class', 1, 'b', 0), index: 1, count: 2, itemId: 'warhammer' });
    expect(base.equipmentPicks[pickKey('class', 1, 'b', 0)]).toEqual(['', 'warhammer']);
    expect(equipmentComplete(base, KITS)).toBe(false);
    const both = reduce(base, { type: 'pickEquipment', key: pickKey('class', 1, 'b', 0), index: 0, count: 2, itemId: 'warhammer' });
    expect(equipmentComplete(both, KITS)).toBe(true);
    // The same weapon picked twice is one entry of two
    expect(startingInventory(both, KITS).equipment.find(e => e.itemId === 'warhammer')?.quantity).toBe(2);
  });

  it('gives the chosen items linked by item, Chain Mail and the Shield worn, and the pouch\'s and loose coins', () => {
    const { equipment, currency } = startingInventory(reduce(chosen, ...CHAIN_MAIL_AND_SHIELD), KITS);
    expect(equipment).toEqual([
      { name: 'Chain Mail', quantity: 1, itemId: 'chain-mail', equipped: true },
      { name: 'Longsword', quantity: 1, itemId: 'longsword', equipped: false },
      { name: 'Shield', quantity: 1, itemId: 'shield', equipped: true },
      { name: 'Insignia of rank', quantity: 1, itemId: undefined, equipped: false },
      { name: 'Common Clothes', quantity: 1, itemId: 'clothes', equipped: false },
      { name: 'Pouch', quantity: 1, itemId: 'pouch', equipped: false },
      { name: 'Playing Card Set', quantity: 1, itemId: 'cards', equipped: false },
    ]);
    expect(currency).toEqual({ cp: 0, sp: 5, ep: 0, gp: 10, pp: 0 });
  });

  it('wears only the first suit of armour and the first shield', () => {
    const twoSuits: StartingEquipment = { groups: [{ choice: false, options: [{ key: '_', entries: [
      item('Chain Mail', 'chain-mail', 'HEAVY'), item('Leather Armor', 'leather', 'LIGHT'),
      item('Shield', 'shield', 'SHIELD'), item('Spiked Shield', 'shield-2', 'SHIELD'),
    ] }] }], goldAlternative: null };
    const worn = startingInventory(chosen, { cls: twoSuits, background: EMPTY_KIT }).equipment
      .filter(e => e.equipped).map(e => e.name);
    expect(worn).toEqual(['Chain Mail', 'Shield']);
  });

  it('takes the starting wealth in place of both kits, within 5d4 × 10', () => {
    expect(goldRange(FIGHTER_KIT.goldAlternative!)).toEqual({ min: 50, max: 200 });
    expect(goldDice(FIGHTER_KIT.goldAlternative!)).toBe('5d4 × 10 gp');
    expect(goldDice({ count: 5, sides: 4, multiplier: 1 })).toBe('5d4 gp');
    expect(rollGold(FIGHTER_KIT.goldAlternative!, () => 0.99)).toBe(200);
    expect(rollGold(FIGHTER_KIT.goldAlternative!, () => 0)).toBe(50);

    const gold = reduce(chosen, { type: 'setStartingGold', gold: 120 });
    expect(startingInventory(gold, KITS)).toEqual({ equipment: [], currency: { cp: 0, sp: 0, ep: 0, gp: 120, pp: 0 } });
    expect(equipmentComplete(gold, KITS)).toBe(true);
    expect(equipmentComplete(reduce(chosen, { type: 'setStartingGold', gold: 201 }), KITS)).toBe(false);
  });

  it('labels entries as the book lists them', () => {
    expect(entryLabel(item('Handaxe', 'handaxe', null, 2))).toBe('2 × Handaxe');
    expect(entryLabel(item('Pouch', 'pouch', null, 1, 1500))).toBe('Pouch (with 15 gp)');
    expect(entryLabel(FIGHTER_KIT.groups[1].options[1].entries[0])).toBe('2 × martial weapon of your choice');
  });

  it('is cleared with a new class or background, each its own', () => {
    const settled = reduce(chosen, ...CHAIN_MAIL_AND_SHIELD, { type: 'setStartingGold', gold: 100 });
    const newClass = reduce(settled, { type: 'selectClass', cls: fighter });
    expect(newClass.equipmentOptions).toEqual({ [optionKey('background', 1)]: 'b' });
    expect(newClass.equipmentPicks).toEqual({});
    expect(newClass.startingGold).toBeNull();
    const newBackground = reduce(settled, { type: 'selectBackground', background: soldier });
    expect(Object.keys(newBackground.equipmentOptions)).toEqual([optionKey('class', 0), optionKey('class', 1)]);
    expect(newBackground.startingGold).toBe(100);
  });

  it('goes with the create request, and the Equipment step waits for it', () => {
    const state = reduce(chosen, ...CHAIN_MAIL_AND_SHIELD);
    const request = buildCreateRequest(state, KITS);
    expect(request?.equipment?.filter(e => e.equipped).map(e => e.itemId)).toEqual(['chain-mail', 'shield']);
    expect(request?.currency?.gp).toBe(10);
    // Without kits, or with nothing in them, the request carries neither
    expect(buildCreateRequest(state)).not.toHaveProperty('equipment');
    expect(buildCreateRequest(state, { cls: EMPTY_KIT, background: EMPTY_KIT })).not.toHaveProperty('currency');

    const atEquipment = { ...chosen, step: 5 };
    expect(canAdvance(atEquipment, KITS)).toBe(false);
    expect(canAdvance({ ...state, step: 5 }, KITS)).toBe(true);
  });
});
