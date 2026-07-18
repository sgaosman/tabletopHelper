# Race Trait Analysis - D&D 5e

Comprehensive analysis of all racial traits from the 5e.tools dataset,
categorised for combat automation in QuestKeeper VTT.

Total race entries analysed: **134** (including reprints across sources)
Total traits catalogued: **749**

## Category Legend

| Category | Description |
|----------|-------------|
| STAT_BONUS | Ability score increase (fixed or choose-any under Tasha's/MotM rules) |
| COMBAT_ACTIVE | Usable as action/bonus action/reaction in combat (needs a UI button) |
| COMBAT_PASSIVE | Always-on combat modifier (tracked automatically, no button needed) |
| PROFICIENCY | Weapon/armor/tool/skill/language proficiency grant |
| MOVEMENT | Speed modifiers, fly/swim/climb speed |
| SENSE | Darkvision, blindsight, tremorsense, etc. |
| RESISTANCE | Damage resistance/immunity, condition advantage/immunity |
| FLAVOUR | No direct mechanical combat impact |

## Summary Statistics

| Category | Count |
|----------|-------|
| STAT_BONUS | 117 |
| COMBAT_ACTIVE | 86 |
| COMBAT_PASSIVE | 69 |
| PROFICIENCY | 160 |
| MOVEMENT | 73 |
| SENSE | 64 |
| RESISTANCE | 92 |
| FLAVOUR | 88 |
| **Total** | **749** |

---

## Aarakocra

### Aarakocra (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +2 WIS |
| Movement | MOVEMENT | 20 ft walk, 50 ft fly |
| Languages | PROFICIENCY | Auran |
| Dive Attack | FLAVOUR | If you are flying and dive at least 30 ft. straight toward a target and then hit it with a melee weapon attack, the attack deals an extra 1d6 damage to the target. |
| Talons | COMBAT_PASSIVE | You are proficient with your unarmed strikes, which deal 1d4 slashing damage on a hit. |

### Aarakocra (EEPC)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +1 WIS |
| Movement | MOVEMENT | 25 ft walk, 50 ft fly |
| Languages | PROFICIENCY | Common, one other language, Auran |
| Flight | MOVEMENT | You have a flying speed of 50 feet. To use this speed, you can't be wearing medium or heavy armor. |
| Talons | COMBAT_PASSIVE | Your talons are natural weapons, which you can use to make unarmed strikes. If you hit with them, you deal slashing damage equal to 1d4 + your Strength modifier, instead of the bludgeoning damage normal for an unarmed strike. |

### Aarakocra (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, fly (equal to walking speed) |
| Innate Spellcasting | COMBAT_ACTIVE | gust of wind (at level 3, 1/day) |
| Flight | MOVEMENT | Because of your wings, you have a flying speed equal to your walking speed. You can't use this flying speed if you're wearing medium or heavy armor. |
| Talons | COMBAT_PASSIVE | You have talons that you can use to make unarmed strikes. When you hit with them, the strike deals 1d6 + your Strength modifier slashing damage, instead of the bludgeoning damage normal for an unarmed strike. |
| Wind Caller | COMBAT_ACTIVE | Starting at 3rd level, you can cast the gust of wind spell with this trait, without requiring a material component. Once you cast the spell with this trait, you can't do so again until you finish a long rest. You can also cast the spell using any spell slots you have of 2nd level or higher. Intel... |

## Aasimar

### Aasimar (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 WIS, +2 CHA |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic, radiant damage |
| Languages | PROFICIENCY | Common, Celestial |
| Innate Spellcasting | COMBAT_ACTIVE | light (cantrip) (at will); lesser restoration (1/day, at level 3); daylight (1/day, at level 5) |
| Celestial Legacy | COMBAT_ACTIVE | You know the light cantrip. Once you reach 3rd level, you can cast the lesser restoration spell once with this trait, and you regain the ability to do so when you finish a long rest. Once you reach 5th level, you can cast the daylight spell once with this trait, and you regain the ability to do s... |

### Aasimar (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic, radiant damage |
| Innate Spellcasting | COMBAT_ACTIVE | light (cantrip) (at will) |
| Healing Hands | COMBAT_ACTIVE | As an action, you can touch a creature and roll a number of d4s equal to your proficiency bonus. The creature regains a number of hit points equal to the total rolled. Once you use this trait, you can't use it again until you finish a long rest. |
| Light Bearer | FLAVOUR | You know the light cantrip. Charisma is your spellcasting ability for it. |
| Celestial Revelation | COMBAT_ACTIVE | When you reach 3rd level, choose one of the revelation options below. Thereafter, you can use a bonus action to unleash the celestial energy within yourself, gaining the benefits of that revelation. Your transformation lasts for 1 minute or until you end it as a bonus action. Once you transform u... |

#### Aasimar; Necrotic Shroud

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic, radiant damage |
| Innate Spellcasting | COMBAT_ACTIVE | light (cantrip) (at will) |
| Healing Hands | COMBAT_ACTIVE | As an action, you can touch a creature and roll a number of d4s equal to your proficiency bonus. The creature regains a number of hit points equal to the total rolled. Once you use this trait, you can't use it again until you finish a long rest. |
| Light Bearer | FLAVOUR | You know the light cantrip. Charisma is your spellcasting ability for it. |
| Celestial Revelation (Necrotic Shroud) | COMBAT_ACTIVE | When you reach 3rd level, you can use a bonus action to transform yourself. Your transformation lasts for 1 minute or until you end it as a bonus action. Once you transform using your revelation, you can't use it again until you finish a long rest. Your eyes briefly become pools of darkness, and ... |

#### Aasimar; Radiant Consumption

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic, radiant damage |
| Innate Spellcasting | COMBAT_ACTIVE | light (cantrip) (at will) |
| Healing Hands | COMBAT_ACTIVE | As an action, you can touch a creature and roll a number of d4s equal to your proficiency bonus. The creature regains a number of hit points equal to the total rolled. Once you use this trait, you can't use it again until you finish a long rest. |
| Light Bearer | FLAVOUR | You know the light cantrip. Charisma is your spellcasting ability for it. |
| Celestial Revelation (Radiant Consumption) | COMBAT_ACTIVE | When you reach 3rd level, you can use a bonus action to transform yourself. Your transformation lasts for 1 minute or until you end it as a bonus action. Once you transform using your revelation, you can't use it again until you finish a long rest. Searing light temporarily radiates from your eye... |

#### Aasimar; Radiant Soul

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic, radiant damage |
| Innate Spellcasting | COMBAT_ACTIVE | light (cantrip) (at will) |
| Healing Hands | COMBAT_ACTIVE | As an action, you can touch a creature and roll a number of d4s equal to your proficiency bonus. The creature regains a number of hit points equal to the total rolled. Once you use this trait, you can't use it again until you finish a long rest. |
| Light Bearer | FLAVOUR | You know the light cantrip. Charisma is your spellcasting ability for it. |
| Celestial Revelation (Radiant Soul) | COMBAT_ACTIVE | When you reach 3rd level, you can use a bonus action to transform yourself. Your transformation lasts for 1 minute or until you end it as a bonus action. Once you transform using your revelation, you can't use it again until you finish a long rest. Two luminous, spectral wings sprout from your ba... |

### Aasimar (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic, radiant damage |
| Languages | PROFICIENCY | Common, Celestial |
| Innate Spellcasting | COMBAT_ACTIVE | light (cantrip) (at will) |
| Healing Hands | COMBAT_ACTIVE | As an action, you can touch a creature and cause it to regain a number of hit points equal to your level. Once you use this trait, you can't use it again until you finish a long rest. |
| Light Bearer | FLAVOUR | You know the light cantrip. Charisma is your spellcasting ability for it. |

## Aetherborn

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA, +1 to 2 of (STR/DEX/CON/INT/WIS) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic damage |
| Skill Proficiency | PROFICIENCY | Intimidation |
| Languages | PROFICIENCY | Common, 2 other language(s) of choice |
| Gift of the Aetherborn | FLAVOUR | An unknown aetherborn, desperately seeking a means to extend their short life, discovered a process of transformation that prolonged their existence—by giving them the ability to feed on the life essence of other beings. Since then, other aetherborn have learned and carried out this monstrous tra... |

#### Variant; Gifted Aetherborn

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA, +1 to 2 of (STR/DEX/CON/INT/WIS) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic damage |
| Skill Proficiency | PROFICIENCY | Intimidation |
| Languages | PROFICIENCY | Common, 2 other language(s) of choice |
| Gift of the Aetherborn | FLAVOUR | An unknown aetherborn, desperately seeking a means to extend their short life, discovered a process of transformation that prolonged their existence—by giving them the ability to feed on the life essence of other beings. Since then, other aetherborn have learned and carried out this monstrous tra... |
| Drain Life | COMBAT_ACTIVE | You gain a natural attack that deals 1d6 necrotic damage and restores the same number of hit points to you. However, if you goes for 7 days without dealing this damage, your hit point maximum is reduced by 1d6 per week. This reduction can't be removed until you have used your Drain Life ability a... |

## Astral Elf

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Perception |
| Innate Spellcasting | COMBAT_ACTIVE | dancing lights (cantrip) (at will); light (cantrip) (at will); sacred flame (cantrip) (at will) |
| Astral Fire | FLAVOUR | You know one of the following cantrips of your choice: dancing lights, light, or sacred flame. Intelligence, Wisdom, or Charisma is your spellcasting ability for it (choose when you select this race). |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws you make to avoid or end the charmed condition on yourself. |
| Starlight Step | COMBAT_ACTIVE | As a bonus action, you can magically teleport up to 30 feet to an unoccupied space you can see. You can use this trait a number of times equal to your proficiency bonus, and you regain all expended uses when you finish a long rest. |
| Astral Trance | FLAVOUR | You don't need to sleep, and magic can't put you to sleep. You can finish a long rest in 4 hours if you spend those hours in a trancelike meditation, during which you remain conscious. Whenever you finish this trance, you gain proficiency in one skill of your choice and with one weapon or tool of... |

## Autognome

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Damage Resistance | RESISTANCE | Resistance to poison damage |
| Condition Immunity | RESISTANCE | Immune to disease |
| Tool Proficiency | PROFICIENCY | 2 tool(s) of choice |
| Armored Casing | COMBAT_PASSIVE | You are encased in thin metal or some other durable material. While you aren't wearing armor, your base Armor Class is 13 + your Dexterity modifier. |
| Built for Success | COMBAT_PASSIVE | You can add a d4 to one attack roll, ability check, or saving throw you make, and you can do so after seeing the d20 roll but before the effects of the roll are resolved. You can use this trait a number of times equal to your proficiency bonus, and you regain all expended uses when you finish a l... |
| Healing Machine | COMBAT_PASSIVE | If the mending spell is cast on you, you can spend a Hit Die, roll it, and regain a number of hit points equal to the roll plus your Constitution modifier (minimum of 1 hit point). In addition, your creator designed you to benefit from several spells that preserve life but that normally don't aff... |
| Mechanical Nature | RESISTANCE | You have resistance to poison damage and immunity to disease, and you have advantage on saving throws against being paralyzed or poisoned. You don't need to eat, drink, or breathe. |
| Sentry's Rest | FLAVOUR | When you take a long rest, you spend at least 6 hours in an inactive, motionless state, instead of sleeping. In this state, you appear inert, but you remain conscious. |
| Specialized Design | FLAVOUR | You gain two tool proficiencies of your choice, selected from the Player's Handbook. |

## Aven

### Aven (PSA)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX |
| Movement | MOVEMENT | 25 ft walk, 30 ft fly |
| Languages | PROFICIENCY | Common, one other language |
| Flight | MOVEMENT | You have a flying speed of 30 feet. You can't use your flying speed while you wear medium or heavy armor. (If your campaign uses the variant rule for encumbrance, you can't use your flying speed if you are encumbered.) |

### Aven (PSD)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +2 WIS |
| Movement | MOVEMENT | 25 ft walk, 30 ft fly |
| Skill Proficiency | PROFICIENCY | Perception |
| Languages | PROFICIENCY | Common, one other language |
| Flight | MOVEMENT | You have a flying speed of 30 feet. You can't use your flying speed while you wear medium or heavy armor. (If your campaign uses the variant rule for encumbrance, you can't use your flying speed if you are encumbered.) |
| Hawkeyed | PROFICIENCY | You have proficiency in the Perception skill. In addition, attacking at long range doesn't impose disadvantage on your ranged weapon attack rolls. |

## Bugbear

### Bugbear (ERLW)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 DEX |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Stealth |
| Languages | PROFICIENCY | Common, Goblin |
| Long-Limbed | COMBAT_PASSIVE | When you make a melee attack on your turn, your reach for it is 5 feet greater than normal. |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |
| Sneaky | PROFICIENCY | You are proficient in the Stealth skill. |
| Surprise Attack | COMBAT_PASSIVE | If you surprise a creature and hit it with an attack on your first turn in combat, the attack deals an extra 2d6 damage to it. You can use this trait only once per combat. |

### Bugbear (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Stealth |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws you make to avoid or end the charmed condition on yourself. |
| Long-Limbed | COMBAT_PASSIVE | When you make a melee attack on your turn, your reach for it is 5 feet greater than normal. |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |
| Sneaky | PROFICIENCY | You are proficient in the Stealth skill. In addition, without squeezing, you can move through and stop in a space large enough for a Small creature. |
| Surprise Attack | COMBAT_PASSIVE | If you hit a creature with an attack roll, the creature takes an extra 2d6 damage if it hasn't taken a turn yet in the current combat. |

### Bugbear (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 DEX |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Stealth |
| Languages | PROFICIENCY | Common, Goblin |
| Long-Limbed | COMBAT_PASSIVE | When you make a melee attack on your turn, your reach for it is 5 feet greater than normal. |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |
| Sneaky | PROFICIENCY | You are proficient in the Stealth skill. |
| Surprise Attack | COMBAT_PASSIVE | If you surprise a creature and hit it with an attack on your first turn in combat, the attack deals an extra 2d6 damage to it. You can use this trait only once per combat. |

## Bullywug

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | -2 INT, -2 CHA |
| Movement | MOVEMENT | 20 ft walk, 40 ft swim |
| Languages | PROFICIENCY | one other language |
| Amphibious | MOVEMENT | You can breathe air and water. |
| Speak with Frogs and Toads | FLAVOUR | You can communicate simple concepts to frogs and toads when you speak in Bullywug. |
| Swamp Camouflage | FLAVOUR | You have advantage on Dexterity (Stealth) checks made to hide in swampy terrain. |
| Standing Leap | MOVEMENT | Your long jump is up to 20 feet and your high jump is up to 10 feet, with or without a running start. |

## Centaur

### Centaur (GGR)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 WIS |
| Speed | MOVEMENT | 40 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Animal handling, Medicine, Nature, Survival |
| Languages | PROFICIENCY | Common, Sylvan |
| Fey | RESISTANCE | Your creature type is fey, rather than humanoid. |
| Charge | COMBAT_PASSIVE | If you move at least 30 feet straight toward a target and then hit it with a melee weapon attack on the same turn, you can immediately follow that attack with a bonus action, making one attack against the target with your hooves. |
| Hooves | COMBAT_PASSIVE | Your hooves are natural melee weapons, which you can use to make unarmed strikes. If you hit with them, you deal bludgeoning damage equal to 1d4 + your Strength modifier, instead of the bludgeoning damage normal for an unarmed strike. |
| Equine Build | MOVEMENT | You count as one size larger when determining your carrying capacity and the weight you can push or drag. In addition, any climb that requires hands and feet is especially difficult for you because of your equine legs. When you make such a climb, each foot of movement costs you 4 extra feet, inst... |
| Survivor | FLAVOUR | You have proficiency in one of the following skills of your choice: Animal Handling, Medicine, Nature, or Survival. |

### Centaur (MOT)

*Reprint of Centaur (GGR) with minor flavour changes. See original for traits.*

### Centaur (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Speed | MOVEMENT | 40 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Animal handling, Medicine, Nature, Survival |
| Charge | COMBAT_PASSIVE | If you move at least 30 feet straight toward a target and then hit it with a melee weapon attack on the same turn, you can immediately follow that attack with a bonus action, making one attack against the target with your hooves. |
| Equine Build | MOVEMENT | You count as one size larger when determining your carrying capacity and the weight you can push or drag. In addition, any climb that requires hands and feet is especially difficult for you because of your equine legs. When you make such a climb, each foot of movement costs you 4 extra feet inste... |
| Hooves | COMBAT_PASSIVE | You have hooves that you can use to make unarmed strikes. When you hit with them, the strike deals 1d6 + your Strength modifier bludgeoning damage, instead of the bludgeoning damage normal for an unarmed strike. |
| Natural Affinity | FLAVOUR | Your fey connection to nature gives you an intuitive connection to the natural world and the animals within it. You therefore have proficiency in one of the following skills of your choice: Animal Handling, Medicine, Nature, or Survival. |

## Changeling

### Changeling (ERLW)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA, +1 to 1 of (STR/DEX/CON/INT/WIS) |
| Skill Proficiency | PROFICIENCY | choose 2 from: Deception, Insight, Intimidation, Persuasion |
| Languages | PROFICIENCY | Common, 2 other language(s) of choice |
| Shapechanger | FLAVOUR | As an action, you can change your appearance and your voice. You determine the specifics of the changes, including your coloration, hair length, and sex. You can also adjust your height and weight, but not so much that your size changes. You can make yourself appear as a member of another race, t... |

### Changeling (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Skill Proficiency | PROFICIENCY | choose 2 from: Deception, Insight, Intimidation, Performance, Persuasion |
| Shapechanger | FLAVOUR | As an action, you change your appearance and your voice. You determine the specifics of the changes, including your coloration, hair length, and sex. You can also adjust your height between Medium and Small. You can make yourself appear as a member of another race, though none of your game statis... |

## Custom Lineage

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 to 1 of (STR/DEX/CON/INT/WIS/CHA) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | 1 skill(s) of choice |
| Languages | PROFICIENCY | Common, 1 other language(s) of choice |
| Feat | FLAVOUR | You gain one feat of your choice for which you qualify. |
| Variable Trait | FLAVOUR | You gain one of the following options of your choice: (a) darkvision with a range of 60 feet or (b) proficiency in one skill of your choice. |

#### Custom Lineage; Darkvision

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 to 1 of (STR/DEX/CON/INT/WIS/CHA) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | 1 skill(s) of choice |
| Languages | PROFICIENCY | Common, 1 other language(s) of choice |
| Feat | FLAVOUR | You gain one feat of your choice for which you qualify. |
| Variable Trait; Darkvision | FLAVOUR | You gain darkvision with a range of 60 feet. |

#### Custom Lineage; Skill Proficiency

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 to 1 of (STR/DEX/CON/INT/WIS/CHA) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | 1 skill(s) of choice |
| Languages | PROFICIENCY | Common, 1 other language(s) of choice |
| Feat | FLAVOUR | You gain one feat of your choice for which you qualify. |
| Variable Trait; Skill Proficiency | PROFICIENCY | You gain proficiency in one skill of your choice. |

## Deep Gnome

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 120 ft |
| Innate Spellcasting | COMBAT_ACTIVE | disguise self (1/day, at level 3); nondetection (1/day, at level 5) |
| Gift of the Svirfneblin | COMBAT_ACTIVE | Starting at 3rd level, you can cast the disguise self spell with this trait. Starting at 5th level, you can also cast the nondetection spell with it, without requiring a material component. Once you cast either of these spells with this trait, you can't cast that spell with it again until you fin... |
| Gnomish Magic Resistance | FLAVOUR | You have advantage on Intelligence, Wisdom, and Charisma saving throws against spells. |
| Svirfneblin Camouflage | COMBAT_ACTIVE | When you make a Dexterity (Stealth) check, you can make the check with advantage. You can use this trait a number of times equal to your proficiency bonus, and you regain all expended uses when you finish a long rest. |

## Dhampir

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 35 ft walk, climb (equal to walking speed) |
| Darkvision | SENSE | 60 ft |
| Ancestral Legacy | PROFICIENCY | If you replace a race with this lineage, you can keep the following elements of that race: any skill proficiencies you gained from it and any climbing, flying, or swimming speed you gained from it. If you don't keep any of those elements or you choose this lineage at character creation, you gain ... |
| Deathless Nature | RESISTANCE | You don't need to breathe. |
| Spider Climb | MOVEMENT | You have a climbing speed equal to your walking speed. In addition, at 3rd level, you can move up, down, and across vertical surfaces and upside down along ceilings, while leaving your hands free. |
| Vampiric Bite | COMBAT_ACTIVE | Your fanged bite is a natural weapon, which counts as a simple melee weapon with which you are proficient. You add your Constitution modifier, instead of your Strength modifier, to the attack and damage rolls when you attack with this bite. It deals 1d4 piercing damage on a hit. While you are mis... |

## Dragonborn

### Dragonborn (PHB)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 CHA |
| Damage Resistance | RESISTANCE | Resistance to choose from: acid, cold, fire, lightning, poison damage |
| Languages | PROFICIENCY | Common, Draconic |
| Draconic Ancestry | FLAVOUR | You have draconic ancestry. Choose one type of dragon from the Draconic Ancestry table. Your breath weapon and damage resistance are determined by the dragon type, as shown in the table. [see table] |
| Breath Weapon | COMBAT_ACTIVE | You can use your action to exhale destructive energy. Your draconic ancestry determines the size, shape, and damage type of the exhalation. When you use your breath weapon, each creature in the area of the exhalation must make a saving throw, the type of which is determined by your draconic ances... |

### Dragonborn (Chromatic) (FTD)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Damage Resistance | RESISTANCE | Resistance to choose from: acid, lightning, poison, fire, cold damage |
| Chromatic Ancestry | FLAVOUR | You have a chromatic dragon ancestor, granting you a special magical affinity. Choose one kind of dragon from the Chromatic Ancestry table. This determines the damage type for your other traits, as shown in the table. [see table] |
| Breath Weapon | COMBAT_ACTIVE | When you take the Attack action on your turn, you can replace one of your attacks with an exhalation of magical energy in a 30-foot line that is 5 feet wide. Each creature in that area must make a Dexterity saving throw (DC = 8 + your Constitution modifier + your proficiency bonus). On a failed s... |
| Chromatic Warding | COMBAT_ACTIVE | Starting at 5th level, as an action, you can channel your draconic energy to protect yourself. For 1 minute, you become immune to the damage type associated with your Chromatic Ancestry. Once you use this trait, you can't do so again until you finish a long rest. |

### Dragonborn (Gem) (FTD)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Damage Resistance | RESISTANCE | Resistance to choose from: force, radiant, psychic, thunder, necrotic damage |
| Gem Ancestry | FLAVOUR | You have a gem dragon ancestor, granting you a special magical affinity. Choose one kind of dragon from the Gem Ancestry table. This determines the damage type for your other traits, as shown in the table. [see table] |
| Breath Weapon | COMBAT_ACTIVE | When you take the Attack action on your turn, you can replace one of your attacks with an exhalation of magical energy in a 15-foot cone. Each creature in that area must make a Dexterity saving throw (DC = 8 + your Constitution modifier + your proficiency bonus). On a failed save, the creature ta... |
| Psionic Mind | FLAVOUR | You can send telepathic messages to any creature you can see within 30 feet of you. You don't need to share a language with the creature for it to understand these messages, but it must be able to understand at least one language to comprehend them. |
| Gem Flight | COMBAT_ACTIVE | Starting at 5th level, you can use a bonus action to manifest spectral wings on your body. These wings last for 1 minute. For the duration, you gain a flying speed equal to your walking speed and can hover. Once you use this trait, you can't do so again until you finish a long rest. |

### Dragonborn (Metallic) (FTD)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Damage Resistance | RESISTANCE | Resistance to choose from: fire, lightning, acid, cold damage |
| Metallic Ancestry | FLAVOUR | You have a metallic dragon ancestor, granting you a special magical affinity. Choose one kind of dragon from the Metallic Ancestry table. This determines the damage type for your other traits, as shown in the table. [see table] |
| Breath Weapon | COMBAT_ACTIVE | When you take the Attack action on your turn, you can replace one of your attacks with an exhalation of magical energy in a 15-foot cone. Each creature in that area must make a Dexterity saving throw (DC = 8 + your Constitution modifier + your proficiency bonus). On a failed save, the creature ta... |
| Metallic Breath Weapon | COMBAT_ACTIVE | At 5th level, you gain a second breath weapon. When you take the Attack action on your turn, you can replace one of your attacks with an exhalation in a 15-foot cone. The save DC for this breath is 8 + your Constitution modifier + your proficiency bonus. Whenever you use this trait, choose one: E... |

## Duergar

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 120 ft |
| Damage Resistance | RESISTANCE | Resistance to poison damage |
| Innate Spellcasting | COMBAT_ACTIVE | enlarge/reduce (1/day, at level 3); invisibility (1/day, at level 5) |
| Duergar Magic | COMBAT_ACTIVE | Starting at 3rd level, you can cast the enlarge/reduce spell on yourself with this trait, without requiring a material component. Starting at 5th level, you can also cast the invisibility spell on yourself with this trait, without requiring a material component. Once you cast either of these spel... |
| Dwarven Resilience | RESISTANCE | You have advantage on saving throws you make to avoid or end the poisoned condition on yourself. You also have resistance to poison damage. |
| Psionic Fortitude | RESISTANCE | You have advantage on saving throws you make to avoid or end the charmed or stunned condition on yourself. |

## Dwarf

### Dwarf (PHB)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON |
| Speed | MOVEMENT | 25 ft |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to poison damage |
| Weapon Proficiency | PROFICIENCY | battleaxe, handaxe, light hammer, warhammer |
| Tool Proficiency | PROFICIENCY | choose 1 from: smith's tools, brewer's supplies, mason's tools |
| Languages | PROFICIENCY | Common, Dwarvish |
| Dwarven Resilience | RESISTANCE | You have advantage on saving throws against poison, and you have resistance against poison damage. |
| Stonecunning | FLAVOUR | Whenever you make an Intelligence (History) check related to the origin of stonework, you are considered proficient in the History skill and add double your proficiency bonus to the check, instead of your normal proficiency bonus. |

### Dwarf (Kaladesh) (PSK)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON, +1 WIS |
| Speed | MOVEMENT | 25 ft |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to poison damage |
| Languages | PROFICIENCY | Common, Dwarvish |
| Dwarven Resilience | RESISTANCE | You have advantage on saving throws against poison, and you have resistance against poison damage. |
| Dwarven Toughness | COMBAT_PASSIVE | Your hit point maximum increases by 1, and it increases by 1 every time you gain a level. |

## Eladrin

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Perception |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws you make to avoid or end the charmed condition on yourself. |
| Fey Step | COMBAT_ACTIVE | As a bonus action, you can magically teleport up to 30 feet to an unoccupied space you can see. You can use this trait a number of times equal to your proficiency bonus, and you regain all expended uses when you finish a long rest. When you reach 3rd level, your Fey Step gain an additional effect... |
| Trance | FLAVOUR | You don't need to sleep, and magic can't put you to sleep. You can finish a long rest in 4 hours if you spend those hours in a trancelike meditation, during which you retain consciousness. Whenever you finish this trance, you can change your season, and you can gain two proficiencies that you don... |

## Elf

### Elf (PHB)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Perception |
| Languages | PROFICIENCY | Common, Elvish |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws against being charmed, and magic can't put you to sleep. |
| Trance | FLAVOUR | Elves don't need to sleep. Instead, they meditate deeply, remaining semiconscious, for 4 hours a day. (The Common word for such meditation is "trance.") While meditating, you can dream after a fashion; such dreams are actually mental exercises that have become reflexive through years of practice.... |

### Elf (Kaladesh) (PSK)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +1 WIS |
| Darkvision | SENSE | 60 ft |
| Weapon Proficiency | PROFICIENCY | longsword, shortsword, shortbow, longbow |
| Skill Proficiency | PROFICIENCY | Perception |
| Languages | PROFICIENCY | Common, Elvish |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws against being charmed, and magic can't put you to sleep. |
| Trance | FLAVOUR | Elves don't need to sleep. Instead, they meditate deeply, remaining semiconscious, for 4 hours a day. (The Common word for such meditation is "trance.") While meditating, you can dream after a fashion; such dreams are actually mental exercises that have become reflexive through years of practice.... |
| Elf Culture | FLAVOUR | The elves of Kaladesh don't organize themselves into nations or tribes. Still, they recognize three distinct cultural groups among their kind—though in truth these groupings are more like attitudes or alignments with regard to the rest of society and the use of technology. Choose one of these cul... |

### Elf (Zendikar) (PSZ)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 WIS |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Perception |
| Languages | PROFICIENCY | Common, Elvish |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws against being charmed, and magic can't put you to sleep. |

## Fairy

### Fairy (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, fly (equal to walking speed) |
| Innate Spellcasting | COMBAT_ACTIVE | druidcraft (cantrip) (at will); faerie fire (1/day, at level 3); enlarge/reduce (1/day, at level 5) |
| Fairy Magic | COMBAT_ACTIVE | You know the druidcraft cantrip. Starting at 3rd level, you can cast the faerie fire spell with this trait. Starting at 5th level, you can also cast the enlarge/reduce spell with this trait. Once you cast faerie fire or enlarge/reduce with this trait, you can't cast that spell with it again until... |
| Flight | MOVEMENT | Because of your wings, you have a flying speed equal to your walking speed. You can't use this flying speed if you're wearing medium or heavy armor. |

### Fairy (WBtW)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, fly (equal to walking speed) |
| Innate Spellcasting | COMBAT_ACTIVE | druidcraft (cantrip) (at will); faerie fire (1/day, at level 3); enlarge/reduce (1/day, at level 5) |
| Flight | MOVEMENT | Because of your wings, you have a flying speed equal to your walking speed. You can't use this flying speed if you're wearing medium or heavy armor. |
| Fairy Magic | COMBAT_ACTIVE | You know the druidcraft cantrip. Starting at 3rd level, you can cast the faerie fire spell with this trait. Starting at 5th level, you can also cast the enlarge/reduce spell with this trait. Once you cast faerie fire or enlarge/reduce with this trait, you can't cast that spell with it again until... |

## Firbolg

### Firbolg (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Innate Spellcasting | COMBAT_ACTIVE | detect magic (at level 1, 1/day); disguise self (at level 1, 1/day) |
| Firbolg Magic | COMBAT_ACTIVE | You can cast detect magic and disguise self spells with this trait. When you use this version of disguise self, you can seem up to 3 feet shorter or taller. Once you cast either of these spells with this trait, you can't cast that spell with it again until you finish a long rest. You can also cas... |
| Hidden Step | COMBAT_ACTIVE | As a bonus action, you can magically turn invisible until the start of your next turn or until you attack, make a damage roll, or force someone to make a saving throw. You can use this trait a number of times equal to your proficiency bonus, and you regain all expended uses when you finish a long... |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |
| Speech of Beast and Leaf | FLAVOUR | You have the ability to communicate in a limited manner with Beasts, Plants, and vegetation. They can understand the meaning of your words, though you have no special ability to understand them in return. You have advantage on all Charisma checks you make to influence them. |

### Firbolg (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 WIS, +1 STR |
| Languages | PROFICIENCY | Common, Elvish, Giant |
| Innate Spellcasting | COMBAT_ACTIVE | detect magic (1/rest); disguise self (1/rest) |
| Firbolg Magic | COMBAT_ACTIVE | You can cast detect magic and disguise self with this trait, using Wisdom as your spellcasting ability for them. Once you cast either spell, you can't cast it again with this trait until you finish a short or long rest. When you use this version of disguise self, you can seem up to 3 feet shorter... |
| Hidden Step | COMBAT_ACTIVE | As a bonus action, you can magically turn invisible until the start of your next turn or until you attack, make a damage roll, or force someone to make a saving throw. Once you use this trait, you can't use it again until you finish a short or long rest. |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |
| Speech of Beast and Leaf | FLAVOUR | You have the ability to communicate in a limited manner with beasts and plants. They can understand the meaning of your words, though you have no special ability to understand them in return. You have advantage on all Charisma checks you make to influence them. |

## Genasi

### Genasi (EEPC)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON |
| Languages | PROFICIENCY | Common, Primordial |

### Genasi (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |

## Giff

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, swim (equal to walking speed) |
| Weapon Proficiency | PROFICIENCY | firearms |
| Astral Spark | COMBAT_PASSIVE | Your psychic connection to the Astral Plane enables you to mystically access a spark of divine power, which you can channel through your weapons. When you hit a target with a simple or martial weapon, you can cause the target to take extra force damage equal to your proficiency bonus. You can use... |
| Firearms Mastery | PROFICIENCY | You have a mystical connection to firearms that traces back to the gods of the giff, who delighted in such weapons. You have proficiency with all firearms and ignore the LD property of any firearm. In addition, attacking at long range with a firearm doesn't impose disadvantage on your attack roll. |
| Hippo Build | FLAVOUR | You have advantage on Strength-based ability checks and Strength saving throws. In addition, you count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |

## Gith

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 INT |
| Languages | PROFICIENCY | Common, one other language |

## Githyanki

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Damage Resistance | RESISTANCE | Resistance to psychic damage |
| Tool Proficiency | PROFICIENCY | 1 tool(s) of choice |
| Skill Proficiency | PROFICIENCY | 1 skill(s) of choice |
| Innate Spellcasting | COMBAT_ACTIVE | mage hand (cantrip) (at will); jump (1/day, at level 3); misty step (1/day, at level 5) |
| Astral Knowledge | PROFICIENCY | You can mystically access a reservoir of experiences of entities connected to the Astral Plane. Whenever you finish a long rest, you gain proficiency in one skill of your choice and with one weapon or tool of your choice, selected from the Player's Handbook, as you momentarily project your consci... |
| Githyanki Psionics | COMBAT_ACTIVE | You know the mage hand cantrip, and the hand is invisible when you cast the cantrip with this trait. Starting at 3rd level, you can cast the jump spell with this trait. Starting at 5th level, you can also cast misty step with it. Once you cast jump or misty step with this trait, you can't cast th... |
| Psychic Resilience | RESISTANCE | You have resistance to psychic damage. |

## Githzerai

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Damage Resistance | RESISTANCE | Resistance to psychic damage |
| Innate Spellcasting | COMBAT_ACTIVE | mage hand (cantrip) (at will); shield (1/day, at level 3); detect thoughts (1/day, at level 5) |
| Githzerai Psionics | COMBAT_ACTIVE | You know the mage hand cantrip, and the hand is invisible when you cast the cantrip with this trait. Starting at 3rd level, you can cast the shield spell with this trait. Starting at 5th level, you can also cast the detect thoughts spell with it. Once you cast shield or detect thoughts spell with... |
| Psychic Resilience | RESISTANCE | You have resistance to psychic damage. |

## Gnoll

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, -2 INT |
| Darkvision | SENSE | 60 ft |
| Bite | COMBAT_PASSIVE | Your fanged maw is a natural weapon, which you can use to make unarmed strikes. If you hit with it, you deal piercing damage equal to 1d4 + your Strength modifier, instead of the bludgeoning damage normal for an unarmed strike. |
| Rampage | COMBAT_ACTIVE | When you reduce a creature to 0 hit points with a melee attack on your turn, you can take a bonus action to move up to half your speed and make a bite attack. |

## Gnome

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 INT |
| Speed | MOVEMENT | 25 ft |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common, Gnomish |
| Gnome Cunning | RESISTANCE | You have advantage on all Intelligence, Wisdom, and Charisma saving throws against magic. |

## Gnome (Deep)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 STR, +2 DEX |
| Speed | MOVEMENT | 20 ft |
| Darkvision | SENSE | 120 ft |
| Languages | PROFICIENCY | Gnomish, Terran, Undercommon |
| Innate Spellcasting | COMBAT_ACTIVE | nondetection (at will); blindness/deafness (at will); blur (at will); disguise self (at will) |
| Stone Camouflage | FLAVOUR | You have advantage on Dexterity (Stealth) checks to hide in rocky terrain. |
| Gnome Cunning | FLAVOUR | You have advantage on Intelligence, Wisdom, and Charisma saving throws against magic. |
| Superior Darkvision | FLAVOUR | Accustomed to life underground, you have superior vision in dark and dim conditions. You can see in dim light within 120 feet of you as if it were bright light, and in darkness as if it were dim light. You can't discern color in darkness, only shades of gray. |

## Goblin

### Goblin (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | -2 STR, +2 DEX |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common, Goblin |
| Nimble Escape | COMBAT_PASSIVE | You can take the Disengage or Hide action as a bonus action on each of your turns. |

### Goblin (ERLW)

*Reprint of Goblin (VGM) with minor flavour changes. See original for traits.*

### Goblin (GGR)

*Reprint of Goblin (VGM) with minor flavour changes. See original for traits.*

### Goblin (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws you make to avoid or end the charmed condition on yourself. |
| Fury of the Small | COMBAT_ACTIVE | When you damage a creature with an attack or a spell and the creature's size is larger than yours, you can cause the attack or spell to deal extra damage to the creature. The extra damage equals your proficiency bonus. You can use this trait a number of times equal to your proficiency bonus, rega... |
| Nimble Escape | COMBAT_PASSIVE | You can take the Disengage or Hide action as a bonus action on each of your turns. |

### Goblin (PSZ)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Speed | MOVEMENT | 25 ft |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to fire, psychic damage |
| Languages | PROFICIENCY | Common, Goblin |
| Grit | RESISTANCE | You have resistance to fire damage and psychic damage. In addition, when you are wearing no armor, your AC is equal to 11 + your Dexterity modifier. |

### Goblin (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +1 CON |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common, Goblin |
| Fury of the Small | COMBAT_ACTIVE | When you damage a creature with an attack or a spell and the creature's size is larger than yours, you can cause the attack or spell to deal extra damage to the creature. The extra damage equals your level. Once you use this trait, you can't use it again until you finish a short or long rest. |
| Nimble Escape | COMBAT_PASSIVE | You can take the Disengage or Hide action as a bonus action on each of your turns. |

### Goblin (Dankwood) (AWM)

*Reprint of Goblin (VGM) with minor flavour changes. See original for traits.*

## Goliath

### Goliath (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Damage Resistance | RESISTANCE | Resistance to cold damage |
| Skill Proficiency | PROFICIENCY | Athletics |
| Little Giant | PROFICIENCY | You have proficiency in the Athletics skill, and you count as one size larger when determining your carrying weight and the weight you can push, drag, or lift. |
| Mountain Born | RESISTANCE | You have resistance to cold damage. You also naturally acclimate to high altitudes, even if you've never been to one. This includes elevations above 20,000 feet. |
| Stone's Endurance | COMBAT_ACTIVE | You can supernaturally draw on unyielding stone to shrug off harm. When you take damage, you can use your reaction to roll a d12. Add your Constitution modifier to the number rolled and reduce the damage by that total. You can use this trait a number of times equal to your proficiency bonus, and ... |

### Goliath (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 CON |
| Damage Resistance | RESISTANCE | Resistance to cold damage |
| Skill Proficiency | PROFICIENCY | Athletics |
| Languages | PROFICIENCY | Common, Giant |
| Natural Athlete | PROFICIENCY | You have proficiency in the Athletics skill. |
| Stone's Endurance | COMBAT_ACTIVE | You can focus yourself to occasionally shrug off injury. When you take damage, you can use your reaction to roll a d12. Add your Constitution modifier to the number rolled, and reduce the damage by that total. After you use this trait, you can't use it again until you finish a short or long rest. |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |
| Mountain Born | RESISTANCE | You have resistance to cold damage. You're also acclimated to high altitude, including elevations above 20,000 feet. |

## Grimlock

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, -2 CHA |
| Blindsight | SENSE | 30 ft |
| Languages | PROFICIENCY | Undercommon |
| Keen Hearing and Smell | FLAVOUR | You have advantage on Wisdom (Perception) checks that rely on hearing or smell. |
| Stone Camouflage | FLAVOUR | You have advantage on Dexterity (Stealth) checks made to hide in rocky terrain. |

## Grung

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +1 CON |
| Movement | MOVEMENT | 25 ft walk, 25 ft climb |
| Damage Immunity | RESISTANCE | Immune to poison damage |
| Condition Immunity | RESISTANCE | Immune to poisoned |
| Skill Proficiency | PROFICIENCY | Perception |
| Languages | PROFICIENCY | one other language |
| Arboreal Alertness | PROFICIENCY | You have proficiency in the Perception skill. |
| Amphibious | MOVEMENT | You can breathe air and water. |
| Poison Immunity | RESISTANCE | You're immune to poison damage and the poisoned condition. |
| Poisonous Skin | COMBAT_PASSIVE | Any creature that grapples you or otherwise comes into direct contact with your skin must succeed on a 12 Constitution saving throw or become poisoned for 1 minute. A poisoned creature no longer in direct contact with you can repeat the saving throw at the end of each of its turns, ending the eff... |
| Standing Leap | MOVEMENT | Your long jump is up to 25 feet and your high jump is up to 15 feet, with or without a running start. |
| Water Dependency | FLAVOUR | If you fail to immerse yourself in water for at least 1 hour during a day, you suffer one level of exhaustion at the end of that day. You can only recover from this exhaustion through magic or by immersing yourself in water for at least 1 hour. |

## Hadozee

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, climb (equal to walking speed) |
| Dexterous Feet | FLAVOUR | As a bonus action, you can use your feet to manipulate an object, open or close a door or container, or pick up or set down a Tiny object. |
| Glide | MOVEMENT | When you fall at least 10 feet above the ground, you can use your reaction to extend your skin membranes to glide horizontally a number of feet equal to your walking speed, and you take 0 damage from the fall. You determine the direction of the glide. |
| Hadozee Dodge | COMBAT_ACTIVE | The magic that runs in your veins heightens your natural defenses. When you take damage, you can use your reaction to roll a d6. Add your proficiency bonus to the number rolled, and reduce the damage you take by an amount equal to that total (minimum of 0 damage). You can use this trait a number ... |

## Half-Elf

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA, +1 to 2 of (STR/DEX/CON/INT/WIS) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | 2 skill(s) of choice |
| Languages | PROFICIENCY | Common, Elvish, 1 other language(s) of choice |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws against being charmed, and magic can't put you to sleep. |

## Half-Orc

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 CON |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Intimidation |
| Languages | PROFICIENCY | Common, Orc |
| Relentless Endurance | COMBAT_PASSIVE | When you are reduced to 0 hit points but not killed outright, you can drop to 1 hit point instead. You can't use this feature again until you finish a long rest. |
| Savage Attacks | COMBAT_PASSIVE | When you score a critical hit with a melee weapon attack, you can roll one of the weapon's damage dice one additional time and add it to the extra damage of the critical hit. |

## Halfling

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX |
| Speed | MOVEMENT | 25 ft |
| Languages | PROFICIENCY | Common, Halfling |
| Lucky | COMBAT_PASSIVE | When you roll a 1 on an attack roll, ability check, or saving throw, you can reroll the die and must use the new roll. |
| Brave | RESISTANCE | You have advantage on saving throws against being frightened. |
| Halfling Nimbleness | FLAVOUR | You can move through the space of any creature that is of a size larger than yours. |

## Harengon

### Harengon (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Skill Proficiency | PROFICIENCY | Perception |
| Hare-Trigger | FLAVOUR | You can add your proficiency bonus to your initiative rolls. |
| Lucky Footwork | COMBAT_ACTIVE | When you fail a Dexterity saving throw, you can use your reaction to roll a d4 and add it to the save, potentially turning the failure into a success. You can't use this reaction if you're prone or your speed is 0. |
| Rabbit Hop | COMBAT_ACTIVE | As a bonus action, you can jump a number of feet equal to five times your proficiency bonus, without provoking opportunity attacks. You can use this trait only if your speed is greater than 0. You can use it a number of times equal to your proficiency bonus, and you regain all expended uses when ... |

### Harengon (WBtW)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Skill Proficiency | PROFICIENCY | Perception |
| Hare-Trigger | FLAVOUR | You can add your proficiency bonus to your initiative rolls. |
| Lucky Footwork | COMBAT_ACTIVE | When you fail a Dexterity saving throw, you can use your reaction to roll a d4 and add it to the save, potentially turning the failure into a success. You can't use this reaction if you're prone or your speed is 0. |
| Rabbit Hop | COMBAT_ACTIVE | As a bonus action, you can jump a number of feet equal to five times your proficiency bonus, without provoking opportunity attacks. You can use this trait only if your speed is greater than 0. You can use it a number of times equal to your proficiency bonus, and you regain all expended uses when ... |

## Hexblood

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | 2 skill(s) of choice |
| Innate Spellcasting | COMBAT_ACTIVE | disguise self (at will); hex (at will) |
| Ancestral Legacy | PROFICIENCY | If you replace a race with this lineage, you can keep the following elements of that race: any skill proficiencies you gained from it and any climbing, flying, or swimming speed you gained from it. If you don't keep any of those elements or you choose this lineage at character creation, you gain ... |
| Eerie Token | COMBAT_ACTIVE | As a bonus action, you can harmlessly remove a lock of your hair, one of your nails, or one of your teeth. This token is imbued with magic until you finish a long rest. While the token is imbued in this way, you can take these actions: Once you create a token using this feature, you can't do so a... |
| Hex Magic | COMBAT_ACTIVE | You can cast the disguise self and hex spells with this trait. Once you cast either of these spells with this trait, you can't cast that spell with it again until you finish a long rest. You can also cast these spells using any spell slots you have. Intelligence, Wisdom, or Charisma is your spell... |

## Hobgoblin

### Hobgoblin (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common, Goblin |
| Martial Advantage | FLAVOUR | Once per turn, you can deal an extra 2d6 damage to a creature you hit with a weapon attack if that creature is within 5 ft. of an ally of yours that isn't incapacitated. |

### Hobgoblin (ERLW)

*Reprint of Hobgoblin (VGM) with minor flavour changes. See original for traits.*

### Hobgoblin (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws you make to avoid or end the charmed condition on yourself. |
| Fey Gift | COMBAT_ACTIVE | You can use this trait to take the Help action as a bonus action, and you can do so a number of times equal to your proficiency bonus. You regain all expended uses when you finish a long rest. Starting at 3rd level, choose one of the options below each time you take the Help action with this trait: |
| Fortune from the Many | COMBAT_PASSIVE | If you miss with an attack roll or fail an ability check or a saving throw, you can draw on your bonds of reciprocity to gain a bonus to the roll equal to the number of allies you can see within 30 feet of you (maximum bonus of +3). You can use this trait a number of times equal to your proficien... |

### Hobgoblin (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON, +1 INT |
| Darkvision | SENSE | 60 ft |
| Weapon Proficiency | PROFICIENCY | choose from:  |
| Armor Proficiency | PROFICIENCY | light armor |
| Languages | PROFICIENCY | Common, Goblin |
| Martial Training | PROFICIENCY | You are proficient with two martial weapons of your choice and with light armor. |
| Saving Face | COMBAT_PASSIVE | Hobgoblins are careful not to show weakness in front of their allies, for fear of losing status. If you miss with an attack roll or fail an ability check or a saving throw, you can gain a bonus to the roll equal to the number of allies you can see within 30 feet of you (maximum bonus of +5). Once... |

## Human

### Human (PHB)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 to all ability scores |
| Languages | PROFICIENCY | Common, 1 other language(s) of choice |

### Human (Innistrad) (PSI)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Languages | PROFICIENCY | Common, 1 other language(s) of choice |

### Human (Ixalan) (PSX)

*Reprint of Human (PHB) with minor flavour changes. See original for traits.*

### Human (Kaladesh) (PSK)

*Reprint of Human (PHB) with minor flavour changes. See original for traits.*

### Human (Zendikar) (PSZ)

*Reprint of Human (PHB) with minor flavour changes. See original for traits.*

## Kalashtar

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 WIS, +1 CHA |
| Damage Resistance | RESISTANCE | Resistance to psychic damage |
| Languages | PROFICIENCY | Common, one other language, 1 other language(s) of choice |
| Dual Mind | RESISTANCE | You have advantage on all Wisdom saving throws. |
| Mind Link | FLAVOUR | You can speak telepathically to any creature you can see, provided the creature is within a number of feet of you equal to 10 times your level. You don't need to share a language with the creature for it to understand your telepathic utterances, but the creature must be able to understand at leas... |
| Severed from Dreams | FLAVOUR | Kalashtar sleep, but they don't connect to the plane of dreams as other creatures do. Instead, their minds draw from the memories of their otherworldly spirit while they sleep. As such, you are immune to spells and other magical effects that require you to dream, like dream, but not to spells and... |

## Kender

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Skill Proficiency | PROFICIENCY | choose 1 from: Insight, Investigation, Sleight of hand, Stealth, Survival |
| Fearless | RESISTANCE | You have advantage on saving throws you make to avoid or end the frightened condition on yourself. When you fail a saving throw to avoid or end the frightened condition on yourself, you can choose to succeed instead. Once you succeed on a saving throw in this way, you can't do so again until you ... |
| Kender Curiosity | PROFICIENCY | Thanks to the mystical origin of your people, you gain proficiency with one of the following skills of your choice: Insight, Investigation, Sleight of Hand, Stealth, or Survival. |
| Taunt | COMBAT_ACTIVE | You have an extraordinary ability to fluster creatures. As a bonus action, you can unleash a string of provoking words at a creature within 60 feet of yourself that can hear and understand you. The target must succeed on a Wisdom saving throw, or it has disadvantage on attack rolls against target... |

## Kenku

### Kenku (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common, Auran |
| Ambusher | FLAVOUR | In the first round of a combat, you have advantage on attack rolls against any creature who is surprised. |
| Mimicry | FLAVOUR | You can mimic any sounds you have heard, including voices. A creature that hears the sounds can tell they are imitations with a successful 14 Wisdom (Insight) check. |

### Kenku (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Skill Proficiency | PROFICIENCY | 2 skill(s) of choice |
| Expert Duplication | FLAVOUR | When you copy writing or craftwork produced by yourself or someone else, you have advantage on any ability checks you make to produce an exact duplicate. |
| Kenku Recall | FLAVOUR | Thanks to your supernaturally good memory, you have proficiency in two skills of your choice. Moreover, when you make an ability check using any skill in which you have proficiency, you can give yourself advantage on the check before rolling the d20. You can give yourself advantage in this way a ... |
| Mimicry | FLAVOUR | You can accurately mimic sounds you have heard, including voices. A creature that hears the sounds you make can tell they are imitations only with a successful Wisdom (Insight) check against a DC of 8 + your proficiency bonus + your Charisma modifier. |

### Kenku (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +1 WIS |
| Skill Proficiency | PROFICIENCY | choose 2 from: Acrobatics, Deception, Sleight of hand, Stealth |
| Languages | PROFICIENCY | Common, Auran |
| Expert Forgery | FLAVOUR | You can duplicate other creatures' handwriting and craftwork. You have advantage on all checks made to produce forgeries or duplicates of existing objects. |
| Kenku Training | PROFICIENCY | You are proficient in your choice of two of the following skills: Acrobatics, Deception, Stealth, and Sleight of Hand. |
| Mimicry | FLAVOUR | You can mimic sounds you have heard, including voices. A creature that hears the sounds can tell they are imitations with a successful Wisdom (Insight) check opposed by your Charisma (Deception) check. |

## Khenra

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +1 STR |
| Speed | MOVEMENT | 35 ft |
| Weapon Proficiency | PROFICIENCY | longsword, spear, javelin |
| Languages | PROFICIENCY | Common, one other language |
| Khenra Weapon Training | PROFICIENCY | You have proficiency with the khopesh (longsword), spear, and javelin. |
| Khenra Twins | FLAVOUR | If your twin is alive and you can see your twin, whenever you roll a 1 on an attack roll, ability check, or saving throw, you can reroll the die and must use the new roll. If your twin is dead (or if you were born without a twin), you can't be frightened. |

## Kobold

### Kobold (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, -4 STR |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common, Draconic |
| Pack Tactics | COMBAT_PASSIVE | You have advantage on an attack roll against a creature if at least one of your allies is within 5 feet of the creature and the ally isn't incapacitated. |
| Sunlight Sensitivity | COMBAT_PASSIVE | You have disadvantage on attack rolls and on Wisdom (Perception) checks that rely on sight when you, the target of your attack, or whatever you are trying to perceive is in direct sunlight. |

### Kobold (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Arcana, Investigation, Medicine, Sleight of hand, Survival |
| Innate Spellcasting | COMBAT_ACTIVE | choose 1 cantrip |
| Draconic Cry | COMBAT_ACTIVE | As a bonus action, you let out a cry at your enemies within 10 feet of you. Until the start of your next turn, you and your allies have advantage on attack rolls against any of those enemies who could hear you. You can use this trait a number of times equal to your proficiency bonus, and you rega... |
| Kobold Legacy | FLAVOUR | Kobold's connections to dragons can manifest in unpredictable ways in an individual kobold. Choose one of the following legacy options for your kobold. |

#### Kobold; Craftiness

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Arcana, Investigation, Medicine, Sleight of hand, Survival |
| Innate Spellcasting | COMBAT_ACTIVE | choose 1 cantrip |
| Draconic Cry | COMBAT_ACTIVE | As a bonus action, you let out a cry at your enemies within 10 feet of you. Until the start of your next turn, you and your allies have advantage on attack rolls against any of those enemies who could hear you. You can use this trait a number of times equal to your proficiency bonus, and you rega... |
| Kobold Legacy (Craftiness) | PROFICIENCY | You have proficiency in one of the following skills of your choice: Arcana, Investigation, Medicine, Sleight of Hand, or Survival. |

#### Kobold; Defiance

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Arcana, Investigation, Medicine, Sleight of hand, Survival |
| Innate Spellcasting | COMBAT_ACTIVE | choose 1 cantrip |
| Draconic Cry | COMBAT_ACTIVE | As a bonus action, you let out a cry at your enemies within 10 feet of you. Until the start of your next turn, you and your allies have advantage on attack rolls against any of those enemies who could hear you. You can use this trait a number of times equal to your proficiency bonus, and you rega... |
| Kobold Legacy (Defiance) | RESISTANCE | You have advantage on saving throws to avoid or end the frightened condition on yourself. |

#### Kobold; Draconic Sorcery

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Arcana, Investigation, Medicine, Sleight of hand, Survival |
| Innate Spellcasting | COMBAT_ACTIVE | choose 1 cantrip |
| Draconic Cry | COMBAT_ACTIVE | As a bonus action, you let out a cry at your enemies within 10 feet of you. Until the start of your next turn, you and your allies have advantage on attack rolls against any of those enemies who could hear you. You can use this trait a number of times equal to your proficiency bonus, and you rega... |
| Kobold Legacy (Draconic Sorcery) | COMBAT_ACTIVE | You know one cantrip of your choice from the sorcerer spell list. Intelligence, Wisdom, or Charisma is your spellcasting ability for that cantrip (choose when you select this race). |

### Kobold (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common, Draconic |
| Grovel, Cower, and Beg | COMBAT_ACTIVE | As an action on your turn, you can cower pathetically to distract nearby foes. Until the end of your next turn, your allies gain advantage on attack rolls against enemies within 10 feet of you that you can see. Once you use this trait, you can't use it again until you finish a short or long rest. |
| Pack Tactics | COMBAT_PASSIVE | You have advantage on an attack roll against a creature if at least one of your allies is within 5 feet of the creature and the ally isn't incapacitated. |
| Sunlight Sensitivity | COMBAT_PASSIVE | You have disadvantage on attack rolls and on Wisdom (Perception) checks that rely on sight when you, the target of your attack, or whatever you are trying to perceive is in direct sunlight. |

## Kor

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +1 WIS |
| Movement | MOVEMENT | 30 ft walk, 30 ft climb |
| Skill Proficiency | PROFICIENCY | Athletics, Acrobatics |
| Languages | PROFICIENCY | Common, one other language |
| Brave | RESISTANCE | You have advantage on saving throws against being frightened. |
| Climbing | MOVEMENT | You also have a climbing speed of 30 feet as long as you are not encumbered or wearing heavy armor. |
| Kor Climbing | MOVEMENT | You have proficiency in the Athletics and Acrobatics skills. |
| Lucky | COMBAT_PASSIVE | When you roll a 1 on the d20 for an attack roll, ability check, or saving throw, you can reroll the die and must use the new roll. |

## Kuo-Toa

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Movement | MOVEMENT | 30 ft walk, 30 ft swim |
| Darkvision | SENSE | 120 ft |
| Languages | PROFICIENCY | Undercommon |
| Amphibious | MOVEMENT | You can breathe air and water. |
| Otherworldly Perception | FLAVOUR | You can sense the presence of any creature within 30 feet of you that is invisible or on the Ethereal Plane. You can pinpoint such a creature that is moving. |
| Slippery | FLAVOUR | You have advantage on ability checks and saving throws made to escape a grapple. |
| Sunlight Sensitivity | COMBAT_PASSIVE | While in sunlight, you have disadvantage on attack rolls, as well as on Wisdom (Perception) checks that rely on sight. |
| Superior Darkvision | FLAVOUR | You have superior vision in dark and dim conditions. You can see in dim light within 120 feet of you as if it were bright light, and in darkness as if it were dim light. You can't discern color in darkness, only shades of gray. |

## Leonin

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 STR, +2 CON |
| Speed | MOVEMENT | 35 ft |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Athletics, Intimidation, Perception, Survival |
| Languages | PROFICIENCY | Common, one other language |
| Claws | COMBAT_PASSIVE | Your claws are natural weapons, which you can use to make unarmed strikes. If you hit with them, you can deal slashing damage equal to 1d4 + your Strength modifier, instead of the bludgeoning damage normal for an unarmed strike. |
| Daunting Roar | COMBAT_ACTIVE | As a bonus action, you can let out an especially menacing roar. Creatures of your choice within 10 feet of you that can hear you must succeed on a Wisdom saving throw or become frightened of you until the end of your next turn. The DC of the save equals 8 + your proficiency bonus + your Constitut... |

## Lizardfolk

### Lizardfolk (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, -2 INT |
| Movement | MOVEMENT | 30 ft walk, 30 ft swim |
| Languages | PROFICIENCY | Draconic |
| Hold Breath | FLAVOUR | You can hold your breath for up to 15 minutes at a time. |
| Natural Armor | COMBAT_PASSIVE | Your scales function as natural armor, granting you a +3 bonus to Armor Class. |

### Lizardfolk (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, swim (equal to walking speed) |
| Skill Proficiency | PROFICIENCY | choose 2 from: Animal handling, Medicine, Nature, Perception, Stealth, Survival |
| Bite | COMBAT_PASSIVE | You have a fanged maw that you can use to make unarmed strikes. When you hit with it, the strike deals 1d6 + your Strength modifier slashing damage, instead of the bludgeoning damage normal for an unarmed strike. |
| Hold Breath | FLAVOUR | You can hold your breath for up to 15 minutes at a time. |
| Hungry Jaws | COMBAT_ACTIVE | You can throw yourself into a feeding frenzy. As a bonus action, you can make a special attack with your Bite. If the attack hits, it deals its normal damage, and you gain temporary hit points equal to your proficiency bonus. You can use this trait a number of times equal to your proficiency bonu... |
| Natural Armor | COMBAT_PASSIVE | You have tough, scaly skin. When you aren't wearing armor, your base AC is 13 + Dexterity modifier. You can use your natural armor to determine your AC if the armor you wear would leave you with a lower AC. A shield's benefits apply as normal while you use your natural armor. |

### Lizardfolk (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON, +1 WIS |
| Movement | MOVEMENT | 30 ft walk, 30 ft swim |
| Skill Proficiency | PROFICIENCY | choose 2 from: Animal handling, Nature, Perception, Stealth, Survival |
| Languages | PROFICIENCY | Common, Draconic |
| Swim Speed | MOVEMENT | You have a swimming speed of 30 feet. |
| Bite | COMBAT_PASSIVE | Your fanged maw is a natural weapon, which you can use to make unarmed strikes. If you hit with it, you deal piercing damage equal to 1d6 + your Strength modifier, instead of the bludgeoning damage normal for an unarmed strike. |
| Cunning Artisan | FLAVOUR | As part of a short rest, you can harvest bone and hide from a slain beast, construct, dragon, monstrosity, or plant creature of size small or larger to create one of the following items: a shield, a club, a javelin, or 1d4 dart or blowgun needle. To use this trait, you need a blade, such as a dag... |
| Hold Breath | FLAVOUR | You can hold your breath for up to 15 minutes at a time. |
| Hunter's Lore | PROFICIENCY | You gain proficiency with two of the following skills of your choice: Animal Handling, Nature, Perception, Stealth, and Survival. |
| Natural Armor | COMBAT_PASSIVE | You have tough, scaly skin. When you aren't wearing armor, your AC is 13 + your Dexterity modifier. You can use your natural armor to determine your AC if the armor you wear would leave you with a lower AC. A shield's benefits apply as normal while you use your natural armor. |
| Hungry Jaws | COMBAT_ACTIVE | In battle, you can throw yourself into a vicious feeding frenzy. As a bonus action, you can make a special attack with your bite. If the attack hits, it deals its normal damage, and you gain temporary hit points equal to your Constitution modifier (minimum of 1), and you can't use this trait agai... |

## Locathah

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 DEX |
| Movement | MOVEMENT | 30 ft walk, 30 ft swim |
| Skill Proficiency | PROFICIENCY | Athletics, Perception |
| Languages | PROFICIENCY | Aquan, Common |
| Natural Armor | COMBAT_PASSIVE | You have tough, scaly skin. When you aren't wearing armor, your AC is 12 + your Dexterity modifier. You can use your natural armor to determine your AC if the armor you wear would leave you with a lower AC. A shield's benefits apply as normal while you use your natural armor. |
| Observant & Athletic | PROFICIENCY | You have proficiency in the Athletics and Perception skills. |
| Leviathan Will | RESISTANCE | You have advantage on saving throws against being charmed, frightened, paralyzed, poisoned, stunned, or put to sleep. |
| Limited Amphibiousness | MOVEMENT | You can breathe air and water, but you need to be submerged at least once every 4 hours to avoid suffocating. |

## Loxodon

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON, +1 WIS |
| Languages | PROFICIENCY | Common, one other language |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |
| Loxodon Serenity | RESISTANCE | You have advantage on saving throws against being charmed or frightened. |
| Natural Armor | COMBAT_PASSIVE | You have thick, leathery skin. When you aren't wearing armor, your AC is 12 + your Constitution modifier. You can use your natural armor to determine your AC if the armor you wear would leave you with a lower AC. A shield's benefits apply as normal while you use your natural armor. When the game ... |
| Trunk | FLAVOUR | You can grasp things with your trunk, and you can use it as a snorkel. It has a reach of 5 feet, and it can lift a number of pounds equal to five times your Strength score. You can use it to do the following simple tasks: lift, drop, hold, push, or pull an object or a creature; open or close a do... |
| Keen Smell | SENSE | Thanks to your sensitive trunk, you have advantage on Wisdom (Perception), Wisdom (Survival), and Intelligence (Investigation) checks that involve smell. |

## Merfolk

### Merfolk (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Movement | MOVEMENT | 10 ft walk, 40 ft swim |
| Languages | PROFICIENCY | Common, Aquan |
| Amphibious | MOVEMENT | You can breathe air and water. |

### Merfolk (PSZ)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 CHA |
| Movement | MOVEMENT | 30 ft walk, 30 ft swim |
| Languages | PROFICIENCY | Common, one other language, 1 other language(s) of choice |
| Amphibious | MOVEMENT | You can breathe air and water. |
| Swimming | MOVEMENT | You have a swimming speed of 30 feet. |

## Minotaur

### Minotaur (GGR)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 CON |
| Skill Proficiency | PROFICIENCY | choose 1 from: Intimidation, Persuasion |
| Languages | PROFICIENCY | Common, one other language |
| Horns | COMBAT_PASSIVE | Your horns are natural melee weapons, which you can use to make unarmed strikes. If you hit with them, you deal piercing damage equal to 1d6 + your Strength modifier, instead of the bludgeoning damage normal for an unarmed strike. |
| Goring Rush | COMBAT_ACTIVE | Immediately after you use the Dash action on your turn and move at least 20 feet, you can make one melee attack with your horns as a bonus action. |
| Hammering Horns | COMBAT_ACTIVE | Immediately after you hit a creature with a melee attack as part of the Attack action on your turn, you can use a bonus action to attempt to shove that target with your horns. The target must be no more than one size larger than you and within 5 feet of you. Unless it succeeds on a Strength savin... |
| Imposing Presence | PROFICIENCY | You have proficiency in one of the following skills of your choice: Intimidation or Persuasion. |

### Minotaur (MOT)

*Reprint of Minotaur (GGR) with minor flavour changes. See original for traits.*

### Minotaur (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Horns | COMBAT_PASSIVE | You have horns that you can use to make unarmed strikes. When you hit with them, the strike deals 1d6 + your Strength modifier piercing damage, instead of the bludgeoning damage normal for an unarmed strike. |
| Goring Rush | COMBAT_ACTIVE | Immediately after you take the Dash action on your turn and move at least 20 feet, you can make one melee attack with your Horns as a bonus action. |
| Hammering Horns | COMBAT_ACTIVE | Immediately after you hit a creature with a melee attack as part of the Attack action on your turn, you can use a bonus action to attempt to push that target with your horns. The target must be within 5 feet of you and no more than one size larger than you. Unless it succeeds on a Strength saving... |
| Labyrinthine Recall | FLAVOUR | You always know which direction is north, and you have advantage on any Wisdom (Survival) check you make to navigate or track. |

### Minotaur (Amonkhet) (PSA)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 CON |
| Skill Proficiency | PROFICIENCY | Intimidation |
| Languages | PROFICIENCY | Common, one other language |
| Natural Weapon | COMBAT_PASSIVE | You can use your horns as a natural weapon to make unarmed strikes. If you hit with your horns, you deal bludgeoning damage equal to 1d6 + your Strength modifier. |
| Relentless Endurance | COMBAT_PASSIVE | When you are reduced to 0 hit points but not killed outright, you can drop to 1 hit point instead. You can't use this feature again until you finish a long rest. |
| Savage Attacks | COMBAT_PASSIVE | When you score a critical hit with a melee weapon attack, you can roll one of the weapon's damage dice one additional time and add it to the extra damage of the critical hit. |

## Naga

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON, +1 INT |
| Damage Immunity | RESISTANCE | Immune to poison damage |
| Condition Immunity | RESISTANCE | Immune to poisoned |
| Tool Proficiency | PROFICIENCY | poisoner's kit |
| Languages | PROFICIENCY | Common, one other language |
| Speed Burst | MOVEMENT | By lowering your body to the ground and propelling yourself with your arms, you can move more quickly for a time. As a bonus action on your turn, if you have both hands free, you can increase your walking speed by 5 feet until the end of your turn. |
| Natural Weapons | COMBAT_PASSIVE | Your fanged maw and constricting serpentine body are natural weapons, which you can use to make unarmed strikes. If you hit with your bite, you deal piercing damage equal to 1d4 + your Strength modifier, and your target must make a Constitution saving throw (8 + your proficiency bonus + your Cons... |
| Poison Immunity | RESISTANCE | You are immune to poison damage and can't be poisoned. |
| Poison Affinity | FLAVOUR | You gain proficiency with the poisoner's kit. |

## Orc

### Orc (DMG)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, -2 INT |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common, Orc |
| Aggressive | COMBAT_ACTIVE | As a bonus action, you can move up to your speed toward a hostile creature that you can see. |

### Orc (EGW)

*Reprint of Orc (ERLW) with minor flavour changes. See original for traits.*

### Orc (ERLW)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 CON |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 2 from: Animal handling, Insight, Intimidation, Medicine, Nature, Perception, Survival |
| Languages | PROFICIENCY | Common, Orc |
| Aggressive | COMBAT_ACTIVE | As a bonus action, you can move up to your movement speed toward a hostile creature you can see or hear. You must end this move closer to the enemy than you started. |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |

### Orc (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Adrenaline Rush | COMBAT_ACTIVE | You can take the Dash action as a bonus action. You can use this trait a number of times equal to your proficiency bonus, and you regain all expended uses when you finish a long rest. Whenever you use this trait, you gain a number of temporary hit points equal to your proficiency bonus. |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |
| Relentless Endurance | COMBAT_PASSIVE | When you are reduced to 0 hit points but not killed outright, you can drop to 1 hit point instead. Once you use this trait, you can't do so again until you finish a long rest. |

### Orc (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 CON |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 2 from: Animal handling, Insight, Intimidation, Medicine, Nature, Perception, Survival |
| Languages | PROFICIENCY | Common, Orc |
| Aggressive | COMBAT_ACTIVE | As a bonus action, you can move up to your movement speed toward a hostile creature you can see or hear. You must end this move closer to the enemy than you started. |
| Powerful Build | FLAVOUR | You count as one size larger when determining your carrying capacity and the weight you can push, drag, or lift. |

### Orc (Ixalan) (PSX)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 CON |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Intimidation |
| Languages | PROFICIENCY | Common, Orc |
| Relentless Endurance | COMBAT_PASSIVE | When you are reduced to 0 hit points but not killed outright, you can drop to 1 hit point instead. You can't use this feature again until you finish a long rest. |
| Savage Attacks | COMBAT_PASSIVE | When you score a critical hit with a melee weapon attack, you can roll one of the weapon's damage dice one additional time and add it to the extra damage of the critical hit. |

## Owlin

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, fly (equal to walking speed) |
| Darkvision | SENSE | 120 ft |
| Skill Proficiency | PROFICIENCY | Stealth |
| Flight | MOVEMENT | Thanks to your wings, you have a flying speed equal to your walking speed. You can't use this flying speed if you're wearing medium or heavy armor. |
| Silent Feathers | PROFICIENCY | You have proficiency in the Stealth skill. |

## Plasmoid

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to acid, poison damage |
| Amorphous | COMBAT_PASSIVE | You can squeeze through a space as narrow as 1 inch wide, provided you are wearing and carrying nothing. You have advantage on ability checks you make to initiate or escape a grapple. |
| Hold Breath | FLAVOUR | You can hold your breath for 1 hour. |
| Natural Resilience | RESISTANCE | You have resistance to acid and poison damage, and you have advantage on saving throws against being poisoned. |
| Shape Self | FLAVOUR | As an action, you can reshape your body to give yourself a head, one or two arms, one or two legs, and makeshift hands and feet, or you can revert to a limbless blob. While you have a humanlike shape, you can wear clothing and armor made for a Humanoid of your size. As a bonus action, you can ext... |

## Reborn

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Damage Resistance | RESISTANCE | Resistance to poison damage |
| Ancestral Legacy | PROFICIENCY | If you replace a race with this lineage, you can keep the following elements of that race: any skill proficiencies you gained from it and any climbing, flying, or swimming speed you gained from it. If you don't keep any of those elements or you choose this lineage at character creation, you gain ... |
| Deathless Nature | RESISTANCE | You have escaped death, a fact represented by the following benefits: You have advantage on saving throws against disease and being poisoned, and you have resistance to poison damage.; You have advantage on death saving throws.; You don't need to eat, drink, or breathe.; You don't need to sleep, ... |
| Knowledge from a Past Life | COMBAT_PASSIVE | You temporarily remember glimpses of the past, perhaps faded memories from ages ago or a previous life. When you make an ability check that uses a skill, you can roll a d6 immediately after seeing the number on the d20 and add the number on the d6 to the check. You can use this feature a number o... |

## Satyr

### Satyr (MOT)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA, +1 DEX |
| Speed | MOVEMENT | 35 ft |
| Skill Proficiency | PROFICIENCY | Performance, Persuasion |
| Languages | PROFICIENCY | Common, Sylvan |
| Fey | RESISTANCE | Your creature type is fey, rather than humanoid. |
| Ram | COMBAT_PASSIVE | You can use your head and horns to make unarmed strikes. If you hit with them, you deal bludgeoning damage equal to 1d4 + your Strength modifier. |
| Magic Resistance | RESISTANCE | You have advantage on saving throws against spells and other magical effects. |
| Mirthful Leaps | MOVEMENT | Whenever you make a long or high jump, you can roll a d8 and add the number rolled to the number of feet you cover, even when making a standing jump. This extra distance costs movement as normal. |

### Satyr (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Speed | MOVEMENT | 35 ft |
| Skill Proficiency | PROFICIENCY | Performance, Persuasion |
| Ram | COMBAT_PASSIVE | You can use your head and horns to make unarmed strikes. When you hit with them, the strike deals 1d6 + your Strength modifier bludgeoning damage, instead of the bludgeoning damage normal for an unarmed strike. |
| Magic Resistance | RESISTANCE | You have advantage on saving throws against spells. |
| Mirthful Leaps | MOVEMENT | Whenever you make a long jump or a high jump, you can roll a d8 and add the number rolled to the number of feet you cover, even when making a standing jump. This extra distance costs movement as usual. |

## Sea Elf

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, swim (equal to walking speed) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to cold damage |
| Skill Proficiency | PROFICIENCY | Perception |
| Child of the Sea | RESISTANCE | You can breathe air and water, and you have resistance to cold damage. |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws you make to avoid or end the charmed condition on yourself. |
| Friend of the Sea | MOVEMENT | Aquatic animals have an extraordinary affinity with your people. You can communicate simple ideas to any Beast that has a swimming speed. It can understand your words, though you have no special ability to understand it in return. |
| Trance | FLAVOUR | You don't need to sleep, and magic can't put you to sleep. You can finish a long rest in 4 hours if you spend those hours in a trancelike meditation, during which you retain consciousness. Whenever you finish this trance, you can gain two proficiencies that you don't have, each one with a weapon ... |

## Shadar-Kai

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic damage |
| Skill Proficiency | PROFICIENCY | Perception |
| Blessing of the Raven Queen | COMBAT_ACTIVE | As a bonus action, you can magically teleport up to 30 feet to an unoccupied space you can see. You can use this trait a number of times equal to your proficiency bonus, and you regain all expended uses when you finish a long rest. Starting at 3rd level, you also gain resistance to all damage whe... |
| Fey Ancestry | RESISTANCE | You have advantage on saving throws you make to avoid or end the charmed condition on yourself. |
| Necrotic Resistance | RESISTANCE | You have resistance to necrotic damage. |
| Trance | FLAVOUR | You don't need to sleep, and magic can't put you to sleep. You can finish a long rest in 4 hours if you spend those hours in a trancelike meditation, during which you retain consciousness. Whenever you finish this trance, you can gain two proficiencies that you don't have, each one with a weapon ... |

## Shifter

### Shifter (ERLW)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common |
| Shifting | COMBAT_ACTIVE | As a bonus action, you can assume a more bestial appearance. This transformation lasts for 1 minute, until you die, or until you revert to your normal appearance as a bonus action. When you shift, you gain temporary hit points equal to your level + your Constitution modifier (minimum of 1 tempora... |

### Shifter (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Acrobatics, Athletics, Intimidation, Survival |
| Shifting | COMBAT_ACTIVE | As a bonus action, you can assume a more bestial appearance. This transformation lasts for 1 minute, until you die, or until you revert to your normal appearance as a bonus action. When you shift, you gain temporary hit points equal to 2 × your proficiency bonus. You can shift a number of times e... |

#### Shifter; Beasthide

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Acrobatics, Athletics, Intimidation, Survival |
| Shifting (Beasthide) | COMBAT_ACTIVE | As a bonus action, you can assume a more bestial appearance. This transformation lasts for 1 minute, until you die, or until you revert to your normal appearance as a bonus action. When you shift, you gain temporary hit points equal to 2 × your proficiency bonus + 1d6, and you regain all expended... |

#### Shifter; Longtooth

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Acrobatics, Athletics, Intimidation, Survival |
| Shifting (Longtooth) | COMBAT_ACTIVE | As a bonus action, you can assume a more bestial appearance. This transformation lasts for 1 minute, until you die, or until you revert to your normal appearance as a bonus action. When you shift, you gain temporary hit points equal to 2 × your proficiency bonus, and you regain all expended uses ... |

#### Shifter; Swiftstride

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Acrobatics, Athletics, Intimidation, Survival |
| Shifting (Swiftstride) | COMBAT_ACTIVE | As a bonus action, you can assume a more bestial appearance. This transformation lasts for 1 minute, until you die, or until you revert to your normal appearance as a bonus action. When you shift, you gain temporary hit points equal to 2 × your proficiency bonus, and you regain all expended uses ... |

#### Shifter; Wildhunt

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | choose 1 from: Acrobatics, Athletics, Intimidation, Survival |
| Shifting (Wildhunt) | COMBAT_ACTIVE | As a bonus action, you can assume a more bestial appearance. This transformation lasts for 1 minute, until you die, or until you revert to your normal appearance as a bonus action. When you shift, you gain temporary hit points equal to 2 × your proficiency bonus, and you regain all expended uses ... |

## Simic Hybrid

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON, +1 to 1 of (STR/DEX/INT/WIS/CHA) |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | Common |
| Animal Enhancement | COMBAT_ACTIVE | Your body has been altered to incorporate certain animal characteristics. You choose one animal enhancement now and a second enhancement at 5th level. At 1st level, choose one of the following options: At 5th level, your body evolves further, developing new characteristics. Choose one of the opti... |

## Siren

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA |
| Movement | MOVEMENT | 25 ft walk, 30 ft fly |
| Languages | PROFICIENCY | Common, one other language |
| Innate Spellcasting | COMBAT_ACTIVE | friends (cantrip) (at will) |
| Siren's Song | COMBAT_ACTIVE | You know the friends cantrip and can cast it without material components. |
| Flight | MOVEMENT | You have a flying speed of 30 feet. You can't use your flying speed while you wear medium or heavy armor. (If your campaign uses the variant rule for encumbrance, you can't use your flying speed if you are encumbered.) |

## Skeleton

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, -4 INT, -4 CHA |
| Darkvision | SENSE | 60 ft |
| Damage Immunity | RESISTANCE | Immune to poison damage |
| Condition Immunity | RESISTANCE | Immune to exhaustion, poisoned |
| Brittle Bones | FLAVOUR | You are vulnerable to bludgeoning damage. |
| Undead Nature | RESISTANCE | You are immune to poison damage and exhaustion, and you can't be poisoned. You don't require air, food, drink, or sleep. |

## Tabaxi

### Tabaxi (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, climb (equal to walking speed) |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Perception, Stealth |
| Cat's Claws | COMBAT_PASSIVE | You can use your claws to make unarmed strikes. When you hit with them, the strike deals 1d6 + your Strength modifier slashing damage, instead of the bludgeoning damage normal for an unarmed strike. |
| Cat's Talent | PROFICIENCY | You have proficiency in the Perception and Stealth skills. |
| Feline Agility | MOVEMENT | Your reflexes and agility allow you to move with a burst of speed. When you move on your turn in combat, you can double your speed until the end of the turn. Once you use this trait, you can't use it again until you move 0 feet on one of your turns. |

### Tabaxi (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 DEX, +1 CHA |
| Movement | MOVEMENT | 30 ft walk, 20 ft climb |
| Darkvision | SENSE | 60 ft |
| Skill Proficiency | PROFICIENCY | Perception, Stealth |
| Languages | PROFICIENCY | Common, 1 other language(s) of choice |
| Feline Agility | MOVEMENT | Your reflexes and agility allow you to move with a burst of speed. When you move on your turn in combat, you can double your speed until the end of the turn. Once you use this trait, you can't use it again until you move 0 feet on one of your turns. |
| Cat's Claws | COMBAT_PASSIVE | Because of your claws, you have a climbing speed of 20 feet. In addition, your claws are natural weapons, which you can use to make unarmed strikes. If you hit with them, you deal slashing damage equal to 1d4 + your Strength modifier, instead of the bludgeoning damage normal for an unarmed strike. |
| Cat's Talents | PROFICIENCY | You have proficiency in the Perception and Stealth skills. |

## Thri-kreen

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Chameleon Carapace | COMBAT_PASSIVE | While you aren't wearing armor, your carapace gives you a base Armor Class of 13 + your Dexterity modifier. As an action, you can change the color of your carapace to match the color and texture of your surroundings, giving you advantage on Dexterity (Stealth) checks made to hide in those surroun... |
| Secondary Arms | COMBAT_PASSIVE | You have two slightly smaller secondary arms below your primary pair of arms. The secondary arms can manipulate an object, open or close a door or container, pick up or set down a Tiny object, or wield a weapon that has the L property. |
| Sleepless | FLAVOUR | You do not require sleep and can remain conscious during a long rest, though you must still refrain from strenuous activity to gain the benefit of the rest. |
| Thri-kreen Telepathy | FLAVOUR | Without the assistance of magic, you can't speak the non-thri-kreen languages you know. Instead you use telepathy to convey your thoughts. You have the magical ability to transmit your thoughts mentally to willing creatures within 120 feet of yourself. A contacted creature doesn't need to share a... |

## Tiefling

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA, +1 INT |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to fire damage |
| Languages | PROFICIENCY | Common, Infernal |
| Innate Spellcasting | COMBAT_ACTIVE | thaumaturgy (cantrip) (at will); hellish rebuke (2nd level) (1/day, at level 3); darkness (1/day, at level 5) |
| Infernal Legacy | COMBAT_ACTIVE | You know the thaumaturgy cantrip. Once you reach 3rd level, you can cast the hellish rebuke spell as a 2nd-level spell with this trait; you regain the ability to cast it when you finish a long rest. Once you reach 5th level, you can also cast the darkness spell once per day with this trait; you r... |

## Tortle

### Tortle (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Skill Proficiency | PROFICIENCY | choose 1 from: Animal handling, Medicine, Nature, Perception, Stealth, Survival |
| Claws | COMBAT_PASSIVE | You have claws that you can use to make unarmed strikes. When you hit with them, the strike deals 1d6 + your Strength modifier slashing damage, instead of the bludgeoning damage normal for an unarmed strike. |
| Hold Breath | FLAVOUR | You can hold your breath for up to 1 hour. |
| Natural Armor | COMBAT_PASSIVE | Your shell provides you a base AC of 17 (your Dexterity modifier doesn't affect this number). You can't wear light, medium, or heavy armor, but if you are using a shield, you can apply the shield's bonus as normal. |
| Shell Defense | COMBAT_ACTIVE | You can withdraw into your shell as an action. Until you emerge, you gain a +4 bonus to your AC, and you have advantage on Strength and Constitution saving throws. While in your shell, you are prone, your speed is 0 and can't increase, you have disadvantage on Dexterity saving throws, you can't t... |

### Tortle (TTP)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +1 WIS |
| Skill Proficiency | PROFICIENCY | Survival |
| Languages | PROFICIENCY | Aquan, Common |
| Claws | COMBAT_PASSIVE | Your claws are natural weapons, which you can use to make unarmed strikes. If you hit with them, you deal slashing damage equal to 1d4 + your Strength modifier, instead of bludgeoning damage normal for an unarmed strike. |
| Hold Breath | FLAVOUR | You can hold your breath for up to 1 hour at a time. Tortles aren't natural swimmers, but they can remain underwater for some time before needing to come up for air. |
| Natural Armor | COMBAT_PASSIVE | Due to your shell and the shape of your body, you are ill-suited to wearing armor. Your shell provides ample protection, however; it gives you a base AC of 17 (your Dexterity modifier doesn't affect this number). You gain no benefit from wearing armor, but if you are using a shield, you can apply... |
| Shell Defense | COMBAT_ACTIVE | You can withdraw into your shell as an action. Until you emerge, you gain a +4 bonus to AC, and you have advantage on Strength and Constitution saving throws. While in your shell, you are prone, your speed is 0 and can't increase, you have disadvantage on Dexterity saving throws, you can't take r... |

## Triton

### Triton (MOT)

*Reprint of Triton (VGM) with minor flavour changes. See original for traits.*

### Triton (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Movement | MOVEMENT | 30 ft walk, swim (equal to walking speed) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to cold damage |
| Innate Spellcasting | COMBAT_ACTIVE | fog cloud (1/day, at level 1); gust of wind (1/day, at level 3); water walk (1/day, at level 5) |
| Amphibious | MOVEMENT | You can breathe air and water. |
| Control Air and Water | COMBAT_ACTIVE | You can cast fog cloud with this trait. Starting at 3rd level, you can cast the gust of wind spell with this trait. Starting at 5th level, you can also cast the water walk spell with it. Once you cast any of these spells with this trait, you can't cast that spell with it again until you finish a ... |
| Emissary of the Sea | MOVEMENT | You can communicate simple ideas to any Beast, Elemental, or Monstrosity that has a swimming speed. It can understand your words, though you have no special ability to understand it in return. |
| Guardian of the Depths | RESISTANCE | Adapted to the frigid ocean depths, you have resistance to cold damage. |

### Triton (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 STR, +1 CHA, +1 CON |
| Movement | MOVEMENT | 30 ft walk, 30 ft swim |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to cold damage |
| Languages | PROFICIENCY | Common, Primordial |
| Innate Spellcasting | COMBAT_ACTIVE | fog cloud (1/day, at level 1); gust of wind (1/day, at level 3); wall of water (1/day, at level 5) |
| Swim Speed | MOVEMENT | You have a swimming speed of 30 feet. |
| Amphibious | MOVEMENT | You can breathe air and water. |
| Control Air and Water | COMBAT_ACTIVE | A child of the sea, you can call on the magic of elemental air and water. You can cast fog cloud with this trait. Starting at 3rd level, you can cast gust of wind with it, and starting at 5th level, you can also cast wall of water with it. Once you cast a spell with this trait, you can't cast tha... |
| Emissary of the Sea | FLAVOUR | Aquatic beasts have an extraordinary affinity with your people. You can communicate simple ideas with beasts that can breathe water. They can understand the meaning of your words, though you have no special ability to understand them in return. |
| Guardians of the Depths | RESISTANCE | Adapted to even the most extreme ocean depths, you have resistance to cold damage. |

## Troglodyte

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 STR, +2 CON, -4 INT, -4 CHA |
| Darkvision | SENSE | 60 ft |
| Languages | PROFICIENCY | one other language |
| Chameleon Skin | FLAVOUR | You have advantage on Dexterity (Stealth) checks made to hide. |
| Stench | FLAVOUR | Any creature other than a troglodyte that starts its turn within 5 ft. of you must succeed on a 12 Constitution saving throw or be poisoned until the start of the creature's next turn. On a successful saving throw, the creature is immune to the stench of all troglodytes for 1 hour. |
| Sunlight Sensitivity | COMBAT_PASSIVE | While in sunlight, you have disadvantage on attack rolls, as well as on Wisdom (Perception) checks that rely on sight. |
| Natural Armor | COMBAT_PASSIVE | Your thick hide grants you a +1 bonus to Armor Class. |

## Vampire

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to necrotic damage |
| Languages | PROFICIENCY | Common, one other language |
| Blood Thirst | COMBAT_PASSIVE | You can drain blood and life energy from a willing creature, or one that is grappled by you, incapacitated, or restrained. Make a melee attack against the target. If you hit, you deal 1 piercing damage and 1d6 necrotic damage. The target's hit point maximum is reduced by an amount equal to the ne... |
| Vampiric Resistance | RESISTANCE | You have resistance to necrotic damage. |

## Vedalken

### Vedalken (GGR)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 INT, +1 WIS |
| Tool Proficiency | PROFICIENCY | 1 tool(s) of choice |
| Skill Proficiency | PROFICIENCY | choose 1 from: Arcana, History, Investigation, Medicine, Performance, Sleight of hand |
| Languages | PROFICIENCY | Common, one other language, 1 other language(s) of choice |
| Vedalken Dispassion | RESISTANCE | You have advantage on all Intelligence, Wisdom, and Charisma saving throws. |
| Partially Amphibious | FLAVOUR | By absorbing oxygen through your skin, you can breathe underwater for up to 1 hour. Once you've reached that limit, you can't use this trait again until you finish a long rest. |

### Vedalken (PSK)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 INT, +1 WIS |
| Languages | PROFICIENCY | Common, one other language |
| Vedalken Cunning | RESISTANCE | You have advantage on all Intelligence, Wisdom, and Charisma saving throws against magic. |
| Aether Lore | FLAVOUR | Whenever you make an Intelligence (History) check related to magic items or aether-powered technological devices, you can add twice your proficiency bonus, instead of any proficiency bonus you normally apply. |

## Verdan

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 CON, +2 CHA |
| Skill Proficiency | PROFICIENCY | Persuasion |
| Languages | PROFICIENCY | Common, Goblin, 1 other language(s) of choice |
| Black Blood Healing | COMBAT_PASSIVE | The black blood that is a sign of your people's connection to That-Which-Endures boosts your natural healing. When you roll a 1 or 2 on any Hit Die you spend at the end of a short rest, you can reroll the die and must use the new roll. |
| Limited Telepathy | FLAVOUR | You can telepathically speak to any creature you can see within 30 feet of you. You don't need to share a language with the creature for it to understand your telepathy, but it must be able to understand at least one language. This process of communication is slow and limited, allowing you to tra... |
| Persuasive | PROFICIENCY | Your people's lack of history makes you trustworthy and humble. You have proficiency in the Persuasion skill. |
| Telepathic Insight | RESISTANCE | Your mind's connection to the world around you strengthens your will. You have advantage on all Wisdom and Charisma saving throws. |

## Warforged

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CON, +1 to 1 of (STR/DEX/INT/WIS/CHA) |
| Damage Resistance | RESISTANCE | Resistance to poison damage |
| Condition Immunity | RESISTANCE | Immune to disease |
| Tool Proficiency | PROFICIENCY | 1 tool(s) of choice |
| Skill Proficiency | PROFICIENCY | 1 skill(s) of choice |
| Languages | PROFICIENCY | Common, 1 other language(s) of choice |
| Constructed Resilience | RESISTANCE | You were created to have remarkable fortitude, represented by the following benefits: You have advantage on saving throws against being poisoned, and you have resistance to poison damage.; You don't need to eat, drink, or breathe.; You are immune to disease.; You don't need to sleep, and magic ca... |
| Sentry's Rest | FLAVOUR | When you take a long rest, you must spend at least six hours in an inactive, motionless state, rather than sleeping. In this state, you appear inert, but it doesn't render you unconscious, and you can see and hear as normal. |
| Integrated Protection | COMBAT_PASSIVE | Your body has built-in defensive layers, which can be enhanced with armor: You gain a +1 bonus to Armor Class.; You can don only armor with which you have proficiency. To don armor other than a shield, you must incorporate it into your body over the course of 1 hour, during which you remain in co... |

## Yuan-Ti

### Yuan-Ti (MPMM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | Choose any +2/+1 or +1/+1/+1 (MotM/lineage rules) |
| Darkvision | SENSE | 60 ft |
| Damage Resistance | RESISTANCE | Resistance to poison damage |
| Innate Spellcasting | COMBAT_ACTIVE | poison spray (cantrip) (at will); animal friendship (at level 1, 1/day); suggestion (1/day, at level 3) |
| Magic Resistance | RESISTANCE | You have advantage on saving throws against spells. |
| Poison Resilience | RESISTANCE | You have advantage on saving throws you make to avoid or end the poisoned condition on yourself. You also have resistance to poison damage. |
| Serpentine Spellcasting | COMBAT_ACTIVE | You know the poison spray cantrip. You can cast animal friendship an unlimited number of times with this trait, but you can target only snakes with it. Starting at 3rd level, you can also cast suggestion with this trait. Once you cast it, you can't do so again until you finish a long rest. You ca... |

### Yuan-ti Pureblood (VGM)

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +2 CHA, +1 INT |
| Darkvision | SENSE | 60 ft |
| Damage Immunity | RESISTANCE | Immune to poison damage |
| Condition Immunity | RESISTANCE | Immune to poisoned |
| Languages | PROFICIENCY | Common, Abyssal, Draconic |
| Innate Spellcasting | COMBAT_ACTIVE | poison spray (cantrip) (at will); animal friendship (at level 1, 1/day); suggestion (1/day, at level 3) |
| Magic Resistance | RESISTANCE | You have advantage on saving throws against spells and other magical effects. |
| Poison Immunity | RESISTANCE | You are immune to poison damage and the poisoned condition. |

## Zombie

| Trait Name | Category | Combat Notes |
|------------|----------|--------------|
| Ability Score Increase | STAT_BONUS | +1 STR, +2 CON, -6 INT, -4 WIS, -4 CHA |
| Darkvision | SENSE | 60 ft |
| Damage Immunity | RESISTANCE | Immune to poison damage |
| Condition Immunity | RESISTANCE | Immune to poisoned |
| Undead Fortitude | COMBAT_PASSIVE | If damage reduces you to 0 hit points, you must make a Constitution saving throw with a DC of 5+the damage taken, unless the damage is radiant or from a critical hit. On a success, you drop to 1 hit point instead. |
| Undead Nature | RESISTANCE | You are immune to poison damage, and you can't be poisoned. You don't require air, food, drink, or sleep. |
