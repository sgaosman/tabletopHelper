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
| `/user/queue/encounter/errors` | Error messages (nothing publishes to it yet) | Private to user |

`/topic/encounter/{id}/action` and `/topic/encounter/{id}/log` were planned in M4 but never published; since R3 the subscription guard refuses them. The frontend subscribes to `state`, or to `dm-state` on the DM session page, plus the error queue.

### Send (client → server)

| Destination | Purpose | Who Can Send |
|-------------|---------|-------------|
| `/app/encounter/{id}/join` | Join the encounter session: publishes both views again, so a client that has just subscribed receives the state it may see | Any campaign member |

`/join` is the only STOMP handler (`EncounterWebSocketController`). The other SEND destinations planned in M4 (`initiative`, `attack`, `spell`, `ability`, `damage`, `heal`, `condition/apply`, `condition/remove`, `deathsave`, `turn/advance`, `turn/back`) were never built: combat actions are REST calls under `/api/encounters/{id}/combat/` (see [[api-reference#Combat]]), and each one publishes both views.

### Subscription Guard (D157)

`EncounterSubscriptionGuard` checks every SUBSCRIBE and SEND frame. It is registered by its own configurer, `EncounterSubscriptionConfig`, and runs after the CONNECT check.

- **SUBSCRIBE** must name one of three destinations exactly, so the simple broker's wildcard patterns never reach it:
  - `/topic/encounter/{id}/state` — a member of the encounter's campaign
  - `/topic/encounter/{id}/dm-state` — the campaign's DM
  - `/user/queue/encounter/errors` — anyone connected

  `{id}` must be a lower-case UUID, as the server publishes it. Any other destination is refused, and so is an encounter that doesn't exist.
- **SEND** must go to a destination under `/app/` that contains none of the pattern characters `*`, `?` or `{`. Clients can't publish straight to `/topic`, `/queue` or `/user` destinations.
- A refused frame gets an ERROR frame, and Spring then closes the connection.

Before R3 only CONNECT was checked: any signed-in user who knew an encounter ID could subscribe, wildcard subscriptions worked, and clients could SEND straight to `/topic`.

## State Broadcast Strategy

After every change, `EncounterBroadcaster` publishes the **full** updated encounter state twice, once per view (D004, D157): the DM View to `/topic/encounter/{id}/dm-state`, and the Player View, derived from it by `EncounterViews.playerView`, to `/topic/encounter/{id}/state`. Every client replaces its local state with the snapshot it receives. (Before R3 one unfiltered state went to `/state` for everyone.)

This "full state broadcast" approach is deliberately simple. With 9 users, the payload is small (a few KB of JSON) and the broadcast is instantaneous. Delta updates would add complexity for no performance benefit at this scale.

If a client disconnects and reconnects, it re-subscribes and sends `/app/encounter/{id}/join` again, which publishes the current state of both views (it can also fetch it through the REST API).

## Player View (D157)

The Player View is what every player receives, on `/topic/encounter/{id}/state` and in REST responses. `EncounterViews.forUser` gives the campaign's DM the DM View and everyone else the Player View.

- **Hidden participants are left out.** A participant whose `isVisibleToPlayers` is `false` is dropped; `null` counts as visible. Only monsters can be hidden, through the DM-only `PATCH /api/encounters/{id}/participants/{participantId}/visibility`.
- **Monsters lose their DM-only fields:** `monsterId` (it leads to the full stat block), `notes`, `spellsKnown`, `spellSlotsCurrent`, `spellAttackBonus`, `spellSaveDc`, `spellcastingAbility` and `resourcePoolsCurrent` become `null`.
- **Monster HP becomes a Health Status** unless the campaign's `showExactMonsterHp` setting is on: `hpCurrent`, `hpMax` and `hpTemp` become `null`, and `healthStatus` is set. Temporary HP doesn't count toward it.

  | `healthStatus` | Shown as | When |
  |---|---|---|
  | `UNHARMED` | Unharmed | at full HP |
  | `INJURED` | Injured | above half HP |
  | `BLOODIED` | Bloodied | at half HP or less |
  | `DOWN` | Down | at 0 HP |

  The frontend's `utils/healthStatus.ts` turns these into the labels, for the player's initiative list and both spell target pickers. `healthStatus` is `null` everywhere else: in the DM View, for player characters, and when exact HP is shown.
- **Player characters are unchanged.**
- **The combat log** (`GET /api/encounters/{id}/combat/log`) for players leaves out entries whose actor, target or Legendary Resistance target is hidden, and no entry keeps the Legendary Resistance fields (`legendaryResistanceEligible`, `lrTargetId`, `lrDamageDealt`, `lrConditionsApplied`, `lrResolved`). An entry logged during a hidden participant's turn keeps its place, with `turnParticipantName` cleared.
- **Monster action templates** (`GET /api/encounters/{id}/participants/{participantId}/actions`) are DM only.

## Message Formats

### EncounterStateMessage (broadcast on every action)

The broadcast payload is the `EncounterResponse` DTO, the same shape as the REST encounter responses; [[api-reference#Encounter Response Shape]] lists its actual fields. The sketch below predates the implementation (for example, the ID field is `id`). In the Player View, a monster's HP fields can be `null` with `healthStatus` set instead (see [Player View](#player-view-d157)).

```json
{
  "encounterId": "uuid",
  "status": "ACTIVE",
  "roundNumber": 1,
  "currentTurnIndex": 2,
  "participants": [
    {
      "id": "uuid",
      "displayName": "Aragorn",
      "participantType": "PLAYER",
      "initiative": 18,
      "hpCurrent": 45,
      "hpMax": 52,
      "hpTemp": 0,
      "armourClass": 18,
      "activeConditions": [{"name": "Blessed", "durationRounds": 10}],
      "isCurrentTurn": true,
      "isAlive": true,
      "isVisibleToPlayers": true,
      "controlledByUserId": "uuid",
      "concentrationSpell": null,
      "deathSaveSuccesses": 0,
      "deathSaveFailures": 0
    }
  ]
}
```

### AttackActionMessage (broadcast after an attack resolves)

Planned in M4 for the `/action` topic and never published: attack results reach clients through the state broadcast and the combat log, which the session pages poll over REST every 3 seconds.

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
- `encounter/EncounterBroadcaster.java` — publishes the DM View and the Player View (R3). `EncounterController` and `CombatController` call it after every REST mutation. This superseded the `SimpMessagingTemplate` and `broadcastState()` each controller used before R3.
- `encounter/EncounterViews.java` — derives the Player View and the players' combat log; `HealthStatus.java` — the four Health Status values (R3)

**Frontend:**
- `useWebSocket.ts` — STOMP client hook with auto-reconnect (5s delay). It fetches a fresh access token in `beforeConnect`, subscribes to the state topic for its `view` (`state`, or `dm-state` for the DM page) and the error queue, and sends `join` on every connect.
- `EncounterContext.tsx` — wraps useWebSocket, manages encounter state via useReducer, fetches initial state via REST. Its `view` prop chooses the topic; the DM session page passes `view="dm"`.

**Remaining (Milestone 5):** Combat action message mappings (`/attack`, `/spell`, `/damage`, `/heal`, `/condition/*`, `/deathsave`, `/turn/*`), combat log broadcasts, and action result messages. *(Not built this way: M5 made combat actions REST endpoints that publish the state, and the combat log is polled over REST. D171 moves the log onto the WebSocket in R4.)*

**Note:** Campaign detail page still uses 10-second REST polling, which is fine for its use case (see [[decisions-log#D008]]).
