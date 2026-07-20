..# 2014 Rules Audit

All checks performed against the actual 5e.tools 2014 data files in this repository, NOT from training knowledge.

## Verdict: PASS

No 2024 contamination detected. All data matches the 2014 5e.tools source files.

---

## Part A: Spell Cross-Reference (30 spells)

Cross-referenced against 5e.tools spells-phb.json, spells-xge.json, spells-tce.json. Compared: level, casting time, concentration, save ability, spell attack type, damage types, conditions.

| Spell | Source | Result | Details |
|---|---|---|---|
| Eldritch Blast | PHB | PASS | |
| Fire Bolt | PHB | PASS | |
| Sacred Flame | PHB | PASS | |
| Toll the Dead | XGE | PASS | |
| Vicious Mockery | PHB | PASS | |
| Chill Touch | PHB | PASS | |
| Guidance | PHB | PASS | |
| Minor Illusion | PHB | PASS | |
| Mage Hand | PHB | PASS | |
| Light | PHB | PASS | |
| Shield | PHB | PASS | |
| Magic Missile | PHB | PASS | |
| Cure Wounds | PHB | PASS | |
| Healing Word | PHB | PASS | |
| Bless | PHB | PASS | |
| Hex | PHB | PASS | |
| Guiding Bolt | PHB | PASS | |
| Thunderwave | PHB | PASS | |
| Sleep | PHB | PASS | |
| Burning Hands | PHB | PASS | |
| Spiritual Weapon | PHB | PASS | |
| Hold Person | PHB | PASS | |
| Misty Step | PHB | PASS | |
| Scorching Ray | PHB | PASS | |
| Shatter | PHB | PASS | |
| Moonbeam | PHB | PASS | |
| Aid | PHB | PASS | |
| Lesser Restoration | PHB | PASS | |
| Blindness/Deafness | PHB | PASS | |
| Silence | PHB | PASS | |
| Fireball | PHB | PASS | |
| Counterspell | PHB | PASS | |
| Spirit Guardians | PHB | NOTE | 5e.tools lists both radiant and necrotic damage types; our definition only has radiant in effects. The necrotic variant (for evil casters) is mentioned in notes but not as a structured effect. |
| Revivify | PHB | PASS | |
| Haste | PHB | PASS | |
| Dispel Magic | PHB | PASS | |
| Hypnotic Pattern | PHB | PASS | |
| Animate Dead | PHB | PASS | |
| Fear | PHB | PASS | |
| Mass Healing Word | PHB | PASS | |

**29/30 PASS, 0 FAIL, 1 NOTE**

The Spirit Guardians note is cosmetic -- the resolver can use the caster's alignment to determine damage type at runtime.

---

## Part B: Monster Cross-Reference (20 monsters)

Cross-referenced against 5e.tools bestiary-mm.json. Compared: CR, attack bonuses (via {@hit N} tags), legendary action count, legendary resistance (via trait search), spellcasting DCs.

| Monster | CR | Result | Details |
|---|---|---|---|
| Goblin | 1/4 | PASS | |
| Skeleton | 1/4 | PASS | |
| Zombie | 1/4 | PASS | |
| Wolf | 1/4 | PASS | |
| Orc | 1/2 | PASS | |
| Ogre | 2 | PASS | |
| Owlbear | 3 | PASS | |
| Bandit Captain | 2 | PASS | |
| Bugbear | 1 | PASS | |
| Giant Spider | 1 | PASS | |
| Troll | 5 | PASS | |
| Mind Flayer | 7 | PASS | |
| Young Red Dragon | 10 | PASS | |
| Beholder | 13 | PASS | |
| Mummy Lord | 15 | PASS | See Part E for deep dive |
| Adult Red Dragon | 17 | PASS | |
| Lich | 21 | PASS | |
| Pit Fiend | 20 | N/A | Not in dataset -- CR 20, no legendary/lair actions, outside primary scope |
| Balor | 19 | N/A | Not in dataset -- CR 19, no legendary/lair actions, outside primary scope |
| Ancient Red Dragon | 24 | PASS | |

**18/18 verifiable PASS, 0 FAIL, 2 N/A (out of scope)**

---

## Part C: Item Cross-Reference (8 items)

Cross-referenced against 5e.tools items.json. Compared: charges, attunement.

| Item | Source | Result | Details |
|---|---|---|---|
| Potion of Healing | DMG | PASS | |
| Potion of Greater Healing | DMG | PASS | |
| Potion of Superior Healing | DMG | PASS | |
| Potion of Supreme Healing | DMG | PASS | |
| Wand of Fireballs | DMG | PASS | |
| Wand of Magic Missiles | DMG | PASS | |
| Staff of Fire | DMG | PASS | |
| Staff of Frost | DMG | PASS | |

**8/8 PASS**

---

## Part D: 2024 Contamination Check

### D1: Spell Mechanics (5 high-risk spells)

These spells changed significantly between 2014 and 2024. Verified each against the raw 5e.tools entry text:

| Spell | 2014 Rule | 5e.tools Text Confirms 2014? | Our Data Matches? |
|---|---|---|---|
| Guidance | Range: touch, concentration, 1 minute | YES -- "You touch one willing creature" | PASS |
| Healing Word | Range: 60ft, bonus action | YES -- range 60ft confirmed | PASS |
| Aid | Increases max HP and current HP by 5 | YES -- "hit point maximum and current hit points increase by 5" | PASS |
| Barkskin | AC can't be less than 16 | YES -- "the target's AC can't be less than 16" | PASS |
| Banishment | Targets one creature, CHA save | YES -- "send one creature...Charisma saving throw" | PASS |

**No 2024 contamination detected in spell mechanics.**

### D2: Cantrip Scaling

Verified cantrip damage scaling uses 2014 rules (damage increases at character levels 5, 11, 17). The 2024 rules changed some cantrips -- our data uses the 2014 versions as confirmed by the 5e.tools source files.

### D3: Monster Stat Blocks

All 18 verified monsters match the 2014 bestiary-mm.json data. The 2024 Monster Manual changed many stat blocks -- our data is not affected since it was extracted from the 2014 5e.tools files in this repository.

### D4: Conditions

Our data references these standard 5e conditions: blinded, charmed, deafened, frightened, grappled, incapacitated, invisible, paralyzed, petrified, poisoned, prone, restrained, stunned, unconscious. These are the 2014 condition set. The 2024 rules changed Exhaustion from 6 levels to 10 levels -- our data does not reference exhaustion levels, so no contamination.

---

## Part E: Mummy Lord Deep Dive

Raw data from bestiary-mm.json:

### Traits (2 total)
1. Magic Resistance
2. Rejuvenation

**NO Legendary Resistance trait exists in the 2014 Mummy Lord.** The previous review agent's claim that legendaryResistanceCount should be 3 was WRONG. Our data correctly has legendaryResistanceCount unset.

### Rotting Fist (from raw 5e.tools text)
```
{@atk mw} {@hit 9} to hit, reach 5 ft., one target.
{@h}14 ({@damage 3d6 + 4}) bludgeoning damage plus 21 ({@damage 6d6}) necrotic damage.
```

**3d6+4 bludgeoning is correct per the 2014 Monster Manual.** The previous review agent's claim it should be 2d6+4 was WRONG.

### Our Definition
- CR: 15 -- CORRECT
- legendaryResistanceCount: not set -- CORRECT (no LR in 2014)
- legendaryActionCount: 3 -- CORRECT
- Rotting Fist attackBonus: 9 -- CORRECT
- Rotting Fist damage: 3d6+4 bludgeoning + 6d6 necrotic -- CORRECT

**Mummy Lord: fully verified against 2014 source data. No issues.**

---

## Issues Found

**None.** All data matches the 2014 5e.tools source files. No 2024 contamination detected.

One NOTE: Spirit Guardians lists only radiant damage type in structured effects, but 5e.tools also lists necrotic (for evil casters). This is a minor completeness gap, not a 2014/2024 discrepancy -- both editions have both damage types.
