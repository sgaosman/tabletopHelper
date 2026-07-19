Let's work on automating player and enemy spells, abilities, actions and legendary resistances 

From what I can tell in dnd 5e rules, the actions that a character can take fall into one of the following categories:

- Attack: Can only be taken on their turn (already implemented)
- Multiattack: Can only be taken on their turn. (already implemented through the add attack feature)
- Cast a spell: Can only be taken on their turn unless the spell specifies otherwise (e.g. Counterspell can be cast as a reaction) or the character has a feature that allows them to cast a spell outside of their turn (e.g. War Caster allows  a character to cast a single-target spell in place of an opportunity attack's melee attack).
	- When a spell is cast, a spell slot equivalent to the spell's level is expended 
	- When a spell is cast, the spell can either be single target or multiple targets (depending on the spell). 
	- A spell can be cast on self
	- Spells can be upcasted to gain greater benefits
- AOE attacks: 
	- e.g. adult emerald dragon's disorienting breath attack
- Legendary actions: 
	- Can be taken at any point in combat

Correct me if I am wrong or if I have missed any other types of actions. 

My aim is to do the following:

- At all times, the DM can see the status of all characters, including HP, AC, spell slots and conditions, presented in a list in turn order.
- at all times, the players can see the HP, AC, Spell slots and conditions of themselves and other players. They can see all encounter participants in a list in turn order
- When a player is in the encounter screen, they should be able to see all actions available to them, including:
	- On panels of other characters, they should see an attack option to attack the target 
	- at the top of their screen, they should continue seeing Add Condition and Set/Change Concentration (existing functionality)
	- In addition to the top of their screen, they should see a cast spell option. when they select this, they get to pick what spell they would like to cast (from a list that is available to them and that they have prepared only(1)). 
		- They can then choose what level they want to cast the spell at (e.g. if casting bless, can cast bless at level 1 or at higher levels)(2) 
		- They then get to choose a number of targets valid for the spell which should increase/decrease as they select the level of the spell, if upcasting does indeed increase the number of targets (e.g. it will do in Bless's case, but upcasting fireball only gives more damage, not more targets)
		- They can then choose to action the spell, at which point the application should roll the applicable dice for the spell and apply the relevant effect to the targets. 
		- If the spell deals damage, it should roll the spell save for the targets or the spell attack roll against the targets' AC and then subtract the damage as applicable to the spell 
		- If the spell applies any negative conditions, it should roll the spell save DC for the condition, if there is one to save against the condition, and then apply the condition automatically for the relevant number of turns 
			- If a spell applies a condition to a target, there should be a little indicator (similar to the current conditions indicators) stating the condition and what source it's coming from (e.g. Blindness/Deafness: Blinded or Entangle: Restrained)
			- If the source of the spell drops concentration on it, all targets being affected by the spell should stop being affected with the condition of the spell
		- If the spell applies any beneficial effects, (e.g. Bless or Aid), it should apply an indicator within the target character's box
		- (STRETCH GOAL) ideally, the beneficial effect would also apply for the player (e.g. Aid would increase the targets' HP and max hit points by 5, Bless would apply a 1d4 automatically to the affected targets' attack rolls or saving throws), but this may be difficult to engineer given how many effects exist in the game. (2)
	- They should also be able to see bonus actions available to them (if any)
	- They should also be able to see a burger menu that allows them to do any other actions available to them which are applicable to combat (e.g. Dodge, Hide, use an item (3), Help), using any of these actions should register the action in the log
		- If the player uses an item, a model should pop up containing a list of items they have equipped
		- If the item requires attunement, show ones they have attuned to, otherwise do not show the item
		- If the item has charges or a limited number of uses, show the current number of uses (this should be managed by the player or the DM within the character page)
		- (Stretch Goal) if the item activates an effect on a character (e.g. restoring health via potions, dealing damage such as with a Wand of Fireballs, inflicting a status effect via Wand of Entangle), then it should function similarly to the spells functions above, just via the item modal once the item is selected
- When a DM is in the encounter screen, they should be able to see all of the actions available to their characters including:
	- attacks 
		- when a DM chooses to attack another character, the attack box should still show at the top of the page (existing funtionality). however, there should be a new functionality that allows them to select which creature is doing the attack for greater clarity in the combat log. If no source creature is selected, then the default should just use the DM's name as i believe it is doing now (validate this assumption)
	- spells 
		- the DM's characters should be able to cast spells if the monster stat block allows them to do so, similar to the characters. the monster (or enemy)'s spell slots should only be visible to the DM
	- legendary actions 
		- the DM should be able to do all legendary actions available to the monster, if the monster stat block has legendary items
- At all times, the DM and only the DM can undo actions - this should clear the combat log, undo any damage or healing done by the action, remove any status effects inflicted by the action whether beneficial or negative as well as anything else i may have missed. 

(1) In order to facilitate this I imagine we will need to expand the character creation far beyond what it is now. Let's try and create a character creation functionality within this similar to dicecloud. 
When a player selects new character, they should be able to:
- input a freetype character name
- select a race only from a list of available races (2014.5e.tools lists 191 available races by default) rather than freetyping. 
	- selecting a race should set the character's 
		- size, 
		- speed (and fly speed if applicable),
		- ability score bonuses (take the default option from the race, but give the player the ability to reassign them to other ability scores as per Tasha's Cauldron of Everything rules)
			- e.g. if I select aarakocra, i HAVE deX +2, WIS +1. I should be able to reassign this to any other +2 and any other +1 ability score, for example
			- Some races may not have a default option. this is fine, just leave the ability score bonuses from race blank by default and the player can fill this in.
		- Creature Type, if applicable
		- Proficiencies if applicable
		- race abilities (e.g. astral elf has starlight step as an action)
		- race bonuses (e.g. astral elf has advantage on saving throws to avoid/end charmed conditions on themselves)
- Select a class. selecting a class should:
	- assign the character's number of hit dice
	- assign the character's hit points (should update automatically along with the character's consititution modifier)
		- this includes hit points at first level
		- and also hit points at higher levels - the player should be able to choose to take the average hit points, assign a set number, or roll for new hit points in accordance with their class rules
	- assign the character's proficiencies
	- assign the selected starting equipment in accordance with the equipment available to the class
	- assign the class features available to the class 
		- as the player levels up, more class features should become available
		- for some class features we do not need to systematise them necessarily as they are more 'flavor based' e.g. Magical Tinkering for artificer 
		- for other class features, we do need to systematise them as they are used in combat encounters (e.g. spellcasting for multiple classes) and need to track tihngs like spell slots, number of uses (e.g. peace cleric's emboldening bond), proficiencies granted by the class feature and so on
		- I imagine we will need to do a complete analysis to determine which class features are flavored based before we do any implementation here. Please do so any confirm with me before implementing anything in this regard so I can course correct.
	- If the character is a high enough level, they should be able to select a subclass
		- selecting a subclass should grant additional prepared or known spells as relevant (e.g. domain spells)
		- selecting a subclass should grant additional proficiencies if relevant (e.g. implement of peace from peace cleric)
		- selecting a subclass should grant subclass features, similar to class features.
			- again, we will probably need to do a complete analysis to determine which class features are flavored based before we do any implementation here. Please do so any confirm with me before implementing anything in this regard so I can course correct.
- select a second, third, fourth or fifth class. selecting more classes would still follow the rules above, except in the case where it conflicts with official dnd 5e multiclassing rules, in which case we would implement the official rules.
- select a background 
- select a feat (if available)
- select an alignment
- assign the character to an active campaign, none by default
- assign ability scores
	- The players can select how they want to assign scores from a select box at the top, but underneath the basic info/ability scores/combat/personality notes navigation tab, either:
		- set the scores manually (existing funcitonality)
		- assign standard array
		- point buy
		- roll 4d6 drop lowest
	- points should calculate the stat bonuses automatically (existing functionality)
- the current basic info/ability scores/combat/personality navigation should change to stats/actions/spells/inventory/features/journal
	- stats should contain 
		- total and current hit points (e.g. 12/14 or 24/24)
		- ability scores and bonuses
		- speed
		- armor class
		- darkvision range (if applicable)
		- proficiency bonus
		- initative bonus
		- hit dice (showing a counter of the number remaining vs total e.g. 3/3 d8+2, that goes down when taking a short rest)
		- spell slots (should be able to see the total spell slots and number used/number remaining in a clean UI)
		- saving throw bonuses and proficiencies
		- skill bonuses and proficiencies
		- weapon proficiencies
		- armor proficiencies
		- tool proficiencies
		- language proficiencies
	- actions should contain 
		- attack actions with equipped weapons
			- characters with extra attack should have a reminder for the extra attack
		- class actions (e.g. channel divinity, second wind etc)
		- feat actions
		- race actions (e.g. astral elf astral step)
		- any other actions i cannot think of at the moment (validate and suggest more to this if you believe it is worth considering)
	- spells should contain
		- total number of spell slots 
		- current number of spell slots (prefreably combined this and the above if possible)
		- list of spells available to the character's class(es), split by class in their own box containing the class name in the box header 
		- at any point, the character can click on a burger menu in the top right of the class's box. when clicked, this brings up an option to "Change Prepared/known spells" (text should change depending on if the class is a prepared/known spell class)
		- when this option is clicked, a modal should pop up showing all the spells the character knows / can prepare for that specific class. they can multiselect unprepared/unknown spells or deselect known/prepared spells to add/remove them from the known/prepared spell list for that class
		- They can then click save once done and it saves the known/prepared list. Clicking outside the modal without clicking save, or clicking cancel, does not save the changes. 
	- Inventory should contain 
		- the character's purse and how much money they have in gold, platinum, silver and copper pieces
		- all items they have (from class and background to start with)
		- all items they presently have equipped
		- all items they are currently attuned to (an item can be attuned to while it is also equipped or carried, so assuming this can be a sort of indicator, rather than a whole section in and of itself?)
		- a + symbol at the bottom right, which they can then add any number of items from the item knowledge base repository within this application 
	- features should contain
		- class features
		- race features 
		- background features
		- any other 'features' that the character might have
		- (the overall aim of this tab is to list the characters' features in text, rather than showing what combat actions they might have, so they can have reminders of what the character can do)
	- Journal should contain
		- character image (with an option to click on this and upload a character image, resized as appropriate)
		- alignment
		- physical description
		- personality traits
		- ideals
		- bonds
		- flaws
		- notes 

(2) ALSO in order to facilitate this, I imagine we will need to expand the application's spells knowledge base and functionality far beyond what it is now

we would need to do a complete analysis of all spells available in dnd 5e. The total count I have is 532 for all spells but for now, let's limit this to only apply for spells up to 3rd level - there's no point implementing spells beyond that as the players are only level 3 and unlikely to encounter much more in combat encounters. thsi brings the total down to 294. 

we need to understand
- casting time for the spell
- the spell's minimum spell slot cost
- if the spell deals damage
- if the spell requires material components 
	- We need to surface this in the encounter's spell selection screen
	- if it has a monetary component, e.g. Revivify, we need to check that the character has the appropriate monetary component in their inventory if they wish to cast it in an encounter
- if the spell requires verbal components
	- We need to surface this in the encounter's spell selection screen
	- if it has verbal componets, we need to check that the character is not under the effects of a Silence spell in an encounter (this spell does not have a Silenced condition in dnd , but we should still track it in an encounter like it is one)
- if the spell requires somatic components
	- We need to surface this in the encounter's spell selection screen
- if the spell deals damage
	- if the spell deals damage via an attack roll, when it is selected and cast in an encounter, we need to make an automatic attack roll against the selected target's AC 
		- this should be made clear in the combat log, 
		- e.g. X casts Guiding Bolt! 
		  Spell Attack Roll: 1d20+5 (17) SUCCESS! 
		  2d8(5) Radiant damage is dealt to Y!
	- if the spell deals damage via a spell save, when it is selected and cast in an encounter, we need to make the targets automatically roll against the spell save DC 
		- if the spell is half on save, we need to half the damage on a success, full damage on a fail
		- if the spell is not half on save, we need to apply no damage on a success, full damage on a fail
		-  this should be made clear in the combat log
		  e.g. X casts Fireball! 
		  Spell Save DC 17
		  Y makes a Dexterity save: 16. FAILURE
		  Y takes 40 fire damage! 
- if the spell applies a condition
	- if the spell has no roll to succeed against the condition, the condition should be applied automatically 
	- if the spell has a roll to succeed against the condition, the target should automatically roll 
		-  this should be made clear in the combat log
		  e.g. X casts Color Spray! 
		  Y is Blinded!
		  Z is Blinded!
		  A has too many hit points and is not blinded!
- what classes/subclasses/races/backgrounds/feats grant the spell so that we can apply it automatically in a character's spell list when they select the required pre-requisite aspects and make it available for them to prepare/know the spell so they can cast it in encounters

(3)also in order to facilitate this I imagine we will need to expand the item knowledge base and functionality far beyond what it is now in the same style as spells 
- what kind of item it is 
- whether it can deal damage/restore hp
- whether it can inflict a condition 
- whether it needs an attack roll or induces a saving throw against a DC 
- whether it requires attunement
- anything else i may have missed which you believe is necessary


I know that this is quite a lot of requirements, so:
- 1) do you have any questions to clarify any of these requirements? 
- 2) I do not know the best way to implement this in a way that is secure, reliable and not prone to data errors/networking issues, and extendable for future features. can you suggest an overall approach for how to architect this and a detailed approach for the specific implementation of all features?
- 3) is there anything I might not have considered, either from a product perspective (within the dnd 5e ruleset) or from a technical perspective?
- 4) I am thinking of breaking this down into several more achievable milestones with deliverable points. How would you best break this down?
  5) Is there anything claude code agent would need from me to deliver this in a much more reliable way and guarantee highest chance of correct delivery? would it be better to literally go through and define the behaviour of every single spell, item and creature in the bestiary, or can we define an overall series of patterns that most spells, items and creatures will be able to do and apply that to catch 99% of use cases?



Claude code terminal agent's response:
## Q1: Corrections and Missing Action Types

Your categorization is mostly right but has some gaps and one misclassification:

**AOE attacks aren't a separate category.** A dragon's breath weapon is just an "Action" in its stat block that happens to target an area. It uses a saving throw + damage pattern, same as Fireball. The delivery mechanism (area vs single target) is a property of the attack/spell, not a distinct action type.

**Missing action types you'll need:**

- **Reactions** ‚Äî this is a major gap. Shield, Counterspell, opportunity attacks, Absorb Elements, Hellish Rebuke, Feather Fall. Each creature gets one reaction per round, usable on any turn. This is a first-class part of the action economy alongside actions and bonus actions.
- **Bonus actions** ‚Äî you mentioned these briefly but they're critical. Healing Word, Spiritual Weapon, Misty Step, Rogue's Cunning Action, Monk's Flurry of Blows. Many classes live or die by their bonus action.
- **The bonus action spell rule** (PHB 202) ‚Äî if you cast a spell as a bonus action, any other spell you cast that same turn must be a cantrip with a casting time of 1 action. This trips up players constantly.
- **Cantrips** ‚Äî technically "Cast a Spell" but functionally different: no slot cost, unlimited use, damage scales with *character* level (not class level). These need distinct handling.
- **Legendary Resistance** ‚Äî you mentioned "legendary resistances" in your title but didn't detail it. This is distinct from legendary actions: a monster can choose to succeed on a failed saving throw, typically 3/day. This is one of the most important monster mechanics.
- **Lair Actions** ‚Äî some creatures (dragons, liches, beholders) get actions at initiative count 20 (losing ties). These are like a separate turn in the initiative order.
- **Ritual Casting** ‚Äî some spells can be cast as rituals without using a spell slot, but it takes 10 minutes longer. Relevant mainly outside combat, but the preparation/known-spells UI needs to surface the ritual tag.

**On your spell mechanics, mostly correct but a few clarifications:**

- Material component focus: most casters have an arcane focus, druidic focus, or holy symbol that replaces *non-costly* material components. So the only material components that actually matter in practice are those with a gold piece cost (like Revivify's 300gp diamond). Your inventory check should only enforce costly components.
- Pact Magic (Warlock): their spell slots work completely differently ‚Äî always cast at highest level, all slots are same level, recover on short rest instead of long rest. If you support Warlocks, this needs separate handling from standard spellcasting.
- Known vs Prepared casters vary by class:
  - **Prepared (pick from full class list daily):** Cleric, Druid, Paladin ‚Äî prepare WIS/CHA mod + level spells
  - **Known (fixed selections, swap on level-up):** Sorcerer, Bard, Ranger, Warlock ‚Äî know a fixed count
  - **Wizard (hybrid):** knows spells in spellbook, prepares INT mod + level from that book
  - **Subclass always-prepared:** domain spells, oath spells, etc. ‚Äî always prepared, don't count against limit

---

## Q2: Architecture Approach

I've audited your current data. Here's what I found and what I recommend:

**Current state:**
- Your `Spell` entity has `damageType` and `saveAbility` but the `damageDice` column is never populated
- The seeder ignores `conditionInflict` (60/361 PHB spells), `spellAttack` (23), `scalingLevelDice` (10), `areaTags` (237), `miscTags` (247), and `affectsCreatureType` (40) ‚Äî all game-mechanically critical
- Monster actions/traits/legendary are stored as `{name, description}` text pairs ‚Äî all structured attack/damage/DC data is flattened to prose
- Monster `spellcasting` blocks (with spell lists, slots, DCs) are not extracted as structured data at all
- Character creation exposes none of the jsonb fields (spellsKnown, spellSlots, equipment, currency, features, proficiencies) that already exist on the entity
- There's no "cast spell" combat action ‚Äî all effects are manually decomposed into individual `rollAttack` / `applyDamage` / `addCondition` calls

**Recommended architecture: Declarative Effect Template System**

The core insight is that ~80% of spells follow a handful of mechanical patterns. Rather than writing code for each spell, we define spell behaviour as structured data and build an engine that interprets it.

```
‚îå‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îê
‚îÇ                  EFFECT TEMPLATE                     ‚îÇ
‚îÇ                                                      ‚îÇ
‚îÇ  delivery: ATTACK_ROLL | SAVING_THROW | AUTO         ‚îÇ
‚îÇ  targetType: SELF | SINGLE | MULTI_N | AREA          ‚îÇ
‚îÇ  effects: [                                          ‚îÇ
‚îÇ    { type: DAMAGE, dice: "8d6", damageType: "fire",  ‚îÇ
‚îÇ      scaling: { perLevel: "1d6" } },                 ‚îÇ
‚îÇ    { type: CONDITION, condition: "blinded",           ‚îÇ
‚îÇ      duration: { type: "ROUNDS", count: 1 } },       ‚îÇ
‚îÇ    { type: HEALING, dice: "1d8+MOD" },               ‚îÇ
‚îÇ    { type: BUFF, name: "Bless", description: "..." } ‚îÇ
‚îÇ  ]                                                   ‚îÇ
‚îÇ  saveAbility: "DEX" | null                           ‚îÇ
‚îÇ  halfOnSave: true | false                            ‚îÇ
‚îÇ  spellAttackType: "RANGED" | "MELEE" | null          ‚îÇ
‚îÇ  baseTargetCount: 1                                  ‚îÇ
‚îÇ  targetScaling: { perLevel: 1 } | null               ‚îÇ
‚îÇ  concentration: true | false                         ‚îÇ
‚îÇ  automatable: true | false (false = DM prompt)       ‚îÇ
‚îî‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îò
              ‚îÇ
              ‚ñº
‚îå‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îê
‚îÇ               SPELL EFFECT ENGINE                    ‚îÇ
‚îÇ                                                      ‚îÇ
‚îÇ  castSpell(caster, spell, targets, slotLevel) {      ‚îÇ
‚îÇ    1. Validate: has slot, is prepared, components ok  ‚îÇ
‚îÇ    2. Deduct spell slot                              ‚îÇ
‚îÇ    3. Calculate upcast scaling                       ‚îÇ
‚îÇ    4. For each target:                               ‚îÇ
‚îÇ       a. If ATTACK_ROLL ‚Üí roll d20 + spellAttackMod  ‚îÇ
‚îÇ       b. If SAVING_THROW ‚Üí target rolls save vs DC   ‚îÇ
‚îÇ       c. Apply effects based on hit/fail             ‚îÇ
‚îÇ    5. If concentration ‚Üí set concentration           ‚îÇ
‚îÇ    6. Log everything to combat log                   ‚îÇ
‚îÇ    7. Broadcast via WebSocket                        ‚îÇ
‚îÇ  }                                                   ‚îÇ
‚îî‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îò
```

**For monster actions, same pattern:**

```
‚îå‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îê
‚îÇ            MONSTER ACTION TEMPLATE                   ‚îÇ
‚îÇ                                                      ‚îÇ
‚îÇ  name: "Paralyzing Touch"                            ‚îÇ
‚îÇ  actionType: ACTION | BONUS | REACTION | LEGENDARY   ‚îÇ
‚îÇ  legendaryCost: 1 | 2 | 3                            ‚îÇ
‚îÇ  delivery: ATTACK_ROLL                               ‚îÇ
‚îÇ  attackBonus: 12                                     ‚îÇ
‚îÇ  effects: [                                          ‚îÇ
‚îÇ    { type: DAMAGE, dice: "3d6", damageType: "cold" },‚îÇ
‚îÇ    { type: CONDITION, condition: "paralyzed",         ‚îÇ
‚îÇ      saveDC: 18, saveAbility: "CON",                 ‚îÇ
‚îÇ      duration: { type: "SAVE_END_OF_TURN" } }        ‚îÇ
‚îÇ  ]                                                   ‚îÇ
‚îî‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îò
```

**For items:**

```
‚îå‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îê
‚îÇ              ITEM EFFECT TEMPLATE                    ‚îÇ
‚îÇ                                                      ‚îÇ
‚îÇ  usable: true | false                                ‚îÇ
‚îÇ  charges: { max: 7, recharge: "dawn_1d6+1" } | null  ‚îÇ
‚îÇ  effect: (same Effect structure as spells)           ‚îÇ
‚îÇ  requiresAttunement: true                            ‚îÇ
‚îÇ  equipSlot: "HAND" | "ARMOR" | "NECK" | ...          ‚îÇ
‚îî‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îò
```

**Key architectural decisions:**

1. **Effect templates are data, not code** ‚Äî stored as JSONB on the spell/action/item entity. The engine interprets the template. This means fixing a spell's behavior is a data fix, not a code deploy.

2. **`automatable` flag** ‚Äî spells like Polymorph or Counterspell that have complex conditional logic get `automatable: false`. The system deducts the slot, logs the cast, and prompts the DM to resolve manually. This covers the ~20% edge cases without blocking the ~80% that can be automated.

3. **The engine is server-side only** ‚Äî the frontend sends "cast Fireball at slot level 5 targeting [A, B, C]" and the backend handles all dice rolling, save calculation, damage application, and condition application atomically. This prevents cheating and ensures consistency.

4. **Undo via event sourcing** ‚Äî every combat action already creates a `CombatLog` entry. Extend this to store a `stateBefore` snapshot (affected participants' HP, conditions, concentration before the action). Undo = restore from snapshot + delete the log entry. This is much more reliable than trying to reverse-calculate the prior state.

5. **Race/Class as entities, not strings** ‚Äî create `Race` and `CharacterClass` entities seeded from 5e.tools `races.json` and `class-*.json` data. Character creation becomes a series of foreign key selections that auto-populate derived stats.

---

## Q3: Things You Haven't Considered

**Product (D&D 5e rules):**

- **Magic Resistance** ‚Äî many monsters (e.g., all fiends, Gnome racial trait) have advantage on saving throws against spells. Your save-rolling engine needs to check for this.
- **Spell Immunity** ‚Äî Rakshasa is immune to spells of 6th level and lower. Some creatures are immune to specific spells.
- **Dispel Magic** ‚Äî needs to be able to remove any active spell effect on a target. This means tracking which conditions/buffs came from which spell.
- **Death Ward** ‚Äî a spell that prevents death once. This interacts with your existing death save system.
- **Readied Actions** ‚Äî "I ready Fireball for when the door opens." Uses your action now, casts as a reaction on a trigger. Common enough to need support.
- **Multiclass spell slot calculation** ‚Äî uses a separate table (PHB 165), not the sum of individual class tables. A Cleric 3 / Wizard 3 has the slots of a 6th-level caster, not two 3rd-level casters.
- **Spell scroll usage** ‚Äî anyone can try to use a scroll if the spell is on their class list, but higher-level scrolls require an ability check.
- **Hit dice on short rest** ‚Äî you have `hitDiceTotal`/`hitDiceRemaining` on the character but no mechanic to spend them during rests.

**Technical:**

- **Effect tracking and source attribution** ‚Äî if Bless is dropped, you need to know which targets were affected and remove the buff from all of them. This means every active effect needs a `sourceSpellCastId` that links it back to the specific casting instance.
- **Concurrent state mutations** ‚Äî if the DM and a player both submit actions at the same instant, you need optimistic locking or sequential processing per encounter. Your current model of "REST endpoint ‚Üí mutate ‚Üí broadcast" could have race conditions.
- **Data migration complexity** ‚Äî you have existing characters with free-text race/class. Migration to FK-based selection needs to handle existing data gracefully.
- **Seeder idempotency with enriched data** ‚Äî your current seeder skips if `count > 0`. When you add new fields to the spell entity, you'll need a migration strategy for existing data (re-seed vs update-in-place).
- **Effect template versioning** ‚Äî if you fix a spell's template, encounters already in progress could be mid-cast with the old version. You likely need to snapshot the template at cast time.

---

## Q4: Milestone Breakdown

I'd break this into 7 milestones, ordered by dependency:

| # | Milestone | Depends On | Est. Scope |
|---|-----------|------------|------------|
| 7 | Spell Data Enrichment & Effect Engine | ‚Äî | Medium |
| 8 | Race & Class Data + Character Creation Overhaul | ‚Äî | Large |
| 9 | Character Spellcasting | 7, 8 | Large |
| 10 | Encounter Spell Casting (Combat) | 7, 9 | Large |
| 11 | Monster Actions & Abilities | 7 | Medium |
| 12 | Item System & Inventory | ‚Äî | Medium |
| 13 | Action Economy, Reactions & DM Undo | 10, 11 | Medium |

**M7 and M8 can run in parallel** ‚Äî they have no dependencies on each other.

**M7: Spell Data Enrichment & Effect Engine**
- Add missing columns to `Spell` entity: `conditionInflict`, `spellAttack`, `scalingLevelDice`, `areaTags`, `targetCount`, `halfOnSave`, `effectTemplate` (JSONB)
- Update seeder to extract currently-ignored 5e.tools fields
- Design the `SpellEffectTemplate` JSON schema
- Classify all 294 level 0-3 spells into effect patterns (I generate the templates, you review)
- Build the server-side `SpellEffectEngine` that interprets templates
- Unit test the engine against known spell behaviors
- **Deliverable:** spells have structured mechanical data; engine can calculate "what happens when X casts Fireball at level 5 on targets A, B, C"

**M8: Race & Class Data + Character Creation Overhaul**
- Seed `Race` entity from 5e.tools `races.json` (191 races with size, speed, ASI, proficiencies, features)
- Seed `CharacterClass` and `Subclass` entities from 5e.tools `class-*.json` files (hit dice, proficiencies, features per level, spell list, subclass features)
- Build character creation wizard: race ‚Üí class ‚Üí ability scores (point buy / standard array / 4d6) ‚Üí background ‚Üí derived stats auto-calculated
- Build new character sheet tabs: Stats, Actions, Spells, Inventory, Features, Journal
- Migrate existing free-text race/class to FK references where possible
- **Deliverable:** characters are created via guided selection with auto-populated stats; character sheet shows full mechanical detail

**M9: Character Spellcasting**
- Known/prepared spell management per class with correct limits
- Spell preparation UI (modal with available spells, multi-select, save)
- Always-prepared subclass spells
- Cantrip handling (no slot cost, character-level scaling)
- Spell slot display on character sheet (used/remaining per level)
- Pact Magic handling for Warlocks if needed
- **Deliverable:** characters have correct spell lists, can prepare/swap spells, spell slots tracked properly

**M10: Encounter Spell Casting**
- "Cast Spell" combat action: select spell ‚Üí select level ‚Üí select targets ‚Üí engine resolves
- Attack roll spells (auto-roll d20 + spell attack bonus vs AC)
- Save spells (auto-roll target saves vs spell save DC, half-on-save handling)
- Auto-apply damage, conditions, buffs with source tracking
- Concentration integration (auto-set, auto-drop on new concentration spell)
- Condition indicators showing source spell (e.g., "Restrained (Entangle)")
- Verbal/material component checks in encounter
- Player spell casting UI (on their turn, pick from prepared spells)
- DM spell casting for monsters with spellcasting stat blocks
- **Deliverable:** spells can be cast in combat with fully automated resolution

**M11: Monster Actions & Abilities**
- Parse monster stat block actions into structured `ActionTemplate` data (attack bonus, damage dice, DC, conditions)
- Monster action panel for DM: click action ‚Üí auto-resolve against target
- Legendary actions with point tracking (typically 3/round, refresh at monster's turn start)
- Legendary resistance with use tracking
- Lair actions at initiative count 20
- Monster spellcasting from stat block (extract spell list and slots)
- **Deliverable:** DM can click a monster's stat block actions and have them auto-resolve

**M12: Item System & Inventory**
- Enrich `Item` entity with structured effect data
- Equipment slots and attunement tracking (max 3 attuned)
- Inventory management UI (add items from reference, equip, attune)
- Currency tracking (gp, sp, cp, pp)
- Starting equipment from class/background
- Combat item use: potions, scrolls, wands with charges
- **Deliverable:** characters have inventories; items with effects can be used in combat

**M13: Action Economy, Reactions & DM Undo**
- Action economy tracking per turn: action, bonus action, reaction, movement, free object interaction
- Reaction system (opportunity attacks, Shield, Counterspell, etc.)
- Bonus action spell rule enforcement
- Readied actions
- DM undo: event-sourced rollback of any combat action (restore HP, remove conditions, restore spell slots, clear log entry)
- **Deliverable:** full D&D 5e action economy; DM can undo any action cleanly

---

## Q5: Pattern-Based vs Individual Definition

**Pattern-based, absolutely.** Here's why and how:

Analyzing the 294 level 0-3 spells, they fall into roughly 8 mechanical patterns:

| Pattern | Example Spells | % of Total | Automatable? |
|---------|---------------|------------|-------------|
| Attack roll + damage | Fire Bolt, Guiding Bolt, Chromatic Orb, Scorching Ray | ~15% | Fully |
| Save + damage | Fireball, Shatter, Thunderwave, Sacred Flame | ~15% | Fully |
| Save + condition | Hold Person, Command, Blindness, Entangle | ~12% | Fully |
| Buff (no roll) | Bless, Shield of Faith, Mage Armor, Aid | ~15% | Fully |
| Healing | Cure Wounds, Healing Word, Prayer of Healing | ~8% | Fully |
| Damage + condition | Tasha's Hideous Laughter, Ray of Enfeeblement | ~5% | Fully |
| Utility (no combat effect) | Detect Magic, Identify, Comprehend Languages | ~20% | Log-only |
| Complex/unique | Counterspell, Polymorph, Conjure Animals, Silence | ~10% | Partial |

**What I'd recommend:**

1. **Define ~8 effect template patterns** as a JSON schema (the architecture from Q2)
2. **I generate the effect templates for all 294 spells** by analyzing each spell's 5e.tools data + description. I'd present them to you in batches (say 30 at a time) for review.
3. **The ~20% utility spells** get `automatable: false` ‚Äî the system deducts the slot, logs "X casts Detect Magic," and that's it. No combat effect to automate.
4. **The ~10% complex spells** get partial automation ‚Äî deduct slot, log the cast, apply what we can (e.g., Counterspell: deduct slot + reaction, then prompt DM for the ability check result if needed).
5. **5e.tools already gives us** `conditionInflict`, `spellAttack`, `savingThrow`, `damageInflict`, `areaTags`, and `scalingLevelDice` as structured data ‚Äî we just need to stop ignoring them in the seeder.
6. **What we'd need to manually classify** (not in 5e.tools structured data): exact damage dice per spell level, exact target count, save-for-half flag, exact healing dice, upcast scaling details. These would come from me analyzing each spell's description text and generating the template.

**What I'd need from you to guarantee delivery:**

- **Review the effect templates in batches.** I'll generate them but you know 5e better than any automated system ‚Äî a quick scan to catch misclassifications will prevent bugs down the line.
- **Prioritize which spells matter.** Of the 294, your party probably uses 30-40 regularly. We could implement the full engine but only template those 40 first, adding more as needed.
- **Accept "DM resolves manually" for edge cases.** Trying to automate Polymorph or Counterspell perfectly will take 10x the effort of the other 90% combined. Better to ship the 90% and add complexity later.
- **Decide on class feature scope.** Your instinct to analyze which features are "flavor" vs "mechanical" before implementing is exactly right. I'll do that analysis and present it before touching code.

---

**My recommendation for where to start:** Milestones 7 and 8 in parallel. M7 (spell enrichment) unblocks everything combat-related, and M8 (character creation) unblocks everything character-related. Neither depends on the other, so we can tackle both. Want me to begin with the class feature analysis and spell pattern classification?
