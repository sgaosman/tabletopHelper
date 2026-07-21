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
**Status:** Accepted

**Decision:** Spell slot progression tables (full caster, half caster, pact magic, artificer) are hardcoded as static arrays in both CharacterClassSeeder (backend) and SpellSlotCalculator rather than derived from 5etools data. Multiclass spell slot calculation follows PHB 2014 rules: sum effective caster levels (full=level, half=level/2 round down, artificer=level/2 round up), look up the full caster table for the combined level. Warlock pact slots are tracked separately.

**Rationale:** The 5etools class JSON does not include spell slot tables in a directly parseable format — they are embedded in classTableGroups as display-only table rows. Hardcoding the 4 progression tables (20 levels each) is more reliable and maintainable than parsing display tables. The PHB tables are fixed constants that will never change for 2014 rules.

**Trade-offs:** If a new caster progression type were added (it won't be for 2014 rules), it would require a code change. Acceptable.

## D041: Legacy Character Cleanup on Startup

**Date:** 2026-07-19
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

**Decision:** Multiclass character creation sends a `multiclassClassEntries` JSON array to the server, which builds the full multi-class progression via `LevelUpCalculator.buildMulticlassProgression()`. ASI choices are applied post-creation by showing `AsiModal` sequentially for each pending ASI level, calling the existing `applyChoices` endpoint.

**Rationale:** Integrating ASI choices into the creation wizard would require a parallel version of `AsiModal` that works without a persisted character (no ID for API calls, no ability to save feats). The post-creation approach reuses the existing `AsiModal` and `applyChoices` endpoint unchanged. The `findNextAsiEntry()` helper ensures each `applyChoices` call records on the correct level history entry by scanning forward for the first ASI-eligible entry without a recorded choice.

**Trade-offs:** The character exists briefly without its ASI bonuses applied. If the user closes the browser during the ASI flow, the character has correct HP and features but missing ASI choices — they can apply them later via the level-up flow. The sequential modal UX is slightly less integrated than an inline wizard step, but avoids significant code duplication.

## D058: ASI History Recording Targets First Unrecorded ASI Level

**Date:** 2026-07-19
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

**Decision:** Frontend parsing functions for `@JsonRawValue` JSONB fields (`abilityScoreIncrease`, `effects`, `grantsFeatures`, `prerequisite`) must check `typeof value === 'string'` before calling `JSON.parse()`. Jackson's `@JsonRawValue` outputs raw JSON without quoting, so the browser's JSON parser converts these to native JS objects/arrays before the frontend code runs.

**Rationale:** `JSON.parse()` on an already-parsed array coerces it to `"[object Object]"` and throws SyntaxError. The catch block silently returned `null`, which caused the AsiModal's ability score choice UI to never render for feats like Fey Teleportation. The `parseFeatOptions` function already had this guard; `parseAbilityScoreIncrease` and `parseFeatEffects` did not.

**Trade-offs:** None. This is a pure bug fix. All `@JsonRawValue` fields should use this pattern going forward.

## D076: Replace Silent Exception Swallowing with Logging

**Date:** 2026-07-20
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

**Decision:** Add `@Valid` to the `updateCharacter` endpoint and Jakarta validation constraints on `CharacterUpdateRequest` fields: `@Min/@Max` on level (1-20), ability scores (1-30), HP, AC, speed, proficiency bonus; `@Size(max=100)` on name.

**Rationale:** Previously, any value could be submitted via the API, potentially creating invalid game state (negative HP max, level 0, etc.).

**Trade-offs:** May reject legitimate edge cases (ability scores >30 from magic items). The constraint is on the API input, not the stored value, so DM-applied effects can bypass this if needed.

## D083: Short Rest Hit Dice Spending and Warlock Pact Slot Reset

**Date:** 2026-07-20
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

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
**Status:** Accepted

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
