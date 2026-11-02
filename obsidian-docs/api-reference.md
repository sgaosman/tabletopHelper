# API Reference

Base URL: `http://localhost:8080/api` (dev) or `https://yourdomain.com/api` (prod)

All authenticated endpoints require the header: `Authorization: Bearer <accessToken>`. An access token lasts 15 minutes; `POST /auth/refresh` exchanges the `refresh_token` cookie for a new one (see [[auth-flow]]). The `/auth/*` endpoints need no access token.

## Authentication

### POST /auth/register

Create a new user account.

**Request:**
```json
{
  "username": "aragorn",
  "email": "aragorn@gondor.com",
  "password": "str1derR4nger",
  "displayName": "Aragorn"
}
```

**Response (201):**
```json
{
  "userId": "uuid",
  "username": "aragorn",
  "displayName": "Aragorn",
  "accessToken": "eyJ..."
}
```

The response also sets the `refresh_token` cookie (HttpOnly, SameSite=Strict, Path=/api/auth, 7 days; Secure outside the dev profile), which starts a new token family. Since R3 the body has no `refreshToken` (D156).

**Errors:**
- `409` — Username already taken / Email already registered
- `400` — Validation failed

### POST /auth/login

**Request:**
```json
{
  "username": "aragorn",
  "password": "str1derR4nger"
}
```

**Response (200):** Same shape as register response, and sets a new `refresh_token` cookie that starts a new token family.

**Errors:**
- `400` — Invalid username or password

### POST /auth/refresh

Exchange the `refresh_token` cookie for a new access token. The request has no body: the browser sends the cookie. The presented refresh token is replaced, and the response sets a cookie with the new one (rotation, D156).

**Response (200):** Same shape as register response.

**Errors:**
- `401` — `{"error": "Your session has ended. Please sign in again."}` when the cookie is missing, unknown, expired or revoked. Presenting a refresh token that was already replaced also revokes its whole family (reuse detection).

Before R3 this endpoint took `{"refreshToken": "..."}` in the body, returned a new token pair, and answered a bad token with 400 (superseded in R3, D156).

### POST /auth/logout

Sign out. Revokes every refresh token from the cookie's login (its token family) and deletes the cookie (`Max-Age=0`). A missing or unknown cookie is ignored. Needs no access token. New in R3 (D156).

**Response (204):** No Content.

## Campaigns

All endpoints require authentication.

### POST /campaigns

Create a new campaign. The authenticated user becomes the DM.

**Request:**
```json
{
  "name": "Curse of Strahd",
  "description": "A gothic horror adventure"
}
```

**Response (201):**
```json
{
  "id": "uuid",
  "name": "Curse of Strahd",
  "description": "A gothic horror adventure",
  "dmUserId": "uuid",
  "dmDisplayName": "Aragorn",
  "inviteCode": "X43EWPQ2",
  "isActive": true,
  "showExactMonsterHp": false,
  "members": [
    {
      "userId": "uuid",
      "username": "aragorn",
      "displayName": "Aragorn",
      "role": "DM",
      "joinedAt": "2026-07-17T22:08:00Z"
    }
  ],
  "createdAt": "2026-07-17T22:08:00Z"
}
```

### POST /campaigns/join

Join an existing campaign via invite code. The authenticated user becomes a PLAYER.

**Request:**
```json
{
  "inviteCode": "X43EWPQ2"
}
```

**Response (200):** Full campaign response including updated members list.

**Errors:**
- `400` — Invalid invite code
- `409` — Already a member

### GET /campaigns

List all campaigns the authenticated user is a member of (as DM or Player).

**Response (200):** Array of campaign objects.

### GET /campaigns/{campaignId}

Get full details of a specific campaign. User must be a member.

**Response (200):** Campaign object with members list.

**Errors:**
- `404` — Campaign not found
- `403` — Not a member

### PATCH /campaigns/{campaignId}/settings

Change the campaign's settings. DM only. New in R3 (D157).

**Request:**
```json
{
  "showExactMonsterHp": true
}
```

- `showExactMonsterHp` — `true` lets players see exact monster HP; `false` (the default) shows them a Health Status instead (see [[websocket-protocol#Player View (D157)]]).

Every setting is optional: one left out, or sent as `null`, keeps its current value. A live encounter's Player View picks up the change with its next update.

**Response (200):** Campaign object.

**Errors:**
- `403` — Not the campaign's DM
- `404` — Campaign not found

## Characters

All endpoints require authentication.

### POST /characters

Create a new player character owned by the authenticated user. Uses FK references to seeded reference data.

**Request:**
```json
{
  "name": "Gandalf",
  "raceId": "uuid",
  "classId": "uuid",
  "subclassId": "uuid (optional)",
  "backgroundId": "uuid",
  "alignment": "Neutral Good",
  "abilityScoreMethod": "standard_array",
  "strength": 10,
  "dexterity": 14,
  "constitution": 16,
  "intelligence": 20,
  "wisdom": 18,
  "charisma": 16,
  "hpMax": 120,
  "speed": 30,
  "campaignId": "uuid (optional)",
  "savingThrowProficiencies": "[\"INT\",\"WIS\"]",
  "skillProficiencies": "[\"Arcana\",\"History\"]",
  "armorProficiencies": "[\"Light armor\"]",
  "weaponProficiencies": "[\"Daggers\",\"Darts\",\"Slings\",\"Quarterstaffs\",\"Light crossbows\"]",
  "toolProficiencies": "[\"Herbalism kit\"]",
  "languageProficiencies": "[\"Common\",\"Elvish\",\"Draconic\"]",
  "damageResistances": "[\"fire\"]",
  "racialAbilityBonuses": "[{\"ability\":\"Intelligence\",\"bonus\":2}]",
  "spellcastingAbility": "INT",
  "spellsKnown": "[{\"name\":\"Fire Bolt\",\"level\":0,\"source\":\"class:Wizard\"},{\"name\":\"Shield\",\"level\":1,\"source\":\"class:Wizard\",\"prepared\":true}]",
  "features": "[{\"name\":\"Magic Initiate\",\"description\":\"Granted by Astral Drifter background.\",\"source\":\"Astral Drifter\"}]",
  "hitDiceMap": "{\"Wizard\":{\"total\":1,\"remaining\":1,\"faces\":6}}",
  "multiclassClassEntries": "[{\"classId\":\"uuid\",\"level\":3,\"subclassId\":\"uuid\"},{\"classId\":\"uuid2\",\"level\":2}]"
}
```

For spellcaster classes, `spellSlots`, `spellSaveDc`, and `spellAttackBonus` are auto-calculated server-side if not provided. For non-casters with feat spells, the frontend calculates and sends these values.

For multiclass characters (level >= 2), the `multiclassClassEntries` field contains a JSON array of class entries with `classId`, `level`, and optional `subclassId`. The server builds `multiclassEntries`, `hitDiceMap`, `hitDiceTotal`, `levelHistory`, and HP from this data using `LevelUpCalculator.buildMulticlassProgression()`. The `characterClass` field is auto-set to "ClassName1 / ClassName2" format. ASI choices are applied post-creation via `POST /characters/{characterId}/apply-choices` — the server records each ASI on the correct level history entry (first unrecorded ASI level, not the last entry).

The `spellsKnown` array uses source prefixes: `"class:ClassName"`, `"race:RaceName"`, `"feat:FeatName"`. Entries support `prepared`, `alwaysPrepared`, `atWill`, `usesPerLongRest`, and `unlocksAtLevel` fields.

**Response (201):** Full character object with auto-populated derived stats (proficiencyBonus, initiativeBonus, armourClass, spellSlots, spellSaveDc, spellAttackBonus, subclassAlwaysPreparedSpells). The `subclassAlwaysPreparedSpells` field is a JSON object keyed by class level (e.g. `{"3":["Hold Person","Spike Growth"],"5":["Sleet Storm"]}`) — populated from the Subclass entity's seeded 5etools data.

**Errors:**
- `400` — Unknown `raceId`, `classId`, `subclassId`, `backgroundId` or `campaignId` (IDs in the body are a 400, not a 404), or validation failed
- `403` — `campaignId` names a campaign the user isn't a member of (D155)

### PUT /characters/{characterId}

Update an existing character. Only the owner can update. All fields are optional — only provided fields are updated.

**Request:** Any subset of character fields. Includes `clearCampaign` (boolean) — set to `true` to unassign the character from its campaign (since `campaignId: null` is indistinguishable from "not provided" in a partial update). Assigning a `campaignId` requires the owner to be a member of that campaign.

**Response (200):** Full updated character object.

**Errors:**
- `404` — Character not found
- `403` — Not the owner, or not a member of the assigned campaign

### GET /characters

List all active characters owned by the authenticated user.

**Response (200):** Array of character objects.

### GET /characters/{characterId}

Get a specific character by ID. Only its owner, or the DM of the campaign it belongs to, may read it (D155, R3).

**Response (200):** Full character object.

**Errors:**
- `404` — Character not found
- `403` — Neither the owner nor the campaign's DM

### DELETE /characters/{characterId}

Soft-delete a character (sets `isActive = false`). Only the owner can delete. Characters currently in an active encounter (PREPARING, ACTIVE, or PAUSED) cannot be deleted.

**Response (204):** No Content.

**Errors:**
- `404` — Character not found
- `403` — Not the owner
- `409` — Character is in active combat

### GET /characters/campaign/{campaignId}

List all active characters assigned to a campaign. DM only since R3; before it, any member could list them (D155).

**Response (200):** Array of character objects.

**Errors:**
- `403` — Not the campaign's DM
- `404` — Campaign not found

### POST /characters/{characterId}/level-up

Level up a character. Supports multiclassing — if classId differs from the character's current class, prerequisite validation runs (must meet both exit prereqs for current class and entry prereqs for the new class, per PHB multiclass rules).

**Request:**
```json
{
  "classId": "uuid"
}
```

`classId` is required — specifies which class to take the new level in.

**Response (200):**
```json
{
  "character": { /* full character object */ },
  "pendingChoices": {
    "asiAvailable": true,
    "subclassRequired": false,
    "newFeatures": ["Extra Attack"],
    "maxSpellLevel": 0
  }
}
```

If `asiAvailable` is true, the client should prompt for ASI/feat choice via `/apply-choices`. If `subclassRequired` is true, the client should prompt for subclass selection.

**Errors:**
- `409` — Character at max level (20) / Multiclass prerequisites not met
- `400` — Class not found

### POST /characters/{characterId}/level-down

Remove the most recent level. Reverses HP, features, ASI choices, hit dice, and multiclass entries from the last `levelHistory` entry. If the removed level was the only level in a secondary class, that class is removed from `multiclassEntries` entirely.

**Response (200):** Full character object.

**Errors:**
- `409` — Character at minimum level (1) / No level history to revert

### POST /characters/{characterId}/apply-choices

Apply pending choices after a level-up (ASI/feat selection, subclass selection).

**Request:**
```json
{
  "asi": {
    "type": "ability",
    "increases": [{"ability": "strength", "bonus": 2}]
  },
  "subclassId": "uuid"
}
```

For `type: "feat"`, provide `featId` (UUID) for full feat automation. The server loads the feat's `effects` JSON and auto-applies all mechanical bonuses (ability scores, proficiencies, resistances, expertise, speed, initiative, HP per level, passive stats, resources, spells). Provide choice fields as needed: `featAbility` (half-feat ability), `resistanceChoice`, `skillProficiencyChoices`, `savingThrowChoice`, `expertiseSkillChoices`, `toolProficiencyChoices`, `languageChoices`, `weaponChoices`, `spellIds`, `optionalFeatureIds`. Legacy `featName` (string) path still works for text-only feat recording. Both `asi` and `subclassId` are optional — provide whichever choices are pending. All applied effects are recorded in `levelHistory[].choices.asi.appliedEffects` for deterministic reversal on level-down.

**Response (200):** Full character object with updated ability scores / subclass.

### GET /characters/{characterId}/eligible-classes

List all 13 classes with their multiclass prerequisite status relative to the character's current ability scores. Used by the level-up UI to show which classes the character can multiclass into.

**Response (200):**
```json
[
  {
    "classId": "uuid",
    "className": "Fighter",
    "currentClassLevel": 3,
    "currentClass": true,
    "meetsPrerequisites": true,
    "prerequisiteDescription": "Current class"
  },
  {
    "classId": "uuid",
    "className": "Wizard",
    "currentClassLevel": 0,
    "currentClass": false,
    "meetsPrerequisites": false,
    "prerequisiteDescription": "INT 13 (you have 10 ✗)"
  }
]
```

Classes are sorted: current class(es) first, then eligible classes, then ineligible (sorted alphabetically within each group).

## Monsters

### GET /monsters

Search and filter monsters. Paginated.

**Query params:** `name`, `type`, `cr`, `source`, `page`, `size`, `sort`

All filter params (`type`, `cr`, `source`) accept comma-separated values for multiselect (e.g., `type=Dragon,Undead`).

**Response (200):** Spring Page of Monster objects (name, type, challengeRating, hitPoints, armourClass, speed, stats, traits, actions, etc.). JSONB fields (speed, traits, actions, etc.) are returned as raw JSON via `@JsonRawValue`.

### GET /monsters/{id}

Get a single monster by ID.

### GET /monsters/search

Fuzzy search monsters by name. Uses PostgreSQL `pg_trgm` extension with `word_similarity()` for typo-tolerant matching, combined with ILIKE for exact substring matches. Results ranked: exact prefix > substring > fuzzy similarity (threshold 0.4).

**Query params:** `name` (required), `maxResults` (default 10, max 20)

**Example:** `/monsters/search?name=gobln` returns Goblin, Goblin Boss, etc.

**Response (200):** Array of Monster objects (not paginated).

### GET /monsters/filters/types, /monsters/filters/challenge-ratings, /monsters/filters/sources

Return distinct filter values for dropdowns.

## Spells

### GET /reference/spells

Search and filter spells. Paginated.

**Query params:** `name`, `level`, `school`, `source`, `className`, `subclass`, `concentration`, `ritual`, `page`, `size`, `sort`

All filter params except `concentration` and `ritual` accept comma-separated values for multiselect. `level` accepts comma-separated integers (e.g., `level=0,1,3`). `className` and `subclass` both accept comma-separated values (e.g., `className=Wizard,Cleric&subclass=Cleric%20(Knowledge)`); they are combined into a single list and matched with OR logic against the spell's jsonb `classes` array.

### GET /reference/spells/{id}

Get a single spell by ID.

### GET /reference/spells/filters/schools, /filters/sources, /filters/classes

Return distinct filter values. Classes excludes subclass entries (entries containing parentheses).

### GET /reference/spells/filters/subclasses?className={class}

Return subclass entries for a given class (e.g., `Cleric (Knowledge)` for className=`Cleric`).

### GET /reference/spells/targeting?name={name}&slotLevel={level}

Returns targeting constraints for a spell at a given cast/upcast level. Used by the Cast Spell modal to enforce target limits.

**Query params:** `name` (required, case-insensitive), `slotLevel` (default 0)

**Response:**
```json
{
  "maxTargets": 3,
  "selfOnly": false,
  "canTargetSelf": true,
  "canTargetAllies": true,
  "canTargetEnemies": false,
  "targetType": "MULTI_TARGET"
}
```

`maxTargets` is -1 for unlimited (AOE spells). Upcast scaling is applied automatically based on `targetCountUpcastScaling` in the spell effect template.

## Items

### GET /reference/items

Search and filter items. Paginated.

**Query params:** `name`, `type`, `rarity`, `source`, `page`, `size`, `sort`

Filter params `type`, `rarity`, and `source` accept comma-separated values for multiselect (e.g., `type=Weapon,Armor`).

### GET /reference/items/{id}

Get a single item by ID.

### GET /reference/items/filters/types, /filters/rarities, /filters/sources

Return distinct filter values.

## Conditions

### GET /reference/conditions

Return all conditions sorted by name.

### GET /reference/conditions/{id}

Get a single condition by ID.

## Quick Rules Reference

### GET /reference/quickref

Return the full quick reference data from `bookref-quick.json`. Response is a JSON array of 5 chapter objects, each containing an `entries` array of sections with nested content (text, tables, lists, insets, etc.) using 5e.tools markup format.

### GET /reference/races

List all races sorted by name. Optional `?source=PHB` filter.

### GET /reference/races/{id}

Get a single race by ID.

### GET /reference/races/filters/sources

List distinct race source codes.

### GET /reference/classes

List all 13 character classes sorted by name. Includes hit dice, spellcasting info, spell slot progression, skill choices, and proficiencies.

### GET /reference/classes/{id}

Get a single class by ID.

### GET /reference/classes/{id}/subclasses

List subclasses for a given class, sorted by name. Includes features and always-prepared spells.

### GET /reference/backgrounds

List all 101 backgrounds sorted by name. Includes skill/tool/language proficiencies and background feature.

### GET /reference/backgrounds/{id}

Get a single background by ID.

### GET /reference/feats

List all 108 feats sorted by name. Includes prerequisites, description, ability score increases, and effects (structured mechanical data for automation).

### GET /reference/feats/{id}

Get a single feat by ID.

### GET /reference/optional-features

List optional features (Eldritch Invocations, Fighting Styles, Battle Maneuvers, Metamagic).

**Query params:** `type` — filter by normalized type string (`EldritchInvocation`, `Metamagic`, `BattleManeuver`, `FightingStyle`).

**Response (200):** Array of optional feature objects with `id`, `name`, `source`, `featureType`, `description`, `prerequisite`.

**Count:** 114 total (54 EI, 23 BM, 27 FS, 10 MM).

## Encounters

All endpoints require authentication. The three reads (`GET /encounters/{id}`, `GET /encounters/campaign/{campaignId}` and `GET /encounters/join/{sessionCode}`) need membership of the encounter's campaign; every other endpoint is for the campaign's DM only (D155). An action in the wrong encounter status, for example deleting an encounter that isn't PREPARING, returns 409.

**Every encounter response is the caller's view** (D157): the DM View, with everything, for the campaign's DM, and the Player View for everyone else. The Player View leaves out hidden participants, strips monsters' DM-only fields and, unless the campaign shows exact monster HP, replaces their HP with a Health Status; see [[websocket-protocol#Player View (D157)]].

After every mutation, the server publishes the DM View to `/topic/encounter/{id}/dm-state` and the Player View to `/topic/encounter/{id}/state`. (Before R3, one unfiltered state went to `/topic/encounter/{id}/state` and to every caller.)

### POST /encounters

Create a new encounter in PREPARING status.

**Request:**
```json
{
  "campaignId": "uuid",
  "name": "Goblin Ambush",
  "description": "Optional description"
}
```

**Response (201):** Full encounter object with empty participants list.

### GET /encounters/campaign/{campaignId}

List all encounters for a campaign, ordered by creation date (newest first).

**Response (200):** Array of encounter objects.

### GET /encounters/{id}

Get a single encounter with all participants.

**Response (200):** Full encounter object.

### DELETE /encounters/{id}

Delete an encounter. Only works in PREPARING status.

### POST /encounters/{id}/participants

Add a participant (monster or player character).

**Request:**
```json
{
  "participantType": "MONSTER",
  "monsterId": "uuid",
  "displayName": "Goblin",
  "quantity": 3
}
```

For `PLAYER` type, provide `characterId` instead of `monsterId`. HP, AC, and initiative modifier are auto-populated from the monster or character entity.

When `quantity` > 1, participants are named sequentially ("Goblin 1", "Goblin 2", "Goblin 3"), continuing from any existing participants with the same base name.

**Response (200):** Full encounter object with updated participants.

### PATCH /encounters/{id}/participants/{participantId}/name

Rename a participant. Updates the `displayName` while preserving the underlying `monsterId` foreign key so the system still knows what creature the participant is.

**Request:**
```json
{
  "displayName": "Bob the Goblin"
}
```

**Response (200):** Full encounter object.

**Errors:**
- `400` — displayName is required / blank
- `404` — Participant not found

### PATCH /encounters/{id}/participants/{participantId}/visibility

Hide a monster from players, or show it again, in any encounter status. DM only. New in R3 (D157); it is the first endpoint that sets `isVisibleToPlayers`.

**Request:**
```json
{
  "visible": false
}
```

A hidden monster is left out of the Player View, so players don't see it in the initiative list or the spell target pickers, and their combat log leaves out entries about it.

**Response (200):** Full encounter object (the caller's view).

**Errors:**
- `400` — `visible` is missing, or the participant isn't a monster ("Only monsters can be hidden from players")
- `404` — Participant not found

### DELETE /encounters/{id}/participants/{participantId}

Remove a participant from the encounter.

**Response (200):** Full encounter object.

### POST /encounters/{id}/initiatives

Set initiative values for specific participants.

**Request:**
```json
{
  "initiatives": [
    { "participantId": "uuid", "initiative": 18 },
    { "participantId": "uuid", "initiative": 12 }
  ]
}
```

**Response (200):** Full encounter object with updated initiatives and sort order.

### POST /encounters/{id}/initiatives/roll

Roll initiative (1d20 + modifier) for all participants. Updates sort order.

**Response (200):** Full encounter object.

### POST /encounters/{id}/start

Transition from PREPARING to ACTIVE. Requires all participants to have initiative set (409 otherwise). Generates a session code and marks the first participant (by initiative order) as current turn.

**Response (200):** Full encounter object with session code.

### POST /encounters/{id}/pause

Transition from ACTIVE to PAUSED.

### POST /encounters/{id}/resume

Transition from PAUSED to ACTIVE.

### POST /encounters/{id}/end

Transition to COMPLETED.

### GET /encounters/join/{sessionCode}

Look up an encounter by session code. Used by players to join a live encounter. Requires membership of the encounter's campaign since R3; before it, anyone signed in could look one up (D155).

**Response (200):** Full encounter object (the caller's view).

**Errors:**
- `404` — Invalid session code
- `403` — Not a member of the encounter's campaign

### Encounter Response Shape

```json
{
  "id": "uuid",
  "campaignId": "uuid",
  "campaignName": "Curse of Strahd",
  "name": "Goblin Ambush",
  "description": "...",
  "status": "ACTIVE",
  "currentTurnIndex": 2,
  "roundNumber": 1,
  "sessionCode": "X43EWPQ2",
  "participants": [
    {
      "id": "uuid",
      "participantType": "PLAYER",
      "characterId": "uuid",
      "monsterId": null,
      "displayName": "Aragorn",
      "initiative": 18,
      "initiativeModifier": 3,
      "sortOrder": 0,
      "hpMax": 52,
      "hpCurrent": 52,
      "hpTemp": 0,
      "armourClass": 18,
      "activeConditions": null,
      "concentrationSpell": null,
      "spellSlotsCurrent": "{\"1\":{\"max\":4,\"remaining\":3},\"2\":{\"max\":3,\"remaining\":3}}",
      "isVisibleToPlayers": true,
      "isAlive": true,
      "isCurrentTurn": true,
      "controlledByUserId": "uuid",
      "deathSaveSuccesses": 0,
      "deathSaveFailures": 0,
      "notes": null,
      "healthStatus": null
    }
  ],
  "createdAt": "2026-07-18T10:00:00Z"
}
```

`healthStatus` is set only in the Player View, on a monster whose HP it hides: `UNHARMED`, `INJURED`, `BLOODIED` or `DOWN`, with `hpCurrent`, `hpMax` and `hpTemp` then `null`. The Player View also sets a monster's `monsterId`, `notes`, `spellsKnown`, `spellSlotsCurrent`, `spellAttackBonus`, `spellSaveDc`, `spellcastingAbility` and `resourcePoolsCurrent` to `null`, and leaves out participants hidden from players (D157).

## Combat

All combat endpoints are under `/api/encounters/{encounterId}/combat`. The encounter must be in ACTIVE or PAUSED status (409 otherwise). The DM may act for any participant; a player may act only for a participant they control, and may attack or cast spells only on that participant's turn (403 otherwise, D028, D155). Setting HP, restoring spell slots, monster actions and spells, Legendary Resistance, lair status and turn changes are DM only. A participant named in the body or query string that isn't in the encounter is a 400.

After every mutation, the server publishes the DM View and the Player View, and the response, including `encounterState` in spell results, is the caller's view (D157).

### POST /encounters/{id}/combat/attack

Roll an attack (d20 + modifier vs AC). On hit, automatically rolls damage dice and applies damage. Supports advantage/disadvantage. Natural 20 doubles damage dice (critical hit); natural 1 auto-misses (unless forceCrit is true).

**Attacking unconscious/downed targets:** Attacks against dying player characters (0 HP, < 3 death save failures) auto-hit without rolling a d20. Damage causes 1 death save failure (2 on critical hit). If damage >= target's max HP, the target dies instantly (massive damage). Advantage defaults on in the frontend UI for unconscious targets.

**Request:**
```json
{
  "targetId": "uuid",
  "attackBonus": 5,
  "damageDice": "1d8+3",
  "damageType": "slashing",
  "advantage": null,
  "forceCrit": false
}
```

- `attackBonus`: total attack modifier (e.g. +5)
- `damageDice`: dice expression in NdS+M format (e.g. "2d6+3", "1d8+4")
- `damageType`: optional damage type string
- `advantage`: `true` = advantage (roll 2d20, take higher), `false` = disadvantage (take lower), `null` = normal
- `forceCrit`: `true` = treat as critical hit regardless of roll (doubles damage dice, overrides nat 1). Used for melee attacks against unconscious targets within 5 feet (PHB Unconscious condition).

**Query params:** `actorId` (optional) — the attacking participant.

**Response (200):** Full encounter object. Two combat log entries are created: one ATTACK (hit/miss) and one DAMAGE (if hit).

### POST /encounters/{id}/combat/damage

Apply damage to a participant. Temp HP absorbs damage first. Dropping to 0 HP kills monsters outright; players enter the dying state (death saves reset, concentration dropped). Automatically triggers a concentration check if the target is concentrating.

**Damaging unconscious/downed targets:** Damage against dying player characters (0 HP, < 3 death save failures) causes 1 death save failure. If damage >= target's max HP, the target dies instantly (massive damage, PHB pg.197).

**Request:**
```json
{
  "targetId": "uuid",
  "amount": 15,
  "damageType": "fire"
}
```

**Query params:** `actorId` (optional) — the participant dealing damage (for combat log attribution).

**Response (200):** Full encounter object.

### POST /encounters/{id}/combat/heal

Heal a participant. Capped at max HP. Healing a dying player (0 HP) revives them, resets death saves, removes the Unconscious condition, and auto-applies Prone. Also works on dead players (3 death save failures) for resurrection effects like Revivify — same mechanics apply.

**Request:**
```json
{
  "targetId": "uuid",
  "amount": 8
}
```

**Query params:** `actorId` (optional).

**Response (200):** Full encounter object.

### POST /encounters/{id}/combat/hp

Directly set a participant's current HP and/or temp HP (DM override). Updates alive/dying status automatically.

**Request:**
```json
{
  "targetId": "uuid",
  "hpCurrent": 25,
  "hpTemp": 5
}
```

**Response (200):** Full encounter object.

### POST /encounters/{id}/combat/condition/add

Add a condition to a participant. Condition names are stored lowercase. Duplicates are ignored. Conditions with a duration are automatically removed at the start of the affected creature's turn after the specified number of rounds.

**Request:**
```json
{
  "targetId": "uuid",
  "condition": "poisoned",
  "duration": 3
}
```

- `duration`: optional, number of rounds. `null` = indefinite (must be manually removed).

Active conditions are now stored as objects: `[{"name":"poisoned","duration":3,"appliedRound":1}]`. Legacy string arrays are handled transparently.

### POST /encounters/{id}/combat/condition/remove

Remove a condition from a participant.

**Request:** Same shape as add.

### POST /encounters/{id}/combat/death-save

Roll a death saving throw for a dying player character. Server rolls 1d20:
- Natural 20: regain 1 HP, revive
- Natural 1: 2 failures
- 10+: 1 success (3 successes = stabilized)
- 2-9: 1 failure (3 failures = death)

**Request:**
```json
{
  "participantId": "uuid"
}
```

### POST /encounters/{id}/combat/concentration

Set or clear a participant's concentration spell. Setting a new spell while already concentrating logs the loss of the previous spell.

**Request:**
```json
{
  "participantId": "uuid",
  "spellName": "Bless"
}
```

Pass `spellName: null` to clear concentration.

### POST /encounters/{id}/combat/spell-slot/use

Use a spell slot. Decrements the remaining count for the specified level. Players can use their own slots; DM can use anyone's.

**Request:**
```json
{
  "participantId": "uuid",
  "slotLevel": 3
}
```

**Errors:**
- `409` — No remaining slots at that level

### POST /encounters/{id}/combat/spell-slot/restore

Restore a spell slot (DM only). Increments the remaining count.

**Request:** Same shape as use.

**Errors:**
- `409` — Already at maximum
- `400` — The participant has no slots at that level

### POST /encounters/{id}/combat/cast-spell

Cast a spell as a combat action. Auto-resolves ~184 spells using the SpellResolverEngine; remaining spells deduct the slot and log the cast for DM adjudication.

**Query params:** `actorId` (UUID) — required, the caster participant ID.

**Request:**
```json
{
  "spellName": "Guiding Bolt",
  "slotLevel": 1,
  "targetIds": ["uuid"],
  "advantage": null,
  "usePactSlot": false,
  "overrideSpellAttackBonus": null,
  "overrideSpellSaveDC": null
}
```

- `slotLevel` — 0 for cantrips (no slot deducted), 1–9 for leveled spells
- `advantage` — `true` for advantage, `false` for disadvantage, `null` for normal
- `usePactSlot` — if true, deducts from pact magic slots instead of regular slots
- `overrideSpellAttackBonus` / `overrideSpellSaveDC` — DM overrides for monsters (which don't have snapshot spell stats)

**Response (200):**
```json
{
  "encounterState": { "...full encounter response..." },
  "spellName": "Guiding Bolt",
  "slotLevelUsed": 1,
  "autoResolved": true,
  "resultSummary": "Guiding Bolt hits Goblin 1 for 14 radiant damage",
  "targets": [
    {
      "targetId": "uuid",
      "targetName": "Goblin 1",
      "outcome": "HIT",
      "damage": 14,
      "healing": null,
      "conditionsApplied": [],
      "attackRoll": 18,
      "saveRoll": null
    }
  ],
  "manualResolutionReason": null
}
```

**Delivery methods resolved:**
- `SPELL_ATTACK` — d20 + spell attack bonus vs target AC (nat 20 crits, nat 1 misses)
- `SAVING_THROW` — target rolls d20 + save mod vs spell save DC (half damage on save if applicable)
- `AUTO_HIT` — effects applied without rolls (e.g., Magic Missile)
- `SELF` — effects applied to caster only

**Auto-handled mechanics:**
- Cantrip scaling by total character level (not class level)
- Upcast damage scaling
- Slot deduction (regular or pact)
- Concentration replacement with condition cascade
- Silence check (blocks verbal component spells)
- Damage applied through existing pipeline (temp HP, death saves, concentration checks)
- Conditions applied with source tracking (`sourceSpellName`, `sourceParticipantId`, `sourceRequiresConcentration`)

**Errors:**
- `409` — No spell slot remaining at that level / Caster is silenced and the spell has a verbal component
- `400` — More targets than the spell allows at that level

A spell the resolver can't automate, including an unknown spell, is not an error: the slot is spent and the response has `autoResolved: false` with a `manualResolutionReason`.

### POST /encounters/{id}/combat/repeat-spell-effect

Repeat the damaging effect of a concentration spell without consuming a spell slot. Used for spells like Call Lightning, Flaming Sphere, Moonbeam, etc. that have recurring effects each turn.

**Query params:** `actorId` (UUID) — required, the caster participant ID. Must have active concentration.

**Request:**
```json
{
  "targetIds": ["uuid"],
  "advantage": null,
  "overrideSpellAttackBonus": null,
  "overrideSpellSaveDC": null
}
```

**Response (200):** Same `CastSpellResponse` format as cast-spell.

**Supported spells (20):** Call Lightning, Moonbeam, Flaming Sphere, Dust Devil, Heat Metal, Vampiric Touch, Flame Blade, Maximilian's Earthen Grasp, Melf's Minute Meteors, Witch Bolt, Create Bonfire, Cloud of Daggers, Spirit Guardians, Spiritual Weapon, Searing Smite, Ensnaring Strike, Tasha's Caustic Brew, Phantasmal Force, Hunger of Hadar, Aura of Vitality.

**Mechanics:**
- No spell slot consumed — uses the slot level stored from the original cast (`concentrationSlotLevel` or `activeSpellSlotLevel`)
- Works for both concentration spells and non-concentration persistent spells (e.g., Spiritual Weapon)
- Upcast scaling applied based on stored slot level
- Supports healing repeats (Aura of Vitality: 2d6 healing)
- Witch Bolt special case: repeat is AUTO_HIT (no save), 1d12 lightning, no upcast scaling
- Damage resistances/immunities/vulnerabilities applied to targets
- Logged as `SPELL_EFFECT_REPEAT` action type

**Errors:**
- `409` — Caster has no active concentration or persistent spell

A spell with no repeatable effect is not an error: the response has `autoResolved: false` with a `manualResolutionReason`.

### POST /encounters/{id}/combat/turn/next

Advance to the next participant in initiative order. Increments round number when wrapping to the top. Automatically removes expired conditions on the participant whose turn is starting (conditions with a `duration` that has elapsed).

### POST /encounters/{id}/combat/turn/previous

Go back to the previous participant. Decrements round number when wrapping (minimum round 1).

### GET /encounters/{id}/combat/log

Get the full combat log for the encounter, ordered chronologically. Campaign members only (D155, R3; before it, any signed-in user could read it). The DM gets every entry. Players get the log without entries whose actor, target or Legendary Resistance target is hidden, without the Legendary Resistance fields (`legendaryResistanceEligible`, `lrTargetId`, `lrDamageDealt`, `lrConditionsApplied`, `lrResolved`), and with `turnParticipantName` cleared on entries logged during a hidden participant's turn (D157).

**Response (200):**
```json
[
  {
    "id": "uuid",
    "roundNumber": 1,
    "actorId": "uuid",
    "actorName": "Goblin 1",
    "targetId": "uuid",
    "targetName": "Aragorn",
    "actionType": "DAMAGE",
    "description": "Goblin 1 deals 7 damage to Aragorn (piercing)",
    "rollValue": null,
    "rollTotal": null,
    "damageDealt": 7,
    "healingDone": null,
    "turnParticipantName": "Goblin 1",
    "createdAt": "2026-07-18T10:05:00Z"
  }
]
```

**Action types:** `ATTACK`, `DAMAGE`, `HEAL`, `CONDITION_ADD`, `CONDITION_REMOVE`, `DEATH_SAVE`, `CONCENTRATION_CHECK`, `CONCENTRATION_LOST`, `TURN_ADVANCE`, `TURN_BACK`, `STABILIZE`, `KILL`, `REVIVE`, `SPELL_SLOT_USE`, `SPELL_SLOT_RESTORE`, `SPELL_CAST`

## Error Response Format

All errors return a consistent format:

```json
{
  "error": "Human-readable error message"
}
```

HTTP status codes used (D155, R3). Services throw the domain exceptions in `shared/`, and `GlobalExceptionHandler` maps them:
- `200` — Success
- `201` — Created
- `204` — No Content (successful deletion, logout)
- `400` — Bad input (`ValidationException` and Spring's binding errors): failed validation, with a message per field; an unknown ID in the request body, such as a `raceId` or a participant ID; a malformed UUID or a parameter of the wrong type (e.g. `?level=abc`); a missing or unreadable body; a missing required parameter; wrong login details
- `401` — Unauthorized: no access token, or an expired or invalid one, on a protected endpoint; or a missing, expired, revoked or reused refresh cookie on `/auth/refresh`. The frontend answers a 401 with one refresh and a retry
- `403` — Forbidden (`ForbiddenException`): signed in but not allowed. Not a member of the campaign, not its DM, not the character's owner, acting for a participant you don't control, or a player attacking or casting outside their turn
- `404` — Not found (`NotFoundException`): the resource named in the URL doesn't exist (campaign, character, encounter, participant, session code, combat log entry), or the path is unknown
- `409` — Conflict (`ConflictException`): the wrong state for the action (e.g. the encounter isn't active, the character is in an active encounter, already a member, username taken, no spell slot left), or two people changed the same data at once (optimistic locking, D034: "Someone else changed this at the same time. Please try again.")
- `500` — Anything unexpected. Outside the dev profile the body is always `{"error": "Something went wrong. Please try again."}`; only dev returns the real message (`server.error.include-message: always`)

Before R3, "not found" and "not allowed" both returned 400 (`IllegalArgumentException`), an expired token returned 403, and optimistic-lock conflicts returned 500 (superseded in R3, D155).
