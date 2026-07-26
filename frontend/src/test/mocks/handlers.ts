import { http, HttpResponse } from 'msw';

const BASE = '/api';

function makeTimestamp() { return new Date().toISOString(); }

// --- Auth ---
export const authHandlers = [
  http.post(`${BASE}/auth/login`, async () => {
    return HttpResponse.json({
      userId: 'user-001', username: 'testuser', displayName: 'Test User',
      accessToken: 'mock-access-token', refreshToken: 'mock-refresh-token',
    });
  }),

  http.post(`${BASE}/auth/register`, async () => {
    return HttpResponse.json({
      userId: 'user-002', username: 'newuser', displayName: 'New User',
      accessToken: 'mock-access-token-new', refreshToken: 'mock-refresh-token-new',
    });
  }),

  http.post(`${BASE}/auth/refresh`, async () => {
    return HttpResponse.json({
      userId: 'user-001', username: 'testuser', displayName: 'Test User',
      accessToken: 'mock-access-token-refreshed', refreshToken: 'mock-refresh-token-refreshed',
    });
  }),
];

// --- Shared data builders ---
function makeCampaign(id: string, name: string, dmUserId = 'user-001', membersCount = 1) {
  const members = [
    { userId: dmUserId, username: 'testuser', displayName: 'Test User', role: 'DM' as const, joinedAt: makeTimestamp() },
  ];
  for (let i = 1; i < membersCount; i++) {
    members.push({ userId: `plr-00${i}`, username: `player${i}`, displayName: `Player ${i}`, role: 'PLAYER' as const, joinedAt: makeTimestamp() });
  }
  return {
    id, name, description: null, dmUserId, dmDisplayName: 'Test User',
    inviteCode: 'ABC123XY', isActive: true, members, createdAt: makeTimestamp(),
  };
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

  http.put(`${BASE}/characters/:id`, async () => {
    return HttpResponse.json(makeMockCharacter('char-001', 'Updated Character', 'Fighter', 6));
  }),

  http.delete(`${BASE}/characters/:id`, async () => {
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${BASE}/characters/:id/eligible-classes`, async () => {
    return HttpResponse.json([
      { classId: 'cls-fighter', className: 'Fighter', currentClassLevel: 5, currentClass: true, meetsPrerequisites: true, prerequisiteDescription: 'Current class' },
      { classId: 'cls-wizard', className: 'Wizard', currentClassLevel: 0, currentClass: false, meetsPrerequisites: true, prerequisiteDescription: 'INT 13 required' },
    ]);
  }),

  http.post(`${BASE}/characters/:id/level-up`, async () => {
    return HttpResponse.json({
      character: makeMockCharacter('char-001', 'Gimli', 'Fighter', 6),
      pendingChoices: { asiAvailable: false, subclassRequired: false, expertiseAvailable: false, expertiseCount: 0, spellSelectionNeeded: false, newFeatures: ['Extra Attack (2)'], maxSpellLevel: 0 },
    });
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
];

// --- Encounters (core) ---
export const encounterHandlers = [
  http.get(`${BASE}/encounters/campaign/:campaignId`, async () => {
    return HttpResponse.json([
      { id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush', description: null, status: 'PREPARING', currentTurnIndex: 0, roundNumber: 1, sessionCode: null, participants: [], createdAt: makeTimestamp() },
    ]);
  }),

  http.post(`${BASE}/encounters`, async () => {
    return HttpResponse.json({
      id: 'enc-new', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'New Encounter', description: null, status: 'PREPARING', currentTurnIndex: 0, roundNumber: 1, sessionCode: null, participants: [], createdAt: makeTimestamp(),
    });
  }),

  http.get(`${BASE}/encounters/join/:code`, async () => {
    return HttpResponse.json({
      id: 'enc-join', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Joined Encounter', status: 'ACTIVE', currentTurnIndex: 0, roundNumber: 1, sessionCode: 'XYZ999AA', participants: [], createdAt: makeTimestamp(),
    });
  }),

  http.get(`${BASE}/encounters/:id`, async () => {
    return HttpResponse.json({
      id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush', status: 'ACTIVE', currentTurnIndex: 0, roundNumber: 1, sessionCode: 'ABC123XY', participants: [makeMockParticipant('part-001', 'Test Goblin', 'MONSTER'), makeMockParticipant('part-002', 'Gimli', 'PLAYER')], createdAt: makeTimestamp(),
    });
  }),
];

// --- Encounter Builder (additional handlers for CRUD operations) ---
export const encounterBuilderHandlers = [
  http.get(`${BASE}/monsters/search`, async () => {
    return HttpResponse.json([
      makeMockMonster('mon-001', 'Goblin', 'Humanoid', '1/4', 7, 15),
      makeMockMonster('mon-002', 'Owlbear', 'Monstrosity', '3', 59, 13),
      makeMockMonster('mon-003', 'Adult Dragon', 'Dragon', '14', 200, 19),
    ]);
  }),

  http.post(`${BASE}/encounters/:id/participants`, async () => {
    const participant = makeMockParticipant('part-new', 'New Participant', 'MONSTER');
    return HttpResponse.json({
      id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush', status: 'PREPARING', currentTurnIndex: 0, roundNumber: 1, sessionCode: null,
      participants: [makeMockParticipant('part-001', 'Test Goblin', 'MONSTER'), participant],
      createdAt: makeTimestamp(),
    });
  }),

  http.delete(`${BASE}/encounters/:id/participants/:participantId`, async () => {
    return HttpResponse.json({
      id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush', status: 'PREPARING', currentTurnIndex: 0, roundNumber: 1, sessionCode: null,
      participants: [],
      createdAt: makeTimestamp(),
    });
  }),

  http.post(`${BASE}/encounters/:id/initiatives`, async () => {
    return HttpResponse.json({
      id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush', status: 'PREPARING', currentTurnIndex: 0, roundNumber: 1, sessionCode: null,
      participants: [makeMockParticipant('part-001', 'Test Goblin', 'MONSTER')],
      createdAt: makeTimestamp(),
    });
  }),

  http.post(`${BASE}/encounters/:id/start`, async () => {
    return HttpResponse.json({
      id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush', status: 'ACTIVE', currentTurnIndex: 0, roundNumber: 1, sessionCode: 'SESS123X',
      participants: [makeMockParticipant('part-001', 'Test Goblin', 'MONSTER')],
      createdAt: makeTimestamp(),
    });
  }),

  http.delete(`${BASE}/encounters/:id`, async () => {
    return new HttpResponse(null, { status: 204 });
  }),
];

// --- Combat ---
export const combatHandlers = [
  http.post(`${BASE}/encounters/:id/combat/damage`, async () => {
    return HttpResponse.json({});
  }),
  http.post(`${BASE}/encounters/:id/combat/heal`, async () => {
    return HttpResponse.json({});
  }),
  http.get(`${BASE}/encounters/:id/combat/log`, async () => {
    return HttpResponse.json([]);
  }),
];

// --- Reference Data ---
export const referenceHandlers = [
  http.get(`${BASE}/reference/spells`, async () => {
    return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0 });
  }),
  http.get(`${BASE}/reference/spells/:id`, async () => {
    return HttpResponse.json({
      id: 'spell-001', name: 'Fireball', level: 3, school: 'Evocation',
      castingTime: '1 action', rangeDistance: '150 feet', duration: 'Instantaneous',
      components: { verbal: true, somatic: true, material: 'bat guano' },
      concentration: false, ritual: false, description: 'A bright streak flashes...', higherLevels: 'When cast at 4th level...',
      classes: ['Wizard', 'Sorcerer'], damageType: 'fire', damageDice: '8d6', saveAbility: 'DEX', source: 'PHB',
    });
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
  http.get(`${BASE}/reference/spells/targeting`, async () => {
    return HttpResponse.json({ selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true, targetCount: 1, upcastTargetBonus: 0 });
  }),
  http.get(`${BASE}/reference/conditions`, async () => {
    return HttpResponse.json([{ id: 'cond-001', name: 'blinded', description: 'Cannot see.', effects: null }]);
  }),
  http.get(`${BASE}/reference/items`, async () => {
    return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0 });
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
  http.get(`${BASE}/reference/races`, async () => {
    return HttpResponse.json([
      { id: 'race-human', name: 'Human', source: 'PHB', size: 'Medium', speed: 30, abilityScoreBonuses: JSON.stringify([{ ability: 'STR', bonus: 1 }]), traits: '[]', proficiencies: '{}', resistances: '[]' },
      { id: 'race-elf', name: 'Elf', source: 'PHB', size: 'Medium', speed: 30, abilityScoreBonuses: JSON.stringify([{ ability: 'DEX', bonus: 2 }]), traits: '[]', proficiencies: '{}', resistances: '[]' },
    ]);
  }),
  http.get(`${BASE}/reference/races/sources`, async () => {
    return HttpResponse.json(['PHB', 'DMG']);
  }),
  http.get(`${BASE}/reference/classes`, async () => {
    return HttpResponse.json([
      { id: 'cls-fighter', name: 'Fighter', source: 'PHB', hitDice: 10, primaryAbility: 'STR', savingThrowProficiencies: '["STR","CON"]', armorProficiencies: '["All Armor","Shields"]', weaponProficiencies: '["Simple","Martial"]', toolProficiencies: '[]', skillChoices: '{}', spellcastingAbility: null, features: '[]', startingEquipment: '[]', subclassLevel: 3, isSpellcaster: false, isPreparedCaster: false, isKnownCaster: false, isPactMagic: false, spellSlotProgression: '{}', multiclassRequirements: null, multiclassProficiencies: null },
    ]);
  }),
  http.get(`${BASE}/reference/subclasses`, async () => {
    return HttpResponse.json([]);
  }),
  http.get(`${BASE}/reference/backgrounds`, async () => {
    return HttpResponse.json([]);
  }),
  http.get(`${BASE}/reference/feats`, async () => {
    return HttpResponse.json([]);
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
    });
  }),
  http.get(`${BASE}/monsters/search`, async () => {
    return HttpResponse.json([]);
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
];

// --- Helper factories ---
export function makeMockCharacter(id: string, name: string, cls: string, level: number) {
  return {
    id, userId: 'user-001', ownerDisplayName: 'Test User', campaignId: 'camp-001',
    name, race: 'Human', characterClass: cls, subclass: null, level,
    experiencePoints: 0, background: null, alignment: 'Neutral',
    strength: 14, dexterity: 12, constitution: 14, intelligence: 10, wisdom: 12, charisma: 10,
    hpMax: 30, hpCurrent: 30, hpTemp: 0, hitDiceTotal: `${level}d10`, hitDiceRemaining: `${level}d10`,
    armourClass: 16, initiativeBonus: 1, speed: 30, proficiencyBonus: 3,
    savingThrowProficiencies: JSON.stringify(['str', 'con']),
    skillProficiencies: JSON.stringify(['athletics', 'perception']),
    skillExpertises: null,
    armorProficiencies: JSON.stringify(['All Armor']),
    weaponProficiencies: JSON.stringify(['Simple Weapons', 'Martial Weapons']),
    toolProficiencies: null, languageProficiencies: JSON.stringify(['Common']),
    damageResistances: null, damageImmunities: null, conditionImmunities: null,
    features: JSON.stringify([{ name: 'Action Surge', description: 'Take one additional action.', source: 'Class' }]),
    spellsKnown: null, spellSlots: null, spellSaveDc: null, spellAttackBonus: null, spellcastingAbility: null,
    subclassAlwaysPreparedSpells: null, equipment: null, currency: null,
    personalityTraits: null, ideals: null, bonds: null, flaws: null,
    deathSaveSuccesses: 0, deathSaveFailures: 0, portraitUrl: null,
    abilityScoreMethod: null, racialAbilityBonuses: null,
    multiclassEntries: null, attunedItems: null, equippedItems: null,
    hitDiceMap: JSON.stringify({ [cls]: { total: level, remaining: level, faces: 10 } }),
    levelHistory: JSON.stringify([{ characterLevel: 1, classId: 'cls-fighter', className: cls, classLevel: 1, hpGained: 12, featuresGained: [] }]),
    featResources: null, isActive: true,
    createdAt: makeTimestamp(), updatedAt: makeTimestamp(),
  };
}

export function makeMockParticipant(id: string, displayName: string, type: string) {
  return {
    id, participantType: type,
    monsterId: type === 'MONSTER' ? 'mon-001' : undefined,
    characterId: type === 'PLAYER' ? 'char-001' : undefined,
    displayName, initiative: 15, initiativeModifier: 2, sortOrder: 1,
    hpMax: 30, hpCurrent: 30, hpTemp: 0, armourClass: 14,
    activeConditions: null, concentrationSpell: null, concentrationSlotLevel: null,
    activeSpell: null, activeSpellSlotLevel: null,
    spellSlotsCurrent: null, spellAttackBonus: null, spellSaveDc: null,
    spellcastingAbility: null, spellsKnown: null,
    isVisibleToPlayers: true, isAlive: true, isCurrentTurn: false,
    controlledByUserId: null, deathSaveSuccesses: 0, deathSaveFailures: 0,
  };
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
    source: 'MM',
  };
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
