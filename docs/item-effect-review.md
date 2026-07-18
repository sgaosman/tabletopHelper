# Item Effect Review

Data file: `backend/src/main/resources/data/item-effects/item-effect-definitions.json`

## Summary Statistics

- **Total items defined:** 104
- **By type:** Potion (37), Wondrous Item (22), Staff (13), Scroll (12), Wand (11), Weapon (4), Oil (2), Rod (2), Ring (1)
- **By pattern category:** SELF_BUFF (38), COMPLEX (12), SUMMON (12), SAVE_DAMAGE (10), SAVE_CONDITION (10), HEAL (8), ATTACK_DAMAGE (6), SAVE_DAMAGE_AND_CONDITION (3), BUFF_NO_ROLL (2), UTILITY (2), AUTO_DAMAGE (1)
- **Automatable:** 74
- **Requires manual resolution:** 30
- **Consumed on use:** 59
- **Reusable:** 45

---

## Item Definitions by Type

### Potions (37 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Potion of Healing | - | Yes | HEAL | No |  |
| Potion of Greater Healing | - | Yes | HEAL | No |  |
| Potion of Superior Healing | - | Yes | HEAL | No |  |
| Potion of Supreme Healing | - | Yes | HEAL | No |  |
| Potion of Speed | - | Yes | SELF_BUFF | No | No concentration required unlike the spell. When effect ends, creature cannot move or take actions until after its ne... |
| Potion of Heroism | - | Yes | SELF_BUFF | No | Grants 10 temp HP and Bless effect (1d4 to attacks and saves) for 1 hour, no concentration. |
| Potion of Invulnerability | - | Yes | SELF_BUFF | No |  |
| Potion of Flying | - | Yes | SELF_BUFF | No |  |
| Potion of Invisibility | - | Yes | SELF_BUFF | No | Effect ends early if the creature attacks or casts a spell. |
| Potion of Fire Breath | - | Yes | SAVE_DAMAGE | No | After drinking (action), uses bonus action to exhale fire. Grants 3 uses within 1 hour. |
| Potion of Growth | - | Yes | SELF_BUFF | No | No concentration required. |
| Potion of Diminution | - | Yes | SELF_BUFF | No | No concentration required. Could be used offensively if administered to an unwilling creature. |
| Potion of Poison | - | Yes | SAVE_DAMAGE_AND_CONDITION | Yes | Disguised as a healing potion. Complex damage reduction mechanic each turn. |
| Potion of Maximum Power | - | Yes | SELF_BUFF | No | Applies to the first damage-dealing spell of 4th level or lower within 1 minute. |
| Potion of Advantage | - | Yes | SELF_BUFF | No | Player chooses when to apply the advantage within the 1-hour window. |
| Potion of Vitality | - | Yes | HEAL | No | Primarily used out of combat for Hit Die recovery. Exhaustion removal is combat-relevant. |
| Potion of Gaseous Form | - | Yes | SELF_BUFF | No | Can end effect early as a bonus action. No concentration required. |
| Potion of Acid Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Cold Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Fire Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Force Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Lightning Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Necrotic Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Poison Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Psychic Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Radiant Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Thunder Resistance | - | Yes | SELF_BUFF | No |  |
| Potion of Hill Giant Strength | - | Yes | SELF_BUFF | No |  |
| Potion of Frost Giant Strength | - | Yes | SELF_BUFF | No | Same Strength value as Potion of Stone Giant Strength. |
| Potion of Stone Giant Strength | - | Yes | SELF_BUFF | No | Same Strength value as Potion of Frost Giant Strength. |
| Potion of Fire Giant Strength | - | Yes | SELF_BUFF | No |  |
| Potion of Cloud Giant Strength | - | Yes | SELF_BUFF | No |  |
| Potion of Storm Giant Strength | - | Yes | SELF_BUFF | No |  |
| Elixir of Health | - | Yes | HEAL | No | Does not restore HP; removes conditions and diseases only. |
| Bottled Breath | - | Yes | COMPLEX | Yes | Two mutually exclusive modes on use. If exhaled, casts Gust of Wind. If held, 1 hour of no breathing needed. Player m... |
| Potion of Giant Size | - | Yes | SELF_BUFF | Yes | Legendary rarity. HP doubling and weapon dice tripling are complex to track. When effect ends, HP above normal maximu... |
| Potion of Possibility | - | Yes | SELF_BUFF | No | Similar to Lucky feat. Two uses over 8 hours. Cannot gain additional Fragments while you have any from this potion. |

### Oils (2 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Oil of Sharpness | - | Yes | BUFF_NO_ROLL | No | Requires 1 minute to apply. Only works on slashing or piercing weapons/ammunition. |
| Oil of Etherealness | - | Yes | SELF_BUFF | Yes | Takes 10 minutes to apply, so cannot be used during combat. Included because the effect is combat-relevant if pre-app... |

### Wands (11 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Wand of Fireballs | 7 | No | SAVE_DAMAGE | No | 1 charge = 3rd-level Fireball (8d6). Each additional charge increases spell level by 1 (+1d6 damage per charge). Risk... |
| Wand of Magic Missiles | 7 | No | AUTO_DAMAGE | No | 1 charge = 1st-level Magic Missile (3 darts). Each additional charge adds 1 dart. Each dart can target a different cr... |
| Wand of Lightning Bolts | 7 | No | SAVE_DAMAGE | No | 1 charge = 3rd-level Lightning Bolt (8d6). Each additional charge increases spell level by 1 (+1d6 damage per charge)... |
| Wand of Web | 7 | No | SAVE_CONDITION | Yes | Requires concentration equivalent tracking. Web fills 20-foot cube, difficult terrain, creatures entering or starting... |
| Wand of Binding | 7 | No | SAVE_CONDITION | No | Hold Person costs 2 charges, Hold Monster costs 5 charges. Both use DC 17 WIS save. Also has defensive reaction use (... |
| Wand of Fear | 7 | No | SAVE_CONDITION | No | Two modes: Command (1 charge, single target) and Cone of Fear (2 charges, 60-foot cone). |
| Wand of Paralysis | 7 | No | SAVE_CONDITION | No |  |
| Wand of Polymorph | 7 | No | COMPLEX | Yes | Polymorph is inherently complex. DM must select beast form, track new HP pool, and handle reversion. |
| Wand of Entangle | 7 | No | SAVE_CONDITION | No | Lower DC (13) than most wands. Area becomes difficult terrain. |
| Wand of Wonder | 7 | No | COMPLEX | Yes | Completely random effects via d100 table. Cannot be automated. DC 15 for any spells with saves. |
| Wand of Winter | 7 | No | SAVE_DAMAGE | No | Unusual: destroyed on d20 roll of 20 (not 1) when last charge expended. Can cast Ray of Frost without charges. No com... |

### Staffs (13 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Staff of Fire | 10 | No | SAVE_DAMAGE | No | Uses wielder's spell save DC. Also provides passive fire resistance. |
| Staff of Frost | 10 | No | SAVE_DAMAGE | No | Uses wielder's spell save DC. Also provides passive cold resistance. |
| Staff of Healing | 10 | No | HEAL | No | Uses wielder's spellcasting ability modifier and spell save DC. |
| Staff of Striking | 10 | No | ATTACK_DAMAGE | No | Triggered on melee hit (not a separate action). +3 bonus to attack and damage rolls as a magic quarterstaff. |
| Staff of Power | 20 | No | COMPLEX | Yes | +2 magic quarterstaff with many spell options. Uses wielder's spell save DC and spell attack bonus. Retributive Strik... |
| Staff of the Magi | 50 | No | COMPLEX | Yes | Legendary artifact. +2 magic quarterstaff. Also casts Arcane Lock, Detect Magic, Enlarge/Reduce, Light, Mage Hand, Pr... |
| Staff of Swarming Insects | 10 | No | COMPLEX | Yes | Uses wielder's spell save DC. Giant Insect and Insect Plague are concentration spells. Insect Cloud is heavily obscur... |
| Staff of Thunder and Lightning | - | No | COMPLEX | Yes | +2 magic quarterstaff. Five different abilities, each usable once per dawn. The combined Thunder and Lightning option... |
| Staff of Withering | 3 | No | ATTACK_DAMAGE | No | Triggered on melee hit (not a separate action). Functions as a magic quarterstaff. |
| Staff of the Python | - | No | SUMMON | Yes | Requires DM to run the giant constrictor snake stat block. Staff is destroyed if snake reaches 0 HP. |
| Staff of the Adder | - | No | ATTACK_DAMAGE | No | Bonus action to animate for 1 minute, bonus action to revert. Snake head has AC 15 and 20 HP; if destroyed, staff is ... |
| Staff of Defense | 10 | No | SELF_BUFF | No | Must be on class spell list to use. Shield is cast as a reaction. No material components needed. |
| Staff of Charming | 10 | No | SAVE_CONDITION | No | Uses wielder's spell save DC. Also casts Comprehend Languages (1 charge, non-combat). Also functions as a magic quart... |

### Scrolls (12 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Spell Scroll (Cantrip) | - | Yes | COMPLEX | Yes | The specific spell varies per scroll. System should prompt DM to specify which spell. Casting time equals the spell's... |
| Spell Scroll (1st Level) | - | Yes | COMPLEX | Yes | The specific spell varies per scroll. If spell is above caster's level, DC 11 spellcasting ability check required. |
| Spell Scroll (2nd Level) | - | Yes | COMPLEX | Yes | The specific spell varies per scroll. If spell is above caster's level, DC 12 spellcasting ability check required. |
| Spell Scroll (3rd Level) | - | Yes | COMPLEX | Yes | The specific spell varies per scroll. If spell is above caster's level, DC 13 spellcasting ability check required. Hi... |
| Scroll of Protection from Aberrations | - | Yes | SELF_BUFF | No | 5-minute duration. Any creature type can force entry with a DC 15 Charisma check. |
| Scroll of Protection from Beasts | - | Yes | SELF_BUFF | No | 5-minute duration. Creature can force entry with DC 15 CHA check. |
| Scroll of Protection from Celestials | - | Yes | SELF_BUFF | No | 5-minute duration. Creature can force entry with DC 15 CHA check. |
| Scroll of Protection from Elementals | - | Yes | SELF_BUFF | No | 5-minute duration. Creature can force entry with DC 15 CHA check. |
| Scroll of Protection from Fey | - | Yes | SELF_BUFF | No | 5-minute duration. Creature can force entry with DC 15 CHA check. |
| Scroll of Protection from Fiends | - | Yes | SELF_BUFF | No | 5-minute duration. Creature can force entry with DC 15 CHA check. |
| Scroll of Protection from Plants | - | Yes | SELF_BUFF | No | 5-minute duration. Creature can force entry with DC 15 CHA check. |
| Scroll of Protection from Undead | - | Yes | SELF_BUFF | No | 5-minute duration. Creature can force entry with DC 15 CHA check. |

### Weapons (4 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Javelin of Lightning | - | No | ATTACK_DAMAGE | No | Property usable once per dawn. Can still be used as a magic javelin between uses. Target also takes normal javelin at... |
| Dagger of Venom | - | No | SAVE_DAMAGE_AND_CONDITION | No | +1 magic weapon. Poison coating is activated as an action (separate from the attack), usable once per dawn. Poison la... |
| Mace of Terror | 3 | No | SAVE_CONDITION | No | Also functions as a magic mace for melee attacks. |
| Devotee's Censer | - | No | HEAL | No | Magic flail. Also counts as a holy symbol. Healing aura is once per dawn. |

### Rods (2 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Rod of Retribution | 3 | No | SAVE_DAMAGE | No | Reaction-based, triggered when you take damage. All charges regain at dawn. |
| Blast Scepter | - | No | SAVE_DAMAGE | No | Can be used as arcane focus. Unlimited uses of 4th-level Thunderwave. Also pushes creatures 10 feet on failed save. |

### Rings (1 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Ring of the Ram | 3 | No | ATTACK_DAMAGE | No | Can also be used to break unattended objects (STR check with +5 per charge). 1-3 charges per use, damage and push sca... |

### Wondrous Items (22 items)

| Item Name | Charges | Consumed | Pattern Category | Manual Resolution | Notes |
|-----------|---------|----------|-----------------|-------------------|-------|
| Necklace of Fireballs | - | Yes | SAVE_DAMAGE | No | Contains 1d6+3 beads when found. Each bead is consumed. Throwing multiple beads in one action upcasts the Fireball (+... |
| Horn of Blasting | - | No | SAVE_DAMAGE_AND_CONDITION | No | 20% chance of exploding on each use (10d6 fire damage to blower, horn destroyed). No limit on uses otherwise. |
| Bead of Force | - | Yes | SAVE_DAMAGE | Yes | Typically found in groups of 1d4+4. The force sphere enclosure is complex to track. REVIEW: sphere interaction needs ... |
| Iron Bands of Bilarro | - | No | ATTACK_DAMAGE | No | Usable once per dawn. Item destroyed if STR check succeeds. If STR check fails, no further attempts possible by that ... |
| Dust of Disappearance | - | Yes | BUFF_NO_ROLL | No | Affects all creatures within 10 feet, not just the user. Each creature's invisibility ends independently if they atta... |
| Dust of Sneezing and Choking | - | Yes | SAVE_CONDITION | Yes | Appears to be Dust of Disappearance. Suffocation mechanic is complex: creature suffocates until it succeeds on a save... |
| Cape of the Mountebank | - | No | UTILITY | No | Usable once per dawn. Leaves smoke clouds that lightly obscure. Combat-relevant for tactical repositioning. |
| Pearl of Power | - | No | UTILITY | No | Usable once per dawn. Does not need to be used during combat but is combat-relevant for spell slot recovery. |
| Pipes of Haunting | 3 | No | SAVE_CONDITION | No | Requires proficiency with wind instruments. Creatures that succeed on save are immune for 24 hours. |
| Helm of Brilliance | - | No | COMPLEX | Yes | Gem counts determined randomly when found. Risk: on d20 roll of 1 when taking fire damage from failed spell save, rem... |
| Gem of Brightness | 50 | No | SAVE_CONDITION | No | 50 charges total, non-recharging. Becomes a nonmagical jewel (50 gp) when charges exhausted. The blinding beam (1 cha... |
| Bag of Tricks, Gray | - | No | SUMMON | Yes | Random creature from table. DM must run creature stat block. Three pulls per dawn. |
| Bag of Tricks, Rust | - | No | SUMMON | Yes | Random creature from table. DM must run creature stat block. Three pulls per dawn. |
| Bag of Tricks, Tan | - | No | SUMMON | Yes | Random creature from table. DM must run creature stat block. Three pulls per dawn. |
| Elemental Gem, Blue Sapphire | - | Yes | SUMMON | Yes | DM must run elemental stat block. Elemental obeys commands but may become hostile if concentration-like control is lost. |
| Elemental Gem, Emerald | - | Yes | SUMMON | Yes | DM must run elemental stat block. |
| Elemental Gem, Red Corundum | - | Yes | SUMMON | Yes | DM must run elemental stat block. |
| Elemental Gem, Yellow Diamond | - | Yes | SUMMON | Yes | DM must run elemental stat block. |
| Horn of Valhalla, Silver | - | No | SUMMON | Yes | No proficiency requirement for silver horn. Usable once per 7 days. DM must run berserker stat blocks. |
| Horn of Valhalla, Brass | - | No | SUMMON | Yes | Requires proficiency with all simple weapons. If used without meeting requirement, summoned berserkers attack you. Us... |
| Horn of Valhalla, Bronze | - | No | SUMMON | Yes | Requires proficiency with medium armor. If used without meeting requirement, summoned berserkers attack you. Usable o... |
| Horn of Valhalla, Iron | - | No | SUMMON | Yes | Requires proficiency with all martial weapons. If used without meeting requirement, summoned berserkers attack you. U... |

---

## Items Requiring Manual Resolution

These items cannot be fully automated and require DM intervention:

1. **Potion of Poison** (Potion, SAVE_DAMAGE_AND_CONDITION): Disguised as a healing potion. Complex damage reduction mechanic each turn.
1. **Oil of Etherealness** (Oil, SELF_BUFF): Takes 10 minutes to apply, so cannot be used during combat. Included because the effect is combat-relevant if pre-applied. REVIEW: borderline inclusion.
1. **Wand of Web** (Wand, SAVE_CONDITION): Requires concentration equivalent tracking. Web fills 20-foot cube, difficult terrain, creatures entering or starting turn must save vs restrained.
1. **Wand of Polymorph** (Wand, COMPLEX): Polymorph is inherently complex. DM must select beast form, track new HP pool, and handle reversion.
1. **Wand of Wonder** (Wand, COMPLEX): Completely random effects via d100 table. Cannot be automated. DC 15 for any spells with saves.
1. **Staff of Power** (Staff, COMPLEX): +2 magic quarterstaff with many spell options. Uses wielder's spell save DC and spell attack bonus. Retributive Strike is a last-resort option that destroys the staff.
1. **Staff of the Magi** (Staff, COMPLEX): Legendary artifact. +2 magic quarterstaff. Also casts Arcane Lock, Detect Magic, Enlarge/Reduce, Light, Mage Hand, Protection from Evil and Good at no charge cost. Uses wielder's spell save DC. Spell Absorption as reaction. Retributive Strike option.
1. **Staff of Swarming Insects** (Staff, COMPLEX): Uses wielder's spell save DC. Giant Insect and Insect Plague are concentration spells. Insect Cloud is heavily obscuring.
1. **Staff of Thunder and Lightning** (Staff, COMPLEX): +2 magic quarterstaff. Five different abilities, each usable once per dawn. The combined Thunder and Lightning option does not expend the individual uses. REVIEW: complex tracking of five separate daily uses.
1. **Staff of the Python** (Staff, SUMMON): Requires DM to run the giant constrictor snake stat block. Staff is destroyed if snake reaches 0 HP.
1. **Spell Scroll (Cantrip)** (Scroll, COMPLEX): The specific spell varies per scroll. System should prompt DM to specify which spell. Casting time equals the spell's normal casting time.
1. **Spell Scroll (1st Level)** (Scroll, COMPLEX): The specific spell varies per scroll. If spell is above caster's level, DC 11 spellcasting ability check required.
1. **Spell Scroll (2nd Level)** (Scroll, COMPLEX): The specific spell varies per scroll. If spell is above caster's level, DC 12 spellcasting ability check required.
1. **Spell Scroll (3rd Level)** (Scroll, COMPLEX): The specific spell varies per scroll. If spell is above caster's level, DC 13 spellcasting ability check required. Higher save DC and attack bonus than lower-level scrolls.
1. **Bead of Force** (Wondrous Item, SAVE_DAMAGE): Typically found in groups of 1d4+4. The force sphere enclosure is complex to track. REVIEW: sphere interaction needs manual DM management.
1. **Dust of Sneezing and Choking** (Wondrous Item, SAVE_CONDITION): Appears to be Dust of Disappearance. Suffocation mechanic is complex: creature suffocates until it succeeds on a save. If it fails 3 saves in a row, it takes 2d10 bludgeoning damage per failed save thereafter.
1. **Bottled Breath** (Potion, COMPLEX): Two mutually exclusive modes on use. If exhaled, casts Gust of Wind. If held, 1 hour of no breathing needed. Player must choose at time of use.
1. **Helm of Brilliance** (Wondrous Item, COMPLEX): Gem counts determined randomly when found. Risk: on d20 roll of 1 when taking fire damage from failed spell save, remaining gems explode (various damage per gem type within 60 feet, DEX DC 17 for half). Loses magic when all gems removed.
1. **Bag of Tricks, Gray** (Wondrous Item, SUMMON): Random creature from table. DM must run creature stat block. Three pulls per dawn.
1. **Bag of Tricks, Rust** (Wondrous Item, SUMMON): Random creature from table. DM must run creature stat block. Three pulls per dawn.
1. **Bag of Tricks, Tan** (Wondrous Item, SUMMON): Random creature from table. DM must run creature stat block. Three pulls per dawn.
1. **Elemental Gem, Blue Sapphire** (Wondrous Item, SUMMON): DM must run elemental stat block. Elemental obeys commands but may become hostile if concentration-like control is lost.
1. **Elemental Gem, Emerald** (Wondrous Item, SUMMON): DM must run elemental stat block.
1. **Elemental Gem, Red Corundum** (Wondrous Item, SUMMON): DM must run elemental stat block.
1. **Elemental Gem, Yellow Diamond** (Wondrous Item, SUMMON): DM must run elemental stat block.
1. **Horn of Valhalla, Silver** (Wondrous Item, SUMMON): No proficiency requirement for silver horn. Usable once per 7 days. DM must run berserker stat blocks.
1. **Horn of Valhalla, Brass** (Wondrous Item, SUMMON): Requires proficiency with all simple weapons. If used without meeting requirement, summoned berserkers attack you. Usable once per 7 days.
1. **Horn of Valhalla, Bronze** (Wondrous Item, SUMMON): Requires proficiency with medium armor. If used without meeting requirement, summoned berserkers attack you. Usable once per 7 days.
1. **Horn of Valhalla, Iron** (Wondrous Item, SUMMON): Requires proficiency with all martial weapons. If used without meeting requirement, summoned berserkers attack you. Usable once per 7 days.
1. **Potion of Giant Size** (Potion, SELF_BUFF): Legendary rarity. HP doubling and weapon dice tripling are complex to track. When effect ends, HP above normal maximum become temp HP. REVIEW: significant stat modifications require careful tracking.

---

## Items Flagged for Review

These items have uncertain classification or borderline inclusion:

- **Oil of Etherealness**: Takes 10 minutes to apply, so cannot be used during combat. Included because the effect is combat-relevant if pre-applied. REVIEW: borderline inclusion.
- **Staff of Thunder and Lightning**: +2 magic quarterstaff. Five different abilities, each usable once per dawn. The combined Thunder and Lightning option does not expend the individual uses. REVIEW: complex tracking of five separate daily uses.
- **Bead of Force**: Typically found in groups of 1d4+4. The force sphere enclosure is complex to track. REVIEW: sphere interaction needs manual DM management.
- **Potion of Giant Size**: Legendary rarity. HP doubling and weapon dice tripling are complex to track. When effect ends, HP above normal maximum become temp HP. REVIEW: significant stat modifications require careful tracking.

---

## Design Notes for Implementation

### Charge Recharge Rates

The `rechargeRate` field uses these conventions:
- `dawn_1d6+1` — regains 1d6+1 charges at dawn (most wands)
- `dawn_1d6+4` — regains 1d6+4 charges at dawn (most staves with 10 charges)
- `dawn_2d8+4` — regains 2d8+4 charges at dawn (Staff of Power)
- `dawn_4d6+2` — regains 4d6+2 charges at dawn (Staff of the Magi)
- `dawn_1d3` — regains 1d3 charges at dawn (low-charge items)
- `dawn_1d8+2` — regains 1d8+2 charges at dawn (Staff of Charming)
- `dawn_all` — regains all charges at dawn (single-use-per-day items)
- `null` — no recharging (consumables, non-recharging items)

### Destruction on Last Charge

Many wands and staves risk destruction when the last charge is expended:
- `d20_roll_1` — roll d20; on a 1, item is destroyed
- `d20_roll_1_vanish` — on a 1, item vanishes in a flash of light
- `d20_roll_1_nonmagical` — on a 1, item becomes nonmagical
- `d20_roll_1_swarm` — on a 1, a swarm consumes and destroys it
- `d20_roll_20_melt` — on a 20 (not 1), item melts (Wand of Winter)
- `d20_roll_20_regain` — on a 20, regains 1d12+1 charges (Staff of the Magi)

### Spell References

Items that cast spells include a `spellReference` field linking to the spell name.
The implementation should cross-reference the spell effect definitions for full
mechanical details when an item casts a known spell.

### Save DCs

Items use two types of save DCs:
- **Fixed DC** (e.g., 13, 15, 17, 18) — the item's own DC, independent of the user
- **"caster"** — uses the wielder's spell save DC (staves that require attunement by a spellcaster)

### Potion Administration

In D&D 5e, drinking a potion is an action. Some DMs use a house rule allowing
potions as a bonus action. The system should support both options as a campaign setting.

### Scope Decisions

**Included but borderline:**
- Oil of Etherealness (10-minute application time makes it non-combat, but effect is combat-relevant)
- Potion of Gaseous Form (primarily escape/utility, but has combat implications)
- Cape of the Mountebank (utility teleportation, but tactically important)
- Pearl of Power (spell slot recovery, not direct combat effect)
- Blast Scepter (adventure-specific but mechanically interesting)

**Excluded (passive-only bonuses, mundane, or non-combat):**
- +1/+2/+3 Wand of the War Mage (passive bonus only)
- +1/+2/+3 Rod of the Pact Keeper (passive bonus only)
- Cloak of Displacement (passive defensive effect)
- Brooch of Shielding (passive resistance)
- Sun Blade (weapon with passive bonus, extra radiant vs undead is triggered but part of normal attack flow)
- Oathbow (complex sworn enemy mechanic but primarily a weapon)
- Mace of Disruption (extra damage vs undead/fiends is triggered on hit)
- Mace of Smiting (extra damage on crit)
- Scimitar of Speed (bonus action attack)
- Ioun Stones (passive stat bonuses)
- Periapts (passive condition immunities)
- Scarab of Protection (passive save advantage)
- Wand of Pyrotechnics (harmless light, no combat effect)
- Wand of Smiles/Scowls (cosmetic effects)
- Wand of Secrets/Magic Detection (detection, not combat)
- Wand of Enemy Detection (detection only)
- Wand of Conducting (cosmetic)
- Staff of Adornment/Birdcalls/Flowers (cosmetic)
- Rod of the Vonindod (Locate Object only)
- Alchemy Jug (utility)
- Immovable Rod (utility)
- Decanter of Endless Water (utility)
- Folding Boat, Portable Hole, Carpets of Flying (travel/utility)
- Rod of Security, Rod of Absorption (complex, niche)
- Figurines of Wondrous Power (complex summon, many variants, niche)
- Wand of Orcus (artifact-level, campaign-specific)
- Blackstaff (Waterdeep-specific artifact)
