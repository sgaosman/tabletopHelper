# Decisions Log

A record of key technical decisions, their rationale, and trade-offs accepted.

## D001: Java + Spring Boot for Backend

**Date:** 2026-07-17
**Status:** Accepted

**Decision:** Use Java 21 with Spring Boot 3 for the backend.

**Rationale:** Developer familiarity is the dominant factor for a solo project. Spring Boot provides mature, well-documented libraries for REST, WebSocket/STOMP, JPA, and Security — everything the project needs out of the box.

**Trade-offs:** More boilerplate than Node.js/TypeScript or Kotlin. Slower iteration loop than a dynamically typed language. Higher JVM memory footprint (irrelevant at this scale). Offset by existing developer knowledge and tooling maturity.

**Alternatives considered:**
- Node.js/Express — faster prototyping, shared language with frontend, but less robust WebSocket library ecosystem and developer is less experienced
- Kotlin/Spring — reduces boilerplate while keeping Spring, but adds learning curve

## D002: PostgreSQL Over SQLite or MongoDB

**Date:** 2026-07-17
**Status:** Accepted

**Decision:** Use PostgreSQL 16 as the database.

**Rationale:** The data model is fundamentally relational (characters belong to campaigns, campaigns have members, encounters have participants). PostgreSQL handles these relationships with foreign keys and joins, and its native `jsonb` support avoids an explosion of join tables for nested data like spell slots and equipment.

**Trade-offs:** Requires Docker to run locally (vs. SQLite's zero-config). More operational complexity in production.

**Alternatives rejected:**
- **SQLite** — doesn't handle concurrent writes well. With 9 users sending combat actions simultaneously, MVCC matters.
- **MongoDB** — the JSON-heavy data suggests it, but the strong relational structure (users → characters → campaigns → encounters) is better served by a relational model. Managing referential integrity manually adds complexity without benefit.

## D003: JWT Over Session Cookies

**Date:** 2026-07-17
**Status:** Superseded by D156

**Decision:** Use JWT tokens for authentication (stored in localStorage).

**Rationale:** JWTs allow stateless authentication — no server-side session store needed. Particularly useful for WebSocket connections where session-based auth would require sticky sessions or shared session storage. The JWT is included in the STOMP CONNECT frame for WebSocket auth.

**Trade-offs:** Tokens in localStorage are vulnerable to XSS. No server-side revocation (a compromised token is valid until expiry). Accepted because: small trusted user group, no sensitive financial data, and 1-hour access token window limits exposure.

## D004: Full State Broadcast Over Delta Updates

**Date:** 2026-07-17
**Status:** Accepted

**Decision:** After every combat action, broadcast the full encounter state to all clients.

**Rationale:** With 9 users and ~20 participants, the state object is a few KB of JSON. Full broadcast is simpler to implement, eliminates state desync bugs, and makes reconnection trivial (just re-subscribe and get the current state). Delta updates would add significant complexity for no measurable performance benefit at this scale.

**Trade-offs:** Slightly more bandwidth per message. Not viable for hundreds of users (but that's explicitly out of scope).

## D005: STOMP Over SockJS for Real-Time

**Date:** 2026-07-17
**Status:** Accepted

**Decision:** Use STOMP protocol over SockJS for WebSocket communication.

**Rationale:** STOMP provides publish-subscribe semantics that map naturally to the encounter model. SockJS provides transport fallback for environments where WebSockets are blocked. Spring has first-class support for both.

**Alternatives considered:** Raw WebSockets (would require building custom message routing from scratch), Server-Sent Events (unidirectional, insufficient for combat actions).

## D006: Gradle Kotlin DSL Over Maven

**Date:** 2026-07-17
**Status:** Accepted

**Decision:** Use Gradle with Kotlin DSL (`build.gradle.kts`) over Maven.

**Rationale:** Build files are roughly a third the length of equivalent `pom.xml`. Faster incremental builds. Better IDE autocompletion via Kotlin DSL. Gradle Wrapper eliminates installation requirement.

## D007: Tailwind CSS v4 with Vite Plugin

**Date:** 2026-07-17
**Status:** Accepted

**Decision:** Use Tailwind CSS v4 via the `@tailwindcss/vite` plugin.

**Rationale:** Tailwind v4 simplifies configuration — no `tailwind.config.js` needed, just `@import "tailwindcss"` in CSS. The Vite plugin integrates directly into the build pipeline. Utility-first CSS enables rapid UI development.

**Note:** Tailwind v4 + Vite 8's Rolldown bundler requires `import type` for TypeScript type-only imports. Regular imports of interfaces cause the production build to fail with `MISSING_EXPORT` errors. This was discovered and fixed during Milestone 1.

## D008: Polling for Campaign Detail Refresh

**Date:** 2026-07-17
**Status:** Accepted (temporary)

**Decision:** Use 10-second polling on the campaign detail page to refresh member/character lists.

**Rationale:** WebSocket infrastructure isn't built yet (Milestone 4). Polling is a cheap, effective bridge for the campaign detail page where the DM occasionally checks for new characters. The overhead of one GET request every 10 seconds is negligible.

**Future:** Will be superseded by WebSocket-based notifications when the encounter system is built, if warranted. Polling may remain for non-real-time pages where the simplicity outweighs the latency.

## D009: JSON Columns for Nested Character Data

**Date:** 2026-07-17
**Status:** Accepted

**Decision:** Store complex nested fields (spell slots, equipment, features, proficiencies) as PostgreSQL `jsonb` columns on the `player_characters` table rather than normalizing into separate tables.

**Rationale:** A fully normalized character sheet would require 10+ additional tables (equipment, features, spell_slots, skill_proficiencies, etc.) with complex join queries. The data is owned by a single character and never queried independently. PostgreSQL `jsonb` supports indexing and querying if needed later.

**Trade-offs:** No foreign key enforcement on JSON content. Harder to write aggregate queries across all characters' equipment, for example. Accepted because these queries aren't needed for the application's use cases.

## D010: @JsonRawValue for JSONB String Fields

**Date:** 2026-07-17
**Status:** Superseded by D168

**Decision:** Use Jackson's `@JsonRawValue` annotation on all entity fields that store JSONB data as Java `String` types.

**Rationale:** Without this annotation, Hibernate loads JSONB columns as Java Strings, and Jackson double-escapes them on serialization (e.g., `"[\"Wizard\"]"` instead of `["Wizard"]`). `@JsonRawValue` tells Jackson to emit the string as raw JSON, producing correct output without needing a custom deserializer or a `JsonNode` field type.

**Trade-offs:** `@JsonRawValue` is write-only — if the API ever needs to accept these fields on input, a custom deserializer would be needed. Acceptable because these entities are read-only reference data.

## D011: Recursive Subclass Spell Collection

**Date:** 2026-07-17
**Status:** Accepted

**Decision:** Make the `collectSubclassSpells` method in `SpellSeeder` recursive to traverse arbitrarily nested JSON structures under `additionalSpells`.

**Rationale:** The 5e.tools `additionalSpells` data has four collection types (`prepared`, `expanded`, `known`, `innate`), each with different nesting depths. `innate` in particular nests spells under `{level: {resource: {count: [spells]}}}`. A recursive traversal handles all patterns without hardcoding each nesting structure.

## D012: Item Type Resolution Cascade

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Resolve item types through a multi-step cascade: (1) strip `|SOURCE` suffix from type code and look up in TYPE_MAP, (2) fall back to `typeAlt` field, (3) infer from `baseItem` field, (4) check boolean flags (`wondrous`, `staff`, etc.), (5) infer from item description text for magic items with a rarity.

**Rationale:** 5e.tools item data is inconsistent — some items use type codes with source suffixes (`RD|DMG`), some have no type but have `typeAlt`, some have only boolean flags, and a handful (MTG crossover items) have no type metadata at all. The cascade ensures all 1,723 items get a correct type with zero NULLs.

## D013: Multiselect Filters via Java-Side Splitting and Collection Parameters

**Date:** 2026-07-18
**Status:** Accepted (revised)

**Decision:** Implement multiselect filters by accepting comma-separated values in query parameters, splitting them in Java (controller/service), and passing `List<String>` (or `List<Integer>`) to Spring Data JPA native queries using `IN (:list)` collection expansion.

**Rationale:** The original approach used PostgreSQL's `string_to_array`/`unnest` to split comma-separated values directly in SQL. This worked in raw psql but silently failed through JDBC parameter binding, returning 0 results for multi-value filters. Java-side splitting with Spring Data JPA's built-in collection parameter support (`IN (:list)` expands to `IN (?, ?, ?)`) is reliable and framework-idiomatic.

**Trade-offs:** Adds count parameters alongside list parameters (e.g., `typeCount` + `typeList`) to handle the "no filter" case, since empty collections can't be passed to `IN` clauses. Slightly more verbose repository method signatures.

**Class/subclass filter note:** Spell class and subclass params are combined into a single list and matched against the jsonb `classes` array using `EXISTS (SELECT 1 FROM jsonb_array_elements_text(s.classes) AS c WHERE c IN (:classList))`. This supports any combination of multiple classes and subclasses with OR logic.

## D014: Encounter Session Code Reuses Campaign Invite Code Pattern

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Generate encounter session codes using the same `SecureRandom` + `CODE_CHARS` pattern as campaign invite codes (8-character alphanumeric, excluding ambiguous characters 0/O/1/I/L).

**Rationale:** Consistency across the application. Players are already familiar with entering 8-character codes from campaign invites. The character set avoids confusion when reading codes aloud at the table.

## D015: WebSocket Auth via STOMP CONNECT Header (Not Query Param)

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Pass the JWT as a native header (`Authorization: Bearer <token>`) in the STOMP CONNECT frame rather than as a URL query parameter on the WebSocket upgrade request.

**Rationale:** Query parameters appear in server access logs, browser history, and potentially proxy logs — exposing the JWT. STOMP native headers are transmitted inside the WebSocket frame after the upgrade, keeping the token out of HTTP-visible surfaces. The `WebSocketAuthInterceptor` extracts the token from either the `Authorization` header or a `token` native header for flexibility.

**Trade-offs:** Slightly more complex client-side setup (must configure `connectHeaders` on the STOMP client). SockJS fallback transports may expose headers differently, but Spring's ChannelInterceptor operates at the STOMP layer regardless of transport.

## D016: Static Quick Reference from bookref-quick.json

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Serve the quick reference data as raw JSON from the `bookref-quick.json` file via a single GET endpoint, with all markup parsing done on the frontend.

**Rationale:** The quick reference is ~268KB of static data with deeply nested structures (sections, tables, lists, insets, inline blocks) and 5e.tools markup tags. Parsing markup server-side would strip formatting information needed for rich rendering. Serving raw JSON preserves the full structure and lets the frontend render it with appropriate styling.

## D017: Silent JWT Refresh via Axios Interceptor

**Date:** 2026-07-18
**Status:** Superseded by D155 and D156

**Decision:** Catch both 401 and 403 responses in the Axios response interceptor. When a request that carried a Bearer token receives either status, use the stored refresh token to obtain a new access token, then retry the original request. Queue concurrent failing requests to avoid duplicate refresh calls.

**Rationale:** Spring Security returns 403 (not 401) when a JWT is expired, because the `JwtAuthenticationFilter` silently skips invalid tokens rather than rejecting them — the request proceeds as unauthenticated and hits the `anyRequest().authenticated()` rule, which returns 403. The original interceptor only caught 401, so expired tokens caused silent failures (blank pages, empty data). The `hadToken` guard ensures only requests that actually sent a token trigger the refresh flow — unauthenticated 403s (if any) are not intercepted.

**Trade-offs:** If the refresh token is also expired (7-day window), localStorage is cleared and the user is redirected to `/login`. A never-resolving promise is returned to prevent the rejected promise from crashing React during the redirect.

## D018: globalThis.global Polyfill for sockjs-client

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Add `<script>globalThis.global = globalThis;</script>` to `index.html` before the app module script.

**Rationale:** `sockjs-client` references the Node.js `global` variable. Vite 8's Rolldown bundler does not auto-shim Node.js globals (Vite's previous esbuild-based optimizer did). Without the polyfill, the app crashes with `ReferenceError: global is not defined` on page load.

## D019: PostgreSQL pg_trgm for Fuzzy Monster Search

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Use PostgreSQL's `pg_trgm` extension with `word_similarity()` for fuzzy monster name search in the encounter builder, combined with ILIKE for exact substring matches.

**Rationale:** DMs need to quickly find monsters while building encounters, and exact-match search fails with typos ("gobln", "beholdr"). `pg_trgm` provides trigram-based similarity scoring directly in PostgreSQL with no external dependencies. Combined ranking (exact prefix → substring → fuzzy similarity at threshold 0.4) gives intuitive results. The `gin_trgm_ops` index on `LOWER(name)` ensures fast lookups even against the full SRD monster table (~2000 entries).

**Trade-offs:** Requires PostgreSQL extensions (`pg_trgm`, `fuzzystrmatch`) created at startup via `DataSeeder`. The threshold of 0.4 was chosen to allow single-character typos while avoiding false positives — "aaara" does not match "Aarakocra" but "aara" does.

**Alternatives considered:**
- Elasticsearch — overkill for ~2000 monsters, adds operational complexity
- Levenshtein distance — less flexible than trigram similarity for partial matches and prefix typos
- Client-side fuzzy search (Fuse.js) — would require loading all monsters to the client

## D020: Participant displayName Rename with Preserved Monster Identity

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Allow DMs to rename encounter participants via `PATCH /encounters/{id}/participants/{participantId}/name`, updating the `displayName` field while preserving the `monsterId` foreign key.

**Rationale:** DMs often want to give meaningful names to enemies ("Jeff the Direwolf", "Bob the Goblin") rather than generic labels. The `displayName` is the user-facing label, while `monsterId` remains the FK to the monster entity. This means renames never break monster stat lookups, CR calculations, or any system that relies on knowing what creature the participant actually is.

**Trade-offs:** None significant. The `displayName` was already a separate field from the monster's canonical name since initial encounter design.

## D021: Combat Engine as REST + WebSocket Broadcast (Not Pure WebSocket)

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Implement combat actions (damage, heal, conditions, death saves, turn management) as REST endpoints under `/api/encounters/{id}/combat/*`, with WebSocket broadcast after each mutation. Combat log stored in a separate `combat_logs` table.

**Rationale:** REST endpoints are simpler to implement, test, and debug than pure WebSocket message handlers. The DM's browser makes a REST call, the server mutates state and broadcasts the updated encounter to all connected clients via the existing STOMP topic. This reuses the same broadcast pattern established in M4. The combat log is a write-only append table that can be queried separately without loading the full encounter.

**Trade-offs:** Slightly higher latency than pure WebSocket (HTTP round-trip vs message-over-existing-connection), but imperceptible for turn-based combat. Players still receive real-time updates via WebSocket subscription. If we later need sub-second latency for dice animations or live HP counters, we can add `@MessageMapping` handlers alongside the REST endpoints.

**Alternatives considered:**
- Pure WebSocket `@MessageMapping` for all combat actions — harder to test, no Swagger docs, harder to debug, and the latency difference is irrelevant for turn-based play
- Combined REST + WebSocket commands for different action types — unnecessary complexity for the current feature set

## D022: Server-Side Dice Rolling for Attack Rolls

**Date:** 2026-07-18
**Status:** Accepted (partially superseded by D164)

**Decision:** Attack rolls (d20 + modifier vs AC) and damage dice are rolled server-side using `ThreadLocalRandom`. The DM inputs the attack bonus and damage dice expression (e.g. "2d6+3"); the server handles the full flow: roll d20, compare vs AC, roll damage on hit, apply damage through the existing damage pipeline.

**Rationale:** Server-side rolling ensures consistency (single source of truth for combat state), prevents tampering, and keeps the attack→hit→damage→HP-update flow atomic in a single transaction. The `DiceRoller` utility parses `NdS+M` expressions and handles critical hits (doubled dice count). Advantage/disadvantage is a simple boolean toggle.

**Trade-offs:** Players don't see real dice or rolling animations — all results appear in the combat log. The DM must manually input attack bonus and damage dice per attack (no auto-lookup from monster stat blocks). Both could be improved in M6: client-side dice animations synced to server results, and auto-populating attack parameters from monster action data.

## D023: Condition Duration as Object Array with Auto-Expiry

**Date:** 2026-07-18
**Status:** Superseded by D162

**Decision:** Changed `activeConditions` from a string array (`["blinded","prone"]`) to an object array (`[{"name":"blinded","duration":3,"appliedRound":1}]`). Conditions with a non-null `duration` are automatically removed at the start of the affected creature's turn when `currentRound - appliedRound >= duration`.

**Rationale:** D&D 5e conditions frequently have durations ("until the end of your next turn", "for 1 minute"). Tracking this manually is error-prone during hectic combat. The round-based model is a simplification — it doesn't distinguish "start of turn" vs "end of turn" expiry, but covers the vast majority of cases. The DM can always manually remove/re-add conditions for edge cases.

**Trade-offs:** Backward-incompatible format change for `activeConditions` JSONB. Mitigated by a fallback parser that auto-converts legacy string arrays to the new format on read. No data migration needed since existing encounters are unlikely to be in active combat during the upgrade.

## D024: Spell Slot Tracking via Encounter Participant Copy

**Date:** 2026-07-18
**Status:** Accepted (partially superseded by D166)

**Decision:** Added `spell_slots_current` JSONB field to `encounter_participants`, auto-populated from the character's `spell_slots` when a player is added to an encounter. Slots are tracked per-encounter independently of the character sheet.

**Rationale:** Spell slots are encounter-scoped resources — a character might have different remaining slots across multiple encounters, and we don't want combat to mutate the permanent character record. The copy-on-join pattern mirrors how HP is already handled (copied from character, tracked on participant). The `{level: {max, remaining}}` format is compact and supports all 9 spell levels.

**Trade-offs:** Slot state diverges from the character sheet immediately after the encounter starts. This is intentional — the character sheet represents the character's base state, and encounter participants represent their in-combat state. Future improvement: sync remaining slots back to the character sheet when an encounter ends (e.g. for between-encounter resource management).

## D025: Unconscious Combat Mechanics (PHB Faithful)

**Date:** 2026-07-18
**Status:** Superseded by D161

**Decision:** Implemented PHB-faithful combat rules for unconscious/downed characters: attacks auto-hit (no d20 roll), damage causes death save failures (1 normal, 2 on crit), massive damage (>= max HP) causes instant death. Added `forceCrit` field to attack rolls for the DM to manually mark within-5-feet melee attacks as crits (PHB Unconscious condition, pg.292). Frontend defaults to advantage when targeting downed PCs. Downed PCs can also receive direct damage and conditions.

**Rationale:** The app has no map or distance tracking, so the within-5-feet auto-crit rule from the Unconscious condition cannot be determined automatically. A manual `forceCrit` toggle lets the DM and players decide when it applies, staying faithful to the rules without requiring a grid.

**Trade-offs:** `forceCrit` overrides a natural 1 (turns it into a hit), which is technically non-RAW but makes sense since forced crits represent the DM explicitly deciding the attack is a crit regardless of the d20 roll.

## D026: Resurrection via Healing

**Date:** 2026-07-18
**Status:** Superseded by D161

**Decision:** Dead player characters (3 death save failures) can be healed back to life, representing resurrection spells like Revivify. Healing a dead PC sets them alive, resets death saves, removes the Unconscious condition, and auto-applies Prone. The combat log distinguishes "resurrected" (from dead) vs "revived" (from dying).

**Rationale:** D&D 5e has multiple resurrection mechanics. Rather than implementing each spell individually, a generic "heal from dead" approach covers all cases and lets the DM handle the narrative. Auto-applying Prone matches the PHB — a creature revived from death is typically prone.

**Trade-offs:** No distinction between different resurrection spells (Revivify vs Raise Dead vs Resurrection). The DM is responsible for enforcing spell-specific restrictions (time limits, material components, HP restored) outside the app.

## D027: Combat Log UX — Turn Tracking and Smart Scroll

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Added `turn_participant_name` to `combat_logs` table, populated from the current turn index on every log entry. Frontend renders round headers and turn sub-headers instead of per-line `R1` prefixes. TURN_ADVANCE entries are rendered as turn headers rather than action lines. Combat log scroll position is now preserved when the user scrolls up — new entries show a "scroll to bottom (X new messages)" floating button instead of force-scrolling.

**Rationale:** The previous `R1` prefix was hard to scan during combat with many entries. Turn-grouped headers mirror how players think about combat — "what happened on Thug 1's turn?" Smart scroll prevents the frustrating snap-to-bottom behaviour when reviewing earlier actions mid-combat.

**Trade-offs:** Existing combat log entries from before this change won't have `turn_participant_name` populated (NULL). The frontend handles this gracefully by only showing turn headers when the field is present.

## D028: Player Combat Permissions — Conditions, Concentration, and On-Turn Attacks

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Players can now add/remove conditions and set concentration on their own character at any time, and attack other participants on their turn. Backend enforces these rules with three permission methods: `verifyDmOrTargetOwner` (conditions on own character), `verifyDmOrController` (concentration on own character), and `verifyDmOrControllerOnTurn` (attacks only when it's the player's turn). The DM retains full control over all actions at all times. Player encounter session page updated with condition/concentration self-management controls, clickable condition badges for removal, and an attack panel with the same multi-attack/clone/force-crit UI as the DM view.

**Rationale:** Players in D&D are responsible for tracking their own conditions and concentration, and they make their own attack rolls on their turn. The previous model required the DM to perform all actions, which is an unnecessary bottleneck and doesn't match how tabletop sessions actually run.

**Trade-offs:** Players can only attack on their turn (server-enforced), which prevents out-of-turn attacks like opportunity attacks or readied actions. The DM can still resolve these manually. Damage and healing remain DM-only to prevent abuse.

## D029: Deferred M6 — Polish After Combat UI Stabilises

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Defer Milestone 6 (Polish, Mobile & Deployment) until after M14. The encounter session UI will be substantially rebuilt by M11–M13 (encounter spellcasting, monster actions, enhanced action economy), making early polish work throwaway.

**Rationale:** Responsive layouts, error toasts, and loading states all depend on the UI surface they wrap. With the combat UI gaining spell casting panels, monster action panels, reaction prompts, and action economy indicators in M11–M13, any mobile layout work done now would need to be redone. Polish once after the UI stabilises, not before.

## D030: Declarative Effect Template Architecture

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Spell, monster action, and item effects are defined as structured JSON data (not code). A single `SpellResolverEngine`/effect engine interprets templates server-side. Approximately 85% of level 0–3 spells are fully automatable, ~10% are complex/partial (system deducts slot and logs cast, DM resolves effects manually), and ~5% are utility/log-only.

**Rationale:** 294 spells at levels 0–3 would require thousands of lines of spell-specific code if implemented individually. A declarative approach means fixing a spell's behaviour is a data fix (update the JSON template), not a code deploy. The effect template schema covers delivery method, targeting, effects array, upcast scaling, cantrip scaling, and conditions with source tracking. The `requiresManualResolution` flag gracefully handles spells that don't fit simple patterns (Sleep, Counterspell, Spirit Guardians, etc.) without blocking automation of the majority.

**Trade-offs:** Some spells will always need manual DM resolution. The template schema can't express every possible D&D 5e spell interaction. Accepted because manual fallback is already the status quo, and automating 85% is a massive improvement.

## D031: Pattern-Based Spell Classification

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** 12 pattern categories cover all spells: `ATTACK_DAMAGE`, `SAVE_DAMAGE`, `SAVE_CONDITION`, `SAVE_DAMAGE_AND_CONDITION`, `HEAL`, `BUFF_NO_ROLL`, `SELF_BUFF`, `DEBUFF_NO_SAVE`, `AUTO_DAMAGE`, `SUMMON`, `UTILITY`, `COMPLEX`. Complex spells are marked `requiresManualResolution = true`; the system deducts the spell slot, sets concentration if applicable, and logs the cast — the DM resolves effects manually.

**Rationale:** Categorising spells by mechanical pattern allows the effect engine to have a small number of well-tested resolution paths rather than per-spell logic. Each pattern maps to a deterministic sequence: delivery check (attack roll or save) → effect application (damage, condition, healing) → state updates (concentration, combat log). Complex spells that don't fit (Sleep's HP pool, Counterspell's reaction timing, Spirit Guardians' per-turn area damage) are flagged rather than force-fitted into an incorrect pattern.

## D032: Source-Tracked Conditions

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Expand the condition object to include `sourceSpellName`, `sourceParticipantId`, and `sourceRequiresConcentration` fields. When a caster drops concentration or dies, all conditions across all participants that match the source are automatically removed.

**Rationale:** D&D 5e spells like Entangle, Hold Person, and Bless apply conditions to multiple targets. When the caster loses concentration, all affected targets should have those conditions removed. Without source tracking, the DM must manually remove conditions from every affected target — error-prone during hectic combat with multiple concentration spells active. Source tracking also enables condition indicators like "Restrained (Entangle)" instead of just "Restrained", making it clear where each condition came from.

**Trade-offs:** Increases the size of the conditions JSONB array. Requires updating the condition add/remove logic and the concentration-drop cascade. Acceptable complexity for a significant UX improvement.

## D033: Monster Action Structured Data — Progressive Enhancement

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** All monsters CR 0–10 (~1,200–1,500) and all monsters at any CR with legendary actions, legendary resistance, or lair actions (~60–80 additional) get structured action definitions parsed from raw 5e.tools JSON. CR 11–15 (~200–300) as secondary priority. Total primary scope: ~1,300–1,600 monsters. Remaining monsters (CR 16+ without legendary features) continue using manual damage/condition tools.

**Rationale:** There are 2,684 monsters in the database. CR 0–10 covers every monster the party is likely to encounter at levels 1–10 and includes the vast majority of commonly used creatures. Most CR 0–5 monsters have simple action profiles (1–3 attacks) and are fast to extract. Legendary/lair monsters at any CR benefit most from structured data due to their complexity (action points, resistances, lair actions). The manual tools continue to work for all monsters, so structured data is a progressive enhancement, not a requirement.

**Trade-offs:** Large data volume (~1,300–1,600 definitions) increases the risk of extraction errors, particularly for higher-CR monsters with complex multi-phase actions, aura effects, and conditional abilities. Mitigated by "REVIEW:" flags on uncertain extractions and human review via `docs/monster-action-review.md`.

## D034: Optimistic Locking for Concurrent Mutations

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Add a `@Version` annotated `Long version` field to both `Encounter` and `EncounterParticipant` entities. Hibernate will automatically throw `OptimisticLockException` if two concurrent transactions try to update the same row. The combat controller catches this and returns 409 Conflict, prompting the client to retry after the next WebSocket state broadcast.

**Rationale:** With the introduction of player combat permissions (M5) and encounter spellcasting (M11), multiple users can submit combat actions simultaneously. The current REST-then-broadcast pattern has no explicit locking and could produce race conditions (e.g., two damage applications reading the same HP, both subtracting, resulting in only one being applied). Optimistic locking is cheap insurance — with turn-based combat, actual conflicts will be extremely rare, but the protection should be there.

**Trade-offs:** Adds a version column to two tables. Clients need to handle 409 responses (retry after next WebSocket broadcast). Negligible overhead for robust concurrency protection.

## D035: Existing Test Characters Deleted in M9

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** All current `player_characters` are test data created during development. They will be deleted as part of M9 (Character Builder Overhaul) rather than migrated, since the new system uses FK-based race/class selection backed by seeded reference data, making the old free-text format incompatible.

**Rationale:** The existing characters have free-text race/class/subclass fields (e.g., `race: "Human"` as a string). The new system will use foreign keys to `Race` and `CharacterClass` entities with structured data (ASI, proficiencies, features, speed, size). Migrating would require fuzzy-matching free-text values to reference entities with no guarantee of correctness. Since all current characters are test data with no real player investment, deletion is cleaner.

**SQL:** `DELETE FROM encounter_participants WHERE character_id IS NOT NULL; DELETE FROM player_characters;`

## D036: Persistent Spell Effects as Companion Participants

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Spells like Spiritual Weapon and Flaming Sphere create `EncounterParticipant` entries with `participantType = COMPANION`, linked to the caster via a `summonedByParticipantId` foreign key. These appear as sub-cards beneath the caster in the initiative order UI. They are automatically removed when concentration drops or duration ends.

**Rationale:** Several D&D 5e spells create persistent effects that act independently: Spiritual Weapon (bonus action attack each turn), Flaming Sphere (ram as bonus action), summoned creatures (Find Familiar, Animate Dead, Conjure Animals). Modelling these as encounter participants gives them initiative tracking, HP (where applicable), and action resolution through the same combat engine. The sub-card UI makes it clear which effects belong to which caster.

**Trade-offs:** Adds complexity to the participant model and initiative order. The `COMPANION` type needs special handling: no death saves, auto-removed on concentration loss, actions may be bonus actions rather than full actions. Accepted because the alternative (tracking these effects as ad-hoc state) would be harder to manage and display.

## D037: Undo via Before-State Snapshots

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Each combat log entry stores a JSON snapshot of every affected participant's state before the action was applied. Undo restores the snapshots and deletes the log entry. Cascading effects (e.g., damage → 0 HP → concentration drop → condition removal from multiple targets) are captured in the outermost action's snapshot, so a single undo reverses the entire cascade.

**Rationale:** The alternative — reverse-calculating prior state from the action — is fragile. If damage triggered a concentration check which dropped a spell which removed conditions from three targets, reversing that chain requires understanding the full cascade logic. Storing the before-state is simple, deterministic, and handles any future cascade complexity without modification. The snapshot is a few KB of JSON per action — negligible storage cost.

**Trade-offs:** Increases combat log row size by the snapshot payload. For a typical action affecting 1–3 participants, this is ~1–3 KB of additional JSONB. Acceptable for reliable undo.

## D038: Rename QuestKeeper to TabletopHelper

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Renamed the entire project from "QuestKeeper" to "TabletopHelper". Java package `com.questkeeper` renamed to `com.tabletophelper`, main class `QuestKeeperApplication` to `TabletopHelperApplication`, Gradle group/project name updated, frontend display text updated, Docker Compose service names updated, and all documentation updated. GitHub repository renamed to `sgaosman/tabletopHelper`. PostgreSQL database user and database name remain `questkeeper` since renaming those requires DBA operations with no functional benefit.

**Rationale:** The new name better reflects the project's scope as a general-purpose virtual tabletop helper rather than a quest-tracking tool. The rename was done before any public release or user-facing deployment, so there is no migration burden.

**Trade-offs:** The PostgreSQL credentials (`questkeeper` user and database) remain unchanged to avoid unnecessary DBA work. This is a cosmetic inconsistency between the app name and the DB name, but has zero functional impact since the credentials are configuration, not user-visible.


## D039: Reference Data FK Architecture for Character Builder

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Character builder uses FK references to seeded reference data tables (races, character_classes, subclasses, backgrounds, feats) rather than embedding all data in the player_characters table. The PlayerCharacter entity has both FK fields (race_id, class_id, etc.) and denormalized text fields (race, character_class) for display. The text fields are populated from the referenced entity at creation/update time.

**Rationale:** FK references enable the creation wizard to present searchable, filterable lists of valid choices seeded from 5etools data. Denormalized text fields provide fast display without JOINs and backwards compatibility with the encounter system which reads character names for display. The old free-text fields are retained as read fallbacks but new characters always use FKs.

**Trade-offs:** Dual fields (FK + text) add some denormalization. If a reference entity name changes (unlikely for seeded data), the denormalized text would become stale until the character is re-saved. Acceptable for seeded reference data that doesn't change.

## D040: Hardcoded Spell Slot Progression Tables

**Date:** 2026-07-19
**Status:** Accepted (partially superseded by D153 and D169)

**Decision:** Spell slot progression tables (full caster, half caster, pact magic, artificer) are hardcoded as static arrays in both CharacterClassSeeder (backend) and SpellSlotCalculator rather than derived from 5etools data. Multiclass spell slot calculation follows PHB 2014 rules: sum effective caster levels (full=level, half=level/2 round down, artificer=level/2 round up), look up the full caster table for the combined level. Warlock pact slots are tracked separately.

**Rationale:** The 5etools class JSON does not include spell slot tables in a directly parseable format — they are embedded in classTableGroups as display-only table rows. Hardcoding the 4 progression tables (20 levels each) is more reliable and maintainable than parsing display tables. The PHB tables are fixed constants that will never change for 2014 rules.

**Trade-offs:** If a new caster progression type were added (it won't be for 2014 rules), it would require a code change. Acceptable.

## D041: Legacy Character Cleanup on Startup

**Date:** 2026-07-19
**Status:** Superseded by D152

**Decision:** The DataSeeder deletes all existing player characters that lack race_id FK references (legacy free-text characters) on startup. This runs before the new reference data seeders. Encounter participants referencing deleted characters are also cleaned up.

**Rationale:** Per the M9 specification, all test characters from prior milestones use the old free-text format and are invalid under the new schema. Cleaning them prevents schema conflicts and ensures a clean starting state for the new character builder.

**Trade-offs:** Destructive for any existing character data. Acceptable since the app has no real users yet — all characters are development test data.

## D042: Soft Delete for Player Characters

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Character deletion sets `isActive = false` rather than hard-deleting the row. Characters participating in encounters with status PREPARING, ACTIVE, or PAUSED are blocked from deletion (409 Conflict). Characters in COMPLETED encounters remain deletable — their encounter participant records are preserved for combat log integrity.

**Rationale:** Hard-deleting a character would cascade-delete or orphan encounter participant records, breaking combat logs for completed encounters. Soft delete preserves referential integrity while removing the character from the player's active list. The active-combat guard prevents mid-session disruption.

**Trade-offs:** Soft-deleted characters remain in the database. This is fine — the volume is negligible and allows potential future "undelete" functionality.

## D043: clearCampaign Boolean for PATCH-Style Campaign Unassignment

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Added `clearCampaign` boolean field to `CharacterUpdateRequest`. When `true`, the character's campaign is set to null. This is needed because `campaignId: null` in a partial update is indistinguishable from "field not provided".

**Rationale:** Standard PATCH-style updates treat null fields as "not provided, don't change". To allow unsetting a campaign assignment, a separate flag is needed to express the intent "set this to null".

**Trade-offs:** Adds a non-standard field to the DTO. Alternatives (separate endpoint, empty-string sentinel) are equally non-standard. This pattern is well-understood in PATCH-style APIs.

## D044: Proficiency Collection at Character Creation

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Four new JSONB columns on `player_characters`: `armor_proficiencies`, `weapon_proficiencies`, `tool_proficiencies`, `language_proficiencies`. Collected during character creation by merging proficiencies from race (fixed + choices), class, and background (fixed + choices), deduplicating via Set.

**Rationale:** D&D 5e proficiencies come from three sources (race, class, background), each with fixed grants and player-choice options. The character sheet needs a unified proficiency list for display and for future automation (e.g., checking if a character is proficient with a weapon during combat). Collecting at creation time avoids re-deriving from three separate data sources on every sheet load.

**Trade-offs:** Proficiencies are denormalized — if the reference data changes, existing characters' proficiencies won't update. Acceptable since reference data is static 5e.tools SRD content.

## D045: Background Seeder Recursive Copy Resolution

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** `BackgroundSeeder.resolveCopy()` is now recursive (up to depth 5) to handle multi-level `_copy` chains in 5e.tools background data. `parseSingleProfSet` handles `"any"` keys with numeric values, storing `{"any": N}` objects instead of the string "Any".

**Rationale:** Several backgrounds (e.g., Spy copies Criminal, various variant backgrounds) use `_copy` to inherit from another background which itself may copy from a third. The previous single-level resolution missed these chains. The `{"any": N}` pattern for tool/language proficiencies (e.g., "choose any 2 languages") provides structured data that the frontend can render as a picker.

**Trade-offs:** Depth limit of 5 prevents infinite loops. No known 5e.tools backgrounds exceed 2 levels of copy depth.

## D046: Exotic Languages in Race and Background Pickers

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Language pickers in the character creation wizard now include all 16 standard and exotic D&D 5e languages (Common, Dwarvish, Elvish, Giant, Gnomish, Goblin, Halfling, Orc, Abyssal, Celestial, Deep Speech, Draconic, Infernal, Primordial, Sylvan, Undercommon) plus Druidic and Thieves' Cant.

**Rationale:** The initial implementation only included the 8 standard languages. Several races and backgrounds grant exotic language choices (e.g., Tiefling with Infernal, various backgrounds offering "any language"). Missing exotic languages prevented valid character creation for these options.

**Trade-offs:** None significant. The full language list is small and well-defined.

## D042: Soft-Delete Characters to Preserve Combat History

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Character deletion uses soft-delete (`isActive = false`) instead of hard-delete. The `GET /characters` endpoint filters to `isActive = true`. Characters in active encounters (PREPARING, ACTIVE, or PAUSED) cannot be deleted — the backend checks via `EncounterParticipantRepository.existsByCharacter_IdAndEncounter_StatusIn()`.

**Rationale:** Hard-deleting a character would cascade-delete or orphan `encounter_participants` and `combat_logs` rows, destroying historical combat data. Soft-delete preserves referential integrity — past encounter records remain accessible even after a player retires a character. The active combat guard prevents mid-session disruption.

**Trade-offs:** Soft-deleted characters remain in the database indefinitely. A future cleanup job could purge characters with no encounter history if storage becomes a concern.

## D043: Campaign Assignment from Character Sheet

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Players can assign or unassign characters to campaigns from a dropdown on the character sheet page (next to the tabs bar), rather than only during character creation. The `clearCampaign` boolean field on `CharacterUpdateRequest` handles the unassign case, since `campaignId: null` is indistinguishable from "not provided" in a PATCH-style update.

**Rationale:** Players often create characters before knowing which campaign they'll join, or may move characters between campaigns. The character sheet is the natural place to manage this since it's where the player views and edits their character.

**Trade-offs:** The `clearCampaign` boolean adds a field solely to work around JSON null ambiguity. Alternative approaches (sentinel UUID, separate endpoint) were considered but this is the simplest.

## D044: Proficiency Tracking as JSONB Arrays

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Added four JSONB columns to `player_characters`: `armor_proficiencies`, `weapon_proficiencies`, `tool_proficiencies`, `language_proficiencies`. These are populated during character creation from the combined race + class + background proficiency sources, with deduplication.

**Rationale:** The character sheet needs to display all proficiencies (armor, weapons, tools, languages) in the Stats tab. Previously only skill proficiencies and saving throws were tracked. Storing them as JSONB arrays follows the existing pattern for `skill_proficiencies` and `saving_throw_proficiencies`.

**Trade-offs:** Denormalized from the source reference data (race, class, background tables). If a player manually edits proficiencies post-creation, the arrays diverge from what the reference data would produce. This is intentional — manual overrides are a feature.

## D047: Source-Tagged spellsKnown Format for Multi-Source Spell Management

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Extended the `spellsKnown` JSONB array entries from `{name, level, prepared?}` to `{name, level, source, prepared?, alwaysPrepared?, atWill?, usesPerLongRest?, unlocksAtLevel?}`. The `source` field uses a prefix convention: `"class:Cleric"`, `"race:Tiefling"`, `"feat:Fey Touched"`. Entries without a `source` field default to `"class:{className}"` for backward compatibility.

**Rationale:** D&D 5e characters get spells from multiple sources (class, race, feats, backgrounds). The Spells tab needs to group spells by source into separate boxes (e.g., "Cleric Spells" box, "Tiefling Spells" box, "Fey Touched" box). Source tagging also enables per-source behavior: class spells can be prepared/unprepared, racial spells are fixed innate abilities, subclass always-prepared spells are locked.

**Trade-offs:** Increases the size of spellsKnown entries. The `source` field is denormalized (it duplicates info derivable from the character's class/race/feat data), but accessing it inline avoids re-deriving the source on every render.

## D048: Race additionalSpells Normalized Format

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Added `additional_spells` JSONB column to the `races` table. The RaceSeeder parses all 8 distinct 5etools `additionalSpells` patterns into a normalized format: `{ability, abilityChoices, fixedSpells: [{name, level, atWill, unlocksAtLevel, castLevel?, usesPerLongRest?}], spellChoices: [{fromClass, level, count}], expandedList?, options?}`. The `options` array handles races like Astral Elf that offer multiple spell set choices.

**Rationale:** The raw 5etools format uses 8 distinct patterns (known arrays, innate daily nesting, choose filters, expanded spell lists, ability string vs object, multiple outer entries). A normalized format lets the frontend render race spells consistently without reimplementing the parsing logic. 76 of 226 races have spell data.

**Trade-offs:** Some information loss in normalization (e.g., `#c` cantrip suffix and `#N` cast-at-level suffix are parsed into structured fields, source book references are stripped). This is intentional — the normalized format captures the game-mechanical information needed for the character sheet.

## D049: Auto-Calculate Spell Slots and Derived Stats at Character Creation

**Date:** 2026-07-19
**Status:** Accepted (partially superseded by D158)

**Decision:** `CharacterService.createCharacter()` now auto-calculates `spellSlots`, `spellSaveDc`, and `spellAttackBonus` for spellcaster classes if they weren't provided in the request. Uses the existing `SpellSlotCalculator` (which handles multiclass and pact magic) and derives caster type from the class name (Paladin/Ranger = half, Artificer = artificer, Warlock = pact, all others = full).

**Rationale:** Previously these fields were passed through from the frontend request without validation or calculation. The frontend had no mechanism to compute them during creation. Server-side calculation ensures correctness and reduces frontend complexity.

**Trade-offs:** Caster type is derived from class name rather than stored as a field on CharacterClass. This works because the 2014 PHB has a fixed, small set of caster classes. If a homebrew class were added, the mapping would need updating.

## D050: Feat Spell Management via grantsFeatures Parsing

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Parse feat `grantsFeatures` JSON (raw 5etools `additionalSpells` format) on the frontend into a normalized `ParsedFeatOption` structure. The parser (`featSpellParser.ts`) handles all 5etools patterns: fixed known spells, choose-from-class filters, choose-from-list, daily innate spells, fixed/choose ability. During character creation, backgrounds that grant feats show a feat configuration UI in the Background step and spell selection in the Spells step. On the character sheet, an "Add Feat Spells" button opens a modal for post-creation feat spell management.

**Rationale:** The 5etools `additionalSpells` format is already stored verbatim in the `feats.grants_features` JSONB column. Parsing it client-side keeps the backend simple and reuses the same format used for race `additionalSpells`. The normalized `ParsedFeatOption` structure flattens the complex nesting into flat arrays (fixedCantrips, cantripChoice, fixedSpells, spellChoice, ability/abilityChoices) that map directly to UI components.

**Trade-offs:** Parsing happens on every render rather than being pre-processed. The 5etools data format is complex (8+ patterns) which means the parser has many code paths, but each feat only exercises 2-3 of them. Rune Shaper's `daily` array encodes all 14 spells as individual fixed grants rather than a choice — this matches the raw data but may not perfectly reflect the feat's rules text.

## D051: Non-Caster Class Spell Section Suppression

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** The Spells tab only shows a class spell section (e.g., "Fighter Spells") if the character's class is a spellcaster class (Bard, Cleric, Druid, Paladin, Ranger, Sorcerer, Warlock, Wizard, Artificer) or has a spellcaster subclass (Eldritch Knight, Arcane Trickster). Non-caster classes with feat-granted spells show only the feat spell boxes.

**Rationale:** When a non-caster like a Fighter takes a spell-granting feat (e.g., Magic Initiate via Astral Drifter background), the character's `spellcastingAbility` field gets set. The Spells tab previously used this field to create a fallback "class spell" box, resulting in an empty "Fighter Spells" section alongside the feat spells. This was confusing — Fighters don't have class spells.

**Trade-offs:** The spellcaster subclass list (Eldritch Knight, Arcane Trickster) is hardcoded. If homebrew spellcaster subclasses are added, the list would need updating. Alternatively, a `isSpellcaster` field on the Subclass entity could be added in the future.

## D052: Level History JSONB for Deterministic Rollback

**Date:** 2026-07-19
**Status:** Accepted (partially superseded by D167)

**Decision:** A `level_history` JSONB column on `player_characters` stores a per-level array recording exactly what was gained at each level: HP, features, ASI/feat choices, and which class the level was taken in. Level-down pops the last entry and reverses every change deterministically.

**Rationale:** Without a record of what happened at each level, level-down would need to re-derive all stats from scratch — which is fragile because ASI choices, feat selections, and HP gains are not deterministic. The history also enables multiclass tracking: each entry records which class was leveled, so level-down knows which class to decrement.

**Trade-offs:** The JSONB grows linearly with level (up to 20 entries). This is negligible in practice. The history is append-only during level-up and pop-only during level-down, so consistency is straightforward.

## D053: PHB Multiclass Prerequisites with AND/OR Operators

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Multiclass prerequisites are stored as structured JSON on `character_classes`: `[{ability, minimum, operator}]`. The `operator` field defaults to AND but supports OR for classes like Fighter (STR 13 OR DEX 13) and Ranger (DEX 13 AND WIS 13). The `MulticlassValidator` utility evaluates these against a character's ability scores, enforcing the PHB rule that characters must meet prerequisites for both their current class (exit) and the new class (entry).

**Rationale:** The 5etools raw data uses a nested structure with explicit OR groups. Normalizing to a flat array with an operator field simplifies both storage and evaluation. The AND/OR split covers all 13 PHB classes.

**Trade-offs:** The flat array with operator field doesn't support deeply nested boolean logic (e.g., (A AND B) OR (C AND D)), but no PHB class requires this complexity.

## D054: Server-Side Leveling with Client Class Selection

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Level-up is a server-side operation: the client sends only which class to level into, and the server computes all derived stats (HP, features, proficiency bonus, spell slots, hit dice). The server returns a `pendingChoices` object indicating whether the client needs to prompt for ASI or subclass selection.

**Rationale:** Keeping all leveling logic server-side prevents drift between client and server state. The `LevelUpCalculator` utility is shared between character creation (multi-level) and level-up, ensuring consistency. The two-step flow (level-up then apply-choices) keeps each API call focused.

**Trade-offs:** The client cannot preview exact HP gain before confirming, though this is a minor UX gap since HP gain is deterministic (PHB average). The two-step flow adds a round trip for ASI/subclass levels.

## D055: Single JSONB Effects Column for Feat Automation

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** All structured mechanical effects for feats are stored in a single `effects` JSONB column on the `feats` table, rather than 10+ separate columns for each effect type (resistances, proficiencies, speed, etc.). The `FeatEffectResolver` service parses this JSON and applies/reverses effects deterministically.

**Rationale:** A single JSONB column avoids schema proliferation (10+ columns, many nullable) and is easily extensible for new effect types. The 5etools data already provides structured keys (`additionalSpells`, `armorProficiencies`, `resist`, etc.) that map directly to JSON fields. The seeder extracts these from raw 5etools data, with hand-authored templates for stat-modifying feats (Tough, Alert, Observant, Mobile, Lucky, Squat Nimbleness) that have no structured 5etools keys.

**Trade-offs:** The effects JSON is opaque to SQL queries — you cannot query "all feats that grant resistance" without parsing JSON. This is acceptable because feat effects are only consumed by the application layer (FeatEffectResolver), never queried directly in SQL.

## D056: Deterministic Feat Reversal via Applied Effects Record

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** When a feat is applied during level-up, all mechanical changes are recorded in `levelHistory[level].choices.asi.appliedEffects` as a structured object (ability increases, proficiencies added, resistances added, etc.). On level-down, the `FeatEffectResolver.reverseFeatEffects()` method uses this record to precisely undo each change.

**Rationale:** Feats involve player choices (which ability to increase, which skill to get expertise in, which resistance to pick) that cannot be re-derived from the feat definition alone. Storing the exact applied effects ensures reversal is always correct regardless of what the player chose. This extends the `levelHistory` pattern established in M10 (D052).

**Trade-offs:** The levelHistory JSONB grows slightly larger with appliedEffects data. This is negligible — even at level 20 with a feat at every ASI level, the total is under 10KB.

## D057: Multiclass Character Creation with Post-Creation ASI Flow

**Date:** 2026-07-19
**Status:** Accepted (partially superseded by D159)

**Decision:** Multiclass character creation sends a `multiclassClassEntries` JSON array to the server, which builds the full multi-class progression via `LevelUpCalculator.buildMulticlassProgression()`. ASI choices are applied post-creation by showing `AsiModal` sequentially for each pending ASI level, calling the existing `applyChoices` endpoint.

**Rationale:** Integrating ASI choices into the creation wizard would require a parallel version of `AsiModal` that works without a persisted character (no ID for API calls, no ability to save feats). The post-creation approach reuses the existing `AsiModal` and `applyChoices` endpoint unchanged. The `findNextAsiEntry()` helper ensures each `applyChoices` call records on the correct level history entry by scanning forward for the first ASI-eligible entry without a recorded choice.

**Trade-offs:** The character exists briefly without its ASI bonuses applied. If the user closes the browser during the ASI flow, the character has correct HP and features but missing ASI choices — they can apply them later via the level-up flow. The sequential modal UX is slightly less integrated than an inline wizard step, but avoids significant code duplication.

## D058: ASI History Recording Targets First Unrecorded ASI Level

**Date:** 2026-07-19
**Status:** Superseded by D159

**Decision:** `recordAsiInHistory` and `recordFeatInHistory` find the target level history entry via `findNextAsiEntry()` — scanning forward through the history for the first entry where `LevelUpCalculator.isAsiLevel(className, classLevel)` is true and `choices.asi` is absent — rather than always targeting the last entry.

**Rationale:** The previous approach (always using `history.get(history.size() - 1)`) was correct for the single-ASI level-up flow but incorrect for post-creation ASI application. A level 8 Fighter has ASI at class levels 4, 6, and 8, but the last history entry is level 8 regardless of which ASI is being applied. Forward scanning correctly handles both cases: level-up (only one unrecorded ASI, which is the last entry) and post-creation (multiple unrecorded ASIs, applied in order).

**Trade-offs:** None identified. The forward scan is O(n) where n is character level (max 20), which is negligible.

## D059: Wizard Spellbook as Curated spellsKnown Subset

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Wizard spellbook management uses the existing `spellsKnown` JSONB array with `source: "class:Wizard"`. At creation, Wizard spells are stored with `prepared: false` (unlike other prepared casters which default to `prepared: true`). The character sheet provides "Add to Spellbook" (search full class list, add with `prepared: false`) and "Remove from Spellbook" buttons. The "Change Prepared" modal for Wizard filters to only spellbook spells rather than searching the full class list. Starting spellbook count is `6 + (level - 1) * 2` per `wizardSpellbookCount()`.

**Rationale:** Wizards are unique among prepared casters: they prepare from a curated spellbook, not the full class list. Other prepared casters (Cleric, Druid, Paladin) have implicit access to all class spells and only need to toggle prepared status. The spellbook is a persistent, growing collection that the player manages — adding spells found during adventuring, copying from scrolls, etc. Storing spellbook spells as unprepared `spellsKnown` entries with an add/remove workflow matches this two-tier model (spellbook membership, then preparation) while reusing the existing data model.

**Trade-offs:** No schema changes needed. The `prepared: false` default for Wizard creation means a newly created Wizard has no prepared spells and must prepare them from the character sheet — a deliberate design choice matching D&D RAW.

## D060: 1/3 Caster Subclass Support (Eldritch Knight / Arcane Trickster)

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** Eldritch Knight and Arcane Trickster are handled as 1/3 caster exceptions to Fighter and Rogue. They gain spellcasting at class level 3 with dedicated constants (`THIRD_CASTER_CANTRIPS`, `THIRD_CASTER_SPELLS`) indexed by CLASS level (not character level). Max spell level is `ceil(classLevel / 6)` capped at 4. Multiclass spell slot contribution is `floor(classLevel / 3)`. Spell selection in the creation wizard uses a dedicated `ThirdCasterSpellSelectionStep` component that searches the appropriate spell list class (Wizard for both).

**Rationale:** 1/3 casters don't fit the full/half/pact caster taxonomy. Their progression is by class level, not character level, and they use a different class's spell list. Separate constants and a dedicated component avoid overloading the existing spell selection logic.

**Trade-offs:** Constants are duplicated between EK and AT (same values); this is intentional — they may diverge in supplements. The spell list class lookup (`THIRD_CASTER_SPELL_LIST`) enables future 1/3 caster subclasses with different lists.

## D061: Subclass Always-Prepared Spells on Character Sheet

**Date:** 2026-07-19
**Status:** Accepted

**Decision:** The `subclassAlwaysPreparedSpells` field (parsed from 5etools `additionalSpells.prepared`/`expanded` during seeding) is exposed on `CharacterResponse` from the Subclass entity. The character sheet displays unlocked spells in a dedicated section keyed by class level, with an "Always Prepared" badge and lock icon. Spells are filterable by the character's current class level from their primary class entry in `multiclassEntries`.

**Rationale:** Always-prepared spells are a core subclass feature for Clerics (domains), Paladins (oaths), Druids (circles), and others. Displaying them separately from the main spell list makes it clear which spells don't count against prepared limits.

**Trade-offs:** Only the primary class subclass is shown; secondary class subclasses in multiclass are not yet surfaced (deferred until multiclass-at-creation support matures).

## D062: Per-Class Multiclass Spellcasting Stats

**Date:** 2026-07-19
**Status:** Accepted (partially superseded by D158 and D167)

**Decision:** When a multiclass character has spellcasting classes with different abilities, the Spells tab shows per-class spell save DC and attack bonus instead of the single global values. The frontend computes these from the character's ability scores, proficiency bonus, and the known spellcasting ability mapping (`CASTER_ABILITY`). The backend's `CharacterService` iterates all class entries in `multiclassEntries` to find the first spellcasting class for the global spell stats (for backwards compatibility).

**Rationale:** A Paladin/Druid multiclass uses CHA for Paladin spells and WIS for Druid spells — a single spell save DC is misleading. Per-class display matches how D&D 5e multiclass spellcasting actually works.

**Trade-offs:** Per-class stats are computed client-side from a hardcoded ability mapping rather than stored on the entity. This is appropriate since the mapping is static (derived from PHB) and the character sheet already has all needed data.

## D063: Stale Subclass Reference on Class Level Reduction

**Date:** 2026-07-20
**Status:** Accepted (known limitation)

**Decision:** When a multiclass entry's class level is reduced below its `subclassLevel`, the subclass picker UI disappears but the stale subclass reference remains in the `classEntries` state. No client-side cleanup is performed.

**Rationale:** The server validates subclass level requirements independently and will reject or ignore a subclass that doesn't meet the level threshold. The stale reference in frontend state is harmless — if the user later increases the level back above `subclassLevel`, the previously selected subclass reappears, which is arguably better UX than forcing re-selection. The server is the source of truth for character data integrity.

**Trade-offs:** Inconsistent UI state (subclass reference exists but picker is hidden). Accepted because the server enforces correctness and the stale reference provides a convenience if the level change was accidental.

## D064: Expertise Support for Rogue and Bard

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Expertise (doubled proficiency bonus) is supported at both creation and level-up for Rogue (levels 1, 6) and Bard (levels 3, 10). The `skillExpertises` JSON array on `PlayerCharacter` stores expertise selections. At creation, the wizard shows an expertise picker when the character has Rogue or Bard levels that qualify. During level-up, an `ExpertiseModal` appears after ASI/subclass choices when leveling into an expertise-granting level.

**Rationale:** Expertise is a core class feature per PHB. Without it, Rogues and Bards are mechanically incomplete. The `isExpertiseLevel()` utility centralizes the level check logic for both flows.

**Trade-offs:** Only skill expertise is supported — tool expertise (which Rogues can also choose) is not yet implemented. The expertise picker only shows skills the character is proficient in, matching PHB rules.

## D065: Multiclass Proficiency Grants

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** When multiclassing, secondary classes grant only their `multiclassProficiencies` (a subset of full class proficiencies per PHB Chapter 6). The `CharacterCreateWizard` reads `multiclassProficiencies` from each secondary class entry and merges armor/weapon/tool proficiencies. Skill choices from multiclass proficiencies are presented via a separate picker per secondary class, deduplicating against already-taken skills.

**Rationale:** PHB multiclass rules explicitly limit proficiency grants for secondary classes. Full class proficiencies would be overpowered and incorrect. The `multiclassProficiencies` data was seeded from 5etools in M10.

**Trade-offs:** The multiclass skill picker only appears at creation, not during level-up into a new class. Level-up multiclass proficiency grants are handled server-side in `CharacterService.levelUp()`.

## D066: 1/3 Caster Spell List on Character Sheet

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** The character sheet's Spells tab detects 1/3 caster subclasses (Eldritch Knight, Arcane Trickster) from `multiclassEntries` and shows a spell management box with the correct spell list class (Wizard for both EK and AT). The `ManageSpellsModal` accepts a `spellListClass` prop that overrides the `className` used for API spell search, so EK/AT characters browse the Wizard spell list instead of the Fighter/Rogue list.

**Rationale:** EK and AT use the Wizard spell list per PHB, not their parent class spell list. Without this mapping, the "Manage Known" modal would search for Fighter/Rogue spells (which don't exist) and show zero results.

**Trade-offs:** The `spellListClass` mapping is hardcoded in `THIRD_CASTER_SPELL_LIST` in `spellConstants.ts`. This is correct for the PHB but would need extension if homebrew 1/3 caster subclasses use different spell lists.

## D067: Expertise Modal in Level-Up Flow

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Added `ExpertiseModal` component to the level-up choice chain (LevelUp → ASI → Subclass → Expertise → Done). Rogue gets 2 expertise slots at class levels 1 and 6; Bard gets 2 at class levels 3 and 10. The modal shows all proficient skills not already marked as expertise, and the selected skills are saved via `applyChoices` with `expertiseSkills` field. Backend merges new expertise into existing `skillExpertises` JSON array.

**Rationale:** Expertise is a core Rogue/Bard feature that doubles proficiency bonus on chosen skills. Without automation, players must manually track this.

**Trade-offs:** `isExpertiseLevel()` is hardcoded for Rogue/Bard. Homebrew classes with expertise at different levels would need updates.

## D068: Expanded Spell List Column on Subclasses

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Added `expanded_spell_list` JSONB column to the `subclasses` table, separate from `always_prepared_spells`. The `CharacterClassSeeder` parses the 5etools `expanded` key (as opposed to `prepared`) into this column. Expanded spells are added to the class's spell list for selection but are not always prepared.

**Rationale:** 5etools distinguishes between `prepared` (always prepared, e.g., Land Druid circle spells) and `expanded` (added to selection list, e.g., Warlock patron spells). These are mechanically different: expanded spells can be chosen when learning/preparing spells but don't auto-prepare.

**Trade-offs:** The frontend does not yet use `expandedSpellList` to filter the spell selection modal — that integration is deferred. The data is seeded and available via the API.

## D069: Race Spell Level Lookup from Spell Data

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** The `RaceSeeder` now builds a spell level lookup map from `spells.json` during seeding and uses it to set the correct spell level for race spells that don't have an explicit `#N` cast-level suffix. Previously, spells like Darkness (a 2nd-level spell) were stored with `level: 0` because no suffix was present.

**Rationale:** Race spells should display at their actual spell level for correct sorting and grouping in the Spells tab. The 5etools data only includes explicit cast levels (via `#N` suffix) when the spell is cast at a higher level than normal (e.g., Tiefling's Hellish Rebuke at 2nd level).

**Trade-offs:** Adds a spells.json read during race seeding. Since spells are seeded first, the data is always available. The lookup is built once per seed run.

## D070: Multiclass Subclass Written to multiclassEntries

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** When `applyChoices` is called with a `subclassId` for a secondary multiclass, the backend now writes `subclassId`/`subclassName` into the matching `multiclassEntries` JSON entry. A new `classId` field in `ApplyChoicesRequest` identifies which class the subclass belongs to. For primary classes, `character.subclassRef`/`subclass` are still set. For secondary classes, only `multiclassEntries` is updated.

**Rationale:** Previously, `applyChoices` always overwrote the primary class's subclass fields regardless of which class the subclass belonged to, and never updated `multiclassEntries`. This caused: (1) the primary class's subclass being overwritten by a secondary class's subclass, (2) spell slot calculation not detecting 1/3 casters (which reads `subclassName` from `multiclassEntries`), and (3) the character sheet not showing spell sections for 1/3 caster subclasses.

**Trade-offs:** The `classId` must be passed from the frontend. If omitted, the backend defaults to the primary class for backwards compatibility.

## D071: Multiclass Caster Spell Sections on Character Sheet

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** The character sheet Spells tab now creates empty spell groups for ALL multiclassed caster classes from `multiclassEntries`, not just the primary class and 1/3 casters. Regular casters (Druid, Cleric, etc.) and 1/3 casters (EK, AT) taken via multiclass all get a spell management section even when no spells have been selected yet.

**Rationale:** Previously, only the primary class and 1/3 caster subclasses (with `subclassName` in `multiclassEntries`) got empty spell groups. Secondary caster classes like Druid taken via multiclass were invisible on the Spells tab, making it impossible to select or manage their spells.

**Trade-offs:** Uses a hardcoded `SPELLCASTER_CLASSES` list to determine which classes are casters. This is correct for PHB but would need extension for homebrew classes.

## D072: Spell Selection After Level-Up

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** After level-up into a spellcasting class completes (ASI/subclass/expertise choices resolved), the frontend automatically switches to the Spells tab and opens the ManageSpellsModal for the leveled class. For known casters, the modal opens in "known" mode; for prepared casters, it opens in "prepared" mode. For 1/3 caster subclasses, this is triggered after the subclass choice resolves.

**Rationale:** Players expect to select spells when leveling into a caster class. Without this prompt, new multiclass casters had to manually navigate to the Spells tab and find the right button.

**Trade-offs:** The modal auto-opens but can be dismissed. If the player skips it, they can still use the "Manage Known" / "Change Prepared" buttons on the Spells tab at any time.

## D073: Feat Spell Entries Include Full Data

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** When a feat grants spells via the ASI modal (level-up), `FeatEffectResolver.applyFeatSpells()` now looks up each spell from the repository to populate `name` and `level`, and parses the feat's `grantsFeatures` to determine `usesPerLongRest` and `atWill` status. Previously, only `id` and `source` were stored, causing blank spell lines on the character sheet.

**Rationale:** The frontend's Spells tab renders spell entries by `name` and displays usage info (`usesPerLongRest`, `atWill`). Missing data caused blank lines and clicking them triggered a nameless search returning an unrelated spell.

**Trade-offs:** Added `SpellRepository` dependency to `FeatEffectResolver`. The `parseFeatSpellUsage` helper parses the 5etools `additionalSpells` JSON format to extract daily usage counts.

## D074: Feat Ability Score Increase in Character Creation

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Feats with optional ability score increases (e.g., Fey Teleportation's +1 INT or CHA) now show a choice picker in the character creation wizard's Background step. The chosen bonus is applied to `finalScores` before character submission. The `abilityScoreIncrease` field (from the feat's top-level `ability` data) is parsed separately from the spellcasting ability (from `additionalSpells[].ability`).

**Rationale:** The 5etools data has two distinct `ability` concepts: the feat's stat increase (top-level) and the granted spells' casting ability (inside `additionalSpells`). The wizard previously only handled the latter as "Spellcasting Ability" and never applied the actual stat increase.

**Trade-offs:** Added `selectedFeatAsiAbility` state and `featAsi` memo to the wizard. Feats with fixed ASI show the bonus as text; feats with choose-from-list ASI show a button picker.

## D075: Handle @JsonRawValue Pre-Parsed Fields in Frontend

**Date:** 2026-07-20
**Status:** Superseded by D168

**Decision:** Frontend parsing functions for `@JsonRawValue` JSONB fields (`abilityScoreIncrease`, `effects`, `grantsFeatures`, `prerequisite`) must check `typeof value === 'string'` before calling `JSON.parse()`. Jackson's `@JsonRawValue` outputs raw JSON without quoting, so the browser's JSON parser converts these to native JS objects/arrays before the frontend code runs.

**Rationale:** `JSON.parse()` on an already-parsed array coerces it to `"[object Object]"` and throws SyntaxError. The catch block silently returned `null`, which caused the AsiModal's ability score choice UI to never render for feats like Fey Teleportation. The `parseFeatOptions` function already had this guard; `parseAbilityScoreIncrease` and `parseFeatEffects` did not.

**Trade-offs:** None. This is a pure bug fix. All `@JsonRawValue` fields should use this pattern going forward.

## D076: Replace Silent Exception Swallowing with Logging

**Date:** 2026-07-20
**Status:** Superseded by D155

**Decision:** Replace all `catch (Exception ignored) {}` blocks across CharacterService and FeatEffectResolver with `log.warn`/`log.error`. Critical operations (`recalculateSpellSlots`, `appendLevelHistory`) now re-throw after logging.

**Rationale:** 18 silent catch blocks were hiding data corruption and making bugs invisible. The 6-way architecture review flagged this as the highest-priority fix.

**Trade-offs:** Critical operations now throw, which could surface previously-hidden errors. This is intentional — failing loudly prevents silent data corruption.

## D077: CharacterService Extraction to CharacterMapper and CharacterJsonHelper

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Extract `toResponse()` and `buildAllSubclassAlwaysPreparedSpells()` into `CharacterMapper`, and 15 JSON manipulation methods into `CharacterJsonHelper`. CharacterService delegates to both via constructor injection.

**Rationale:** CharacterService was ~1457 lines with mixed concerns (business logic, DTO mapping, JSON manipulation). Extraction reduces it to ~997 lines and makes each class single-responsibility.

**Trade-offs:** Three files to navigate instead of one. Offset by each file being focused and independently testable.

## D078: Typed JSONB Records (LevelHistoryEntry, MulticlassEntry, HitDiceEntry)

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Add Java records (`LevelHistoryEntry`, `MulticlassEntry`, `HitDiceEntry`, `FeatureRecord`) to replace `Map<String, Object>` for JSONB deserialization throughout CharacterJsonHelper and CharacterService.

**Rationale:** Raw maps provide no compile-time safety. Typos in map keys (e.g., `classid` vs `classId`) silently return null. Typed records catch schema drift at compile time and make the code self-documenting.

**Trade-offs:** Records are immutable, so mutations require creating new instances (e.g., updating a level requires `new MulticlassEntry(...)` instead of `entry.put("level", ...)`). Accepted because immutability reduces side-effect bugs.

## D079: Reference Data Caching with @Cacheable

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Add `@EnableCaching` on the application class and `@Cacheable` annotations on 13 reference data endpoints (spell filters, conditions, item filters, races, classes, subclasses, backgrounds, feats).

**Rationale:** Reference data changes only when the database is re-seeded. Caching eliminates ~80% of DB reads for read-heavy reference lookups. Uses Spring's default `ConcurrentMapCacheManager` (in-memory, no expiry) — appropriate since reference data is effectively immutable at runtime.

**Trade-offs:** Cache is never evicted (requires app restart to pick up reference data changes). Acceptable for this project's deployment model.

## D080: FK Indexes and GIN Index for Query Performance

**Date:** 2026-07-20
**Status:** Superseded by D170

**Decision:** Add `@Index` annotations on FK columns (`user_id`, `campaign_id` on `PlayerCharacter`; `encounter_id`, `character_id` on `EncounterParticipant`; `encounter_id` on `CombatLog`). Add a GIN index on `spells.classes` JSONB column via `schema.sql`.

**Rationale:** PostgreSQL does not automatically index FK columns. All these FKs are used in WHERE/JOIN clauses. The GIN index enables efficient `@>` containment queries on the JSONB `classes` array in the spells table.

**Trade-offs:** Slight write overhead from index maintenance. Negligible at this scale.

## D081: Shared Frontend Utility Module (dndRules.ts)

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Extract duplicated D&D constants and utility functions (`ABILITIES`, `ALL_SKILLS`, `abilityMod`, `formatMod`, `safeJsonParse`) into `frontend/src/utils/dndRules.ts`. Update CharacterSheetPage, MonsterStatBlock, and AsiModal to import from the shared module.

**Rationale:** 6+ files had independent copies of ability score arrays, skill lists, and modifier calculations. Any fix or change required updating all copies.

**Trade-offs:** None. Pure deduplication.

## D082: Input Validation on Character Update Endpoint

**Date:** 2026-07-20
**Status:** Superseded by D158

**Decision:** Add `@Valid` to the `updateCharacter` endpoint and Jakarta validation constraints on `CharacterUpdateRequest` fields: `@Min/@Max` on level (1-20), ability scores (1-30), HP, AC, speed, proficiency bonus; `@Size(max=100)` on name.

**Rationale:** Previously, any value could be submitted via the API, potentially creating invalid game state (negative HP max, level 0, etc.).

**Trade-offs:** May reject legitimate edge cases (ability scores >30 from magic items). The constraint is on the API input, not the stored value, so DM-applied effects can bypass this if needed.

## D083: Short Rest Hit Dice Spending and Warlock Pact Slot Reset

**Date:** 2026-07-20
**Status:** Accepted (partially superseded by D158)

**Decision:** Replace the single-die short rest with a multi-die spending UI (per-class +/- buttons with remaining dice tracking). Add warlock pact slot reset on short rest. Add short-rest feat resource resets.

**Rationale:** The original short rest implementation only spent a single hit die of an arbitrary class. D&D 5e allows spending multiple hit dice from any class, and warlocks recover pact magic slots on short rest.

**Trade-offs:** More complex UI, but essential for rules accuracy.

## D084: Concentration Save CON Proficiency Fix

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Fix `CombatService.checkConcentration()` to include Constitution saving throw proficiency bonus when calculating concentration checks. Added `hasConSaveProficiency()` helper that parses the `savingThrowProficiencies` JSON array.

**Rationale:** Classes like Sorcerer have CON save proficiency, which was being ignored in concentration checks. This gave an incorrect DC comparison.

**Trade-offs:** None. Pure bug fix.

## D085: LevelUp/LevelDown Integration Tests

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Add 16 unit tests with Mockito mocks covering single-class levelUp, levelDown, round-trip state restoration, ownership validation, level history integrity, and spellcaster slot recalculation.

**Rationale:** LevelUp/LevelDown is the most fragile code path — it mutates HP, features, hit dice, spell slots, multiclass entries, and level history. Zero tests existed before this.

**Trade-offs:** Tests use mocked repositories (not a real DB), so they don't catch JPA/SQL issues. Full integration tests would require `@SpringBootTest` + test database, which is a future improvement.

## D086: localStorage Draft Saving and beforeunload Guard for Character Wizard

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Add `beforeunload` event handler when the wizard has meaningful data, and persist wizard draft to localStorage on step/field changes. Show a restoration banner on mount if a draft exists. Clear draft on successful character creation.

**Rationale:** Users reported losing in-progress character creation when accidentally navigating away or closing the tab. This was the most frustrating UX failure identified in the review.

**Trade-offs:** localStorage has a 5MB limit (plenty for a single wizard draft). Draft may become stale if reference data changes between sessions.

## D087: Shared Frontend Utilities in dndRules.ts

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Extract duplicated D&D constants and functions (ABILITIES, abilityMod, formatMod, safeJsonParse, skill lists, ability abbreviation maps) into `frontend/src/utils/dndRules.ts`. All components import from this single source.

**Rationale:** Six components had independent copies of the same constants and functions. Updates required finding and changing every copy — a maintenance hazard that the architecture review flagged.

**Trade-offs:** None significant. All consumers already used identical implementations.

## D088: CharacterCreateWizard Split into Step Components

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Split the 3664-line `CharacterCreateWizard.tsx` into 8 files under `wizard/`: `types.ts` (188 lines — shared types, constants, utilities), `BasicInfoStep.tsx` (65 lines), `RaceStep.tsx` (176 lines), `AbilityScoresStep.tsx` (165 lines), `ClassStep.tsx` (400 lines), `BackgroundStep.tsx` (463 lines), `SpellsStep.tsx` (1101 lines), and `ReviewStep.tsx` (264 lines). The main wizard is now 902 lines.

**Rationale:** The architecture review flagged the wizard as the largest single file. Extracting each wizard step into its own component with a typed props interface reduces the main file by 75% and makes each step independently readable and testable. All state remains in the parent; step components are pure render + callbacks.

**Trade-offs:** Props interfaces are large (especially SpellsStep and ClassStep) due to the amount of state the parent manages. This is acceptable because the prop drilling makes data flow explicit and avoids hidden coupling.

## D089: Database Indexes on Foreign Keys and GIN on spells.classes

**Date:** 2026-07-20
**Status:** Superseded by D170

**Decision:** Add JPA `@Table(indexes)` on `player_characters.user_id`, `player_characters.campaign_id`, `encounter_participants.encounter_id`, `encounter_participants.character_id`, and `combat_logs.encounter_id`. Add a PostgreSQL GIN index on `spells.classes` via `schema.sql`.

**Rationale:** All FK columns are used in WHERE/JOIN queries but had no indexes. The GIN index supports containment queries on the JSONB classes column for spell filtering by class.

**Trade-offs:** Minor write overhead. Using `ddl-auto: update` with JPA `@Table(indexes)` for FK indexes; `schema.sql` with `CREATE INDEX IF NOT EXISTS` for the GIN index since JPA doesn't support GIN natively.

## D090: Reference Data Caching with @Cacheable

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Add `@EnableCaching` to the application and `@Cacheable` to 13 reference data endpoints (spell schools, spell sources, spell classes, conditions, item types, item rarities, item sources, races, race sources, classes, subclasses, backgrounds, feats).

**Rationale:** Reference data changes only on redeploy. Caching eliminates ~80% of repeated DB reads for data that is loaded on nearly every wizard/sheet page view.

**Trade-offs:** Uses Spring's default ConcurrentMapCache (in-memory, no TTL, no eviction). Adequate for a single-instance deployment. Cache is invalidated by restart.

## D091: CharacterService Extraction to CharacterMapper and CharacterJsonHelper

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Extract `toResponse()` and `buildAllSubclassAlwaysPreparedSpells()` into `CharacterMapper` (138 lines). Extract all JSON manipulation helpers (appendFeatures, removeFeatures, updateHitDiceMap, buildHitDiceTotal, updateMulticlassEntries, mergeJsonArray, appendLevelHistory, findNextAsiEntry, recordAsiInHistory, recordFeatInHistory, parseFeaturesList) into `CharacterJsonHelper` (347 lines). CharacterService delegates to both via injected fields.

**Rationale:** CharacterService was a 1420-line god object with 10 dependencies. Extracting mapping and JSON manipulation into focused components reduces it to 997 lines and makes each helper independently testable.

**Trade-offs:** Adds two more Spring beans and constructor parameters. The service still has core business logic that could be further decomposed if it grows again.

## D092: Character GET Endpoint Auth Check

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Add ownership and campaign membership validation to `GET /api/characters/{id}`. A user can view a character if they own it or are a member of its campaign.

**Rationale:** The endpoint had no auth check — any authenticated user could read any character by UUID (IDOR vulnerability). All other character endpoints already validated ownership.

**Trade-offs:** DMs viewing campaign members' sheets now works by design rather than by accident. Characters not assigned to a campaign can only be viewed by their owner.

## D093: Unconscious Attack Auto-Crit with isRanged Override

**Date:** 2026-07-20
**Status:** Superseded by D161

**Decision:** Attacks against downed targets auto-crit by default (PHB p.292: melee attacks against unconscious creatures are critical hits). Added `isRanged` field to `AttackRollRequest` — when true, the auto-crit is suppressed (ranged attacks don't auto-crit). UI shows a "Ranged" toggle button next to "Crit" on both DM and player encounter pages.

**Rationale:** The system doesn't track melee vs ranged weapon types, so defaulting to auto-crit (the common case) with an opt-out for ranged is the safest approach.

**Trade-offs:** DMs attacking a downed PC at range must remember to toggle "Ranged". Forgetting means double dice damage, which is more harmful than the previous behavior of under-damaging.

## D094: HikariCP Connection Pool Configuration

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Configure HikariCP with 20 max connections (dev) / 30 max connections (prod), 5 minimum idle, 20-second connection timeout.

**Rationale:** Spring Boot defaults to 10 connections. Combat actions hold transactions through multiple repository reads; concurrent encounters can exhaust the default pool.

**Trade-offs:** Higher pool size uses more PostgreSQL backend connections. 20-30 is well within PostgreSQL's default `max_connections` of 100.

## D095: Global React Error Boundary

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Add `ErrorBoundary` component wrapping the entire app in `main.tsx`. Catches unhandled React rendering errors and shows a recovery UI with a reload button.

**Rationale:** Without an error boundary, any component throwing during render crashes the entire app to a white screen with no recovery. This is especially dangerous during combat encounters.

**Trade-offs:** A single top-level boundary is coarse-grained — a bug in the combat log kills the entire page rather than just the log component. Per-section boundaries can be added later for high-risk areas.

## D096: CharacterSheetPage Split into Tab Components

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Split the 2103-line `CharacterSheetPage.tsx` into 8 files under `sheet/`: `types.ts` (shared SpellEntry interface), `StatCard.tsx`, `StatsTab.tsx`, `ActionsTab.tsx`, `SpellsTab.tsx` (includes ManageSpellsModal and AddFeatModal), `InventoryTab.tsx`, `FeaturesTab.tsx`, and `JournalTab.tsx`. The main page is now 570 lines (state management, header, HP bar, tab routing, rest/level modals).

**Rationale:** The second architecture review flagged this as the largest remaining file. Each tab was already a separate function at the bottom of the file — extraction to separate files was mechanical. SpellsTab at 1190 lines is the largest component due to its two embedded modals, which are tightly coupled and not worth splitting further.

**Trade-offs:** Props are passed down from the parent rather than each tab accessing shared state directly. This makes data flow explicit at the cost of larger prop interfaces on SpellsTab.

## D097: Flyway Database Migration Management

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Replaced Hibernate `ddl-auto: update` with `ddl-auto: validate` and added Flyway for schema migration management. First migration `V1__gin_index_spells_classes.sql` moves the GIN index from `schema.sql` to Flyway. Configured `baseline-on-migrate: true` with `baseline-version: 0` to handle existing databases.

**Rationale:** `ddl-auto: update` silently alters production schemas and can't handle index creation, column renames, or data migration. Flyway provides versioned, repeatable migrations with rollback capability.

**Trade-offs:** All future schema changes must go through Flyway migration files. Developers must run `flyway:migrate` or let Spring Boot auto-migrate on startup.

## D098: JPA EntityGraph on Character Listing Queries

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Added `@EntityGraph(attributePaths = {"user", "campaign", "raceRef", "classRef", "subclassRef", "backgroundRef"})` to both `findByUserIdAndIsActiveTrue` and `findByCampaignIdAndIsActiveTrue` repository methods.

**Rationale:** `CharacterMapper.toResponse()` accesses all 6 lazy `@ManyToOne` relationships. Without the entity graph, listing N characters generates up to 6N+1 queries. With the entity graph, it's a single query with LEFT JOINs.

**Trade-offs:** The query is wider (more columns) but eliminates N+1. For the typical case of 1-10 characters per user, this is a clear win.

## D099: AsiModal FeatPicker Extraction

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Extracted `FeatPicker.tsx` (~530 lines) from `AsiModal.tsx`, reducing the modal to ~160 lines. FeatPicker manages all feat-related state internally and communicates via `onSelectionChange(AsiChoice | null)` callback.

**Rationale:** The 903-line AsiModal was the largest remaining component file. The feat selection UI (search, prerequisites, choices, spell picker) is a self-contained concern with complex internal state.

**Trade-offs:** The callback pattern means AsiModal can't inspect FeatPicker's internal state — it only knows whether a valid selection exists and what the final AsiChoice is.

## D100: ARIA Accessibility Attributes

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Added basic ARIA attributes across the frontend: `role="dialog"` + `aria-modal` + `aria-labelledby` on all 5 modals, `aria-label="Close"` on icon-only close buttons, `role="alert"` on error messages, `<nav aria-label>` on dashboard headers, `aria-expanded` + `aria-haspopup` + `role="listbox"` on MultiSelect, `role="tablist"` + `role="tab"` + `aria-selected` on CharacterSheetPage tabs.

**Rationale:** The codebase had essentially zero ARIA attributes. These additions cover the highest-impact areas for screen reader users without requiring structural changes.

**Trade-offs:** Focus trapping in modals is not yet implemented — just the semantic markup. Full keyboard accessibility would require a focus trap hook.

**Update (2026-11-02, R4 planning):** the shared `Modal` (D171) traps focus, closes on Escape and returns focus to the opener. Every dialog uses it except the three in `SpellsTab`, which move to it when R8 rebuilds that screen; icon-only buttons use `IconButton`, which requires a label.

## D101: CombatService Damage Deduplication

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Extracted `applyDamageToTarget()` private method with a `DamageResult` record return type from `CombatService`. Both `rollAttack()` and `applyDamage()` now delegate to this method instead of duplicating temp HP absorption + HP reduction + death/dying logic.

**Rationale:** Three copies of nearly identical damage application code meant bug fixes had to be applied in multiple places, and the unconscious auto-crit fix revealed this duplication.

**Trade-offs:** The helper returns a record with `actualDamage` and `droppedToZero` fields so callers can still make decisions based on the damage outcome.

## D102: Comprehensive Testing Suite (M25)

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Implemented 330 tests (220 backend + 110 frontend). Backend uses JUnit 5 + Mockito; frontend uses Vitest + jsdom. Tests cover all domain logic (spell slots, level up/down, multiclass validation, feat effects, combat mechanics, dice rolling), all services (CharacterService, CombatService, EncounterService, AuthService, CampaignService), all utilities (markup parsing, JSON helpers, exception handling), and all frontend utility functions (D&D rules, spell constants, feat prerequisites, feat spell parsing, markup parsing, wizard helpers).

**Rationale:** The codebase had only 53 tests before this milestone. With major refactoring and new features landing, a comprehensive test suite was needed before adding more features. Tests were prioritized by risk: FeatEffectResolver reversal symmetry, CombatService rules, CharacterJsonHelper mutations, then progressively less risky areas.

**Trade-offs:** Component-level React tests and integration tests (requiring a test database) are deferred — the current suite covers logic and service layers. CharacterService gained `shortRest()` and `longRest()` methods as part of making the business logic testable.

## D103: Spell Stats Snapshot on Encounter Participant

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Copy `spellAttackBonus`, `spellSaveDc`, `spellcastingAbility`, and `spellsKnown` from `PlayerCharacter` to `EncounterParticipant` when a player is added to an encounter. The SpellResolverEngine reads these from the participant, not the character.

**Rationale:** Follows the existing snapshot pattern for `hpMax` and `armourClass`. Encounter participants are combat snapshots — the character sheet can change mid-encounter (e.g., leveling between sessions), and combat should use the stats that were current when the encounter started. Reading from participant also avoids needing to load the full character entity during spell resolution.

**Trade-offs:** Spell stats diverge from the character sheet after encounter start. This is intentional and consistent with how HP/AC are already handled.

## D104: DM Monster Spell Overrides

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** `CastSpellRequest` includes `overrideSpellAttackBonus` and `overrideSpellSaveDC` fields. When the DM casts a spell for a monster, these values override the participant's (likely null) spell stats. The frontend shows input fields for these when `isMonster` is true.

**Rationale:** Monsters don't have snapshot spell stats because they aren't added from a PlayerCharacter. Monster spellcasting stats are in the stat block's free-text description, not in structured columns. Rather than parsing monster spellcasting from text (fragile and error-prone), the DM enters the values from the stat block.

**Trade-offs:** DMs must manually look up and enter spell attack bonus and save DC from the monster's stat block. This will be improved in M12 when monster actions and spellcasting get structured data.

## D105: Concentration Cascade via Source-Tracked Conditions

**Date:** 2026-07-20
**Status:** Accepted (partially superseded by D162)

**Decision:** `dropConcentrationCascade()` iterates ALL encounter participants and removes any condition where `sourceRequiresConcentration=true` AND `sourceSpellName` and `sourceParticipantId` match the concentrating caster. This method is wired into all three concentration-loss paths: `dropConcentrationOnZeroHp()`, `checkConcentration()` on failed save, and `setConcentration()` when replacing with a new spell. Each removed condition is logged to the combat log.

**Rationale:** D&D 5e requires all spell effects to end when concentration drops. Without cascade, the DM must manually remove conditions from every affected target — error-prone with spells like Bless or Entangle that affect multiple targets. Source tracking (D032) provides the data; this decision wires the automation.

**Trade-offs:** The cascade iterates all participants on every concentration drop, which is O(participants × conditions). With typical encounter sizes (10–20 participants, 0–3 conditions each), this is negligible.

## D106: SpellResolverEngine as Pure Logic Service

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** `SpellResolverEngine` is a `@Service` with no `@Transactional` annotation. It receives entities as method parameters and returns a `SpellCastResult` record — it never persists anything. All persistence (damage application, condition addition, concentration setting, combat logging) is handled by the calling `CombatService.castSpell()` method within its transaction.

**Rationale:** Separating resolution logic from persistence makes the engine independently testable (40 unit tests with mocked repositories) and ensures the full spell cast is atomic — if any step fails, the entire transaction rolls back. The engine is a pure function from (encounter state, spell template, targets) → (result), which is easier to reason about.

**Trade-offs:** The engine loads the spell entity via `SpellRepository.findByNameIgnoreCase()`, so it does have one repository dependency. This could be removed by passing the effect template as a parameter, but the current approach is simpler for the caller.

## D107: Spell Slot Format Conversion at Encounter Join

**Date:** 2026-07-20
**Status:** Superseded by D168

**Decision:** `EncounterService.addPlayerParticipant()` converts spell slots from `{used, total}` (PlayerCharacter format) to `{remaining, max}` (encounter format) via `convertSpellSlotsFormat()`. The conversion is idempotent — it handles both formats as input.

**Rationale:** The character builder and encounter system evolved independently with different slot formats. Rather than migrating all existing character data (which would require coordinating frontend changes), we convert at the boundary — when a character joins an encounter. This is the single point where the two systems meet.

**Trade-offs:** Two formats still exist in the codebase. A future cleanup could unify on `{remaining, max}` everywhere, but the conversion approach works now and doesn't risk breaking the character builder.

## D108: Race Spell Level Lookup from Database

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** `RaceSeeder.buildSpellLevelLookup()` queries `SpellRepository.findAll()` to build the spell name → level map, rather than reading from JSON files. `fixRaceSpellLevels()` runs on every startup after spell seeding.

**Rationale:** 5e.tools spell data is split across multiple source-book JSON files (`spells-phb.json`, `spells-xge.json`, etc.) — there is no single `spells.json`. The database is the single source of truth after seeding, and the query is simple and fast.

**Trade-offs:** The fix runs on every startup even if no corrections are needed, adding a few milliseconds. The alternative (tracking whether the fix has run) would add complexity for negligible benefit.

## D109: Flyway V3 Migration for CHECK Constraint

**Date:** 2026-07-20
**Status:** Accepted

**Decision:** Added Flyway migration V3 to drop and recreate the `combat_logs_action_type_check` constraint with all 16 action types including `SPELL_CAST`.

**Rationale:** The CHECK constraint was originally auto-generated by Hibernate's `ddl-auto: update` based on the `@Enumerated` annotation on `CombatActionType`. Adding a new enum value doesn't trigger Hibernate to update the CHECK constraint, so a manual migration is required.

**Trade-offs:** Future additions to `CombatActionType` will also need Flyway migrations to update this constraint. This is preferable to removing the constraint entirely, as it provides data integrity at the database level.

## D110: Spell Damage Effect Lookup Iterates All Effects

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** `SpellResolverEngine.findDamageEffect()` iterates all entries in the `effects` array to find the first with a `damageDice` field, rather than assuming `effects[0]` is the damage effect.

**Rationale:** 11 spell templates place the damage effect at index 1+ (Lightning Lure, Absorb Elements, Armor of Agathys, Ensnaring Strike, Zephyr Strike, Web, Ashardalon's Stride, Elemental Weapon, Meld into Stone, Booming Blade). The first effect in these spells is a non-damage entry (pull, buff, control). Hardcoding index 0 missed damage for all of these.

**Trade-offs:** Marginal iteration overhead (effects arrays are 1-3 entries). No functional downside.

## D112: Target Count Enforcement in Cast Spell

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** Spell target counts are now enforced at both the backend (`CombatService.validateTargetCount()`) and frontend (`SpellCastModal.tsx` target selection step). The backend rejects requests exceeding the spell's `targetCount` (with upcast scaling applied). The frontend fetches targeting constraints from `GET /api/reference/spells/targeting` and caps the selection UI at the maximum allowed targets, plus filters the target list by `selfOnly`/`canTargetSelf`/`canTargetAllies`/`canTargetEnemies`.

**Rationale:** Previously, the `targetCount` field existed in all 288 spell effect definitions but was completely unused — the Cast Spell modal allowed selecting any number of targets for any spell. This violated D&D 5e targeting rules (e.g., Bless targets 3 creatures at level 1, +1 per upcast level).

**Trade-offs:** The targeting endpoint adds one extra API call per spell cast. Backend validation is the authoritative check; frontend enforcement is purely UX to prevent the user from selecting too many targets.

## D111: Pact Slot Fallback in Use/Restore Endpoints

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** `CombatService.useSpellSlot()` and `restoreSpellSlot()` now fall back to looking up `"pact_N"` when `"N"` is not found in the spell slot map.

**Rationale:** Warlock pact slots are keyed as `"pact_5"` (not `"5"`), but the `SpellSlotRequest` DTO only accepts `slotLevel` as an integer. Without the fallback, the use/restore endpoints could never operate on pact slots, breaking short rest recovery and manual slot management for Warlocks.

**Trade-offs:** The fallback is implicit rather than requiring an explicit `usePactSlot` flag on the DTO. This keeps the API simpler (callers don't need to know whether a slot is a pact slot) but means you can't have both regular and pact slots at the same level and target one specifically. This isn't a real scenario for Warlocks.

## D112: Damage Resistance/Immunity/Vulnerability Handling

**Date:** 2026-07-21
**Status:** Accepted (partially superseded by D161)

**Decision:** `CombatService.applyDamageToTarget()` now checks the target's damage immunities, resistances, and vulnerabilities before applying damage. Immune → 0, resistant → half (rounded down), vulnerable → double. Data is read from Monster (`damageResistances`, `damageImmunities`, `damageVulnerabilities`) and PlayerCharacter entities. Complex conditional resistances (objects with `cond: true`) are skipped — only simple string entries are matched.

**Rationale:** Damage type data was stored on all monsters and characters but was never applied during combat. A lightning-immune creature would take full lightning damage from Call Lightning.

**Trade-offs:** Conditional resistances (e.g., "resistance to bludgeoning from nonmagical weapons") require DM adjudication and are intentionally skipped. The DM can still override damage amounts manually.

## D113: Repeatable Concentration Spell Effects

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** Added `repeatEffect` field to spell effect definitions and a `POST /repeat-spell-effect` endpoint. 10 concentration spells (Call Lightning, Moonbeam, Flaming Sphere, etc.) now support repeating their damaging effect on subsequent turns without consuming a spell slot. The concentration slot level is tracked via a new `concentration_slot_level` column to support upcast scaling on repeats. A `RotateCw` button appears next to concentration text in the encounter UI.

**Rationale:** D&D 5e concentration spells like Call Lightning deal damage each turn as an action. Without this, the DM had to manually apply damage each round instead of using the auto-resolver.

**Trade-offs:** `repeatEffect: true` reuses the spell's main damage/save/delivery. For special cases (Witch Bolt: AUTO_HIT, no upcast), `repeatEffect` accepts an override object. The modal always appears when concentration is active — if the spell doesn't have `repeatEffect`, the backend returns an error message rather than filtering the button client-side (avoids per-participant API calls in the DM view).

## D114: Active Spell Tracking for Non-Concentration Persistent Spells

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** Added `active_spell` and `active_spell_slot_level` columns to encounter_participants (V6 migration) to track non-concentration persistent spells like Spiritual Weapon. Spells with `persistsWithoutConcentration: true` in their effect template are stored in these fields on cast. The repeat-spell-effect endpoint checks both concentration and active spell fields.

**Rationale:** Spiritual Weapon is the most commonly used non-concentration persistent spell. It requires a bonus action each turn to attack, which fits the repeat button pattern. Tying repeat effects only to concentration would miss this important spell.

**Trade-offs:** Currently only Spiritual Weapon uses this mechanism at levels 0–3. Crown of Stars (L7) and Animate Objects (L5) would use it when higher-level definitions are added. Active spells are cleared on death (0 HP) but not on concentration checks or new concentration spells — they are independent tracking.

## D115: Manual Resolution Concentration Fix

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** Fixed the `requiresManualResolution` early return in `SpellResolverEngine.resolveSpell()` to pass through `concentration` and `durationRounds` from the spell template. Previously, manually-resolved concentration spells (e.g., Searing Smite, Ensnaring Strike) would not set concentration on the caster, making the repeat button invisible.

**Rationale:** Smite spells require manual resolution for the initial hit but are concentration spells with ongoing repeatable effects. Without this fix, casting Searing Smite would not set concentration, so the DM couldn't use the repeat button for ongoing fire damage.

**Trade-offs:** None — this was a bug fix. The manual resolution path now correctly signals concentration.


## D116: MOD Placeholder Substitution in Spell Resolver

**Date:** 2026-07-21
**Status:** Accepted (partially superseded by D165)

**Decision:** Added `substituteModPlaceholders()` method to `SpellResolverEngine` that replaces both `SPELL_MOD` and `MOD` placeholders in dice expressions with the caster's spellcasting ability modifier (derived from `spellAttackBonus - proficiencyBonus`). Applied to damage dice, healing dice, and repeat effect dice paths. MOD substitution now runs before upcast scaling so `parseDiceExpression` can correctly parse the numeric modifier.

**Rationale:** Spell definitions use `+MOD` for spells where damage/healing includes the spellcasting modifier (Cure Wounds, Healing Word, Spiritual Weapon, etc.). Previously only `SPELL_MOD` was handled (for cantrip scaling like Magic Stone and Green-Flame Blade), causing `1d8+MOD` to throw "Invalid dice expression" at runtime.

**Trade-offs:** `MOD` is now a reserved token in dice expressions. This is acceptable since all dice expressions in the system are internally authored.

## D117: Explicit NONE Delivery Case for Concentration Spells

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** Added explicit `case "NONE"` to the delivery method switch in `SpellResolverEngine.resolveSpell()` that correctly passes through concentration info (`concentration` flag, `concentrationSpellName`, `durationRounds`). Previously, NONE delivery spells fell through to the `default` case which called `manualResult()` — a helper that hardcodes `concentrationSet: false`.

**Rationale:** 8 concentration spells use `NONE` delivery (Dancing Lights, Friends, True Strike, Darkness, Silence, Skywrite, Clairvoyance, Major Image). These are utility/illusion spells where damage resolution doesn't apply, but concentration tracking must still work. The `manualResult()` method was designed as a generic fallback that doesn't examine spell metadata, so it never set concentration.

**Trade-offs:** None. The fix is a targeted case addition that mirrors the `SELF` delivery pattern.

## D118: Flock of Familiars Concentration Correction

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** Changed `concentration: false` for Flock of Familiars in `spell-effect-definitions.json`. The spell was incorrectly marked as concentration in the original data.

**Rationale:** Per D&D 5e 2014 rules (Icewind Dale: Rime of the Frostmaiden), Flock of Familiars has a 1-hour duration but is NOT a concentration spell. The familiars persist for the full duration without requiring concentration.

## D119: Caddy Over Nginx for Production Reverse Proxy

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** Use Caddy as the production reverse proxy and static file server instead of Nginx. Caddy serves the frontend SPA, reverse proxies `/api/*` and `/ws/*` to the Spring Boot backend, and handles SSL via automatic Let's Encrypt provisioning.

**Rationale:** Caddy provides automatic HTTPS with zero configuration — no certbot, no cron jobs, no manual certificate renewal. The Caddyfile syntax is significantly simpler than Nginx config for our use case (reverse proxy + SPA fallback). WebSocket proxying works out of the box.

**Trade-offs:** Slightly higher memory footprint than Nginx (~15MB vs ~5MB). Less community content/examples. Acceptable for our scale.

## D120: Environment-Driven CORS Configuration

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** `CorsConfig.java` reads allowed origins from the `CORS_ALLOWED_ORIGINS` environment variable (comma-separated), defaulting to `http://localhost:5173` for local development.

**Rationale:** In production, Caddy proxies both frontend and API on the same domain, making CORS headers unnecessary. However, the CORS filter still runs as a Spring bean. Making it configurable avoids hardcoding `localhost:5173` while supporting future setups where frontend and backend might be on separate origins.

## D121: V0 Initial Schema Migration for Production

**Date:** 2026-07-21
**Status:** Accepted

**Decision:** Created `V0__initial_schema.sql` containing all base table definitions (users, campaigns, characters, spells, monsters, encounters, etc.). Disabled `baseline-on-migrate` in the prod profile so Flyway runs V0 as a real migration on fresh databases.

**Rationale:** In development, tables were created by Hibernate `ddl-auto`. The existing migrations (V1-V6) only added indexes, columns, and constraint updates — they assumed the base tables already existed. A fresh production database had no tables, causing V1 to fail with "relation spells does not exist". V0 creates the base schema that V1-V6 build upon.

**Trade-offs:** The dev profile still uses `baseline-on-migrate: true` since local databases already have all tables. New developers must run the local Docker Compose DB before starting the backend.

## D122: HTTP-Only Caddy for IP Access

**Date:** 2026-07-21
**Status:** Superseded by D154

**Decision:** The Caddyfile uses `auto_https off` and listens on `:80` only. No HTTPS is configured while the app is accessed by IP address.

**Rationale:** Caddy cannot provision Let's Encrypt certificates for bare IP addresses. Self-signed certs caused `ERR_SSL_PROTOCOL_ERROR` in browsers, and Chrome's HTTPS-first mode prevented HTTP access via IP. Disabling auto-HTTPS entirely resolves both issues. When a custom domain is purchased, the Caddyfile will be updated to use the domain name and Caddy will auto-provision HTTPS.

**Trade-offs:** Traffic is unencrypted over HTTP. Acceptable for a private game group on a known IP; must be resolved before any public-facing deployment.

## D124: Tourmaline Design System — Tailwind v4 Theme Tokens

**Date:** 2026-07-22
**Status:** Accepted — amended by D173 (selectable themes and a Tourmaline readability pass)

**Decision:** Implement the UI redesign using Tailwind CSS v4's `@theme` block with CSS custom properties, rather than a JS-based design tokens file or a separate CSS variables sheet. All 51 frontend TSX files converted from dark-mode utility classes to semantic token classes (e.g., `bg-gray-900` → `bg-card`, `text-gray-400` → `text-muted`). Typography uses Google Fonts (Cinzel for headings/labels, Cormorant Garamond for body/inputs) loaded via `<link>` in `index.html`. Square corners enforced via global `border-radius: 0 !important`. Class-colored accents provided by `classColours.ts` utility with 13 D&D class colors.

**Rationale:** Tailwind v4 `@theme` blocks make custom properties first-class utilities (e.g., `--color-page` automatically generates `bg-page`, `text-page`, `border-page`). This eliminates the need for a separate token mapping layer. The semantic naming (`card`, `ink`, `muted`, `rule`) provides design intent rather than raw color values, making future palette changes a single-file edit in `index.css`.

**Trade-offs:** The `@theme` block is CSS-only — JS code that needs token values (like `classColours.ts`) must duplicate the hex values. Game-icons replacement and slide-out panels were deferred to keep scope focused on the visual conversion. Lucide-react icons are retained.

## D123: Planned Migration from Hetzner to DigitalOcean

**Date:** 2026-07-21
**Status:** Withdrawn — the Hetzner server and account were closed in November 2026; the next host is chosen in the Hosting step (D154)

**Decision:** Migrate from Hetzner CPX22 (~EUR 24/month) to DigitalOcean Basic Droplet ($12/month, 1 vCPU, 2GB RAM) after the initial Hetzner credit (EUR 25) is consumed.

**Rationale:** Hetzner's current pricing is significantly higher than initially estimated. DigitalOcean offers a 2GB droplet at $12/month — sufficient for the current workload (small group, single game session). The Docker Compose deployment is provider-agnostic; migration requires only a database dump/restore and DNS update.

## D124: Tourmaline Theme UI Redesign

**Date:** 2026-07-22
**Status:** Accepted — amended by D173 (selectable themes and a Tourmaline readability pass)

**Decision:** Replace the default dark-mode UI with a warm parchment light-mode design system ("Tourmaline Theme") using Cinzel and Cormorant Garamond serif fonts, square corners, class-colored accents, and Tailwind v4 CSS custom property tokens.

**Rationale:** The original dark-mode UI (bg-gray-950, sans-serif, rounded corners) looked generic, like a default template. A fantasy-themed light design with serif typography gives the app a distinctive D&D identity. Tailwind v4's `@theme` block with CSS custom properties (`--color-page`, `--color-card`, `--color-ink`, etc.) provides a single source of truth for the design system, making future theming changes trivial. Class colors (13 D&D classes) are applied as accent stripes and text color, not full backgrounds, keeping the interface readable.

**Trade-offs:** Serif fonts have slightly lower information density than sans-serif at small sizes — mitigated by using Cormorant Garamond at 13-15px for body text. Square corners lose visual softness but reinforce the medieval/parchment aesthetic. Light mode may strain eyes in dark rooms during evening game sessions — a future dark theme toggle could address this.

**Key files:** `frontend/src/index.css` (@theme block), `frontend/src/components/common/NavBar.tsx`, `frontend/src/utils/classColours.ts`

## D125: Generic Resource Pool System — M24.5

**Date:** 2026-07-26
**Status:** Accepted (partially superseded by D169; its pools-only sync-back replaced by D166's Write-back in R6)

**Decision:** Implement a unified `resource_pools` JSONB system to replace `feat_resources` and `hit_dice_map` on `player_characters`, and add `resource_pools_current` to `encounter_participants`. Three new reference tables (`resource_pool_definitions`, `class_feature_pools`, `monster_pool_triggers`) are seeded on startup. An expression evaluator handles `maxUsesFormula`, `resetAmount`, and `resetCheck` values for dynamic pool sizing and partial/probabilistic recovery.

**Rationale:** The application currently has 20+ class features, race traits, feats, and monster abilities that need resource tracking (Ki, Sorcery Points, Rage, Channel Divinity, Bardic Inspiration, Wild Shape, etc.) with no unified system. Only `feat_resources` (1 feat: Lucky) and `hit_dice_map` exist as ad-hoc JSONB columns. M12 (monster actions), M17 (class features), and M18 (sorcerer metamagic) all depend on this infrastructure. A unified schema avoids having 5+ separate JSONB columns doing the same thing.

**Details of the design (resolved through domain-modeling session on 2026-07-26):**

1. **Schema:** `ResourcePoolEntry` record with fields: `poolId`, `displayName`, `sourceType`, `sourceName`, `maxUses`, `maxUsesFormula`, `currentUses`, `resetOn`, `resetAmount`, `resetCheck`, `spendActionType`, `icon`, `metadata` (JSONB).

2. **Reference tables (seeded on startup):**
   - `resource_pool_definitions` — pool templates (what pools exist)
   - `class_feature_pools` — which class/subclass gets which pool at which level
   - `monster_pool_triggers` — conditions that auto-create pools for monsters on encounter join

3. **Spell slots remain separate** — their unique upcasting, pact magic, and Flexible Casting mechanics don't fit the pool model. Action economy (action/bonus action/reaction used) stays as dedicated columns for performance. HP, death saves, conditions, concentration stay as columns per the rule: "willing spend = pool, uncontrollable loss = column."

4. **Pool lifecycle:**
   - **Creation:** Post-hoc `syncPoolsForCharacter()` reconciles pools against definitions after creation, level-up, feat application, or feature change. Idempotent — safe to call on any mutation.
   - **Encounter join:** PCs copy `currentUses` as-is from character sheet. Monsters create pools fresh at `maxUses`.
   - **During encounter:** Pools tracked independently on `encounter_participants.resource_pools_current`. Turn advance handles per-turn resets and probabilistic recharge checks.
   - **Sync-back:** On COMPLETED status, `currentUses` written back to character sheet for matching `poolId`s. Monsters are not synced.
   - **Rest:** Short rest resets `resetOn == "shortRest"` only. Long rest resets all pools fully (superset of short rest). `resetAmount` expression supports partial recovery (e.g., `"floor(maxUses/2)"` for hit dice, `"1d6+1"` for magic items). See D127 for corrected semantics.
   - **Level-down:** Pool deleted entirely. Re-sync cleans up orphans.

5. **Expression evaluator** supports: `proficiencyBonus`, `totalLevel`, `{className}Level`, `{ability}Modifier`, basic arithmetic (`+`, `-`, `*`, `/`), `ceil()`, `floor()`, `maxUses` (self-reference for `resetAmount`), and `"1d6>=N"` syntax for probabilistic recharge checks.

6. **Frontend display:** Resources display at source — on feature cards (Features tab), on item rows (Inventory tab), on action buttons (Actions tab). Actions tab allows spend/recover outside encounters. Encounter view shows current/max pills on participant rows.

**Trade-offs:**
- Three new reference tables add schema complexity over a hardcoded approach, but future-proof against homebrew classes and monsters where pool definitions must be data-driven.
- The expression evaluator adds ~50 lines of tested code for a capability that could be deferred to level-up time. Accepted because monsters don't have a level-up pipeline — they need formula evaluation at runtime.
- `maxUses` and `maxUsesFormula` as dual fields (constant vs expression) adds a conditional path in the evaluator but keeps the common case (constant) simple.

**Alternatives considered:**
- **Hardcoded pool definitions in Java** — rejected because homebrew class/monster creator (future roadmap) would require a migration from code to data.
- **Action economy in resource_pools** — rejected for performance reasons (JSONB access overhead on near-every-turn checks).
- **Spell slots in resource_pools** — rejected because upcasting (selecting from higher pools), pact magic (parallel slot tracks), and Flexible Casting (dynamic pool creation) don't fit the simple decrement model.

**Related decisions:** D024 (spell slot copy-on-join pattern), D044 (proficiency collection), D054 (server-side leveling), D083 (short rest hit dice), D099 (feat resources)

**Key files:** `CONTEXT.md`, `obsidian-docs/database-schema.md` (resource pool tables section), `obsidian-docs/decisions-log.md` (this entry), `backend/src/main/resources/db/migration/V8__resource_pool_system.sql` (future)

**Update (2026-11-03, R6 workstream G):** the sync-back in the pool lifecycle above is gone. `ResourcePoolService.syncBackToCharacter`, which copied each participant pool's `currentUses` over the sheet's pool with the same `poolId` at COMPLETED, went in R6 workstream G with `copyToParticipant`, and D166's Write-back replaces them. A player participant copies the sheet's pools through `character/CombatSnapshot` when it is added and again when the encounter starts. When the DM completes the encounter with its results applied, each pool's change in uses since the participant joined is applied to the sheet's pool as it then stands, kept between 0 and its maximum, beside the HP and slot changes, so pools spent in two encounters at once add up. If the DM declines, no pool changes. Removing a player character from an Active encounter applies its changes at once. Monsters' pools are still never written back, and of two participant pools with one ID the first still counts. In `WriteBackIT` an innate Hellish Rebuke cast only in combat came off the sheet's `innate:hellish-rebuke` pool at the end, and nothing came off when the DM declined.

## D126: Frontend Test Infrastructure & Expanded Coverage

**Date:** 2026-07-26
**Status:** Accepted

**Decision:** Installed MSW 2 (Mock Service Worker) for API-level mocking, built a custom test render wrapper (`src/test/test-utils.tsx`) that wraps components in BrowserRouter + AuthProvider, created MSW handlers with complete Campaign/Monster/Character data factories (`makeMockCharacter`, `makeMockParticipant`, `makeMockMonster`), and added 63 new frontend tests across 16 files plus 16 new backend tests across 2 files. Total test count: 253 frontend (up from 110) + 276 backend (up from 260) = 529 tests.

**Rationale:** The frontend had zero component or integration tests before this session. Every UI change was a leap of faith. Adding 47 component tests across the most-used pages (LoginPage, RegisterPage, PlayerDashboard, CampaignManage, BestiaryPage, AsiModal, EncounterBuilder) plus 6 integration tests (auth flow, campaign CRUD, player dashboard flow) provides a safety net for UI development. Backend gains tests for MonsterService search/filter parsing (8 tests) and ReferenceController spell-targeting endpoint (8 tests), which were the two largest untested backend subsystems.

**Test infrastructure details:**
- **MSW handlers** cover all 7 API modules: auth (login/register/refresh), campaigns (CRUD + join), characters (CRUD + level-up + eligible classes), encounters (CRUD + participants + initiatives + start), combat (damage/heal/log), reference (spells/races/classes/filters), monsters (search/filters/pagination). Complete Campaign objects include `dmDisplayName`, `members[]`, and `createdAt` — previously missing, causing crashes in PlayerDashboard and CampaignManagePage.
- **Custom render** (test-utils.tsx) re-exports all `@testing-library/react` functions so tests import from a single source.
- **vitest.setup.ts** auto-starts MSW before all tests, resets handlers and localStorage after each, and auto-closes after all.
- **`@testing-library/user-event`** installed for realistic interaction simulation (userEvent.setup() pattern).

**Component tests (14 files, 47 tests):**
- LoginPage, RegisterPage — form rendering, validation, error states, redirects
- MultiSelect — open/close, filtering, select/deselect, clear-all, ARIA
- LevelUpModal, SubclassModal, ExpertiseModal — loading states, selection, confirmation, error display
- AsiModal — ability score allocation, point tracking, Apply button flow
- FormattedDescription — markup cleaning, text bolding, tag conversion
- StatsTab, ActionsTab, FeaturesTab, InventoryTab — character sheet tab rendering
- BestiaryPage, SpellsPage, ItemsPage — table rendering, pagination, search
- EncounterBuilderPage — encounter list, create form
- PlayerDashboard — campaign/character display, delete flow, join campaign
- CampaignManagePage — campaign list, create form, empty state

**Integration tests (3 files, 11 tests):**
- Auth flow: login → role select, protected/unprotected route guards
- DM campaign flow: page rendering, form toggle
- Player dashboard flow: character display, delete confirmation, join campaign
- Navigation: DM/player role routes, 404 handling

**Backend tests (2 files, 16 tests):**
- MonsterServiceTest — filter parsing (no/single/multi/combined filters, blank name, fuzzy search edge cases, unknown ID)
- ReferenceControllerTargetingTest — targeting endpoint (single-target, self-only, upcast scaling, area spells, 404 cases, repeat effect flag)

**Deliberately skipped:**
- EncounterSessionPage (DM/Player) — WebSocket dependency makes unit testing impractical
- CharacterCreateWizard — 902 lines, 7-step wizard with interdependent API calls; needs dedicated integration test setup
- SpellCastModal — 4-step modal with encounter context
- Snapshot tests — not written, per project policy

**Related decisions:** D102 (M25 initial test suite), D124 (Tourmaline design system)

## D127: Rest Reset Semantics for Resource Pools

**Date:** 2026-07-27
**Status:** Accepted

**Decision:** Short rests reset only pools whose `resetOn` field is `"shortRest"`. Long rests reset ALL pools (both `"shortRest"` and `"longRest"`). A pool with `resetOn: "longRest"` means "only on a long rest" — it does NOT also reset on a short rest. This is the opposite of the D&D 5e interpretation where short reset abilities are a subset: short-rest features recover on both rests, long-rest features recover only on a long rest.

**Rationale:** The original implementation (both in `ResourcePoolService.resetPools()` and the frontend `handleShortRest()`) incorrectly treated short rest as resetting `resetOn === "shortRest" || resetOn === "longRest"`, causing Barbarian Rage, Sorcerer Sorcery Points, Bardic Inspiration, and Lucky Luck Points to all regenerate on a 1-hour short rest. This violated the D&D 5e resting rules.

Three locations were fixed:
- `ResourcePoolService.resetPools()` line 240: `case "shortRest"` now matches only `"shortRest"` pools
- `CharacterSheetPage.tsx` `handleShortRest()`: resourcePools and featResources now reset only `resetOn === "shortRest"`
- `CharacterSheetPage.tsx` `handleLongRest()`: unchanged — always resets everything (already correct)

The existing `resetFeatResources()` in `CharacterService` was already correct (line 1034: `!shortRestOnly || "shortRest".equals(r.resetOn())`) and required no changes.

**Pool reset matrix (post-fix):**

| Pool | resetOn | Short Rest | Long Rest |
|---|---|---|---|
| Monk — Ki Points | shortRest | ✓ | ✓ |
| Cleric — Channel Divinity | shortRest | ✓ | ✓ |
| Paladin — Channel Divinity | shortRest | ✓ | ✓ |
| Druid — Wild Shape | shortRest | ✓ | ✓ |
| Fighter — Second Wind | shortRest | ✓ | ✓ |
| Fighter — Action Surge | shortRest | ✓ | ✓ |
| Sorcerer — Sorcery Points | longRest | ✗ | ✓ |
| Bard — Bardic Inspiration | longRest | ✗ | ✓ |
| Barbarian — Rage | longRest | ✗ | ✓ |
| Lucky feat (3 Luck Points) | longRest | ✗ | ✓ |

**Dice notation for partial resets:** `ExpressionEvaluator` was extended to support dice-roll expressions in `resetAmount` (e.g., `1d6+1`, `2d4`). This enables magic item charge recovery and other partial-regeneration use cases. Dice are rolled after variable substitution and before integer arithmetic. The `resolveDice()` method handles `NdM±C` patterns with ThreadLocalRandom.

**Related decisions:** D125 (resource pool design), D083 (short rest hit dice)

**Key files:** `ResourcePoolService.java:240`, `CharacterSheetPage.tsx` (short rest handlers), `ExpressionEvaluator.java` (resolveDice), `CharacterService.java:1034`

**Update (2026-11-03, R6 workstream D):** rests now run on the server, in `RestService`, and the sheet only sends them (D158). A short rest still resets only pools whose `resetOn` is `"shortRest"`, through `ResourcePoolService.resetPools`, each by its `resetAmount`, or in full when it has none. A long rest refills every pool completely and ignores `resetAmount`, which would otherwise bring the Psi Warrior's and Soulknife's psionic dice pools back as 1 **(choice)**. Lucky's luck points are a `feat:lucky` pool now, so the separate `feat_resources` resets are gone: `CharacterService`'s rest code and its `resetFeatResources()` no longer exist, and the sheet's `handleShortRest()` and `handleLongRest()` send the rest commands and work nothing out. Left for R7: the psionic dice pools still don't regain one die on a short rest.

**Update (2026-11-03, R7 planning):** psionic dice recovery follows the answer to question D2, recorded on D150.

## D128: Known Issue — Missing Second ASI Prompt on Multi-Level Character Creation

**Date:** 2026-07-27
**Status:** Accepted (Known Issue — resolved by D159 in R6 workstream F)

**Issue:** When a character is created at level 8 or above, the character creation wizard only triggers one post-creation ASI flow (e.g., the level 4 ASI). The second ASI at level 8 is silently skipped. The ASI is only offered if the player manually levels the character down and back up from the character sheet, which retriggers the `pendingChoices.asiAvailable` check.

**Root cause:** The `findNextAsiEntry()` method in `CharacterService` returns only the first unrecorded ASI level from `levelHistory`. After the first ASI is applied post-creation, the method is not called again to check for additional pending ASI entries. The post-creation ASI flow (`AsiModal` → `handleAsiComplete` → check for subclass/expertise/spells) has no loop or recurrence to handle multiple pending ASIs.

**Impact:** Characters created at level 8, 12, 16, or 19 miss their second (or third) ASI/feat choice. Fighters at level 6+ miss their bonus ASI. This affects any class with multiple ASI thresholds within the created level range.

**Expected behavior:** After applying the first ASI, the post-creation flow should check `findNextAsiEntry()` again and present another `AsiModal` for each pending ASI entry, looping until all ASI entries in `levelHistory` are recorded.

**Workaround:** Manually level down once (removes the last level) then level up again — this retriggers the ASI check for the missing threshold.

**Related decisions:** D054 (server-side leveling), D058 (ASI history recording)

**Key files:** `CharacterService.java` (`findNextAsiEntry()`, post-creation ASI flow), `CharacterCreateWizard.tsx`, `AsiModal.tsx`

**Update (2026-11-03, R6 workstream F):** fixed by D159's Pending Choices. Creating a character at level N now adds a Pending Choice for every ASI of levels 1 to N, each tied to the level that owes it, so none can be skipped and none lands on the wrong level. In `PendingChoiceServiceTest` a Fighter created at 8th level owes all three of its ASIs, at Fighter 4, 6 and 8, and each is recorded on its own level when made. The creation wizard asks only for the 1st level's choices and goes straight to the sheet, so its ASI loop after creation is gone; the rest wait on the sheet's banner, and the player may make them in any order or leave them for later. `CharacterUpdates.owedAsiLevel`, which replaced `findNextAsiEntry()` in R6 workstream C and still guessed the latest level that owed an ASI, is gone too (D167). Characters created or levelled before F that missed an ASI get it as a Pending Choice from the `backfill-pending-choices` job: locally it found 11 missed ASIs on 7 characters, among them both TestFighters' at Fighter 6 and 8, the Fighter bonus ASIs this issue names (D159). The workaround of levelling down and up again is no longer needed.

## D129: M12 Scope Expansion — All CRs, M12.5 Eliminated

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** M12 covers all 2,357 monsters across all CRs, not just CR 0-10. M12.5 is eliminated as a separate milestone. The monster action resolver engine handles all CRs because the data patterns are identical (same delivery methods, effect types, and action structures at all tiers). Only 4 fields are unique to CR 11+ (`isEyeRays`, `rayCount`, `variableCost`, `autoHit`) and all are tractable edge cases.

**Related:** D033

## D130: Monster Action Panel UI — Expandable Accordion on Participant Row

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** When the DM clicks on a monster participant in the encounter session, the monster's row expands downward (accordion-style) to show its available actions inline in the initiative order. Actions are grouped into sections: Actions (with individual attacks and multiattack combos listed as separate lines), Spells (if the monster has spellcasting), and Legendary Actions (with remaining count and cost per action). Non-automatable actions show their description text and log to the combat log when clicked. The DM adjudicates effects manually using existing damage/heal/condition tools.

**Related:** D033, D104

## D131: Monster Spellcasting — Option B with Editable Overrides

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** Monster spells appear as buttons in the monster action accordion panel (not through the player SpellCastModal). Clicking a spell opens a simplified flow: target selection only (since slot level, save DC, and attack bonus are known from the structured data). The save DC and attack bonus fields are shown as pre-populated editable text boxes so the DM can override them if needed. Monster spells resolve through the existing SpellResolverEngine. Innate spellcasting (per-spell usage limits like "1/day", "3/day") is modelled as ResourcePoolEntry records (e.g., poolId `"innate:dispel-magic"`, maxUses 1, resetOn `"longRest"`). "At will" spells get no pool (unlimited use).

**Related:** D104, D125

## D132: Multiattack — Component Lines with Combo Options

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** The monster action panel shows both individual component attacks AND multiattack combos as separate clickable lines. Example: a monster with "Multiattack: Longsword + Dagger" shows 3 lines: "Longsword", "Dagger", "Multiattack: Longsword + Dagger". This allows the DM to execute the full multiattack against one target, or split attacks across targets by clicking individual components. When a multiattack has options (e.g., "two longsword OR two longbow attacks"), each option appears as a separate multiattack line.

**Related:** D033

## D133: Legendary Actions — Persistent Panel, Usable Any Time

**Date:** 2026-07-29
**Status:** Accepted (partially superseded by D163)

**Decision:** If a monster has legendary actions, its expandable accordion panel always shows a "Legendary Actions" section with the remaining count badge. The DM can click a legendary action at any time during combat (they are not gated by turn). Each legendary action shows its cost. The legendary action pool resets at the start of the monster's turn via the existing ResourcePoolEntry turn-reset mechanism. Legendary actions that reference spellcasting open the monster's spell panel. Non-automatable legendary actions log to combat log with description.

**Related:** D125, D127

## D134: Legendary Resistance — Inline Prompt on Failed Save

**Date:** 2026-07-29
**Status:** Accepted (partially superseded by D163)

**Decision:** When a monster with legendary resistance remaining fails a saving throw (from a spell or save-forcing ability), the system returns the failure result along with a prompt: "Use Legendary Resistance? (X remaining)". This prompt appears inline in the combat result (in the response payload). If the DM clicks "Use Legendary Resistance", the save is converted to a success, the resistance count is decremented (via `ResourcePoolService.spendPool`), and the combat log records both the original failure and the legendary resistance use.

**Related:** D125

## D135: Recharge — ResourcePoolEntry with Auto-Roll on Turn Start

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** Each rechargeable monster action is modelled as a ResourcePoolEntry with maxUses 1, resetOn `"turn"`, and resetCheck matching the recharge range (e.g., `"1d6>=5"` for Recharge 5–6). When `advanceTurn()` is called and it becomes a monster's turn, the existing pool reset logic rolls the recharge check automatically. The result is logged to the combat log: `"[Monster Name]'s [Action Name] recharges! (rolled X)"` on success, `"[Monster Name]'s [Action Name] does not recharge (rolled X)"` on failure. The recharge pool is created per-action (not per-monster) using the action name in the poolId (e.g., `"monster:recharge:fire-breath"`).

**Related:** D125, D127

## D136: Lair Actions — Synthetic Participant at Initiative 20

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** When a lair-capable monster is added to an encounter and the encounter is started, the DM is prompted "Is [Monster Name] fighting in its lair?" If yes, a synthetic "Lair Actions" participant is inserted into the initiative order at initiative 20 (losing ties, i.e., sorted after any creature that also has initiative 20). When the turn reaches this participant, the DM sees the available lair actions. The combat log attributes lair actions to the monster: `"[Monster Name]'s Lair Action activates! [description]"`. The system enforces the 5e rule that the same lair action cannot be used two rounds in a row (the previously used action is greyed out). Lair action data is pre-structured in `monster-action-definitions.json`.

**Related:** D033

## D137: Non-Automatable Actions — Log and Manual Adjudication

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** Actions marked `automatable: false` (or lacking structured effects) display their description text in the accordion panel. When clicked, they log to the combat log as `"[Monster Name] uses [Action Name]"` with the description. The DM then uses existing manual tools (damage, heal, condition buttons) to adjudicate effects. Categories: teleports, shape change, detect/perception, movement actions, and unique abilities all follow this pattern. Monster summoning is deferred to M26 and just logged for now.

**Related:** D033, D036

## D138: Monster Reactions and Bonus Actions — Deferred to M13

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** Although the `monster-action-definitions.json` contains reactions (311 monsters) and bonusActions (192 monsters) data, these are not displayed or usable in M12. M13 (Enhanced Action Economy) will implement reactions and bonus actions for both monsters and players together.

**Related:** D033

## D139: Monster Action Data Seeding — action_templates Column on Monster Entity

**Date:** 2026-07-29
**Status:** Accepted (partially superseded by D169)

**Decision:** A new `action_templates` JSONB column is added to the `monsters` table. The structured action data from `monster-action-definitions.json` is seeded into this column by a `MonsterActionSeeder` (following the same pattern as `SpellSeeder.seedEffectTemplates()` which loads `spell-effect-definitions.json` into the `effect_template` column on spells). The resolver reads action templates from the Monster entity at runtime.

**Related:** D033, D030

## D140: M12 Test Suite — Full Coverage

**Date:** 2026-07-29
**Status:** Accepted — met in R5 (D160, D172)

**Decision:** M12 includes a full test suite: unit tests for MonsterActionResolverEngine (all delivery methods, multiattack, recharge, legendary actions, lair actions, spellcasting), integration tests for new endpoints, and frontend component tests for the accordion action panel. Target: 60-100+ new tests.

**Related:** D102, D126

**Update (2026-11-02, R5 implementation):** met by R5's tests rather than M12's. The combat pipeline's unit tests cover every delivery method, Multiattack, recharge, legendary and lair actions and spellcasting, and integration tests cover each combat endpoint, including the damage and dying golden table (D172). At the end of R5 the backend has 1,228 unit and 126 integration tests and the frontend 518, all passing.

## D141: M12 Frontend Styling — Tourmaline Token Consistency

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** The InlineMonsterActions and MonsterActionPanel components created during M12 used CSS variable references (e.g., `bg-[--surface-page]`, `text-[--text-muted]`) that did not correspond to any tokens in the Tourmaline design system (which uses `--color-page` → `bg-page`, `--color-muted` → `text-muted`). Additionally, default Tailwind colour classes (`bg-amber-50`, `text-green-600`) were used instead of Tourmaline semantic tokens. All class names were replaced with the correct Tourmaline utility classes and semantic colour tokens.

**Why:** The mismatched variable names resolved to `var(--surface-page)` which does not exist — the theme defines `--color-page`. This caused most elements in the inline monster action panel to render without backgrounds, borders, or text colour, appearing flat and invisible against the page.

**How to apply:** All new frontend components must use the Tourmaline utility classes defined in `frontend/src/index.css` (`bg-page`, `text-muted`, `border-rule`, `font-heading`, `font-body`, etc.). CSS variable references via Tailwind arbitrary syntax (`bg-[--...]`) are allowed but must reference actual theme variables. Semantic colours (`bg-buff`, `text-debuff`, `bg-monster`, `text-cls-monk`) must be used instead of raw Tailwind colours (`bg-green-50`, `text-red-600`).

**Related:** D141, [[Tourmaline Design System]]

## D142: Monster Action Execution Flow — Selected-Action Pattern

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** The InlineMonsterActions component was missing an Execute button for automatable actions. The onClick handler only fired for non-automatable actions; for automatable actions (those with a delivery method and effects), clicking the action button did nothing. Added a `selectedAction` state so that clicking an automatable action highlights it, and a separate "Execute" button appears between the target selector and result display to trigger the API call. Multiattack and non-automatable actions call onExecute directly without the intermediate selection step.

**Why:** Without the selected-action pattern, users could select targets but had no way to trigger execution for automatable actions. The flow is now: click action → highlight → select targets → click Execute → see result.

**How to apply:** The three flows are: (1) Multiattack/non-automatable → click calls onExecute directly; (2) Automatable (actions/legendary) → click selects, user picks targets, Execute button calls onExecute; (3) Lair → same as #2 but rendered as full-width description cards instead of compact buttons.

**Related:** D141, [[M12 Monster Actions]]

## D143: Multiattack Resolution — Nested Template Object

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** The `resolveMultiattack` method in `MonsterActionResolverEngine` was checking `fullTemplates.isArray()` but the `action_templates` JSONB column stores a definition **object** with nested `actions`, `legendaryActions`, and `lairActions` arrays — not a flat array. This caused every component action lookup to return null with the error "Component 'X' not found". Fixed by making `resolveMultiattack` detect whether `fullTemplates` is an array or an object, and if an object, search across all nested action arrays (`actions`, `legendaryActions`, `lairActions`, `bonusActions`, `reactions`, `traits`).

**Why:** `action_templates` was seeded as `objectMapper.writeValueAsString(definition)` where definition is the per-monster entry from `monster-action-definitions.json` — an object, not the flat actions array.

**How to apply:** When `MonsterActionResolverEngine.resolveAction` receives `fullTemplates`, it may be either a flat array (legacy behaviour) or a definition object. Multiattack resolution must handle both forms.

**Related:** D139, [[M12 Monster Actions]]

## D144: Lair Action Rendering — Description Cards

**Date:** 2026-07-29
**Status:** Accepted (partially superseded by D169)

**Decision:** Lair actions in the monster action panel are rendered as full-width description cards instead of compact buttons. Each card shows the action name as a `Cinzel` heading with a `Castle` icon, the full description as body text, key stats (save DC, damage dice, damage type) as pills, and a "Manual" badge for non-automatable actions. Additionally, 68 lair action names across ~40 monsters that were auto-generated sentence fragments (e.g., "The aboleth casts", "Pools of water", "Water in the") were renamed to proper descriptive labels (e.g., "Phantasmal Force", "Grasping Tide", "Psychic Conduit"). The data was fixed in `monster-action-definitions.json` and re-seeded to 2,357 monsters.

**Why:** Lair actions are complex, narrative abilities with long descriptions. Sentence-fragment names like "The dragon creates" or "A wall of" are unhelpful as UI labels. The card layout lets DMs read the full description in-panel before deciding to use the action, while proper names make the action list scannable.

**How to apply:** The lair tab uses a dedicated rendering path (`tab === 'lair'` conditional) with full card markup. New lair action definitions must have concise descriptive names rather than sentence fragments. To re-seed action_templates after data changes, null out the column on monsters (`UPDATE monsters SET action_templates = NULL`) and restart the backend to trigger `MonsterActionSeeder`.

**Related:** D141, [[M12 Monster Actions]], [[lair actions]]

## D145: Legendary Resistance — Combat Log Based Undo System

**Date:** 2026-07-29
**Status:** Accepted (partially superseded by D163)

**Decision:** Legendary Resistance (LR) was completely reworked from an inline prompt in the monster action result panel to a combat-log-based system with full undo capability. Key changes:

1. **V10 migration** adds 5 columns to `combat_logs`: `legendary_resistance_eligible`, `lr_target_id`, `lr_damage_dealt`, `lr_conditions_applied`, `lr_resolved`.

2. **Per-target logging**: `monsterAction()` now logs one combat log entry per target instead of a single summary. Failed saves against LR-eligible targets store the damage amount and condition names in the log entry.

3. **Undo on LR use**: `useLegendaryResistance()` now accepts a `combatLogId` rather than `participantId`. It finds the combat log entry, reverts the damage (heals the target), removes the conditions that were applied, decrements the **target's** LR pool, and marks the entry as resolved.

4. **UI relocation**: The "Use Legendary Resistance" button appears in the `CombatLogPanel` on eligible log entries instead of inline in the monster action result panel. The `confirm-failed-save` endpoint and `Accept` button were removed (effects apply immediately; LR is the undo mechanism).

5. **LogAction refactoring**: An overloaded `logAction` method accepts optional LR metadata fields. The original `logAction` delegates to the overloaded version with null LR fields. This ensures all combat log entries go through the same proven code path.

**Why:** The old implementation had three bugs: (a) the frontend sent the attacker's participant ID instead of the target's when calling `useLegendaryResistance`; (b) LR only decremented the pool without undoing the damage or conditions that were already applied; (c) the LR prompt appeared in the wrong location (action result panel instead of combat log).

**How to apply:** When a save fails against a monster with LR available, the combat log entry carries `legendaryResistanceEligible: true` with the target's ID, damage dealt, and conditions applied. The DM clicks "Use Legendary Resistance" on the log entry → damage is healed, conditions removed, LR pool decremented, entry marked resolved. Button disappears after click.

**Related:** D127, D128, [[M12 Monster Actions]], [[Legendary Resistance]]

## D146: Recharge Pool Configuration — resetOn Must Be Null for Probabilistic Reset

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** Recharge action pools (e.g., Fire Breath `1d6>=5`) were incorrectly configured with `resetOn: "turn"` in `createForMonster`, which caused `resetPools` to unconditionally reset them to maxUses every turn — bypassing the probabilistic recharge check entirely. The recharge check only runs when `shouldReset` is false, so pools intended to use probabilistic recharge must have `resetOn: null`. Fixed by setting `resetOn` to `null` for recharge pools during monster creation.

Additionally, `RechargeLogEntry` was extended with a `checkExpression` field and the combat log message format improved from `"Monster's Fire Breath recharges! (rolled 5)"` to `"Adult Red Dragon rolls 1d6 (5). Adult Red Dragon recharges Fire Breath!"`.

**Why:** The `resetPools` logic evaluates `shouldReset` based on `resetOn` matching the trigger. Pools with `resetOn: "turn"` always get fully reset to maxUses, skipping the `resetCheck` evaluation. Pools with `resetOn: null` (no automatic reset) fall into the `!shouldReset` branch where the recharge check is evaluated.

**How to apply:** Monster pools that use probabilistic recharge (`resetCheck` like `"1d6>=5"`) must set `resetOn` to `null`. Pools that reset fully every turn (like legendary actions) use `resetOn: "turn"`. The `RechargeLogEntry` constructor now requires 5 args: `(poolId, displayName, success, rollValue, checkExpression)`.

**Related:** D127, D145, [[M12 Monster Actions]], [[Resource Pools]]

## D147: Legendary Action Depletion — Dual-Layer Enforcement

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** Legendary action cost enforcement requires both frontend and backend layers. The frontend disables action buttons and the Execute button when `legendaryRemaining < actionCost` (greyed out, shows ✗, tooltip explains deficit). The backend `spendPool()` method now checks `currentUses >= amount` and returns `false` when insufficient — previously it only checked pool existence and clamped to 0, always returning `true`. The `monsterAction()` pre-validation uses this to throw `IllegalStateException` with a clear message.

**Why:** The frontend disabled state alone is unreliable (React disabled buttons can still receive clicks in some edge cases, and stale state after spending). The backend check is essential but was ineffective because `spendPool` returned `true` as long as the pool existed, regardless of remaining uses.

**How to apply:** All callers of `spendPool` already check the return value and throw on `false`. The return value now correctly reflects sufficiency: `found && hadEnough`. Monster creation in `createForMonster` sets `resetOn: "turn"` for legendary actions (full reset each turn) and `resetOn: null` for recharge actions (probabilistic reset).

**Related:** D145, D146, [[M12 Monster Actions]], [[Resource Pools]]

## D148: Combat Log Description Format — Action Name Required

**Date:** 2026-07-29
**Status:** Accepted

**Decision:** Combat log entries for monster actions must include the action name in the description. Changed `buildTargetDescription` from the format `"Adult Red Dragon vs Target: HIT (14+9=23) for 18 slashing"` to `"Adult Red Dragon uses Bite vs Target: HIT (14+9=23) for 18 slashing damage"`. The method signature changed to accept `String actionName` as a parameter.

**Why:** Without the action name, combat log entries were ambiguous ("Adult Red Dragon vs asdasd: FAILED_SAVE (2) — frightened") — the DM couldn't tell which action caused the effect.

**How to apply:** `buildTargetDescription(EncounterParticipant monster, String actionName, MonsterActionResolverEngine.TargetResult tr)` — the action name is extracted from `actionTemplate.path("name").asText("Unknown Action")` in `monsterAction()`.

**Related:** D145, [[M12 Monster Actions]], [[Combat Log]]

## D149: Spring Boot Error Messages — include-message Always

**Date:** 2026-07-29
**Status:** Superseded by D155

**Decision:** Added `server.error.include-message: always` and `server.error.include-binding-errors: always` to `application.yml`. The frontend error handler was updated to try `err.response?.data?.message`, `err.response?.data?.error`, raw response body, and finally `err.message` (Axios generic text) in that order.

**Why:** Spring Boot's default `include-message: never` stripped exception messages from error responses, causing the frontend to show unhelpful generic text like "Request failed with status code 409" instead of the actual error like "Not enough legendary actions remaining (need 2)".

**How to apply:** All backend exception messages now reach the frontend. New error handlers should follow the same fallback chain pattern.

**Related:** D147, [[Error Handling]]

## D150: Refactor Phase Before M13 — Phases R1–R8

**Date:** 2026-11-01
**Status:** Accepted — in progress (R1–R6 done; R7–R8 to come)

**Decision:** Feature milestones (M13 onward) are paused until the refactor phase is complete. The phase runs in eight steps. Each step leaves the app deployable, with CI green and the project docs updated.

| Phase | Scope | Decisions |
|---|---|---|
| R1 Safety and hygiene | Remove the startup character deletion. Fix spell slots and recompute stored maximums. Stop feat reseeding. Fix the rules-of-hooks errors. Move the lair and monster-spell UI out of the unused MonsterActionPanel, then delete it. Add CI and put the type check back in the production build. Turn on HTTPS. Add a health check, automated backups and non-root containers. Align Node versions. Harden production config. Archive stale docs and remove stray files. | D152, D153, D154, D165, D169 |
| R2 Test harness | Testcontainers integration tier, golden rules tables, strict MSW, foreign-key indexes in Flyway. | D170, D172 |
| R3 Access and auth | Access policy and error codes, shipped with the frontend refresh change. WebSocket subscription checks and DM/Player Views. Rotating refresh cookie. Module skeleton and boundary test. | D151, D155, D156, D157 |
| R4 Frontend foundations | Generated API types, TanStack Query, shared UI primitives, route code splitting. | D171 |
| R5 Combat pipeline | Single resolve/apply path, typed encounter state, combat rules fixes, server-calculated attacks, monster action fixes, encounter lifecycle. | D160–D166, D168 |
| R6 Character authority | Command endpoints, typed character state, Pending Choices, server-side rests, derived progression, encounter Write-back. | D158, D159, D166, D167, D168 |
| R7 Content | Game data files, hash-based idempotent seeding, pool level tables and expression parser. Remove deprecated columns and unused 5etools data. | D169 |
| R8 Consolidation | Shared encounter components, single spell picker, wizard reducer, Spring Boot upgrade, remaining doc updates. | D154, D171 |

Some migrations rewrite existing character data (D153, D167, D169). Each one first produces a dry-run report showing every character before and after, for maintainer approval. A database backup is taken before it runs in production.

**Rationale:** The 2026-11-01 codebase review found:

- a startup routine that can delete every character
- game rules calculated in the browser and accepted unchecked by the server
- authorization gaps on characters, encounters and WebSocket topics
- about twenty combat and progression rules errors against the 2014 PHB
- the logic that applies results to targets copied four times in CombatService
- no tests against a real database

M13 (action economy) changes the same combat code. Building it first would multiply the cost of every fix.

**Trade-offs:** No new features until R8 finishes. The trigger for M6, which requires M13, moves further out.

**Alternatives considered:**
- **Fix bugs alongside M13** — rejected: M13 rewrites the turn and action code that the combat pipeline (D160) restructures.
- **One big rewrite** — rejected: phases keep the live app working between steps and keep each change reviewable.

**Related:** D029 (M6 deferral), D138 (M13 scope), D151–D172

**Update (2026-11-01, R1 planning):** nothing is hosted (the Hetzner server was closed), so R1 no longer turns on HTTPS or installs automated backups. Those move to a Hosting step taken when a new host is chosen (D154). R1 moves only monster spellcasting out of `MonsterActionPanel` (D165).

**Update (2026-11-02, R3 planning):** D155's removal of catch-and-continue blocks moves to the phases that rewrite that code: the combat services in R5, the character services and `ResourcePoolService` in R6, and the seeders in R7.

**Update (2026-11-02, R5 complete):** R4 and R5 were completed on 2026-11-02. R5 ran workstreams E and F at the same time, F in a separate worktree that was merged afterwards. Since R5 removed the main reason for pausing M13, starting M13 before R6–R8 was considered; the order was kept: R6, R7, R8, then M13. Hosting stays deferred until the app is to be played online (D154).

**Update (2026-11-03, R6 workstreams A and B):** R6's plan is drafted, kept outside the repository, and awaits approval. Workstreams A and B, which change no character behaviour, were built on 2026-11-03. C and D change levelling, multiclass prerequisites and rests, so they wait for approval; E, F and G wait for answers to the plan's questions.

**Update (2026-11-03, R6 planning, approved):** the R6 plan was approved on 2026-11-03 with every default answer. Workstreams C to G follow A and B in order, one local commit each. The answers to the plan's questions for E, F and G are recorded under D158, D159 and D166.

**Update (2026-11-03, R6 complete):** R6 was completed on 2026-11-03 in seven workstreams, each one local commit followed by its docs: A, the character rules core and golden tables (ff8092f); B, typed character state (5ebd41f); C, derived progression (1bafaa4); D, the intent endpoints and server-side rests (5f79550); E, DM Adjustments (634d13b); F, Pending Choices (690211f); and G, Write-back (d999dbf). The docs are in 030994a (the approved plan), 9146e8b (A and B), 98e958d (C), 29a09db (D), 13c175f (E), 213d821 (F) and the commit that records G. Migrations V19 to V24 and the `recompute-characters` and `backfill-pending-choices` jobs changed the local data. Every migration and job was backed up and dry-run on the local database first, and from D on a rehearsal on a copy of the data came before that. R6 ends with 1,676 backend unit tests, 178 integration tests, and 586 frontend tests in 66 files. **Left for R7:** dropping the seven unmapped character columns (D168); the seeders' catch-and-continue blocks (D155); the psionic dice pools regaining one die on a short rest (D127); a repeatable flag in the feat data, and perhaps condition immunities in combat (D158, D161); and Tough, Hill Dwarf and Draconic Bloodline found in the data rather than by name, with the spell tables moved to data files (D167). **Left for R8:** server-side feat prerequisites (D158, D159). **Later:** invocations, pact boons, Metamagic and maneuvers; Magical Secrets from any class's list, swap-only levels, the Eldritch Knight's and Arcane Trickster's school limits, tool Expertise and multiclass skill picks; and recording on their levels the subclasses chosen before F and the creation spells above the 1st level (D159, D167). Participants still copy the stored spellcasting ability (D164). From G: whether to allow removal while Paused, a combat log entry for applied or declined results, `spendPool` and `recoverPool` taking lists to break the cycle through `resourcepool` (D151), `results_applied` on the DM's page, the revive 403's wording, and writing back conditions and Hit Dice (D166). Stats copied when a participant joins don't pick up adjustments made during the fight, by design.

**Update (2026-11-03, R7 planning):** R7 runs as workstreams A to H, one local commit each: A baselines, B loaders and content hashes, C the seeders' catches, D pools, E character rules from data, F monster data and condition immunities, G dropping the deprecated columns and the unused 5etools data, H small items. Each one is rehearsed on a restored copy on port 5433 before the local database changes. The plan was approved on 2026-11-04 with every recommended answer: rows missing from the data are kept and logged; the loaders live in a new `content` module; psionic dice come back through a one-use recovery pool that resets on a short rest, spent to regain one die (to be checked against Tasha's text); Rage and Wild Shape show "Unlimited" at 20th level; only Lay on Hands and Divine Sense are added among the missing pools; the class tables move to a data file; the roughly 590 text-only monster saves are reported, not applied; combat honours condition immunities; the template columns become typed records; the seven deprecated columns are archived and dropped and the unused data deleted from the repo; and the server works out the creation spellcasting ability.

## D151: Modular Monolith — Microservices Rejected

**Date:** 2026-11-01
**Status:** Accepted — implemented (skeleton in R3); each later phase moves its area into its module

**Decision:** The backend stays a single deployable Spring Boot application, organised into modules with enforced boundaries:

- `identity` — users and authentication
- `campaign`
- `content` — reference game data and its loading
- `character`
- `encounter`
- `rules` — plain Java with no Spring or JPA: dice, expressions, ability scores, slot tables, effect resolution, damage maths

Each module exposes a small public API (services and DTOs) and keeps everything else package-private. Modules affect each other through application events. For example, `EncounterCompleted` triggers the character module's Write-back (D166). A boundary test (Spring Modulith `ApplicationModules.verify()`, or ArchUnit) fails the build when one module reaches into another's internals. R3 creates the module skeleton and the test, and each later phase moves its area into its module.

**Rationale:**
1. **Core operations must be atomic across areas.** One spell cast reads content, spends a slot, damages several participants, may break concentration and writes the log. Ending an encounter writes back to characters. Split into services, each of these becomes a distributed transaction with compensating steps.
2. **There is no scaling need.** A table is 4–7 users (D004 assumed about 9). If more than one backend instance is ever needed, the in-memory STOMP broker is replaced by an external broker relay inside the monolith.
3. **There is one maintainer.** Microservices multiply what has to be run and debugged — separate databases, service-to-service auth, versioned internal APIs, tracing, more containers and VPS cost — for no benefit here.
4. **The real problem is weak internal boundaries.** Classes do too much, packages use each other's repositories (e.g. `CharacterService` uses `EncounterParticipantRepository`), and rules are split between browser and server. Services would freeze those boundaries into network contracts. Enforced modules fix them and keep refactoring cheap.

**Trade-offs:** Boundaries are enforced by a test rather than by process isolation. The whole application deploys together.

**Alternatives considered:**
- **Microservices per area** — rejected for the reasons above.
- **Keep the current package-by-layer monolith** — rejected: nothing enforces boundaries, which is how the oversized classes and cross-package coupling arose.

**Revisit when:** one area needs to scale or release independently. The likeliest candidate is `content`: it is read-only, cacheable and shares no transactions with the rest, so it could become a separate service or a static bundle.

**Related:** D001, D004, D021, D166

**Update (2026-11-02, R3 planning):** the boundary test uses ArchUnit. Only a module may use its own `internal` package, and `rules` may use only the JDK and `shared` (the domain exceptions). Spring Modulith's `verify()` would fail on today's `character` ↔ `encounter` dependency cycle. R3 moves `auth`, `user` and the security classes into `identity`. It also moves the static calculators `SpellSlotCalculator`, `SpellSlotTables`, `ProficiencyBonus`, `DiceRoller` and `ExpressionEvaluator` into `rules`. `User` stays public until other modules hold user IDs instead.

**Update (2026-11-03, R6 workstreams A and B):** workstream B moved `SpellSlot` and `KnownSpell` from `encounter.state`, and `ResourcePoolEntry`, `FeatResourceEntry` and `LevelHistoryEntry` from `character.dto`, into `character.state`, which both modules use. `CharacterUpdates` replaced `CharacterJsonHelper`. `character` still imports encounter classes until workstream D.

**Update (2026-11-03, R6 planning, approved):** planned for D and G: `character/` stops importing `encounter`. It asks whether a character is in a live encounter through a `LiveEncounters` interface that `encounter` implements **(choice)**. Write-back stays an event: its record, `CombatResults`, lives in `character`, `encounter` publishes it, and a listener in `character` applies it inside the completing transaction, so dependencies run only from encounter to character **(choice)**.

**Update (2026-11-03, R6 workstream C):** C adds `character.internal`, which only `character` uses: its `CharacterStats` works out the Derived Stats (D167). Other modules go through `CharacterService`. The `recompute-characters` job in `maintenance` reads the public `DerivedStats` record through `derivedStats` and `recalculate`, and `encounter`'s `SpellcastingService` reads a class's spell numbers through `classSpellcasting` (D164), a new dependency that runs from encounter to character, as the plan above intends. `character` still imports encounter classes until workstream D.

**Update (2026-11-03, R6 workstream D):** `character` no longer imports `encounter`. It asks whether a character is in a Live Encounter through its own `LiveEncounters` interface, which `encounter/ParticipantLiveEncounters` implements from the encounters each character takes part in, so the dependency runs from encounter to character, as the plan above intends. `includes` answers for an Active or Paused encounter, during which rests, level-up, level-down and apply-choices answer 409 (D158, D166). `includesUnfinished` counts a Preparing encounter too, so deleting a character in one is still refused, as before **(choice)**. `CharacterService` no longer uses `EncounterParticipantRepository`, the example in the rationale above. `ModuleBoundaryTest` gains a rule that no class in `character` may depend on anything in `encounter`. `resourcepool` still uses both modules, and both use it, so `character` and `encounter` still meet in a cycle through it. Write-back's `CombatResults` event comes with G.

**Update (2026-11-03, R6 workstream E):** E adds `character/DmAdjustments`, a public helper that works only from the sheet: a character's ability scores, AC and damage defences with its active DM Adjustments on top, and every feat it has taken (D158). `encounter` reads it in `CreatureStats`, `CharacterWeapons` and `EncounterService`, so combat sees the adjusted values, and the dependency still runs from encounter to character, as the plan above intends. `resourcepool`'s `ResourcePoolService` reads it too, for granted feats' pools and the ability modifiers in pool formulas. `character` still imports nothing from `encounter`: a DM Adjustment is allowed during a Live Encounter, so it asks no question about encounters (D166). `DmAdjustment` joins the records in `character.state` (D168), and `campaign`'s `AccessPolicy` gains `requireCharacterDm`, which takes IDs like its other checks (D155).

**Update (2026-11-03, R6 workstream G):** Write-back is built as planned (D166). `CombatResults` is a record in `character`, which `encounter`'s new `EncounterResults` builds from each player participant and publishes. `character`'s `WriteBackService` handles it in an `@EventListener` that must join the publishing transaction (`Propagation.MANDATORY`), so a failed write-back undoes the completion too. The End dialog's preview calls `WriteBackService.preview` directly, which also runs from encounter to character. A participant now copies its sheet through `character/CombatSnapshot`, so `EncounterService` no longer reads `DmAdjustments` or uses `ResourcePoolService`: `copySpellSlots` and `copySpellsKnown` went from `EncounterService`, and `copyToParticipant` and `syncBackToCharacter` from `ResourcePoolService` (D125). `CombatTotals` joins the records in `character.state` (D168). The cycle through `resourcepool` remains, because the combat services in `encounter.internal` still use `ResourcePoolService`, and its `spendPool` and `recoverPool` take an `EncounterParticipant`, so `ModuleBoundaryTest` isn't tightened. Making those two take a list of pools would break the cycle; that is left for later.

**Update (2026-11-03, R7 planning):** R7 creates the `content` module: the loaders move from `seeder` into `content.internal`, and `content` gives the loaded class tables (`ClassTables`) to `character` **(choice)**. The `reference` entities stay where they are.

## D152: Remove Startup Character Cleanup

**Date:** 2026-11-01
**Status:** Accepted — implemented (R1)

**Decision:** Delete `DataSeeder.cleanupLegacyCharacters()`. Make `raceId` required in `CharacterCreateRequest` (`@NotNull`), and add a Flyway migration making `player_characters.race_id` `NOT NULL`. If homebrew races are ever needed, they are added as race reference rows rather than left null.

**Rationale:** D041 assumed "the app has no real users yet — all characters are development test data". That no longer holds now the app is deployed for real play (D119–D122).

The method still runs on every boot. If any character has a null `race_id`, it runs `DELETE FROM player_characters` and also deletes every player encounter participant. `raceId` is optional in the API, so one request without it would wipe every character at the next restart.

The one-off migration the method existed for is finished. No production row can have a null race, because the cleanup would already have deleted everything.

**Trade-offs:** None in practice; the wizard always sends a race.

**Alternatives considered:**
- **Keep the method behind a one-time flag** — rejected: destructive code whose job is done has no reason to stay.
- **Only make `raceId` required** — rejected: it leaves a mass delete at startup, one schema change away from firing.

**Supersedes:** D041

**Key files:** `DataSeeder.java:111-130`, `CharacterCreateRequest.java`

**Related:** D039, D042 (soft delete)

## D153: Spell Slots — A Single Spellcasting Class Uses Its Own Table

**Date:** 2026-11-01
**Status:** Accepted — implemented (R1)

**Decision:**
- `SpellSlotCalculator` uses the combined multiclass table only when a character has two or more Spellcasting Classes.
- With exactly one, it uses that class's own progression. Paladin, Ranger, Artificer, Eldritch Knight and Arcane Trickster each have their own table.
- Pact Magic is not a Spellcasting Class for this purpose, so a Paladin 5 / Warlock 2 uses the Paladin table plus pact slots.
- `CharacterClassSeeder` and `SpellSlotCalculator` read the progression tables from one shared source, which moves to a data file in R7 (D169).
- A golden test checks every class at levels 1–20 against the PHB tables, plus multiclass cases (e.g. Paladin 2 / Sorcerer 3 = caster level 4).
- A one-off migration recomputes stored slot maximums for every character. It keeps the used count and caps it at the new maximum. A dry-run report comes first (D150).

**Rationale:** PHB p.164 applies the multiclass spellcasting rules only once a character has the Spellcasting feature from more than one class. The current code applies multiclass rounding (level ÷ 2 or ÷ 3, rounded down) to everyone. As a result:

- half casters are wrong at every odd level from 3. A Paladin 3 gets two 1st-level slots instead of three, and a Paladin 5 gets no 2nd-level slots.
- third casters are wrong at every level from 4 that isn't divisible by 3.

The frontend's `maxSpellLevel` is already correct, so affected players can currently learn spells they have no slots for.

**Trade-offs:** Rewrites stored data for existing half and third casters. Characters already in an encounter keep their copied slots until re-added.

**Alternatives considered:**
- **A formula instead of tables** (half casters use the full-caster row at ceil(level ÷ 2) from level 2; third casters at ceil(level ÷ 3) from level 3) — gives the same results, but duplicates what the per-class tables already hold. Rejected in favour of the tables.

**Partially supersedes:** D040 (single-class handling and where the tables live; D040's reason for writing the tables ourselves still stands)

**Amends:** D049

**Key files:** `SpellSlotCalculator.java:56-94`, `CharacterClassSeeder.java` (spell_slot_progression)

**Related:** D060, D062, [[Spellcasting Class]]

## D154: Production and Delivery Baseline — HTTPS, CI, Operations

**Date:** 2026-11-01
**Status:** Accepted — implemented (R1 parts); HTTPS and backups in the Hosting step; Spring Boot upgrade in R8

**Decision:**
- **HTTPS:** a domain name (bought, or a free dynamic-DNS subdomain) points at the server. Caddy provisions certificates automatically, HTTP redirects to HTTPS, and HSTS is enabled.
- **CI:** a GitHub Actions workflow runs on every push and pull request. It runs the backend unit and integration tests, the frontend type check (`tsc -b`), oxlint, Vitest and the Vite build.
- **Type check in the production build:** the production frontend Dockerfile goes back to running `npm run build`, which includes the type check. The 265 existing type errors are fixed first:
  - the test tsconfig gains the jest-dom and Vitest types
  - mocks use `null` where the API returns null
  - the five production errors go away with the MonsterActionPanel removal (D165)
- **Operations:**
  - Spring Boot Actuator exposes only `/actuator/health` (public). `deploy.sh` and the Docker health checks use it.
  - A nightly automated `pg_dump`, with retention, is copied off the server.
  - Containers run as a non-root user.
  - Node 24 LTS is pinned for Docker and local development (`.nvmrc`).
  - Swagger UI and the API docs are turned off in the production profile.
  - Spring Boot is upgraded in R8, once the integration tests (D172) exist. Its support status should be confirmed first.

**Rationale:**
- D122 accepted plain HTTP as temporary "until a custom domain is purchased", and said it must be resolved before any public deployment. Passwords and tokens currently travel unencrypted, and D156's secure cookies need HTTPS.
- Without CI, nothing runs the 571 tests automatically.
- Skipping `tsc` in the Docker build (commit 3d4373a) is how a call to an endpoint that doesn't exist reached production.
- The deploy health check calls `GET /api/auth/login`, an endpoint that only accepts POST, so it can never succeed.
- Backups are manual only.

**Trade-offs:** A domain costs a small yearly fee; a dynamic-DNS subdomain is free. CI fails until the existing type errors are fixed. Players need the new address.

**Alternatives considered:**
- **A certificate for the bare IP address** — rejected for now: it depends on newer short-lived IP certificates and on Caddy support, both of which would need checking. A hostname is simpler and survives the planned move to DigitalOcean (D123).
- **Keep skipping `tsc` in Docker for build speed** — rejected: type errors reach production.

**Supersedes:** D122

**Related:** D119, D120, D123, D156, D172

**Update (2026-11-01, R1 planning):** the Hetzner server and account have been closed, and nothing is hosted. Turning on HTTPS, nightly backups with an off-server copy, and checking health on a live deploy move to a Hosting step taken when a new host is chosen. R1 keeps CI, the type-checked production build, the health endpoint itself, non-root containers, Node 24 and the production config changes.

## D155: Access Policy and Error Handling

**Date:** 2026-11-01
**Status:** Accepted — implemented (R3; combat code in R5; character and resource pool code in R6); the seeders' catch-and-continue removal in R7

**Decision:**
- **One access policy.** An `AccessPolicy` service provides `requireCampaignDm`, `requireCampaignMember`, `requireCharacterOwnerOrDm` and `requireControls(participant)`. Every command and query that touches campaign data calls it first, replacing the ownership checks copied into each service method. It also covers places that have no check today:
  - reading the combat log
  - looking up an encounter by session code
  - adding a player participant (the character must belong to a campaign member)
  - attaching a character to a campaign (the owner must be a member)
  - using legendary resistance (the log entry must belong to the encounter)
- **Error codes.** Domain exceptions map to HTTP codes:
  - `NotFound` → 404
  - `Forbidden` → 403
  - `InvalidState` / `Conflict` → 409
  - `Validation` → 400
  - `OptimisticLockingFailureException` → 409
  - request parameters of the wrong type (e.g. `?level=abc`) → 400
- **401 for missing or expired tokens.** Spring Security gets an `HttpStatusEntryPoint(UNAUTHORIZED)`, and the frontend interceptor refreshes only on 401. Both changes ship in the same release.
- **No catch-and-continue inside transactions.** Exceptions propagate and roll the transaction back. Catches remain only at boundaries: each seeder file, with a summary, and production startup fails if content cannot load.
- **Messages.** Domain exceptions carry a message meant for users, and it is always returned. Unexpected exceptions return a generic message in production. `server.error.include-message: always` moves to the dev profile.

**Rationale:**
- Authorization is checked separately in each method, and several reads and writes have no check at all.
- "Not found" and "not yours" both throw `IllegalArgumentException`, which returns 400.
- D034 says optimistic-lock conflicts return 409, but no handler exists, so they come back as 500.
- D017 refreshes the token on 403 because Spring answers an expired token with 403 when no entry point is configured. Once permission failures return 403, that would trigger pointless refreshes.
- D076 replaced silent catches with logging, but 127 broad catches still carry on after a failure, so half-applied changes get saved. For example, a failed pool parse writes `[]` over a character's pools.
- D149's goal, meaningful error text in the UI, is kept through domain messages without exposing internal exception text in production.

**Trade-offs:** Errors that are swallowed today will show up as failed requests — existing bugs becoming visible. Frontend code that checks for 400 on ownership errors must handle 403 instead.

**Alternatives considered:**
- **`@PreAuthorize` annotations everywhere** — not ruled out. An explicit policy call is simpler to read and test first, and can be wrapped in annotations later.
- **Keep refreshing on 403** — rejected: it treats "not allowed" as "session expired".

**Supersedes:** D076, D149, and (together with D156) D017

**Amends:** D028 (its permission methods move into the policy; the player permissions themselves are unchanged), D034 (adds the missing 409 handler), D092

**Key files:** `GlobalExceptionHandler.java`, `SecurityConfig.java`, `frontend/src/api/axiosConfig.ts:33`

**Related:** D156, D160, [[Error Handling]]

**Update (2026-11-02, R3 planning):** `AccessPolicy` lives in `campaign` and takes IDs, so `campaign` depends on no other module (D151). It adds `requireCharacterOwner`: only the owner can change a character until D158. Reading a character becomes owner-or-DM, and the campaign's character list becomes DM-only. One `ConflictException` covers InvalidState and Conflict. Acting out of turn returns 403. A missing resource named in the URL returns 404; a missing ID in the request body returns 400. Wrong login details still return 400, and a bad refresh cookie returns 401. Error bodies keep the `{"error": ...}` shape.

**Update (2026-11-02, R5 implementation):** the combat code's catch-and-continue blocks went with `CombatService` and both resolver engines (workstream D). The catches left in `encounter/` convert to domain exceptions (an unknown participant type answers 400; stored JSON that can't be read is logged and answers 409) or log a broadcast that failed after commit (D160).

**Update (2026-11-03, R6 planning, approved):** planned for C and D: the catch-and-continue blocks in `character/` and `resourcepool/` go. A pool sync that fails stops the command instead of writing `[]` over the pools, and `MulticlassValidator` answers 409 when class data can't be read instead of treating it as no requirements.

**Update (2026-11-03, R6 workstream C):** C removes the catch-and-continue blocks in `character/` and `resourcepool/`. Of their 17 catches, 7 remain, and each turns reference data that can't be read into a 409. `MulticlassValidator` answers 409 when a class's prerequisites can't be read, instead of treating them as none. Class and subclass features, a subclass's always-prepared spells, multiclassing proficiencies, a race's speed, a class's spellcasting ability and a feat's data that can't be read answer 409 too, where the old code skipped them, used a default or answered 500. `ResourcePoolService.reconciledPools` replaces `syncPoolsForCharacter`, whose catch-all logged a failure and let the command save the rest: a pool whose maximum can't be worked out now stops the command, and nothing is saved. `syncBackToCharacter` loses its catch too, and `applyChoices` no longer turns every error into an `IllegalStateException`, which answered 500. The seeders' catches go in R7.

**Update (2026-11-03, R6 workstream E):** `AccessPolicy` gains `requireCharacterDm(campaignId, userId)`, the check behind DM Adjustments (D158). Only the DM of the character's campaign passes. Anyone else gets 403 ("Only the DM of this character's campaign can adjust it"), the character's owner and strangers alike. A character in no campaign has no DM, so everyone gets 403 with its own message ("This character isn't in a campaign, so it has no DM to adjust it"). The check asks only whether the caller is the campaign's DM, so a DM who also owns the character may adjust it. `requireCharacterOwner` is unchanged: the owner changes a character only through the player commands, and the DM only through DM Adjustments. `CharacterCommands.beginAsDm` reads only the character's and campaign's IDs before the check, so a caller who may not adjust a character never locks its row, and a character that doesn't exist answers 404. An unknown adjustment answers 404. Removing one twice, or granting a feat the character already has, answers 409. A field that belongs to another kind, or an amount of 0 or beyond ±100, answers 400.

**Update (2026-11-03, R7 planning):** the seeders' 48 inner catches go. A row a loader can't read either fails that loader, which rolls back and keeps its old hash, or matches a named skip rule that the summary counts. The only catch left is one per loader in the orchestrator: development logs the failure and skips the loaders that depend on it, and with `content.fail-on-error=true` (the prod profile) startup fails **(choice)**.

## D156: Session Security — In-Memory Access Token, Rotating Refresh Cookie

**Date:** 2026-11-01
**Status:** Accepted — implemented (R3); deploying it needs the Hosting step's HTTPS

**Decision:**
- **Access tokens** last 15 minutes and are held in the frontend's memory, not localStorage.
- **Refresh tokens:**
  - sent as an httpOnly, Secure, SameSite=Strict cookie scoped to `/api/auth`
  - stored hashed in a new `refresh_tokens` table
  - replaced on every use and revoked on logout
  - reusing a token that has already been replaced revokes that whole token family
- **Token type:** every JWT carries a type claim, and refresh tokens are rejected when used as bearer tokens.
- **Secret:** `application.yml` has no default JWT secret. A dev-only value lives in `application-dev.yml`, and startup fails if `JWT_SECRET` is missing or shorter than 256 bits.
- **Getting a token:** on page load the app calls `/api/auth/refresh` to get an access token. The STOMP client fetches a fresh access token before every connect and reconnect (`beforeConnect`).

**Rationale:**
- D003 accepted localStorage tokens and no revocation for "a small trusted user group". The project now aims at online play beyond one trusted table, and D122 required hardening before any public deployment.
- Today access and refresh tokens differ only in expiry, so the 7-day refresh token works as a bearer token. That undermines D003's "1-hour access token window".
- The fallback secret in `application.yml` means any deployment without `JWT_SECRET` signs tokens with a key committed to the repo.
- WebSocket reconnects reuse the token read on the first connect.

**Trade-offs:** Everyone is logged out once when this is deployed. It requires HTTPS. It adds a table and a cookie flow, and logout becomes a server call.

**Alternatives considered:**
- **Keep localStorage with a shorter expiry** — rejected: a cross-site scripting attack could still steal the refresh token.
- **Server-side sessions** — rejected: loses D003's stateless WebSocket authentication.

**Supersedes:** D003 (token storage and the no-revocation trade-off; JWT access tokens remain), and (together with D155) D017

**Related:** D015, D154, D155

**Update (2026-11-02, R3 planning):** refresh tokens are random values rather than JWTs, and only their SHA-256 hashes are stored (V13). Access tokens carry `type: access`; no other token is accepted as a bearer token or at STOMP CONNECT. Each login starts a token family, and each refresh issues a new 7-day token. The cookie's Secure flag is off only in the dev profile. Browser tabs take turns refreshing (Web Locks API), so two tabs refreshing at once aren't mistaken for reuse. `beforeConnect` always refreshes.

## D157: Encounter Visibility — Subscription Checks and DM/Player Views

**Date:** 2026-11-01
**Status:** Accepted — implemented (R3, R5)

**Decision:**
- **Subscription checks.** The WebSocket channel interceptor checks SUBSCRIBE frames as well as CONNECT. Only campaign members may subscribe to an encounter, and only its DM may subscribe to the DM destination.
- **Two views.** One broadcaster publishes two full-state views (D004):
  - the DM View, with everything
  - the Player View, which:
    - removes hidden participants
    - strips monster notes, spell lists and action templates
    - shows monster HP as a Health Status, unless the campaign setting "show exact monster HP" is on
    - redacts combat log entries about hidden participants
- **Hiding participants.** A DM endpoint hides and reveals participants, so `isVisibleToPlayers` finally works.
- **Membership for lookups.** Looking up an encounter by session code and reading its combat log require membership (D155).
- **Allowed origins.** Allowed origins for `/ws` come from configuration instead of `*`.

**Rationale:** Today, any logged-in user who knows an encounter ID can subscribe and receive monster HP, notes and spell lists. "Hidden" participants are filtered only in the player's browser, and no endpoint can set the flag. One unfiltered payload can't serve both roles.

**Trade-offs:** The player session page changes from exact monster HP (`p.hpCurrent/p.hpMax`) to a status label unless the DM opts in. Players see a shorter initiative list while monsters are hidden. Each broadcast is serialised twice.

**Alternatives considered:**
- **Per-user destinations (`/user/queue/...`)** — workable, but rejected for now: every player receives the same Player View, so one shared topic is simpler.
- **Filter in the browser** — rejected: the data still reaches the client.

**Amends:** D004 (full-state broadcast kept, once per view), D015 (adds SUBSCRIBE checks)

**Related:** D155, [[DM View]], [[Player View]], [[Health Status]]

**Update (2026-11-02, R3 planning):** the Player View is published on `/topic/encounter/{id}/state` and the DM View on `/topic/encounter/{id}/dm-state`. REST responses use the same split. The Player View also removes monster spell slots, spellcasting numbers and resource pools, and action templates become DM-only. Players' combat logs leave out entries whose actor or target is hidden, and drop the Legendary Resistance fields. Only monsters can be hidden. A subscription must name an exact destination, and clients may SEND only to `/app/**`. `/ws` takes its allowed origins from `CORS_ALLOWED_ORIGINS`. **Correction:** players saw exact monster HP in the spell target pickers, not in the initiative list.

**Update (2026-11-02, R5 planning):** the Player View numbers `sortOrder` and `currentTurnIndex` over the participants players can see, and shows no current turn while a hidden participant acts **(choice)**. Spell log entries give each target its own entry, which the existing filter removes, so a multi-target spell no longer names hidden targets. A player who names a hidden participant as a target gets 400, as for an unknown one.

**Update (2026-11-02, R5 implementation):** spell and monster-action log entries are written per target, so the Player View filter removes hidden targets' entries, and a player naming a hidden participant as a target gets 400 (workstream D). The Player View numbers `sortOrder` and `currentTurnIndex` over the turns players can see and shows no current turn while a hidden participant acts. The Lair's turn appears to players only while at least one lair monster is visible to them, because its log entry names one **(choice)** (workstream E, D163).

## D158: Server-Authoritative Character Commands and DM Adjustments

**Date:** 2026-11-01
**Status:** Accepted — implemented (R6 workstreams C–G)

**Decision:**
- **Intent endpoints.** `PUT /characters/{id}` is retired. Characters change only through intent endpoints:
  - details (name, notes, appearance, personality, inventory)
  - HP (damage, healing, temporary HP)
  - spell slot spend and restore
  - resource pool spend and recover
  - short rest and long rest
  - add feat
  - the existing level-up, level-down and apply-choices
- **Derived Stats.** The server calculates every Derived Stat and never accepts one from the client:
  - proficiency bonus
  - HP maximum
  - spell slot maximums
  - spell save DC and spell attack bonus for each class
  - prepared-spell limits

  Character creation sends the player's choices only.
- **DM Adjustments.** A DM-only, logged endpoint records named modifiers (stat, amount, reason) that are added on top of Derived Stats. This is how magic items, boons and homebrew are represented until they are modelled properly.
- **Rests** (reset rules per D127):
  - A short rest spends the hit dice the player chooses for each class. The server rolls them, or the player enters their own rolls. Each die heals its roll plus CON modifier. It also resets short-rest pools and pact slots.
  - A long rest restores HP to maximum, regains spent hit dice up to half the character's total (minimum one), restores all spell slots, resets short- and long-rest pools, and clears temporary HP.
  - Resting while the character is in an Active or Paused encounter returns 409.

**Rationale:**
- The catch-all PUT accepts any level, HP, slots, pools or level history, so every rule the server enforces can be bypassed. The browser currently works out rests and slot use itself and writes the results back.
- The backend's own rest methods have no endpoint and disagree with the browser: hit dice per class versus the total, and temporary HP not cleared.
- Short-rest healing uses a fixed value instead of a roll.
- Multiclass long rests regain half the dice of each class, minimum one each, which restores too many.

**Trade-offs:** Every character sheet edit is rewired; all tabs currently save through `saveField` → `characterApi.update`. Players can no longer edit stats by hand. Anything they used to edit for magic items or homebrew goes through DM Adjustments, which needs a small UI.

**Alternatives considered:**
- **Keep PUT with stricter validation (the D082 approach)** — rejected: validation can limit a value's range, but it can't tell whether a level, slot count or HP total is legitimate.
- **Let players apply their own adjustments** — rejected for now; it could be added later as a campaign setting.

**Supersedes:** D082

**Partially supersedes:**
- D049 — derived values are always calculated by the server, never accepted from the client
- D062 — the server calculates spell stats for each class
- D083 — rest logic moves to the server, and hit dice are rolled

**Key files:** `CharacterService.java` (`updateCharacter`, `shortRest`, `longRest`), `CharacterSheetPage.tsx:127-194`

**Related:** D054, D127, D159, D166, D167, [[Derived Stat]], [[DM Adjustment]]

**Update (2026-11-03, R6 planning, approved):** planned for D and E. **Commands (D):** intent endpoints under `/api/characters/{id}` replace the PUT: details, campaign, inventory, HP (damage takes temp HP first and stops at 0, healing stops at the maximum, and temp HP keeps the higher amount), spending and restoring slots and pools, short and long rests, prepared spells, the Wizard's spellbook, and an interim known-spells update, checked against the class list, level and known limit, until F **(choice)**. Player commands stay owner-only. Each command locks the character row (pessimistic write lock, 5-second wait, 409 when it runs out), with `@Version` as a backstop **(choice)**. Derived Stats keep stored copies written by one derivation, initiative and speed are derived, and AC keeps its creation value plus DM Adjustments, because armour isn't modelled **(choice)**. Race and background can't change after creation, and classes change only by levelling **(choice)**. **Rests:** a short rest spends the chosen hit dice, each healing its roll (entered by the player from 1 to the die size, or rolled by the server) plus CON, never below 0. A long rest needs at least 1 HP (PHB p.186), restores HP, clears temp HP, recovers half the total hit dice (at least one, largest first) and resets every slot and pool **(choice)**. Rests, level-up, level-down and applying choices answer 409 while the character is in an Active or Paused encounter. **DM Adjustments (E), answered with the defaults:** the DM manages them from the campaign page's character list. Kinds are STAT (AC, HP maximum, speed, initiative, spell save DC, spell attack bonus and the six ability scores; saving throws and skills aren't adjustable), DEFENCE (a resistance, immunity or vulnerability, D161) and FEAT (a grant through `FeatEffectResolver`, reversed on removal). Players can't set their own AC, so every AC change goes through the DM. Players see each adjustment and its reason. A feat taken outside an ASI is a DM grant, and the sheet's Add Feat Spells dialog goes.

**Update (2026-11-03, R6 workstream D):** D replaces `PUT /api/characters/{id}`, `updateCharacter` and `CharacterUpdateRequest` with the intent endpoints, each under `/api/characters/{id}` and each returning the character: `PATCH details`, `PUT campaign`, `PUT inventory`, `POST hp`, `POST spell-slots/{slot}/spend` and `/restore`, `POST pools/spend` and `/recover`, `POST short-rest`, `POST long-rest`, `PUT spells/prepared`, `POST` and `DELETE spellbook` (DELETE takes a JSON body) and `PUT spells/known`. **Commands:** the new `character/CharacterCommands` is the start and end of every player command, levelling, applied choices and deletion included. It checks the owner's ID before locking, so another user never takes the lock. It sets a 5-second lock wait with `set_config('lock_timeout','5000ms',true)`, the same as `SET LOCAL`, because Hibernate's SQL for the lock is `FOR NO KEY UPDATE` and ignores the lock timeout hint on PostgreSQL. It then locks the character row, refuses rests and levelling while the character is in a Live Encounter (D166), and at the end derives the Derived Stats through `CharacterStats` (D167) and saves. `@Version` on `PlayerCharacter`, from V21, is the backstop: a stale write answers 409. In `CharacterCommandsIT` a command waiting on a held lock got 409 after about 5.8 seconds. The commands live in `CharacterSheetService` (details, campaign, inventory, HP, slots and pools), `RestService`, and `CharacterSpellService` (prepared spells, the Wizard's spellbook and the interim known spells). **HP:** `{kind: DAMAGE|HEALING|TEMPORARY, amount ≥ 1}`. Damage comes off temp HP first and stops at 0, healing stops at the maximum, and temp HP keeps the higher amount; the rules are in the new `rules/character/CurrentHitPoints`. **Short rest:** `{hitDice: [{className, roll?}]}`, one entry per die. The server rolls a die sent without a roll. A roll outside 1 to the die size, more dice than remain, or a die of a class the character has no Hit Dice of answers 400, and every die is checked before any is rolled. Each die heals its roll plus CON, never below 0. Short-rest pools and Pact Magic slots recover, and a rest with no dice does only that. **Long rest:** it needs at least 1 HP (PHB p.186), or answers 409. It restores HP, clears temp HP and recovers half the total Hit Dice, at least one, largest first. The browser gave each class back half its own dice, at least one each, so a multiclass character can now regain a different number: a Fighter 1 / Wizard 1 with every die spent got 2 back and now gets 1, and a Fighter 3 / Wizard 3 got 2 and now gets 3. Every slot comes back, and every pool refills completely, ignoring `resetAmount`, which would otherwise bring the psionic dice pools back as 1 **(choice)**. Short rests still use `resetAmount` (D127). **Pools:** the pool ID goes in the body, and an unknown pool answers 400; spending more uses than are left, or recovering past the maximum, answers 409. **Slots:** the slot goes in the path. An unknown slot answers 404, and none left or none spent answers 409. **Interim known spells:** optional `cantrips` (any spellcasting class) and `spells` (known casters only). A spell is on a class's list if the spells table lists the class or "Class (Subclass)", and Eldritch Knights and Arcane Tricksters use the wizard's list. The server refuses spells over a class's limit, above its highest spell level or off its list. F's Pending Choices replace this command (D159). **Feat pools:** `feat_resources` is unmapped and joins R7's drop list; the column stays (D168). Feats with uses become sheet pools named `feat:<kebab-case name>`, with source type FEAT, beside the innate pools (D165). They take no action to spend, and reset as the feat says. They are built from the feats the level history records, that is, feats taken at an ASI, so feats picked in the creation wizard get no pool until F. No local character had `feat_resources` data. **Live Encounters (D151):** rests, level-up, level-down and apply-choices answer 409 during an Active or Paused encounter. Deleting is still refused while the character is in a Preparing encounter too, as before, through a second question, `includesUnfinished` **(choice)**. The response gains `inLiveEncounter`, and the sheet disables those buttons with a note. **Item links (D164):** inventory entries' optional `itemId` links them to items. A link to an item that doesn't exist answers 400, at creation as well. Weapon matching in combat (`CharacterWeapons`) tries `itemId` before the name. **Frontend:** each command has its own mutation, which writes the returned character into the cache. Damage, Heal and + Temp HP buttons sit beside the HP bar. The feat-resources strip is gone, and Lucky shows as a `feat:lucky` pool with − and + buttons. The short-rest dialog has a roll box for each die. InventoryTab adds, removes and links items and changes quantities. Slot cards have a button to get a spent slot back, and a new Manage Cantrips dialog works for every class with cantrips. AddFeatModal and "remove feat spells" are gone; E brings DM-granted feats. **Edits players lose:** the sheet's own adding of feat spells by hand and removing of a feat's spells, until E. The PUT also accepted fields the sheet never offered, and no command takes them: ability scores; proficiencies and expertise; resistances, immunities and condition immunities; race, background, `abilityScoreMethod` and `racialAbilityBonuses`; HP, temp HP, slot uses, remaining Hit Dice or pool uses set to any value; and `spellsKnown` rewritten wholesale, so race and feat spells can't be edited any more. It had ignored AC since C. A Wizard of 3rd level or higher can no longer add cantrips through Add to Spellbook; Manage Cantrips replaces that. **Rehearsal on a copy of the local data:** V21's preview examined 276 rows and found 13 characters to change (`version` 0), `recompute-characters` found no changes, and all 13 characters and 15 encounters loaded. On asdasd, which is in 3 Active encounters, the short rest, long rest, level-up and level-down each got 409, while an HP command got 200. On a new Fighter 3 / Wizard 2, a roll of 11 on a d10 got 400, a short rest with entered and server rolls healed 5 → 40 (capped), and the long rest returned the Hit Dice largest first (Fighter 2/3, Wizard 0/2) and restored every slot. **Locally:** V21 was applied at 10:26 UTC after two backups and a dry run, and all 13 characters have version 0. `recompute-characters` then found no changes, apart from the known TestPeace cantrip warning, and all 13 characters and 15 encounters load through the API. **Tests:** new `CharacterCommandsIT`, `CharacterCommandsTest`, `CharacterSheetServiceTest`, `RestServiceTest`, `CharacterSpellServiceTest` and `CurrentHitPointsTest`. `HttpSecurityIT`, `WeaponAttackIT` and `CharacterJsonMappingIT` no longer use the PUT. `PreviewMigrationJobIT`'s V19 and V20 previews were replaced by V21's preview, because previews load characters with today's mappings, which need `version`; the V19 and V20 apply tests stay. The backend now has 1,577 unit and 149 integration tests, and the frontend 543 tests in 61 files. **Left for later:** E brings DM Adjustments and DM-granted feats, whose effects must also feed the feat pools, speed and initiative. F replaces `spells/known`, and creation feats get their pools. In G, End still writes pool uses back without locking character rows, so in a rare race with a character command, End answers 409 through the version check (D166). R7 drops `feat_resources`, and psionic dice still don't regain one die on a short rest.

**Update (2026-11-03, R6 workstream E):** E builds DM Adjustments. `POST /api/characters/{id}/adjustments` adds one and `DELETE /api/characters/{id}/adjustments/{adjustmentId}` removes one, each returning the character. **Access:** they are for the DM of the character's campaign only, through the new `AccessPolicy.requireCharacterDm` (D155) and `CharacterCommands.beginAsDm`. A character that doesn't exist answers 404. Anyone who isn't the campaign's DM gets 403, the owner and strangers alike, and a character in no campaign gets 403 with its own message. A DM who also owns the character may adjust it; locally, the DM of asdasd's campaign also owns asdasd. `beginAsDm` checks the IDs before locking, then takes the same five-second lock wait and row lock as the player commands, and checks the campaign again under the lock, in case the owner moved the character meanwhile. Both commands end by deriving the stats again through `CharacterStats`, like D's commands. Adjustments are allowed during Live Encounters (D166). Every use is logged at INFO with the DM's, the adjustment's and the character's IDs, and the kind, target and amount; the reason isn't logged. **Storage:** V22 adds `player_characters.dm_adjustments`, JSONB, not null, default `'[]'`, typed as `character.state.DmAdjustment` and kept oldest first (D168). Each entry holds its ID and kind (STAT, DEFENCE or FEAT), its target and amount, a required reason of at most 200 characters, who added it and when, and who removed it and when. A DEFENCE also holds its defence, and a FEAT the feat's ID and the effects granting it applied. A removed entry stays as the record, and only active ones count. An unknown adjustment answers 404, and removing one twice answers 409. A field that belongs to another kind answers 400, and so does a blank reason. **STAT:** the target is AC, HP maximum, speed, initiative, spell save DC, spell attack bonus, or one of the six ability scores (`ARMOUR_CLASS`, `HP_MAX`, `SPEED`, `INITIATIVE`, `SPELL_SAVE_DC`, `SPELL_ATTACK_BONUS`, `STR` to `CHA`). Saving throws and skills aren't adjustable; they follow from the scores. The amount must be non-zero and within ±100, otherwise 400. The stored ability scores and `armour_class` keep the values without adjustments, and the adjusted values are worked out when read **(choice)**. So AC is the creation value plus the AC adjustments. Adjusted scores are clamped to 1–30, so they may pass the Ability Score Maximum. Speed stops at 0 and the HP maximum at 1, and AC isn't clamped. Ability adjustments come first in the derivation, so the modifiers, HP from CON, initiative from DEX, the spell numbers, multiclass prerequisites, short-rest healing (CON) and pool formulas all use the adjusted scores. The Ability Score Maximum check for ASIs uses the scores without adjustments. The other STAT adjustments are added on top: to the HP maximum, initiative and speed, and the spell ones to every class's numbers and to the main ones. HP maximum adjustments follow C's rule (D167): a higher maximum raises current HP by as much, and a lower one caps it. **DEFENCE:** a damage resistance, immunity or vulnerability to one of the 13 PHB damage types, which `DamageRules` now lists (D161). Condition immunities aren't covered, because combat doesn't read them for anyone **(choice)**. Combat (`CreatureStats`) reads a player character's defences live, so they apply at once in Live Encounters. A defence the character already has counts once. **FEAT:** it grants a feat through `FeatEffectResolver`, with the same choices as an ASI feat, and records the applied effects, so removal reverses them exactly. The DM's picker may grant a feat the character doesn't qualify for, showing the unmet prerequisite. Granting a feat the character already has answers 409, unless it's repeatable. A feat counts as repeatable when its text says it can be taken more than once, which is only Elemental Adept, since the data has no flag **(choice)**. The feat picker follows the same rule, on the ASI path too. Reversing a feat removes only its latest entry, so a feat taken twice keeps the other; this affects levelling down too. "Already has the feat" means a feature added by a feat, a level that took it by name, or an active grant. Feats chosen at creation are recorded as race or background features, so they aren't detected yet (F). `CharacterStats` reads feats from the level history and from active FEAT adjustments, for feat pools, speed, initiative and HP per level, so a granted Lucky has its `feat:lucky` pool. **New classes:** `character/DmAdjustments`, a public helper that works only from the sheet: adjusted scores, AC, damage defences and feats taken (D151). `DmAdjustmentService` holds the two commands. The new DTOs are `DmAdjustmentRequest`, the request, and `BaseAbilityScores`, which the response carries. **Response:** the ability scores, `armourClass`, `damageResistances` and `damageImmunities` include the active adjustments, and the two defence lists are always lists now. The response gains `damageVulnerabilities`, `baseAbilityScores` (the scores without adjustments) and `dmAdjustments`, active and removed. **In combat:** defences apply at once. So do ability-score adjustments, because saves and weapon attacks read the scores live, and spell DC and attack adjustments for class spells, which `SpellcastingService` reads from the sheet (D164). AC, the HP maximum, initiative and the main spell numbers used by race and feat spells are copied when a participant is added, so they reach a participant only if it is added afterwards. `CreatureStats`, `CharacterWeapons` and `EncounterService` read the adjusted values. **Frontend:** for DMs, each character card on the campaign page has an "Adjustments" button with the active count, such as "Adjustments (2)". It opens `DmAdjustmentsModal`, which lists the active adjustments and removes one after a confirmation, adds a Stat, Defence or Feat, the last through the existing feat picker, and keeps removed ones in a collapsed history. For players, the Stats tab has a "DM Adjustments" card listing each adjustment and its reason, and the resistances card is now "Damage Defences", with immunities and vulnerabilities. The ASI dialog raises the score without adjustments and shows the adjusted one beside it. The new mutations are `useAddAdjustment` and `useRemoveAdjustment`. **Rehearsal on a copy of the local data:** V22's preview examined 276 rows and found 13 characters to change, each gaining `[]`, `recompute-characters` found no changes, and everything loaded. As the DM, "+1 AC (Ring of Protection)" took asdasd's AC from 13 to 14. With fire resistance added, 10 fire damage in a live encounter did 5, logged "(fire, halved by resistance)", and after its removal the same damage did 10. Granting Lucky gave a `feat:lucky` pool at 3/3 and the feature, and a second grant got 409. Removing it took both away, and a second removal got 409. The owner got 403 on a character in no campaign, and on one in a campaign they don't run. **Locally:** V22 was applied at 11:22 UTC after two backups and a dry run, and all 13 characters have an empty list. `recompute-characters` then found no changes, and all 13 characters and 15 encounters load through the API. **Tests:** new `DmAdjustmentIT` (7 tests) and `DmAdjustmentsTest`, and DM Adjustment cases in `CharacterStatsTest`, `CharacterMapperTest`, `FeatEffectResolverTest`, `CreatureStatsTest`, `ResourcePoolServiceTest` and `AccessPolicyTest`. The frontend has new tests for `DmAdjustmentsModal` and `utils/dmAdjustments`, and new cases for AsiModal, the campaign page, the Stats tab and repeatable feats. `PreviewMigrationJobIT`'s V21 preview made way for V22's preview and apply tests, and `CharacterJsonMappingIT` round-trips the new column. The backend now has 1,600 unit and 158 integration tests, and the frontend 562 tests in 63 files. **Left for later:** F detects creation feats and makes the ASI path refuse a feat the character already has, which only the picker does now. In G, stats copied when a participant joins don't pick up adjustments made during a Live Encounter (D166). R7 adds a repeatable flag to the feat data, and possibly condition immunities in combat.

**Update (2026-11-03, R6 workstream F):** F finishes what D and E left to it, through Pending Choices (D159). **Known spells:** the interim `PUT /api/characters/{id}/spells/known`, `CharacterSpellService.setKnown` and `KnownSpellsRequest` are gone, and so are the frontend's `useSetKnownSpells` and the sheet's Manage Known and Manage Cantrips dialogs. A class learns its cantrips and spells through its levels' CANTRIPS and SPELLS Pending Choices instead. The new `learnCantrips` and `learnSpells` keep D's checks: each spell on the class's list, of a level the class can learn, within its limit, and not one it knows already. The new `forget` takes them away again when the level is removed. Copying spells into a Wizard's spellbook through `POST spellbook` stays as it was. **Apply-choices** moves from `CharacterService` to the new `PendingChoiceService`, still through `CharacterCommands.beginOutsideEncounters`: it stays the owner's alone and answers 409 during a Live Encounter (D166). The campaign's DM sees how many choices each character owes but gets 403 making one, as planned. **Feats at creation:** a race's or background's feat, such as a Variant Human's, is now a FEAT choice made on the sheet, applied through `FeatEffectResolver` and recorded on the 1st level. Its speed, initiative, HP per level and pool count as an ASI's feat's do. Before F such a feat was only a feature named after the race or background, so its effects never applied. `DmAdjustments.featsTaken` includes these feats, and `hasFeat` moved there from `DmAdjustmentService`, so E's check now detects a feat taken at creation: a DM grant of one answers 409. Apply-choices now refuses a feat the character already has, with 400, unless it is repeatable (E's rule); a DM grant still answers 409. **Level-up** answers `{character, newFeatures}`. Its `pendingChoices` flags are gone, because the new level's choices are among the character's Pending Choices. **Rehearsal on a copy of the local data:** a Variant Human's Alert took initiative from +2 to +7, and an ASI taken as Lucky got its `feat:lucky` pool, which levelling down removed with the feat. **Left for later:** the server still doesn't check feat prerequisites (R8).

**Update (2026-11-03, R6 workstream G):** G closes what D and E left to it, through Write-back (D166). **End and the character rows:** ending an encounter, or removing a player character from one under way, now locks each character's row after the encounter's, in ID order, with the same five-second wait, through the new `CharacterCommands.beginWriteBack`. So D's race, where End wrote pool uses back without the lock and could answer 409 through the version check, is gone. A character loaded earlier in the transaction and changed since still answers 409, rather than being written back stale. Write-back saves each sheet through `CharacterCommands.save`, which derives the stats as `finish` does. **Adjustments and encounters:** a participant copies its sheet again when the encounter starts, so an adjustment to AC, the HP maximum, initiative or the main spell numbers made while the encounter is Preparing reaches it. One made once the encounter has started still doesn't, by design **(choice)**. **Dead characters:** the new `CharacterCommands.requireAlive` answers 409 for a dead character's short and long rests, damage, healing and temporary hit points, level-up and level-down, such as "Aria is dead, so can't take a short rest until the DM revives them". Its other commands go on, such as details, inventory, slots and pools, and so do its choices, DM Adjustments and deletion **(choice)**. `CharacterStats` keeps a dead character at 0 HP whatever its maximum does. **Revival:** `POST /api/characters/{id}/revive` is the DM's second command on a character, beside DM Adjustments. It starts with `beginAsDm`, so only the DM of the character's campaign may revive it, its 403 reuses the DM Adjustments wording ("Only the DM of this character's campaign can adjust it"), and a character in no campaign can't be revived (D155). It sets HP to 1 and clears temp HP and the dead flag, and reviving a character who isn't dead answers 409. It isn't refused during an encounter. Every character response gains `dead`. **Left for later:** the revive 403's wording, which still says "adjust".

**Update (2026-11-03, R6 click-through):** a browser click-through of the plan's checks, on a copy of the local data, passed all nine. Its small fixes: the short rest now answers with a `ShortRestResponse`, `{character, rolls}`, as level-up answers `{character, newFeatures}`, so the sheet can show each die's roll, whether the player entered it or the server rolled it, and what it healed **(choice)**. The Stats tab shows the Hit Dice each class has left, the DM's character card shows every class (`formatClasses`), the encounter builder shows current and maximum HP, and the End dialog's preview is headed "None of this is written back" when the DM unticks Apply (D166). The race and ability score steps show a negative ability bonus as "-2" rather than "+-2". Left for later: Paladins have no Lay on Hands or Divine Sense pools (R7's data, D169), and creation doesn't add a background's starting equipment and gold (R8's wizard).

**Update (2026-11-03, R6 review fixes):** a review before pushing R6 found that a player could make DM Adjustments to their own character, or revive it, by creating a campaign, which makes them its DM, moving the character in, and moving it back. `CharacterCommands.beginAsDm` now refuses the character's owner with `403` even as the DM of its campaign, as `AccessPolicy.requireCharacterDm` already stated, so a DM can't adjust or revive a character they own themselves. The creation request no longer takes `features`, which let a request add a feat such as Tough without taking it; feats come only through Pending Choices and DM Adjustments. An unknown spell in a feat's spell choice and an unknown subclass in a multiclass creation answer `400` instead of being stored without a name or dropped.

**Update (2026-11-03, R7 planning):** the feat data carries `repeatable`, true only for Elemental Adept, which replaces the description regex. Condition immunities follow the answer to question F2, recorded on D150.

## D159: Pending Choices

**Date:** 2026-11-01
**Status:** Accepted — implemented (R6 workstream F); invocations, pact boons, Metamagic and maneuvers later

**Decision:**
- **Recording.** When a level grants a choice — ability score increase or feat, subclass, expertise, fighting style, cantrips or spells — the server stores a Pending Choice. It holds an ID, its type, the class and class level it came from, and any limits on the options.
- **Applying.** `apply-choices` must name a Pending Choice ID. Applying it records the result in that level's history entry and removes it. A request with no matching Pending Choice returns 409.
- **Creation above level 1.** Creating a character at level N generates every Pending Choice for levels 1 to N. The wizard and the character sheet resolve them in order until none are left.

**Rationale:** `apply-choices` never checks that an ability score increase is actually owed, so unlimited increases are possible. `findNextAsiEntry()` (D058) guesses the target history entry by scanning, which can attach a choice to the wrong level and then break level-down. D128's missing second increase for characters created at level 8 or above comes from the same flow.

**Trade-offs:** The level-up pop-up chain (`pendingLevelClass` in `CharacterSheetPage`) is rebuilt around the server's list. The wizard must handle several ability score increases in a row.

**Alternatives considered:**
- **Loop `findNextAsiEntry()` (the fix D128 expected)** — rejected: it still guesses, and still allows unlimited increases.

**Supersedes:** D058

**Partially supersedes:** D057 (post-creation ability score flow)

**Amends:** D054 (`pendingChoices` becomes a stored list)

**Resolves:** D128

**Related:** D052, D158, D167, [[Pending Choice]]

**Update (2026-11-03, R6 planning, approved):** planned for F, answered with the defaults. Pending Choices are a JSONB list on the character **(choice)**. The types are ASI or feat, subclass, expertise, fighting style, cantrips, spells, and race or background feats at creation, which replace the wizard's feat step so their effects apply. Invocations, pact boons, Metamagic and maneuvers come later. Levelling up adds that level's choices, and creating a character at level N adds the choices for levels 1 to N. `apply-choices` resolves one choice (409 if it doesn't exist, 400 for an invalid answer) and records the result in that level's history; levelling down removes that level's unresolved choices. Players see a banner on the sheet with a button per choice. They may skip a choice and leave it pending, and may level up or join encounters with choices pending. The DM sees a count of pending choices on the campaign page but can't resolve or waive them **(choice)**. Characters created above level 1 make their choices on the sheet after creation. A known caster's spell choice offers the one-spell swap. The backfill job adds only missed ASIs, subclasses and expertise.

**Update (2026-11-03, R6 workstream F):** F builds Pending Choices. **Storage:** V23 adds `player_characters.pending_choices`, JSONB, not null, default `'[]'`, typed as `character.state.PendingChoice` and kept oldest level first (D168). An entry holds its ID, type, source, character level, class, class level, count, options, `maxSpellLevel` and `swap`, and a field its type doesn't use is left out. The types are `SUBCLASS`, `FIGHTING_STYLE`, `ASI` (an ASI or a feat in its place), `FEAT` (a race's or background's feat at creation), `EXPERTISE`, `CANTRIPS` and `SPELLS`, in the order a level's are made: a subclass first, because it can bring more. The sources are `LEVEL_UP`, `CREATION`, `RACE` and `BACKGROUND`. **What a level owes:** the new `character/PendingChoices` works it out from `ClassProgression` (D167): the subclass at the class's subclass level, a Fighting Style, an ASI, Expertise, the cantrips by which Cantrips Known rise, and, for a class that knows a set number of spells, the spells by which Spells Known rise. A class that prepares its spells has none to choose. A Wizard's spell choice goes into its spellbook: 6 at Wizard 1 and 2 at each level after (PHB p.114), worked out from the class name **(choice)**. Each spell choice is capped at the highest spell level the class could learn at the choice's own class level, so a choice made late still fits its level and levelling down stays consistent **(choice)**. The swap is offered only when Spells Known rise, so a level where the number stays the same, such as Sorcerer 12, gets no choice **(choice)**. The Fighting Styles are hard-coded per class in `ClassProgression.fightingStyles`, the PHB's and then Tasha's, because the optional features table lost which class each style belongs to **(choice)**. The Champion's Additional Fighting Style at Fighter 10 chooses from the fighter's. The chosen style becomes a feature named "Fighting Style: X", and a style can't be taken twice. `ClassProgression` also gains `spellbookSpells`. **Adding them:** `CharacterService` adds the new level's choices at level-up, and undoes the last level's at level-down, through `PendingChoiceService`. Creating a character at level N adds the choices of levels 1 to N, less what the request chose itself, a FEAT choice for each feat the race gives, and one for the background's feat from the feats it lists, which `FeatRepository.findByNameIgnoreCase` matches by name. A subclass in the request is recorded on its class's subclass level, and one given below that level answers 400 **(choice)**. Expertise in the request is recorded on the levels that give it, oldest level first **(choice)**. Cantrips and spells in the request count against their class's choices oldest first, but aren't recorded per level **(choice)**. **Making them:** the new `character/PendingChoiceService` takes `applyChoices` over from `CharacterService`. `ApplyChoicesRequest` now needs a `choiceId`, gives only the field its type takes (`asi`, `feat`, `subclassId`, `expertiseSkills`, `fightingStyle`, or `spells` with an optional `swap` of `{replace, with}`), and loses `classId`. The answer is recorded on the history entry of the level that owes the choice, and the choice leaves the list. No matching choice answers 409, as does one already made, and so does apply-choices during a Live Encounter (D166) **(choice)**. A field for another choice type answers 400, and so does a duplicate feat, unless the feat is repeatable (E's rule, D158) **(choice)**. Spells, cantrips and Expertise must match the choice's count exactly (Expertise takes every skill left when fewer are), and Expertise needs a skill the character is proficient in; otherwise they answer 400 **(choice)**. A subclass also adds its features to each class level the character already has from its subclass level on, recorded on those levels, and brings the choices it gives them, such as an Eldritch Knight's cantrips and spells. **Records:** `LevelChoices` gains `subclass` (the new `SubclassChosen`), `fightingStyle`, `cantrips`, `spells` (the new `SpellsLearned`: the spells learned, and a swap's replaced spell and replacement) and `feats` (the new `FeatTaken`, a creation feat with the effects it applied). `LevelHistoryEntry` gains `withFeaturesGained`, for the features a Fighting Style or a later subclass adds. `CharacterUpdates.owedAsiLevel` and `owedExpertiseLevel` are gone, replaced by helpers that write to a named level, so C's "latest level that owes it" rule is now the choice's own level (D167). **Levelling down** removes the level's pending choices and reverses those made for it, by what the level records: its subclass, Fighting Style, cantrips and spells, giving back a spell a swap replaced, as well as its ASI and Expertise. **Backfill:** the new `backfill-pending-choices` job (`maintenance/BackfillPendingChoicesJob`) adds the ASIs, subclasses and Expertise that existing characters' levels missed, through `PendingChoiceService.missedChoices` and `addPending`. Expertise the sheet has that no level records counts against the levels that give it, oldest first. Fighting Styles and known spells aren't backfilled, as planned. Backfilled choices are marked as level-up choices **(choice)**. **Frontend:** the sheet has a banner, `PendingChoicesBanner`, with a button per pending choice. Levelling up opens that level's dialogs one after another through `ChoiceDialog`, with the choices a subclass brings next. Skip leaves a choice on the banner, and closing a dialog stops the run. The buttons are disabled during a Live Encounter. `FightingStyleModal` is new, and `ManageSpellsModal` moved out of SpellsTab with a choice mode; they join AsiModal (which takes FEAT choices too), SubclassModal and ExpertiseModal. The creation wizard asks only for the 1st level's choices and goes straight to the sheet, so its ASI loop after creation is gone, which fixes D128. Race and background feats, such as a Variant Human's, are chosen on the sheet, and their effects now apply, feat pools included; before, they were silently dropped (D158). Manage Known and Manage Cantrips are gone, and copying spells into a spellbook stays. An ASI can't retake a feat the character already has, unless it is repeatable. For DMs, each character card on the campaign page shows "N choices pending". The DM can't make or waive choices. **Rehearsal on a copy of the local data:** V23's preview examined 276 rows and found 13 characters to change, each gaining `[]`. The backfill's dry run found exactly the plan's figure, `recompute-characters` found no changes, and everything loaded. An ASI taken as Lucky got its pool, and levelling down reversed both. A backfilled ASI was applied and then reversed. A Variant Human's Alert took initiative from +2 to +7. A Sorcerer 5's spell choices, including a swap of Shield for Chromatic Orb, were reversed on level-down. **Locally:** V23 was applied at 12:47 UTC after two backups and a dry run, which examined 276 rows and found 13 characters to change (`pending_choices` → `[]`), none failing to load. After a third backup, the backfill's dry run found 8 of 13 characters to change, all soft-deleted: 11 missed ASIs on 7 characters and TestBard's Expertise ×2 at Bard 3, exactly as the plan predicted, with no subclass missed and `asdasd` owing nothing. Applied, it changed those 8, and a second dry run found none. `recompute-characters` then found no changes, and all 13 characters and 15 encounters load through the API. **Tests:** new `PendingChoicesTest` (every class's choices at levels 1 to 20, typed out from the PHB), `PendingChoiceServiceTest`, `BackfillPendingChoicesJobTest` and `PendingChoicesIT` (7 tests), and frontend tests for the new dialogs and `utils/pendingChoices`. `PreviewMigrationJobIT`'s V22 preview made way for V23's preview and apply tests. The backend now has 1,648 unit and 167 integration tests, and the frontend 577 tests in 66 files, with lint at 0 errors and 17 warnings. **Left for later:** invocations, pact boons, Metamagic and maneuvers; Magical Secrets choosing from any class's list, so Bards are held to the bard list for now; swap-only levels; the Eldritch Knight's and Arcane Trickster's school limits; tool Expertise; multiclass skill picks; and server-side feat prerequisites (R8). Older data has two gaps. Subclasses chosen before F aren't recorded per level, so levelling down past them doesn't remove them. Spells sent at creation for levels above the 1st aren't recorded per level either.

**Update (2026-11-03, R6 click-through):** the creation wizard's class step now says that a 1st-level choice the wizard doesn't ask for, such as a Fighter's Fighting Style, is made on the sheet, as the Pending Choice created with the character already had it.

**Update (2026-11-03, R6 review fixes):** a swap can replace only a spell known before the choice's level. Choices made out of order let a swap replace a spell a later level learned, which left a lower level knowing a spell it couldn't have once those levels were undone, so such a swap now answers `400`. The creation wizard sends the race's chosen spellcasting ability, such as a Fairy's, instead of the class's, which overrode it. A feat that grants spells, applied through its Pending Choice or a DM grant, sets the race and feat spellcasting ability to its chosen ability (`featAbility`) when the character has none, and the level history records it so levelling down or removing the grant clears it. A character whose race and feat spells use different abilities still has one ability for both, until per-spell abilities come later. PendingChoicesTest's name now says its table follows the PHB except for the swap, offered only when Spells Known rise. The wizard's review step names a 1st-level choice made on the sheet, such as a Fighting Style.

**Update (2026-11-03, R6 review fixes, follow-up):** the creation wizard sends the race's chosen spellcasting ability, or else the first class's, so a caster keeps its class's ability and a later feat doesn't change its main spell numbers. An Eldritch Knight, an Arcane Trickster or a first class that doesn't cast still sends none. Each feat that grants spells records the ability it chose and whether it set the race and feat ability (`spellcastingAbilitySet`). Undoing the feat that set it, by levelling down or removing a DM grant, hands the ability to another applied feat that chose one, which then counts as having set it, or else clears it. Undoing any other feat changes nothing, and a race's ability set at creation is never replaced.

## D160: Combat Pipeline — A Single Resolve/Apply Path

**Date:** 2026-11-01
**Status:** Accepted — implemented (R5)

**Decision:** Every combat command follows one path:

1. authorize (D155)
2. lock the encounter row (pessimistic write lock)
3. resolve
4. apply
5. commit
6. broadcast after the commit

The pieces:

- **Resolve:** one resolver in the `rules` module handles weapon attacks, spells and monster actions, all modelled as Effects (D030). It returns outcomes and changes nothing. Outcomes are enums (`HIT`, `MISS`, `CRITICAL`, `SAVE_SUCCESS`, `SAVE_FAIL`, …).
- **Apply:** one `EffectApplier` makes every state change, following the rules in D161–D163:
  - temporary HP and damage types
  - dropping to 0, damage at 0, death and healing
  - concentration and conditions
  - legendary resistance eligibility
  - combat log entries
- **Service split:** `CombatService` is split into turn, attack, spellcasting, monster action, legendary resistance and condition services.
- **Broadcast timing:** broadcasts run in `@TransactionalEventListener(phase = AFTER_COMMIT)`, so clients never see state that was rolled back.
- **Settling disagreements:** where the current copies behave differently, the behaviour is settled to the 2014 rules and pinned by a test before the switch.

**Rationale:**
- The "apply results to targets" loop exists four times in `CombatService` (attack, spell, repeat effect, monster action and spell). Each copy handles concentration, duplicate conditions and revival differently, and that is where most combat bugs come from.
- `SpellResolverEngine` and `MonsterActionResolverEngine` duplicate the save, attack and formatting logic, and spell outcomes differently.
- Broadcasting inside the transaction can publish state that is then rolled back.
- Optimistic locking on its own turns two simultaneous actions into a failed request.

**Trade-offs:** Some actions behave differently after the switch, which is intended. Combat log wording may change; D148's rule that log entries name the action is kept. Legendary resistance undo won't work on log entries written before the switch, so deploy between sessions.

**Alternatives considered:**
- **Fix each copy in place** — rejected: the copies would drift apart again.
- **Optimistic locking with automatic retry** — rejected: retrying a resolved action can re-roll its dice. Queuing behind a lock keeps results stable.

**Amends:** D021 (broadcast timing), D030, D034 (adds the lock; `@Version` stays as a backstop), D101 (`applyDamageToTarget` generalised), D106 (the resolvers are merged)

**Key files:** `CombatService.java` (apply loops at about :528, :637, :822, :933), `SpellResolverEngine.java`, `MonsterActionResolverEngine.java`

**Related:** D161, D162, D163, D164, D165, D168, [[Effect]]

**Update (2026-11-02, R5 planning):** the resolver is `rules.combat.Resolver`, with an injected `Rng` so tests can script dice. `EffectApplier` in `encounter.internal` is the only class that changes participants. A command checks access by campaign ID, then loads the encounter under `PESSIMISTIC_WRITE` with a 5-second timeout that answers 409 **(choice)**. Services publish `EncounterChanged`; `EncounterBroadcaster` handles it after commit and reloads the committed state, so a late broadcast never sends older state. Manual damage, healing, Set HP and death saves go to a seventh service, `HitPointService` **(choice)**.

**Update (2026-11-02, R5 implementation):** built as planned. `rules.combat` holds the `Effect` model and `Resolver`. `encounter.internal` holds `EffectApplier`, the only class that changes participants; the template adapters `SpellEffects` and `MonsterEffects`; and seven services replacing `CombatService` and both resolver engines: `TurnService`, `AttackService`, `SpellcastingService`, `MonsterActionService`, `LegendaryResistanceService`, `ConditionService` and `HitPointService`. Turn boundaries run in one place, `TurnProcessor` (workstream E). `CombatAccess` checks access, then locks the encounter and requires it to be Active (workstream B). Area damage is rolled once and logged per target, with a summary entry.

## D161: Damage, Dying and Downed-Target Rules (2014 PHB)

**Date:** 2026-11-01
**Status:** Accepted — implemented (R5; player defences from DM Adjustments and the sheet's Dead state in R6)

**Decision:** The `EffectApplier` (D160) applies these rules on every path: weapon attacks, spells, monster actions, monster spells and manual DM damage.

Terms used below:
- **Dying** — at 0 HP, unconscious and making death saves.
- **Stable** — at 0 HP and unconscious, but no longer making death saves.
- **Dead** — failed three death saves, died instantly from massive damage, or, for a monster, reduced to 0 HP.

| Situation | Rule |
|---|---|
| Damage types | Immunity → 0. Otherwise apply other modifiers first, then resistance (halve, round down), then vulnerability (double). Several resistances to the same type count once. A PC's resistances, immunities and vulnerabilities come from race and feature data; a monster's from its stat block. |
| Halving (resistance, save for half) | Round down with no minimum, so half of 1 is 0. |
| Area or multi-target effect | Roll damage once per effect, then apply each target's save and resistances separately. |
| Temporary HP | Absorbs damage first. New temporary HP don't stack: keep the higher amount (the PHB lets the creature choose; higher is the default). |
| PC drops to 0 from positive HP | Falls unconscious and prone; death saves reset; concentration ends. If the damage left over after reaching 0 is at least the PC's HP maximum, the PC dies instantly. |
| PC at 0 HP takes damage (dying or stable) | One failed death save, or two if from a critical hit. A stable PC stops being stable. If the damage is at least the PC's HP maximum, the PC dies. |
| Monster drops to 0 | Dead. |
| Healing a dying or stable creature | It regains the HP and consciousness, and its death saves reset. It stays prone. (PCs already work this way today; unchanged.) |
| Healing a dead creature | No effect. Only revival effects (Revivify, Raise Dead, Resurrection and similar) bring it back, with the HP that spell gives. Time limits, such as Revivify's one minute, stay with the DM. |
| Attacking an unconscious or paralyzed target | Within 5 ft: advantage, and a hit is a critical hit. Beyond 5 ft: a paralyzed target gives advantage but no automatic critical. An unconscious target is also prone, so advantage and disadvantage cancel and it's a normal roll. Attacks never hit automatically. |
| Saving throws while unconscious, paralyzed, stunned or petrified | STR and DEX saves fail automatically. |
| Dead creatures | Can't be targeted by attacks. |

Other changes:

- Attacks carry a "within 5 ft" flag, replacing `isRanged`. It defaults to on for melee and off for ranged, since there is no map.
- `forceCrit` becomes an override only the DM can use.
- A logged DM "set HP / revive" action covers corrections.
- Manual DM damage gets an optional damage type. The default is untyped, meaning no resistances are applied.

**Rationale:** Current behaviour departs from the PHB in several places:

- Damage from spells and monsters resets a dying PC's death saves.
- Massive damage from positive HP isn't checked.
- Weapon and manual damage skip resistances.
- Halving uses a minimum of 1.
- Area damage is rolled separately for each target.
- Healing a dying PC already works correctly, but D026 also lets ordinary healing bring back a PC who has died (three failed death saves or massive damage), and it revives dead monsters too.
- The resolvers auto-hit downed targets without a critical, while `rollAttack` auto-hits and auto-crits every melee attack.

D025 called the auto-hit "PHB faithful", but the Unconscious condition grants advantage, not an automatic hit.

**Trade-offs:** Combat becomes more lethal in places (massive damage, concentration loss) and less in others (attacks on downed targets can miss). D026's convenience of "any healing resurrects" is replaced by revival effects and the DM override.

**Alternatives considered:**
- **Keep "healing resurrects" (D026)** — rejected: a potion or Healing Word shouldn't bring back a character who has died, and it currently revives dead monsters too. Healing a dying character still works as it does now.
- **Keep auto-hit on downed targets** — rejected: it isn't the 2014 rule.

**Supersedes:** D025, D026, D093

**Partially supersedes:** D112 (Damage Resistance/Immunity/Vulnerability Handling — the order of steps, rounding, coverage of every damage path, PC vulnerabilities)

**Key files:** `CombatService.java` (`applyDamageToTarget` :1189-1230, `rollAttack` :37-162, `applyHealing`)

**Related:** D101, D160, D162, [[Dying]], [[Stable]], [[Dead]]

**Update (2026-11-02, R5 planning):** `rules.combat.DamageRules` implements the table, and D172's golden test pins it first. A participant's `life_state` (Alive, Dying, Stable, Dead) replaces `is_alive`, because a Stable PC's death saves are reset and can't otherwise be told from a Dying PC's **(choice)**. Dropping to 0 adds unconscious and prone; regaining HP removes the unconscious condition that dropping to 0 added. PCs have no stored vulnerabilities, so none apply until R6 **(choice)**. Manual damage and healing become DM-only again, as D028 says; today a player who names their own participant as the actor can apply both.

**Update (2026-11-02, R5 implementation):** built as decided. `rules.combat.DamageRules` implements the table, and `encounter_participants.life_state` holds Alive, Dying, Stable or Dead (V16). Dropping to 0 makes a PC Dying, unconscious and prone; regaining HP removes that unconsciousness (end rule HP_REGAINED) but leaves prone. A PC added at 0 HP joins Stable and unconscious **(choice)**. Dead creatures can't be targeted (409). A "Within 5 ft" toggle, on by default, replaces Ranged for the downed-target rules. Manual damage and healing are DM-only, and "Set HP / revive", logged as HP_SET, replaces Resurrect. Forced criticals were DM-only in workstream D; workstream F lets players force one as a Manual Modifier when the campaign allows it (D164). PCs have no vulnerabilities until R6.

**Update (2026-11-03, R6 planning, approved):** planned for E: player characters' vulnerabilities, and any extra resistances or immunities, come from DEFENCE DM Adjustments. No 2014 race or feat grants a vulnerability **(choice)**.

**Update (2026-11-03, R6 workstream E):** built as planned. A player character's vulnerabilities, and any resistances or immunities beyond its race's, features' and feats', now come from DEFENCE DM Adjustments (D158). Each names one of the 13 damage types (PHB p.196), which `DamageRules.DAMAGE_TYPES` now lists. `CreatureStats` reads a player character's defences live, from its sheet and its active adjustments through `character/DmAdjustments`, so one the DM adds or removes during a Live Encounter applies to the next damage. A defence the character has from both counts once, as several resistances to one type always have, and removing the adjustment leaves the sheet's. In `DmAdjustmentIT`, a fire resistance added while the encounter was Active took 10 fire damage to 5, logged as halved by resistance, and a cold vulnerability doubled 4 cold damage to 8. Condition immunities aren't covered, because combat doesn't read them for anyone **(choice)**; R7 may add them.

**Update (2026-11-03, R6 workstream G):** a player character's death now reaches its sheet (D166). When the DM applies an encounter's results, a participant whose Life State is Dead marks its character dead (`player_characters.is_dead`, V24), at 0 HP with no temp HP. One still Dying or Stable is written back alive at 0 HP, keeping any temp HP it had left **(choice)**. As the table says, healing has no effect on the dead. On the sheet, healing, temp HP, damage, rests and level changes answer 409, and a later encounter can't heal a dead sheet, though its slot and pool changes still apply; `CharacterStats` keeps it at 0. Only the DM's revival brings it back, at 1 HP. Which spell did it, and its time limit, stay with the DM, as they do for Set HP / revive in combat. A dead character can't join an encounter, and an encounter can't start with one who died after being added (409). A character whose sheet is at 0 HP but not dead still joins Stable and unconscious, as before. Participants now join with the sheet's temp HP, where they used to start at 0. In `WriteBackIT` three failed death saves made Aria Dead, the End dialog's preview said she would die, and the end left her dead at 0. Her rests, healing, level-up and a new encounter got 409, the player's revival got 403, and the DM's brought her back at 1 HP.

**Update (2026-11-03, R7 planning):** the feat data carries `repeatable`, true only for Elemental Adept, which replaces the description regex. Condition immunities follow the answer to question F2, recorded on D150.

## D162: Concentration Instances and Condition End Rules

**Date:** 2026-11-01
**Status:** Accepted — implemented (R5); end rules missing from the data come with R7

**Decision:**
- **Concentration Instances.** Each casting of a concentration spell creates a Concentration Instance: caster, spell, slot level and the round it started. Conditions and ongoing effects it creates are tied to that instance, and ending the instance removes exactly what it created.
- **When concentration ends.** Starting a new concentration spell ends the caster's current instance first. Concentration also ends:
  - on a failed CON save (DC 10 or half the damage, whichever is higher; one save per separate damage application)
  - when the caster becomes incapacitated (including unconscious) or dies
  - when the duration runs out

  Monsters use the same model as PCs.
- **Condition End Rules.** Each condition has one of:
  - after N rounds
  - at the start or end of a named creature's turn
  - when its Concentration Instance ends
  - on a successful Repeat Save
  - manual removal only

  The turn service processes them at the right turn boundary. Monster Repeat Saves are rolled automatically; PCs are prompted or rolled for automatically, as the DM prefers.
- **Existing conditions.** Conditions created before this change have no end rule and are treated as manual.
- **D028 stays.** Players may still add and remove conditions on their own character, and every removal by a player is written to the combat log.

**Rationale:**
- Matching conditions by spell name means recasting the same concentration spell wipes the conditions it just applied.
- Monster spells tag conditions with the monster's name and `sourceRequiresConcentration=false`, so they never end.
- Monster damage never triggers concentration checks.
- D023's round-difference expiry ignores whether an effect ends at the start or the end of a turn.
- The save-to-end parameters passed to `applyConditions` are ignored.

**Trade-offs:** New prompts appear on both session pages, and some conditions end slightly earlier or later than today. Spell and monster data need duration and repeat-save information, and gaps in the existing data must be filled. PCs now lose concentration when monsters damage them, a real increase in difficulty.

**Alternatives considered:**
- **Keep matching by spell name plus caster** — rejected: it can't tell two castings of the same spell apart.
- **Make condition removal DM-only** — rejected in favour of D028's table practice, with logging so the DM can see removals.

**Supersedes:** D023

**Partially supersedes:** D105 (the cascade matches by instance, not spell name)

**Amends:** D032 (adds the instance reference)

**Related:** D028, D113, D114, D160, D161, [[Concentration Instance]], [[Condition End Rule]], [[Repeat Save]]

**Update (2026-11-02, R5 planning):** a Concentration Instance is a `concentration` JSONB value `{id, spell, slotLevel, startedRound, durationRounds}`. Condition entries gain `id`, `concentrationInstanceId`, `endRule` and `repeatSave`; end rule types are `AFTER_ROUNDS`, `START_OF_TURN`, `END_OF_TURN`, `CONCENTRATION`, `REPEAT_SAVE` and `MANUAL`. End rules come only from data that already has them (43 spell conditions with durations, 7 with repeat saves, and the monster `saveToEnd*` fields); every other condition is manual until R7's data files fill the gaps **(choice)**. The DM's preference for PC Repeat Saves is the campaign setting `prompt_player_repeat_saves`, off by default, so the server rolls them **(choice)**. A spell's duration ends at the start of its caster's turn **(choice)**.

**Update (2026-11-02, R5 implementation):** Concentration Instances are stored in `encounter_participants.concentration` (V16). Each damage application forces one concentration save, monster damage included. Concentration ends on incapacitation, 0 HP or death, a new casting ends the old instance first, and ending an instance removes exactly the conditions it created. Condition entries carry an `endRule` (the planned types plus HP_REGAINED, for the unconsciousness from dropping to 0 **(choice)**) and an optional `repeatSave` `{ability, dc, awaitingRoll}`, and `TurnProcessor` applies them at turn boundaries. A duration ends at the start of its caster's turn, or of the bearer's if the caster has left or is a dead monster **(choice)**. The server rolls Repeat Saves at the end of the creature's turn, unless the campaign's `prompt_player_repeat_saves` is on, when a PC's condition waits for the player to roll through `POST /api/encounters/{id}/combat/repeat-save`. A failed monster Repeat Save offers Legendary Resistance. A Repeat Save with its own condition, such as Fear's on losing sight of the caster, is left to the DM **(choice)**. Spell conditions stored as objects now apply with their own durations and Repeat Saves; workstream D had dropped them, so Hold Person didn't paralyze until workstream E. No data creates START_OF_TURN or END_OF_TURN rules yet; R7's data files add them.

## D163: Legendary, Lair and Turn-Order Rules

**Date:** 2026-11-01
**Status:** Accepted — implemented (R5)

**Decision:**
- **Legendary Resistance:** using it turns the failed save into a success. The creature takes the effect's success outcome (half damage or none, as the Effect specifies), and only the conditions from that save are removed. It is offered for every failed save by a creature with uses left, whatever caused the save, including player spells. D145's log-based mechanism stays.
- **Legendary actions:** they stay usable throughout the round, on other creatures' turns. The only change from D133 is that they can't be used during the legendary creature's own turn, when it takes its normal actions instead. The DM decides the exact moment, because the app can't detect the end of a turn. Spent actions return at the start of the creature's turn, as now.
- **Lair actions:** D136 is completed. The "fighting in its lair" toggle moves to the live DM page (D165), and the Lair row appears at initiative 20, losing ties. The rule against repeating a lair action two rounds running stays.
- **Turn order:**
  - `currentTurnIndex` is the single source of truth, and `isCurrentTurn` is calculated in responses.
  - Initiative ties default to the higher DEX score. That's a common convention: the PHB leaves ties to the DM and players. The DM can reorder tied creatures.
  - Dead monsters are skipped.
  - Dying PCs keep their turns and are prompted for a death save at the start of each.
- **Monster proficiency:** CR 0 gives +2 (CR 0–4 → +2).

**Rationale:**
- D145 refunds all the damage when legendary resistance is used, but a creature that succeeds on a save-for-half effect still takes half.
- `SpellResolverEngine` results carry no legendary resistance data, so player spells never offer it.
- The PHB allows legendary actions only at the end of another creature's turn, which never falls on the creature's own turn.
- Lair status can currently be set only from the unused `MonsterActionPanel`, and `monstersInLair` is never read.
- Whose turn it is lives in two places (`currentTurnIndex` and `isCurrentTurn`), which sort missing initiatives differently.

**Trade-offs:** DMs can't use legendary actions on the monster's own turn (D133 allowed them at any time). New legendary resistance prompts appear on player spell results.

**Alternatives considered:**
- **Enforce "end of another creature's turn" exactly, with a legendary-action window after every turn** — rejected: it adds a step to every turn for little benefit.
- **Leave D133 unchanged** — rejected: it allows a clear rules violation.

**Partially supersedes:** D133 (timing), D134 and D145 (what legendary resistance undoes, and offering it for every source)

**Amends:** D136 (completes it)

**Related:** D135, D146, D147, D160, D165, [[Legendary Resistance]], [[Legendary Action]], [[Lair Action]]

**Update (2026-11-02, R5 planning):** `rules.combat.TurnOrder` sorts by initiative, then higher DEX, then the DM's order for ties, kept in `sort_order`. The Lair is an entry in the turn order named "Lair Actions", at initiative 20 and losing ties, present while a monster listed in `monsters_in_lair` is alive; it is not a participant row **(choice)**. Lair actions run only on the Lair turn. Legendary Resistance log entries store the success-outcome damage and the IDs of the conditions that save applied. Using Legendary Resistance on the save that killed a monster brings it back with the refunded HP, because it undoes the save **(choice)**. CR 0 proficiency was already fixed in R2.

**Update (2026-11-02, R5 implementation):** the encounter stores whose turn it is (`current_turn_participant_id`, or `is_lair_turn` for the Lair) rather than a position, because the order is computed, and a stored position drifts as DEX breaks ties, participants come and go and the Lair's turn appears and disappears **(choice)**. The API still returns `currentTurnIndex`, computed for each view (V17). A tie means equal initiative and equal DEX; the DM orders a tie group with `PUT /api/encounters/{id}/initiatives/tie-order`, while Preparing or Active. The Lair's turn is listed while a monster in `monsters_in_lair` is alive, and the DM sets that with an In lair toggle on monsters that have lair actions. Lair actions outside the Lair's turn, and legendary actions on the monster's own turn, answer 409. At the start of a turn a monster regains its legendary actions, spent Recharge abilities roll, and a Dying PC gets a death-save prompt, kept in `death_save_due` until it rolls **(choice)**. Dead monsters' turns are skipped, and Next Turn with nobody able to act answers 409. Legendary Resistance is offered on every failed save while uses remain; it refunds the difference from `lr_success_damage` and removes the conditions in `lr_condition_ids` (V16). Saves logged before V16 can't use it.

**Update (2026-11-03, R5 follow-ups):** a failed save against a hit's condition also offers Legendary Resistance; using it removes only the conditions that save guarded, and the hit's damage stays. It isn't offered when the hit killed the target. The tie arrows are a shared component, also shown in the encounter builder while Preparing or Active.

## D164: Attacks and Spells Calculated and Validated by the Server

**Date:** 2026-11-01
**Status:** Accepted — implemented (R5; per-class spell numbers and item links in R6 workstreams C and D)

**Decision:**
- **Weapon attacks.** A player's weapon attack names the weapon and any declared options: advantage or disadvantage, within 5 ft, Great Weapon Master or Sharpshooter. The server calculates:
  - the attack bonus: the ability modifier the weapon allows (STR for melee, DEX for ranged, either for finesse), plus proficiency if proficient, plus any magic bonus
  - the damage: weapon dice plus the ability modifier and any magic bonus

  This needs inventory items to link to item reference rows. That is to be checked during R5 planning, and a migration may be needed.
- **Spell casts.** A spell cast names the spell and the slot. The server uses that class's spellcasting ability for the attack bonus and save DC, and checks that:
  - the spell is known, prepared or granted (by race, feat or subclass)
  - the slot is at least the spell's level and is available
  - a levelled spell uses a slot

  Cantrips scale with character level.
- **Manual Modifiers.** A campaign setting, on by default, lets players enter a manual attack bonus, damage dice, save DC or forced critical. Each use appears in the combat log with its value. The DM can always enter them. DM overrides for monsters are unchanged (D104, D131).

**Rationale:** The server currently takes the attack bonus, damage dice, `forceCrit` and spell attack and DC overrides from the player's browser. It doesn't check that a spell is known, and a levelled spell sent with slot level 0 costs nothing.

**Trade-offs:** Until class features (M17) and magic items are modelled, some legitimate attacks can't be calculated (the Archery fighting style, Hunter's Mark damage, a magic weapon that isn't listed). That is why the Manual Modifier setting defaults to on. The player's attack form changes from typing numbers to picking a weapon.

**Alternatives considered:**
- **Server-calculated only, with no manual input** — rejected: it would block legitimate play until M17.
- **Keep trusting client values** — rejected: the rules can't be enforced.

**Partially supersedes:** D022 (nobody types the attack bonus and dice for PC attacks any more)

**Amends:** D028 (the on-turn attack permission is unchanged)

**Related:** D104, D131, D158, D160, [[Manual Modifier]]

**Update (2026-11-02, R5 planning):** inventory entries are free text (`{name, quantity, description}`) with no item link. R5 matches an entry's name against the 54 base weapons, whose names are unique, and migrates no characters; item IDs come with R6's typed inventory **(choice)**. Magic bonuses aren't stored (the seeder doesn't keep `bonusWeapon`), so they are Manual Modifiers for now. The setting is `campaigns.allow_player_manual_modifiers`, on by default. A player's forced critical counts as a Manual Modifier, which reconciles D161's DM-only override with this decision and CONTEXT.md **(choice)**. Great Weapon Master and Sharpshooter need the feat among the character's features and a qualifying weapon the character is proficient with. Race and feat spells have no recorded casting ability, so they use the character's main spell attack bonus and save DC **(choice)**.

**Update (2026-11-02, R5 implementation):** workstream F adds `campaigns.allow_player_manual_modifiers` (V18, on by default) and `ManualModifiers`. The DM always, and players while the setting is on, may enter a spell attack bonus, save DC or forced critical; a refused one answers 403, and each use is logged under the action's own type, for example "Manual Modifier (player): Rogue's attack is a critical hit." Players' hand-entered weapon attack numbers stay until workstream G replaces them with weapon attacks the server calculates.

**Update (2026-11-02, R5 workstream G):** weapon attacks are now calculated by the server. `rules.combat.WeaponRules` works out the ability (STR for melee, DEX for ranged, and for finesse the higher modifier, keeping the weapon's own on a tie), the attack bonus and the damage; the Resolver doubles a critical's dice. Great Weapon Master and Sharpshooter offer −5 to hit for +10 damage only with the feat (read from `features` and the level history's ability score choices) and a qualifying weapon the character is proficient with; a thrown melee weapon qualifies for neither, and anything else answers 400. `CharacterWeapons` matches inventory names, ignoring case and plurals, against the 55 mundane weapons, meaning `items` rows with a weapon category, damage dice and no rarity: the 54 base weapons less the Net, plus the Iron Ball and the Oversized Longbow **(choice)**. Proficiency matches the spellings in the data: simple, martial, Simple Weapons, weapon names and plurals, and Firearms by ammunition tag. Unarmed Strike is always offered, and `GET /api/encounters/{id}/combat/attack-options` lists the choices. A magic bonus, extra damage, a forced critical or a whole attack entered by hand is a Manual Modifier, a whole attack counting as one **(choice)**. Left for later: versatile damage, fighting styles, Monk Martial Arts, two-weapon fighting, ammunition, loading and reach, disadvantage for ranged attacks in melee, and an arcane focus Staff counting as a quarterstaff. No local character has inventory items yet, and there is no UI to add them, so players see Unarmed Strike and, while the setting allows it, Other.

**Update (2026-11-03, R6 planning, approved):** planned: inventory entries' optional `itemId` links them to items, and weapon matching tries it before the name (D). Class spells in combat use that class's DC and attack bonus from the sheet; race and feat spells keep the main numbers (C) **(choice)**.

**Update (2026-11-03, R6 workstream C):** each class's spell numbers are worked out when the sheet is read, not stored, so C adds no column **(choice)**. Every character response gains `spellcasting`, a `ClassSpellcasting` entry for each class that casts, in the order the classes were taken: its ability, save DC, attack bonus and highest spell level, its cantrip and known or prepared limits, and whether it is a Warlock's Pact Magic. An Eldritch Knight or Arcane Trickster counts from 3rd level, with INT. In combat, `SpellcastingService` casts a player character's class spell with that class's DC and attack bonus, read from the live sheet through `CharacterService.classSpellcasting`, and a race or feat spell, or a monster's, with the main numbers. `SpellSources` and `CasterSpells` decide whose spell a cast is: a class's, when the character has the spell from a class, unless an innate use pays. A Manual Modifier's log entry names the number the spell would have used. The Main Spellcasting Ability is the one chosen at creation, such as a Fairy's choice for its race spells, or else the first casting class's, and the derivation never writes it **(choice)**. The Spells tab shows each class's numbers from the server, with the main numbers beside race and feat spells. Left for later: encounter participants still copy the stored spellcasting ability, which changes only what is displayed.

**Update (2026-11-03, R6 workstream D):** inventory entries' optional `itemId` now links them to items. `InventoryChecks` answers 400 for a link to an item that doesn't exist, at creation as well as through the new `PUT /api/characters/{id}/inventory` (D158). Weapon matching in combat (`CharacterWeapons`) tries `itemId` before the name: an entry linked to one of the 55 mundane weapons is that weapon, whatever it is called, and is offered under the weapon's name. An entry linked to any other item, such as a magic weapon, is matched by its name, as before. In `WeaponAttackIT` the greatsword is called "Grandfather's blade" and linked to its item. The sheet's Inventory tab, which could only change coins, now adds and removes entries, changes quantities, and links an entry to an item from the items list or unlinks it.

**Update (2026-11-03, R6 review fixes):** combat worked out the spellcasting modifier that damage and healing add, the MOD in spell templates, as the spell attack bonus less proficiency, so a DM Adjustment or a Manual Modifier to the attack bonus also changed Cure Wounds and the like. It now comes from the score of the ability the spell is cast with: the class's ability for a class spell, the character's race and feat ability for the others, and a monster's spellcasting ability. It falls back to the attack bonus less proficiency only when no ability is known.

## D165: Monster Action and Spellcasting Fixes

**Date:** 2026-11-01
**Status:** Accepted — implemented (R1, R5; player innate pools in R6)

**Decision:**
- **One spellcasting path** for PCs and monsters, paid from a Spell Source: a spell slot, a pact slot, an innate use, or nothing (at will).
- **Innate uses live only in resource pools:**
  - spell slot fields hold real slots only, so the `{"innate": true, ...}` blob goes
  - innate spells are cast at their base level unless the stat block says otherwise
  - one builder creates innate pools, and the duplicate in `MonsterSpellCastService` is removed
- **The DM's monster spellcasting uses `/combat/monster-spell`.** In R1, three things move from the unused `MonsterActionPanel` into the live DM page: the lair toggle, monster spellcasting and the legendary resistance prompt. The panel is then deleted, along with its call to `confirmFailedSave`, which doesn't exist.
- **Multiattack:** the DM picks a target for each component attack, and every damage part is applied with its own type (e.g. piercing plus poison).
- **Feature triggers** match exact feature names. Text that merely mentions "legendary" no longer grants unrelated pools such as Legendary Resistance.
- **Dice are structured values** (count, sides, modifier). Negative modifiers such as `1d8-2` work.

**Rationale:**
- The DM page currently casts monster spells through the player endpoint, which can't read innate data.
- Innate "X/day" keys are read as spell levels.
- `parseSpellSlots` silently returns nothing for the innate blob.
- Multiattack resolves every component against every selected target and drops extra damage.
- `hasFeature` returns true for any monster whose legendary actions text contains "legendary".
- MOD substitution can produce `"1d8+-2"`, which `DiceRoller` rejects.

**Trade-offs:** The DM's multiattack screen changes to choosing a target per attack. The monster action data must contain every damage part, which still needs checking. Monsters already in an encounter must be re-added after the innate change. Some monsters lose pools they were wrongly given.

**Alternatives considered:**
- **Keep `MonsterActionPanel` and wire it in** — rejected: it predates the current design system's styling and duplicates the live inline actions.

**Partially supersedes:** D116 (string substitution replaced by structured dice)

**Amends:** D104, D131, D132 (combo lines kept, with a target per component), D143

**Related:** D136, D145, D160, D163, [[Spell Source]]

**Correction (2026-11-01, R1 planning):** `MonsterActionPanel` has no lair toggle (`setLairStatus` is never called anywhere), and the legendary resistance prompt already lives in the DM combat log (D145). R1 therefore moves only monster spellcasting. The lair toggle is built in R5 with D163, when the backend starts reading it.

**Update (2026-11-02, R5 planning):** the Spell Sources are `SLOT`, `PACT_SLOT`, `INNATE` and `NONE`. Daily keys such as `1e`, `1/day` and `3e/day each` give uses, never spell levels; an innate spell is cast at its level in `spells`, because the data records no cast level **(choice)**. One `MonsterPools` class builds every monster pool. `rules.Dice` also parses flat damage such as `1` (45 entries) and negative modifiers such as `1d6 - 1` (32 entries); the monster data already lists each damage part separately (882 actions have two or more). **Correction:** the seeded Legendary Resistance trigger is `{"hasField": "legendary"}`, and `hasFeature` is never seeded, so the 50 monsters with legendary actions but no Legendary Resistance, such as the Aboleth, get the pool anyway. A generic breath-weapon trigger also duplicates the per-action recharge pools. The DM's spell modal hid every levelled monster spell, because monster entries carry no `prepared` flag, and then asked for a spell name the server can't cast.

**Update (2026-11-02, R5 implementation):** built in workstream F. Monsters and PCs cast through one path; `MonsterSpellCastService` and the monster-spell endpoint are gone, and `GET /api/encounters/{id}/combat/spell-options` lists what an actor may cast. `SpellSources` picks the Spell Source (SLOT, PACT_SLOT, INNATE or NONE; the server works it out when a cast leaves it out) and checks that a PC's spell is known or prepared, at a legal level, with a slot or use left, answering 400 otherwise; a DM casting for a PC follows the same rules. Innate uses are resource pools named `innate:<spell>`; a PC's start full on its first cast in an encounter and aren't written back until R6 **(choice)**. Race and feat spells with their own uses count as innate uses at their own level and can also be cast with a slot, as Fey Touched and the Multiverse races allow; ones with no recorded uses can only use slots **(choice)**. Always-prepared subclass spells come from the subclass data **(choice)**. `MonsterSpellbook` reads every shape of monster spell list, so daily keys such as `1/day each` give uses (a drow's Darkness is a 2nd-level spell, once read as level 1); `additionalSpellcasting` on 5 monsters and notes such as "as a 5th-level spell" aren't read yet. `MonsterPools` builds every monster pool, replacing `ResourcePoolService`'s monster builders. V18 matches Legendary Resistance to the trait: 336 monsters get the pool instead of 386, and the 50 that lose it, such as the Aboleth, have legendary actions but no Legendary Resistance. The breath-weapon trigger it deletes matched no monster. Monsters already in an encounter keep the pools they joined with, so 10 local participants, Aboleths among them, hold a wrong Legendary Resistance pool until re-added. The DM's spell modal lists a monster's slot, innate and at-will spells.

**Update (2026-11-02, R5 workstream G):** each Multiattack component takes its own target (`componentTargetIds`), and `GET /api/encounters/{id}/combat/multiattack-options` lists the components. Each one resolves and logs on its own, and later ones skip creatures that earlier ones killed. A multiattack with no components, with a component naming none of the monster's actions, or with the data's own and/or note can't be read: 124 of 1,478 are left for the DM with the reason, including 102 that used to resolve partly **(choice)**. A multiattack with alternatives offers its default components, which the DM can skip; choosing an alternative isn't supported yet. Sending a whole multiattack at more than one target answers 400. **Known issue (fixed in the R5 follow-ups, see the note below):** 90 on-hit conditions, such as a dire wolf's prone, applied without the saving throw the data records.

**Update (2026-11-03, R5 follow-ups):** a hit's conditions now wait for the saving throw the data records, on the condition itself or, failing that, on the attack (`saveDC`, or the Monster Manual data's `secondarySaveDC`). The target rolls through `SaveRules`, the condition applies only on a failure, and the roll is logged on the target's entry. Grapples stay automatic, because a stat block's escape DC is for escaping, unless the grapple itself records a save (the Mindwitness and Ulitharid tentacles, whose text says otherwise; R7 corrects the data). 205 conditions in 133 attacks on 129 monsters now wait for a failed save, such as the Dire Wolf's prone, the Lich's paralysis and the Mind Flayer's stun. A Repeat Save with no DC of its own uses the condition's save. Left for R7's data files: 53 conditions record a DC without an ability (grapple escape and check DCs) and still come with the hit; about 590 have a save only in the stat-block text; the Froghemoth, Froghemoth Elder and Slarkrethel records hold the monster's own save rather than the target's; and rules such as "fails by 5 or more" apply on any failed save.

**Update (2026-11-03, R6 planning, approved):** planned for C: a player character's innate spell uses become sheet pools named `innate:<spell>`, built from known spells that have uses, reset on a long rest, copied into encounters and written back like any other pool **(choice)**.

**Update (2026-11-03, R6 workstream C):** a player character's innate spell uses are now sheet pools. Each levelled spell the character knows with uses of its own, once unlocked at its level and unless it is at will, gets a pool named `innate:<spell>`, the ID participants already use, which resets on a long rest; a spell known from two places gets one pool. `ResourcePoolService.reconciledPools` builds them with the class and subclass pools whenever the Derived Stats are worked out (D167), so they join an encounter with the character and are synced back at completion like any other pool. A participant that joined without one still starts it full on its first cast. A synced pool now keeps the uses already spent, as slots and Hit Dice do, where it used to keep its current uses **(choice)**. Locally the `recompute-characters` job added innate pools for Faerie Fire, Enlarge/Reduce and Absorb Elements, at 1/1 each, to TestBarb.

**Update (2026-11-03, R6 workstream D):** the rests that refill a player character's innate pools now run on the server (D158). A long rest refills every pool, innate uses included, and a short rest leaves them, since they reset on a long rest. The sheet spends and recovers them through `POST /api/characters/{id}/pools/spend` and `/recover`, as `CharacterCommandsIT` does with `innate:hellish-rebuke`. Feats with limited uses now get sheet pools of their own, named `feat:<feat>` in the same kebab case (`ResourcePoolEntry.featPoolId`), which `ResourcePoolService.reconciledPools` builds from the level history beside the innate pools (D158).

**Update (2026-11-03, R7 planning):** the data is corrected for the Kraken's, Froghemoth's (VGM and MPMM), Froghemoth Elder's and Slarkrethel's bites, whose DC is the monster's own save to regurgitate, and for the Mindwitness's and Ulitharid's (MPMM) tentacles, whose grapples are automatic. The 53 DCs without an ability become `escapeDC` or are dropped. The roughly 590 saves found only in stat-block text are reported as candidates, not applied **(choice)**.

## D166: Encounter Lifecycle and Write-Back

**Date:** 2026-11-01
**Status:** Accepted — implemented (lifecycle R5; Write-back R6 workstream G)

**Decision:**
- **Lifecycle.** Encounters move Preparing → Active ⇄ Paused → Completed. Any other transition returns 409, and Completed is final.
- **Applying results.** Completing an encounter asks the DM whether to apply its results to the character sheets. Applying is the default.
- **Write-back applies changes, not final values.** For each player character, what changed during the encounter is applied to the sheet as it stands at that moment:
  - HP lost or regained
  - spell slots and resource pool uses spent or recovered
  - hit dice spent
  - death: a PC who died is marked dead

  Values stay within limits: HP between 0 and maximum, uses between 0 and maximum. Temporary HP is set to whatever was left. Conditions are not written back.
- **Several encounters at once.** A character can be in more than one encounter at a time. Each encounter's changes are applied when it completes, so the results add up.
- **Blocked during an encounter.** While a character is in an Active or Paused encounter, rests and level changes return 409. Other sheet changes are allowed.

**Rationale:**
- `pause`, `resume` and `end` skip status checks, so a Completed encounter can be resumed and ended again, syncing pools twice.
- HP and spell slots spent in combat never reach the character sheet. D024 listed sync-back as a future improvement.
- D024's original aim was to allow several combat sessions at once, each working on its own copy of the character, with an option to update the main sheet afterwards. Applying changes rather than copying final values keeps that aim: concurrent encounters combine without anyone choosing which copy wins, and the sheet doesn't need locking during combat.
- The choice at completion keeps the option to update the sheet, but gives it to the DM, who adjudicates, rather than to the player.

**Trade-offs:** Character sheets now change after every fight, as 5e expects, and players will notice. A character in two encounters at once starts each with the same resources, so the same last spell slot could be spent in both; when both are applied, the sheet stops at zero. Rests and level-ups wait until the character's encounters have ended.

**Alternatives considered:**
- **One active encounter per character, with final values copied back** (the first draft of this decision) — rejected: it blocks concurrent sessions and needs the sheet locked during combat.
- **The player chooses whether to apply results** (D024's original intent) — rejected: players would have a reason to decline damage. The DM decides.
- **Keep combat separate from the sheet** — rejected: resources spent in combat must carry over through the adventuring day.
- **Write back conditions too** — rejected: most combat conditions end with the encounter, and long-lasting ones can be re-applied.

**Partially supersedes:** D024 (combat results now reach the character record; the per-encounter copies stay)

**Related:** D125, D158, [[Encounter Lifecycle]], [[Write-back]]

**Update (2026-11-02, R5 planning):** combat commands need an Active encounter. A Paused encounter can only resume or complete **(choice)**. Completing an encounter still syncs pools back, but only once, because Completed is final. The apply-results question and Write-back come in R6.

**Update (2026-11-02, R5 implementation):** combat commands need an Active encounter, and a Paused one can only resume or complete (workstream B). Removing a participant is also allowed while Active: it ends that creature's concentration and passes the turn on if it was theirs **(choice)**. Adding participants stays Preparing-only.

**Update (2026-11-03, R5 follow-ups):** the DM session page has a Remove control on each creature, behind a confirmation, while the encounter is Preparing or Active.

**Update (2026-11-03, R6 planning, approved):** planned for G, answered with the defaults. Each participant stores what it joined with (`joined_with`), and characters added while Preparing are copied again when the encounter starts **(choice)**. At completion the DM decides once for the whole encounter, in an End dialog that previews each character's changes; the preview can't be edited **(choice)**. Applying writes the HP, slot and pool changes within their limits, sets temp HP to what was left, and marks a character who died as dead. A character still Dying or Stable is written back alive at 0 HP. A dead character can't rest, be healed or join an encounter until the DM revives it, at 1 HP **(choice)**. A character removed during an Active encounter has its changes applied at removal. If the DM declines, nothing syncs back, pool uses included. Players see the result on their sheet, which reloads when the encounter completes; there is no separate summary **(choice)**. Snapshots for encounters already Active or Paused come from the sheets at the time of migration. Character rows are locked in ID order at completion, and the old pools-only sync-back goes.

**Update (2026-11-03, R6 workstream D):** the block during an encounter is built. While a character is in a Live Encounter, one that is Active or Paused, its short rest, long rest, level-up, level-down and apply-choices answer 409 with a message naming the character and what waits, such as "Aria is in an encounter that is under way, so can't take a long rest until it ends". Its other sheet commands, such as HP, slots and pools, go on, since combat changes the participant rather than the sheet (D158). Every character response's `inLiveEncounter` tells the sheet, which disables the rest and level buttons and says why. `character` asks through its own `LiveEncounters` interface, which `encounter` implements (D151). A Preparing encounter blocks no rest or level, but a character in one still can't be deleted, as before **(choice)**. Left for G: End still writes pool uses back without locking character rows, so in a rare race with a character command, End answers 409 through the character's version check (V21).

**Update (2026-11-03, R6 workstream E):** DM Adjustments are allowed during Live Encounters: `CharacterCommands.beginAsDm` doesn't ask about encounters, just as the HP, slot and pool commands don't (D158). Whether one applies at once depends on whether combat reads the stat from the sheet or copies it when the participant is added. Defences, ability scores, and the spell save DC and attack bonus of class spells are read from the sheet, so an adjustment to them reaches the participant at once: damage uses the adjusted defences, and saves and weapon attacks the adjusted scores (D161, D164). AC, the HP maximum, initiative and the main spell numbers that race and feat spells use are copied when the participant is added, so an adjustment to them reaches only participants added afterwards. In `DmAdjustmentIT` a fire resistance added while the encounter was Active halved the next fire damage. Left for G: stats copied when a participant joins don't pick up adjustments made during a Live Encounter.

**Update (2026-11-03, R6 workstream G):** G builds Write-back. **Storage:** V24 adds `encounter_participants.joined_with`, JSONB, typed as the new `character.state.CombatTotals` `{hpCurrent, hpTemp, slotsUsed, poolsCurrentUses}`: the HP, temp HP, each slot's used count and each pool's current uses that a player participant joined with. The same record describes what it ended with. For Active and Paused encounters V24 fills it from the sheets as they stand, with HP kept between 0 and the maximum, as planned; a Preparing encounter's participants get theirs when it starts, and a Completed one's stay null (D168). V24 also adds `encounters.results_applied`, BOOLEAN, the DM's choice, which is null until an encounter completes and for those completed before R6, and `player_characters.is_dead`, BOOLEAN, not null, default false. **Joining:** a player participant copies its sheet through the new `character/CombatSnapshot.of(character)` when it is added, and again when the encounter starts. At the start it copies everything except the initiative the DM set **(choice)**, so a rest or a DM Adjustment made while Preparing reaches it, and nothing that changed before the start is written back. Participants now join with the sheet's temp HP, where they used to start at 0 **(choice)**. A dead character can't be added (409), and the start answers 409 if an added character has died since, until the DM revives or removes them **(choice)**. `EncounterService.copySpellSlots` and `copySpellsKnown` are gone, as are `ResourcePoolService.syncBackToCharacter` and `copyToParticipant`, so `EncounterService` no longer uses `ResourcePoolService` (D125, D151). **Ending:** `GET /api/encounters/{id}/results` previews each player character's changes against its sheet as it stands, for the End dialog. It is for the DM only and only for Active or Paused encounters, it lists only the slots and pools that would change, and it changes nothing. `POST /api/encounters/{id}/end` takes `{applyResults}`, which is true when it is missing or null **(choice)**, and the encounter keeps it in `results_applied`. Ending a completed encounter again answers 409, as before. Ending locks the encounter's row and publishes `character/CombatResults`, which `encounter/EncounterResults` builds from each player participant, and `character/WriteBackService` applies it inside the completing transaction (D151). `WriteBackService` locks the characters' rows sorted by UUID text, which is PostgreSQL's order, with the five-second wait, and then applies the rules in `rules/character/WriteBack`. A character loaded before its lock and changed since fails the version check with 409. **What applying writes:** each change since `joined_with`, in HP, slots used and pool uses, applied to the sheet as it stands and kept within its limits, so two encounters add up; temp HP, set to what the participant had left; for a participant who died, dead at 0 HP and 0 temp HP; and for one still Dying or Stable, alive at 0 HP, keeping any temp HP left **(choice)**. Once a sheet is dead, a later encounter can't heal it, though its slot and pool changes still apply **(choice)**, and the stats derivation keeps a dead character at 0 (D167). Conditions aren't written back, as decided. Nor are Hit Dice: nothing in combat spends them, so the Decision's "hit dice spent" has nothing to write yet. **Declining:** nothing is written, pool uses included; not even a character row's version changes. **Removal:** removing a player participant from an Active encounter applies its changes at once, and the end leaves them alone. Removal while Paused is still refused under R5's rule, so that branch can't be reached yet. Removal while Preparing writes nothing. **Dead characters:** short and long rests, healing, temp HP, damage, level-up, level-down and joining an encounter answer 409. Choices, details, inventory, slots, pools, DM Adjustments and deletion are still allowed **(choice)**. `POST /api/characters/{id}/revive` goes through `beginAsDm`, so it is for the DM of the character's campaign only. Its 403 reuses the DM Adjustments wording, and a character in no campaign can't be revived (D155, D158). Reviving sets HP to 1 and clears temp HP and the dead flag, and reviving again answers 409. The character response carries `dead`. **Frontend:** `EndEncounterModal` replaces the `window.confirm`. It previews each character's changes, such as "HP 28 → 19 of 28" and "Dies", which can't be edited, with "Apply results to character sheets" ticked. The sheet has a dead banner, and its rest, HP and level buttons are disabled. The campaign page shows Dead and a Revive button on the DM's card, and the encounter builder shows "Dead" instead of Add. The removal confirmation says a player character's changes so far are applied. An open sheet reloads every 10 seconds while its character is in a Live Encounter, and every 30 seconds otherwise, through the query layer (`useCharacter`'s `followChanges`), and the DM's end and removal also refresh the cached characters. The WebSocket isn't used, and no message changed **(choice)**. The new hooks are `useEncounterResults` and `useReviveCharacter`, and the DM's character commands share `useDmCommand`, which was `useAdjustmentCommand`. **Rehearsal on a copy of the local data:** V24's preview examined 276 rows and found 16 changes. Ending asdasd's three encounters, one applied (50 → 47), one declined (nothing changed) and one applied (→ 0, alive, with Rage still 2/2), gave the plan's 50 → 0. A death, the 409s and revival at 1 HP worked. Removal mid-fight applied 50 → 38, and the end left 38. A browser check of the End dialog, death and revival passed. **Locally:** V24 was applied at 13:51 UTC after two backups and a dry run, which examined 276 rows and found 16 to change: asdasd's 3 participants in Live Encounters gain `joined_with` with HP 50, temp HP 0, no slots and Rage 2/2, and all 13 characters gain `is_dead` false. The 15 encounters gain `results_applied` as NULL, which isn't counted as a change, and none fails to load. `recompute-characters` and `backfill-pending-choices` then found no changes, and all 13 characters and 15 encounters load. Ending asdasd's 3 Live Encounters with their results applied would take its HP from 50 to 0 (−50, 0 and −3), alive, with Rage unchanged. **Tests:** new `WriteBackIT` (10 tests), `WriteBackTest`, `WriteBackServiceTest` and `CombatSnapshotTest`, and new cases in `EncounterServiceTest` and `EncounterJsonMappingIT`. `HitSaveIT`, `LegendaryResistanceIT`, `MultiattackIT`, `SpellcastingIT` and `TurnBoundaryIT` edit their participants after the start, since starting copies the sheet again. `PreviewMigrationJobIT`'s V17 and V23 preview tests went, because today's entities can't load those schemas' rows; their apply tests stay. The backend now has 1,676 unit and 178 integration tests, and the frontend 586 tests in 66 files. **Left for later:** whether to allow removal while Paused; a combat log entry for applied or declined results, which only the server log records for now; making `spendPool` and `recoverPool` take lists, which would break the cycle through `resourcepool` (D151); showing `results_applied` on the DM's page; the revive 403's wording; and writing back conditions and Hit Dice, as the Decision leaves them. Stats copied at join don't pick up adjustments made during the fight, by design (D158).

## D167: Character Progression — Derived HP and Exact Reversal

**Date:** 2026-11-01
**Status:** Accepted — implemented (R6 workstreams A, C, E and F)

**Decision:**
- **HP maximum is derived** from:
  - the sum of each level's recorded hit die result (the maximum die at level 1)
  - total level × CON modifier
  - per-level bonuses: Tough +2, Hill Dwarf +1, Draconic Resilience +1 per sorcerer level
  - DM Adjustments

  CON changes apply retroactively, as the PHB says. Level history records the hit die result, the class, the choices made, and the ability score increase actually applied.
- **Levelling down** removes the last history entry and recalculates, with no reverse arithmetic. D056's record of applied effects for feats stays.
- **The ability score maximum** is set per character (default 20; Barbarian Primal Champion raises STR and CON to 24) and checked in one place.
- **One modifier function**, `floorDiv(score − 10, 2)`, replaces five copies.
- **Levelling up adds one hit die** of that class to both the maximum and the remaining count. Spent dice stay spent.
- **Multiclass prerequisites** are checked only when adding a new class, against every current class and the new one (PHB p.163).
- **Other fixes:**
  - resource pools re-sync after levelling down
  - Eldritch Knights and Arcane Tricksters get spell prompts from their own tables
  - spell save DC and attack bonus are calculated for each Spellcasting Class
- **Existing characters** are recalculated after a dry-run report and a backup (D150).

**Rationale:** Current problems:

- Levelling down reverses ability increases after lowering the level, so a CON change adjusts HP for the wrong number of levels.
- Undoing an increase near 20 loses points, because increases are capped and reversals are not.
- Tough adds +2 only on the level it is taken.
- The feat resolver's modifier truncates, which gives odd scores below 10 the wrong modifier.
- Levelling up refills spent hit dice.
- The check for adding a new class looks only at the primary class.
- Third casters are never prompted for spells.

Deriving whatever can be derived removes the reversal arithmetic where these bugs live, while history still records every non-repeatable choice — the concern behind D052.

**Trade-offs:** Some characters' HP maximum will change when recalculated. History entries written before the change have no record of the increase actually applied, so they fall back to today's logic. Multiclass casters see a separate DC for each class.

**Alternatives considered:**
- **Patch each reversal step** — rejected: the reversal arithmetic is where these bugs keep coming from.

**Partially supersedes:** D052 (reversal by recalculation instead of arithmetic), D062 (the server calculates spell stats for each class)

**Amends:** D053 (when prerequisites are checked), D056, D060

**Related:** D054, D158, D159, [[Derived Stat]], [[Spellcasting Class]]

**Update (2026-11-03, R6 workstreams A and B):** workstream A added `rules.character`, pure functions with golden tests typed from the PHB: `ClassProgression` (ASI, expertise and fighting style levels, cantrips and spells known, prepared limits, highest spell level), `HitPoints` (hit die results plus CON, Tough, Hill Dwarf and Draconic Bloodline bonuses, at least 1 per level), `HitDice` (long-rest recovery of half the total, largest first), `Rests`, `AbilityScores` (the maximum of 20, or 24 for STR and CON after Primal Champion) and `Multiclassing` (every class's prerequisites). Nothing calls them until workstream C. `rules.Ability.modifier` rounds down (PHB p.13) and replaced seven copies; two truncated, so a monster with an odd DEX below 10 now gets the correct initiative modifier when added, and a feat changing an odd CON below 10 adjusts HP correctly. The multiclass HP rule is on PHB p.163. Where existing code disagrees with the PHB (a multiclass dip gets the maximum die; Tough, Hill Dwarf and Draconic Bloodline HP; no minimum of 1 HP per level; the hard-coded maximum of 20; level-up checking only the primary class's prerequisites; Eldritch Knight and Arcane Trickster spells; long rests returning half of each class's dice), workstream C fixes it.

**Update (2026-11-03, R6 planning, approved):** planned for C. Each level records a `hitDieResult` using the fixed average, with the maximum die only at character level 1, so a multiclass dip gets the average (PHB p.163); rolled HP is out of scope **(choice)**. A higher HP maximum raises current HP by the same amount, and a lower one caps it **(choice)**. ASIs record the amount applied (older entries fall back to the amount requested) and must be +2 to one score or +1 to two, within the maximum (400 otherwise). Primal Champion is recorded at Barbarian 20. History also records multiclass proficiencies and expertise, so levelling down removes them **(choice)**. Levelling down removes the last entry, reverses only what it recorded, derives the stats again and syncs pools. Tough, Hill Dwarf and Draconic Bloodline are matched by name until R7 **(choice)**. The spell tables live in `rules` (R6 A), and R7 moves them to data files. V20 backfills `hitDieResult` with the values the old code used, and the `recompute-characters` job recalculates existing characters, as a dry run first **(choice)**.

**Update (2026-11-03, R6 workstream C):** C wires `rules.character` in. The new `character.internal.CharacterStats` is the single derivation of the Derived Stats: the proficiency bonus; the HP maximum, from each level's `hitDieResult` plus CON with Tough, Hill Dwarf and Draconic Bloodline, at least 1 per level; initiative; speed from the race; the ability scores and their Ability Score Maximums; the slot and Hit Dice maximums; each Spellcasting Class's numbers, with Warlocks flagged as Pact Magic (D164); and the main spell numbers for race and feat spells. After creation, the PUT, levelling and applied choices it writes the stored copies and reconciles the pools through `ResourcePoolService.reconciledPools`, which replaces `syncPoolsForCharacter` (D155, D165). A higher HP maximum raises current HP by as much, and a lower one caps it. `hpGained` now records what each level added to the maximum, as a record only **(choice)**. New records: `DerivedStats`; `ClassSpellcasting` and `AbilityScoreMaximums`, in the response; and `character.state.ProficienciesGained`. Creation no longer takes `hpMax`, `armourClass`, `initiativeBonus`, `speed`, `proficiencyBonus`, `spellSlots`, `spellSaveDc`, `spellAttackBonus` or `hitDice`; armour class starts at 10 + DEX, and a multiclass character whose class levels don't add up to its level answers 400. The PUT ignores the Derived Stats, the level and class fields, `features`, `levelHistory` and `spellcastingAbility`, and reads only each slot's used count, each class's remaining Hit Dice and each pool's current uses, until workstream D removes it. Level-up records the fixed-average `hitDieResult`, the die's maximum only at character level 1, so a multiclass dip's first level gets the average (PHB p.163). It checks the new class's prerequisites and every current class's, so a Fighter/Paladin with CHA 12 can't take a third class; asks Eldritch Knights and Arcane Tricksters for spells, up to the class's own highest spell level; and at Barbarian 20 adds Primal Champion's +4 STR and CON, up to 24. It no longer refills spent slots or Hit Dice, and spent pool uses stay spent. Tough adds 2 at every level, and the Hill Dwarf and Draconic Bloodline bonuses now count. An ASI must be +2 to one score or +1 to two, within the maximum, or it answers 400, and one that no level owes answers 409. An ASI goes on the latest level that owes it, as Expertise does, so levelling down reverses the right one. Levelling down removes the last entry and reverses only what it recorded: the latest copy of a repeated feature, its ASI and Expertise, and that level's multiclass proficiencies and ability increases. It then derives the stats again, capping current HP and spent Hit Dice, and syncs the pools. The creation wizard stops computing HP, DCs and Hit Dice, and AsiModal reads the server's maximums. Locally, after backups and dry runs, V20 gave 97 level entries on 13 characters a `hitDieResult`, and on every entry `hpGained` minus `hitDieResult` is the character's current CON modifier. The `recompute-characters` job then changed 6 of 13 characters, all soft-deleted test characters: no HP maximum, proficiency bonus, slot, main DC, speed or Hit Dice changed, initiative rose by 1 for four characters whose DEX increases never reached it, and 7 pools were added on 3 characters. The plan didn't predict the Superiority Dice: the existing formula gives 5 at Fighter 9, which is right, since a Battle Master gains a die at 7th level, so TestBM's pool goes from 0/0 to 5/5 and a TestFighter's from 4/4 to 5/5. TestPeace knows 5 Cleric cantrips where a level 9 Cleric knows 4, a warning that changes nothing. A second dry run found nothing to change. New tests: `CharacterStatsTest`, `LevelProgressionTest`, `CharacterServiceTest` with `CharacterFixture`, `RecomputeCharactersJobTest`, `ProgressionIT` and `SpellsTab.test.tsx`. The 49 cases of the deleted `LevelUpDownRoundTripTest` and `CharacterServiceBusinessTest` moved to `LevelProgressionTest` and `CharacterServiceTest`, whose Javadoc maps where each went. The backend now has 1,510 unit and 140 integration tests, and the frontend 535 tests in 61 files. Left for later: server-side rests (D); subclass and creation-time choices, which aren't recorded per level, so levelling down can't reverse them (F); and multiclass skill choices, which still aren't offered.

**Update (2026-11-03, R6 workstream E):** `CharacterStats` now works the Derived Stats out with the DM Adjustments too (D158). Ability adjustments come first: each score is the stored one plus its active STAT adjustments, clamped to 1–30, so it may pass the Ability Score Maximum. The modifiers, HP from CON, initiative from DEX and every spell number then use the adjusted scores, and so do multiclass prerequisites (`MulticlassValidator`), short-rest healing (`RestService`) and pool formulas (`ResourcePoolService`). The stored scores stay without adjustments **(choice)**, and the response shows them as `baseAbilityScores`. An ASI is checked against the Ability Score Maximum with those scores, and the ASI dialog raises them. An HP maximum adjustment is added after the levels' hit points, and the maximum stops at 1. A higher maximum raises current HP by as much, and a lower one caps it, as for levels. `hitPointMaximum` for a list of levels, which creation uses for each level's `hpGained`, leaves those adjustments out. Speed stops at 0. Feats count whether a level took one or the DM granted it: their speed, initiative and pools come from both, through `DmAdjustments.featsTaken`, and a granted Tough adds its HP per level through the feature it adds. `FeatEffectResolver` now removes only a feat's latest feature entry when it reverses one, so levelling down past the second of two Elemental Adepts keeps the first. In `DmAdjustmentIT`, CON +2 on a Wizard 3 took its HP maximum from 20 to 23 and current HP from 14 to 17, and −10 to the maximum capped current HP at 13.

**Update (2026-11-03, R6 workstream F):** a level's choices are now recorded on the level that owes them, through its Pending Choice (D159). C's rule that an ASI or Expertise goes on the latest level that owes one is replaced by the choice's own level: `CharacterUpdates.owedAsiLevel` and `owedExpertiseLevel` are gone, and the new helpers write to a named level (`historyIndex` and `recordChoices`). So a Fighter created at 8th level records its three ASIs on its 4th, 6th and 8th levels (D128), and a choice made late can't land on another level. Levelling down reverses more of what a level records: its subclass, Fighting Style, cantrips and spells, giving back a spell a swap replaced, as well as its ASI and Expertise, and the choices it still owes go with it. A Fighting Style's feature, and the features a subclass chosen later brings, are added to the `featuresGained` of the levels they come at, so they go with those levels. Each spell choice is capped at the highest spell level the class could learn at the choice's own class level, so levelling down stays consistent **(choice)**. A feat a race or background gives at creation counts as an ASI's feat does, through `DmAdjustments.featsTaken`: `CharacterStats` adds its speed, initiative and HP per level and builds its pool, and in `PendingChoiceServiceTest` a Tough taken at creation adds 2 at every level. An ASI is still +2 to one score or +1 to two within the maximum, checked now by `PendingChoiceService`, and it can't take a feat the character already has unless the feat is repeatable (D158). In the rehearsal on a copy of the local data, an ASI taken as Lucky, a backfilled ASI and a Sorcerer 5's spell choices, a swap of Shield for Chromatic Orb among them, were each reversed by levelling down. Left for later: a subclass chosen before F isn't recorded on its level, so levelling down past it doesn't remove it; spells sent at creation for levels above the 1st aren't recorded per level either; and multiclass skill choices still aren't offered.

**Update (2026-11-03, R6 review fixes):** the recompute-characters job and `CharacterStats` now share one rule for current hit points after the maximum changes, `CurrentHitPoints.afterMaximum`, so the job's dry run shows a dead character staying at 0, as the real run does. The job also skips, with a warning, any character whose sheet can't be worked out, instead of stopping.

**Update (2026-11-03, R7 planning):** Tough's `hpPerLevel` comes from the feat data, and Hill Dwarf's and the Draconic Bloodline's from new `effects` columns on races and subclasses. The spell and class tables move to `data/content/class-tables.json`, which `content` loads into `rules` records, and the frontend reads them from `GET /api/reference/class-tables`.

## D168: Typed JSON End-to-End

**Date:** 2026-11-01
**Status:** Accepted — implemented (encounter tables R5; character tables and the Write-back columns R6); dropping the unmapped character columns and typing the spell and monster template columns in R7

**Decision:**
- Every JSONB column maps to typed Java records through Hibernate's JSON support (`@JdbcTypeCode(SqlTypes.JSON)`). The API returns structured JSON rather than JSON inside strings, and the frontend never calls `JSON.parse` on API fields.
- Each concept has one shape. Spell slots are `{max, used}` everywhere, for both characters and participants.
- Each column is converted by a Flyway data migration that normalises existing rows, with a check before deploying that every row still loads.
- Typing happens area by area, as each area is rewritten, not as a separate pass.

**Rationale:** About 27 JSONB columns on `PlayerCharacter` and several on `EncounterParticipant` are plain strings, parsed by hand on both sides. Their shapes drift (`{total, used}` versus `{max, remaining}`, bridged by D107's conversion). Parse failures are swallowed, and invalid JSON reaches Postgres and comes back as a 500. D078 typed some fields inside the backend but stopped at the API.

**Trade-offs:** The API contract changes for every JSON field, so frontend parse sites and MSW mocks change, and data migrations touch existing rows. `{max, used}` was chosen over D107's suggested `{remaining, max}` because raising a maximum (on level-up, or D153's recalculation) then needs no change to `used`.

**Alternatives considered:**
- **Normalise into tables** — rejected: D009's reasons still hold.
- **Keep strings with stricter validation** — rejected: the types are lost at every boundary.

**Supersedes:** D010, D075, D107

**Amends:** D078 (extended to every column and to the API)

**Related:** D009, D160, D171

**Update (2026-11-02, R5 planning):** R5 types these participant fields: conditions, spell slots (`{max, used}`, pact slots keyed `pact_N`), resource pools, known spells and the Concentration Instance, plus the encounter's `monsters_in_lair`. Spell and monster templates are read into the rules module's Effect model where they are used; their columns get record types with R7's data files **(choice)**. Before V15 or V16 runs, the `preview-migration` maintenance job runs it inside a transaction that is always rolled back, prints every changed row before and after, and checks that each converted row loads. A backup comes first.

**Update (2026-11-02, R5 implementation):** V15 typed the participant columns (conditions, spell slots `{max, used}` with pact slots keyed `pact_N`, known spells and resource pools) and the encounter's `monsters_in_lair`; V16 added the Concentration Instance and condition IDs and end rules. `JsonColumnConfig` makes Hibernate use Spring's ObjectMapper, so unknown keys don't fail a load. The `preview-migration` job dry-ran V15, V16 and V17 against the local database before each was applied, and no row failed to load.

**Update (2026-11-02, R5 workstream G):** `monsters.action_templates` is read into the `MonsterTemplates` records where it is used, replacing the JSON walking in the monster services. No column changes; the column's own record type still comes with R7. A comparison over all 2,357 local templates parsed every one and found the 7,823 non-multiattack actions, spellbooks, pool counts and lair flags unchanged.

**Update (2026-11-03, R6 workstreams A and B):** workstream B typed every `player_characters` JSON column into records in `character.state`, and the character API carries structured JSON. V19 normalises the stored values: slots become `{max, used}`, hit dice move from `hit_dice_map` to a new `hit_dice` column keyed by class (`{dieSize, total, remaining}`), equipment names become entries `{name, quantity, description, itemId, equipped, attuned}`, and values of the wrong JSON type become NULL. Locally all 13 characters changed (hit dice on 13, slots on 5) and every row loads. `hit_dice_total`, `hit_dice_remaining`, `hit_dice_map`, `prepared_spells`, `attuned_items` and `equipped_items` are unmapped and go in R7; `feat_resources` stays mapped, because feats with uses still write it, until workstream D. `preview-migration` covers `player_characters`, and no longer counts a column added as NULL as a change.

**Update (2026-11-03, R6 workstream C):** C adds to the level history's records. A `LevelHistoryEntry` gains `hitDieResult`, `proficienciesGained` (the new `ProficienciesGained`: the armour, weapon and tool proficiencies a multiclass level added) and `abilityIncreases` (Primal Champion's), `LevelChoices` gains `expertise`, and an `AbilityScoreImprovement` gains `applied`, its increases as actually applied; a field an entry doesn't have is left out. An improvement recorded before R6 has no `applied`, so levelling down takes off the amount requested. Before V20 was applied, `preview-migration` dry-ran it: 276 rows examined, 13 characters to change, and none fails to load (D167). The response's new `spellcasting` and `abilityScoreMaximums` are typed records, `ClassSpellcasting` and `AbilityScoreMaximums`, worked out when read and never stored (D164).

**Update (2026-11-03, R6 workstream D):** `feat_resources` joins the unmapped columns. `PlayerCharacter` and the API drop `featResources`, and a feat's limited uses are a `feat:<feat>` pool in `resource_pools`, built from the `FeatResourceEntry` the level history records in the feat's `AppliedFeatEffects` (D158). The column stays until R7 drops it, and no local character had data in it. `CharacterJsonMappingIT` checks that it is no longer written. V21 adds `player_characters.version` (`BIGINT NOT NULL DEFAULT 0`), the `@Version` behind the row lock (D158). Before V21 was applied, `preview-migration` dry-ran it: 276 rows examined, 13 characters to change (`version` → 0), and none fails to load. A preview loads the characters with today's mappings, which now need `version`, so `PreviewMigrationJobIT` previews V21 in place of V19 and V20, whose apply tests stay. `PUT /api/characters/{id}/inventory` takes the `InventoryItem` and `Currency` records as they are stored.

**Update (2026-11-03, R6 workstream E):** V22 adds `player_characters.dm_adjustments` (`JSONB NOT NULL DEFAULT '[]'`), typed as the new `character.state.DmAdjustment` and kept oldest first: `{id, kind, target, amount, defence, featId, appliedEffects, reason, addedBy, addedAt, removedBy, removedAt}`, with the fields a kind doesn't use left out and times as ISO text (D158). A removed entry keeps its place as the record. Before V22 was applied, `preview-migration` dry-ran it: 276 rows examined, 13 characters to change (`dm_adjustments` → `[]`), and none fails to load. A preview loads the characters with today's mappings, which now need `dm_adjustments`, so `PreviewMigrationJobIT` previews and applies V22, and V21's preview gave way; V21's apply test stays. `CharacterJsonMappingIT` round-trips an adjustment of each kind, and a character saved without any has `[]`, never null. The response's new `baseAbilityScores` is a typed record, `BaseAbilityScores`, read from the stored scores, and a FEAT request takes the `AsiChoice` an ASI's feat takes.

**Update (2026-11-03, R6 workstream F):** V23 adds `player_characters.pending_choices` (`JSONB NOT NULL DEFAULT '[]'`), typed as the new `character.state.PendingChoice` and kept oldest level first: `{id, type, source, characterLevel, classId, className, classLevel, count, options, maxSpellLevel, swap}`, with the fields a type doesn't use left out (D159). A race's or background's feat has no class. `LevelChoices` gains `subclass`, `fightingStyle`, `cantrips`, `spells` and `feats`, each left out until it is made, with three new records: `SubclassChosen` `{classId, subclassId, subclassName}`, `SpellsLearned` `{learned, replaced, replacement}`, whose `replaced` is the `KnownSpell` as the sheet had it, and `FeatTaken` `{featName, featId, appliedEffects}`. `LevelHistoryEntry` gains `withFeaturesGained`, because a Fighting Style or a later subclass adds to a level's `featuresGained`. Before V23 was applied, `preview-migration` dry-ran it: 276 rows examined, 13 characters to change (`pending_choices` → `[]`), and none fails to load. A preview loads the characters with today's mappings, which now need `pending_choices`, so `PreviewMigrationJobIT` previews and applies V23, and V22's preview gave way; V22's apply test stays. `CharacterJsonMappingIT` round-trips a Pending Choice of each shape and the new level choices, and a character saved without any has `[]`, never null. `ApplyChoicesRequest` gains a required `choiceId`, `feat` (an `AsiChoice`), `fightingStyle`, `spells` and `swap` (`SpellSwap`, `{replace, with}`) and loses `classId`. `LevelUpResponse` is just `{character, newFeatures}`, so its nested `PendingChoices` record is gone.

**Update (2026-11-03, R6 workstream G):** V24 adds `encounter_participants.joined_with` (JSONB), typed as the new `character.state.CombatTotals` `{hpCurrent, hpTemp, slotsUsed, poolsCurrentUses}`. `slotsUsed` is keyed `"1"` to `"9"` and `pact_N`, as spell slots are, and `poolsCurrentUses` by pool ID; a key without a number counts as absent (D166). It is null for monsters and for participants of encounters completed before R6. V24 also adds two plain columns, `encounters.results_applied` (BOOLEAN, null until an encounter completes) and `player_characters.is_dead` (BOOLEAN NOT NULL DEFAULT FALSE). For Active and Paused encounters V24 builds `joined_with` from the sheets in SQL, as `CombatTotals.of` does: HP kept between 0 and the maximum, temp HP at least 0, each slot's used count between 0 and its maximum, and the first of two pools with one ID. The file has no DO blocks. Before V24 was applied, `preview-migration` dry-ran it: 276 rows examined, 16 to change (3 participants' `joined_with`, and `is_dead` → false on 13 characters), and none fails to load. The encounters' `results_applied` arrives as NULL, which isn't counted. A preview loads rows with today's entities, which now map V24's columns, so `PreviewMigrationJobIT` previews and applies V24 against encounters, participants and characters, and the previews of V17 and V23 gave way; their apply tests stay. `EncounterJsonMappingIT` round-trips `joined_with`, keyed as slots and pools are, and `results_applied`. The End dialog's preview is a typed record too, `CombatResultPreview`, with `SlotChange` and `PoolChange`, and `POST end` takes `EndEncounterRequest`, `{applyResults}`.

**Update (2026-11-03, R7 planning):** V29 drops `hit_dice_total`, `hit_dice_remaining`, `hit_dice_map`, `feat_resources`, `prepared_spells`, `attuned_items` and `equipped_items`, after their non-null values are archived. `monsters.action_templates` and `spells.effect_template` map to records, following question F3.

## D169: Game Content as Data Files with Idempotent Seeding

**Date:** 2026-11-01
**Status:** Accepted — feat reseeding fix implemented (R1); the rest planned (R7)

**Decision:**
- **Data files.** Game data we write ourselves lives in JSON files under `backend/src/main/resources/data/`, and the seeders become generic loaders. That covers:
  - race traits
  - class feature tables (ASI levels, expertise levels, subclass levels)
  - spell slot tables
  - resource pool definitions
  - monster action templates
  - spell effect templates
- **Stable IDs.** Loaders update rows by natural key (name and source) and never delete and reload, so row IDs stay the same.
- **Change detection.** Each file's content hash is stored in a new content version table, and a loader re-runs only when its file changes. This replaces "null the column and restart" re-seeding (D144) and migrations that null columns to force re-seeding (V4, V6).
- **Pool sizes.** Resource pool sizes use level tables wherever the PHB uses level thresholds, and a pool's reset type can change by level. A proper expression parser handles the genuine formulas. It supports operator precedence, brackets, minus signs, `min`, `max`, `floor`, `ceil`, dice and `NdM>=X`.
- **Pool corrections:**
  - Battle Master superiority dice 4, 5 and 6 at Fighter 3, 7 and 15 (die size d8, d10 and d12 at 3, 10 and 18)
  - Bardic Inspiration uses `max(1, CHA mod)` and recharges on a short rest from Bard 5
  - Cleric Channel Divinity 1, 2 and 3 uses at levels 2, 6 and 18
  - Action Surge 2 uses at level 17

  Every existing pool definition is re-checked against the PHB.
- **R1 fix.** Feats without effect keys get an empty effect list instead of `null`; the `null` is what makes `FeatSeeder` delete and reload on every boot. `featId` values in level history are re-linked by feat name.
- **Clean-up.** Unused 5etools `adventure/` and `book/` data (about 60 MB) comes out of the build. `DataSeeder` stops creating extensions and indexes that V0 already creates.
- **Proof.** The move is checked by snapshotting the reference tables before and after and comparing them.

**Rationale:**
- Most seeders skip when `count() > 0`, so data fixes never reach existing databases.
- `FeatSeeder` deletes and reseeds on every boot, changing every feat ID.
- `RaceSeeder` (1,142 lines) and `ResourcePoolSeeder` (581 lines) are game data written as Java.
- Caster tables are duplicated in the seeder and `SpellSlotCalculator`.
- `ExpressionEvaluator` works strictly left to right, which breaks the Battle Master formula at level 14, gives `2*-1 = 1`, and has no `max`.

**Trade-offs:** Corrected pool definitions change existing characters' pool maximums at their next sync (current uses are capped at the new maximum). The seeder diff is large, so equivalence has to be shown by the table comparison.

**Alternatives considered:**
- **Flyway Java migrations for reference data** — rejected: ties game data edits to schema versions.
- **Keep the formulas and only fix the evaluator** — rejected: level tables say exactly what the PHB says, while formulas hide off-by-one errors.

**Partially supersedes:** D040 (the tables move from Java arrays into a data file), D125 (pool sizing), D139 and D144 (the re-seed workflow)

**Related:** D045, D127, D146, D153, [[Content]], [[Resource Pools]]

**Update (2026-11-03, R6 click-through):** `CharacterClassSeeder` now brings stored classes up to date at every start, not only when their multiclass data is missing. Its first correction is the Fighter's `primary_ability`: the either-or prerequisite was seeded as "OR" and now reads "STR or DEX", while the Monk, Paladin and Ranger keep "and".

**Update (2026-11-03, R7 planning):** `content_versions` (V25) holds one row per loader: its name, the SHA-256 of its files plus the loader's code version, the row count and the load time. A loader runs again when that hash changes or a loader it depends on ran. Rows missing from the data are kept and logged **(choice)**. `content.mode` (`apply`, `preview` or `off`) keeps maintenance starts from loading content. The proof is a by-ID diff on the restored copy, plus a by-natural-key diff between a database seeded from scratch by `1a0f53c` and one loaded by the new code.

## D170: Indexes Managed Only by Flyway

**Date:** 2026-11-01
**Status:** Accepted — implemented (R2)

**Decision:** All indexes are created in Flyway migrations, and JPA `@Index` annotations are removed. A new migration adds indexes on the foreign keys used in lookups and joins. These include at least:

- `player_characters(user_id)`
- `player_characters(campaign_id)`
- `encounter_participants(encounter_id)`
- `encounter_participants(character_id)`
- `combat_logs(encounter_id)`

The full list is settled during R2 planning.

**Rationale:** D080 and D089 added these indexes through `@Index` with `ddl-auto: update`. Since D097 switched to Flyway with `ddl-auto: validate`, Hibernate no longer creates them. The migrations contain only two indexes: the monster-name trigram index (V0) and the `spells.classes` GIN index (V1). So any database built by Flyway, including production, has no foreign-key indexes.

**Trade-offs:** A brief lock while the indexes are built (the tables are small).

**Alternatives considered:**
- **Keep the annotations as documentation** — rejected: they suggest indexes that don't exist.

**Supersedes:** D080, D089

**Related:** D097, D121

**Update (2026-11-02, R2 planning):** the list adds `encounters(campaign_id)` and `campaign_members(user_id)`, both used by repository lookups. `campaign_members(campaign_id)` is covered by its unique constraint. Not indexed: `encounter_participants(monster_id)` and `campaigns(dm_user_id)`, because no query filters on them, and the foreign keys of the small reference tables. V12 uses `IF NOT EXISTS`, since databases Hibernate built before D097 may already have the first five. An integration test fails on any foreign key without an index unless it is on that allow-list.

## D171: Frontend Architecture — Query Cache, Generated Types, Shared Components

**Date:** 2026-11-01
**Status:** Accepted — foundations implemented (R4); consolidation in R8

**Decision:**
- **Server data:** TanStack Query.
  - WebSocket messages write straight into the query cache, and mutation responses update it.
  - Actions no longer trigger an extra GET.
  - The combat log arrives over the WebSocket instead of being polled every 3 seconds.
- **API contract:** the springdoc OpenAPI spec is the source of truth. TypeScript types are generated from it (openapi-typescript), replacing hand-written copies, and `api-reference.md` becomes an overview that points to the spec.
- **UI primitives:** shared components:
  - Button
  - IconButton (requires a label)
  - Input
  - Modal (keeps focus inside, closes on Escape, accessible markup)
- **Bundles:** each route's code loads separately.
- **Linting:** oxlint adds the exhaustive-deps and unused-variable rules.
- **R8 consolidation:**
  - shared encounter components (HP bar, death saves, conditions, combat log, attack form, participant row) used by both session pages
  - one `SpellPicker` replacing the four copies in `SpellsStep` and the one in `SpellsTab`
  - wizard state in one reducer with derived values, instead of effects that reset other state
  - feats parsed once, on the server
  - one choose-targets → confirm → result flow for both spell pop-ups

**Rationale:**
- Each page manages its own fetching, error and loading state; there are 62 `.then()` calls with no `.catch`.
- A combat action updates state in three different ways.
- Types drift from the backend; for example, participant `className` is read through `as any`.
- About 350 lines are duplicated between the DM and player session pages.
- The single 762 KB bundle triggers Vite's size warning.

**Trade-offs:** Every page's data loading is touched, one page at a time. Generated types will turn existing mismatches into compile errors.

**Alternatives considered:**
- **Redux Toolkit / RTK Query** — rejected: heavier than needed. TanStack Query covers server data, and local state stays in components.
- **Keep hand-written types** — rejected: the drift is already causing bugs.

**Amends:** D081 and D087 (the shared rules utilities shrink as rules move to the server), D088, D096, D100

**Related:** D004, D168, D172

**Update (2026-11-02, R4 planning):** springdoc writes OpenAPI 3.0, and a property is nullable unless marked required. The spec and generated types are committed under `frontend/src/api/generated/`, and CI fails when either is stale. After every change the server publishes the full combat log per view, on `/topic/encounter/{id}/log` and `/dm-log`. A mutation's response updates the cache unless a WebSocket message arrived meanwhile. The wizard, `SpellsTab` and `FeatPicker` keep their loading code until R8, and exhaustive-deps only warns there.

**Update (2026-11-03, R5 follow-ups):** participants carry a read-only `characterClass` in both views, so player characters show their class colour on the DM's and the players' pages.

## D172: Testing Strategy — Three Tiers and Golden Rules Tables

**Date:** 2026-11-01
**Status:** Accepted — implemented (R2 tiers); extended in every phase

**Decision:**
- **Unit tier:** the `rules` module and services, with plain JUnit and Mockito, as today.
- **Integration tier:** a real PostgreSQL database via Testcontainers, covering:
  - Flyway from an empty database, with schema validation
  - repository and custom search queries
  - the HTTP security cases (401, 403, 404, 409) through the full stack
  - WebSocket subscription checks

  It runs as a separate Gradle task so `./gradlew test` stays fast; CI runs both.
- **Golden rules tables:** spell slots for every class at levels 1–20, proficiency by level and by CR, pool sizes by level, and D161's damage and dying table.
- **Frontend:** Vitest, with MSW set to `onUnhandledRequest: 'error'` and a mock STOMP client for the session pages. New tests cover the shared encounter components, refresh on 401 and the wizard reducer.
- **D140:** its M12 coverage target was never met, and the combat pipeline's tests (D160) meet it.

**Rationale:**
- All 318 backend tests are Mockito unit tests. Nothing exercises migrations, custom SQL, the security filter chain, controllers or STOMP.
- `MonsterActionResolverEngine` and `MonsterSpellCastService` have no tests.
- MSW's `bypass` mode lets mocks drift from the real API silently; two mock paths already differ from it.

**Trade-offs:** Docker must be running for the integration tier. Some existing frontend tests will fail once strict MSW mode exposes requests they weren't mocking.

**Alternatives considered:**
- **An H2 in-memory database** — rejected: no JSONB, `pg_trgm` or GIN support.

**Amends:** D085, D102, D126

**Related:** D140, D154, D161, D170

**Update (2026-11-02, R2 planning):** each golden table lands in its own phase: spell slots by caster type in R1 (done); spell slots for every class, and proficiency by level and by CR, in R2; pool sizes in R7, written before D169's level tables because today's formulas are known to be wrong; D161's damage and dying table in R5, written before the `EffectApplier`. Tests for the wizard reducer and the R8 encounter components come in R8. Integration tests assert today's HTTP and STOMP behaviour, and R3 flips the cases that D155–D157 change. `integrationTest` stops at once with a clear message when Docker isn't running, and it isn't part of `./gradlew build`.

**Update (2026-11-03, R7 planning):** `PoolSizesGoldenTest` is written in A against today's formulas, with the known-wrong cases listed, and D makes them pass.

## D173: Selectable Themes and a More Readable Tourmaline

**Date:** 2026-11-01
**Status:** Accepted — implemented (before R2)

**Decision:**
- **Four themes, chosen per device:** Tourmaline (the default), Parchment & Ink, Tavern by Candlelight (dark) and Adventurer's Handbook. The choice is saved in the browser and applied before the first paint, so pages never flash the wrong theme. Nothing is stored on the server.
- **Tourmaline readability pass:**
  - Crimson Pro replaces Cormorant Garamond for body text; Cinzel stays for headings.
  - Ink #1C1917 replaces pure black (15.6:1 on the page).
  - Faint text is #57524C (6.9:1, was 4.6:1), and rules are darker.
  - The nav keeps its gemstone line but drops the rainbow wash.
- **Larger text in every theme:** every hard-coded 9–15px size in the components goes up 2px, with line height 1.55, in one mechanical pass.
- **Switcher:** an account menu at the right of the top bar replaces the Switch Role and Sign Out buttons. It holds Theme, Switch Role and Sign Out.
- **Implementation:**
  - Each theme is a block of D124's tokens in `index.css`, keyed by `data-theme` on `<html>`.
  - Colours that bypass the tokens, such as the nav gradient, become theme tokens.
  - The prototype switcher is removed and kept on a throwaway branch.

**Rationale:** User feedback that Tourmaline is hard to read: a thin body font, 9–11px labels, and pure black beside faint greys (4.6:1). Extra themes cost little once every colour comes from tokens.

**Trade-offs:** Larger text can wrap in tight spots such as the nav, tables and chips. Switch Role and Sign Out move one click deeper. A theme chosen on one device doesn't follow the account to another. The dark theme needs a sweep for hard-coded light colours.

**Alternatives considered:**
- **A labelled theme dropdown in the top bar** — rejected: the DM bar already holds 10 items.
- **A floating corner button** — rejected: it covers page content on every screen, including the wizard's Next button on phones.
- **Saving the theme to the account** — deferred: needs a database column and API change, and per-device suits players who want a dark theme on a phone at the table.

**Amends:** D124

**Update (2026-11-03, R5 follow-ups):** the Crit and Pause buttons use the paladin gold with card-coloured text, which meets WCAG AA in every theme: Tourmaline 4.71:1 (was 2.81), Parchment 4.57 (2.73), Tavern 7.01 (8.89) and Handbook 4.68 (2.79). Still below AA: the player's Adv toggle (about 2.4:1) and the Paused banner and chip in the light themes (about 2.2–2.4:1).

**Update (2026-11-03, contrast fix):** the player's Adv toggle now uses card-coloured text on the buff background (5.90:1 to 6.82:1), and the Paused banner and chip use the paladin gold with card-coloured text (4.57:1 to 7.00:1), so every control the R5 follow-ups listed below AA now meets it. The Repeat Save prompt still uses the faded monk tint (about 2.2:1 to 2.4:1 in the light themes), and the selected Norm toggle is 4.48:1 in Tavern.

**Update (2026-11-03, R6 review fixes):** three places put monk-gold text on the monk tint, at 2.83:1 in Tourmaline, Parchment and Handbook: the pending-choices banner, the DM's "N choices pending" chip and the wizard's "Choices made on your sheet" box. Their text is now ink on the tint: 16.86:1 in Tourmaline, 17.53:1 in Parchment, 10.11:1 in Tavern and 16.61:1 in Handbook.
