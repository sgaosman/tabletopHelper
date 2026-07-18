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
