import { http, HttpResponse } from 'msw';
import { CLASS_TABLES } from './classTables';
import { GLOSSARY } from './glossary';
import type { AuthResponse } from '../../types/auth';
import type { Campaign, CampaignMember, CampaignSettingsRequest } from '../../types/campaign';
import type {
  ArmourClassBreakdown, DmAdjustment, EligibleClassResponse, FeatOptionResponse, LevelUpResponse, PendingChoice, PlayerCharacter, ShortRestResponse,
} from '../../types/character';
import type {
  AttackOption, CastSpellResponse, CombatLogEntry, MonsterActionResponse, MonsterActionTemplate, MultiattackOption,
  SpellOption,
} from '../../types/combat';
import type {
  CombatResultPreview, ConditionEntry, Encounter, EncounterParticipant, ParticipantType, ResourcePoolEntry, SpellEffect,
  TurnEntry,
} from '../../types/encounter';
import type { Monster } from '../../types/monster';
import type {
  Background, CharacterClassRef, Condition, Feat, Item, OptionalFeature, PageResponse, Race, Spell, SpellTargetingInfo,
  StartingEquipment, Subclass,
} from '../../types/reference';

const BASE = '/api';

function makeTimestamp() { return new Date().toISOString(); }

// --- Auth ---
export const authHandlers = [
  http.post(`${BASE}/auth/login`, async () => {
    return HttpResponse.json({
      userId: 'user-001', username: 'testuser', displayName: 'Test User',
      accessToken: 'mock-access-token',
    } satisfies AuthResponse);
  }),

  http.post(`${BASE}/auth/register`, async () => {
    return HttpResponse.json({
      userId: 'user-002', username: 'newuser', displayName: 'New User',
      accessToken: 'mock-access-token-new',
    } satisfies AuthResponse);
  }),

  // No refresh cookie unless a test signs in with setupAuthToken() (test-utils), which overrides this
  http.post(`${BASE}/auth/refresh`, async () => {
    return HttpResponse.json({ error: 'Your session has ended. Please sign in again.' }, { status: 401 });
  }),

  http.post(`${BASE}/auth/logout`, async () => {
    return new HttpResponse(null, { status: 204 });
  }),
];

// --- Shared data builders ---
export function makeCampaign(id: string, name: string, dmUserId = 'user-001', membersCount = 1): Campaign {
  const members: CampaignMember[] = [
    { userId: dmUserId, username: 'testuser', displayName: 'Test User', role: 'DM', joinedAt: makeTimestamp() },
  ];
  for (let i = 1; i < membersCount; i++) {
    members.push({ userId: `plr-00${i}`, username: `player${i}`, displayName: `Player ${i}`, role: 'PLAYER' as const, joinedAt: makeTimestamp() });
  }
  return {
    id, name, dmUserId, dmDisplayName: 'Test User',
    inviteCode: 'ABC123XY', isActive: true, showExactMonsterHp: false, promptPlayerRepeatSaves: false,
    allowPlayerManualModifiers: true, members,
    createdAt: makeTimestamp(),
  } satisfies Campaign;
}

// --- Campaigns ---
export const campaignHandlers = [
  http.get(`${BASE}/campaigns`, async () => {
    return HttpResponse.json([makeCampaign('camp-001', 'Test Campaign')]);
  }),

  http.get(`${BASE}/campaigns/:id`, async ({ params }) => {
    return HttpResponse.json(makeCampaign(params.id as string, 'Test Campaign'));
  }),

  http.post(`${BASE}/campaigns`, async () => {
    return HttpResponse.json(makeCampaign('camp-new', 'New Campaign'));
  }),

  http.post(`${BASE}/campaigns/join`, async () => {
    return HttpResponse.json(makeCampaign('camp-joined', 'Joined Campaign', 'dm-other', 2));
  }),

  http.patch(`${BASE}/campaigns/:id/settings`, async ({ params, request }) => {
    const settings = await request.json() as CampaignSettingsRequest;
    return HttpResponse.json({ ...makeCampaign(params.id as string, 'Test Campaign'), ...settings });
  }),
];

// --- Characters ---
export const characterHandlers = [
  http.get(`${BASE}/characters`, async () => {
    return HttpResponse.json([makeMockCharacter('char-001', 'Gimli', 'Fighter', 5)]);
  }),

  http.get(`${BASE}/characters/:id`, async ({ params }) => {
    return HttpResponse.json(makeMockCharacter(params.id as string, 'Test Character', 'Wizard', 3));
  }),

  http.post(`${BASE}/characters`, async () => {
    return HttpResponse.json(makeMockCharacter('char-new', 'New Character', 'Ranger', 1));
  }),

  // The creation wizard's AC preview: 10 + DEX 2 without armour
  http.post(`${BASE}/characters/armour-class-preview`, async () => {
    return HttpResponse.json({
      total: 12, parts: [{ label: 'Unarmoured', amount: 10 }, { label: 'DEX', amount: 2 }], warnings: [], armourEntries: [],
    } satisfies ArmourClassBreakdown);
  }),

  // The sheet's intent commands (D158), each answering with the character
  ...[
    ['patch', 'details'], ['put', 'campaign'], ['put', 'inventory'], ['post', 'hp'],
    ['post', 'spell-slots/:slot/spend'], ['post', 'spell-slots/:slot/restore'], ['post', 'pools/spend'],
    ['post', 'pools/recover'], ['post', 'long-rest'], ['put', 'spells/prepared'],
    ['post', 'spellbook'], ['delete', 'spellbook'],
  ].map(([method, path]) => http[method as 'patch' | 'put' | 'post' | 'delete'](`${BASE}/characters/:id/${path}`,
    async ({ params }) => HttpResponse.json(makeMockCharacter(params.id as string, 'Test Character', 'Wizard', 3)))),

  // A short rest answers with the character and what each Hit Die spent rolled
  http.post(`${BASE}/characters/:id/short-rest`, async ({ params }) => {
    return HttpResponse.json({
      character: makeMockCharacter(params.id as string, 'Test Character', 'Wizard', 3),
      rolls: [],
    } satisfies ShortRestResponse);
  }),

  http.delete(`${BASE}/characters/:id`, async () => {
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${BASE}/characters/:id/eligible-classes`, async () => {
    return HttpResponse.json([
      { classId: 'cls-fighter', className: 'Fighter', currentClassLevel: 5, currentClass: true, meetsPrerequisites: true, prerequisiteDescription: 'Current class' },
      { classId: 'cls-wizard', className: 'Wizard', currentClassLevel: 0, currentClass: false, meetsPrerequisites: true, prerequisiteDescription: 'INT 13 required' },
    ] satisfies EligibleClassResponse[]);
  }),

  // No feat's prerequisite is unmet unless a test says so (D159)
  http.get(`${BASE}/characters/:id/feat-options`, async () => {
    return HttpResponse.json([] satisfies FeatOptionResponse[]);
  }),

  http.post(`${BASE}/characters/:id/level-up`, async () => {
    return HttpResponse.json({
      character: makeMockCharacter('char-001', 'Gimli', 'Fighter', 6),
      newFeatures: ['Extra Attack (2)'],
    } satisfies LevelUpResponse);
  }),

  http.post(`${BASE}/characters/:id/level-down`, async () => {
    return HttpResponse.json(makeMockCharacter('char-001', 'Gimli', 'Fighter', 4));
  }),

  http.post(`${BASE}/characters/:id/apply-choices`, async () => {
    return HttpResponse.json(makeMockCharacter('char-001', 'Gimli', 'Fighter', 6));
  }),

  http.get(`${BASE}/characters/campaign/:campaignId`, async () => {
    return HttpResponse.json([
      makeMockCharacter('char-001', 'Player 1 Char', 'Cleric', 4),
    ]);
  }),

  // The DM's adjustments (D158), each answering with the character
  http.post(`${BASE}/characters/:id/adjustments`, async ({ params }) => {
    return HttpResponse.json(makeMockCharacter(params.id as string, 'Player 1 Char', 'Cleric', 4));
  }),
  http.delete(`${BASE}/characters/:id/adjustments/:adjustmentId`, async ({ params }) => {
    return HttpResponse.json(makeMockCharacter(params.id as string, 'Player 1 Char', 'Cleric', 4));
  }),
  // The DM's revival of a character who died (D166)
  http.post(`${BASE}/characters/:id/revive`, async ({ params }) => {
    return HttpResponse.json({ ...makeMockCharacter(params.id as string, 'Player 1 Char', 'Cleric', 4), hpCurrent: 1 });
  }),
];

/**
 * A Pending Choice as the server stores one (D159): a Fighter 4's Ability Score Improvement from levelling up, unless
 * the overrides make it another.
 */
export function makeMockPendingChoice(overrides: Partial<PendingChoice> = {}): PendingChoice {
  const choice = {
    id: 'choice-001', type: 'ASI', source: 'LEVEL_UP', characterLevel: 4, classId: 'cls-fighter',
    className: 'Fighter', classLevel: 4, count: 1,
  } satisfies PendingChoice;
  return { ...choice, ...overrides };
}

/** A DM Adjustment as the server records one: added by the DM, still active unless the overrides remove it. */
export function makeMockAdjustment(overrides: Partial<DmAdjustment> = {}): DmAdjustment {
  const adjustment = {
    id: 'adj-001', kind: 'STAT', target: 'ARMOUR_CLASS', amount: 1, reason: 'Ring of Protection',
    addedBy: 'user-001', addedAt: '2026-11-03T10:00:00Z',
  } satisfies DmAdjustment;
  return { ...adjustment, ...overrides };
}

// --- Encounters (core) ---
export const encounterHandlers = [
  http.get(`${BASE}/encounters/campaign/:campaignId`, async () => {
    return HttpResponse.json([
      makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] }),
    ]);
  }),

  http.post(`${BASE}/encounters`, async () => {
    return HttpResponse.json(
      makeMockEncounter({ id: 'enc-new', name: 'New Encounter', status: 'PREPARING', sessionCode: undefined, participants: [] }),
    );
  }),

  http.get(`${BASE}/encounters/join/:code`, async () => {
    return HttpResponse.json(
      makeMockEncounter({ id: 'enc-join', name: 'Joined Encounter', sessionCode: 'XYZ999AA', participants: [] }),
    );
  }),

  http.get(`${BASE}/encounters/:id`, async () => {
    return HttpResponse.json(makeMockEncounter());
  }),

  http.patch(`${BASE}/encounters/:id/participants/:participantId/name`, async () => {
    return HttpResponse.json(makeMockEncounter({ status: 'PREPARING', sessionCode: undefined }));
  }),

  http.patch(`${BASE}/encounters/:id/participants/:participantId/visibility`, async () => {
    return HttpResponse.json(makeMockEncounter());
  }),

  http.post(`${BASE}/encounters/:id/initiatives/roll`, async () => {
    return HttpResponse.json(makeMockEncounter({ status: 'PREPARING', sessionCode: undefined }));
  }),

  http.put(`${BASE}/encounters/:id/initiatives/tie-order`, async () => {
    return HttpResponse.json(makeMockEncounter());
  }),

  http.post(`${BASE}/encounters/:id/pause`, async () => {
    return HttpResponse.json(makeMockEncounter({ status: 'PAUSED' }));
  }),

  http.post(`${BASE}/encounters/:id/resume`, async () => {
    return HttpResponse.json(makeMockEncounter());
  }),

  http.post(`${BASE}/encounters/:id/end`, async () => {
    return HttpResponse.json(makeMockEncounter({ status: 'COMPLETED' }));
  }),

  // The End dialog's preview of what each character sheet takes (D166)
  http.get(`${BASE}/encounters/:id/results`, async () => {
    return HttpResponse.json([] satisfies CombatResultPreview[]);
  }),
];

// --- Encounter Builder (additional handlers for CRUD operations) ---
export const encounterBuilderHandlers = [
  // Must stay ahead of GET /monsters/:id in allHandlers.
  http.get(`${BASE}/monsters/search`, async () => {
    return HttpResponse.json([
      makeMockMonster('mon-001', 'Goblin', 'Humanoid', '1/4', 7, 15),
      makeMockMonster('mon-002', 'Owlbear', 'Monstrosity', '3', 59, 13),
      makeMockMonster('mon-003', 'Adult Dragon', 'Dragon', '14', 200, 19),
    ]);
  }),

  http.post(`${BASE}/encounters/:id/participants`, async () => {
    const participant = makeMockParticipant('part-new', 'New Participant', 'MONSTER');
    return HttpResponse.json(makeMockEncounter({
      status: 'PREPARING', sessionCode: undefined,
      participants: [makeMockParticipant('part-001', 'Test Goblin', 'MONSTER'), participant],
    }));
  }),

  http.delete(`${BASE}/encounters/:id/participants/:participantId`, async () => {
    return HttpResponse.json(makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] }));
  }),

  http.post(`${BASE}/encounters/:id/initiatives`, async () => {
    return HttpResponse.json(makeMockEncounter({
      status: 'PREPARING', sessionCode: undefined,
      participants: [makeMockParticipant('part-001', 'Test Goblin', 'MONSTER')],
    }));
  }),

  http.post(`${BASE}/encounters/:id/start`, async () => {
    return HttpResponse.json(makeMockEncounter({
      sessionCode: 'SESS123X',
      participants: [makeMockParticipant('part-001', 'Test Goblin', 'MONSTER')],
    }));
  }),

  http.delete(`${BASE}/encounters/:id`, async () => {
    return new HttpResponse(null, { status: 204 });
  }),
];

// --- Combat ---
export const combatHandlers = [
  http.post(`${BASE}/encounters/:id/combat/attack`, async () => HttpResponse.json(makeMockEncounter())),
  // A character with a longsword and the unarmed strike everyone has; tests that attack serve what they need (D164)
  http.get(`${BASE}/encounters/:id/combat/attack-options`, async () => HttpResponse.json([
    makeMockAttackOption('Longsword'),
    makeMockAttackOption('Unarmed Strike', { damage: '4', damageType: 'bludgeoning' }),
  ] satisfies AttackOption[])),
  http.post(`${BASE}/encounters/:id/combat/damage`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/heal`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/hp`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/condition/add`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/condition/remove`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/death-save`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/repeat-save`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/concentration`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/spell-slot/use`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/spell-slot/restore`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/cast-spell`, async () => HttpResponse.json(makeMockCastSpellResponse())),
  // A caster with nothing to cast; tests that cast serve the options they need (D164, D165)
  http.get(`${BASE}/encounters/:id/combat/spell-options`, async () => HttpResponse.json([] satisfies SpellOption[])),
  http.post(`${BASE}/encounters/:id/combat/turn/next`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/turn/previous`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/repeat-spell-effect`, async () => HttpResponse.json(makeMockCastSpellResponse())),
  http.get(`${BASE}/encounters/:id/combat/log`, async () => {
    return HttpResponse.json([] satisfies CombatLogEntry[]);
  }),
  http.post(`${BASE}/encounters/:id/combat/monster-action`, async () => {
    return HttpResponse.json({
      resolved: true, description: 'Test Goblin uses Scimitar.', totalDamage: 0, totalHealing: 0,
      targetResults: [], requiresManualResolution: false, conditionsInflicted: [],
      legendaryResistanceAvailable: false, legendaryResistanceRemaining: 0,
      encounterState: makeMockEncounter(),
    } satisfies MonsterActionResponse);
  }),
  http.post(`${BASE}/encounters/:id/combat/legendary-resistance`, async () => HttpResponse.json(makeMockEncounter())),
  // The DM's undo (M14): the encounter as it was, with nothing left to undo in the mock
  http.post(`${BASE}/encounters/:id/combat/undo`, async () => HttpResponse.json(makeMockEncounter({ undo: null }))),
  http.post(`${BASE}/encounters/:id/combat/use-pool`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/standard-action`, async () => HttpResponse.json(makeMockEncounter())),
  http.post(`${BASE}/encounters/:id/combat/object-interaction`, async () => HttpResponse.json(makeMockEncounter())),
  http.put(`${BASE}/encounters/:id/combat/turn-economy`, async () => HttpResponse.json(makeMockEncounter())),
  http.get(`${BASE}/encounters/:id/participants/:participantId/actions`, async () => {
    return HttpResponse.json({
      actions: [{
        name: 'Scimitar', attackBonus: 4, deliveryMethod: 'MELEE_WEAPON', automatable: true,
        effects: [{ effectType: 'DAMAGE', damageDice: '1d6+2', damageType: 'slashing' }],
      }],
    } satisfies MonsterActionTemplate);
  }),
  http.post(`${BASE}/encounters/:id/combat/lair-status`, async () => HttpResponse.json(makeMockEncounter())),
  // A monster without a multiattack; tests that split one serve its attacks (D165)
  http.get(`${BASE}/encounters/:id/combat/multiattack-options`, async () => HttpResponse.json([] satisfies MultiattackOption[])),
];

// --- Reference Data ---
export const referenceHandlers = [
  http.get(`${BASE}/reference/spells`, async () => {
    return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } satisfies PageResponse<Spell>);
  }),
  // Must stay ahead of /reference/spells/:id, which would otherwise match "targeting".
  http.get(`${BASE}/reference/spells/targeting`, async () => {
    return HttpResponse.json({
      maxTargets: -1, selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true,
      targetType: 'SINGLE_TARGET', hasRepeatEffect: false, repeatActionType: null,
    } satisfies SpellTargetingInfo);
  }),
  http.get(`${BASE}/reference/spells/:id`, async () => {
    return HttpResponse.json({
      id: 'spell-001', name: 'Fireball', level: 3, school: 'Evocation',
      castingTime: '1 action', rangeDistance: '150 feet', duration: 'Instantaneous',
      components: { verbal: true, somatic: true, material: 'bat guano' },
      concentration: false, ritual: false, description: 'A bright streak flashes...', higherLevels: 'When cast at 4th level...',
      classes: ['Wizard', 'Sorcerer'], damageType: 'fire', damageDice: '8d6', saveAbility: 'DEX', source: 'PHB',
    } satisfies Spell);
  }),
  http.get(`${BASE}/reference/spells/filters/schools`, async () => {
    return HttpResponse.json(['Abjuration', 'Conjuration', 'Divination', 'Enchantment', 'Evocation', 'Illusion', 'Necromancy', 'Transmutation']);
  }),
  http.get(`${BASE}/reference/spells/filters/sources`, async () => {
    return HttpResponse.json(['PHB', 'XGE', 'TCE']);
  }),
  http.get(`${BASE}/reference/spells/filters/classes`, async () => {
    return HttpResponse.json(['Barbarian', 'Bard', 'Cleric', 'Druid', 'Fighter', 'Monk', 'Paladin', 'Ranger', 'Rogue', 'Sorcerer', 'Warlock', 'Wizard']);
  }),
  http.get(`${BASE}/reference/spells/filters/subclasses`, async () => {
    return HttpResponse.json(['Cleric (Life)']);
  }),
  http.get(`${BASE}/reference/conditions`, async () => {
    return HttpResponse.json([{ id: 'cond-001', name: 'blinded', description: 'Cannot see.', effects: null, source: 'PHB' }] satisfies Condition[]);
  }),
  http.get(`${BASE}/reference/items`, async () => {
    return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } satisfies PageResponse<Item>);
  }),
  http.get(`${BASE}/reference/items/:id`, async ({ params }) => {
    return HttpResponse.json({
      id: params.id as string, name: 'Longsword', type: 'M', subtype: null, rarity: 'none',
      description: 'A versatile martial weapon.', properties: null, requiresAttunement: false, attunementCondition: null,
      weight: 3, cost: '15 gp', damageDice: '1d8', damageType: 'slashing', source: 'PHB', isHomebrew: false,
      stealthDisadvantage: false,
    } satisfies Item);
  }),
  http.get(`${BASE}/reference/items/filters/types`, async () => {
    return HttpResponse.json(['Armor', 'Weapon', 'Potion', 'Scroll']);
  }),
  http.get(`${BASE}/reference/items/filters/rarities`, async () => {
    return HttpResponse.json(['Common', 'Uncommon', 'Rare', 'Very Rare', 'Legendary']);
  }),
  http.get(`${BASE}/reference/items/filters/sources`, async () => {
    return HttpResponse.json(['DMG', 'PHB']);
  }),
  http.get(`${BASE}/reference/class-tables`, async () => {
    return HttpResponse.json(CLASS_TABLES);
  }),
  http.get(`${BASE}/reference/glossary`, async () => {
    return HttpResponse.json(GLOSSARY);
  }),
  http.get(`${BASE}/reference/quickref`, async () => {
    return HttpResponse.json([]);
  }),
  http.get(`${BASE}/reference/races`, async () => {
    return HttpResponse.json([
      makeMockRace('race-human', 'Human', JSON.stringify([{ ability: 'STR', bonus: 1 }])),
      makeMockRace('race-elf', 'Elf', JSON.stringify([{ ability: 'DEX', bonus: 2 }])),
    ]);
  }),
  http.get(`${BASE}/reference/races/filters/sources`, async () => {
    return HttpResponse.json(['PHB', 'DMG']);
  }),
  http.get(`${BASE}/reference/classes`, async () => {
    return HttpResponse.json([
      { id: 'cls-fighter', name: 'Fighter', source: 'PHB', hitDice: 10, primaryAbility: 'STR', savingThrowProficiencies: '["STR","CON"]', armorProficiencies: '["All Armor","Shields"]', weaponProficiencies: '["Simple","Martial"]', toolProficiencies: '[]', skillChoices: '{}', spellcastingAbility: null, features: '[]', startingEquipment: '[]', subclassLevel: 3, isSpellcaster: false, isPreparedCaster: false, isKnownCaster: false, isPactMagic: false, spellSlotProgression: '{}', multiclassRequirements: null, multiclassProficiencies: null },
    ] satisfies CharacterClassRef[]);
  }),
  http.get(`${BASE}/reference/classes/:classId/subclasses`, async () => {
    return HttpResponse.json([] satisfies Subclass[]);
  }),
  http.get(`${BASE}/reference/backgrounds`, async () => {
    return HttpResponse.json([] satisfies Background[]);
  }),
  // Starting equipment: none, unless a test serves some
  http.get(`${BASE}/reference/classes/:classId/starting-equipment`, async () => {
    return HttpResponse.json({ groups: [], goldAlternative: null } satisfies StartingEquipment);
  }),
  http.get(`${BASE}/reference/backgrounds/:backgroundId/starting-equipment`, async () => {
    return HttpResponse.json({ groups: [], goldAlternative: null } satisfies StartingEquipment);
  }),
  http.get(`${BASE}/reference/feats`, async () => {
    return HttpResponse.json([] satisfies Feat[]);
  }),
  http.get(`${BASE}/reference/optional-features`, async () => {
    return HttpResponse.json([] satisfies OptionalFeature[]);
  }),
];

// --- Monsters ---
export const monsterHandlers = [
  http.get(`${BASE}/monsters`, async ({ request }) => {
    const url = new URL(request.url);
    const page = parseInt(url.searchParams.get('page') || '0');
    const size = parseInt(url.searchParams.get('size') || '20');
    return HttpResponse.json({
      content: [
        makeMockMonster('mon-001', 'Goblin', 'Humanoid', '1/4', 7, 15),
        makeMockMonster('mon-002', 'Owlbear', 'Monstrosity', '3', 59, 13),
      ],
      totalElements: 2, totalPages: page === 0 && size >= 2 ? 1 : Math.ceil(2 / size),
      number: page, size,
    } satisfies PageResponse<Monster>);
  }),
  http.get(`${BASE}/monsters/filters/sources`, async () => {
    return HttpResponse.json(['MM', 'DMG']);
  }),
  http.get(`${BASE}/monsters/filters/types`, async () => {
    return HttpResponse.json(['Aberration', 'Beast', 'Dragon', 'Humanoid']);
  }),
  http.get(`${BASE}/monsters/filters/challenge-ratings`, async () => {
    return HttpResponse.json(['0', '1/4', '1/2', '1', '5', '10', '20']);
  }),
  http.get(`${BASE}/monsters/:id`, async ({ params }) => {
    return HttpResponse.json(makeMockMonster(params.id as string, 'Goblin', 'Humanoid', '1/4', 7, 15));
  }),
];

// --- Helper factories ---
export function makeMockCharacter(id: string, name: string, cls: string, level: number) {
  return {
    id, userId: 'user-001', ownerDisplayName: 'Test User', campaignId: 'camp-001',
    raceId: 'race-human', raceName: 'Human',
    name, race: 'Human', characterClass: cls, subclass: null, level,
    experiencePoints: 0, background: null, alignment: 'Neutral',
    strength: 14, dexterity: 12, constitution: 14, intelligence: 10, wisdom: 12, charisma: 10,
    // The scores without the DM's adjustments, of which there are none (D158)
    baseAbilityScores: { strength: 14, dexterity: 12, constitution: 14, intelligence: 10, wisdom: 12, charisma: 10 },
    hpMax: 30, hpCurrent: 30, hpTemp: 0,
    armourClass: 16, initiativeBonus: 1, speed: 30, proficiencyBonus: 3,
    // How the server worked the AC out (M13.5): a chain shirt and DEX, with nothing equipped in the inventory listed
    armourClassBreakdown: {
      total: 16,
      parts: [{ label: 'Chain Shirt', amount: 13 }, { label: 'DEX (max 2)', amount: 1 }, { label: 'Shield', amount: 2 }],
      warnings: [],
      armourEntries: [],
    },
    savingThrowProficiencies: ['str', 'con'],
    skillProficiencies: ['athletics', 'perception'],
    skillExpertises: null,
    armorProficiencies: ['All Armor'],
    weaponProficiencies: ['Simple Weapons', 'Martial Weapons'],
    toolProficiencies: null, languageProficiencies: ['Common'],
    damageResistances: [], damageImmunities: [], damageVulnerabilities: [], conditionImmunities: null,
    features: [{ name: 'Action Surge', description: 'Take one additional action.', source: 'Class' }],
    spellsKnown: null, spellSlots: null, spellSaveDc: null, spellAttackBonus: null, spellcastingAbility: null,
    // Worked out by the server (D167)
    spellcasting: [],
    abilityScoreMaximums: { strength: 20, dexterity: 20, constitution: 20, intelligence: 20, wisdom: 20, charisma: 20 },
    subclassAlwaysPreparedSpells: null, equipment: null, currency: null,
    personalityTraits: null, ideals: null, bonds: null, flaws: null,
    deathSaveSuccesses: 0, deathSaveFailures: 0, portraitUrl: null,
    abilityScoreMethod: null, racialAbilityBonuses: null,
    multiclassEntries: null,
    hitDice: { [cls]: { total: level, remaining: level, dieSize: 10 } },
    levelHistory: [{ characterLevel: 1, classId: 'cls-fighter', className: cls, classLevel: 1, hpGained: 12, hitDieResult: 10, featuresGained: [], choices: {} }],
    dmAdjustments: [],
    // The choices its levels owe, of which there are none (D159)
    pendingChoices: [],
    isActive: true, inLiveEncounter: false, dead: false,
    createdAt: makeTimestamp(), updatedAt: makeTimestamp(),
  } satisfies PlayerCharacter;
}

export function makeMockParticipant(
  id: string,
  displayName: string,
  type: ParticipantType,
  overrides: Partial<EncounterParticipant> = {},
): EncounterParticipant {
  const participant = {
    id, participantType: type,
    monsterId: type === 'MONSTER' ? 'mon-001' : undefined,
    characterId: type === 'PLAYER' ? 'char-001' : undefined,
    displayName, initiative: 15, initiativeModifier: 2, sortOrder: 1,
    hpMax: 30, hpCurrent: 30, hpTemp: 0, armourClass: 14,
    isVisibleToPlayers: true, lifeState: 'ALIVE', isCurrentTurn: false, deathSaveDue: false,
    deathSaveSuccesses: 0, deathSaveFailures: 0,
    activeEffects: [],
    // A fresh turn's economy, one Attack action's attacks and a walking speed (D174)
    turnEconomy: {
      actions: 1, actionsUsed: 0, attackActionTaken: false, attacksLeft: 0, bonusActionUsed: false,
      reactionUsed: false, objectInteractionUsed: false, bonusActionSpellCast: false, otherSpellCast: false,
      dashed: false, disengaged: false,
    },
    attacksPerAction: 1,
    speed: { walk: 30 },
  } satisfies EncounterParticipant;
  return { ...participant, ...overrides };
}

/**
 * A Spell Effect as the server sends one (M15): Spiritual Weapon cast in round 1 with a 2nd-level slot, attacking for a
 * bonus action, with no concentration, no uses and no triggers, unless the overrides say otherwise.
 */
export function makeMockSpellEffect(spell = 'Spiritual Weapon', overrides: Partial<SpellEffect> = {}): SpellEffect {
  const effect = {
    id: `effect-${spell.toLowerCase().replace(/[^a-z]+/g, '-')}`, spell, slotLevel: 2, concentration: false,
    startedRound: 1, durationRounds: 10, usesLeft: null, directedCost: 'BONUS_ACTION', directedResolves: true,
    maxUsesEachTurn: null, triggers: [],
  } satisfies SpellEffect;
  return { ...effect, ...overrides };
}

/**
 * A condition as the server writes one: applied in round 1, lasting until removed by hand, with no source.
 * Its ID comes from its name, so two mocks of one condition are the same entry.
 */
export function makeMockCondition(name: string, overrides: Partial<ConditionEntry> = {}): ConditionEntry {
  const condition = {
    id: `cond-${name}`, name, duration: null, appliedRound: 1,
    sourceSpellName: null, sourceParticipantId: null, sourceRequiresConcentration: null,
    concentrationInstanceId: null, endRule: { type: 'MANUAL', participantId: null }, repeatSave: null,
  } satisfies ConditionEntry;
  return { ...condition, ...overrides };
}

/** A full pool of three uses that a long rest restores. */
export function makeMockResourcePool(
  poolId: string,
  displayName: string,
  overrides: Partial<ResourcePoolEntry> = {},
): ResourcePoolEntry {
  const pool = {
    poolId, displayName, sourceType: 'CLASS', sourceName: null,
    maxUses: 3, maxUsesFormula: null, currentUses: 3, resetOn: 'longRest',
    resetAmount: null, resetCheck: null, spendActionType: 'FREE', icon: null, metadata: {},
  } satisfies ResourcePoolEntry;
  return { ...pool, ...overrides };
}

/**
 * The turn order the server computes for these participants (D163): by sortOrder, on the turn of the one
 * whose isCurrentTurn is set, with no Lair and no ties. A dead monster's turn is skipped.
 */
export function mockTurnOrder(participants: EncounterParticipant[]): TurnEntry[] {
  return [...participants].sort((a, b) => a.sortOrder - b.sortOrder).map(p => ({
    participantId: p.id, name: p.displayName, initiative: p.initiative ?? null, lair: false,
    skipped: p.participantType === 'MONSTER' && p.lifeState === 'DEAD', current: p.isCurrentTurn, tieGroup: null,
  } satisfies TurnEntry));
}

/** An encounter whose turn order and current turn follow from its participants, unless overridden. */
export function makeMockEncounter(overrides: Partial<Encounter> = {}): Encounter {
  const participants = overrides.participants
    ?? [makeMockParticipant('part-001', 'Test Goblin', 'MONSTER'), makeMockParticipant('part-002', 'Gimli', 'PLAYER')];
  const turnOrder = overrides.turnOrder ?? mockTurnOrder(participants);
  const current = turnOrder.findIndex(turn => turn.current);
  const encounter = {
    id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush',
    status: 'ACTIVE', currentTurnIndex: current >= 0 ? current : null, roundNumber: 1, sessionCode: 'ABC123XY',
    participants, turnOrder, monstersInLair: [],
    createdAt: makeTimestamp(), allowPlayerManualModifiers: true,
  } satisfies Encounter;
  return { ...encounter, ...overrides };
}

/**
 * A proficient STR melee weapon at +5 for 1d8 + 3 slashing, without Great Weapon Master, Sharpshooter or Reckless
 * Attack.
 */
export function makeMockAttackOption(weapon: string, overrides: Partial<AttackOption> = {}): AttackOption {
  const option = {
    weapon, ability: 'STR', proficient: true, attackBonus: 5, damage: '1d8 + 3', damageType: 'slashing',
    ranged: false, powerAttack: null, recklessAttack: false, twoHanded: false, versatileDamage: null, features: [],
    critRange: 20, sneakAttack: null, martialArts: false,
  } satisfies AttackOption;
  return { ...option, ...overrides };
}

export function makeMockCastSpellResponse(overrides: Partial<CastSpellResponse> = {}): CastSpellResponse {
  const response = {
    encounterState: makeMockEncounter(),
    spellName: 'Fire Bolt', slotLevelUsed: 0, spellSource: 'NONE', autoResolved: true,
    resultSummary: 'Fire Bolt hits Test Goblin for 7 fire damage.',
    targets: [{ targetId: 'part-001', targetName: 'Test Goblin', outcome: 'hit', damage: 7, conditionsApplied: [], attackRoll: 18 }],
  } satisfies CastSpellResponse;
  return { ...response, ...overrides };
}

export function makeMockLogEntry(id: string, description: string, overrides: Partial<CombatLogEntry> = {}): CombatLogEntry {
  const entry = {
    id, roundNumber: 1, actionType: 'DAMAGE', description, createdAt: makeTimestamp(), undone: false,
  } satisfies CombatLogEntry;
  return { ...entry, ...overrides };
}

export function makeMockMonster(id: string, name: string, type: string, cr: string, hp: number, ac: number) {
  return {
    id, name, type, challengeRating: cr, hitPoints: hp, armourClass: ac,
    size: 'Medium', subtype: null, alignment: null, acType: null,
    hitDice: `${hp / 5}d8`, speed: { walk: 30 },
    strength: 14, dexterity: 12, constitution: 14, intelligence: 8, wisdom: 12, charisma: 8,
    savingThrows: {}, skills: {}, damageResistances: null, damageImmunities: null,
    damageVulnerabilities: null, conditionImmunities: null, senses: { passive_perception: 11 }, languages: null,
    experiencePoints: 0, traits: [], actions: [], reactions: [], legendaryActions: null, lairActions: null,
    source: 'MM', isHomebrew: false,
  } satisfies Monster;
}

export function makeMockRace(id: string, name: string, abilityScoreBonuses: string) {
  return {
    id, name, source: 'PHB', size: 'Medium', speed: JSON.stringify({ walk: 30 }), abilityScoreBonuses,
    creatureType: 'Humanoid', darkvision: null, traits: '[]', proficiencies: '{}', resistances: '[]',
    raceChoices: null, additionalSpells: null, baseRaceName: null, description: null,
  } satisfies Race;
}

export const allHandlers = [
  ...authHandlers,
  ...campaignHandlers,
  ...characterHandlers,
  ...encounterHandlers,
  ...encounterBuilderHandlers,
  ...combatHandlers,
  ...referenceHandlers,
  ...monsterHandlers,
];
