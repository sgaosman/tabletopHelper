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

**Response (200):** Spring Page of Monster objects (name, type, challengeRating, hitPoints, armourClass, speed, stats, traits, actions, etc.). JSONB fields (speed, traits, actions, etc.) are returned as raw JSON via `@JsonRawValue`.

### GET /monsters/{id}

Get a single monster by ID.

### GET /monsters/filters/types, /monsters/filters/challenge-ratings, /monsters/filters/sources

Return distinct filter values for dropdowns.

## Spells

### GET /reference/spells

Search and filter spells. Paginated.

**Query params:** `name`, `level`, `school`, `source`, `className`, `subclass`, `concentration`, `ritual`, `page`, `size`, `sort`

When `subclass` is provided, results include both base class spells and subclass-specific spells.

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
