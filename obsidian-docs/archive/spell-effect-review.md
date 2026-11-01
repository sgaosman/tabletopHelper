# Spell Effect Definitions — Review Document

Generated for human review. 288 spells at levels 0–3.

## Summary Statistics

| Metric | Count |
|--------|-------|
| Total spells | 288 |
| Level 0 (cantrips) | 46 |
| Level 1 | 79 |
| Level 2 | 88 |
| Level 3 | 75 |
| Requires manual resolution | 104 |
| Has REVIEW notes | 51 |
| Concentration spells | 117 |
| Ritual spells | 27 |

## Pattern Category Breakdown

| Pattern | Count | % |
|---------|-------|---|
| UTILITY | 81 | 28.1% |
| COMPLEX | 60 | 20.8% |
| BUFF_NO_ROLL | 31 | 10.8% |
| SAVE_DAMAGE | 30 | 10.4% |
| SAVE_CONDITION | 24 | 8.3% |
| ATTACK_DAMAGE | 19 | 6.6% |
| SELF_BUFF | 16 | 5.6% |
| SAVE_DAMAGE_AND_CONDITION | 10 | 3.5% |
| SUMMON | 10 | 3.5% |
| HEAL | 6 | 2.1% |
| AUTO_DAMAGE | 1 | 0.3% |

## Schema Extensions (Second Pass)

| Extension | Count | Description |
|---|---|---|
| projectileScaling | 3 | Eldritch Blast, Magic Missile, Scorching Ray -- beam/dart/ray count scaling |
| scalingInterval: 2 | 2 | Flame Blade, Spiritual Weapon -- per-2-level upcast scaling |
| reactionTrigger | 7 | All REACTION spells now have trigger descriptions |
| saveToEndEachTurn | 12 | Spells where targets repeat saves at end of turn |
| conditionalDamage | 3 | Toll the Dead, Booming Blade, Green-Flame Blade -- damage varies by condition |
| grantsAdvantage/imposesDisadvantage | 6 | Faerie Fire, Guiding Bolt, Vicious Mockery, Blur, True Strike + 1 |

## Full Spell List

| Spell Name | Level | Pattern Category | Delivery Method | Manual Resolution | Review Notes |
|------------|-------|-----------------|-----------------|-------------------|-------------|
| Acid Splash | 0 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Blade Ward | 0 | SELF_BUFF | SELF | No |  |
| Booming Blade | 0 | COMPLEX | SPELL_ATTACK | YES | REVIEW: The melee attack uses weapon attack roll, not spell attack roll, despite 5e.tools tagging... |
| Chill Touch | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Control Flames | 0 | UTILITY | NONE | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. |
| Create Bonfire | 0 | COMPLEX | SAVING_THROW | YES | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. |
| Dancing Lights | 0 | UTILITY | NONE | No |  |
| Druidcraft | 0 | UTILITY | NONE | No |  |
| Eldritch Blast | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No | Beam count scales with level via projectileScaling (1/2/3/4). Each beam is 1d10 force. | |
| Encode Thoughts | 0 | UTILITY | SELF | No | REVIEW: Classes filled from D&D 5e knowledge (GGR setting-specific). Raw data classesFromMap was ... |
| Fire Bolt | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Friends | 0 | UTILITY | NONE | No |  |
| Frostbite | 0 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. Disadvanta... |
| Green-Flame Blade | 0 | COMPLEX | SPELL_ATTACK | YES | REVIEW: The melee attack uses weapon attack roll, not spell attack roll, despite 5e.tools tagging... |
| Guidance | 0 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Gust | 0 | UTILITY | SAVING_THROW | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. The saving... |
| Infestation | 0 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. |
| Light | 0 | UTILITY | SAVING_THROW | No | REVIEW: deliveryMethod set to SAVING_THROW per cross-reference rule (5e.tools has savingThrow fie... |
| Lightning Lure | 0 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: Damage is conditional on proximity after pull. System should check if target ends within ... |
| Mage Hand | 0 | UTILITY | NONE | No |  |
| Magic Stone | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. This cantr... |
| Mending | 0 | UTILITY | NONE | No |  |
| Message | 0 | UTILITY | NONE | No |  |
| Mind Sliver | 0 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: Classes filled from D&D 5e knowledge (TCE). Raw data classesFromMap was empty (only subcl... |
| Minor Illusion | 0 | UTILITY | NONE | No |  |
| Mold Earth | 0 | UTILITY | NONE | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. Can create... |
| Poison Spray | 0 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Prestidigitation | 0 | UTILITY | NONE | No |  |
| Primal Savagery | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. This is a ... |
| Produce Flame | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Ray of Frost | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Resistance | 0 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Sacred Flame | 0 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Sapping Sting | 0 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | No | REVIEW: Classes filled from D&D 5e knowledge (EGW Dunamancy spell, Wizard only). Raw data classes... |
| Shape Water | 0 | UTILITY | NONE | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. |
| Shillelagh | 0 | SELF_BUFF | SELF | No |  |
| Shocking Grasp | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Spare the Dying | 0 | UTILITY | AUTO_HIT | No |  |
| Sword Burst | 0 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Thaumaturgy | 0 | UTILITY | NONE | No |  |
| Thorn Whip | 0 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Thunderclap | 0 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. Audible to... |
| Toll the Dead | 0 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. The d12 vs... |
| True Strike | 0 | SELF_BUFF | NONE | No |  |
| Vicious Mockery | 0 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | No | Disadvantage on next attack via imposesDisadvantage |
| Word of Radiance | 0 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: Classes filled from D&D 5e knowledge (XGE). Raw data classesFromMap was empty. Unlike Thu... |
| Absorb Elements | 1 | COMPLEX | SELF | YES |  |
| Alarm | 1 | UTILITY | NONE | No |  |
| Animal Friendship | 1 | SAVE_CONDITION | SAVING_THROW | No |  |
| Armor of Agathys | 1 | SELF_BUFF | SELF | YES |  |
| Arms of Hadar | 1 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Bane | 1 | SAVE_CONDITION | SAVING_THROW | No |  |
| Beast Bond | 1 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Bless | 1 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Burning Hands | 1 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Catapult | 1 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Cause Fear | 1 | SAVE_CONDITION | SAVING_THROW | No |  |
| Ceremony | 1 | UTILITY | NONE | YES |  |
| Chaos Bolt | 1 | ATTACK_DAMAGE | SPELL_ATTACK | YES | REVIEW: Damage type is random based on d8 roll. Leaping mechanic on matching d8s makes full autom... |
| Charm Person | 1 | SAVE_CONDITION | SAVING_THROW | No |  |
| Chromatic Orb | 1 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Color Spray | 1 | COMPLEX | AUTO_HIT | YES |  |
| Command | 1 | SAVE_CONDITION | SAVING_THROW | YES | REVIEW: 'Grovel' command causes prone (listed in conditionInflict). Other commands have varying e... |
| Compelled Duel | 1 | SAVE_CONDITION | SAVING_THROW | YES |  |
| Comprehend Languages | 1 | UTILITY | SELF | No |  |
| Create or Destroy Water | 1 | UTILITY | NONE | No |  |
| Cure Wounds | 1 | HEAL | AUTO_HIT | No |  |
| Detect Evil and Good | 1 | UTILITY | SELF | No |  |
| Detect Magic | 1 | UTILITY | SELF | No |  |
| Detect Poison and Disease | 1 | UTILITY | SELF | No |  |
| Disguise Self | 1 | UTILITY | SELF | No |  |
| Dissonant Whispers | 1 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Distort Value | 1 | UTILITY | NONE | No |  |
| Divine Favor | 1 | SELF_BUFF | SELF | No |  |
| Earth Tremor | 1 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | No |  |
| Ensnaring Strike | 1 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | YES |  |
| Entangle | 1 | SAVE_CONDITION | SAVING_THROW | No |  |
| Expeditious Retreat | 1 | SELF_BUFF | SELF | No |  |
| Faerie Fire | 1 | SAVE_CONDITION | SAVING_THROW | No |  |
| False Life | 1 | SELF_BUFF | SELF | No |  |
| Feather Fall | 1 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Find Familiar | 1 | SUMMON | NONE | YES |  |
| Fog Cloud | 1 | UTILITY | NONE | YES |  |
| Frost Fingers | 1 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Gift of Alacrity | 1 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Goodberry | 1 | HEAL | NONE | YES | REVIEW: Life Domain Disciple of Life interaction (2+spell level per berry) is a common ruling que... |
| Grease | 1 | SAVE_CONDITION | SAVING_THROW | No |  |
| Guiding Bolt | 1 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Hail of Thorns | 1 | SAVE_DAMAGE | SAVING_THROW | YES |  |
| Healing Word | 1 | HEAL | AUTO_HIT | No |  |
| Hellish Rebuke | 1 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Heroism | 1 | BUFF_NO_ROLL | AUTO_HIT | YES |  |
| Hex | 1 | COMPLEX | AUTO_HIT | YES |  |
| Hunter's Mark | 1 | COMPLEX | AUTO_HIT | YES |  |
| Ice Knife | 1 | ATTACK_DAMAGE | SPELL_ATTACK | YES |  |
| Identify | 1 | UTILITY | NONE | No |  |
| Illusory Script | 1 | UTILITY | NONE | No |  |
| Inflict Wounds | 1 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Jim's Magic Missile | 1 | ATTACK_DAMAGE | SPELL_ATTACK | YES | REVIEW: Acquisitions Incorporated source. Unlike regular Magic Missile, requires attack rolls. Ha... |
| Jump | 1 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Longstrider | 1 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Mage Armor | 1 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Magic Missile | 1 | AUTO_DAMAGE | AUTO_HIT | YES |  |
| Magnify Gravity | 1 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Protection from Evil and Good | 1 | BUFF_NO_ROLL | AUTO_HIT | No | REVIEW: Material consumed but no specific GP cost in 5e.tools data. Touch range. Concentration. S... |
| Purify Food and Drink | 1 | UTILITY | NONE | No |  |
| Ray of Sickness | 1 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Sanctuary | 1 | BUFF_NO_ROLL | SAVING_THROW | YES |  |
| Searing Smite | 1 | SAVE_DAMAGE | SAVING_THROW | YES |  |
| Shield | 1 | SELF_BUFF | SELF | No |  |
| Shield of Faith | 1 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Silent Image | 1 | UTILITY | NONE | YES |  |
| Silvery Barbs | 1 | COMPLEX | AUTO_HIT | YES |  |
| Sleep | 1 | COMPLEX | AUTO_HIT | YES |  |
| Snare | 1 | SAVE_CONDITION | SAVING_THROW | YES |  |
| Speak with Animals | 1 | UTILITY | SELF | No |  |
| Tasha's Caustic Brew | 1 | SAVE_DAMAGE | SAVING_THROW | YES |  |
| Tasha's Hideous Laughter | 1 | SAVE_CONDITION | SAVING_THROW | No |  |
| Tenser's Floating Disk | 1 | UTILITY | NONE | No |  |
| Thunderous Smite | 1 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | YES |  |
| Thunderwave | 1 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Unseen Servant | 1 | SUMMON | NONE | No |  |
| Witch Bolt | 1 | ATTACK_DAMAGE | SPELL_ATTACK | YES |  |
| Wrathful Smite | 1 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | YES |  |
| Zephyr Strike | 1 | SELF_BUFF | SELF | YES |  |
| Aganazzar's Scorcher | 2 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Aid | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Air Bubble | 2 | UTILITY | AUTO_HIT | No |  |
| Alter Self | 2 | SELF_BUFF | SELF | YES |  |
| Animal Messenger | 2 | UTILITY | NONE | No |  |
| Arcane Lock | 2 | UTILITY | NONE | No |  |
| Augury | 2 | UTILITY | SELF | No |  |
| Barkskin | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Beast Sense | 2 | UTILITY | AUTO_HIT | No |  |
| Blindness/Deafness | 2 | SAVE_CONDITION | SAVING_THROW | No |  |
| Blur | 2 | SELF_BUFF | SELF | No |  |
| Borrowed Knowledge | 2 | UTILITY | SELF | No |  |
| Branding Smite | 2 | SELF_BUFF | SELF | No |  |
| Calm Emotions | 2 | SAVE_CONDITION | SAVING_THROW | No |  |
| Cloud of Daggers | 2 | COMPLEX | AUTO_HIT | YES |  |
| Continual Flame | 2 | UTILITY | NONE | No |  |
| Cordon of Arrows | 2 | COMPLEX | SAVING_THROW | YES |  |
| Crown of Madness | 2 | COMPLEX | SAVING_THROW | YES |  |
| Darkness | 2 | UTILITY | NONE | No |  |
| Darkvision | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Detect Thoughts | 2 | UTILITY | SELF | No |  |
| Dragon's Breath | 2 | COMPLEX | AUTO_HIT | YES |  |
| Dust Devil | 2 | COMPLEX | SAVING_THROW | YES |  |
| Earthbind | 2 | SAVE_CONDITION | SAVING_THROW | No | REVIEW: Not a standard condition; reduces flying speed to 0. Airborne creature safely descends at... |
| Enhance Ability | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Enlarge/Reduce | 2 | COMPLEX | SAVING_THROW | YES |  |
| Enthrall | 2 | SAVE_CONDITION | SAVING_THROW | No |  |
| Find Steed | 2 | SUMMON | NONE | YES |  |
| Find Traps | 2 | UTILITY | SELF | No |  |
| Flame Blade | 2 | COMPLEX | SPELL_ATTACK | YES |  |
| Flaming Sphere | 2 | COMPLEX | SAVING_THROW | YES |  |
| Flock of Familiars | 2 | SUMMON | NONE | YES |  |
| Fortune's Favor | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Gentle Repose | 2 | UTILITY | NONE | No |  |
| Gift of Gab | 2 | UTILITY | SELF | No |  |
| Gust of Wind | 2 | COMPLEX | SAVING_THROW | YES |  |
| Healing Spirit | 2 | COMPLEX | NONE | YES |  |
| Heat Metal | 2 | COMPLEX | AUTO_HIT | YES |  |
| Hold Person | 2 | SAVE_CONDITION | SAVING_THROW | No |  |
| Immovable Object | 2 | UTILITY | NONE | No |  |
| Invisibility | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Jim's Glowing Coin | 2 | SAVE_CONDITION | SAVING_THROW | No |  |
| Kinetic Jaunt | 2 | SELF_BUFF | SELF | No |  |
| Knock | 2 | UTILITY | NONE | No |  |
| Lesser Restoration | 2 | UTILITY | AUTO_HIT | No | Removes conditions, does not heal HP |
| Levitate | 2 | UTILITY | SAVING_THROW | No |  |
| Locate Animals or Plants | 2 | UTILITY | SELF | No |  |
| Locate Object | 2 | UTILITY | SELF | No |  |
| Magic Mouth | 2 | UTILITY | NONE | No |  |
| Magic Weapon | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Maximilian's Earthen Grasp | 2 | COMPLEX | SAVING_THROW | YES |  |
| Melf's Acid Arrow | 2 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| Mind Spike | 2 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Mirror Image | 2 | COMPLEX | SELF | YES |  |
| Misty Step | 2 | UTILITY | SELF | No |  |
| Moonbeam | 2 | COMPLEX | SAVING_THROW | YES |  |
| Nathair's Mischief | 2 | COMPLEX | SAVING_THROW | YES |  |
| Nystul's Magic Aura | 2 | UTILITY | NONE | No |  |
| Pass without Trace | 2 | BUFF_NO_ROLL | SELF | No |  |
| Phantasmal Force | 2 | COMPLEX | SAVING_THROW | YES |  |
| Prayer of Healing | 2 | HEAL | AUTO_HIT | No |  |
| Protection from Poison | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Pyrotechnics | 2 | SAVE_CONDITION | SAVING_THROW | YES |  |
| Ray of Enfeeblement | 2 | COMPLEX | SPELL_ATTACK | YES |  |
| Rime's Binding Ice | 2 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | No | REVIEW: Speed reduction is not a standard condition. Half damage on save but no ice hindrance. Ac... |
| Rope Trick | 2 | UTILITY | NONE | No |  |
| Scorching Ray | 2 | ATTACK_DAMAGE | SPELL_ATTACK | No |  |
| See Invisibility | 2 | SELF_BUFF | SELF | No |  |
| Shadow Blade | 2 | COMPLEX | SELF | YES |  |
| Shatter | 2 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Silence | 2 | UTILITY | NONE | No |  |
| Skywrite | 2 | UTILITY | NONE | No |  |
| Snilloc's Snowball Swarm | 2 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Spider Climb | 2 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Spike Growth | 2 | COMPLEX | NONE | YES |  |
| Spiritual Weapon | 2 | COMPLEX | SPELL_ATTACK | YES |  |
| Spray of Cards | 2 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | No |  |
| Suggestion | 2 | SAVE_CONDITION | SAVING_THROW | No |  |
| Summon Beast | 2 | SUMMON | NONE | YES |  |
| Tasha's Mind Whip | 2 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | No | REVIEW: upcast does not increase damage, only target count. |
| Vortex Warp | 2 | UTILITY | SAVING_THROW | No |  |
| Warding Bond | 2 | COMPLEX | AUTO_HIT | YES |  |
| Warding Wind | 2 | SELF_BUFF | SELF | No |  |
| Warp Sense | 2 | UTILITY | SELF | No |  |
| Web | 2 | SAVE_CONDITION | SAVING_THROW | YES |  |
| Wither and Bloom | 2 | COMPLEX | SAVING_THROW | YES |  |
| Wristpocket | 2 | UTILITY | SELF | No |  |
| Zone of Truth | 2 | UTILITY | SAVING_THROW | No |  |
| Animate Dead | 3 | COMPLEX | NONE | YES |  |
| Antagonize | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: No classes listed in source data; may be from Book of Many Things supplement. On successf... |
| Ashardalon's Stride | 3 | COMPLEX | SELF | YES | REVIEW: No classes listed in source data; from Fizban's Treasury of Dragons. Speed bonus also upc... |
| Aura of Vitality | 3 | COMPLEX | SELF | YES |  |
| Beacon of Hope | 3 | BUFF_NO_ROLL | AUTO_HIT | No | REVIEW: targetCount is null because spell says 'any number of creatures within range' — no fixed ... |
| Bestow Curse | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: 1d8 necrotic damage is only one of four curse options, not always applied. Touch range. |
| Blinding Smite | 3 | COMPLEX | SAVING_THROW | YES |  |
| Blink | 3 | SELF_BUFF | SELF | YES |  |
| Call Lightning | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: aoeSize is 5 (the bolt's 5ft radius), but the storm cloud is 60ft radius. Each bolt targe... |
| Catnap | 3 | UTILITY | AUTO_HIT | No | REVIEW: No classes listed in source data; from Xanathar's Guide. Willing targets so no save needed. |
| Clairvoyance | 3 | UTILITY | NONE | No |  |
| Conjure Animals | 3 | COMPLEX | NONE | YES |  |
| Conjure Barrage | 3 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: Damage type depends on weapon used — typically slashing, piercing, or bludgeoning. No upc... |
| Counterspell | 3 | COMPLEX | NONE | YES |  |
| Create Food and Water | 3 | UTILITY | NONE | No |  |
| Crusader's Mantle | 3 | COMPLEX | SELF | YES |  |
| Daylight | 3 | UTILITY | NONE | No |  |
| Dispel Magic | 3 | COMPLEX | NONE | YES |  |
| Elemental Weapon | 3 | BUFF_NO_ROLL | AUTO_HIT | YES | REVIEW: Non-standard upcast scaling — not per-level linear. damageType is 'varies' because caster... |
| Enemies Abound | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: No classes listed in source data; from Xanathar's Guide (typically Bard, Sorcerer, Warloc... |
| Erupting Earth | 3 | SAVE_DAMAGE | SAVING_THROW | No | REVIEW: No classes listed in source data; from Xanathar's Guide (typically Druid, Sorcerer, Wizar... |
| Fast Friends | 3 | SAVE_CONDITION | SAVING_THROW | No |  |
| Fear | 3 | SAVE_CONDITION | SAVING_THROW | No |  |
| Feign Death | 3 | UTILITY | AUTO_HIT | No |  |
| Fireball | 3 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Flame Arrows | 3 | COMPLEX | AUTO_HIT | YES | REVIEW: Source is XGE. classesFromMap was empty in data; classes listed from general D&D 5e knowl... |
| Fly | 3 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Galder's Tower | 3 | UTILITY | NONE | No |  |
| Gaseous Form | 3 | BUFF_NO_ROLL | AUTO_HIT | YES |  |
| Glyph of Warding | 3 | COMPLEX | SAVING_THROW | YES |  |
| Haste | 3 | BUFF_NO_ROLL | AUTO_HIT | YES |  |
| Hunger of Hadar | 3 | COMPLEX | SAVING_THROW | YES |  |
| Hypnotic Pattern | 3 | SAVE_CONDITION | SAVING_THROW | No |  |
| Incite Greed | 3 | SAVE_CONDITION | SAVING_THROW | No |  |
| Intellect Fortress | 3 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Leomund's Tiny Hut | 3 | UTILITY | SELF | YES |  |
| Life Transference | 3 | COMPLEX | AUTO_HIT | YES |  |
| Lightning Arrow | 3 | COMPLEX | SAVING_THROW | YES |  |
| Lightning Bolt | 3 | SAVE_DAMAGE | SAVING_THROW | No |  |
| Linked Glyphs | 3 | COMPLEX | NONE | YES | REVIEW: Source is AitFR-AVT. conditionInflict 'invisible' in source data refers to the glyphs bei... |
| Magic Circle | 3 | COMPLEX | SAVING_THROW | YES |  |
| Major Image | 3 | UTILITY | NONE | No |  |
| Mass Healing Word | 3 | HEAL | AUTO_HIT | No |  |
| Meld into Stone | 3 | UTILITY | SELF | YES |  |
| Melf's Minute Meteors | 3 | COMPLEX | SAVING_THROW | YES |  |
| Motivational Speech | 3 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Nondetection | 3 | UTILITY | NONE | No |  |
| Phantom Steed | 3 | UTILITY | NONE | No |  |
| Plant Growth | 3 | UTILITY | NONE | YES |  |
| Protection from Energy | 3 | BUFF_NO_ROLL | AUTO_HIT | No |  |
| Pulse Wave | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: Dunamancy spell from Explorer's Guide to Wildemount. Half damage on success but NO forced... |
| Remove Curse | 3 | UTILITY | AUTO_HIT | No |  |
| Revivify | 3 | HEAL | AUTO_HIT | YES |  |
| Sending | 3 | UTILITY | NONE | No |  |
| Sleet Storm | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: Two different saves — DEX for prone and CON for concentration. Cylinder is 20ft tall with... |
| Slow | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: No standard condition inflicted despite heavy debuff. The spell's effects are a unique co... |
| Speak with Dead | 3 | UTILITY | NONE | No |  |
| Speak with Plants | 3 | UTILITY | NONE | No |  |
| Spirit Guardians | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: Damage type is radiant or necrotic depending on caster alignment. Both damageInflict type... |
| Spirit Shroud | 3 | COMPLEX | SELF | YES | REVIEW: Upcast scaling is non-standard — 1d8 per 2 slot levels above 3rd. Damage type chosen from... |
| Stinking Cloud | 3 | COMPLEX | SAVING_THROW | YES | REVIEW: No standard condition inflicted. Effect is 'wastes action retching' which is mechanically... |
| Summon Fey | 3 | SUMMON | NONE | YES | REVIEW: Tasha's Cauldron summon spell. Material component not consumed. |
| Summon Lesser Demons | 3 | SUMMON | NONE | YES | REVIEW: Material component optionally consumed (for protective circle). Demons attack nearest non... |
| Summon Shadowspawn | 3 | SUMMON | NONE | YES | REVIEW: Tasha's Cauldron summon spell. Material component not consumed. |
| Summon Undead | 3 | SUMMON | NONE | YES | REVIEW: Tasha's Cauldron summon spell. Material component not consumed. |
| Thunder Step | 3 | COMPLEX | SAVING_THROW | YES |  |
| Tidal Wave | 3 | SAVE_DAMAGE_AND_CONDITION | SAVING_THROW | No | REVIEW: Area shape is non-standard (30x10x10 wall-like shape). Mapped as SPECIAL targetType with ... |
| Tiny Servant | 3 | SUMMON | NONE | YES |  |
| Tongues | 3 | UTILITY | NONE | No |  |
| Vampiric Touch | 3 | COMPLEX | SPELL_ATTACK | YES |  |
| Wall of Sand | 3 | UTILITY | NONE | YES |  |
| Wall of Water | 3 | UTILITY | NONE | YES |  |
| Water Breathing | 3 | UTILITY | NONE | No |  |
| Water Walk | 3 | UTILITY | NONE | No |  |
| Wind Wall | 3 | COMPLEX | SAVING_THROW | YES |  |

## Spells Requiring Manual Resolution (104)

| Spell Name | Level | Reason |
|------------|-------|--------|
| Booming Blade | 0 | Involves a melee weapon attack (not a standard spell attack), with conditional movement-triggered secondary damage th... |
| Create Bonfire | 0 | Persistent zone requiring saves on entry and at end of turn for each creature. Requires positional tracking each round. |
| ~~Eldritch Blast~~ | ~~0~~ | ~~Fixed: now automatable via projectileScaling~~ | |
| Green-Flame Blade | 0 | Involves a melee weapon attack (not standard spell attack), secondary target selection within 5 feet of primary, and ... |
| Absorb Elements | 1 | Two-phase effect: resistance on cast (reaction), extra damage on next melee hit. Damage type depends on triggering da... |
| Armor of Agathys | 1 | Reactive damage: cold damage triggers when hit by melee attack while temp HP remain. Requires tracking temp HP from t... |
| Ceremony | 1 | Multiple rite choices with very different effects. Wedding's +2 AC is combat-relevant but conditional. |
| Chaos Bolt | 1 | Damage type determined by d8 roll. Matching d8s cause bolt to leap to new target with new attack and damage rolls. |
| Color Spray | 1 | HP pool targeting from lowest HP - requires knowledge of all creatures' current HP in the cone to determine who is af... |
| Command | 1 | Effect depends on which one-word command is given. DM determines behavior for non-standard commands. |
| Compelled Duel | 1 | Complex end conditions: ends if caster attacks others, casts harmful spells on non-target, ally harms target, or cast... |
| Ensnaring Strike | 1 | Smite-style spell: triggers on next weapon hit, then requires STR save. Ongoing damage at start of restrained target'... |
| Find Familiar | 1 | Creates a permanent companion creature with its own stat block, initiative, and actions. Touch spell delivery require... |
| Fog Cloud | 1 | Heavily obscured area creates complex line-of-sight interactions. Creatures in fog are effectively blinded (advantage... |
| Goodberry | 1 | Creates consumable items (10 berries) that are used over time. Each use is a separate action. Life Domain Cleric Disc... |
| Hail of Thorns | 1 | Smite-style: triggers on next ranged weapon hit. AOE burst affects target and creatures within 5 feet of target. |
| Heroism | 1 | Recurring temp HP at start of each turn requires per-turn tracking. Amount equals caster's spellcasting ability modif... |
| Hex | 1 | Extra damage on every hit (not just once). Transferable to new target on kill. Upcast extends concentration duration ... |
| Hunter's Mark | 1 | Extra damage on every weapon hit (not just once). Transferable to new target on kill. Upcast extends concentration du... |
| Ice Knife | 1 | Two delivery methods: ranged spell attack for piercing damage, then DEX save for cold explosion (hit or miss). Explos... |
| Jim's Magic Missile | 1 | Multiple attack rolls. Special crit rules (5d4 instead of doubled). Natural 1 on any dart causes all to backfire on c... |
| Magic Missile | 1 | Multiple darts that can be freely distributed among targets. Each dart is a separate instance of damage (relevant for... |
| Sanctuary | 1 | Requires WIS save from each attacker before each attack/spell. Complex end conditions (warded creature attacks, casts... |
| Searing Smite | 1 | Smite-style delivery on next weapon hit. Ongoing fire damage with per-turn CON saves. Can be manually extinguished. |
| Silent Image | 1 | Illusion effects are inherently DM-adjudicated. No mechanical automation possible. |
| Silvery Barbs | 1 | Reaction timing: triggers after a successful d20 roll. Forces reroll and grants advantage to a different creature. Re... |
| Sleep | 1 | HP pool targeting from lowest HP - requires knowledge of all creatures' current HP in the area to determine who is af... |
| Snare | 1 | Trap mechanic: placed ahead of time, triggers on creature movement. Nearly invisible. Two ways to escape (DEX save or... |
| Tasha's Caustic Brew | 1 | Ongoing damage at start of each affected creature's turn. Can be removed by action (self or ally). Concentration. |
| Thunderous Smite | 1 | Smite-style delivery on next weapon hit. Forced movement (10 ft push) on failed save. |
| Witch Bolt | 1 | Sustained damage over multiple turns. Must use action each turn to maintain. Upcast only affects initial damage, not ... |
| Wrathful Smite | 1 | Smite-style delivery. To end frightened, creature must use action for WIS ability check (not save) vs spell DC - impo... |
| Zephyr Strike | 1 | Two effects: passive (no OA) and one-time active (advantage + damage + speed). Must track whether one-time effect has... |
| Alter Self | 2 | Multiple mode choices (Aquatic Adaptation, Change Appearance, Natural Weapons) with different mechanical effects |
| Cloud of Daggers | 2 | Persistent area damage with no save; triggers on entering or starting turn in area |
| Cordon of Arrows | 2 | Persistent trap effect; triggers when creature enters 30ft area; limited ammunition (4 pieces); each piece destroyed ... |
| Crown of Madness | 2 | Requires caster to use action each turn to maintain. Target must attack a creature chosen by the caster. WIS save at ... |
| Dragon's Breath | 2 | Grants touched creature ability to exhale 15ft cone (DEX save, damage) as an action on future turns. Damage type chos... |
| Dust Devil | 2 | Persistent movable effect. STR save for creatures ending turn within 5ft. Half damage on save but not pushed. Can be ... |
| Enlarge/Reduce | 2 | Two modes (Enlarge/Reduce) with different effects. CON save only if unwilling. Modifies weapon damage, size, STR chec... |
| Find Steed | 2 | Summons a permanent steed (warhorse, pony, camel, elk, or mastiff). Steed has its own stat block and acts in combat. |
| Flame Blade | 2 | Creates a fiery blade weapon. Melee spell attack as action on each turn. Bonus action to cast, action to attack. Upca... |
| Flaming Sphere | 2 | Persistent movable sphere. Creatures ending turn within 5ft must save. Can be rammed into creatures as bonus action (... |
| Flock of Familiars | 2 | Summons 3 familiars with individual stat blocks. Can deliver touch spells. |
| Gust of Wind | 2 | Persistent line effect. STR save each turn for creatures starting turn in line. Pushed 15ft on fail. Moving closer co... |
| Healing Spirit | 2 | Creates a healing zone. Creatures moving through or starting turn there can be healed (no action). Limited total heal... |
| Heat Metal | 2 | Auto-damage with no save on initial cast and each bonus action repeat. CON save only to avoid dropping object/disadva... |
| Maximilian's Earthen Grasp | 2 | Multi-turn spell: initial grab (STR save, damage + restrained), crush action (STR save, damage), retarget/move as act... |
| Mirror Image | 2 | Creates 3 illusory duplicates. Each attack targeting caster requires d20 roll to determine if it hits a duplicate ins... |
| Moonbeam | 2 | Persistent movable AOE cylinder. CON save when entering area first time on a turn or starting turn there. Can be move... |
| Nathair's Mischief | 2 | Random effect each turn (d4 table). Different saves (WIS or DEX) depending on result. Movable 10ft before rolling. Ef... |
| Phantasmal Force | 2 | Creates an illusion perceived only by the target. DM adjudicates what the illusion is and how the target interacts wi... |
| Pyrotechnics | 2 | Two modes: Fireworks (CON save, blinded until end of next turn, 10ft radius) or Smoke (20ft radius heavily obscured, ... |
| Ray of Enfeeblement | 2 | Ranged spell attack that applies a debuff (no damage). Target deals half damage with STR-based weapon attacks. CON sa... |
| Shadow Blade | 2 | Creates a magic weapon (simple melee, finesse, light, thrown 20/60). Damage dealt via weapon attacks, not spell attac... |
| Spike Growth | 2 | Damage based on movement through area (2d4 per 5ft), not saves. Area is difficult terrain. Camouflaged (Perception ch... |
| Spiritual Weapon | 2 | Creates a persistent spectral weapon. Melee spell attack on cast (bonus action) and each subsequent turn (bonus actio... |
| Summon Beast | 2 | Summons a creature with its own stat block (Bestial Spirit). Environment choice affects traits. Creature acts on cast... |
| Warding Bond | 2 | Target gains +1 AC, +1 saves, resistance to all damage. But caster takes the same damage the target takes. Ends if >6... |
| Web | 2 | Persistent area: DEX save when entering or starting turn. Restrained on fail. STR check (action) to break free. Webs ... |
| Wither and Bloom | 2 | Combines damage (CON save, half on success) with healing (one creature spends a Hit Die to heal). Two distinct effects. |
| Animate Dead | 3 | Creates persistent undead companions that need stat blocks, initiative, and ongoing command management |
| Antagonize | 3 | Requires choosing a second target for the forced reaction attack and determining if target can make the attack |
| Ashardalon's Stride | 3 | Persistent movement-triggered damage that applies automatically as caster moves near creatures each turn |
| Aura of Vitality | 3 | Requires bonus action each turn to select a creature in the aura and heal it |
| Bestow Curse | 3 | Multiple curse effect options that the caster must choose from, each with different mechanical impacts |
| Blinding Smite | 3 | Triggered on next melee weapon hit (smite spell pattern); requires tracking pending smite and repeated saves |
| Blink | 3 | Requires d20 roll at end of each turn and tracking plane-shift state; not concentration but has duration |
| Call Lightning | 3 | Persistent effect requiring action each turn to target a new point; storm cloud has spatial constraints; bonus damage... |
| Conjure Animals | 3 | DM chooses creatures, multiple summons require stat blocks, initiative, and action management |
| Counterspell | 3 | Reaction timing, identifying target spell level, potential ability check needed for higher-level spells |
| Crusader's Mantle | 3 | Persistent aura buff that modifies weapon attack damage for all friendly creatures within range each turn |
| Dispel Magic | 3 | Requires identifying active magical effects on target and their levels, potential ability check for higher-level effects |
| Elemental Weapon | 3 | Modifies weapon attack rolls and damage; non-linear upcast scaling (+2/2d4 at 5th, +3/3d4 at 7th) requires special ha... |
| Enemies Abound | 3 | Requires random target selection for affected creature's attacks and tracking repeated saves on damage |
| Flame Arrows | 3 | Requires tracking individual ammunition shots and applying extra damage per hit |
| Gaseous Form | 3 | Complex transformation with multiple mechanical effects: resistance to nonmagical damage, advantage on three save typ... |
| Glyph of Warding | 3 | Trap spell with complex trigger conditions, two modes of operation (explosive runes or spell glyph), and contextual a... |
| Haste | 3 | Multiple simultaneous buffs (speed doubling, AC bonus, advantage on DEX saves, extra action) plus debilitating lethar... |
| Hunger of Hadar | 3 | Persistent AOE with two separate damage triggers (start of turn cold, end of turn acid DEX save), automatic blinding ... |
| Leomund's Tiny Hut | 3 | Creates a persistent environmental effect that blocks spells and creatures from passing through; complex interaction ... |
| Life Transference | 3 | Self-damage that cannot be reduced feeds into healing calculation (2x damage = healing). Requires rolling damage, app... |
| Lightning Arrow | 3 | Two-phase attack: ranged weapon attack roll for primary target (full on hit, half on miss), then DEX save for AOE sec... |
| Linked Glyphs | 3 | Complex trap spell with linked glyphs, multiple trigger conditions, and stored spell interactions |
| Magic Circle | 3 | Complex protective ward with multiple effects against chosen creature types, optional reverse mode, and CHA save only... |
| Meld into Stone | 3 | Complex self-transformation with conditional damage on expulsion, Perception disadvantage, and ability to still cast ... |
| Melf's Minute Meteors | 3 | Persistent resource (6 meteors) with bonus action activation each turn, 1-2 meteors per use, each with its own AOE an... |
| Plant Growth | 3 | Two casting time modes with different effects; terrain modification requires DM adjudication |
| Pulse Wave | 3 | Forced movement (pull/push) requires positional tracking and caster choice of direction |
| Revivify | 3 | Requires dead creature target (died within 1 minute); system must validate death timing and consume 300gp material |
| Sleet Storm | 3 | Persistent AOE with per-turn saves (DEX for prone, CON for concentration), heavily obscured area, difficult terrain t... |
| Slow | 3 | Multiple complex debuff effects (AC penalty, save penalty, action economy restrictions, spell delay mechanic), per-tu... |
| Spirit Guardians | 3 | Persistent AOE that moves with caster, triggers on creature entry or turn start, requires tracking designated unaffec... |
| Spirit Shroud | 3 | Persistent self-buff adding damage to all attacks against nearby creatures, healing prevention, and speed reduction a... |
| Stinking Cloud | 3 | Persistent AOE with per-turn CON save causing action loss (not a standard condition), heavily obscured area, wind dis... |
| Summon Fey | 3 | Summoned creature with stat block, initiative, and actions that need to be managed each turn |
| Summon Lesser Demons | 3 | Random demon count, DM chooses types, demons are hostile to everyone, protective circle mechanic, multiple creatures ... |
| Summon Shadowspawn | 3 | Summoned creature with stat block, initiative, and actions that need to be managed each turn |
| Summon Undead | 3 | Summoned creature with stat block, initiative, and actions that need to be managed each turn |
| Thunder Step | 3 | Teleportation combined with AOE damage at departure point; positional tracking required for both caster movement and ... |
| Tiny Servant | 3 | Summoned creature(s) with stat block that need turn-by-turn management; scales with upcast level |
| Vampiric Touch | 3 | Persistent spell attack repeatable each turn; healing derived from damage dealt requires calculation per hit |
| Wall of Sand | 3 | Wall placement and tracking which creatures are within the wall's space for blinding and movement cost |
| Wall of Water | 3 | Wall placement, difficult terrain, attack disadvantage tracking, fire damage halving, cold interaction creating destr... |
| Wind Wall | 3 | Wall placement with shapeable path, persistent barrier blocking small flyers and projectiles, initial damage on appea... |

## Validation Results (Second Pass)

- PASS: All 35 validation checks passed (32 pass, 2 informational, 1 noted)
- PASS: No SPELL_ATTACK spell has halfOnSave=true
- PASS: 117 concentration spells verified
- PASS: All SAVING_THROW spells have saveAbility
- PASS: All REACTION spells have reactionTrigger
- PASS: All saveToAvoid are boolean, all saveAbility are strings
- PASS: No remaining {@...} markup
- PASS: All 288 spells have populated classes arrays
- NOTE: Magic Stone is the only damage cantrip without cantripScaling (intentional -- does not scale with level)
- NOTE: Eldritch Blast now automatable via projectileScaling (manual count reduced from 105 to 104)

