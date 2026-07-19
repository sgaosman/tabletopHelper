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

**Decision:** Defer Milestone 6 (Polish, Mobile & Deployment) until after M13. The encounter session UI will be substantially rebuilt by M10–M12 (encounter spellcasting, monster actions, enhanced action economy), making early polish work throwaway.

**Rationale:** Responsive layouts, error toasts, and loading states all depend on the UI surface they wrap. With the combat UI gaining spell casting panels, monster action panels, reaction prompts, and action economy indicators in M10–M12, any mobile layout work done now would need to be redone. Polish once after the UI stabilises, not before.

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

**Rationale:** With the introduction of player combat permissions (M5) and encounter spellcasting (M10), multiple users can submit combat actions simultaneously. The current REST-then-broadcast pattern has no explicit locking and could produce race conditions (e.g., two damage applications reading the same HP, both subtracting, resulting in only one being applied). Optimistic locking is cheap insurance — with turn-based combat, actual conflicts will be extremely rare, but the protection should be there.

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
