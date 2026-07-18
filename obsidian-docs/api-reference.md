# API Reference

Base URL: `http://localhost:8080/api` (dev) or `https://yourdomain.com/api` (prod)

All authenticated endpoints require the header: `Authorization: Bearer <accessToken>`

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
  "accessToken": "eyJ...",
  "refreshToken": "eyJ..."
}
```

**Errors:**
- `400` — Username already taken / Email already registered / Validation failed

### POST /auth/login

**Request:**
```json
{
  "username": "aragorn",
  "password": "str1derR4nger"
}
```

**Response (200):** Same shape as register response.

**Errors:**
- `400` — Invalid username or password

### POST /auth/refresh

Exchange a valid refresh token for new access + refresh tokens.

**Request:**
```json
{
  "refreshToken": "eyJ..."
}
```

**Response (200):** Same shape as register response (new token pair).

**Errors:**
- `400` — Invalid or expired refresh token

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
- `400` — Invalid invite code / Already a member

### GET /campaigns

List all campaigns the authenticated user is a member of (as DM or Player).

**Response (200):** Array of campaign objects.

### GET /campaigns/{campaignId}

Get full details of a specific campaign. User must be a member.

**Response (200):** Campaign object with members list.

**Errors:**
- `400` — Campaign not found / Not a member

## Characters

All endpoints require authentication.

### POST /characters

Create a new player character owned by the authenticated user.

**Request:**
```json
{
  "name": "Gandalf",
  "race": "Human",
  "characterClass": "Wizard",
  "subclass": "School of Evocation",
  "level": 20,
  "background": "Sage",
  "alignment": "Neutral Good",
  "strength": 10,
  "dexterity": 14,
  "constitution": 16,
  "intelligence": 20,
  "wisdom": 18,
  "charisma": 16,
  "hpMax": 120,
  "armourClass": 15,
  "initiativeBonus": 2,
  "speed": 30,
  "proficiencyBonus": 6,
  "campaignId": "uuid (optional)"
}
```

**Response (201):** Full character object.

### PUT /characters/{characterId}

Update an existing character. Only the owner can update. All fields are optional — only provided fields are updated.

**Request:** Any subset of character fields.

**Response (200):** Full updated character object.

**Errors:**
- `400` — Character not found / Not the owner

### GET /characters

List all active characters owned by the authenticated user.

**Response (200):** Array of character objects.

### GET /characters/{characterId}

Get a specific character by ID.

**Response (200):** Full character object.

### GET /characters/campaign/{campaignId}

List all active characters assigned to a campaign. User must be a member of the campaign.

**Response (200):** Array of character objects.

**Errors:**
- `400` — Not a member of this campaign

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

## Encounters

All endpoints require authentication. The authenticated user must be the DM of the encounter's campaign (except `GET /encounters/join/{code}`).

After every mutation, the updated encounter state is broadcast via WebSocket to `/topic/encounter/{id}/state`.

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

**Response (200):** Full encounter object with empty participants list.

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

Transition from PREPARING to ACTIVE. Requires all participants to have initiative set. Generates a session code and marks the first participant (by initiative order) as current turn.

**Response (200):** Full encounter object with session code.

### POST /encounters/{id}/pause

Transition from ACTIVE to PAUSED.

### POST /encounters/{id}/resume

Transition from PAUSED to ACTIVE.

### POST /encounters/{id}/end

Transition to COMPLETED.

### GET /encounters/join/{sessionCode}

Look up an encounter by session code. Used by players to join a live encounter. Does not require campaign membership.

**Response (200):** Full encounter object.

**Errors:**
- `400` — Encounter not found

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
      "notes": null
    }
  ],
  "createdAt": "2026-07-18T10:00:00Z"
}
```

## Combat

All combat endpoints are under `/api/encounters/{encounterId}/combat`. The encounter must be in ACTIVE or PAUSED status. After every mutation, the updated encounter state is broadcast via WebSocket.

### POST /encounters/{id}/combat/attack

Roll an attack (d20 + modifier vs AC). On hit, automatically rolls damage dice and applies damage. Supports advantage/disadvantage. Natural 20 doubles damage dice (critical hit); natural 1 auto-misses.

**Request:**
```json
{
  "targetId": "uuid",
  "attackBonus": 5,
  "damageDice": "1d8+3",
  "damageType": "slashing",
  "advantage": null
}
```

- `attackBonus`: total attack modifier (e.g. +5)
- `damageDice`: dice expression in NdS+M format (e.g. "2d6+3", "1d8+4")
- `damageType`: optional damage type string
- `advantage`: `true` = advantage (roll 2d20, take higher), `false` = disadvantage (take lower), `null` = normal

**Query params:** `actorId` (optional) — the attacking participant.

**Response (200):** Full encounter object. Two combat log entries are created: one ATTACK (hit/miss) and one DAMAGE (if hit).

### POST /encounters/{id}/combat/damage

Apply damage to a participant. Temp HP absorbs damage first. Dropping to 0 HP kills monsters outright; players enter the dying state (death saves reset). Automatically triggers a concentration check if the target is concentrating.

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

Heal a participant. Capped at max HP. Healing a dying player (0 HP) revives them and resets death saves.

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
- `400` — No remaining slots at that level

### POST /encounters/{id}/combat/spell-slot/restore

Restore a spell slot (DM only). Increments the remaining count.

**Request:** Same shape as use.

**Errors:**
- `400` — Already at maximum

### POST /encounters/{id}/combat/turn/next

Advance to the next participant in initiative order. Increments round number when wrapping to the top. Automatically removes expired conditions on the participant whose turn is starting (conditions with a `duration` that has elapsed).

### POST /encounters/{id}/combat/turn/previous

Go back to the previous participant. Decrements round number when wrapping (minimum round 1).

### GET /encounters/{id}/combat/log

Get the full combat log for the encounter, ordered chronologically.

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
    "createdAt": "2026-07-18T10:05:00Z"
  }
]
```

**Action types:** `ATTACK`, `DAMAGE`, `HEAL`, `CONDITION_ADD`, `CONDITION_REMOVE`, `DEATH_SAVE`, `CONCENTRATION_CHECK`, `CONCENTRATION_LOST`, `TURN_ADVANCE`, `TURN_BACK`, `STABILIZE`, `KILL`, `REVIVE`, `SPELL_SLOT_USE`, `SPELL_SLOT_RESTORE`

## Error Response Format

All errors return a consistent format:

```json
{
  "error": "Human-readable error message"
}
```

HTTP status codes used:
- `200` — Success
- `201` — Created
- `400` — Bad request (validation, business logic)
- `401` — Unauthorized (missing/invalid token)
- `403` — Forbidden (valid token but insufficient permissions)
