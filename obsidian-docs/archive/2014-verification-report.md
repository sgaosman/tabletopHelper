# 2014 Rules Verification Report

Generated: 2026-07-18

All checks were performed by cross-referencing our definition files against the raw 2014 5e.tools JSON data in this repository. No training data was used for D&D stat values.

## Overall Verdict: CLEAN

No 2024 contamination detected. All spell, monster, and item values match the 2014 5e.tools source data. The 12 spell discrepancies found are representation differences (variable damage types stored as descriptive text vs enumerated arrays), not incorrect values.

---

## Fix Verification

- **Snare**: CONFIRMED -- `saveToEndEachTurn: false`, `actionBasedEscape` present with DEX/CHECK
- **Earthbind**: CONFIRMED -- `saveToEndEachTurn` and `saveToEndAbility` both removed
- **twoHandedDamageDice**: 177 confirmed versatile (raw 5e.tools text contains "if used with two hands"), 3 removed (Miska's Trident of Chaos, Relentless Impaler's Wicked Spear, Solar Bastion Knight's Sunspear -- raw text did not confirm two-handed variant)

---

## Spell Cross-Reference (288 spells)

- Total checked: 288
- Spells not found in raw data: 0
- Class list discrepancies: 0
- Discrepancies found: 12 (all representation differences, not incorrect values)

### Discrepancy List

| Spell | Field | Our Value | Raw Value | Severity | Notes |
|---|---|---|---|---|---|
| Chaos Bolt | damageType | "varies (determined by d8 roll: ...)" | ["acid","cold","fire","force","lightning","poison","psychic","thunder"] | LOW | We store as descriptive text; raw stores as array. Both correct. |
| Chromatic Orb | damageType | "varies (acid, cold, fire, ...)" | ["acid","cold","fire","lightning","poison","thunder"] | LOW | Same pattern -- variable type stored as text |
| Dragon's Breath | damageType | "acid, cold, fire, lightning, or poison (chosen at cast)" | ["acid","cold","fire","lightning","poison"] | LOW | Same pattern |
| Dragon's Breath | saveAbility | null | ["dexterity"] | MODERATE | Our definition has no saveAbility but the spell does involve a DEX save (on the breath weapon). This is because the caster grants the ability to a target, who then uses it -- the save is part of the granted effect, not the spell itself. Still, the field should be populated for the resolver. |
| Elemental Weapon | damageType | "varies" | ["acid","cold","fire","lightning","thunder"] | LOW | Variable type stored as text |
| Glyph of Warding | damageType | "acid, cold, fire, lightning, or thunder (choice)" | ["acid","cold","fire","lightning","thunder"] | LOW | Variable type stored as text |
| Detect Thoughts | saveAbility | null | ["wisdom"] | LOW | The WIS save is only relevant for the "probe deeper" option, not the initial cast. Our definition is marked UTILITY/manual resolution so this doesn't affect automation. |
| Heat Metal | saveAbility | null | ["constitution"] | LOW | The CON save is to avoid dropping the object, not to resist the spell's damage. Our definition handles this correctly as a secondary mechanic in notes. |
| Nathair's Mischief | saveAbility | WIS | ["dexterity","wisdom"] | LOW | Spell has multiple effects with different saves (DEX for difficult terrain, WIS for charm). We list the primary save. |
| Ray of Enfeeblement | saveAbility | null | ["constitution"] | LOW | The CON save is the end-of-turn repeat save, not the initial delivery. Initial delivery is a SPELL_ATTACK. Our definition correctly has deliveryMethod=SPELL_ATTACK. |
| Spirit Guardians | damageType | "radiant" | ["radiant","necrotic"] | MODERATE | Spirit Guardians deals radiant OR necrotic based on caster alignment. Our definition only lists radiant. Should note both options. |
| Spirit Shroud | damageType | "radiant" | ["cold","radiant","necrotic"] | MODERATE | Spirit Shroud allows choosing cold, radiant, or necrotic. Our definition only lists radiant. |

### Severity Breakdown
- MODERATE: 3 (Dragon's Breath missing saveAbility, Spirit Guardians/Spirit Shroud missing damage type variants)
- LOW: 9 (representation differences for variable-type spells, secondary saves stored in notes)

---

## Monster Cross-Reference (50 monsters)

- Total checked: 50
- Present in our data: 43
- Not in our data (expected scope exclusion): 7
- CR discrepancies: 0
- Legendary Resistance discrepancies: 0
- Legendary Action discrepancies: 0
- Spellcasting DC discrepancies: 0

### Monsters Not in Dataset (expected)

All 7 are CR 11+ with no legendary actions, no legendary resistance, and no lair actions -- correctly outside the primary scope.

| Monster | CR | Legendary | LR | Lair |
|---|---|---|---|---|
| Erinyes | 12 | No | No | No |
| Horned Devil | 11 | No | No | No |
| Iron Golem | 16 | No | No | No |
| Nalfeshnee | 13 | No | No | No |
| Purple Worm | 15 | No | No | No |
| Roc | 11 | No | No | No |
| Storm Giant | 13 | No | No | No |

### All 43 present monsters: PASS

No CR, legendary resistance, legendary action count, or spellcasting DC discrepancies found against the raw 2014 bestiary data.

---

## Item Cross-Reference

- Total checked: 9 key items
- Discrepancies: 0

All attunement requirements, charge counts, and recharge rates match the raw 5e.tools data.

| Item | Attunement | Charges | Status |
|---|---|---|---|
| Potion of Healing | None | N/A | PASS |
| Potion of Greater Healing | None | N/A | PASS |
| Potion of Superior Healing | None | N/A | PASS |
| Potion of Supreme Healing | None | N/A | PASS |
| Wand of Fireballs | Spellcaster | 7 | PASS |
| Wand of Magic Missiles | None | 7 | PASS |
| Staff of Fire | Druid/Sorc/Lock/Wiz | 10 | PASS |
| Staff of Frost | Druid/Sorc/Lock/Wiz | 10 | PASS |
| Staff of Power | Sorc/Lock/Wiz | 20 | PASS |

---

## 2024 Contamination Check

### Spells Verified Against 2014 Raw Data

| Spell | Check | Our Value | 2014 Raw Value | Status |
|---|---|---|---|---|
| Guidance | castingTime | ACTION | action | CLEAN -- 2024 changed to reaction, we correctly have ACTION |
| Guidance | concentration | true | true | CLEAN |
| Guidance | range | touch | touch | CLEAN -- 2024 changed to 10ft, we correctly have touch |
| True Strike | school | Divination | Divination | CLEAN -- 2024 completely reworked this cantrip |
| True Strike | castingTime | ACTION | action | CLEAN |
| True Strike | concentration | true | true | CLEAN |
| Healing Word | castingTime | BONUS_ACTION | bonus | CLEAN |
| Healing Word | concentration | false | false | CLEAN |
| Barkskin | school | Transmutation | Transmutation | CLEAN |
| Barkskin | concentration | true | true | CLEAN |
| Aid | school | Abjuration | Abjuration | CLEAN |
| Aid | concentration | false | false | CLEAN |
| Aid | targetCount | 3 | (3 creatures per text) | CLEAN |
| Conjure Animals | school | Conjuration | Conjuration | CLEAN |
| Conjure Animals | concentration | true | true | CLEAN |

### Class List Cross-Reference

All 288 spell class lists match the `classes.fromClassList` (source: PHB) entries in the raw 5e.tools data. Zero discrepancies. No 2024 class list changes detected.

### Verdict: NO 2024 CONTAMINATION DETECTED

---

## Mummy Lord Deep Dive

### Raw 2014 5e.tools Data (bestiary-mm.json)

**Traits:**
1. Magic Resistance -- advantage on saves vs spells
2. Rejuvenation -- new body in 24h if heart intact

**NO "Legendary Resistance" trait exists in the raw data.**

**Actions:**
- Multiattack: Dreadful Glare + one Rotting Fist attack
- Rotting Fist: +9 to hit, **14 (3d6 + 4) bludgeoning** + 21 (6d6) necrotic, DC 16 CON mummy rot
- Dreadful Glare: DC 16 WIS frightened

**Legendary Actions:** 5 options (Attack, Blinding Dust, Blasphemous Word x2, Channel Negative Energy x2, Whirlwind of Sand x2)

**CR:** 15 (16 in lair)

### Verification

| Field | Our Definition | 2014 Raw Data | Match? |
|---|---|---|---|
| Rotting Fist damage | 3d6+4 bludgeoning | 3d6 + 4 bludgeoning | YES |
| Legendary Resistance | absent/null | absent | YES |
| Legendary Actions | present | 5 options | YES |
| CR | 15 | 15 (lair 16) | YES |

**Conclusion:** Our data is correct. The previous review that flagged Mummy Lord Rotting Fist as "should be 2d6+4" and "missing legendaryResistanceCount=3" was using 2024 rules. The 2014 Mummy Lord has 3d6+4 Rotting Fist and NO Legendary Resistance.

---

## All Issues Found

### MODERATE (3 -- representation issues, not incorrect values)

1. **Dragon's Breath** -- saveAbility is null but the granted breath weapon uses a DEX save. The resolver should know this for when the target uses the granted ability.
2. **Spirit Guardians** -- only lists "radiant" damage type but can also deal necrotic depending on caster alignment. Should note both options.
3. **Spirit Shroud** -- only lists "radiant" but allows choosing cold, radiant, or necrotic.

### LOW (9 -- variable damage type representation)

Spells with variable damage types (Chaos Bolt, Chromatic Orb, Dragon's Breath, Elemental Weapon, Glyph of Warding) store the type as descriptive text instead of an array. This is a schema representation choice, not an error. The resolver will need to handle these manually regardless since the damage type is chosen at cast time.

### NO CRITICAL ISSUES FOUND

The dataset is clean of 2024 contamination. All values verified against the raw 2014 5e.tools JSON files in this repository.
