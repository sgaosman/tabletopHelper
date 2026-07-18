# WebSocket Protocol

Real-time combat communication uses STOMP (Simple Text Oriented Messaging Protocol) over SockJS, provided by Spring WebSocket.

## Why STOMP Over SockJS

- **STOMP** provides publish-subscribe messaging on top of WebSockets — clients subscribe to topics and receive broadcast messages. This maps naturally to the encounter model where all participants see the same state.
- **SockJS** provides a fallback transport layer. When WebSockets are blocked (corporate firewalls, some mobile networks), SockJS transparently falls back to HTTP long-polling. This is cheap insurance for reliability at the table.

## Connection

```
Client → ws://localhost:8080/ws?token=<JWT>
```

The WebSocket endpoint is `/ws`. The JWT is passed as a query parameter. A `ChannelInterceptor` on the server validates it during the STOMP CONNECT frame and associates the session with the authenticated user.

## STOMP Destinations

### Subscribe (server → client)

| Destination | Purpose | Audience |
|-------------|---------|----------|
| `/topic/encounter/{id}/state` | Full encounter state after every action | All participants |
| `/topic/encounter/{id}/action` | Individual combat action results | All participants |
| `/topic/encounter/{id}/log` | Combat log entries | All participants |
| `/user/queue/encounter/errors` | Error messages | Private to user |

### Send (client → server)

| Destination | Purpose | Who Can Send |
|-------------|---------|-------------|
| `/app/encounter/{id}/join` | Join the encounter session | Any participant |
| `/app/encounter/{id}/initiative` | Submit initiative roll | Any participant |
| `/app/encounter/{id}/attack` | Make an attack | Character controller |
| `/app/encounter/{id}/spell` | Cast a spell | Character controller |
| `/app/encounter/{id}/ability` | Use an ability/feature | Character controller |
| `/app/encounter/{id}/damage` | Apply direct damage | DM only |
| `/app/encounter/{id}/heal` | Heal a participant | Character controller |
| `/app/encounter/{id}/condition/apply` | Apply a condition | DM or character controller |
| `/app/encounter/{id}/condition/remove` | Remove a condition | DM or character controller |
| `/app/encounter/{id}/deathsave` | Make a death saving throw | Dying character's controller |
| `/app/encounter/{id}/turn/advance` | Next turn | DM only |
| `/app/encounter/{id}/turn/back` | Previous turn | DM only |

## State Broadcast Strategy

After every action, the server broadcasts the **full** updated encounter state to `/topic/encounter/{id}/state`. Every client replaces its local state with this authoritative snapshot.

This "full state broadcast" approach is deliberately simple. With 9 users, the payload is small (a few KB of JSON) and the broadcast is instantaneous. Delta updates would add complexity for no performance benefit at this scale.

If a client disconnects and reconnects, it re-subscribes to the topic and immediately receives the current state on the next action (or can request it via the REST API).

## Message Formats

### EncounterStateMessage (broadcast on every action)

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

WebSocket infrastructure is planned for **Milestone 4**. The current implementation uses REST polling (10-second interval on the campaign detail page) as a temporary bridge. The full STOMP implementation will be added when the encounter system is built.
