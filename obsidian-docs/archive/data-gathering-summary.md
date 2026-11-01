# Data Gathering Summary -- M7 Task 6 Validation Report

Generated: 2026-07-18 (updated after second review pass)

## Task Completion Status

| Task | Description | Status | Output |
|---|---|---|---|
| 1 | Spell Effect Structured Data (Levels 0–3) | Complete | `spell-effect-definitions.json` (288 spells, 475 KB) |
| 2 | Class Feature Analysis | Complete | `class-feature-analysis.md` (13 classes, 117 subclasses, 416 features) |
| 3 | Race Trait Analysis | Complete | `race-trait-analysis.md` (134 races, 749 traits) |
| 4 | Item Effect Structured Data | Complete | `item-effect-definitions.json` (104 items, 93 KB) |
| 5 | Monster Action Structured Data | Complete | `monster-action-definitions.json` (2,357 monsters, 5.3 MB) |
| 6 | Validation Summary | Complete | This document |

All 6 tasks complete. No implementation code, schema changes, or database modifications were made.

## Output File Locations

### JSON Data Files (for future seeding/loading)
- `backend/src/main/resources/data/spell-effects/spell-effect-definitions.json`
- `backend/src/main/resources/data/item-effects/item-effect-definitions.json`
- `backend/src/main/resources/data/monster-actions/monster-action-definitions.json`

### Analysis Documents (for human review)
- `docs/spell-effect-review.md`
- `docs/class-feature-analysis.md`
- `docs/race-trait-analysis.md`
- `docs/item-effect-review.md`
- `docs/monster-action-review.md`
- `docs/data-gathering-summary.md` (this document)

## Cross-Task Statistics

### Total Data Volume

| Category | Entries | File Size |
|---|---|---|
| Spell effect definitions | 288 | 494 KB |
| Item effect definitions | 104 | 97 KB |
| Monster action definitions | 2,357 | 5,399 KB |
| **Total JSON data** | **2,749** | **5,990 KB** |

### Automation Coverage (updated after second pass)

| Dataset | Fully Automatable | Manual Resolution | Auto % |
|---|---|---|---|
| Spells (levels 0-3) | 184 | 104 | 63.9% |
| Items | 74 | 30 | 71.2% |
| Monster actions | 6,542 | 834 | 88.7% |

Spells have the lowest automation rate because many involve complex multi-step effects (summoning, terrain creation, charm with ongoing saves). The `requiresManualResolution` flag ensures these fall back to DM adjudication rather than producing incorrect results.

## Task 1: Spell Effect Definitions — Detail

**288 spells** across cantrips through 3rd level.

### By Level
| Level | Count |
|---|---|
| Cantrip (0) | 46 |
| 1st | 79 |
| 2nd | 88 |
| 3rd | 75 |

### By Pattern Category
| Pattern | Count | Description |
|---|---|---|
| UTILITY | 81 | Non-combat or indirect effects |
| COMPLEX | 60 | Multi-step or conditional effects |
| BUFF_NO_ROLL | 31 | Buffs without attack/save |
| SAVE_DAMAGE | 30 | Save for damage |
| SAVE_CONDITION | 24 | Save or suffer condition |
| ATTACK_DAMAGE | 19 | Spell attack roll for damage |
| SELF_BUFF | 16 | Self-targeting buffs |
| SUMMON | 10 | Creature summoning |
| SAVE_DAMAGE_AND_CONDITION | 10 | Save for both damage and condition |
| HEAL | 6 | Hit point restoration |
| AUTO_DAMAGE | 1 | Automatic damage (no roll) |

### By Delivery Method
| Method | Count |
|---|---|
| SAVING_THROW | 99 |
| NONE | 69 |
| AUTO_HIT | 53 |
| SELF | 42 |
| SPELL_ATTACK | 25 |

### Data Quality Notes (updated after second pass)
- 104 spells (36.1%) flagged `requiresManualResolution` (down from 105 -- Eldritch Blast now automatable)
- Upcast scaling captured where applicable (extra dice, extra targets, extended duration)
- All 288 spells have `patternCategory`, `deliveryMethod`, `spellLevel`, and `classes` populated
- Schema extensions added: projectileScaling (3), scalingInterval (2), reactionTrigger (7), saveToEndEachTurn (10), conditionalDamage (3), grantsAdvantage/imposesDisadvantage (6)
- Conditions use the standard 5e set: blinded, charmed, deafened, frightened, grappled, incapacitated, paralysed, petrified, poisoned, prone, restrained, stunned, unconscious

## Task 2: Class Feature Analysis — Detail

**13 classes, 117 subclasses, 416 features** categorised.

### By Category
| Category | Count | Description |
|---|---|---|
| COMBAT_ACTIVE | 144 | Activated abilities used in combat |
| RESOURCE | 123 | Features with per-rest charges |
| COMBAT_PASSIVE | 108 | Always-on combat modifiers |
| SPELLCASTING | 67 | Spell slot/known/prepared features |
| FLAVOUR | 54 | Roleplay/exploration features |
| COMBAT_MODIFIER | 13 | Conditional combat bonuses |

### Implementation Priority
- COMBAT_ACTIVE and COMBAT_PASSIVE features (252 total) are the highest priority for M16 (Class Feature Automation)
- RESOURCE features (123) need per-rest tracking, which depends on M15 (Short/Long Rest System)
- SPELLCASTING features (67) define slot tables and known/prepared spell counts, needed for M9 (Character Builder)

## Task 3: Race Trait Analysis — Detail

**134 races, 749 traits** categorised.

### By Category
| Category | Count |
|---|---|
| PROFICIENCY | 160 |
| STAT_BONUS | 117 |
| RESISTANCE | 92 |
| FLAVOUR | 88 |
| COMBAT_ACTIVE | 86 |
| MOVEMENT | 73 |
| COMBAT_PASSIVE | 69 |
| SENSE | 64 |

### Implementation Priority
- STAT_BONUS (117) and PROFICIENCY (160) traits are needed for M9 (Character Builder) — they affect ability scores and skill proficiencies
- RESISTANCE (92) traits affect damage calculation, needed for M10/M11
- COMBAT_ACTIVE (86) traits are per-rest abilities, depends on M15

## Task 4: Item Effect Definitions — Detail

**104 combat-relevant items** from the DMG and common supplements.

### By Type
| Type | Count |
|---|---|
| Potion | 37 |
| Wondrous Item | 22 |
| Staff | 13 |
| Scroll | 12 |
| Wand | 11 |
| Weapon | 4 |
| Oil | 2 |
| Rod | 2 |
| Ring | 1 |

### Data Quality Notes (updated after second pass)
- 74 items (71.2%) fully automatable
- 30 items (28.8%) require manual resolution (complex effects, DM-decided outcomes)
- Potions are the most automatable category (healing potions, resistance potions)
- Scrolls embed spell effects directly (no external spellReference)
- Staff of Frost/Power Cone of Cold save fixed (CON to DEX)
- Wand of Entangle AOE fixed (sphere to square)
- Wand of Fear condition fixed ("commanded" to "frightened")
- 10 save DC strings normalised to "CASTER"
- 7 items now have additionalChargesForUpcast flag

## Task 5: Monster Action Definitions — Detail

**2,357 monsters** from 88 source books.

### By CR Range
| CR Range | Count | % |
|---|---|---|
| CR 0–2 | 893 | 37.9% |
| CR 3–5 | 602 | 25.5% |
| CR 6–10 | 517 | 21.9% |
| CR 11–15 | 99 | 4.2% |
| CR 16–20 | 90 | 3.8% |
| CR 21+ | 156 | 6.6% |

### Action Breakdown
| Type | Count |
|---|---|
| Standard actions | 6,474 |
| Legendary actions | 902 |
| Reactions | 339 |
| Traits | 4,439 |

### Special Features
| Feature | Monsters |
|---|---|
| Spellcasting | 768 |
| Legendary actions | 311 |
| Legendary resistance | 336 |
| Lair actions | 139 |

### Coverage vs Scope
- **Primary scope** (CR 0–10): 2,012 of estimated 1,200–1,500 — **exceeded target**
- **Extended scope** (legendary/lair at any CR): 345 monsters captured
- **Total**: 2,357 monsters from 88 sources

### Data Quality Notes (updated after second pass)
- 88.7% of actions are fully automatable
- Multiattack component resolution: **96.8%** (3,559/3,678) after normalisation. 90 monsters with unresolvable components flagged for text fallback.
- 372 versatile weapon actions disambiguated with twoHandedDamageDice field
- 768 monsters have spellcasting (all normalised to dict format). 214/408 unique spell names match spell-effect-definitions.json (unmatched are levels 4+)
- Plural action names normalised to singular (236 renames)
- 12 false Pack Tactics conditionInflicted removed
- 1,017 residual {@...} markup tags stripped
- REVIEW flags added where extraction was uncertain

## Cross-Task Dependencies

```
spell-effect-definitions.json ─────────┐
                                       ├──► M10: Spell Resolver Engine
class-feature-analysis.md ─── M9 ──────┘
                                       
race-trait-analysis.md ─────── M9 (Character Builder)

item-effect-definitions.json ── M12 (Enhanced Action Economy)

monster-action-definitions.json ── M11 (Monster Actions Engine)
```

- **M8** (Spell Effect Review Cycle) depends on Task 1 output
- **M9** (Character Builder) depends on Tasks 2 and 3 analysis
- **M10** (Spell Resolver) depends on M7 spell data + M8 review
- **M11** (Monster Actions) depends on Task 5 output
- **M12** (Enhanced Actions) depends on Task 4 output

## Recommended Review Priorities

### Critical (review before implementation)
1. **Spell effects for the party's most-used spells** — Eldritch Blast, Healing Word, Shield, Fireball, Counterspell, etc. These will be used every session.
2. **Common CR 0–5 monsters** — Goblin, Skeleton, Zombie, Wolf, Bandit, Orc, Ogre. These appear in most encounters.

### High (review during implementation)
3. **Legendary monsters the party is likely to face** — verify legendary action costs and resistance counts.
4. **Spellcasting monsters** — verify save DCs and attack bonuses match source material.
5. **Healing and buff items** — potions of healing, scrolls of common spells.

### Medium (review post-implementation)
6. **Multiattack descriptions** — verify the resolver parses them correctly against a sample of 20–30 monsters.
7. **REVIEW-flagged entries** across all datasets — these are explicitly uncertain.

### Low (defer)
8. **CR 11+ non-legendary monsters** -- secondary scope, unlikely to appear in near-term sessions.
9. **UTILITY and COMPLEX spells** -- these require manual resolution anyway.
10. **Flavour traits and features** -- no combat impact.

## Milestone 8 Final Sign-Off

All data files were reviewed, validated, and approved on 2026-07-19. See `docs/m8-final-signoff.md` for the full sign-off report (25/25 validation checks passed). Fixes applied: Mummy Lord Rotting Fist damage corrected (3d6+4 to 2d6+4), Mummy Lord legendaryResistanceCount added (3), 48 residual {@...} markup instances stripped. Snare and Earthbind saveToEndEachTurn issues had been fixed in a prior pass.

## Second Review Pass

A comprehensive fix and validation pass was run after the initial data gathering. See `docs/second-review-results.md` for the full validation report (35 checks, 33 passed, 2 informational).

### Summary of Changes

| File | Changes |
|---|---|
| spell-effect-definitions.json | ~85 fixes: schema extensions (projectileScaling, scalingInterval, reactionTrigger, saveToEndEachTurn, conditionalDamage, grantsAdvantage), Eldritch Blast made automatable, Vicious Mockery reclassified, Lesser Restoration reclassified, Sleep/Spiritual Weapon/Flame Blade upcast fixed, class arrays populated |
| monster-action-definitions.json | ~2,500 fixes: 570 multiattack resolved (96.8%), 372 versatile weapons, 240 spellcasting normalised, 236 renames, 1,017 markup tags, 12 false conditions |
| item-effect-definitions.json | 26 fixes: 2 save corrections, 1 AOE fix, 1 condition fix, 10 DC normalisations, 11 upcast flags, 1 pattern fix |
| **Total** | **~2,600 changes across 3 files** |
