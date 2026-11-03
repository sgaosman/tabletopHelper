# WebSocket Protocol

Real-time combat communication uses STOMP (Simple Text Oriented Messaging Protocol) over SockJS, provided by Spring WebSocket.

## Why STOMP Over SockJS

- **STOMP** provides publish-subscribe messaging on top of WebSockets — clients subscribe to topics and receive broadcast messages. This maps naturally to the encounter model, where every client receives the same full state for its view: all players share the Player View, and the DM gets the DM View (D157).
- **SockJS** provides a fallback transport layer. When WebSockets are blocked (corporate firewalls, some mobile networks), SockJS transparently falls back to HTTP long-polling. This is cheap insurance for reliability at the table.

## Connection

```
Client → ws://localhost:8080/ws (SockJS)
STOMP CONNECT headers: { Authorization: "Bearer <access token>" }
```

The WebSocket endpoint is `/ws` with SockJS fallback. The access token is passed as a native header (`Authorization`) in the STOMP CONNECT frame — not as a query parameter. The `Bearer ` prefix is optional, and a native `token` header is read when `Authorization` is absent. `WebSocketAuthInterceptor` (a `ChannelInterceptor` in `identity.internal`) intercepts CONNECT commands, extracts the token and checks it with `JwtTokenProvider.parseAccessToken`: only an unexpired JWT carrying `type: access` is accepted (D156). It then sets a `UsernamePasswordAuthenticationToken`, whose principal is the user's UUID, as the session's user. A missing or invalid token gets an ERROR frame and the connection closes. `WebSocketAuthConfig` registers the interceptor at highest precedence, so CONNECT is checked before any other interceptor sees a frame. Only CONNECT carries a token, so an open connection keeps working after its access token expires.

The frontend uses `@stomp/stompjs` Client with a SockJS factory (`new SockJS('/ws')`). Its `beforeConnect` hook calls `refreshAccessToken()` before every connect and reconnect and puts the fresh token in `connectHeaders`, so a reconnect never offers an expired token. If the refresh answers 401, the client stops and shows "Your session has ended. Please sign in again."; if the server can't be reached, it retries.

**Allowed origins.** `/ws` accepts the origins in `CORS_ALLOWED_ORIGINS`, the same comma-separated list HTTP CORS uses (default `http://localhost:5173`), plus the server's own origin, so an empty value means same origin only (D157). Before R3 it accepted any origin (`*`).

## STOMP Destinations

### Subscribe (server → client)

| Destination | Purpose | Audience |
|-------------|---------|----------|
| `/topic/encounter/{id}/state` | The Player View, after every change | Members of the encounter's campaign, the DM included |
| `/topic/encounter/{id}/dm-state` | The DM View, after every change | The campaign's DM only |
| `/topic/encounter/{id}/log` | Full combat log, Player View, after every change (D171) | Campaign members |
| `/topic/encounter/{id}/dm-log` | Full combat log, DM View | The campaign's DM only |
| `/user/queue/encounter/errors` | Error messages (nothing publishes to it yet) | Private to user |

`/topic/encounter/{id}/action` was planned in M4 but never published, and the guard refuses it. The frontend subscribes to `state` and `log` (`dm-state` and `dm-log` on the DM page) plus the error queue, and refetches both after a reconnect. Each log message is complete, so revealing a monster restores its entries (D171).

### Send (client → server)

| Destination | Purpose | Who Can Send |
|-------------|---------|-------------|
| `/app/encounter/{id}/join` | Join the encounter session: publishes both views again, so a client that has just subscribed receives the state it may see | Any campaign member |

`/join` is the only STOMP handler (`EncounterWebSocketController`). The other SEND destinations planned in M4 (`initiative`, `attack`, `spell`, `ability`, `damage`, `heal`, `condition/apply`, `condition/remove`, `deathsave`, `turn/advance`, `turn/back`) were never built: combat actions are REST calls under `/api/encounters/{id}/combat/` (see [[api-reference#Combat]]), and each one that changes the encounter publishes both views once it commits (R5, D160).

### Subscription Guard (D157)

`EncounterSubscriptionGuard` checks every SUBSCRIBE and SEND frame. It is registered by its own configurer, `EncounterSubscriptionConfig`, and runs after the CONNECT check.

- **SUBSCRIBE** must name one of five destinations exactly, so the simple broker's wildcard patterns never reach it:
  - `/topic/encounter/{id}/state` — a member of the encounter's campaign
  - `/topic/encounter/{id}/dm-state` — the campaign's DM
  - `/topic/encounter/{id}/log` — a member of the encounter's campaign
  - `/topic/encounter/{id}/dm-log` — the campaign's DM
  - `/user/queue/encounter/errors` — anyone connected

  `{id}` must be a lower-case UUID, as the server publishes it. Any other destination is refused, and so is an encounter that doesn't exist.
- **SEND** must go to a destination under `/app/` that contains none of the pattern characters `*`, `?` or `{`. Clients can't publish straight to `/topic`, `/queue` or `/user` destinations.
- A refused frame gets an ERROR frame, and Spring then closes the connection.

Before R3 only CONNECT was checked: any signed-in user who knew an encounter ID could subscribe, wildcard subscriptions worked, and clients could SEND straight to `/topic`.

## State Broadcast Strategy

After every change, `EncounterBroadcaster` publishes the **full** updated encounter state twice, once per view (D004, D157): the DM View to `/topic/encounter/{id}/dm-state`, and the Player View, derived from it by `EncounterViews.playerView`, to `/topic/encounter/{id}/state`. Every client replaces its local state with the snapshot it receives. (Before R3 one unfiltered state went to `/state` for everyone.)

**Broadcast after commit (R5, D160).** A command that changes an encounter publishes an `EncounterChanged` event inside its transaction: `EncounterCommands` does it when the command takes the encounter's row lock. `EncounterBroadcaster` handles the event in an `AFTER_COMMIT` listener, reloads the committed encounter in a read-only transaction of its own, and then publishes both views and both logs. So:

- a command that rolls back publishes nothing, and clients never see a change that was undone;
- a late broadcast never sends an older state than one already sent, because the publishes for one encounter run one at a time, in the order they reloaded;
- the listener runs on the request's thread before the HTTP response is written, so the socket message for a change reaches the client before the response that reports it (the frontend's `useEncounterAction` relies on that order);
- a messaging failure is logged and doesn't fail the request, since the change is already committed. Clients catch up with the next change or when they reconnect.

The `/app/encounter/{id}/join` handler is the one place that publishes without a change: it sends both views and both logs again to a client that has just subscribed.

This "full state broadcast" approach is deliberately simple. With 9 users, the payload is small (a few KB of JSON) and the broadcast is instantaneous. Delta updates would add complexity for no performance benefit at this scale.

If a client disconnects and reconnects, it re-subscribes and sends `/app/encounter/{id}/join` again, which publishes the current state of both views (it can also fetch it through the REST API).

## Player View (D157)

The Player View is what every player receives, on `/topic/encounter/{id}/state` and in REST responses. `EncounterViews.forUser` gives the campaign's DM the DM View and everyone else the Player View.

- **Hidden participants are left out.** A participant whose `isVisibleToPlayers` is `false` is dropped; `null` counts as visible. Only monsters can be hidden, through the DM-only `PATCH /api/encounters/{id}/participants/{participantId}/visibility`.
- **Monsters lose their DM-only fields:** `monsterId` (it leads to the full stat block), `notes`, `spellsKnown`, `spellSlotsCurrent`, `spellAttackBonus`, `spellSaveDc`, `spellcastingAbility`, `resourcePoolsCurrent` and `hasLairActions` become `null`.
- **Monster HP becomes a Health Status** unless the campaign's `showExactMonsterHp` setting is on: `hpCurrent`, `hpMax` and `hpTemp` become `null`, and `healthStatus` is set. Temporary HP doesn't count toward it.

  | `healthStatus` | Shown as | When |
  |---|---|---|
  | `UNHARMED` | Unharmed | at full HP |
  | `INJURED` | Injured | above half HP |
  | `BLOODIED` | Bloodied | at half HP or less |
  | `DOWN` | Down | at 0 HP |

  The frontend's `utils/healthStatus.ts` turns these into the labels, for the player's initiative list and both spell target pickers. `healthStatus` is `null` everywhere else: in the DM View, for player characters, and when exact HP is shown.
- **The turn order is narrowed too (R5, D163).** `turnOrder` leaves out the turns of hidden participants, and the Lair's turn unless players can see a monster in its lair. `tieGroup` becomes `null` on every turn, and `monstersInLair` lists only the monsters players can see. The turns left are numbered again, so each participant's `sortOrder` and `currentTurnIndex` count only what players see. While a hidden participant takes its turn, no turn is current: `currentTurnIndex` is `null` and no participant has `isCurrentTurn` set.
- **Player characters are unchanged.** That includes `characterClass`, which both views carry.
- **The combat log** (`GET /api/encounters/{id}/combat/log`) for players leaves out entries whose actor, target or Legendary Resistance target is hidden, and no entry keeps the Legendary Resistance fields (`legendaryResistanceEligible`, `lrTargetId`, `lrDamageDealt`, `lrConditionsApplied`, `lrResolved`, `lrSuccessDamage` and `lrConditionIds`). An entry logged during a hidden participant's turn keeps its place, with `turnParticipantName` cleared. Since R5 a spell or monster action is logged once per target, plus a summary entry that names no target, so a hidden target's entry is the one that drops out. The Lair's "Turn passes to Lair Actions" entry names a monster in its lair, so players see it only while they can see one. A player who names a hidden participant as a target gets the same 400 as for an unknown participant.
- **Monster action templates** (`GET /api/encounters/{id}/participants/{participantId}/actions`) are DM only.

## Message Formats

### EncounterStateMessage (broadcast on every action)

The broadcast payload is the `EncounterResponse` DTO, the same shape as the REST encounter responses; [[api-reference#Encounter Response Shape]] lists its actual fields. The sketch below is abbreviated and shows the fields R5 changed. In the Player View, a monster's HP fields can be `null` with `healthStatus` set instead (see [Player View](#player-view-d157)).

```json
{
  "id": "uuid",
  "status": "ACTIVE",
  "roundNumber": 2,
  "currentTurnIndex": 1,
  "participants": [
    {
      "id": "uuid",
      "displayName": "Aragorn",
      "participantType": "PLAYER",
      "characterClass": "Ranger",
      "initiative": 18,
      "sortOrder": 1,
      "hpCurrent": 45,
      "hpMax": 52,
      "hpTemp": 0,
      "armourClass": 18,
      "lifeState": "ALIVE",
      "isCurrentTurn": true,
      "deathSaveDue": false,
      "activeConditions": [
        {
          "id": "uuid",
          "name": "poisoned",
          "duration": 3,
          "appliedRound": 1,
          "sourceParticipantId": "uuid",
          "endRule": {"type": "AFTER_ROUNDS", "participantId": "uuid"},
          "repeatSave": {"ability": "CON", "dc": 13, "awaitingRoll": false}
        }
      ],
      "concentrationSpell": "Bless",
      "concentrationSlotLevel": 1,
      "spellSlotsCurrent": {"1": {"max": 4, "used": 1}},
      "isVisibleToPlayers": true,
      "controlledByUserId": "uuid",
      "deathSaveSuccesses": 0,
      "deathSaveFailures": 0
    }
  ],
  "turnOrder": [
    {"participantId": null, "name": "Lair Actions", "initiative": 20, "lair": true, "skipped": false, "current": false, "tieGroup": null},
    {"participantId": "uuid", "name": "Aragorn", "initiative": 18, "lair": false, "skipped": false, "current": true, "tieGroup": null}
  ],
  "monstersInLair": ["uuid"]
}
```

What R5 changed in the payload (D161–D163, D168):

- **`lifeState`** (`ALIVE`, `DYING`, `STABLE` or `DEAD`) replaced `isAlive` on every participant.
- **Concentration** arrives as `concentrationSpell` and `concentrationSlotLevel`, both `null` when the participant isn't concentrating. They are read from the participant's stored Concentration Instance (the `concentration` column holds `{id, spell, slotLevel, startedRound, durationRounds}`), and the payload has no `concentration` object of its own.
- **`isCurrentTurn` and `currentTurnIndex` are computed for each view.** The encounter stores whose turn it is by participant (`current_turn_participant_id`, or `is_lair_turn` for the Lair), not by position, so both are worked out from the turn order every time the state is built (D163). `currentTurnIndex` is the current turn's place in `turnOrder`, and `null` before the encounter starts.
- **`turnOrder`** lists every turn in order: one per participant, and the Lair's (`name` "Lair Actions", `initiative` 20, `lair` true, `participantId` null) while a monster in `monstersInLair` is alive. `skipped` marks a dead monster's turn, which the order passes over, and `current` marks the turn being taken. `tieGroup` numbers participants tied on initiative and DEX, whom the DM may reorder, and is set only in the DM View. A participant's `sortOrder` is the place of its turn in `turnOrder`.
- **`deathSaveDue`** is `true` on a Dying PC from the start of its turn until it rolls a death save. `activeConditions` entries are objects with an `id`, an `endRule` and, when the condition has one, a `repeatSave`, and `spellSlotsCurrent` is an object of `{max, used}` entries (D168). [[api-reference#Encounter Response Shape]] has the rest.
- **`characterClass`** (R5 follow-ups, 2026-11-03, D171) is a player character's class as its sheet names it, such as `"Fighter"`, or `"Fighter / Wizard"` for a multiclass character, and `null` on a monster. The server reads it from the sheet each time it builds the state, so a participant keeps no copy. Both views carry it, and the session pages and the encounter builder use it for the class colour of a player character's row.

### AttackActionMessage (broadcast after an attack resolves)

Planned in M4 for the `/action` topic and never published: attack results reach clients through the state broadcast and the combat log.

```json
{
  "actorParticipantId": "uuid",
  "targetParticipantId": "uuid",
  "attackRoll": 17,
  "attackModifier": 7,
  "totalAttack": 24,
  "isCritical": false,
  "hits": true,
  "damageRoll": 8,
  "damageModifier": 4,
  "damageType": "slashing",
  "totalDamage": 12,
  "description": "Aragorn attacks Goblin 1 with Longsword: 24 vs AC 15 — HIT for 12 slashing damage"
}
```

## Implementation Status

WebSocket infrastructure is **implemented** as of Milestone 4.

**Backend:**
- `config/WebSocketConfig.java` — `@EnableWebSocketMessageBroker`, registers STOMP endpoint at `/ws` with SockJS, simple broker on `/topic` and `/queue`, app prefix `/app`, user prefix `/user`. Since R3 its allowed origins come from `CORS_ALLOWED_ORIGINS`, and it registers no interceptors itself: each module adds its own.
- `identity/internal/WebSocketAuthInterceptor.java` — `ChannelInterceptor` that accepts only an access token on CONNECT frames; `WebSocketAuthConfig` registers it at highest precedence (moved from `config/` in R3)
- `encounter/EncounterSubscriptionGuard.java` — checks SUBSCRIBE and SEND frames (R3); registered by `EncounterSubscriptionConfig`
- `encounter/EncounterWebSocketController.java` — `@MessageMapping("/encounter/{id}/join")` publishes both views of the current state
- `encounter/EncounterBroadcaster.java` — publishes the DM View and the Player View (R3), and both combat logs (R4). Since R5 it publishes from an `AFTER_COMMIT` listener on `encounter/EncounterChanged.java`, which a command publishes through `EncounterCommands`, and the controllers no longer call it (D160); only `EncounterWebSocketController` calls `publish` directly, for `/join`. This superseded the `SimpMessagingTemplate` and `broadcastState()` each controller used before R3.
- `encounter/EncounterViews.java` — derives the Player View and the players' combat log; `HealthStatus.java` — the four Health Status values (R3)

**Frontend:**
- `useWebSocket.ts` — STOMP client hook with auto-reconnect (5s delay). It fetches a fresh access token in `beforeConnect`, subscribes to the state and log topics for its `view` (`state` and `log`, or `dm-state` and `dm-log` for the DM page) and the error queue, and sends `join` on every connect.
- `EncounterContext.tsx` — wraps useWebSocket, keeps state and log in the TanStack Query cache, fetches initial state via REST. Its `view` prop chooses the topics; the DM session page passes `view="dm"`.

**Remaining (Milestone 5):** Combat action message mappings (`/attack`, `/spell`, `/damage`, `/heal`, `/condition/*`, `/deathsave`, `/turn/*`), combat log broadcasts, and action result messages. *(Not built this way: M5 made combat actions REST endpoints that publish the state, and the combat log has arrived over the WebSocket since R4, per D171.)*

**Note:** the campaign detail page still refreshes every 10 seconds, but only while visible (D008).
