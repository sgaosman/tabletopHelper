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
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

**Decision:** Attack rolls (d20 + modifier vs AC) and damage dice are rolled server-side using `ThreadLocalRandom`. The DM inputs the attack bonus and damage dice expression (e.g. "2d6+3"); the server handles the full flow: roll d20, compare vs AC, roll damage on hit, apply damage through the existing damage pipeline.

**Rationale:** Server-side rolling ensures consistency (single source of truth for combat state), prevents tampering, and keeps the attack→hit→damage→HP-update flow atomic in a single transaction. The `DiceRoller` utility parses `NdS+M` expressions and handles critical hits (doubled dice count). Advantage/disadvantage is a simple boolean toggle.

**Trade-offs:** Players don't see real dice or rolling animations — all results appear in the combat log. The DM must manually input attack bonus and damage dice per attack (no auto-lookup from monster stat blocks). Both could be improved in M6: client-side dice animations synced to server results, and auto-populating attack parameters from monster action data.

## D023: Condition Duration as Object Array with Auto-Expiry

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Changed `activeConditions` from a string array (`["blinded","prone"]`) to an object array (`[{"name":"blinded","duration":3,"appliedRound":1}]`). Conditions with a non-null `duration` are automatically removed at the start of the affected creature's turn when `currentRound - appliedRound >= duration`.

**Rationale:** D&D 5e conditions frequently have durations ("until the end of your next turn", "for 1 minute"). Tracking this manually is error-prone during hectic combat. The round-based model is a simplification — it doesn't distinguish "start of turn" vs "end of turn" expiry, but covers the vast majority of cases. The DM can always manually remove/re-add conditions for edge cases.

**Trade-offs:** Backward-incompatible format change for `activeConditions` JSONB. Mitigated by a fallback parser that auto-converts legacy string arrays to the new format on read. No data migration needed since existing encounters are unlikely to be in active combat during the upgrade.

## D024: Spell Slot Tracking via Encounter Participant Copy

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Added `spell_slots_current` JSONB field to `encounter_participants`, auto-populated from the character's `spell_slots` when a player is added to an encounter. Slots are tracked per-encounter independently of the character sheet.

**Rationale:** Spell slots are encounter-scoped resources — a character might have different remaining slots across multiple encounters, and we don't want combat to mutate the permanent character record. The copy-on-join pattern mirrors how HP is already handled (copied from character, tracked on participant). The `{level: {max, remaining}}` format is compact and supports all 9 spell levels.

**Trade-offs:** Slot state diverges from the character sheet immediately after the encounter starts. This is intentional — the character sheet represents the character's base state, and encounter participants represent their in-combat state. Future improvement: sync remaining slots back to the character sheet when an encounter ends (e.g. for between-encounter resource management).

## D025: Unconscious Combat Mechanics (PHB Faithful)

**Date:** 2026-07-18
**Status:** Accepted

**Decision:** Implemented PHB-faithful combat rules for unconscious/downed characters: attacks auto-hit (no d20 roll), damage causes death save failures (1 normal, 2 on crit), massive damage (>= max HP) causes instant death. Added `forceCrit` field to attack rolls for the DM to manually mark within-5-feet melee attacks as crits (PHB Unconscious condition, pg.292). Frontend defaults to advantage when targeting downed PCs. Downed PCs can also receive direct damage and conditions.

**Rationale:** The app has no map or distance tracking, so the within-5-feet auto-crit rule from the Unconscious condition cannot be determined automatically. A manual `forceCrit` toggle lets the DM and players decide when it applies, staying faithful to the rules without requiring a grid.

**Trade-offs:** `forceCrit` overrides a natural 1 (turns it into a hit), which is technically non-RAW but makes sense since forced crits represent the DM explicitly deciding the attack is a crit regardless of the d20 roll.

## D026: Resurrection via Healing

**Date:** 2026-07-18
**Status:** Accepted

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
