import { describe, it, expect } from 'vitest';
import {
  addDart, dartCounts, describeTargets, makeMain, pickedHint, removeDart, roleLabel, targetRoleMode,
} from './targetRoles';
import { repeatRollName } from './conditions';

const names: Record<string, string> = { ogre: 'Ogre', goblin: 'Goblin', orc: 'Orc' };
const nameOf = (id: string) => names[id];

describe('targetRoleMode', () => {
  it('gives the darts at the cast level, as Magic Missile has one more for each slot level above 1st (PHB p.257)', () => {
    const roles = { darts: { 1: 3, 2: 4, 3: 5 } };
    expect(targetRoleMode(roles, 3, false)).toEqual({ kind: 'darts', count: 5 });
    expect(targetRoleMode(roles, 1, false)).toEqual({ kind: 'darts', count: 3 });
  });

  it("splits a spell's own targets from the picked creatures, one more own target with Twinned Spell", () => {
    expect(targetRoleMode({ primaryTargets: 1, pickedWithinFeet: 5 }, 1, false))
      .toEqual({ kind: 'split', main: 1, pickedWithin: 5 });
    expect(targetRoleMode({ primaryTargets: 1, pickedWithinFeet: 5 }, 1, true))
      .toEqual({ kind: 'split', main: 2, pickedWithin: 5 });
    // Hail of Thorns is cast on its caster: every target is picked, Twinned or not
    expect(targetRoleMode({ primaryTargets: 0, pickedWithinFeet: 5 }, 1, true))
      .toEqual({ kind: 'split', main: 0, pickedWithin: 5 });
  });

  it('gives nothing for a spell whose targets are all alike', () => {
    expect(targetRoleMode(undefined, 1, false)).toBeNull();
    expect(targetRoleMode(null, 1, false)).toBeNull();
    expect(targetRoleMode({}, 1, false)).toBeNull();
  });
});

describe('roles', () => {
  const ice = { kind: 'split', main: 1, pickedWithin: 5 } as const;
  const thorns = { kind: 'split', main: 0, pickedWithin: 5 } as const;

  it('labels the first chosen the main target and the rest picked within the distance', () => {
    expect(roleLabel(ice, 0)).toBe('Main target');
    expect(roleLabel(ice, 1)).toBe('Picked (within 5 ft)');
    expect(roleLabel(thorns, 0)).toBe('Picked (within 5 ft)');
    expect(roleLabel({ kind: 'split', main: 1, pickedWithin: null }, 1)).toBe('Picked');
  });

  it('explains what the picked creatures must be near', () => {
    expect(pickedHint(ice)).toBe('The first creature chosen is the main target; the others are creatures within 5 ft of it '
      + 'that the spell also reaches.');
    expect(pickedHint(thorns)).toBe('Pick the creatures within 5 ft of the creature your next attack is against.');
  });

  it('makes a picked creature the main target, the old main target becoming a pick', () => {
    expect(makeMain(['ogre', 'goblin', 'orc'], 'orc')).toEqual(['orc', 'ogre', 'goblin']);
    expect(makeMain(['ogre'], 'goblin')).toEqual(['ogre']);
  });

  it('names each target with its role for the confirm step', () => {
    expect(describeTargets(ice, ['ogre', 'goblin', 'orc'], nameOf)).toBe('Ogre (main); picked within 5 ft: Goblin, Orc');
    expect(describeTargets(thorns, ['ogre', 'goblin'], nameOf)).toBe('picked within 5 ft: Ogre, Goblin');
    expect(describeTargets(null, ['ogre'], nameOf)).toBeNull();
  });
});

describe('darts', () => {
  it('adds a dart while any are left and takes one off a target', () => {
    let darts = addDart([], 'ogre', 3);
    darts = addDart(darts, 'goblin', 3);
    darts = addDart(darts, 'ogre', 3);
    expect(darts).toEqual(['ogre', 'goblin', 'ogre']);
    expect(addDart(darts, 'orc', 3)).toBe(darts);
    expect(removeDart(darts, 'ogre')).toEqual(['ogre', 'goblin']);
    expect(removeDart(darts, 'orc')).toBe(darts);
  });

  it('counts each target\'s darts and names them for the confirm step', () => {
    expect([...dartCounts(['ogre', 'goblin', 'ogre'])]).toEqual([['ogre', 2], ['goblin', 1]]);
    expect(describeTargets({ kind: 'darts', count: 3 }, ['ogre', 'goblin', 'ogre'], nameOf))
      .toBe('Ogre (2 darts), Goblin (1 dart)');
  });
});

describe('repeatRollName', () => {
  it("names a check made with the action in place of a save (D200), else the save", () => {
    expect(repeatRollName({ ability: 'WIS', dc: 13, awaitingRoll: false, byAction: true, check: true })).toBe('WIS check');
    expect(repeatRollName({ ability: 'INT', dc: 14, awaitingRoll: false, byAction: true, check: true, skill: 'Investigation' }))
      .toBe('INT (Investigation) check');
    expect(repeatRollName({ ability: 'WIS', dc: 15, awaitingRoll: false, byAction: true })).toBe('WIS save');
  });
});
