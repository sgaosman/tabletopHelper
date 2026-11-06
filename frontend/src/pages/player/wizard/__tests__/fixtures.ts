import { makeMockRace } from '../../../../test/mocks/handlers';
import type { Background, CharacterClassRef, Race, Spell, Subclass } from '../../../../types/reference';

// Reference data for the wizard's reducer and selector tests, with the raw JSON columns as text, as the server
// sends them

export function race(id: string, name: string, overrides: Partial<Race> = {}): Race {
  return { ...makeMockRace(id, name, '[]'), ...overrides };
}

export function cls(id: string, name: string, overrides: Partial<CharacterClassRef> = {}): CharacterClassRef {
  return {
    id, name, source: 'PHB', hitDice: 8, primaryAbility: 'STR', savingThrowProficiencies: '["STR","CON"]',
    armorProficiencies: null, weaponProficiencies: null, toolProficiencies: null, skillChoices: null,
    spellcastingAbility: null, features: '[]', startingEquipment: '[]', subclassLevel: 3, isSpellcaster: false,
    isPreparedCaster: false, isKnownCaster: false, isPactMagic: false, spellSlotProgression: null,
    multiclassRequirements: null, multiclassProficiencies: null,
    ...overrides,
  };
}

export function subclass(id: string, name: string, of: CharacterClassRef): Subclass {
  return {
    id, name, source: 'PHB', characterClass: of, features: '[]', additionalProficiencies: null,
    alwaysPreparedSpells: null, expandedSpellList: null,
  };
}

export function background(id: string, name: string, overrides: Partial<Background> = {}): Background {
  return {
    id, name, source: 'PHB', feats: null, skillProficiencies: null, toolProficiencies: null, languageProficiencies: null,
    ...overrides,
  };
}

export function spell(name: string, level: number): Spell {
  return {
    id: `spell-${name.toLowerCase().replace(/\W+/g, '-')}`, name, level, school: 'Evocation',
    castingTime: null, rangeDistance: null, components: null, duration: null, concentration: false, ritual: false,
    description: null, higherLevels: null, classes: [], damageType: null, damageDice: null, saveAbility: null,
    source: 'PHB',
  };
}

export const fighter = cls('cls-fighter', 'Fighter', {
  hitDice: 10, savingThrowProficiencies: '["STR","CON"]', armorProficiencies: '["Light","Medium","Heavy","Shield"]',
  weaponProficiencies: '["Simple","Martial"]',
  skillChoices: JSON.stringify({ count: 2, from: ['Acrobatics', 'Athletics', 'Perception', 'Survival'] }),
  multiclassProficiencies: JSON.stringify({ armor: ['Light', 'Medium', 'Shield'], weapons: ['Simple', 'Martial'] }),
});

export const wizard = cls('cls-wizard', 'Wizard', {
  hitDice: 6, savingThrowProficiencies: '["INT","WIS"]', weaponProficiencies: '["Dagger","Quarterstaff"]',
  skillChoices: JSON.stringify({ count: 2, from: ['Arcana', 'History', 'Investigation'] }),
  spellcastingAbility: 'INT', subclassLevel: 2, isSpellcaster: true, isPreparedCaster: true,
});

export const cleric = cls('cls-cleric', 'Cleric', {
  savingThrowProficiencies: '["WIS","CHA"]', armorProficiencies: '["Light","Medium","Shield"]',
  weaponProficiencies: '["Simple"]', skillChoices: JSON.stringify({ count: 2, from: ['History', 'Insight', 'Medicine'] }),
  spellcastingAbility: 'WIS', subclassLevel: 1, isSpellcaster: true, isPreparedCaster: true,
});

// A rogue's multiclass gives one skill from its list (PHB p.164)
export const rogue = cls('cls-rogue', 'Rogue', {
  savingThrowProficiencies: '["DEX","INT"]', toolProficiencies: '["Thieves\' tools"]',
  skillChoices: JSON.stringify({ count: 4, from: ['Acrobatics', 'Deception', 'Stealth', 'Sleight of Hand', 'Insight'] }),
  multiclassProficiencies: JSON.stringify({ armor: ['Light'], tools: ["Thieves' tools"], skills: { from: ['Deception', 'Stealth'], count: 1 } }),
});

export const lifeDomain = subclass('sc-life', 'Life Domain', cleric);
export const lightDomain = subclass('sc-light', 'Light Domain', cleric);
export const evocation = subclass('sc-evocation', 'School of Evocation', wizard);

export const human = race('race-human', 'Human', {
  abilityScoreBonuses: JSON.stringify(['STR', 'DEX', 'CON', 'INT', 'WIS', 'CHA'].map(ability => ({ ability, bonus: 1 }))),
  proficiencies: JSON.stringify({ languages: ['Common'] }),
  raceChoices: JSON.stringify({ languages: [{ anyStandard: 1 }] }),
});

export const variantHuman = race('race-variant-human', 'Variant Human', {
  abilityScoreBonuses: JSON.stringify([{ ability: 'CHOOSE', bonus: 1, count: 2 }]),
  proficiencies: JSON.stringify({ languages: ['Common'] }),
  raceChoices: JSON.stringify({ feats: 1, skills: [{ any: 1 }], languages: [{ anyStandard: 1 }] }),
});

export const highElf = race('race-high-elf', 'High Elf', {
  abilityScoreBonuses: JSON.stringify([{ ability: 'DEX', bonus: 2 }, { ability: 'INT', bonus: 1 }]),
  proficiencies: JSON.stringify({ skills: ['Perception'], languages: ['Common', 'Elvish'], weapons: ['Longsword'] }),
});

export const soldier = background('bg-soldier', 'Soldier', {
  skillProficiencies: '["Athletics","Intimidation"]', toolProficiencies: '["Vehicles (Land)"]',
});

export const sage = background('bg-sage', 'Sage', {
  skillProficiencies: '["Arcana","History"]', languageProficiencies: '[{"anyStandard":2}]',
});
