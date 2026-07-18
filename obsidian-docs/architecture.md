# Architecture

## System Overview

QuestKeeper is a standard three-tier web application: a React SPA frontend, a Spring Boot backend, and a PostgreSQL database. Real-time combat communication uses WebSockets (STOMP over SockJS) layered on top of the HTTP backend.

The system is designed for a single DM and up to 8 players — roughly 9 concurrent users. This constraint eliminates the need for horizontal scaling, message queues, load balancers, or Redis pub/sub. A single Spring Boot instance on a modest VPS handles all traffic trivially.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        CLIENTS                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ DM       │  │ Player 1 │  │ Player 2 │  │ Player N │   │
│  │ (Laptop) │  │ (Phone)  │  │ (Phone)  │  │ (Phone)  │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │              │              │              │         │
│       └──────────────┴──────────────┴──────────────┘         │
│                          │                                   │
│              HTTP (REST) + WebSocket (STOMP)                 │
└──────────────────────────┬───────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │   Nginx (Production)    │
              │   Reverse Proxy + SSL   │
              │   / → Frontend (static) │
              │   /api → Backend:8080   │
              │   /ws → Backend:8080    │
              └────────────┬────────────┘
                           │
         ┌─────────────────▼─────────────────┐
         │        Spring Boot Backend        │
         │           (Port 8080)             │
         │                                   │
         │  ┌─────────────────────────────┐  │
         │  │      Security Layer         │  │
         │  │  JWT Filter → Auth Check    │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         │  ┌─────────────▼───────────────┐  │
         │  │     REST Controllers        │  │
         │  │  /api/auth/**               │  │
         │  │  /api/campaigns/**          │  │
         │  │  /api/characters/**         │  │
         │  │  /api/monsters/**           │  │
         │  │  /api/encounters/**         │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         │  ┌─────────────▼───────────────┐  │
         │  │   WebSocket Controllers     │  │
         │  │  STOMP /app/encounter/**     │  │
         │  │  Topic /topic/encounter/**   │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         │  ┌─────────────▼───────────────┐  │
         │  │     Service Layer           │  │
         │  │  AuthService                │  │
         │  │  CampaignService            │  │
         │  │  CharacterService           │  │
         │  │  CombatService (M5)         │  │
         │  │  EncounterService (M4)      │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         │  ┌─────────────▼───────────────┐  │
         │  │   Repository Layer (JPA)    │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         └────────────────┼──────────────────┘
                          │
              ┌───────────▼───────────┐
              │    PostgreSQL 16      │
              │   (Docker Container)  │
              │                       │
              │  users                │
              │  campaigns            │
              │  campaign_members     │
              │  player_characters    │
              │  monsters       (M3)  │
              │  spells         (M3)  │
              │  conditions     (M3)  │
              │  items          (M3)  │
              │  encounters     (M4)  │
              │  encounter_     (M4)  │
              │    participants       │
              │  combat_logs    (M5)  │
              └───────────────────────┘
```

## Data Flow: REST (CRUD Operations)

```
Client                    Backend                   Database
  │                         │                         │
  │  POST /api/auth/login   │                         │
  │ ──────────────────────► │                         │
  │                         │  SELECT * FROM users    │
  │                         │ ──────────────────────► │
  │                         │ ◄────────────────────── │
  │                         │  Validate password      │
  │                         │  Generate JWT           │
  │  {accessToken, ...}     │                         │
  │ ◄────────────────────── │                         │
  │                         │                         │
  │  GET /api/campaigns     │                         │
  │  Auth: Bearer <JWT>     │                         │
  │ ──────────────────────► │                         │
  │                         │  Validate JWT           │
  │                         │  Query campaigns        │
  │                         │ ──────────────────────► │
  │  [{campaign}, ...]      │ ◄────────────────────── │
  │ ◄────────────────────── │                         │
```

## Data Flow: WebSocket (Real-Time Combat)

```
DM Client          Backend              Player Clients
  │                  │                      │
  │  STOMP CONNECT   │                      │
  │  (JWT in header) │                      │
  │ ───────────────► │                      │
  │                  │  Validate JWT        │
  │  CONNECTED       │                      │
  │ ◄─────────────── │                      │
  │                  │                      │
  │  SUBSCRIBE       │    SUBSCRIBE         │
  │  /topic/enc/X/   │    /topic/enc/X/     │
  │  state           │    state             │
  │ ───────────────► │ ◄─────────────────── │
  │                  │                      │
  │  SEND            │                      │
  │  /app/enc/X/     │                      │
  │  attack          │                      │
  │ ───────────────► │                      │
  │                  │  Roll dice           │
  │                  │  Resolve attack      │
  │                  │  Update DB           │
  │                  │                      │
  │  MESSAGE         │    MESSAGE           │
  │  Full state      │    Full state        │
  │ ◄─────────────── │ ──────────────────► │
```

## Key Architectural Principles

1. **Server is the single source of truth.** All game logic (dice rolls, attack resolution, HP changes) runs on the backend. The client sends action requests; the server validates, resolves, and broadcasts results. Never trust the client for game state.

2. **Full state broadcast.** After every combat action, the server broadcasts the complete encounter state to all subscribers. This is simpler than delta updates and perfectly adequate for 9 users. Each client replaces its local state wholesale from the authoritative server state.

3. **Stateless authentication.** JWTs allow the server to authenticate any request without a database lookup. This works for both REST and WebSocket — the token is in the Authorization header for HTTP and in the CONNECT frame for STOMP.

4. **JSON columns for nested data.** Fields like ability score proficiencies, spell slots, equipment, and monster actions are stored as PostgreSQL `jsonb` columns rather than normalized into separate tables. This avoids an explosion of join tables while keeping the core relationships (users → characters → campaigns) properly relational.

## Project Structure

```
questkeeper/
├── backend/
│   ├── build.gradle.kts          # Dependencies and build config
│   ├── settings.gradle.kts
│   ├── src/main/java/com/questkeeper/
│   │   ├── QuestKeeperApplication.java
│   │   ├── config/               # Security, CORS, JWT filter, WebSocket config
│   │   ├── auth/                 # Login, register, refresh, JWT provider
│   │   ├── user/                 # User entity and repository
│   │   ├── campaign/             # Campaign + members CRUD
│   │   ├── character/            # Player character CRUD
│   │   ├── monster/              # (Milestone 3)
│   │   ├── reference/            # Spells, conditions, items (Milestone 3)
│   │   ├── encounter/            # (Milestone 4)
│   │   ├── combat/               # (Milestone 5)
│   │   └── seeder/               # 5e.tools data import (Milestone 3)
│   └── src/main/resources/
│       ├── application.yml       # Shared config
│       ├── application-dev.yml   # Local dev (Postgres on localhost)
│       └── application-prod.yml  # Production (env var driven)
├── frontend/
│   ├── src/
│   │   ├── api/                  # Axios clients for each backend resource
│   │   ├── context/              # React Context (Auth, Encounter)
│   │   ├── hooks/                # Custom hooks (useAuth, useWebSocket)
│   │   ├── pages/                # Route-level page components
│   │   │   ├── dm/               # DM-specific pages
│   │   │   └── player/           # Player-specific pages
│   │   ├── components/           # Reusable UI components
│   │   └── types/                # TypeScript interfaces
│   ├── vite.config.ts            # Dev server proxy + Tailwind plugin
│   └── package.json
├── docker-compose.yml            # PostgreSQL for local dev
└── obsidian-docs/                # This documentation
```
