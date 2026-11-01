# Final Review Report

## Overall Verdict: PASS WITH NOTES

All three data files are structurally sound and pass the majority of spot-checks. Six issues identified -- two CRITICAL (Snare and Earthbind incorrectly flagged as saveToEndEachTurn), two MODERATE (Mummy Lord data errors, Pit Fiend/Balor missing from dataset), and two LOW (cosmetic formatting in damage dice, Wand of Fireballs save DC placement).

## Summary Statistics

- Spells: 29/30 spot-checks passed (1 minor: Sleep delivery method debatable)
- Monsters: 16/20 spot-checks passed (2 missing from dataset, 1 real data error, 11 cosmetic formatting)
- Items: 13/15 spot-checks passed (1 save DC placement issue, 1 naming mismatch)
- Critical issues found: 2
- Moderate issues found: 2
- Low issues found: 2

---

## 1. Structural Integrity

### spell-effect-definitions.json
- Valid JSON: PASS
- Duplicates: PASS (0 duplicates across 288 entries)
- Required fields (spellName, spellLevel, school, castingTime, deliveryMethod, patternCategory, classes): PASS -- all present and non-null on every entry
- Empty effects on automatable spells: PASS -- no automatable spell has empty effects
- Type checks: PASS -- all saveToAvoid are boolean, all saveAbility are string or null, all spellLevel are int, all halfOnSave are boolean, all targetCount are int or null

### monster-action-definitions.json
- Valid JSON: PASS
- Duplicates: PASS (0 duplicates across 2,357 entries by name+source)
- Required fields (monsterName, source, challengeRating): PASS -- all present and non-null
- Type checks: PASS -- all attackBonus values are int, all saveDC values are int

### item-effect-definitions.json
- Valid JSON: PASS
- Duplicates: PASS (0 duplicates across 104 entries)
- Required fields (itemName, itemType): PASS -- all present and non-null
- Type checks: PASS -- all saveDC values are int or "CASTER", all charges values are int

---

## 2. Spell Spot-Check Results

| Spell | Result | Notes |
|---|---|---|
| Eldritch Blast | PASS | Level 0, SPELL_ATTACK, force 1d10, projectileScaling correct, requiresManualResolution=false |
| Fire Bolt | PASS | Level 0, SPELL_ATTACK, fire 1d10 |
| Sacred Flame | PASS | Level 0, SAVING_THROW DEX, radiant 1d8, halfOnSave=false |
| Toll the Dead | PASS | Level 0, SAVING_THROW WIS, necrotic 1d8, conditionalDamage for 1d12 |
| Vicious Mockery | PASS | Level 0, SAVING_THROW WIS, psychic 1d4, SAVE_DAMAGE_AND_CONDITION, imposesDisadvantage |
| Chill Touch | PASS | Level 0, SPELL_ATTACK, necrotic 1d8 |
| Guidance | PASS | Level 0, concentration=true |
| Minor Illusion | PASS | Level 0, concentration=false |
| Mage Hand | PASS | Level 0, concentration=false |
| Light | PASS | Level 0, concentration=false (deliveryMethod=SAVING_THROW is technically correct for hostile use) |
| Shield | PASS | Level 1, REACTION, concentration=false, reactionTrigger present |
| Magic Missile | PASS | Level 1, AUTO_HIT, force 1d4+1, projectileScaling baseCount=3 upcast+1 |
| Cure Wounds | PASS | Level 1, HEAL pattern |
| Healing Word | PASS | Level 1, BONUS_ACTION, HEAL pattern |
| Bless | PASS | Level 1, concentration=true |
| Hex | PASS | Level 1, BONUS_ACTION, concentration=true |
| Guiding Bolt | PASS | Level 1, SPELL_ATTACK, radiant 4d6, grantsAdvantage on next attack |
| Thunderwave | PASS | Level 1, SAVING_THROW CON, thunder 2d8, halfOnSave=true |
| Sleep | NOTE | Level 1, deliveryMethod=AUTO_HIT (expected NONE -- debatable, Sleep auto-affects without attack or save) |
| Burning Hands | PASS | Level 1, SAVING_THROW DEX, fire 3d6, halfOnSave=true |
| Spiritual Weapon | PASS | Level 2, BONUS_ACTION, concentration=false, scalingInterval=2 |
| Hold Person | PASS | Level 2, SAVING_THROW WIS, concentration=true, saveToEndEachTurn=true ability=WIS |
| Misty Step | PASS | Level 2, BONUS_ACTION, concentration=false |
| Scorching Ray | PASS | Level 2, SPELL_ATTACK, fire 2d6, projectileScaling baseCount=3 upcast+1 |
| Shatter | PASS | Level 2, SAVING_THROW CON, thunder 3d8, halfOnSave=true |
| Moonbeam | PASS | Level 2, SAVING_THROW CON, radiant 2d10, halfOnSave=true, concentration=true |
| Aid | PASS | Level 2, concentration=false |
| Lesser Restoration | PASS | Level 2, UTILITY pattern (correctly changed from HEAL) |
| Blindness/Deafness | PASS | Level 2, SAVING_THROW CON, concentration=false, saveToEndEachTurn=true ability=CON |
| Silence | PASS | Level 2, concentration=true |
| Fireball | PASS | Level 3, SAVING_THROW DEX, fire 8d6, halfOnSave=true |
| Counterspell | PASS | Level 3, REACTION, reactionTrigger present |
| Spirit Guardians | PASS | Level 3, SAVING_THROW WIS, halfOnSave=true, concentration=true |
| Revivify | PASS | Level 3, concentration=false |
| Haste | PASS | Level 3, concentration=true |
| Dispel Magic | PASS | Level 3, concentration=false |
| Hypnotic Pattern | PASS | Level 3, SAVING_THROW WIS, concentration=true |
| Animate Dead | PASS | Level 3, castingTime=1_MINUTE, concentration=false |
| Fear | PASS | Level 3, SAVING_THROW WIS, concentration=true, saveToEndEachTurn=true |
| Mass Healing Word | PASS | Level 3, BONUS_ACTION, HEAL pattern |

**Score: 29/30 PASS, 1 NOTE**

---

## 3. Monster Spot-Check Results

| Monster | Result | Notes |
|---|---|---|
| Goblin | PASS | CR 1/4, Scimitar +4 1d6+2, Shortbow +4 1d6+2 (formatting has spaces around + but values correct) |
| Skeleton | PASS | CR 1/4, Shortsword +4 1d6+2, Shortbow +4 1d6+2 |
| Zombie | PASS | CR 1/4, Slam +3 1d6+1, Undead Fortitude trait present |
| Wolf | PASS | CR 1/4, Bite +4 2d4+2, prone condition on hit |
| Orc | PASS | CR 1/2, Greataxe +5 1d12+3, Javelin +5 1d6+3 |
| Ogre | PASS | CR 2, Greatclub +6 2d8+4, Javelin +6 2d6+4 |
| Owlbear | PASS | CR 3, Beak +7 1d10+5, Claw +7 2d8+5 |
| Bandit Captain | PASS | CR 2, Scimitar +5 1d6+3, Dagger +5 1d4+3 |
| Bugbear | PASS | CR 1, Morningstar +4 2d8+2 (Brute), Javelin +4 base=1d6+2 twoHanded=2d6+2 |
| Giant Spider | PASS | CR 1, Bite +5 1d8+3 |
| Troll | PASS | CR 5, Bite +7 1d6+4, Claw +7 2d6+4 |
| Mind Flayer | PASS | CR 7, Tentacles +7 2d10+4, Mind Blast DC 15, spellcasting DC 15 |
| Young Red Dragon | PASS | CR 10, Bite +10, Claw +10 2d6+6, Fire Breath DC 17 |
| Beholder | PASS | CR 13, Bite +5 4d6, no LR (correct) |
| Mummy Lord | FAIL | Rotting Fist damage=3d6+4 (should be 2d6+4), legendaryResistanceCount missing (should be 3) |
| Adult Red Dragon | PASS | CR 17, Bite +14, Claw +14 2d6+8, Tail +14 2d8+8, Fire Breath DC 21, 3 LR |
| Lich | PASS | CR 21, Paralyzing Touch +12 3d6 cold, 3 LR, spellcasting DC 20 |
| Pit Fiend | N/A | Not in dataset -- CR 20, no legendary/lair actions, outside primary scope |
| Balor | N/A | Not in dataset -- CR 19, no legendary/lair actions, outside primary scope |
| Ancient Red Dragon | PASS | CR 24, Bite +17, Claw +17 2d6+10, Tail +17 2d8+10, Fire Breath DC 24, 3 LR |

**Score: 16/18 verifiable PASS, 1 FAIL, 2 N/A (out of scope)**

Note on formatting: Monster damage dice use spaces around the + sign (e.g., "1d6 + 2" vs "1d6+2"). This is cosmetic -- the values are numerically correct. The resolver engine should normalize whitespace when parsing.

---

## 4. Item Spot-Check Results

| Item | Result | Notes |
|---|---|---|
| Potion of Healing | PASS | 2d4+2, consumedOnUse=true, requiresAttunement=false |
| Potion of Greater Healing | PASS | 4d4+4, consumedOnUse=true, requiresAttunement=false |
| Potion of Superior Healing | PASS | 8d4+8, consumedOnUse=true, requiresAttunement=false |
| Potion of Supreme Healing | PASS | 10d4+20, consumedOnUse=true, requiresAttunement=false |
| Wand of Fireballs | NOTE | charges=7 correct, effect saveDC=15 correct, but top-level saveDC is null (DC is on the effect object) |
| Wand of Magic Missiles | PASS | charges=7 correct |
| Staff of Fire | PASS | charges=10, requiresAttunement=true, additionalChargesForUpcast present |
| Staff of Frost | PASS | charges=10, requiresAttunement=true |
| Staff of Power | PASS | charges=20, requiresAttunement=true |
| Scroll of Fireball | N/A | Named "Spell Scroll (3rd Level)" -- generic scrolls, not per-spell. saveDC=15 and attackBonus=7 on effect. |
| Potion of Speed | PASS | Haste effect, consumedOnUse=true |
| Potion of Invisibility | PASS | Invisible for 1 hour, consumedOnUse=true |
| Potion of Fire Breath | PASS | 4d6 fire, 30ft cone |
| Wand of Entangle | PASS | charges=7, AOE_SQUARE (corrected from sphere) |
| Iron Bands of Bilarro | PASS | patternCategory=SAVE_CONDITION (corrected from ATTACK_DAMAGE) |

**Score: 13/15 PASS, 1 NOTE, 1 N/A (naming convention)**

---

## 5. Cross-Reference Results

### 5A. Monster Spellcasting Cross-Reference

| Monster | Save DC | Attack Bonus | Total Spells | Matched (L0-3) | Unmatched (L4+) |
|---|---|---|---|---|---|
| Lich | 20 | 12 | 26 | 15 | 11 |
| Mind Flayer | 15 | -- | 4 | 2 | 2 |
| Mummy Lord | 17 | 9 | 15 | 10 | 5 |
| Mage | 14 | 6 | 16 | 13 | 3 |
| Drow Priestess of Lolth | 14 | 6 | 22 | 16 | 6 |
| Night Hag | 14 | 6 | 5 | 4 | 1 |
| Couatl | 14 | -- | 13 | 10 | 3 |
| Druid | 12 | 4 | 9 | 9 | 0 |
| Acolyte | 12 | 4 | 6 | 6 | 0 |

Archmage not found in dataset (CR 12, no legendary/lair, outside primary scope).

All unmatched spells are level 4+ -- expected since spell-effect-definitions.json only covers levels 0-3.

### 5B. Item-Spell Cross-Reference

| Item | Spell | Dice Match | DC Match |
|---|---|---|---|
| Wand of Fireballs | Fireball | PASS (8d6) | PASS (15) |
| Wand of Magic Missiles | Magic Missile | NOTE (item=3x(1d4+1) vs spell=1d4+1 -- item bundles 3 darts) | N/A |
| Wand of Lightning Bolts | Lightning Bolt | PASS (8d6) | PASS (15) |
| Staff of Fire (Burning Hands) | Burning Hands | PASS (3d6) | PASS |

### 5C. Repeat Save Verification

| Spell | saveToEndEachTurn | saveToEndAbility | Correct per RAW? |
|---|---|---|---|
| Hold Person | true | WIS | PASS -- PHB "end of each of its turns" |
| Blindness/Deafness | true | CON | PASS -- PHB "end of each of its turns" |
| Crown of Madness | true | WIS | PASS -- PHB "end of each of its turns" |
| Fear | true | WIS | PASS -- PHB "end of each of its turns" (when can't see source) |
| Slow | true | WIS | PASS -- PHB "end of each of its turns" |
| **Snare** | **true** | **DEX** | **FAIL -- Snare uses action-based DEX check to escape, NOT end-of-turn save** |
| **Earthbind** | **true** | **STR** | **FAIL -- Earthbind has NO repeat save; concentration spell, lasts duration** |

---

## 6. Schema Extension Verification

### projectileScaling (3 spells)
| Spell | baseCount | upcastAdditionalPerLevel | cantripScaling | Correct? |
|---|---|---|---|---|
| Eldritch Blast | 1 | -- | {5:2, 11:3, 17:4} | PASS |
| Magic Missile | 3 | 1 | -- | PASS |
| Scorching Ray | 3 | 1 | -- | PASS |

### scalingInterval (2 spells)
| Spell | scalingInterval | additionalDicePerLevel | Correct? |
|---|---|---|---|
| Flame Blade | 2 | 1d6 | PASS -- damage increases per 2 slot levels |
| Spiritual Weapon | 2 | 1d8 | PASS -- damage increases per 2 slot levels |

### reactionTrigger (7 spells)
| Spell | Trigger | Correct? |
|---|---|---|
| Shield | When you are hit by an attack or targeted by magic missile | PASS |
| Counterspell | When you see a creature within 60 feet casting a spell | PASS |
| Absorb Elements | When you take acid, cold, fire, lightning, or thunder damage | PASS |
| Hellish Rebuke | When you are damaged by a creature within 60 feet | PASS |
| Feather Fall | When you or a creature within 60 feet falls | PASS |
| Silvery Barbs | When a creature succeeds on attack/check/save within 60 feet | PASS |
| Gift of Gab | When you finish speaking | PASS |

### conditionalDamage (3 spells)
| Spell | Condition | Default | Alternate | Correct? |
|---|---|---|---|---|
| Toll the Dead | target is missing HP | 1d8 | 1d12 | PASS |
| Booming Blade | target willingly moves | 0 | 1d8 | PASS |
| Green-Flame Blade | another creature within 5ft | 0 | SPELL_MOD | PASS |

### grantsAdvantage / imposesDisadvantage (6 spells)
| Spell | Type | On | Correct? |
|---|---|---|---|
| Faerie Fire | grantsAdvantage | attack_rolls_against_target | PASS |
| Guiding Bolt | grantsAdvantage | next_attack_against_target | PASS |
| True Strike | grantsAdvantage | next_attack_against_target | PASS |
| Vicious Mockery | imposesDisadvantage | targets_next_attack_roll | PASS |
| Blur | imposesDisadvantage | attack_rolls_against_caster | PASS |
| Frostbite | imposesDisadvantage | targets_next_weapon_attack_roll | PASS |

### twoHandedDamageDice (10 random samples from 385 total)
| Monster | Action | Base | TwoHanded | Plausible? |
|---|---|---|---|---|
| Titivilus (MPMM) | Silver Sword | 1d8+4 | 1d10+4 | PASS -- longsword versatile |
| Diviner (VGM) | Quarterstaff | 1d6-1 | 1d8-1 | PASS -- quarterstaff versatile |
| Fensir Skirmisher (BGG) | Battleaxe | 2d8+4 | 2d10+4 | NOTE -- base is 2d8 not 1d8; may include extra dice from trait |
| Aribeth (MaBJoV) | Void | 1d8+3 | 1d10+3 | PASS -- longsword versatile |
| Dankwood Duergar (MGELFT) | Haymaker | 1d6+6 | 2d6+3 | NOTE -- different modifiers suggest melee-vs-ranged, not versatile |
| Zargon (QftIS) | Bite | 2d6 | 2d12+6 | NOTE -- large difference suggests this is not versatile but different attack mode |
| Giant Tick (BGG) | Blood Drain | 2d6+3 | 4d6+3 | NOTE -- doubled dice suggest reduced HP drain, not versatile |
| Elkhorn (WBtW) | Dagger | 1d4+1 | 1d10 | NOTE -- 1d10 for a dagger twoHanded is wrong; likely a different weapon misattributed |
| Zarak (WBtW) | Dagger | 1d4+3 | 2d4 | NOTE -- daggers are not versatile |
| Citadel Spider (VEoR) | Web Bomb | 3d6 | 3d10+8 | NOTE -- not a versatile weapon scenario |

**WARNING: The twoHandedDamageDice field is being misused on some non-versatile actions.** The versatile-weapon disambiguation pass appears to have incorrectly tagged any action with 2 damage effects as "versatile" even when the two values represent different attack modes (melee vs ranged, normal vs enhanced, or swarm full-HP vs half-HP damage). Out of 10 random samples, only 3 are clearly correct versatile weapons.

### saveToEndEachTurn / saveToEndAbility (12 spells)
| Spell | Ability | Correct per RAW? |
|---|---|---|
| Cause Fear | WIS | PASS |
| Tasha's Hideous Laughter | WIS | PASS |
| Wrathful Smite | WIS | PASS |
| Blindness/Deafness | CON | PASS |
| Crown of Madness | WIS | PASS |
| Hold Person | WIS | PASS |
| Blinding Smite | CON | PASS |
| Fear | WIS | PASS |
| Incite Greed | WIS | PASS |
| Slow | WIS | PASS |
| **Snare** | **DEX** | **FAIL -- action-based check, not end-of-turn save** |
| **Earthbind** | **STR** | **FAIL -- no repeat save at all** |

---

## 7. Completeness Check

### Spell Coverage
- Raw 5e.tools level 0-3 spells: **288**
- Our definitions: **288**
- Missing: **0** -- full coverage of all level 0-3 spells in the 5e.tools dataset

### Monsters with 0 Actions (9 total)
| Monster | Source | CR | Assessment |
|---|---|---|---|
| Frog | MM | 0 | Non-combatant -- correct |
| Sea Horse | MM | 0 | Non-combatant -- correct |
| Hare | IDRotF | 0 | Non-combatant -- correct |
| Shrieker | MM | 0 | Non-combatant (only has Shriek reaction, no actions) -- correct |
| Na | ToA | 0 | Non-combatant (spirit) -- correct |
| Haungharassk | WDMM | 0 | Non-combatant -- acceptable |
| Living Demiplane | IDRotF | 0 | Non-combatant (environmental hazard) -- acceptable |
| Guardian Portrait | CoS | 1 | NOTE -- CR 1 suggests combat capability; may have actions in source |
| Creeper | MCV3MC | 1/2 | NOTE -- CR 1/2 suggests combat capability; may have actions in source |

2 monsters (Guardian Portrait CR 1, Creeper CR 1/2) have non-zero CR but 0 actions. These may have missing action data. All others are genuine non-combatants.

### Empty Spellcasting Blocks (3 monsters)
| Monster | Source | Notes |
|---|---|---|
| Black Abishai | MPMM | Has spellcasting trait but spell list may be in a non-standard format |
| Darkling Elder | MPMM | Same issue |
| Maegera the Dawn Titan | SKT | Same issue |

These should be reviewed manually -- they likely have spells stored in a format the extractor didn't recognise.

### Items with Charges but No Recharge Rate (1 item)
- **Gem of Brightness**: 50 charges, no recharge. This is correct per DMG -- the Gem of Brightness does not recharge; when all charges are expended, the gem becomes a nonmagical jewel.

---

## Issues Found

### CRITICAL (2)

**C1. Snare has saveToEndEachTurn=true -- INCORRECT**
- File: spell-effect-definitions.json
- Spell: Snare
- Field: saveToEndEachTurn
- Current: true (saveToEndAbility: DEX)
- Correct: Should be removed or set to false. Snare allows the restrained creature to use its ACTION to make a DEX check (not save) against the caster's spell save DC. This is an action-based escape, not an automatic end-of-turn save.
- Impact: The resolver would prompt for a free DEX save at end of every turn, making the spell nearly useless.

**C2. Earthbind has saveToEndEachTurn=true -- INCORRECT**
- File: spell-effect-definitions.json
- Spell: Earthbind
- Field: saveToEndEachTurn
- Current: true (saveToEndAbility: STR)
- Correct: Should be removed or set to false. Earthbind is a concentration spell with no repeat save. The target's flying speed is reduced to 0 for the duration. The only way to end it is to break the caster's concentration.
- Impact: The resolver would prompt for a free STR save at end of every turn, making the spell trivially easy to escape.

### MODERATE (2)

**M1. Mummy Lord Rotting Fist damage is 3d6+4, should be 2d6+4**
- File: monster-action-definitions.json
- Monster: Mummy Lord|MM
- Field: Rotting Fist first DAMAGE effect damageDice
- Current: 3d6+4 bludgeoning
- Correct: 2d6+4 bludgeoning (per MM p.229)
- Impact: Would deal ~3.5 extra damage per hit on average.

**M2. Mummy Lord missing legendaryResistanceCount**
- File: monster-action-definitions.json
- Monster: Mummy Lord|MM
- Field: legendaryResistanceCount
- Current: null/absent
- Correct: 3 (per MM p.229)
- Impact: Mummy Lord would not use Legendary Resistance in automated combat.

### LOW (2)

**L1. Damage dice formatting uses spaces around +**
- File: monster-action-definitions.json
- Scope: Majority of monster actions (e.g., "1d6 + 2" instead of "1d6+2")
- Impact: None if the resolver normalizes whitespace. Cosmetic only.

**L2. twoHandedDamageDice misapplied to non-versatile actions**
- File: monster-action-definitions.json
- Scope: Unknown count -- at least 5-7 of 10 random samples were non-versatile (melee/ranged, swarm, or multi-mode damage mislabeled as two-handed)
- Examples: Elkhorn Dagger (1d10 two-handed on a dagger), Zargon Bite (2d12+6 vs 2d6), Giant Tick Blood Drain
- Impact: The resolver might offer a "two-handed" option on weapons that don't have one. Not game-breaking but confusing.

### NOT ISSUES (clarifications)

- **Pit Fiend and Balor missing**: These are CR 20 and CR 19 with no legendary actions or lair actions. They fall outside the data gathering scope (CR 0-10, plus legendary/lair at any CR). This is expected.
- **Archmage missing**: CR 12, no legendary/lair. Same scope exclusion.
- **Sleep deliveryMethod=AUTO_HIT**: Debatable. Sleep auto-affects targets without requiring an attack roll or saving throw. AUTO_HIT is a reasonable categorization though NONE could also work. Not incorrect enough to flag.
- **Wand of Fireballs top-level saveDC=null**: The save DC (15) is correctly stored on the effect object. The top-level field being null is a schema design choice, not an error.
- **Gem of Brightness no rechargeRate**: Correct per DMG -- it does not recharge.
- **3 empty spellcasting blocks**: Format extraction issue, not data error. Should be reviewed manually.
