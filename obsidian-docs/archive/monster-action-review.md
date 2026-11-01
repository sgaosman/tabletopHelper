# Monster Action Structured Data — Review Summary

Generated: 2026-07-18

## Overview

| Metric | Count |
|---|---|
| Total monsters | 2,357 |
| Source books | 88 |
| Total actions | 6,474 |
| Legendary actions | 902 |
| Reactions | 339 |
| Traits | 4,439 |
| Monsters with spellcasting | 768 |
| Monsters with legendary actions | 311 |
| Monsters with legendary resistance | 336 |
| Monsters with lair actions | 139 |
| Automatable actions/legendary actions | 6,542 |
| Non-automatable (flagged) | 834 |

## CR Distribution

| CR Range | Count | % |
|---|---|---|
| CR 0–2 | 893 | 37.9% |
| CR 3–5 | 602 | 25.5% |
| CR 6–10 | 517 | 21.9% |
| CR 11–15 | 99 | 4.2% |
| CR 16–20 | 90 | 3.8% |
| CR 21+ | 156 | 6.6% |

Primary scope (CR 0–10): 2,012 monsters (85.4%)
Extended scope (CR 11+ with legendary/lair): 345 monsters (14.6%)

## Top Sources

| Source | Monsters |
|---|---|
| MM (Monster Manual) | 424 |
| MPMM (Mordenkainen Presents) | 222 |
| VGM (Volo's Guide) | 129 |
| MTF (Mordenkainen's Tome) | 108 |
| FTD (Fizban's Treasury) | 81 |
| BAM (Boo's Astral Menagerie) | 69 |
| GGR (Guildmasters' Guide) | 69 |
| BGG (Bigby's Giants) | 53 |
| IDRotF (Icewind Dale) | 51 |
| WDH (Waterdeep Dragon Heist) | 50 |
| All others (78 sources) | 1,001 |

## Data Quality

### Automatable vs Non-Automatable

- **6,542 actions** (89%) are fully automatable — they have structured attack bonuses, damage dice, save DCs, and condition effects
- **834 actions** (11%) are flagged `automatable: false` — these describe effects that are too contextual for automation (summoning, terrain manipulation, perception checks, complex multi-step effects)

Non-automatable actions fall back to the existing manual damage/condition tools, which are already tested and functional.

### REVIEW Flags

REVIEW notes were added to actions and monsters where:
- Save-based effects lack parseable mechanical data (debuff described in prose)
- Multiattack definitions have OR-options (e.g., "two claw attacks or one bite and one claw")
- Spellcasting blocks lack explicit DC/attack bonus (use ability-derived defaults)
- Recharge abilities have complex effects beyond simple damage/condition
- Swallow mechanics require special handling

## Second Pass Fixes Applied

| Fix | Count |
|---|---|
| Multiattack component name normalisation | 570 components resolved |
| Versatile weapon disambiguation (twoHandedDamageDice) | 372 actions |
| Pack Tactics false conditionInflicted removed | 12 traits |
| Spellcasting format normalised (list to dict) | 240 monsters |
| Plural action names to singular (Claws->Claw etc.) | 236 actions |
| Multiattack references updated to match | 181 components |
| Recharge format normalised | 7 values |
| {@...} markup stripped | 1,017 tags |

### Multiattack Resolution (after fixes)

- **96.8%** resolution rate (3,559 / 3,678 components)
- 119 components remain unresolvable (OR-choices, sentence fragments)
- 90 monsters flagged `hasUnresolvedComponents: true` for engine fallback

### Legendary Action Coverage

- 354 / 902 legendary actions (39.2%) have structured effects (attackBonus, saveDC, or effects array)
- Remaining 548 are text-only (Detect, Move, Cast a Spell references, non-automatable effects)

### Known Limitations

1. **Multiattack parsing**: 96.8% of multiattack components now resolve to action names. Remaining 3.2% use text fallback.
2. **Spellcasting blocks**: 768 monsters have spellcasting. All normalised to dict format. 214/408 unique spell names match spell-effect-definitions.json (unmatched are levels 4+ -- expected).
3. **Innate spellcasting**: Frequency limits (at will, 3/day, 1/day) are captured but usage tracking requires encounter-level state.
4. **Lair actions**: 139 monsters have lair actions. These trigger on initiative count 20 and affect the environment -- most require DM adjudication.
5. **Legendary action costs**: Costs (1, 2, or 3 actions) are extracted. The encounter engine must track remaining legendary actions per round.

## Recommended Review Priorities

1. **High-use CR 0-5 monsters** (893 + 602 = 1,495): These appear in most encounters. 17 common monsters already verified correct (Goblin, Skeleton, Zombie, Wolf, Dire Wolf, Orc, Ogre, Troll, Ghoul, Ghost, Mind Flayer, Lich, Adult Black Dragon, Owlbear, Beholder, Commoner, Bandit).
2. **Legendary monsters** (311): Verify legendary action costs and legendary resistance counts against the source material. 335 monsters have LR.
3. **Spellcasting monsters** (768): Ensure save DCs and attack bonuses are correct. Cross-check a sample of spell lists against 5e.tools data.
4. **Versatile weapon actions** (372 fixed): Verify twoHandedDamageDice values are correct for sample monsters.
5. **Unresolved multiattack monsters** (90): Review text descriptions for these to ensure fallback display is adequate.
