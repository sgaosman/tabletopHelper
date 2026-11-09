import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockRace } from '../../test/mocks/handlers';
import type { CharacterCreateRequest } from '../../types/character';
import type {
  Background, CharacterClassRef, PageResponse, Race, Spell, StartingEquipment, StartingEquipmentEntry, Subclass,
} from '../../types/reference';
import CharacterCreateWizard from './CharacterCreateWizard';

/*
 * The request the wizard sends for four whole creations, driven through its steps. They are a baseline: a change
 * to how the wizard keeps its state must leave each request as it is.
 *
 * The reference data is shaped as the server sends it (RaceLoader, CharacterClassLoader, BackgroundLoader), with
 * the raw JSON columns as text, as the mock handlers serve them. The ability scores are the standard array.
 */

// ── Races ──

const human = {
  ...makeMockRace('race-human', 'Human', JSON.stringify(
    ['STR', 'DEX', 'CON', 'INT', 'WIS', 'CHA'].map(ability => ({ ability, bonus: 1 })))),
  proficiencies: JSON.stringify({ languages: ['Common'] }),
  raceChoices: JSON.stringify({ languages: [{ anyStandard: 1 }] }),
} satisfies Race;

// A Variant Human's feat is a Pending Choice the server adds, made on the sheet (D159): the wizard doesn't ask for it
const variantHuman = {
  ...makeMockRace('race-variant-human', 'Variant Human', JSON.stringify([{ ability: 'CHOOSE', bonus: 1, count: 2 }])),
  proficiencies: JSON.stringify({ languages: ['Common'] }),
  raceChoices: JSON.stringify({ feats: 1, skills: [{ any: 1 }], languages: [{ anyStandard: 1 }] }),
} satisfies Race;

const elf = {
  ...makeMockRace('race-high-elf', 'High Elf', JSON.stringify([{ ability: 'DEX', bonus: 2 }, { ability: 'INT', bonus: 1 }])),
  darkvision: 60,
  proficiencies: JSON.stringify({ skills: ['Perception'], languages: ['Common', 'Elvish'], weapons: ['Longsword', 'Shortsword', 'Shortbow', 'Longbow'] }),
} satisfies Race;

// ── Classes ──

const fighter = {
  id: 'cls-fighter', name: 'Fighter', source: 'PHB', hitDice: 10, primaryAbility: 'STR or DEX',
  savingThrowProficiencies: '["STR","CON"]', armorProficiencies: '["Light","Medium","Heavy","Shield"]',
  weaponProficiencies: '["Simple","Martial"]', toolProficiencies: null,
  skillChoices: JSON.stringify({ count: 2, from: ['Acrobatics', 'Animal handling', 'Athletics', 'History', 'Insight', 'Intimidation', 'Perception', 'Survival'] }),
  spellcastingAbility: null, features: '[]', startingEquipment: '[]', subclassLevel: 3, isSpellcaster: false,
  isPreparedCaster: false, isKnownCaster: false, isPactMagic: false, spellSlotProgression: null,
  multiclassRequirements: JSON.stringify([{ ability: 'STR', minimum: 13, operator: 'OR' }, { ability: 'DEX', minimum: 13, operator: 'OR' }]),
  multiclassProficiencies: JSON.stringify({ armor: ['Light', 'Medium', 'Shield'], weapons: ['Simple', 'Martial'] }),
} satisfies CharacterClassRef;

const wizard = {
  id: 'cls-wizard', name: 'Wizard', source: 'PHB', hitDice: 6, primaryAbility: 'INT',
  savingThrowProficiencies: '["INT","WIS"]', armorProficiencies: null,
  weaponProficiencies: '["Dagger","Dart","Sling","Quarterstaff","Light crossbow"]', toolProficiencies: null,
  skillChoices: JSON.stringify({ count: 2, from: ['Arcana', 'History', 'Insight', 'Investigation', 'Medicine', 'Religion'] }),
  spellcastingAbility: 'INT', features: '[]', startingEquipment: '[]', subclassLevel: 2, isSpellcaster: true,
  isPreparedCaster: true, isKnownCaster: false, isPactMagic: false, spellSlotProgression: null,
  multiclassRequirements: JSON.stringify([{ ability: 'INT', minimum: 13 }]), multiclassProficiencies: null,
} satisfies CharacterClassRef;

const cleric = {
  id: 'cls-cleric', name: 'Cleric', source: 'PHB', hitDice: 8, primaryAbility: 'WIS',
  savingThrowProficiencies: '["WIS","CHA"]', armorProficiencies: '["Light","Medium","Shield"]',
  weaponProficiencies: '["Simple"]', toolProficiencies: null,
  skillChoices: JSON.stringify({ count: 2, from: ['History', 'Insight', 'Medicine', 'Persuasion', 'Religion'] }),
  spellcastingAbility: 'WIS', features: '[]', startingEquipment: '[]', subclassLevel: 1, isSpellcaster: true,
  isPreparedCaster: true, isKnownCaster: false, isPactMagic: false, spellSlotProgression: null,
  multiclassRequirements: JSON.stringify([{ ability: 'WIS', minimum: 13 }]),
  multiclassProficiencies: JSON.stringify({ armor: ['Light', 'Medium', 'Shield'] }),
} satisfies CharacterClassRef;

function subclass(id: string, name: string, cls: CharacterClassRef): Subclass {
  return {
    id, name, source: 'PHB', characterClass: cls, features: '[]', additionalProficiencies: null,
    alwaysPreparedSpells: null, expandedSpellList: null,
  };
}

const SUBCLASSES: Record<string, Subclass[]> = {
  'cls-fighter': [subclass('sc-champion', 'Champion', fighter)],
  'cls-wizard': [subclass('sc-evocation', 'School of Evocation', wizard)],
  'cls-cleric': [subclass('sc-life', 'Life Domain', cleric), subclass('sc-light', 'Light Domain', cleric)],
};

// ── Backgrounds ──

const soldier = {
  id: 'bg-soldier', name: 'Soldier', source: 'PHB', feats: null,
  skillProficiencies: '["Athletics","Intimidation"]', toolProficiencies: '["Vehicles (Land)"]', languageProficiencies: null,
} satisfies Background;

const sage = {
  id: 'bg-sage', name: 'Sage', source: 'PHB', feats: null,
  skillProficiencies: '["Arcana","History"]', toolProficiencies: null, languageProficiencies: '[{"anyStandard":2}]',
} satisfies Background;

const acolyte = {
  id: 'bg-acolyte', name: 'Acolyte', source: 'PHB', feats: null,
  skillProficiencies: '["Insight","Religion"]', toolProficiencies: null, languageProficiencies: '[{"anyStandard":2}]',
} satisfies Background;

// ── Starting equipment, as StartingEquipmentOptions reads the PHB's Fighter and Soldier ──

function item(name: string, itemId: string, armourCategory: StartingEquipmentEntry['armourCategory'] = null, quantity = 1,
  coinsCp = 0): StartingEquipmentEntry {
  return { kind: 'ITEM', name, quantity, itemId, armourCategory, coinsCp, candidates: [] };
}

const FIGHTER_KIT: StartingEquipment = {
  groups: [
    { choice: true, options: [
      { key: 'a', entries: [item('Chain Mail', 'item-chain-mail', 'HEAVY')] },
      { key: 'b', entries: [item('Leather Armor', 'item-leather', 'LIGHT'), item('Longbow', 'item-longbow')] },
    ] },
    { choice: true, options: [
      { key: 'a', entries: [
        { kind: 'PICK', name: 'Martial weapon', quantity: 1, coinsCp: 0, candidates: [
          { name: 'Longsword', itemId: 'item-longsword' }, { name: 'Warhammer', itemId: 'item-warhammer' },
        ] },
        item('Shield', 'item-shield', 'SHIELD'),
      ] },
      { key: 'b', entries: [{ kind: 'PICK', name: 'Martial weapon', quantity: 2, coinsCp: 0, candidates: [] }] },
    ] },
  ],
  goldAlternative: { count: 5, sides: 4, multiplier: 10 },
};

const SOLDIER_KIT: StartingEquipment = {
  groups: [
    { choice: false, options: [{ key: '_', entries: [
      { kind: 'ITEM', name: 'Insignia of rank', quantity: 1, coinsCp: 0, candidates: [] },
      item('Common Clothes', 'item-common-clothes'),
      item('Pouch', 'item-pouch', null, 1, 1000),
    ] }] },
    { choice: true, options: [
      { key: 'a', entries: [item('Bone dice set', 'item-dice-set')] },
      { key: 'b', entries: [item('Playing Card Set', 'item-playing-card-set')] },
    ] },
  ],
  goldAlternative: null,
};

// ── Spells ──

function spell(name: string, level: number, school: string, classes: string[]): Spell {
  return {
    id: `spell-${name.toLowerCase().replace(/\W+/g, '-')}`, name, level, school,
    castingTime: '1 action', rangeDistance: '60 feet', duration: 'Instantaneous',
    components: { verbal: true, somatic: true }, concentration: false, ritual: false,
    description: `${name}.`, higherLevels: null, classes, damageType: null, damageDice: null, saveAbility: null,
    source: 'PHB',
  };
}

const SPELLS: Spell[] = [
  spell('Fire Bolt', 0, 'Evocation', ['Wizard']),
  spell('Mage Hand', 0, 'Conjuration', ['Wizard']),
  spell('Prestidigitation', 0, 'Transmutation', ['Wizard']),
  spell('Ray of Frost', 0, 'Evocation', ['Wizard']),
  spell('Guidance', 0, 'Divination', ['Cleric']),
  spell('Light', 0, 'Evocation', ['Cleric', 'Wizard']),
  spell('Sacred Flame', 0, 'Evocation', ['Cleric']),
  spell('Thaumaturgy', 0, 'Transmutation', ['Cleric']),
  spell('Burning Hands', 1, 'Evocation', ['Wizard']),
  spell('Detect Magic', 1, 'Divination', ['Cleric', 'Wizard']),
  spell('Mage Armor', 1, 'Abjuration', ['Wizard']),
  spell('Magic Missile', 1, 'Evocation', ['Wizard']),
  spell('Shield', 1, 'Abjuration', ['Wizard']),
  spell('Sleep', 1, 'Enchantment', ['Wizard']),
  spell('Thunderwave', 1, 'Evocation', ['Wizard']),
  spell('Bless', 1, 'Enchantment', ['Cleric']),
  spell('Cure Wounds', 1, 'Evocation', ['Cleric']),
];

// ── Driving the wizard ──

let created: unknown;

beforeEach(() => {
  localStorage.clear();
  setupAuthToken();
  created = undefined;
  server.use(
    http.get('/api/reference/races', () => HttpResponse.json([human, variantHuman, elf])),
    http.get('/api/reference/classes', () => HttpResponse.json([fighter, wizard, cleric])),
    http.get('/api/reference/classes/:classId/subclasses', ({ params }) =>
      HttpResponse.json(SUBCLASSES[params.classId as string] ?? [])),
    http.get('/api/reference/backgrounds', () => HttpResponse.json([soldier, sage, acolyte])),
    http.get('/api/reference/spells', ({ request }) => {
      const query = new URL(request.url).searchParams;
      const className = query.get('className');
      const level = query.get('level');
      const name = query.get('name')?.toLowerCase();
      const content = SPELLS.filter(s => (!className || s.classes?.includes(className))
        && (level === null || s.level === Number(level)) && (!name || s.name.toLowerCase().includes(name)));
      return HttpResponse.json({
        content, totalElements: content.length, totalPages: 1, number: 0, size: 50,
      } satisfies PageResponse<Spell>);
    }),
    // The body is read here, as the wizard sent it
    http.post('/api/characters', async ({ request }) => {
      created = await request.json();
      return HttpResponse.json(makeMockCharacter('char-new', 'New', 'Fighter', 1));
    }),
  );
});

function renderWizard() {
  window.history.pushState({}, '', '/player/characters/new');
  return render(
    <Routes>
      <Route path="/player/characters/new" element={<CharacterCreateWizard />} />
      <Route path="/player/characters/:id" element={<p>Character sheet</p>} />
    </Routes>,
  );
}

async function next(user: UserEvent) {
  const button = screen.getByRole('button', { name: /Next/ });
  await waitFor(() => expect(button).toBeEnabled());
  await user.click(button);
}

async function basicInfo(user: UserEvent, name: string, alignment: string, level = 1) {
  await user.type(screen.getByPlaceholderText('Enter character name'), name);
  await user.click(screen.getByRole('button', { name: alignment }));
  for (let l = 1; l < level; l++) await user.click(screen.getByRole('button', { name: 'Increase starting level' }));
  expect(screen.getByLabelText('Starting level')).toHaveValue(String(level));
}

/** Assigns the standard array, in the order STR, DEX, CON, INT, WIS, CHA. */
async function standardArray(user: UserEvent, values: [number, number, number, number, number, number]) {
  await user.click(screen.getByRole('button', { name: 'Standard Array' }));
  const selects = screen.getAllByRole('combobox');
  expect(selects).toHaveLength(6);
  for (let i = 0; i < 6; i++) await user.selectOptions(selects[i], String(values[i]));
}

async function clickAll(user: UserEvent, names: string[]) {
  for (const name of names) await user.click(screen.getByRole('button', { name }));
}

async function create(user: UserEvent) {
  await user.click(screen.getByRole('button', { name: /Create Character/ }));
  expect(await screen.findByText('Character sheet')).toBeInTheDocument();
  expect(created).toBeDefined();
  return created;
}

// Each flow clicks through every step, which can take longer than the default 5 s while the whole suite runs
describe('CharacterCreateWizard requests', { timeout: 20_000 }, () => {
  it('Fighter 1: a Human soldier', async () => {
    server.use(
      http.get('/api/reference/classes/cls-fighter/starting-equipment', () => HttpResponse.json(FIGHTER_KIT)),
      http.get('/api/reference/backgrounds/bg-soldier/starting-equipment', () => HttpResponse.json(SOLDIER_KIT)),
      // The server's AC: Chain Mail 16 and a Shield 2 when both are worn
      http.post('/api/characters/armour-class-preview', async ({ request }) => {
        const worn = ((await request.json()) as CharacterCreateRequest).equipment?.filter(e => e.equipped).length ?? 0;
        return HttpResponse.json(worn === 2
          ? { total: 18, parts: [{ label: 'Chain Mail', amount: 16 }, { label: 'Shield', amount: 2 }], warnings: [], armourEntries: [0, 2] }
          : { total: 12, parts: [{ label: 'Unarmoured', amount: 10 }, { label: 'DEX', amount: 2 }], warnings: [], armourEntries: [] });
      }),
    );
    const user = userEvent.setup();
    renderWizard();

    await basicInfo(user, 'Brienne', 'Lawful Good');
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Human/ }));
    await clickAll(user, ['Giant']);
    await next(user);
    await standardArray(user, [15, 13, 14, 8, 12, 10]);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Fighter/ }));
    await clickAll(user, ['Acrobatics', 'Perception']);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Soldier/ }));
    await next(user);

    // Starting equipment (PHB p.143): (a) chain mail, (a) a martial weapon and a shield, and the Soldier's kit
    expect(await screen.findByText('Starting Equipment')).toBeInTheDocument();
    await user.click(await screen.findByRole('radio', { name: '(a) Chain Mail' }));
    await user.click(screen.getByRole('radio', { name: '(a) Martial weapon of your choice, Shield' }));
    await user.selectOptions(screen.getByLabelText('Martial weapon'), 'item-longsword');
    await user.click(screen.getByRole('radio', { name: '(b) Playing Card Set' }));
    expect(await screen.findByText('Armour Class 18')).toBeInTheDocument();
    await next(user);
    expect(screen.queryByText('Choose Spells')).not.toBeInTheDocument();

    expect(await create(user)).toMatchInlineSnapshot(`
      {
        "abilityScoreMethod": "standard",
        "alignment": "Lawful Good",
        "armorProficiencies": [
          "Light",
          "Medium",
          "Heavy",
          "Shield",
        ],
        "backgroundId": "bg-soldier",
        "charisma": 11,
        "classId": "cls-fighter",
        "constitution": 15,
        "currency": {
          "cp": 0,
          "ep": 0,
          "gp": 10,
          "pp": 0,
          "sp": 0,
        },
        "dexterity": 14,
        "equipment": [
          {
            "equipped": true,
            "itemId": "item-chain-mail",
            "name": "Chain Mail",
            "quantity": 1,
          },
          {
            "equipped": false,
            "itemId": "item-longsword",
            "name": "Longsword",
            "quantity": 1,
          },
          {
            "equipped": true,
            "itemId": "item-shield",
            "name": "Shield",
            "quantity": 1,
          },
          {
            "equipped": false,
            "name": "Insignia of rank",
            "quantity": 1,
          },
          {
            "equipped": false,
            "itemId": "item-common-clothes",
            "name": "Common Clothes",
            "quantity": 1,
          },
          {
            "equipped": false,
            "itemId": "item-pouch",
            "name": "Pouch",
            "quantity": 1,
          },
          {
            "equipped": false,
            "itemId": "item-playing-card-set",
            "name": "Playing Card Set",
            "quantity": 1,
          },
        ],
        "intelligence": 9,
        "languageProficiencies": [
          "Common",
          "Giant",
        ],
        "level": 1,
        "name": "Brienne",
        "raceId": "race-human",
        "racialAbilityBonuses": [
          {
            "ability": "STR",
            "bonus": 1,
          },
          {
            "ability": "DEX",
            "bonus": 1,
          },
          {
            "ability": "CON",
            "bonus": 1,
          },
          {
            "ability": "INT",
            "bonus": 1,
          },
          {
            "ability": "WIS",
            "bonus": 1,
          },
          {
            "ability": "CHA",
            "bonus": 1,
          },
        ],
        "savingThrowProficiencies": [
          "STR",
          "CON",
        ],
        "skillProficiencies": [
          "Acrobatics",
          "Perception",
          "Athletics",
          "Intimidation",
        ],
        "strength": 16,
        "toolProficiencies": [
          "Vehicles (Land)",
        ],
        "weaponProficiencies": [
          "Simple",
          "Martial",
        ],
        "wisdom": 13,
      }
    `);
  });

  it('Wizard 1: a High Elf sage with three cantrips and six spellbook spells', async () => {
    const user = userEvent.setup();
    renderWizard();

    await basicInfo(user, 'Elminster', 'Chaotic Good');
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^High Elf/ }));
    await next(user);
    await standardArray(user, [8, 14, 13, 15, 12, 10]);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Wizard/ }));
    await clickAll(user, ['Investigation', 'Medicine']);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Sage/ }));
    await clickAll(user, ['Draconic', 'Dwarvish']);
    await next(user);
    await next(user); // Starting equipment: none for this class and background

    expect(await screen.findByText('Choose Spells')).toBeInTheDocument();
    for (const name of ['Fire Bolt', 'Mage Hand', 'Prestidigitation']) {
      await user.click(await screen.findByRole('button', { name: new RegExp(`^${name}`) }));
    }
    for (const name of ['Burning Hands', 'Detect Magic', 'Mage Armor', 'Magic Missile', 'Shield', 'Sleep']) {
      await user.click(await screen.findByRole('button', { name: new RegExp(`^${name}`) }));
    }
    expect(screen.getByText('3/3 selected')).toBeInTheDocument();
    expect(screen.getByText('6/6 selected')).toBeInTheDocument();
    await next(user);

    expect(await create(user)).toMatchInlineSnapshot(`
      {
        "abilityScoreMethod": "standard",
        "alignment": "Chaotic Good",
        "backgroundId": "bg-sage",
        "charisma": 10,
        "classId": "cls-wizard",
        "constitution": 13,
        "dexterity": 16,
        "intelligence": 16,
        "languageProficiencies": [
          "Common",
          "Elvish",
          "Draconic",
          "Dwarvish",
        ],
        "level": 1,
        "name": "Elminster",
        "raceId": "race-high-elf",
        "racialAbilityBonuses": [
          {
            "ability": "DEX",
            "bonus": 2,
          },
          {
            "ability": "INT",
            "bonus": 1,
          },
        ],
        "savingThrowProficiencies": [
          "INT",
          "WIS",
        ],
        "skillProficiencies": [
          "Perception",
          "Investigation",
          "Medicine",
          "Arcana",
          "History",
        ],
        "spellsKnown": [
          {
            "level": 0,
            "name": "Fire Bolt",
            "source": "class:Wizard",
          },
          {
            "level": 0,
            "name": "Mage Hand",
            "source": "class:Wizard",
          },
          {
            "level": 0,
            "name": "Prestidigitation",
            "source": "class:Wizard",
          },
          {
            "level": 1,
            "name": "Burning Hands",
            "source": "class:Wizard",
          },
          {
            "level": 1,
            "name": "Detect Magic",
            "source": "class:Wizard",
          },
          {
            "level": 1,
            "name": "Mage Armor",
            "source": "class:Wizard",
          },
          {
            "level": 1,
            "name": "Magic Missile",
            "source": "class:Wizard",
          },
          {
            "level": 1,
            "name": "Shield",
            "source": "class:Wizard",
          },
          {
            "level": 1,
            "name": "Sleep",
            "source": "class:Wizard",
          },
        ],
        "strength": 8,
        "weaponProficiencies": [
          "Longsword",
          "Shortsword",
          "Shortbow",
          "Longbow",
          "Dagger",
          "Dart",
          "Sling",
          "Quarterstaff",
          "Light crossbow",
        ],
        "wisdom": 12,
      }
    `);
  });

  it('Cleric 1: a Variant Human acolyte of the Life Domain, its feat left to the sheet', async () => {
    const user = userEvent.setup();
    renderWizard();

    await basicInfo(user, 'Jhessail', 'Neutral Good');
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Variant Human/ }));
    // The race's skill and language, then its two +1s, to WIS and CON
    await clickAll(user, ['Perception', 'Halfling']);
    await user.click(screen.getAllByRole('button', { name: 'WIS' })[0]);
    await user.click(screen.getAllByRole('button', { name: 'CON' })[1]);
    expect(screen.getByText(/grants a feat of your choice/)).toBeInTheDocument();
    await next(user);
    await standardArray(user, [13, 8, 14, 10, 15, 12]);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Cleric/ }));
    await user.click(await screen.findByRole('button', { name: /^Life Domain/ }));
    await clickAll(user, ['Medicine', 'Persuasion']);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Acolyte/ }));
    await clickAll(user, ['Celestial', 'Elvish']);
    await next(user);
    await next(user); // Starting equipment: none for this class and background

    expect(await screen.findByText('Choose Spells')).toBeInTheDocument();
    for (const name of ['Guidance', 'Sacred Flame', 'Thaumaturgy']) {
      await user.click(await screen.findByRole('button', { name: new RegExp(`^${name}`) }));
    }
    expect(screen.getByText('3/3 selected')).toBeInTheDocument();
    await next(user);

    expect(await create(user)).toMatchInlineSnapshot(`
      {
        "abilityScoreMethod": "standard",
        "alignment": "Neutral Good",
        "armorProficiencies": [
          "Light",
          "Medium",
          "Shield",
        ],
        "backgroundId": "bg-acolyte",
        "charisma": 12,
        "classId": "cls-cleric",
        "constitution": 15,
        "dexterity": 8,
        "intelligence": 10,
        "languageProficiencies": [
          "Common",
          "Halfling",
          "Celestial",
          "Elvish",
        ],
        "level": 1,
        "name": "Jhessail",
        "raceId": "race-variant-human",
        "racialAbilityBonuses": [
          {
            "ability": "WIS",
            "bonus": 1,
          },
          {
            "ability": "CON",
            "bonus": 1,
          },
        ],
        "savingThrowProficiencies": [
          "WIS",
          "CHA",
        ],
        "skillProficiencies": [
          "Perception",
          "Medicine",
          "Persuasion",
          "Insight",
          "Religion",
        ],
        "spellsKnown": [
          {
            "level": 0,
            "name": "Guidance",
            "source": "class:Cleric",
          },
          {
            "level": 0,
            "name": "Sacred Flame",
            "source": "class:Cleric",
          },
          {
            "level": 0,
            "name": "Thaumaturgy",
            "source": "class:Cleric",
          },
        ],
        "strength": 13,
        "subclassId": "sc-life",
        "weaponProficiencies": [
          "Simple",
        ],
        "wisdom": 16,
      }
    `);
  });

  it('Fighter 3 / Wizard 2: a Human soldier multiclassed into Wizard', async () => {
    const user = userEvent.setup();
    renderWizard();

    await basicInfo(user, 'Eldric', 'Lawful Neutral', 5);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Human/ }));
    await clickAll(user, ['Elvish']);
    await next(user);
    await standardArray(user, [15, 12, 14, 13, 10, 8]);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Fighter/ }));
    await user.click(screen.getByRole('button', { name: /Multiclassing Options/ }));
    // The second Wizard button is the multiclass option, after the class list's
    await user.click(screen.getAllByRole('button', { name: /^Wizard/ })[1]);
    expect(await screen.findByText('Added to multiclass')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Decrease fighter level' }));
    await user.click(screen.getByRole('button', { name: 'Increase wizard level' }));
    expect(screen.getByLabelText('Fighter level')).toHaveValue('3');
    expect(screen.getByLabelText('Wizard level')).toHaveValue('2');
    await clickAll(user, ['Athletics', 'Survival']);
    await next(user);
    await user.click(await screen.findByRole('button', { name: /^Soldier/ }));
    await next(user);
    await next(user); // Starting equipment: none for this class and background
    // The first class casts no spells, so there is no Spells step
    expect(screen.queryByText('Choose Spells')).not.toBeInTheDocument();

    expect(await create(user)).toMatchInlineSnapshot(`
      {
        "abilityScoreMethod": "standard",
        "alignment": "Lawful Neutral",
        "armorProficiencies": [
          "Light",
          "Medium",
          "Heavy",
          "Shield",
        ],
        "backgroundId": "bg-soldier",
        "charisma": 9,
        "classId": "cls-fighter",
        "constitution": 15,
        "dexterity": 13,
        "intelligence": 14,
        "languageProficiencies": [
          "Common",
          "Elvish",
        ],
        "level": 5,
        "multiclassClassEntries": [
          {
            "classId": "cls-fighter",
            "level": 3,
            "subclassId": null,
          },
          {
            "classId": "cls-wizard",
            "level": 2,
            "subclassId": null,
          },
        ],
        "name": "Eldric",
        "raceId": "race-human",
        "racialAbilityBonuses": [
          {
            "ability": "STR",
            "bonus": 1,
          },
          {
            "ability": "DEX",
            "bonus": 1,
          },
          {
            "ability": "CON",
            "bonus": 1,
          },
          {
            "ability": "INT",
            "bonus": 1,
          },
          {
            "ability": "WIS",
            "bonus": 1,
          },
          {
            "ability": "CHA",
            "bonus": 1,
          },
        ],
        "savingThrowProficiencies": [
          "STR",
          "CON",
        ],
        "skillProficiencies": [
          "Athletics",
          "Survival",
          "Intimidation",
        ],
        "strength": 16,
        "toolProficiencies": [
          "Vehicles (Land)",
        ],
        "weaponProficiencies": [
          "Simple",
          "Martial",
        ],
        "wisdom": 11,
      }
    `);
  });
});
