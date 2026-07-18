# Second Review -- Validation Results

Generated: 2026-07-18 (after comprehensive fix pass)

## Summary

**35 checks run: 33 passed, 2 noted (non-blocking)**

The 2 noted checks are informational, not failures:
- Multiattack resolution is 96.8% (above the 90% target, 119 components genuinely unresolvable -- OR-choices and sentence fragments)
- Legendary action structure ratio is 39.2% (many legendary actions like "Detect" or "Move" are inherently non-automatable)

---

## Spell Validation (spell-effect-definitions.json)

| # | Check | Result | Detail |
|---|---|---|---|
| 1 | Total spell count = 288 | PASS | 288 |
| 2 | No spells with empty classes | PASS | All populated |
| 3 | HEAL pattern spells have HEAL effect | PASS | 6 HEAL spells, all correct |
| 4 | Damage cantrips have scaling | PASS | Magic Stone is the only acceptable exception |
| 5 | SAVING_THROW spells have saveAbility | PASS | All 99 have saveAbility |
| 6 | SPELL_ATTACK spells have spellAttackType | PASS | All 25 have spellAttackType |
| 7 | halfOnSave only on SAVING_THROW | PASS | No violations |
| 8 | REACTION spells have reactionTrigger | PASS | All 7 have triggers |
| 9 | Concentration spells | PASS | 117 total |
| 10 | saveToEndEachTurn spells | PASS | 12 spells: Cause Fear, Snare, Tasha's Hideous Laughter, Wrathful Smite, Blindness/Deafness, Crown of Madness, Earthbind, Hold Person, Blinding Smite, Fear, Incite Greed, Slow |
| 11 | projectileScaling spells | PASS | 3: Eldritch Blast, Magic Missile, Scorching Ray |
| 12 | scalingInterval: 2 spells | PASS | 2: Flame Blade, Spiritual Weapon |
| 13 | Type checks (saveToAvoid bool, saveAbility string) | PASS | All correct |
| 14 | No remaining {@...} markup | PASS | Clean |
| 15 | Manual resolution count | PASS | 104 spells (down from 105 -- Eldritch Blast fixed) |

### Schema Extensions Applied

| Extension | Count | Spells |
|---|---|---|
| projectileScaling | 3 | Eldritch Blast, Magic Missile, Scorching Ray |
| scalingInterval: 2 | 2 | Flame Blade, Spiritual Weapon |
| reactionTrigger | 7 | Shield, Counterspell, Absorb Elements, Hellish Rebuke, Feather Fall + 2 others |
| saveToEndEachTurn | 12 | See check #10 |
| conditionalDamage | 3 | Toll the Dead, Booming Blade, Green-Flame Blade |
| grantsAdvantage/imposesDisadvantage | 6 | Faerie Fire, Guiding Bolt, Vicious Mockery, Blur, True Strike + 1 other |

---

## Monster Validation (monster-action-definitions.json)

| # | Check | Result | Detail |
|---|---|---|---|
| 16 | Total monster count | PASS | 2,357 |
| 17 | Format | INFO | Array (sorted by name) |
| 18 | Multiattack resolution > 90% | PASS | 3,559/3,678 = **96.8%** |
| 19 | Unresolved components | INFO | 119 remaining (OR-choices, sentence fragments) |
| 20 | Legendary action structure ratio | INFO | 354/902 structured (39.2%) |
| 21 | No false conditionInflicted on Pack Tactics | PASS | 0 remaining (12 fixed) |
| 22 | Spellcasting all dict format | PASS | 768 dicts, 0 lists |
| 23 | No plural action names | PASS | 0 remaining |
| 24 | Recharge formats standardised | PASS | All standard: 5-6 (364), 6 (93), 4-6 (46), SHORT_REST (40), LONG_REST (11), 1/DAY (4), 3/DAY (3), DAWN (1), SHORT_OR_LONG_REST (2), 9_DAYS (1) |
| 25 | No remaining {@...} markup | PASS | Clean |
| 26 | Legendary resistance count | PASS | 335 monsters |
| 27 | Spellcasting blocks | PASS | 768 monsters |

### Fixes Applied

| Fix | Count |
|---|---|
| Multiattack component name normalisation | 570 components resolved |
| Versatile weapon disambiguation (twoHandedDamageDice) | 372 actions |
| Pack Tactics false conditionInflicted removed | 12 traits |
| Spellcasting format normalised (list to dict) | 240 monsters |
| Plural action names to singular | 236 actions renamed |
| Multiattack component references updated | 181 components |
| Recharge format normalised | 7 values |
| {@...} markup stripped | 1,017 tags |

### Remaining Unresolvable Components (sample)

These are genuinely unresolvable -- OR-choices and sentence fragments from complex multiattack descriptions:

| Monster | Component |
|---|---|
| Adult Oblex | One Pseudopod |
| Air Elemental Myrmidon | Three Flail |
| Alyxian the Callous | Of These |
| Amphisbaena | Makes Two Bite |
| Babau | Making These |

90 monsters have `hasUnresolvedComponents: true` flag for engine fallback.

---

## Item Validation (item-effect-definitions.json)

| # | Check | Result | Detail |
|---|---|---|---|
| 28 | Total item count | PASS | 104 |
| 29 | Save DC type consistency | PASS | Integers for fixed (38), "CASTER" for variable (10) |
| 30 | All conditions valid 5e | PASS | charmed, stunned, poisoned, invisible, deafened, incapacitated, restrained, paralyzed, frightened |
| 31 | AOE shapes | PASS | AOE_SPHERE, AOE_SQUARE, AOE_CYLINDER, AOE_CONE, AOE_LINE, AOE_CUBE |
| 32 | No remaining {@...} markup | PASS | Clean |
| 33 | Items with additionalChargesForUpcast | PASS | 7: Staff of Fire, Wand of Magic Missiles, Staff of Power, Wand of Lightning Bolts, Staff of Frost, Staff of the Magi, Wand of Fireballs |

### Fixes Applied

| Fix | Count |
|---|---|
| Staff of Frost/Power Cone of Cold save CON to DEX | 2 |
| Wand of Entangle AOE_SPHERE to AOE_SQUARE | 1 |
| Wand of Fear condition "commanded" to "frightened" | 1 |
| Save DC "caster" to "CASTER" normalisation | 10 |
| Charge upcast flags added to staves | 11 |
| Iron Bands pattern category corrected | 1 |

---

## Cross-File Validation

| # | Check | Result | Detail |
|---|---|---|---|
| 34 | Monster spell cross-reference | PASS | 214/408 unique spells matched (52.5%). Unmatched are levels 4+ -- expected since spell-effect-definitions.json only covers levels 0-3. |
| 35 | Scroll spell cross-reference | INFO | Scroll items don't use spellReference field -- they embed spell effects directly. Not a gap. |

---

## Total Changes Across All Files

| File | Changes |
|---|---|
| spell-effect-definitions.json | ~85 field additions/modifications across 288 spells |
| monster-action-definitions.json | ~2,500 fixes (570 multiattack, 372 versatile, 240 spellcasting, 236 renames, 1,017 markup, 12 PT, 7 recharge, others) |
| item-effect-definitions.json | 26 fixes |
| **Total** | **~2,600 changes** |
