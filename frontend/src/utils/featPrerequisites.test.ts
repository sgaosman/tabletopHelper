import { describe, it, expect } from 'vitest';
import { checkFeatPrerequisites, isRepeatableFeat, parseFeatEffects, parseAbilityScoreIncrease } from './featPrerequisites';
import type { PlayerCharacter } from '../types/character';
import type { Feat } from '../types/reference';

function mockCharacter(overrides: Partial<PlayerCharacter> = {}): PlayerCharacter {
  return {
    id: 'test-id',
    userId: 'user-id',
    ownerDisplayName: 'Test User',
    raceId: 'race-id',
    raceName: 'Human',
    name: 'Test Character',
    level: 5,
    experiencePoints: 0,
    strength: 14,
    dexterity: 12,
    constitution: 14,
    intelligence: 10,
    wisdom: 12,
    charisma: 10,
    hpMax: 40,
    hpCurrent: 40,
    hpTemp: 0,
    armourClass: 16,
    initiativeBonus: 1,
    speed: 30,
    proficiencyBonus: 3,
    deathSaveSuccesses: 0,
    deathSaveFailures: 0,
    spellcasting: [],
    abilityScoreMaximums: { strength: 20, dexterity: 20, constitution: 20, intelligence: 20, wisdom: 20, charisma: 20 },
    baseAbilityScores: { strength: 14, dexterity: 12, constitution: 14, intelligence: 10, wisdom: 12, charisma: 10 },
    damageResistances: [],
    damageImmunities: [],
    damageVulnerabilities: [],
    dmAdjustments: [],
    pendingChoices: [],
    isActive: true,
    inLiveEncounter: false,
    dead: false,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
    ...overrides,
  };
}

function mockFeat(overrides: Partial<Feat> = {}): Feat {
  return {
    id: 'feat-id',
    name: 'Test Feat',
    source: 'PHB',
    prerequisite: '',
    description: 'A test feat',
    abilityScoreIncrease: '',
    grantsFeatures: '',
    effects: '',
    ...overrides,
  };
}

describe('featPrerequisites', () => {
  // 20.1 checkFeatPrerequisites: ability score met
  describe('checkFeatPrerequisites', () => {
    it('returns eligible when ability score meets requirement', () => {
      const feat = mockFeat({
        name: 'Heavily Armored',
        prerequisite: JSON.stringify([{ ability: [{ strength: 13 }] }]),
      });
      const character = mockCharacter({ strength: 14 });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(true);
      expect(result.reason).toBeNull();
    });

    // 20.2 checkFeatPrerequisites: ability score not met
    it('returns ineligible with reason when ability score is below requirement', () => {
      const feat = mockFeat({
        name: 'Heavily Armored',
        prerequisite: JSON.stringify([{ ability: [{ strength: 13 }] }]),
      });
      const character = mockCharacter({ strength: 10 });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(false);
      expect(result.reason).toContain('Strength');
      expect(result.reason).toContain('13');
    });

    // 20.3 checkFeatPrerequisites: proficiency prerequisite
    it('returns eligible when character has required proficiency', () => {
      const feat = mockFeat({
        name: 'Heavy Armor Master',
        prerequisite: JSON.stringify([{ proficiency: ['heavy armor'] }]),
      });
      const character = mockCharacter({
        armorProficiencies: ['Light Armor', 'Medium Armor', 'Heavy Armor', 'Shields'],
      });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(true);
    });

    it('returns ineligible when character lacks required proficiency', () => {
      const feat = mockFeat({
        name: 'Heavy Armor Master',
        prerequisite: JSON.stringify([{ proficiency: ['heavy armor'] }]),
      });
      const character = mockCharacter({
        armorProficiencies: ['Light Armor'],
      });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(false);
      expect(result.reason).toContain('Proficiency');
    });

    // 20.4 checkFeatPrerequisites: spellcasting prerequisite
    it('returns ineligible when spellcasting is required but character is non-caster', () => {
      const feat = mockFeat({
        name: 'Ritual Caster',
        prerequisite: JSON.stringify([{ spellcasting: true }]),
      });
      const character = mockCharacter({ spellcastingAbility: undefined });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(false);
      expect(result.reason).toContain('Spellcasting');
    });

    it('returns eligible when spellcasting is required and character is a caster', () => {
      const feat = mockFeat({
        name: 'Ritual Caster',
        prerequisite: JSON.stringify([{ spellcasting: true }]),
      });
      const character = mockCharacter({ spellcastingAbility: 'intelligence' });

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(true);
    });

    // 20.5 checkFeatPrerequisites: no prerequisites always passes
    it('returns eligible when feat has no prerequisites (null)', () => {
      const feat = mockFeat({ name: 'Alert', prerequisite: '' });
      const character = mockCharacter();

      const result = checkFeatPrerequisites(feat, character);
      expect(result.eligible).toBe(true);
      expect(result.reason).toBeNull();
    });
  });

  // 20.6 parseFeatEffects: ability score increase and proficiencies
  describe('parseFeatEffects', () => {
    it('parses effects with ASI data', () => {
      const feat = mockFeat({
        effects: JSON.stringify({
          armorProficiencies: ['Medium Armor', 'Shields'],
          speedBonus: 10,
        }),
      });

      const result = parseFeatEffects(feat);
      expect(result).not.toBeNull();
      expect(result!.armorProficiencies).toEqual(['Medium Armor', 'Shields']);
      expect(result!.speedBonus).toBe(10);
    });

    // 20.7 parseFeatEffects: proficiency list
    it('parses effects with multiple proficiency types', () => {
      const feat = mockFeat({
        effects: JSON.stringify({
          armorProficiencies: ['Medium Armor', 'Shields'],
          weaponProficiencies: ['Martial Weapons'],
          skillProficiencies: ['Athletics'],
        }),
      });

      const result = parseFeatEffects(feat);
      expect(result).not.toBeNull();
      expect(result!.armorProficiencies).toEqual(['Medium Armor', 'Shields']);
      expect(result!.weaponProficiencies).toEqual(['Martial Weapons']);
      expect(result!.skillProficiencies).toEqual(['Athletics']);
    });

    it('returns null when effects field is empty', () => {
      const feat = mockFeat({ effects: '' });
      const result = parseFeatEffects(feat);
      expect(result).toBeNull();
    });

    it('handles already-parsed effects object', () => {
      const effectsObj = { speedBonus: 10, initiativeBonus: 5 };
      const feat = mockFeat({ effects: effectsObj as unknown as string });
      const result = parseFeatEffects(feat);
      expect(result).not.toBeNull();
      expect(result!.speedBonus).toBe(10);
      expect(result!.initiativeBonus).toBe(5);
    });
  });

  // 20.8 parseAbilityScoreIncrease: fixed and choice-based
  describe('parseAbilityScoreIncrease', () => {
    it('parses fixed ASI (Heavily Armored: +1 STR)', () => {
      const feat = mockFeat({
        abilityScoreIncrease: JSON.stringify([{ strength: 1 }]),
      });

      const result = parseAbilityScoreIncrease(feat);
      expect(result).not.toBeNull();
      expect(result!.fixed).toEqual({ strength: 1 });
      expect(result!.choose).toBeUndefined();
    });

    it('parses choice-based ASI (Resilient: choose one ability +1)', () => {
      const feat = mockFeat({
        abilityScoreIncrease: JSON.stringify([
          { choose: { from: ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'], amount: 1 } },
        ]),
      });

      const result = parseAbilityScoreIncrease(feat);
      expect(result).not.toBeNull();
      expect(result!.fixed).toEqual({});
      expect(result!.choose).not.toBeNull();
      expect(result!.choose!.from).toHaveLength(6);
      expect(result!.choose!.amount).toBe(1);
    });

    it('returns null when abilityScoreIncrease is empty', () => {
      const feat = mockFeat({ abilityScoreIncrease: '' });
      const result = parseAbilityScoreIncrease(feat);
      expect(result).toBeNull();
    });
  });

  describe('isRepeatableFeat', () => {
    it('is true for a feat whose text says it can be selected multiple times, as Elemental Adept says', () => {
      expect(isRepeatableFeat(mockFeat({
        name: 'Elemental Adept',
        description: 'You can select this feat multiple times. Each time you do so, you must choose a different damage type.',
      }))).toBe(true);
    });

    it('is false for any other feat, or one without text', () => {
      expect(isRepeatableFeat(mockFeat({ name: 'Lucky', description: 'You have inexplicable luck.' }))).toBe(false);
      expect(isRepeatableFeat(mockFeat({ description: null }))).toBe(false);
    });
  });
});

/*
 * Feats as GET /api/reference/feats sends them. The server keeps prerequisite, abilityScoreIncrease,
 * grantsFeatures and effects as JSONB and writes them out raw, so each arrives already parsed (an array or an
 * object), not as JSON text, although the generated type says string. The values are the ones FeatLoader stores
 * from data/5etools/feats.json and data/content/feat-effects.json: an ability minimum is a string such as
 * "Strength 13", a proficiency is a string such as "Armor: medium", and the entries of the prerequisite array are
 * alternatives, any one of which is enough.
 */
function apiFeat(fields: {
  name: string; source: string; description: string;
  prerequisite?: unknown; abilityScoreIncrease?: unknown; grantsFeatures?: unknown; effects?: unknown;
}): Feat {
  return {
    id: `feat-${fields.name.toLowerCase().replace(/\W+/g, '-')}`,
    name: fields.name,
    source: fields.source,
    description: fields.description,
    prerequisite: (fields.prerequisite ?? null) as string | null,
    abilityScoreIncrease: (fields.abilityScoreIncrease ?? null) as string | null,
    grantsFeatures: (fields.grantsFeatures ?? null) as string | null,
    effects: (fields.effects ?? {}) as string,
  };
}

const GRAPPLER = apiFeat({
  name: 'Grappler', source: 'PHB',
  prerequisite: [{ ability: ['Strength 13'] }],
  description: "You've developed the skills necessary to hold your own in close-quarters grappling. You gain the "
    + 'following benefits:\n- You have advantage on attack rolls against a creature you are grappling.\n- You can '
    + 'use your action to try to pin a creature grappled by you. To do so, make another grapple check. If you '
    + 'succeed, you and the creature are both restrained until the grapple ends.',
});

const HEAVILY_ARMORED = apiFeat({
  name: 'Heavily Armored', source: 'PHB',
  prerequisite: [{ proficiency: ['Armor: medium'] }],
  abilityScoreIncrease: [{ STR: 1 }],
  effects: { armorProficiencies: [{ heavy: true }] },
  description: 'You have trained to master the use of heavy armor, gaining the following benefits:\n'
    + '- You gain proficiency with heavy armor.',
});

// Its two prerequisite entries are alternatives: proficiency with martial weapons, or the Giant Foundling background
const STRIKE_OF_THE_GIANTS = apiFeat({
  name: 'Strike of the Giants', source: 'BGG',
  prerequisite: [{ proficiency: ['Weapon Group: Martial'] }, { background: ['Giant Foundling'] }],
  description: 'You have absorbed primeval magic that gives you an echo of the might of giants.',
});

const ELEMENTAL_ADEPT = apiFeat({
  name: 'Elemental Adept', source: 'PHB',
  prerequisite: [{ spellcasting: true }],
  effects: { repeatable: true },
  description: 'When you gain this feat, choose one of the following damage types: acid, cold, fire, lightning, '
    + 'or thunder.\nSpells you cast ignore resistance to damage of the chosen type. In addition, when you roll '
    + 'damage for a spell you cast that deals damage of that type, you can treat any 1 on a damage die as a 2.\n'
    + 'You can select this feat multiple times. Each time you do so, you must choose a different damage type.',
});

const LUCKY = apiFeat({
  name: 'Lucky', source: 'PHB',
  effects: { resource: { name: 'Luck Points', maxUses: 3, resetOn: 'longRest' } },
  description: 'You have inexplicable luck that seems to kick in at just the right moment.\nYou have 3 luck points.',
});

const fighter = (overrides: Partial<PlayerCharacter> = {}) => mockCharacter({
  armorProficiencies: ['Light Armor', 'Medium Armor', 'Heavy Armor', 'Shields'],
  weaponProficiencies: ['Simple Weapons', 'Martial Weapons'],
  background: 'Soldier',
  ...overrides,
});
const wizard = (overrides: Partial<PlayerCharacter> = {}) => mockCharacter({
  armorProficiencies: [], weaponProficiencies: ['Daggers', 'Quarterstaffs'], background: 'Sage',
  spellcastingAbility: 'INT', ...overrides,
});

describe('checkFeatPrerequisites with feats as the API sends them', () => {
  it.each([
    ['Grappler, STR 13', GRAPPLER, fighter({ strength: 13 })],
    ['Heavily Armored, proficient with medium armour', HEAVILY_ARMORED, fighter()],
    ['Strike of the Giants, proficient with martial weapons', STRIKE_OF_THE_GIANTS, fighter()],
    ['Strike of the Giants, Giant Foundling background', STRIKE_OF_THE_GIANTS, wizard({ background: 'Giant Foundling' })],
    ['Lucky, no prerequisite', LUCKY, wizard()],
  ])('%s: eligible', (_case, feat, character) => {
    expect(checkFeatPrerequisites(feat, character).eligible).toBe(true);
  });

  /*
   * Today's wrong answers, kept so that a change to them shows. Each case asserts today's answer, not the correct
   * one; the workstream that fixes checkFeatPrerequisites moves each case into the assertions above and empties
   * this list.
   */
  const KNOWN_WRONG: Array<{ name: string; feat: Feat; character: PlayerCharacter; correct: boolean; todays: boolean }> = [
    { name: 'Grappler, STR 12', feat: GRAPPLER, character: fighter({ strength: 12 }), correct: false, todays: true },
    {
      name: 'Heavily Armored, proficient with light armour only', feat: HEAVILY_ARMORED,
      character: fighter({ armorProficiencies: ['Light Armor'] }), correct: false, todays: true,
    },
    {
      name: 'Strike of the Giants, neither martial weapons nor Giant Foundling', feat: STRIKE_OF_THE_GIANTS,
      character: wizard(), correct: false, todays: true,
    },
    {
      name: 'Elemental Adept, no spellcasting', feat: ELEMENTAL_ADEPT,
      character: fighter({ spellcastingAbility: null }), correct: false, todays: true,
    },
  ];

  it.each(KNOWN_WRONG)('known wrong today: $name (correct: $correct, today: $todays)', ({ feat, character, correct, todays }) => {
    expect(todays).not.toBe(correct);
    expect(checkFeatPrerequisites(feat, character).eligible).toBe(todays);
  });
});

describe('isRepeatableFeat with feats as the API sends them', () => {
  it('is true for Elemental Adept, whose effects say repeatable and whose text says it can be selected multiple times', () => {
    expect(isRepeatableFeat(ELEMENTAL_ADEPT)).toBe(true);
  });

  it.each([GRAPPLER, HEAVILY_ARMORED, STRIKE_OF_THE_GIANTS, LUCKY])('is false for $name', feat => {
    expect(isRepeatableFeat(feat)).toBe(false);
  });
});

/*
 * The same stored values as JSON text, which the API doesn't send but checkFeatPrerequisites expects: the answers
 * it gives once it no longer fails to parse an array and lets every feat through. An ability minimum, "Strength 13",
 * is read as an object of ability scores, so it is never checked; "Armor: medium" is looked for as a substring of
 * "Medium Armor"; and every prerequisite entry must be met, with a background never counted, rather than any one.
 */
describe('checkFeatPrerequisites with the stored values as JSON text', () => {
  const asText = (feat: Feat): Feat => ({ ...feat, prerequisite: JSON.stringify(feat.prerequisite) });

  it('Strike of the Giants, neither martial weapons nor Giant Foundling: ineligible', () => {
    expect(checkFeatPrerequisites(asText(STRIKE_OF_THE_GIANTS), wizard()).eligible).toBe(false);
  });

  // Today's wrong answers, asserted as they are; the same workstream fixes them and empties this list
  const KNOWN_WRONG: Array<{ name: string; feat: Feat; character: PlayerCharacter; correct: boolean; todays: boolean }> = [
    { name: 'Grappler, STR 12', feat: asText(GRAPPLER), character: fighter({ strength: 12 }), correct: false, todays: true },
    {
      name: 'Heavily Armored, proficient with medium armour', feat: asText(HEAVILY_ARMORED),
      character: fighter(), correct: true, todays: false,
    },
    {
      name: 'Strike of the Giants, proficient with martial weapons', feat: asText(STRIKE_OF_THE_GIANTS),
      character: fighter(), correct: true, todays: false,
    },
    {
      name: 'Strike of the Giants, Giant Foundling background', feat: asText(STRIKE_OF_THE_GIANTS),
      character: wizard({ background: 'Giant Foundling' }), correct: true, todays: false,
    },
  ];

  it.each(KNOWN_WRONG)('known wrong today: $name (correct: $correct, today: $todays)', ({ feat, character, correct, todays }) => {
    expect(todays).not.toBe(correct);
    expect(checkFeatPrerequisites(feat, character).eligible).toBe(todays);
  });
});
