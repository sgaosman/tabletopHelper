# QuestKeeper

A web application for running tabletop RPG sessions, starting with D&D 5th Edition (2014). QuestKeeper combines a comprehensive rules reference database, player character tracking, and a real-time encounter engine into a single tool designed for one DM and up to 8 players.

## What It Does

QuestKeeper replaces the scattered collection of PDFs, paper notes, and browser tabs that most D&D groups use to run combat. The DM and players connect to a shared encounter session where initiative, HP, conditions, attacks, spells, and death saves are all tracked in real time across every device at the table.

### Features Available Now

- **User accounts** — Register, log in, choose between DM and Player roles each session; silent JWT token refresh keeps sessions alive
- **Campaign management** — DMs create campaigns and share an invite code; players join with the code
- **Character sheets** — Players create and edit characters with full D&D 5e stats (ability scores, HP, AC, combat stats, personality traits, notes)
- **DM campaign view** — DMs see all members and characters in their campaigns, auto-refreshing every 10 seconds
- **Full 5e reference database** — Searchable bestiary (2000+ monsters), spells (500+), items (1000+), and conditions from every 2014-era D&D 5e sourcebook, with multiselect filters and quick rules reference
- **Encounter builder** — DMs create encounters by adding monsters (with fuzzy search, typo-tolerant) and player characters from their campaign; manual or auto-rolled initiative; participant renaming while preserving monster identity
- **Live encounter sessions** — Real-time WebSocket sync between DM and players via STOMP over SockJS; session codes for players to join; initiative tracking, turn management, pause/resume/end controls

### Features Coming Soon

- **Combat engine** — Server-side attack resolution, spell casting, damage/healing, condition tracking, death saves, concentration checks, and full turn management
- **Mobile-responsive encounter screen** — Players use their phones at the table; DMs use a laptop

### Future Roadmap (Post-Launch)

- Homebrew monster and item creation with CR calculator
- Loot generator from DMG tables
- Campaign notes with markdown editing
- Character import from external sources
- Multi-system support (Pathfinder 2e, Lancer, Shadowrun)
- Map/grid integration for tactical movement

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3, Gradle (Kotlin DSL) |
| Frontend | React 19, TypeScript, Vite 8, Tailwind CSS 4 |
| Database | PostgreSQL 16 (Docker) |
| Real-time | WebSocket with STOMP over SockJS |
| Auth | JWT (access + refresh tokens) |

## Getting Started

### Prerequisites

- Java 21 (JDK)
- Node.js 20 LTS
- Docker and Docker Compose

### Setup

```bash
# Start PostgreSQL
docker compose up -d db

# Start the backend (in one terminal)
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'

# Start the frontend (in another terminal)
cd frontend
npm install
npm run dev
```

The app is available at:
- **Frontend:** http://localhost:5173
- **Backend API:** http://localhost:8080/api
- **Swagger UI:** http://localhost:8080/swagger-ui.html

## Documentation

Internal documentation for developers is in the [`obsidian-docs/`](obsidian-docs/) directory, covering architecture, API reference, database schema, decisions log, risk register, and troubleshooting.
