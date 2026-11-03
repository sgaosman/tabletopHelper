# API Reference

> **Source of truth (R4, D171):** the springdoc OpenAPI spec is authoritative: Swagger UI at http://localhost:8080/swagger-ui.html while the dev backend runs, or the committed `frontend/src/api/generated/openapi.json`, which `OpenApiSpecIT` keeps in step with the backend. This page is a readable summary and may lag behind it. Access rules follow D155; Player View redactions are in [[websocket-protocol#Player View (D157)]].

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
  "promptPlayerRepeatSaves": false,
  "allowPlayerManualModifiers": true,
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
- `promptPlayerRepeatSaves` — `true` leaves a player character's Repeat Saves for the player to roll, through `POST /encounters/{id}/combat/repeat-save`; `false` (the default) has the server roll them, as it does for monsters. New in R5 (D162).
- `allowPlayerManualModifiers` — `true` (the default) lets players enter Manual Modifiers in combat, as the DM always may: a spell attack bonus, a spell save DC or a forced critical hit. `false` refuses a player's Manual Modifier with a 403. Each use is logged. New in R5 (D164).

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
  "level": 5,
  "alignment": "Neutral Good",
  "abilityScoreMethod": "standard_array",
  "strength": 10,
  "dexterity": 14,
  "constitution": 16,
  "intelligence": 20,
  "wisdom": 18,
  "charisma": 16,
  "campaignId": "uuid (optional)",
  "savingThrowProficiencies": ["INT", "WIS"],
  "skillProficiencies": ["Arcana", "History"],
  "armorProficiencies": ["Light armor"],
  "weaponProficiencies": ["Daggers", "Darts", "Slings", "Quarterstaffs", "Light crossbows"],
  "toolProficiencies": ["Herbalism kit"],
  "languageProficiencies": ["Common", "Elvish", "Draconic"],
  "damageResistances": ["fire"],
  "racialAbilityBonuses": [{"ability": "INT", "bonus": 2}],
  "spellcastingAbility": "INT",
  "spellsKnown": [
    {"name": "Fire Bolt", "level": 0, "source": "class:Wizard"},
    {"name": "Shield", "level": 1, "source": "class:Wizard", "prepared": true}
  ],
  "features": [
    {"name": "Magic Initiate", "description": "Granted by Astral Drifter background.", "source": "Astral Drifter"}
  ],
  "multiclassClassEntries": [{"classId": "uuid", "level": 3, "subclassId": "uuid"}, {"classId": "uuid2", "level": 2}]
}
```

**JSON fields are structured JSON, not strings (R6 workstream B, D168).** The proficiency, resistance and immunity lists, `features`, `spellsKnown`, `spellSlots`, `equipment`, `currency`, `racialAbilityBonuses`, `multiclassClassEntries`, `multiclassEntries`, `hitDice`, `levelHistory`, `resourcePools` and the response's `subclassAlwaysPreparedSpells` are arrays and objects, in the shapes of the `character.state` records, in requests and responses alike; the spec lists each shape. A JSON field sent as a string is refused with `400`. Before R6 the API carried them as JSON text, such as `"skillProficiencies": "[\"Arcana\"]"`.

- `hitDice` is an object keyed by class name, each entry `{dieSize, total, remaining}`. It replaces `hitDiceMap`, `hitDiceTotal` and `hitDiceRemaining`, which the response no longer has and the requests no longer take (`faces` in the old map is `dieSize`).
- `spellSlots` is an object keyed `"1"` to `"9"`, with a Warlock's pact slots keyed `pact_N`, each `{max, used}`, the shape participants have had since V15. It was `{total, used}`.
- `equipment` entries are `{name, quantity, description, itemId, equipped, attuned}`. `equipped` and `attuned` replace the response's `equippedItems` and `attunedItems`.
- `racialAbilityBonuses` entries are `{ability, bonus}`, with the ability abbreviated, such as `"INT"`, or `null` until the player assigns it.
- `featResources` is gone since R6 workstream D (D158): a feat's limited uses, such as Lucky's luck points, are a `feat:<feat>` pool in `resourcePools`, such as `feat:lucky`.

The request no longer takes the stats the server works out (R6 workstream C, D158, D167): `hpMax`, `armourClass`, `initiativeBonus`, `speed`, `proficiencyBonus`, `spellSlots`, `spellSaveDc`, `spellAttackBonus` and `hitDice`. Armour class starts at 10 + the DEX modifier, because armour isn't modelled, and speed is the race's walking speed. `spellcastingAbility` is only the ability race and feat spells are cast with, such as a Fairy's choice of INT, WIS or CHA; a class's own spells use the class's ability.

For multiclass characters (level >= 2), the `multiclassClassEntries` field contains a JSON array of class entries with `classId`, `level`, and optional `subclassId`. The server builds `multiclassEntries` and `levelHistory` from this data with `LevelUpCalculator.buildMulticlassProgression()`, each level with its `hitDieResult`, and works out `hitDice` and HP from them. The class levels must add up to `level`, or the request answers `400`. The `characterClass` field is auto-set to "ClassName1 / ClassName2" format. The choices the levels bring are made after creation through `POST /characters/{characterId}/apply-choices`, and since R6 workstream F each is a Pending Choice that the server records on the level that owes it, so levelling down reverses the right one (D159).

The `spellsKnown` array uses source prefixes: `"class:ClassName"`, `"race:RaceName"`, `"feat:FeatName"`. Entries support `prepared`, `alwaysPrepared`, `atWill`, `usesPerLongRest`, and `unlocksAtLevel` fields.

**Response (201):** Full character object with auto-populated derived stats (proficiencyBonus, initiativeBonus, armourClass, spellSlots, spellSaveDc, spellAttackBonus, subclassAlwaysPreparedSpells). The `subclassAlwaysPreparedSpells` field is an object keyed by subclass name and then by the class level the spells come at, with an entry for each of the character's subclasses (e.g. `{"Life Domain": {"1": ["Bless", "Cure Wounds"], "3": ["Lesser Restoration", "Spiritual Weapon"]}}`) — populated from the Subclass entity's seeded 5etools data. It is `null` when none of them has any.

Every character response also has `spellcasting` and `abilityScoreMaximums`, worked out when it is read rather than stored (R6 workstream C, D164, D167). `spellcasting` has an entry for each class that casts, in the order the classes were taken: `{className, subclassName, classLevel, ability, spellSaveDc, spellAttackBonus, highestSpellLevel, cantripsKnown, spellsKnown, preparedSpells, pactMagic}`. `spellsKnown` is `null` for a class that prepares its spells and `preparedSpells` for one that knows them; a class without slots yet prepares 0, an Eldritch Knight or Arcane Trickster appears from 3rd level with INT, and `pactMagic` is `true` for a Warlock. The top-level `spellSaveDc`, `spellAttackBonus` and `spellcastingAbility` are the main numbers, which race and feat spells use: the ability is the one chosen at creation, or else the first casting class's. `abilityScoreMaximums` gives each score's maximum: 20, or 24 for STR and CON after Primal Champion.

Since R6 workstream D every character response also has `inLiveEncounter`, which is `true` while the character is a participant in an Active or Paused encounter. Rests, level-up, level-down and apply-choices answer `409` then, and the sheet disables those buttons with a note (D151, D166).

Since R6 workstream E every character response includes the active DM Adjustments (D158). The six ability scores, `armourClass`, `damageResistances` and `damageImmunities` have them, and so do `hpMax`, `initiativeBonus`, `speed` and the spell numbers, as the server works them out. Everything worked out from a score uses the adjusted one: the modifiers, hit points, initiative, spell numbers, multiclass prerequisites, short-rest healing and pool formulas. AC is the value the character was created with plus its AC adjustments. `damageResistances` and `damageImmunities` are always lists now, never `null`, with each damage type once. The response also gains:
- `damageVulnerabilities`, which only DM Adjustments give (D161)
- `baseAbilityScores`, the six scores without the adjustments, `{strength, dexterity, constitution, intelligence, wisdom, charisma}`, which an ASI raises up to the `abilityScoreMaximums`; an adjusted score may already be past its maximum
- `dmAdjustments`, every adjustment, oldest first, with the removed ones kept as the record (see [DM Adjustments](#dm-adjustments-r6-workstream-e-d158) below)

Since R6 workstream F every character response also has `pendingChoices`: the Pending Choices its levels owe that its player hasn't made yet, oldest level first, a subclass first within a level (D159). Each is `{id, type, source, characterLevel, classId, className, classLevel, count, options, maxSpellLevel, swap}`, with the fields its type doesn't use left out:
- `type` is `SUBCLASS`, `FIGHTING_STYLE`, `ASI` (an ASI or a feat in its place), `FEAT` (a race's or background's feat at creation), `EXPERTISE`, `CANTRIPS` or `SPELLS`
- `source` is `LEVEL_UP`, `CREATION`, `RACE` or `BACKGROUND`
- `characterLevel` is the level that owes the choice: 1 for a race's or background's feat, which has no class
- `count` is how many skills, cantrips or spells to choose, and 1 for the others
- `options` is the fixed list it is chosen from, where it has one: the class's subclasses or Fighting Styles, or the feats a background lists. A race's feat and an ASI's feat may be any feat
- `maxSpellLevel` is 0 for `CANTRIPS`, and for `SPELLS` the highest level of spell the class could learn at that class level (PHB p.164)
- `swap` is `true` on a `SPELLS` choice when the class may also replace one spell it knows, as a bard, ranger, sorcerer, warlock, Eldritch Knight or Arcane Trickster may when its Spells Known rise. A Wizard's `SPELLS` choice is the spells it copies into its spellbook for free: six at Wizard 1 and two at each level after (PHB p.114)

Since R6 workstream G every character response also has `dead`, which is `true` once the character has died in an encounter whose results the DM applied (D166). Until the DM of its campaign revives it through `POST /characters/{characterId}/revive` (below), it stays at 0 hit points, and its rests, damage, healing and temporary hit points, level-up, level-down and joining an encounter answer `409`. Its other commands, its choices and its DM Adjustments go on, and it can still be deleted.

Since R6 workstream F creating a character at level N gives it the Pending Choices of levels 1 to N, less what the request chose itself, and a `FEAT` choice for each feat its race gives, such as a Variant Human's, and one for its background's, from the feats the background lists. A `subclassId`, or a subclass in `multiclassClassEntries`, is recorded on its class's subclass level, and one for a class below that level answers `400`. `skillExpertises` are recorded on the levels that give Expertise, oldest first, and any Expertise they don't cover stays pending. The class cantrips and spells in `spellsKnown` count against their class's `CANTRIPS` and `SPELLS` choices, oldest first, but aren't recorded on a level. A feat named in `features`, as the example's Magic Initiate is, is only a feature, with no effects. The creation wizard sends only the 1st level's choices, and the player makes the rest on the sheet (D159).

**Errors:**
- `400` — Unknown `raceId`, `classId`, `subclassId`, `backgroundId` or `campaignId` (IDs in the body are a 400, not a 404), or validation failed
- `400` — The class levels in `multiclassClassEntries` don't add up to `level` (R6 workstream C)
- `400` — A subclass for a class below its subclass level (R6 workstream F, D159)
- `400` — An `equipment` entry without a name, with a quantity below 0 or with an `itemId` that names no item, or a coin below 0 (R6 workstream D, D164)
- `403` — `campaignId` names a campaign the user isn't a member of (D155)
- `409` — Reference data the character needs can't be read, such as a race's speed or a class's features (R6 workstream C, D155)

### Character commands (R6 workstream D, D158)

`PUT /characters/{characterId}` is gone, and with it `CharacterUpdateRequest`. Besides creating a character, levelling it and applying the choices its levels bring, its owner changes it only through the commands below. Each answers `200` with the full character object, its Derived Stats worked out again.

- **Only the owner.** Every command, levelling, apply-choices and `DELETE` included, is the owner's alone: another user gets `403` and an unknown character `404`. Access is checked before the character's row is locked, so neither waits for the lock.
- **One at a time.** Each command locks the character's row, so commands on one character queue and each starts from the last one's committed state. One that waits more than five seconds answers `409` ("Another change is still in progress. Please try again in a moment."). A write from a copy of the character that a command has changed since answers `409` too ("Someone else changed this at the same time. Please try again."), because the character's `version` catches it.
- **During an encounter.** Rests, level-up, level-down and apply-choices answer `409` while the character is in an Active or Paused encounter (D166). The other commands go on, because combat changes the participant, not the sheet.
- **A dead character.** Since R6 workstream G its rests, damage, healing, temporary hit points, level-up and level-down answer `409` until the DM revives it, such as "Aria is dead, so can't take a short rest until the DM revives them". Its other commands go on (D166).
- **What no command sets.** Ability scores, proficiencies and expertise, resistances and immunities, race, background, `abilityScoreMethod` and `racialAbilityBonuses`, which the PUT took, can't be set any more, and neither can AC, which it had ignored since workstream C. HP, temporary HP, slot uses, remaining Hit Dice and pool uses change only through the commands below, and race and feat spells can't be edited. Since R6 workstream E the DM of the character's campaign can change some of these through DM Adjustments (below): the ability scores, AC, HP maximum, speed, initiative and spell numbers by an amount, the damage defences, and the feats.

### PATCH /characters/{characterId}/details

Set the details no rule reads. A field left out, or `null`, keeps its value, and an empty text clears it; the name can't be cleared.

**Request:** any of `name`, `experiencePoints`, `alignment`, `personalityTraits`, `ideals`, `bonds`, `flaws`, `notes` and `portraitUrl`, such as `{"name": "Aria Swift", "notes": "Owes 10 gp"}`.

**Errors:**
- `400` — A blank `name`; a `name` over 200 characters, `alignment` over 50 or `portraitUrl` over 500; or `experiencePoints` below 0

### PUT /characters/{characterId}/campaign

Put the character in a campaign its owner has joined, or take it out of its campaign. It replaces the PUT's `campaignId` and `clearCampaign`.

**Request:** `{"campaignId": "uuid"}`, or `{"campaignId": null}` to take the character out of its campaign

**Errors:**
- `400` — Unknown `campaignId`
- `403` — The owner isn't a member of that campaign

### PUT /characters/{characterId}/inventory

Replace the character's whole inventory and coins. Both fields are required.

**Request:**
```json
{
  "equipment": [
    {"name": "Heirloom blade", "quantity": 1, "itemId": "uuid", "equipped": true},
    {"name": "Torch", "quantity": 3}
  ],
  "currency": {"cp": 0, "sp": 5, "ep": 0, "gp": 12, "pp": 0}
}
```

Each entry needs a name, which is trimmed, and its quantity can't be below 0. `itemId` is optional and links the entry to an item: weapon attacks try it before the entry's name, so an entry linked to a mundane weapon is that weapon, whatever it is called (D164). Creation checks `equipment` and `currency` the same way.

**Errors:**
- `400` — An entry without a name, a quantity or coin below 0, or an `itemId` that names no item

### POST /characters/{characterId}/hp

Damage, healing or temporary hit points on the sheet between encounters (PHB p.198). Damage comes off temporary hit points first and stops at 0, healing stops at the HP maximum and leaves temporary hit points alone, and temporary hit points don't stack, so the character keeps the larger amount. In an encounter, the combat endpoints change the participant instead. Since R6 workstream G Write-back applies what the encounter changed to the sheet when the DM ends it, or removes the character from it while it is ACTIVE (D166).

**Request:** `{"kind": "DAMAGE", "amount": 8}`. `kind` is `DAMAGE`, `HEALING` or `TEMPORARY`, and `amount` is at least 1.

**Errors:**
- `400` — No `kind`, or an `amount` below 1
- `409` — The character is dead, until the DM revives it ("Aria is dead, so can't be healed until the DM revives them"; R6 workstream G, D166)

### POST /characters/{characterId}/spell-slots/{slot}/spend, /spell-slots/{slot}/restore

Spend one slot, or get one spent slot back. `slot` is a spell level, `"1"` to `"9"`, or a pact slot, `"pact_N"`. No body.

**Errors:**
- `404` — The character has no such slot
- `409` — None of those slots is left (`spend`), or none has been spent (`restore`)

### POST /characters/{characterId}/pools/spend, /pools/recover

Spend uses of one of the character's resource pools, or get spent uses back.

**Request:** `{"poolId": "feat:lucky", "amount": 1}`. `amount` is at least 1, and 1 when left out.

**Errors:**
- `400` — The character has no pool with that `poolId`
- `409` — The pool hasn't that many uses left (`spend`), or getting them back would take it past its maximum (`recover`)

### POST /characters/{characterId}/short-rest

A short rest (PHB p.186). The body can be left out.

**Request:**
```json
{
  "hitDice": [
    {"className": "Warlock", "roll": 6},
    {"className": "Wizard"}
  ]
}
```

`hitDice` has one entry for each Hit Die spent, naming the class it comes from as the character's `hitDice` names it. The server rolls a die sent without a `roll`. Each die heals its roll plus the CON modifier, never less than 0, up to the HP maximum. Every die is checked before any is rolled, so a refused rest changes nothing. Pools that reset on a short rest come back by their `resetAmount`, or in full when they have none, and so do Pact Magic slots; a rest with no dice does only that (D127). Song of Rest and Arcane Recovery aren't applied.

**Errors:**
- `400` — A class without Hit Dice, more dice of a class than remain, or a roll outside 1 to the die's size
- `409` — The character is in an Active or Paused encounter
- `409` — The character is dead (R6 workstream G, D166)

### POST /characters/{characterId}/long-rest

A long rest (PHB p.186). No body. It restores every hit point and clears temporary hit points. It brings back spent Hit Dice up to half the character's total, at least one, the largest dice first, so a Fighter/Wizard regains d10s before d6s. Every spell slot comes back, and every pool refills completely, whatever its `resetAmount` (D127).

**Errors:**
- `409` — The character has fewer than 1 hit point, or is in an Active or Paused encounter
- `409` — The character is dead (R6 workstream G, D166)

### PUT /characters/{characterId}/spells/prepared

Set the spells one class that prepares its spells has prepared, replacing those it had prepared.

**Request:** `{"className": "Wizard", "spells": ["Shield"]}`

Each spell must be on the class's spell list (for a Wizard, in its spellbook) and no higher than the class's `highestSpellLevel`, and there can be no more than its `preparedSpells`. Always-prepared spells don't count and stay prepared. A spell the class had prepared but isn't named stays on the sheet, unprepared.

**Errors:**
- `400` — A class the character doesn't cast with, or one that knows its spells; an unknown spell, a cantrip, a spell off the class's list or not in the spellbook, or one above the class's highest level; or too many spells
- `409` — A spell's class lists can't be read

### POST, DELETE /characters/{characterId}/spellbook

Copy spells into a Wizard's spellbook (`POST`), or take them out (`DELETE`, which takes a JSON body too).

**Request:** `{"spells": ["Magic Missile", "Shield"]}`, with at least one spell

A spell copied in must be a wizard spell of 1st level or higher, no higher than the Wizard's `highestSpellLevel`; one already in the book is left as it is. A spell taken out must be in the book. Cantrips aren't kept in a spellbook: since R6 workstream F a Wizard learns them through its levels' `CANTRIPS` Pending Choices, and the spells it adds for free as it gains levels come through its `SPELLS` choices (see `apply-choices` below, D159). This command stays for copying in any other wizard spell.

**Errors:**
- `400` — No Wizard levels; an unknown spell, a cantrip, or a spell off the wizard list or above the Wizard's highest level; or, for `DELETE`, a spell that isn't in the book
- `409` — A spell's class lists can't be read

### PUT /characters/{characterId}/spells/known (removed in R6 workstream F)

Removed in R6 workstream F, with `KnownSpellsRequest` (D159). It was the interim way to set the cantrips one class knows, or the spells a class that knows a set number of spells knows. A class now learns them through its levels' `CANTRIPS` and `SPELLS` Pending Choices, made through `apply-choices` (below), which keeps its checks of the class's list, the spell's level and the class's limit.

A spell is on a class's list if the spells table lists the class, or the class with its subclass, such as `"Warlock (The Hexblade)"`; Eldritch Knights and Arcane Tricksters use the wizard's list. Race and feat spells are left as they are.

### DM Adjustments (R6 workstream E, D158)

The DM of a character's campaign changes the character only through DM Adjustments: named changes on top of what the rules give it, each with a reason its player sees. Both commands answer `200` with the full character object, its Derived Stats worked out again.

- **Only the campaign's DM.** Anyone else gets `403` ("Only the DM of this character's campaign can adjust it"), the character's owner and strangers alike. A character in no campaign has no DM, so everyone gets `403` ("This character isn't in a campaign, so it has no DM to adjust it"). A DM who also owns the character may adjust it. A character that doesn't exist answers `404`. Access is checked before the character's row is locked.
- **One at a time.** Each takes the character's row lock, with the same five-second wait and `409`s as the player commands.
- **During an encounter.** Both are allowed while the character is in an Active or Paused encounter (D166). An adjustment to a defence, an ability score, or a class's spell save DC or attack bonus reaches the character's participants at once, because combat reads those from the sheet. AC, the HP maximum, initiative and the main spell numbers are copied when a participant is added and, since R6 workstream G, again when the encounter starts, so an adjustment to them made once the encounter has started doesn't reach its participants (D166).
- **Logged.** Every use is logged with the DM's, the adjustment's and the character's IDs, and the kind, target and amount, but not the reason.

### POST /characters/{characterId}/adjustments

Add a DM Adjustment. What it needs depends on its `kind`, `STAT`, `DEFENCE` or `FEAT`, and a field that belongs to another kind answers `400`. `reason` is required, at most 200 characters.

**Request (STAT):** `{"kind": "STAT", "target": "ARMOUR_CLASS", "amount": 1, "reason": "Ring of Protection"}`

`target` is `ARMOUR_CLASS`, `HP_MAX`, `SPEED`, `INITIATIVE`, `SPELL_SAVE_DC`, `SPELL_ATTACK_BONUS` or an ability score, `STR`, `DEX`, `CON`, `INT`, `WIS` or `CHA`, in any case. Saving throws and skills aren't adjustable: they follow from the scores. `amount` is a whole number from -100 to 100, negative to take away, and not 0. An ability adjustment comes first, so its modifier and everything worked out from it change; the score may pass its maximum but stays within 1 to 30. Speed stops at 0 and the HP maximum at 1. A higher HP maximum raises `hpCurrent` by as much, and a lower one caps it. A spell adjustment changes every class's numbers and the main ones.

**Request (DEFENCE):** `{"kind": "DEFENCE", "defence": "RESISTANCE", "target": "fire", "reason": "Ring of Fire Resistance"}`

`defence` is `RESISTANCE`, `IMMUNITY` or `VULNERABILITY`, and `target` is one of the 13 damage types, in any case: acid, bludgeoning, cold, fire, force, lightning, necrotic, piercing, poison, psychic, radiant, slashing or thunder (D161). A defence the character already has counts once. Condition immunities can't be added.

**Request (FEAT):** `{"kind": "FEAT", "feat": {"featId": "uuid", "featAbility": "CON", "savingThrowChoice": "CON"}, "reason": "Boon of the mountain"}`

`feat` takes the feat fields an `apply-choices` ASI takes: `featId` and the choices the feat needs, such as `featAbility` or `spellIds`. There is no `target`: the feat names it. The feat's effects are applied as an ASI feat's are and recorded in the adjustment, so removing it reverses them exactly. Its speed, initiative, HP per level and limited uses, such as Lucky's `feat:lucky` pool, count as a level's feat's do. The server doesn't check the feat's prerequisites, and the DM's feat picker offers a feat the character doesn't qualify for, showing what it lacks. The character already has a feat when a feat added its feature, a level took it, or an active grant gave it; since R6 workstream F a feat its race or background gave at creation counts too, because the 1st level records it (D159).

**Errors:**
- `400` — No `kind`; a blank `reason`, or one over 200 characters; a field another kind uses; a STAT without a known `target`, or with an `amount` that is missing, 0 or beyond ±100; a DEFENCE without its `defence` or a damage type; a FEAT without `feat.featId`, or a feat that doesn't exist
- `403` — Not the DM of the character's campaign, or a character in no campaign
- `404` — Character not found
- `409` — A feat the character already has, unless its text says it can be taken more than once, which only Elemental Adept's does ("Aria already has Lucky, which can't be taken again")

### DELETE /characters/{characterId}/adjustments/{adjustmentId}

Remove a DM Adjustment. No body. It stays in `dmAdjustments` as the record, with `removedBy` and `removedAt`, and no longer counts. A FEAT's recorded effects are reversed, and only the feat's latest feature entry goes, so a feat granted twice keeps the other.

**Errors:**
- `403` — Not the DM of the character's campaign, or a character in no campaign
- `404` — Character not found, or the character has no such adjustment ("DM Adjustment not found")
- `409` — The adjustment has already been removed ("That DM Adjustment has already been removed")

### POST /characters/{characterId}/revive

Revive a character who died in an encounter, at 1 hit point (R6 workstream G, D166). No body. Only the DM of the character's campaign may revive it, through the same access check and row lock as a DM Adjustment, and the `403` uses the same words ("Only the DM of this character's campaign can adjust it"). A character in no campaign can't be revived. It sets `hpCurrent` to 1, `hpTemp` to 0 and `dead` to `false`, and the Derived Stats are worked out again. Which spell brought the character back is the DM's to judge (D161). It isn't refused during an encounter, and it is logged with the DM's and the character's IDs.

**Response (200):** Full character object.

**Errors:**
- `403` — Not the DM of the character's campaign, or a character in no campaign
- `404` — Character not found
- `409` — The character isn't dead ("Aria isn't dead")

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

Level up a character. Supports multiclassing: when `classId` names a class the character doesn't have yet, the character must meet that class's prerequisites and those of every class it already has (PHB p.163), so a Fighter/Paladin with CHA 12 can't take a third class. Before R6 workstream C only the first class was checked.

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
  "newFeatures": ["Extra Attack"]
}
```

Since R6 workstream F (D159) the response is the levelled character and the names of the features the new level brought. Its `pendingChoices` flags (`asiAvailable`, `subclassRequired`, `expertiseAvailable`, `expertiseCount`, `spellSelectionNeeded`, `spellSelectionType` and `maxSpellLevel`) are gone: the level adds its choices to the character's `pendingChoices`, those with its `characterLevel`, and the client makes each through `/apply-choices`.

Since R6 workstream C (D167) the new level records its `hitDieResult`: the fixed average of the class's Hit Die, or its maximum at character level 1 only, so a multiclass dip's first level gets the average (PHB p.163). The server then works out the Derived Stats again: a higher HP maximum raises `hpCurrent` by as much, and spent slots, Hit Dice and pool uses stay spent. Since R6 workstream F the level's cantrip and spell choices come from the class's own table, so Eldritch Knights and Arcane Tricksters are asked for spells from 3rd level, and a class that prepares its spells, such as a Paladin, is asked for none. Barbarian 20 adds Primal Champion's +4 STR and CON, up to 24.

**Errors:**
- `409` — Character at max level (20) / Multiclass prerequisites not met
- `409` — The character is in an Active or Paused encounter (R6 workstream D, D166)
- `409` — The character is dead (R6 workstream G, D166)
- `400` — Class not found

### POST /characters/{characterId}/level-down

Remove the most recent level. It removes the last `levelHistory` entry and, since R6 workstream C, reverses only what that entry recorded (D167): the latest copy of each feature it gained, so earlier levels keep theirs; its ASI or feat, at the amount applied; its Expertise; the proficiencies a multiclass level added; Primal Champion's increases; and its class's level. The server then works out the Derived Stats again, capping `hpCurrent` at the new maximum and the spent Hit Dice at the new total, and syncs the pools. Since R6 workstream F it also reverses the Pending Choices made for the level, by what the level records: its subclass, which its class loses; its Fighting Style; and its cantrips and spells, giving back a spell a swap replaced. The level's choices still pending go (D159). A subclass chosen before workstream F isn't recorded on its level, so levelling down doesn't take it away. If the removed level was the only level in a secondary class, that class is removed from `multiclassEntries` entirely.

**Response (200):** Full character object.

**Errors:**
- `409` — Character at minimum level (1) / No level history to revert
- `409` — The character is in an Active or Paused encounter (R6 workstream D, D166)
- `409` — The character is dead (R6 workstream G, D166)

### POST /characters/{characterId}/apply-choices

Make one of the character's Pending Choices, named by its `choiceId`, with the answer its type takes (R6 workstream F, D159).

**Request:**
```json
{
  "choiceId": "uuid",
  "asi": {
    "type": "ability",
    "increases": [{"ability": "strength", "bonus": 2}]
  }
}
```

For `type: "feat"`, provide `featId` (UUID) for full feat automation. The server loads the feat's `effects` JSON and auto-applies all mechanical bonuses (ability scores, proficiencies, resistances, expertise, speed, initiative, HP per level, passive stats, resources, spells). Provide choice fields as needed: `featAbility` (half-feat ability), `resistanceChoice`, `skillProficiencyChoices`, `savingThrowChoice`, `expertiseSkillChoices`, `toolProficiencyChoices`, `languageChoices`, `weaponChoices`, `spellIds`, `optionalFeatureIds`. Legacy `featName` (string) path still works for text-only feat recording. Since R6 workstream F a request gives only the field its choice's type takes (below). All applied effects are recorded in `levelHistory[].choices.asi.appliedEffects` for deterministic reversal on level-down.

Since R6 workstream C (D167) an `ability` ASI must be +2 to one score or +1 to two different scores, none taken above its maximum (the character's `abilityScoreMaximums`), or it answers `400`. Since R6 workstream F an ASI is recorded on the level that owes its choice, at the amount actually applied (`choices.asi.applied`), and Expertise on its choice's level (`choices.expertise`), so levelling down reverses the right ones. A feat's speed, initiative and HP per level are recorded there and added when the Derived Stats are worked out.

Since R6 workstream E the maximum is checked against `baseAbilityScores`, the scores without DM Adjustments, and the ASI raises those, so an adjustment that takes a score past its maximum doesn't block an increase (D158). Since R6 workstream F apply-choices refuses a feat the character already has with `400`, unless its text says it can be taken more than once, as the feat picker does. The picker keeps a feat open when its text says it can be taken more than once, which only Elemental Adept's does.

Since R6 workstream F (D159) the request is the `choiceId` and the one field its choice's `type` takes, and a field for another type answers `400`:
- `ASI` — `asi`, as above
- `FEAT` — `feat`, with the fields an ASI's feat takes, such as `{"type": "feat", "featId": "uuid"}`: one of the choice's `options` when it has them, else any feat. It is applied and recorded on the 1st level (`choices.feats`), and its effects count as an ASI's feat's do, its pool among them
- `SUBCLASS` — `subclassId`, one of the class's subclasses. It is recorded on the class's subclass level (`choices.subclass`), and its features are added at each class level the character already has, from the subclass level on. It can bring choices of its own, such as an Eldritch Knight's cantrips and spells, which join `pendingChoices`
- `EXPERTISE` — `expertiseSkills`: exactly `count` skills, or every one left when there are fewer, each one the character is proficient in and has no Expertise in yet
- `FIGHTING_STYLE` — `fightingStyle`, one of the choice's `options` the character doesn't have yet. It becomes a feature, such as "Fighting Style: Defense", recorded on the level
- `CANTRIPS` — `spells`: exactly `count` cantrips on the class's list that it doesn't know yet, within its `cantripsKnown`
- `SPELLS` — `spells`: exactly `count` spells on the class's list, of 1st level up to the choice's `maxSpellLevel`, that it doesn't know yet, within its `spellsKnown`; a Wizard copies them into its spellbook. When the choice's `swap` is `true`, an optional `"swap": {"replace": "Shield", "with": "Chromatic Orb"}` also replaces a spell the class knows with another it could learn. The level records both (`choices.spells`), and levelling down gives the replaced spell back

The answer is recorded on the history entry of the level that owes the choice, and the choice leaves `pendingChoices`. Only the owner makes choices: the campaign's DM sees them but can't make or waive one.

**Response (200):** Full character object with updated ability scores / subclass.

**Errors:**
- `400` — An ASI that isn't +2 to one score or +1 to two, takes a score past its maximum or names something that isn't an ability. Since R6 workstream F also: no `choiceId`; a field another type takes; a feat the character already has, unless it is repeatable, or one off the choice's `options`; a subclass of another class or off the list; a Fighting Style off the list or one the character has; Expertise in a skill the character isn't proficient in or has Expertise in already; a spell off the class's list, above the choice's `maxSpellLevel` or known already; a swap the choice doesn't offer; or another number of skills, cantrips or spells than the choice counts
- `403` — Not the owner, the campaign's DM included (D158, D159)
- `409` — Since R6 workstream F, no pending choice with that `choiceId`, as for one made already; a level that already records its ASI, or a class that already has a subclass; or a cantrip or spell choice for a class the character doesn't cast with. Also the feat's data, or a spell's class lists, can't be read
- `409` — The character is in an Active or Paused encounter (R6 workstream D, D166)

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

Since R6 workstream C a class whose prerequisites can't be read answers `409`, instead of counting as having none (D155).

Since R6 workstream E the prerequisites, here and at level-up, are checked against the ability scores with the DM Adjustments (D158).

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

After every mutation commits, the server publishes the DM View to `/topic/encounter/{id}/dm-state` and the Player View to `/topic/encounter/{id}/state`; a command that rolls back publishes nothing (R5, D160). Commands on one encounter run one at a time: a command that waits more than five seconds for the one before it answers 409. (Before R3, one unfiltered state went to `/topic/encounter/{id}/state` and to every caller.)

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

Since R6 workstream E a character's AC includes its active AC adjustments, and the HP maximum, initiative modifier and main spell numbers it is given include the adjustments its stored Derived Stats have when it is added. An adjustment made after the encounter starts doesn't reach it, except those combat reads from the sheet: defences, ability scores and class spell numbers (D158, D166).

Since R6 workstream G a player character joins with its sheet as `character/CombatSnapshot` reads it: its hit points, kept between 0 and its maximum, its temporary hit points, which used to start at 0, and its AC, initiative modifier, slots, spell numbers, known spells and pools. The participant keeps those totals as what it joined with (`joined_with`), from which Write-back works out what the encounter changed, and the encounter copies the sheet again when it starts (D166). A character who has died can't be added until the DM revives it: `409` ("Aria is dead, so can't join an encounter until the DM revives them").

When `quantity` > 1, participants are named sequentially ("Goblin 1", "Goblin 2", "Goblin 3"), continuing from any existing participants with the same base name.

Adding is allowed only while the encounter is PREPARING (409 otherwise), unlike removing (D166).

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

Remove a participant from the encounter, while it is PREPARING or ACTIVE. Since R5 that includes a fight in progress (D166): the participant's concentration ends, and the conditions it created end with it; it leaves its lair; and if it was the current turn, the turn passes to the next creature in the order, whose turn starts. The participant's own turn doesn't end, so its end-of-turn effects don't run. A PAUSED or COMPLETED encounter answers 409. (Before R5 this worked only while PREPARING.)

Since R6 workstream G removing a player character from an ACTIVE encounter writes what the encounter has changed for it so far back to its sheet at once, through Write-back, before it leaves the turn order, so the encounter's end leaves those changes alone (D166). Removing one while PREPARING writes nothing. The character's row is locked after the encounter's, as when the encounter ends.

**Response (200):** Full encounter object.

**Errors:**
- `404` — Participant not found (before R5 an unknown ID was ignored)
- `409` — The encounter is PAUSED or COMPLETED
- `409` — Since R6 workstream G, the removed character's row was held by another change for more than five seconds, or the character changed after it was read

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

### PUT /encounters/{id}/initiatives/tie-order

Set the DM's order for participants tied on initiative and DEX. Allowed while the encounter is PREPARING or ACTIVE. New in R5 (D163).

The turn order puts the highest initiative first, then the higher DEX score, and only then the DM's order, so a tie means equal initiative and equal DEX. In the DM View, the turns of one tie share a `tieGroup` number in `turnOrder`.

**Request:**
```json
{
  "participantIds": ["uuid", "uuid"]
}
```

`participantIds` names every participant in one tie group, in the order they should act. Their turns swap places among themselves and nothing else moves; the encounter stays on the same creature, whose turn it is.

**Response (200):** Full encounter object.

**Errors:**
- `400` — fewer than two participants, or one named twice; a participant not in the encounter; participants who don't tie on both initiative and DEX; or only some of a tie group
- `409` — the encounter is PAUSED or COMPLETED

### POST /encounters/{id}/start

Transition from PREPARING to ACTIVE; an encounter in any other status answers 409. Requires all participants to have initiative set (409 otherwise). Generates a session code and begins the first turn: the first creature in the turn order becomes the current turn, and its turn starts (D163).

Since R6 workstream G each player character participant joins again with its sheet as it stands, copying everything but the initiative the DM set, so a rest or a DM Adjustment made while PREPARING reaches it, and Write-back measures the encounter's changes from the start (D166). A participant whose character has died since it was added stops the start with `409` until the DM revives the character or removes the participant.

**Response (200):** Full encounter object with session code.

### POST /encounters/{id}/pause

Transition from ACTIVE to PAUSED. A paused encounter takes no combat commands (409) until the DM resumes or ends it.

### POST /encounters/{id}/resume

Transition from PAUSED to ACTIVE.

### POST /encounters/{id}/end

Transition from ACTIVE or PAUSED to COMPLETED, which is final: a COMPLETED encounter can't be resumed or ended again, so its results are written back to the character sheets at most once. Start, pause, resume and end each answer 409 for any other transition (D166, R5).

**Request (optional, since R6 workstream G):** `{"applyResults": false}`. `applyResults` is the DM's choice, made once for the whole encounter. It is `true` when it is left out or `null`, or when there is no body, and the encounter records it as `results_applied`.

With `applyResults` `true`, what the encounter changed for each player character is written back to its sheet as the sheet stands now, through Write-back (D166):
- hit points, each slot's used count and each pool's uses change by as much as they changed since the participant joined, kept between 0 and the sheet's limits, so the changes of two encounters add up
- temporary hit points are set to what the participant had left
- a participant who died makes its character `dead`, at 0 hit points and none temporary, and one still `DYING` or `STABLE` comes back alive at 0 hit points, keeping any temporary hit points left
- a sheet already `dead` stays at 0, though its slot and pool changes apply
- conditions aren't written back, and combat spends no Hit Dice

With `false`, nothing is written back, pool uses included, and no character changes. The encounter's row is locked first, then each character's, in ID order, with the five-second wait. If a character's row is held longer, or the character changed after it was read, the end answers `409` and nothing changes. `GET /encounters/{id}/results` (below) previews the results first.

**Response (200):** Full encounter object.

**Errors:**
- `409` — The encounter isn't ACTIVE or PAUSED, as when it is already COMPLETED; or, since R6 workstream G, a character's row was held for more than five seconds, or the character changed after it was read

### GET /encounters/{id}/results

What ending the encounter with its results applied would write back to each player character's sheet as the sheet stands now, for the DM's End dialog (R6 workstream G, D166). It is for the DM only, only while the encounter is ACTIVE or PAUSED, and it changes nothing.

**Response (200):**
```json
[
  {
    "characterId": "uuid",
    "name": "Aria",
    "lifeState": "ALIVE",
    "hpMax": 28,
    "hpBefore": 28,
    "hpAfter": 19,
    "hpTempBefore": 0,
    "hpTempAfter": 4,
    "deadBefore": false,
    "deadAfter": false,
    "slots": [{"slot": "1", "max": 4, "usedBefore": 1, "usedAfter": 2}],
    "pools": [{"poolId": "innate:hellish-rebuke", "displayName": "Hellish Rebuke", "maxUses": 1, "currentBefore": 1, "currentAfter": 0}]
  }
]
```

One entry for each player character, in the turn order. `lifeState` is how its participant ended: `DYING` and `STABLE` are written back alive at 0 hit points, and `DEAD` makes `deadAfter` true. `slots` and `pools` list only those whose numbers would change.

**Errors:**
- `403` — Not the campaign's DM
- `404` — Encounter not found
- `409` — The encounter isn't ACTIVE or PAUSED ("Cannot complete an encounter that is still being prepared", "The encounter is already completed")

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
  "currentTurnIndex": 1,
  "roundNumber": 1,
  "sessionCode": "X43EWPQ2",
  "participants": [
    {
      "id": "uuid",
      "participantType": "PLAYER",
      "characterId": "uuid",
      "monsterId": null,
      "characterClass": "Ranger",
      "displayName": "Aragorn",
      "initiative": 18,
      "initiativeModifier": 3,
      "sortOrder": 1,
      "hpMax": 52,
      "hpCurrent": 52,
      "hpTemp": 0,
      "armourClass": 18,
      "activeConditions": null,
      "concentrationSpell": null,
      "concentrationSlotLevel": null,
      "spellSlotsCurrent": {"1": {"max": 4, "used": 1}, "2": {"max": 3, "used": 0}},
      "isVisibleToPlayers": true,
      "lifeState": "ALIVE",
      "isCurrentTurn": true,
      "deathSaveDue": false,
      "controlledByUserId": "uuid",
      "deathSaveSuccesses": 0,
      "deathSaveFailures": 0,
      "notes": null,
      "healthStatus": null
    }
  ],
  "turnOrder": [
    {"participantId": null, "name": "Lair Actions", "initiative": 20, "lair": true, "skipped": false, "current": false, "tieGroup": null},
    {"participantId": "uuid", "name": "Aragorn", "initiative": 18, "lair": false, "skipped": false, "current": true, "tieGroup": null}
  ],
  "monstersInLair": ["uuid"],
  "allowPlayerManualModifiers": true,
  "createdAt": "2026-07-18T10:00:00Z"
}
```

The sample is abbreviated: it lists one participant (so `monstersInLair` names a monster that isn't shown) and leaves out the spellcasting fields, `resourcePoolsCurrent` and the action, bonus action and reaction flags, which the spec lists.

`healthStatus` is set only in the Player View, on a monster whose HP it hides: `UNHARMED`, `INJURED`, `BLOODIED` or `DOWN`, with `hpCurrent`, `hpMax` and `hpTemp` then `null`. The Player View also sets a monster's `monsterId`, `notes`, `spellsKnown`, `spellSlotsCurrent`, `spellAttackBonus`, `spellSaveDc`, `spellcastingAbility`, `resourcePoolsCurrent` and `hasLairActions` to `null`, and leaves out participants hidden from players (D157). It narrows `turnOrder` as well, as [[websocket-protocol#Player View (D157)]] describes.

What R5 changed in the payload (D161–D163, D168):

- `lifeState` (`ALIVE`, `DYING`, `STABLE` or `DEAD`) replaced `isAlive`.
- `concentrationSpell` and `concentrationSlotLevel` are read from the participant's stored Concentration Instance and are `null` when it isn't concentrating. The payload has no `concentration` object.
- `isCurrentTurn` and `currentTurnIndex` are computed for each view from the turn order, because the encounter stores whose turn it is by participant (D163). `currentTurnIndex` is the current turn's place in `turnOrder`, and `null` before the encounter starts.
- `turnOrder` lists every turn in order: `participantId`, `name`, `initiative`, `lair`, `skipped` (a dead monster's turn), `current` and, in the DM View only, `tieGroup`. The Lair has a turn (`name` "Lair Actions", `initiative` 20, `participantId` null) while a monster in `monstersInLair` is alive. A participant's `sortOrder` is the place of its turn in `turnOrder`.
- `deathSaveDue` is `true` on a Dying PC from the start of its turn until it rolls a death save. `hasLairActions` (monsters, DM View only) says the monster's action templates have lair actions, so the DM can set it fighting in its lair with `POST /combat/lair-status`.
- An `activeConditions` entry is `{id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave}`. `endRule` is `{type, participantId}`, with `type` one of `AFTER_ROUNDS`, `START_OF_TURN`, `END_OF_TURN`, `CONCENTRATION`, `REPEAT_SAVE`, `HP_REGAINED` or `MANUAL`. `repeatSave` is `{ability, dc, awaitingRoll}`, or `null` when the condition has none.
- `spellSlotsCurrent` is an object keyed `"1"` to `"9"`, with pact slots keyed `pact_N`, each `{max, used}`. `spellsKnown` and `resourcePoolsCurrent` are typed arrays too.
- `allowPlayerManualModifiers` repeats the campaign's setting, so the session pages know whether to offer players Manual Modifier fields (D164).
- `characterClass` (R5 follow-ups, D171) is a player character's class as its sheet names it, such as `"Fighter"`, or `"Fighter / Wizard"` for a multiclass character, and `null` on a monster. The server reads it from the sheet each time it builds the state, so no request sets it and a participant keeps no copy. Both views carry it, and the session pages and the encounter builder use it to colour a player character's row.

## Combat

All combat endpoints are under `/api/encounters/{encounterId}/combat`. A combat command needs an ACTIVE encounter: PREPARING, PAUSED and COMPLETED each answer 409 with their own message (R5, D166). The four reads, `log`, `spell-options`, `attack-options` and `multiattack-options`, work in any status. Each command starts the same way (`CombatAccess`, D160): access by campaign, then the encounter's row lock, then who the user may act for, then the status check. The DM may act for any participant; a player may act only for a participant they control (403 otherwise, D028, D155), and may attack or cast spells only on that participant's turn (403 "You can only attack on your turn"). Damage, healing, Set HP, restoring spell slots, monster actions, Legendary Resistance, lair status and turn changes are DM only (403 for a player, D161). A participant named in the body or query string that isn't in the encounter is a 400, and so is one hidden from the player who names it as a target (D157). An attack, spell or monster action aimed at a dead creature answers 409 ("X is dead"); only Set HP brings the dead back (D161). A player's Manual Modifier answers 403 when the campaign has turned them off (D164).

After every mutation commits, the server publishes the DM View and the Player View, and the response, including `encounterState` in spell results, is the caller's view (D157).

### POST /encounters/{id}/combat/attack

Roll an attack (d20 + modifier vs AC). On hit, automatically rolls damage dice and applies damage. Supports advantage/disadvantage. Natural 20 doubles damage dice (critical hit); natural 1 auto-misses (unless forceCrit is true).

**Two kinds of attack (R5 workstream G, D164):** a *weapon attack* names a `weapon`, and the server works out the attack bonus and damage from the attacker's sheet instead of taking them from the browser. An *attack entered by hand* gives `attackBonus` and `damageDice` instead, for a weapon the server doesn't know and for attacks with no sheet, such as the DM's. A request with `weapon` is the first kind; one without it must give both numbers.

**Weapon attacks:** the attacker is a player character, named by `actorId`, and the weapon is one of its `attack-options`: a weapon in its `equipment`, or Unarmed Strike, which every character has. The ability is STR for a melee weapon, DEX for a ranged weapon and, for a finesse weapon, the higher modifier, keeping the weapon's own on a tie. The attack bonus is that modifier plus the proficiency bonus when the sheet's weapon proficiencies cover the weapon. The damage is the weapon's dice plus that modifier, in the weapon's damage type; a critical hit doubles the dice, not the modifier. `powerAttack: true` takes Great Weapon Master's or Sharpshooter's trade: 5 off the attack bonus for 10 more damage, which a critical hit does not double. It needs the feat, proficiency with the weapon, and either a heavy melee weapon (Great Weapon Master) or a ranged weapon (Sharpshooter); a thrown melee weapon such as a javelin qualifies for neither. Not calculated yet (D164): versatile damage, fighting styles, Monk Martial Arts, two-weapon fighting, ammunition, loading and reach, and disadvantage for a ranged attack in melee.

**Manual Modifiers (D164):** a magic weapon's bonus, extra damage, a forced critical hit and a whole attack entered by hand. The DM may always use them, and a player only while the campaign's `allowPlayerManualModifiers` setting is on; a refused one answers 403 before anything rolls. Each use is logged ahead of the attack, in an entry that names no target, for example "Manual Modifier (player): Aria's Longsword attack adds +1 to the attack roll." An attack entered by hand counts as one use, and a forced critical hit as another.

**Attacking unconscious, paralyzed and downed targets (R5, D161):** an attack never hits automatically. A target that is unconscious or paralyzed gives advantage, and a hit from within 5 feet is a critical hit; beyond 5 feet an unconscious creature is prone, whose disadvantage cancels the advantage. `within5Ft` says which (see the request below). Damage to a player character at 0 HP is one death save failure (two on a critical hit), damage of at least its max HP kills it outright, and the third failure kills it.

**Request, a weapon attack:**
```json
{
  "targetId": "uuid",
  "weapon": "Longsword",
  "powerAttack": false,
  "advantage": null,
  "within5Ft": true,
  "extraAttackBonus": 1,
  "extraDamageDice": "1",
  "extraDamageType": null,
  "forceCrit": false
}
```

**Request, an attack entered by hand:**
```json
{
  "targetId": "uuid",
  "attackBonus": 5,
  "damageDice": "1d8+3",
  "damageType": "slashing",
  "advantage": null,
  "forceCrit": false,
  "within5Ft": true
}
```

- `weapon`: the weapon to attack with, named as `attack-options` names it, such as "Longsword" or "Unarmed Strike", in any case. Leave it out for an attack entered by hand.
- `powerAttack`: `true` takes Great Weapon Master's or Sharpshooter's trade. The weapon's attack option must offer it (its `powerAttack` isn't null). Weapon attacks only.
- `extraAttackBonus`: a Manual Modifier. A number added to the attack roll, such as 1 for a +1 weapon. It leaves the damage alone, so add that with `extraDamageDice`. Weapon attacks only.
- `extraDamageDice`: a Manual Modifier. Extra damage as dice or a flat amount ("1d6", "1" or "+1"), applied with the weapon's damage; its dice double on a critical hit, as the weapon's do. `extraDamageType` is its damage type, the weapon's when left out; a type without dice answers 400. Weapon attacks only.
- `attackBonus`: for an attack entered by hand, the total attack modifier (e.g. +5)
- `damageDice`: for an attack entered by hand, a dice expression in NdS+M format (e.g. "2d6+3", "1d8+4")
- `damageType`: for an attack entered by hand, an optional damage type string
- `advantage`: `true` = advantage (roll 2d20, take higher), `false` = disadvantage (take lower), `null` = normal
- `forceCrit`: `true` = treat as critical hit regardless of roll (doubles damage dice, overrides nat 1). A Manual Modifier (R5, D164): the DM may always force one, and a player only while the campaign allows Manual Modifiers (403 otherwise). Each use is logged, for example "Manual Modifier (player): Rogue's attack is a critical hit."
- `within5Ft`: whether the attacker is within 5 feet of the target, which decides whether a hit on an unconscious or paralyzed target is a critical hit. Defaults to `true` when left out, since there is no map (R5, D161), except for a ranged weapon, which defaults to `false`.

**Query params:** `actorId` — the attacking participant. A player must send their own character's (403 without it, or when it isn't that participant's turn), and a weapon attack needs a player character's. The DM may leave it out for an attack entered by hand, which the log then credits to the DM.

**Response (200):** Full encounter object. The attack is logged as one ATTACK entry per target, with the roll and any damage on it, followed by entries for what the hit did (a KILL, a death save failure, a CONCENTRATION_CHECK and so on); there is no separate DAMAGE entry (R5). Each Manual Modifier the attack uses adds an entry ahead of the attack's own. A weapon attack reads like "Aria uses Greatsword (Great Weapon Master) on Ogre: d20(11) + 0 = 11 vs AC 11 — hit, 20 slashing damage."

**Errors:**
- `400` — an invalid dice expression, or a target that isn't in the encounter or is hidden from the player. A weapon attack adds: an attacker that isn't a player character, a weapon the character doesn't hold, a `powerAttack` that the weapon, the feat or proficiency doesn't allow (the message says which), and `extraDamageType` without `extraDamageDice`. A request that mixes the two kinds is a 400 too: a `weapon` with `attackBonus`, `damageDice` or `damageType`, or numbers entered by hand with `powerAttack` or an `extra…` field, or without both numbers.
- `403` — a player attacking for a participant they don't control or outside its turn, or entering a Manual Modifier the campaign doesn't allow
- `409` — the target is dead, or the encounter isn't ACTIVE

### GET /encounters/{id}/combat/attack-options

List the weapons a character can attack with, and the numbers the server works out for each, so the attack form offers what `attack` accepts. New in R5 (D164). It changes nothing, so it takes no lock and works in any encounter status.

**Query params:** `participantId` (UUID) — required, the attacker. The DM may read any participant's options; a player only their own character's (403 otherwise). A monster has none, because its attacks are its actions, so the list is empty.

**Response (200):**
```json
[
  {
    "weapon": "Greatsword",
    "ability": "STR",
    "proficient": true,
    "attackBonus": 5,
    "damage": "2d6 + 3",
    "damageType": "slashing",
    "ranged": false,
    "powerAttack": { "feat": "Great Weapon Master", "attackBonus": 0, "damage": "2d6 + 13" }
  },
  {
    "weapon": "Unarmed Strike",
    "ability": "STR",
    "proficient": true,
    "attackBonus": 5,
    "damage": "4",
    "damageType": "bludgeoning",
    "ranged": false,
    "powerAttack": null
  }
]
```

- The list holds the character's weapons in inventory order, each once, and then Unarmed Strike, which is always there. An `equipment` entry is a weapon when its name matches one of the 55 mundane weapons in `items` (see [[database-schema#items]]), in any case and singular or plural; an entry with a quantity of 0 doesn't count. Since R6 workstream D an entry whose `itemId` links it to one of those weapons counts as that weapon, whatever it is called, and is listed under the weapon's name (D164). A magic weapon listed under its own name, such as "Flame Tongue Longsword", is not offered: attack with the plain weapon and add the bonus as a Manual Modifier, or enter the attack by hand.
- `weapon` is the name `attack` takes. `ability` is STR, DEX, or the higher of the two for a finesse weapon. `attackBonus` includes the proficiency bonus when `proficient` is true. `damage` is the weapon's dice plus the ability modifier, such as "1d8 + 3". A `ranged` weapon attacks from beyond 5 feet unless the request says `within5Ft: true`. Since R6 workstream E the STR and DEX are the scores with the character's DM Adjustments, read from its sheet (D158).
- `powerAttack` is `null` unless the character has Great Weapon Master or Sharpshooter, the weapon qualifies for it, and they are proficient with the weapon. When it is set it gives the `feat`, and the `attackBonus` and `damage` with the trade taken.
- Proficiency comes from the sheet's `weaponProficiencies`, which may name a category ("simple", "Martial Weapons"), a weapon or its plural ("longswords"), or "Firearms" for a firearm. The feats come from the sheet's features and its level history.

**Errors:**
- `400` — the participant isn't in the encounter, or is hidden from the player asking
- `403` — a player asking about a participant they don't control
- `404` — no such encounter

### POST /encounters/{id}/combat/damage

Apply damage to a participant. DM only (D161). The damage is untyped unless `damageType` is given, when the target's resistances, vulnerabilities and immunities apply. A player character's come from its sheet and its active DM Adjustments, read when the damage is dealt; only the DM's adjustments give it vulnerabilities (R6 workstream E, D161). Temp HP absorbs damage first. Dropping to 0 HP kills a monster (`lifeState` DEAD); a player character falls unconscious and prone, starts dying (DYING, death saves reset) and loses concentration, unless the damage left over after reaching 0 is at least its max HP, which kills it outright (massive damage, PHB p.197). Each application to a concentrating creature that stays up makes one CON save, DC 10 or half the damage, and a failure ends its concentration.

**Damaging a downed target:** damage to a player character at 0 HP is one death save failure, and damage of at least its max HP kills it (PHB p.197). A dead target answers 409 ("X is already dead").

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

Heal a participant. DM only (D161). Capped at max HP; temp HP is untouched. Healing a dying or stable player character brings it back to ALIVE with both death save counts reset and ends the unconsciousness that dropping to 0 gave it (end rule `HP_REGAINED`); it stays prone. The dead can't be healed (409): use Set HP to revive them. (Before R5, healing also raised the dead and added Prone.)

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

Set HP / revive (DM only, D161): sets a participant's current HP, kept between 0 and its max, and optionally its temp HP. It is the only way back from DEAD. Above 0 the creature is ALIVE with both death save counts reset, and a player character loses the unconsciousness that dropping to 0 gave it. At 0, a creature that was up drops as if damaged, without the massive damage rule. Logged as `HP_SET`. It replaces the old Resurrect action.

**Request:**
```json
{
  "targetId": "uuid",
  "hpCurrent": 25,
  "hpTemp": 5
}
```

`hpCurrent` is required; `hpTemp` is optional, and when it is left out the temp HP is not changed.

**Response (200):** Full encounter object.

### POST /encounters/{id}/combat/condition/add

Add a condition to a participant, by the DM for anyone or by a player for their own character. Condition names are stored lowercase. Duplicates are ignored. A condition with a duration ends at the start of the affected creature's turn once that many rounds have passed. An incapacitating condition (incapacitated, paralyzed, petrified, stunned or unconscious) ends the creature's concentration.

**Request:**
```json
{
  "targetId": "uuid",
  "condition": "poisoned",
  "duration": 3
}
```

- `duration`: optional, number of rounds. `null` = indefinite (must be manually removed).

Active conditions are objects (see [Encounter Response Shape](#encounter-response-shape)): a condition added this way is `{id, name: "poisoned", duration: 3, appliedRound: 1, endRule: {type: "AFTER_ROUNDS"}, …}`, or with end rule `MANUAL` when it has no duration. V15 converted the old string arrays, and V16 gave every condition an `id` and an end rule (D162, D168).

### POST /encounters/{id}/combat/condition/remove

Remove a condition from a participant: the DM from anyone, a player from their own character. It removes every condition of that name, whatever applied it, and logs each removal.

**Request:** Same shape as add.

### POST /encounters/{id}/combat/repeat-save

Roll a Repeat Save that a player owes against one of their character's conditions. New in R5 (D162).

A condition with a Repeat Save (a saving throw its creature repeats at the end of each of its turns to end the condition) is rolled by the server when its creature's turn ends. When the campaign's `promptPlayerRepeatSaves` setting is on, a player character's save waits instead: the condition's `repeatSave.awaitingRoll` becomes `true` and the player rolls it with this endpoint. The server rolls the d20 and logs the result as `REPEAT_SAVE`. A success ends the condition. A creature that fails the save and has Legendary Resistance left is offered it, which would end the condition (D163).

The DM or the character's player may call it, at any time while a roll is waiting.

**Request:**
```json
{
  "participantId": "uuid",
  "conditionId": "uuid"
}
```

**Response (200):** Full encounter object.

**Errors:**
- `400` — the participant is not in the encounter, or has no condition with that ID
- `403` — a player rolling for a participant they don't control
- `409` — no Repeat Save against that condition is waiting for a roll, or the encounter isn't ACTIVE

### POST /encounters/{id}/combat/death-save

Roll a death saving throw for a dying player character. Server rolls 1d20:
- Natural 20: regain 1 HP and consciousness (`lifeState` back to ALIVE; the character stays prone)
- Natural 1: 2 failures
- 10+: 1 success (3 successes = stable: `lifeState` STABLE, and the counts reset)
- 2-9: 1 failure (3 failures = death)

The DM or the character's player may roll it, at any time. A rolled save clears the participant's `deathSaveDue` prompt, which the server sets when a Dying character's turn starts (D163).

**Request:**
```json
{
  "participantId": "uuid"
}
```

**Errors:**
- `400` — the participant isn't a player character
- `409` — the character isn't dying: it is alive, stable ("makes no death saves") or dead

### POST /encounters/{id}/combat/concentration

Set or clear a participant's concentration spell by hand. Naming a spell starts a Concentration Instance for it, ending the one the participant held first; naming none ends it. Either way the conditions the ended instance created end with it (R5, D162). Casting a concentration spell with `cast-spell` does the same.

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

Cast a spell as a combat action. Player characters and monsters cast through this one endpoint, paid for from a Spell Source (R5, D165): `actorId` names the caster, and the DM casts for a monster. Spells that have an effect template resolve automatically, ~184 of them; the rest spend what pays for them and log the cast for DM adjudication. The separate `POST /combat/monster-spell` endpoint no longer exists (removed in R5): the DM's spell modal casts for a monster through this one.

**Query params:** `actorId` (UUID) — required, the caster participant ID.

**Request:**
```json
{
  "spellName": "Guiding Bolt",
  "slotLevel": 1,
  "spellSource": "SLOT",
  "targetIds": ["uuid"],
  "advantage": null,
  "overrideSpellAttackBonus": null,
  "overrideSpellSaveDC": null
}
```

- `slotLevel` — the level to cast at. For `SLOT`, the slot's level (1–9). For `PACT_SLOT`, the pact slots' level, or 0. For `INNATE` and `NONE`, the spell's own level, or 0 (innate spells aren't upcast).
- `spellSource` — optional: `SLOT`, `PACT_SLOT`, `INNATE` (a use of the spell's own, such as a monster's 1/day) or `NONE` (a cantrip or at-will spell). Left out, the server infers it from `slotLevel` and what the caster has: a slot, or a pact slot of that level, when `slotLevel` is above 0; otherwise no cost for a cantrip or at-will spell, or an innate use. It replaces `usePactSlot`.
- `advantage` — `true` for advantage, `false` for disadvantage, `null` for normal
- `overrideSpellAttackBonus` / `overrideSpellSaveDC` — Manual Modifiers: numbers entered in place of the caster's own. The DM may always enter them, and a player only while the campaign allows it (403 otherwise). Each use is logged.

A player may cast only what `GET /combat/spell-options` lists for their character: a levelled spell must be known or prepared (always-prepared subclass spells count), a race spell must be unlocked at their level, and a slot, pact slot or innate use must be left. A PC in a running encounter needs a slot or use for every levelled spell. The DM casting for a PC follows the same rules. Monsters cast their slot, innate and at-will spells.

**Spell numbers (R6 workstream C, D164):** a player character's class spell uses that class's save DC and attack bonus, worked out from the live sheet (the class's `spellcasting` entry), so a Cleric/Wizard's Cleric spells use WIS and its Wizard spells INT. Race and feat spells, and monsters' spells, use the caster's main numbers. A spell the character has from a class and from somewhere else counts as the class's, unless an innate use pays for it. `overrideSpellAttackBonus` and `overrideSpellSaveDC` are logged against the number the spell would have used.

**Response (200):**
```json
{
  "encounterState": { "...full encounter response..." },
  "spellName": "Guiding Bolt",
  "slotLevelUsed": 1,
  "spellSource": "SLOT",
  "autoResolved": true,
  "resultSummary": "Guiding Bolt hits Goblin 1 for 14 radiant damage",
  "targets": [
    {
      "targetId": "uuid",
      "targetName": "Goblin 1",
      "outcome": "hit",
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

`slotLevelUsed` is the level the spell was cast at, and `spellSource` what paid for it (`NONE` for a repeated effect). `outcome` is `hit`, `miss` or `critical` for a spell attack, `saved` or `failed_save` for a saving throw, `self` for a spell on its caster, `healed` for healing alone, and `hit` for anything else that needs no roll.

**Delivery methods resolved:**
- `SPELL_ATTACK` — d20 + spell attack bonus vs target AC (nat 20 crits, nat 1 misses)
- `SAVING_THROW` — target rolls d20 + save mod vs spell save DC (half damage on save if applicable)
- `AUTO_HIT` — effects applied without rolls (e.g., Magic Missile)
- `SELF` — effects applied to caster only

**Auto-handled mechanics:**
- Cantrip scaling by total character level (not class level); a monster's cantrips scale with its CR rounded up, at least 1
- Upcast damage scaling
- Payment from the Spell Source: a slot, a pact slot, an innate use or nothing, checked before anything is spent
- A concentration spell first ends the caster's current Concentration Instance, and the conditions it created, then starts its own
- Silence check (blocks verbal component spells)
- Damage applied through `EffectApplier`: the target's defences, temp HP, dropping to 0, death saves, and one concentration save per application. Area damage is rolled once and logged per target.
- Conditions applied with source tracking (`sourceSpellName`, `sourceParticipantId`, `sourceRequiresConcentration`, `concentrationInstanceId`) and the end rule, duration and Repeat Save the spell's data gives (D162)

**Errors:**
- `400` — The caster can't cast the spell now or can't pay for it: it isn't one of their spells, it isn't prepared, a race spell isn't unlocked yet, no slot of that level or higher, pact slot or innate use is left, or `slotLevel` doesn't suit the `spellSource`. Also more targets than the spell allows at that level, and a target that isn't in the encounter or is hidden from a player. A missing slot is a 400 since R5 (it was a 409), because the cast is checked before anything is spent.
- `403` — A player casting for a participant they don't control or outside its turn, or entering a Manual Modifier the campaign doesn't allow
- `409` — The caster is silenced and the spell has a verbal component, a target is dead, or the encounter isn't ACTIVE

A spell with no stored effect template, one the template leaves to the DM, and one whose dice can't be rolled are not errors: what pays for the cast is spent and the response has `autoResolved: false` with a `manualResolutionReason`. A concentration spell still starts the caster's concentration.

### GET /encounters/{id}/combat/spell-options

List what a participant can cast now, and what can pay for each spell, so the spell modal offers only what `cast-spell` accepts. New in R5 (D164, D165). It changes nothing, so it takes no lock and works in any encounter status.

**Query params:** `participantId` (UUID) — required, the caster. The DM may read any participant's options; a player only their own character's (403 otherwise).

**Response (200):**
```json
[
  {
    "name": "Fireball",
    "level": 3,
    "sources": [
      { "source": "SLOT", "levels": [3, 4], "usesLeft": null, "usesMax": null },
      { "source": "PACT_SLOT", "levels": [], "usesLeft": 0, "usesMax": 2 }
    ]
  }
]
```

- `sources` lists what can pay, in the order `SLOT`, `PACT_SLOT`, `INNATE`, `NONE`. `levels` are the levels the spell can be cast at with that source now, lowest first; it is empty when the source is spent, and a spell whose sources are all spent is still listed.
- `usesLeft` and `usesMax` count the pact slots or innate uses; they are `null` for `SLOT` and `NONE`.
- A player character's list holds the spells it can cast now: prepared or always-prepared spells, and race and feat spells once unlocked. A monster's holds its slot, innate (uses left) and at-will spells. Spells are sorted by level, then name.

**Errors:**
- `400` — the participant isn't in the encounter, or is hidden from the player asking
- `403` — a player asking about a participant they don't control

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
- A player character's class spell repeats with that class's save DC and attack bonus, and a race or feat spell with the main numbers, as in cast-spell (R6 workstream C, D164)
- Logged as `SPELL_EFFECT_REPEAT` action type

**Errors:**
- `403` — A player repeating the effect outside the caster's turn, or entering a Manual Modifier the campaign doesn't allow
- `409` — Caster has no active concentration or persistent spell

A spell with no repeatable effect is not an error: the response has `autoResolved: false` with a `manualResolutionReason`.

### POST /encounters/{id}/combat/turn/next

Advance to the next turn (DM only): the current turn ends and the next one starts (R5, D162, D163). The turn order puts the highest initiative first, then the higher DEX, then the DM's order among ties, and includes the Lair's turn on initiative 20 while a monster fighting in its lair is alive. A dead monster's turn is skipped; a player character keeps its turn while dying or dead. Passing the end of the order starts a new round.

- **When a turn ends,** conditions that end at the end of that creature's turn end, on whoever has them, and the creature repeats the save of each of its conditions that has one. The server rolls it, or leaves it for the player when the campaign sets `promptPlayerRepeatSaves` (see `repeat-save`).
- **When a turn starts,** the creature's Concentration Instance ends if its duration has run out. Conditions that end at the start of its turn end, as do conditions whose duration has run out when it is their caster (a spell's duration ends at the start of its caster's turn, or at the bearer's if the caster has left or is a dead monster). The creature gets its action, bonus action and reaction back; its per-turn pools, such as legendary actions, refill; each spent Recharge ability rolls its d6; and a Dying player character is due a death save (`deathSaveDue`).
- The Lair's turn has no processing at either end.

**Errors:**
- `409` — nobody in the turn order can take a turn, or the encounter isn't ACTIVE

### POST /encounters/{id}/combat/turn/previous

Go back to the previous turn (DM only). It only moves the turn marker, past any dead monster's turn, and decrements the round number when it passes the start of the order (never below round 1). Nothing the turn change did is undone, and no turn effects run. Answers 409 when nobody can take a turn.

### POST /encounters/{id}/combat/lair-status

Set which monsters are fighting in their lair (DM only, D136, D163). While any of them is alive the turn order includes the Lair's turn on initiative 20, and lair actions are allowed only on that turn, by a living monster in its lair.

**Request:**
```json
{
  "monsterIds": ["uuid"]
}
```

`monsterIds` is the whole list, which replaces the last; an empty list or `null` clears it. The DM View sets `hasLairActions` on a monster whose action templates have lair actions, which is what the In lair toggle offers. The response shows the effect in `monstersInLair` and `turnOrder`: the "Lair Actions" turn appears, and disappears when no monster in the list is alive. The encounter stays on the creature whose turn it is, because it stores the turn by participant. A participant that is removed from the encounter leaves the list.

**Response (200):** Full encounter object.

**Errors:**
- `400` — a participant that isn't in the encounter, or isn't a monster ("Only monsters fight in their lair")
- `409` — the encounter isn't ACTIVE

### POST /encounters/{id}/combat/legendary-resistance

Use Legendary Resistance on a failed save (DM only, D145, D163). The creature succeeds instead and spends one use of its `monster:legendary-resistance` pool.

**Request:**
```json
{
  "combatLogId": "uuid"
}
```

`combatLogId` is a combat log entry with `legendaryResistanceEligible` set. Since R5 every failed save by a creature that has a use left is logged that way, whether it came from a spell, a monster action or a Repeat Save. Using it refunds the damage beyond what a success would have left (`lrSuccessDamage`), which brings back a monster the save killed, and removes exactly the conditions the save applied (`lrConditionIds`). Concentration the failed save ended stays ended. The save a monster's hit calls for against its conditions is eligible too (R5 follow-ups): using it removes only the conditions that save guarded, the hit's damage stays, and it isn't offered when the hit killed the target.

**Response (200):** Full encounter object.

**Errors:**
- `404` — no such entry in this encounter
- `409` — the entry was already resolved or isn't eligible; it was logged before V16, which recorded too little to undo the save exactly; or the creature has no use left

### POST /encounters/{id}/combat/monster-action

Use a monster's action, legendary action or lair action (DM only). `actionSource` is `ACTION`, `LEGENDARY` or `LAIR`, and the spec has the request and response shapes. Monsters cast spells through `cast-spell`, not here. R5 (D136, D163) added these refusals:

- `409` — a legendary action on the monster's own turn, or without enough legendary actions left; a lair action off the Lair's turn, by a monster that isn't fighting in its lair or is dead, or the same lair action two rounds running; a Recharge action that hasn't recharged; a dead target.
- `400` — a monster participant or an action that doesn't exist, a target that isn't in the encounter, or a monster without action templates.

**Multiattack (R5 workstream G, D165):** each of a multiattack's attacks can take a target of its own. Read the attacks from `multiattack-options`, then send `componentTargetIds`: one list of participant IDs for each attack, in the order that option lists them, with `targetParticipantIds` empty. An empty list skips that attack. A creature named twice for one attack counts once, and an attack the DM resolves by hand (`automated: false`) takes no targets, whatever is sent for it. Each attack resolves and is logged on its own, and a later attack skips a target an earlier one killed. `targetResults` has one entry for each attack and target, so a creature attacked twice appears twice.

```json
{
  "monsterParticipantId": "uuid",
  "actionName": "Multiattack",
  "actionSource": "ACTION",
  "targetParticipantIds": [],
  "componentTargetIds": [["uuid-of-the-beak-target"], ["uuid-of-the-claw-target"]]
}
```

Without `componentTargetIds`, every attack goes at the one creature in `targetParticipantIds`, as before, and naming more than one creature that way answers 400. A multiattack the server can't read is the exception, and isn't an error: nothing rolls, and the response has `requiresManualResolution: true` with the reason, for the DM to resolve by hand. `componentTargetIds` adds these `400` refusals: the action isn't a multiattack, or is one the server can't read; `targetParticipantIds` isn't empty; the number of lists differs from the number of attacks; an attack is given more creatures than its `maxTargets`; or no attack has a target. A dead target is still a `409`.

**Saving throws on hits (R5 follow-ups, D165):** a condition an attack gives waits for the saving throw the monster's data records for it, on the condition itself or, failing that, on the attack: `saveDC` and `saveAbility`, or `secondarySaveDC` and `secondarySaveAbility` where the Monster Manual data keeps a hit's save apart from the attack's own DC, as the Mind Flayer's tentacles do for stunning. Each target the attack hits rolls that save through the same save rules as any other, so a Strength or Dexterity save fails automatically while the target is unconscious, paralyzed, stunned or petrified. The target gets the conditions the save guards only on a failure, and the hit's damage is dealt either way. A condition with no save to wait for still comes with the hit: a grapple, because a stat block's grapple DC is for escaping it (unless the grapple itself records a save), and a condition whose data gives a DC without an ability, or an ability without a DC. An attack whose conditions record different saves can't be resolved, because a hit makes one save at most: nothing rolls, and the response has `requiresManualResolution: true` with the reason. A failed hit save is also offered Legendary Resistance (see `legendary-resistance`).

In `targetResults`, `savedSuccessfully` is `true` when the target made a save, the one a hit calls for against its conditions included (before, only an action delivered as a saving throw set it). `false` means the target failed that save or the action called for none, and `conditionsApplied` lists what it gained. The roll is part of the target's log entry, after the damage:

```
Dire Wolf uses Bite on Aria: d20(15) + 5 = 20 vs AC 12 — hit, 10 piercing damage, STR save d20(12) + 0 = 12 vs DC 13 fails, gains prone.
```

A success reads `STR save d20(13) + 0 = 13 vs DC 13 succeeds against prone`, and an automatic failure `STR save vs DC 13 fails automatically`, each in place of the save text above.

### GET /encounters/{id}/combat/multiattack-options

List a monster's multiattacks and the attacks each makes, so the DM can choose a target for each attack. DM only. New in R5 (D165). It changes nothing, so it takes no lock and works in any encounter status.

**Query params:** `participantId` (UUID) — required, the monster. A participant that isn't a monster has no multiattacks, so the list is empty.

**Response (200):**
```json
[
  {
    "name": "Multiattack",
    "description": "The owlbear makes two attacks: one with its beak and one with its claws.",
    "components": [
      { "name": "Beak", "automated": true, "manualReason": null, "maxTargets": 1 },
      { "name": "Claw", "automated": true, "manualReason": null, "maxTargets": 1 }
    ],
    "manualReason": null
  }
]
```

- There is one entry for each multiattack among the monster's `ACTION` actions. `description` is the stat block's wording, which says when the attacks are alternatives. The entry lists the default attacks, and choosing an alternative isn't supported yet.
- `components` are the attacks in the order `componentTargetIds` takes their targets. Each is the monster's action of that name, found in any of its action lists and in any case, so a dragon whose Multiattack names Frightful Presence, Bite, Claw and Claw has four. `automated: false` means the server doesn't resolve that attack (the data leaves it to the DM, or it is itself a Multiattack or Eye Rays): it takes no targets, and `manualReason` says why. `maxTargets` is how many creatures it can take: an attack's target count, 1 unless the data says more, and `null` for any number.
- A multiattack the server can't read has no `components` and a `manualReason`: the data lists no components, a component names an action the monster doesn't have, or the data leaves an and/or choice for review. In the seeded data that is 124 of 1,478. Using one logs it for the DM to resolve by hand (see `monster-action`).

**Errors:**
- `400` — the participant isn't in the encounter
- `403` — the user isn't the campaign's DM
- `404` — no such encounter

### GET /encounters/{id}/combat/log

Get the full combat log for the encounter, ordered chronologically. Campaign members only (D155, R3; before it, any signed-in user could read it). The DM gets every entry. Players get the log without entries whose actor, target or Legendary Resistance target is hidden, without the Legendary Resistance fields (`legendaryResistanceEligible`, `lrTargetId`, `lrDamageDealt`, `lrConditionsApplied`, `lrResolved`, `lrSuccessDamage` and `lrConditionIds`), and with `turnParticipantName` cleared on entries logged during a hidden participant's turn (D157). Since R5 a spell or monster action is logged once per target, plus a summary entry that names no target.

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

An entry for a failed save that Legendary Resistance can undo also carries `legendaryResistanceEligible`, `lrTargetId`, `lrDamageDealt`, `lrConditionsApplied`, `lrResolved`, `lrSuccessDamage` and `lrConditionIds` in the DM View (D163).

**Action types:** `ATTACK`, `DAMAGE`, `HEAL`, `CONDITION_ADD`, `CONDITION_REMOVE`, `DEATH_SAVE`, `CONCENTRATION_CHECK`, `CONCENTRATION_LOST`, `TURN_ADVANCE`, `TURN_BACK`, `STABILIZE`, `KILL`, `REVIVE`, `SPELL_SLOT_USE`, `SPELL_SLOT_RESTORE`, `SPELL_CAST`, `SPELL_EFFECT_REPEAT`, `MONSTER_ACTION`, `LEGENDARY_ACTION`, `LAIR_ACTION`, `LEGENDARY_RESISTANCE_USED`, `RECHARGE_SUCCESS`, `RECHARGE_FAILURE`, `HP_SET` and `REPEAT_SAVE` (the last two new in R5). A Manual Modifier is logged under the action's own type, naming no target, for example "Manual Modifier (player): Rogue's attack is a critical hit."

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
- `400` — Bad input (`ValidationException` and Spring's binding errors): failed validation, with a message per field; an unknown ID in the request body, such as a `raceId` or a participant ID, which is also the answer when a player names a participant hidden from them (D157); a spell the caster can't cast or pay for (R5, D165); a tie order that isn't a whole tie group (R5, D163); a malformed UUID or a parameter of the wrong type (e.g. `?level=abc`); a missing or unreadable body; a missing required parameter; wrong login details; since R6 workstream E, a DM Adjustment with a field its kind doesn't use, or an amount of 0 or beyond ±100 (D158); since R6 workstream F, an apply-choices answer that doesn't fit its Pending Choice, such as a field another type takes, another number of spells, cantrips or Expertise skills than it counts, or a feat the character already has that can't be taken again, and a subclass at creation for a class below its subclass level (D159)
- `401` — Unauthorized: no access token, or an expired or invalid one, on a protected endpoint; or a missing, expired, revoked or reused refresh cookie on `/auth/refresh`. The frontend answers a 401 with one refresh and a retry
- `403` — Forbidden (`ForbiddenException`): signed in but not allowed. Not a member of the campaign, not its DM, not the character's owner, acting for a participant you don't control, a player attacking or casting outside their turn, or a player entering a Manual Modifier the campaign has turned off (R5, D164); since R6 workstream E, a DM Adjustment from anyone but the DM of the character's campaign, or from anyone at all for a character in no campaign (D158); since R6 workstream G, reviving a character, on the same terms (D166)
- `404` — Not found (`NotFoundException`): the resource named in the URL doesn't exist (campaign, character, encounter, participant, session code, combat log entry), or the path is unknown; since R6 workstream E also a DM Adjustment the character doesn't have (D158)
- `409` — Conflict (`ConflictException`): the wrong state for the action (e.g. the encounter isn't active, the character is in an active encounter, already a member, username taken, no spell slot left on `spell-slot/use`), or two people changed the same data at once (optimistic locking, D034: "Someone else changed this at the same time. Please try again."). R5 added: a combat command on an encounter that isn't ACTIVE; a lifecycle move the encounter's status doesn't allow; an attack, spell, action, damage or healing aimed at a dead creature; a legendary action on the monster's own turn and a lair action off the Lair's turn; a Next Turn with nobody able to act; and an encounter command that waited more than five seconds for the one before it ("Another change is still in progress. Please try again in a moment.", D160). R6 workstream D added: a character command that waited more than five seconds in the same way, or that wrote from a copy of the character a command had changed since (D158); a rest, level-up, level-down or apply-choices while the character is in an Active or Paused encounter (D166); a long rest below 1 hit point; and spending a slot or pool use that isn't left, or getting back one that wasn't spent. R6 workstream E added: removing a DM Adjustment already removed, and granting a feat the character already has, unless it can be taken more than once (D158). R6 workstream F added: an apply-choices request naming no Pending Choice the character has, such as one already made (D159). R6 workstream G added: a rest, damage, healing, temporary hit points, level-up, level-down or joining an encounter for a character who is dead; starting an encounter with a participant whose character has died since it was added; reviving a character who isn't dead; previewing the results of an encounter that isn't Active or Paused; and ending an encounter, or removing a player character from one under way, when a character's row is held for more than five seconds or the character changed after it was read (D166)
- `500` — Anything unexpected. Outside the dev profile the body is always `{"error": "Something went wrong. Please try again."}`; only dev returns the real message (`server.error.include-message: always`)

Before R3, "not found" and "not allowed" both returned 400 (`IllegalArgumentException`), an expired token returned 403, and optimistic-lock conflicts returned 500 (superseded in R3, D155).
