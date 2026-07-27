package com.tabletophelper.seeder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the M24.5 resource pool reference tables on startup.
 * Runs after {@link CharacterClassSeeder} so class entities are available.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResourcePoolSeeder {

    private final ResourcePoolDefinitionRepository poolDefRepo;
    private final ClassFeaturePoolRepository classFeaturePoolRepo;
    private final MonsterPoolTriggerRepository monsterTriggerRepo;
    private final CharacterClassRepository classRepo;
    private final SubclassRepository subclassRepo;
    private final ObjectMapper objectMapper;

    public void seed() {
        if (poolDefRepo.count() > 0) {
            log.info("Resource pool definitions already seeded, skipping");
            return;
        }

        log.info("=== Seeding resource pool system (M24.5) ===");

        seedDefinitions();
        seedClassFeaturePools();
        seedMonsterTriggers();

        log.info("=== Resource pool seeding complete ===");
    }

    // ── Pool definitions ─────────────────────────────────────────

    private void seedDefinitions() {
        List<ResourcePoolDefinition> defs = List.of(
                // ── Class features ──
                def("class:monk-ki", "Ki Points", "CLASS",
                        "monkLevel", "shortRest", null, null,
                        "FREE", "Ki points power Flurry of Blows, Patient Defense, Step of the Wind, and Stunning Strike.", "zap", null),

                def("class:sorcerer-sorcery-points", "Sorcery Points", "CLASS",
                        "sorcererLevel", "longRest", null, null,
                        "FREE", "Sorcery points fuel Metamagic options and can be converted to spell slots via Flexible Casting.", "sparkles", null),

                def("class:cleric-channel-divinity", "Channel Divinity", "CLASS",
                        "1", "shortRest", null, null,
                        "ACTION", "Channel Divinity grants a domain-specific effect. Regains all uses on a short or long rest.", "sun", null),

                def("class:paladin-channel-divinity", "Channel Divinity", "CLASS",
                        "1", "shortRest", null, null,
                        "ACTION", "Channel Divinity grants an oath-specific effect. Regains all uses on a short or long rest.", "sun", null),

                def("class:druid-wild-shape", "Wild Shape", "CLASS",
                        "2", "shortRest", null, null,
                        "ACTION", "Transform into a beast you have seen before. Regains uses on a short or long rest.", "paw-print", null),

                def("class:fighter-second-wind", "Second Wind", "CLASS",
                        "1", "shortRest", null, null,
                        "BONUS_ACTION", "Regain 1d10 + fighter level HP as a bonus action.", "heart", null),

                def("class:fighter-action-surge", "Action Surge", "CLASS",
                        "1", "shortRest", null, null,
                        "FREE", "Take one additional action on your turn. Starting at 17th level, you can use it twice before a rest.", "zap", null),

                def("class:bard-bardic-inspiration", "Bardic Inspiration", "CLASS",
                        "charismaModifier", "longRest", null, null,
                        "BONUS_ACTION", "Inspire an ally with a die (d6 at levels 1-4, d8 at 5-9, d10 at 10-14, d12 at 15+). Regains on short rest starting at 5th level.", "music", null),

                def("class:barbarian-rage", "Rage", "CLASS",
                        "2", "longRest", null, null,
                        "BONUS_ACTION", "Enter a rage for 1 minute. Advantage on STR checks/saves, bonus melee damage, resistance to bludgeoning/piercing/slashing.", "flame", null),

                // ── Subclass features (Fighter) ──
                def("subclass:battle-master-superiority-dice", "Superiority Dice", "CLASS",
                        "3+ceil(fighterLevel+1/7)", "shortRest", null, null,
                        "FREE", "d8 superiority dice that fuel combat manoeuvres. 4 dice at L3, 5 at L7, 6 at L15. Regain all on a short or long rest.", "zap", null),

                def("subclass:arcane-archer-arcane-shot", "Arcane Shot", "CLASS",
                        "2", "shortRest", null, null,
                        "FREE", "Magical arrow shots with special effects. Regain all uses on a short or long rest.", "sparkles", null),

                def("subclass:psi-warrior-psionic-energy", "Psionic Energy Dice", "CLASS",
                        "2*proficiencyBonus", "longRest", "1", null,
                        "FREE", "Psionic power dice. Regain all on a long rest, or 1 die on a short rest.", "zap", null),

                def("subclass:samurai-fighting-spirit", "Fighting Spirit", "CLASS",
                        "3", "longRest", null, null,
                        "BONUS_ACTION", "Gain temporary HP and advantage on weapon attacks until the end of this turn.", "heart", null),

                def("subclass:rune-knight-giants-might", "Giant's Might", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "BONUS_ACTION", "Grow to Large size, advantage on STR checks/saves, bonus damage once per turn for 1 minute.", "flame", null),

                def("subclass:echo-knight-unleash-incarnation", "Unleash Incarnation", "CLASS",
                        "constitutionModifier", "longRest", null, null,
                        "FREE", "When taking the Attack action, make one additional melee attack from your echo's position.", "zap", null),

                def("subclass:cavalier-unwavering-mark", "Unwavering Mark", "CLASS",
                        "strengthModifier", "longRest", null, null,
                        "BONUS_ACTION", "Mark a creature you hit. While marked and within 5ft, it has disadvantage on attacks against others. You get a bonus-action special attack against marked targets.", "shield-check", null),

                def("subclass:cavalier-warding-maneuver", "Warding Maneuver", "CLASS",
                        "constitutionModifier", "longRest", null, null,
                        "REACTION", "Roll 1d8 and add it to a creature's AC against an attack. If it still hits, the creature gains resistance to the attack's damage.", "shield-check", null),

                // ── Other class subclass pools ──
                // -- Barbarian --
                def("subclass:wild-magic-magic-awareness", "Magic Awareness", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "ACTION", "Detect the presence of magic within 60 feet for 10 minutes.", "sparkles", null),

                def("subclass:wild-magic-bolstering-magic", "Bolstering Magic", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "ACTION", "Grant a creature a d3 bonus to attack rolls or ability checks, or restore a 1st-level spell slot.", "sparkles", null),

                def("subclass:beast-infectious-fury", "Infectious Fury", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "REACTION", "Force a creature hit by your Bestial Soul to make a WIS save or take 2d12 psychic damage and attack a target of your choice.", "flame", null),

                def("subclass:zealot-zealous-presence", "Zealous Presence", "CLASS",
                        "1", "longRest", null, null,
                        "BONUS_ACTION", "Unleash a battle cry. Allies within 60 feet gain advantage on attack rolls and saving throws until the start of your next turn.", "crown", null),

                // -- Bard --
                def("subclass:glamour-mantle-of-majesty", "Mantle of Majesty", "CLASS",
                        "1", "longRest", null, null,
                        "BONUS_ACTION", "Cast Command as a bonus action every turn for 1 minute without expending a spell slot. Creatures auto-succeed if already charmed.", "crown", null),

                // -- Cleric --
                def("subclass:light-warding-flare", "Warding Flare", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "REACTION", "Impose disadvantage on an attacker within 30 feet that you can see.", "sun", null),

                def("subclass:tempest-wrath-of-the-storm", "Wrath of the Storm", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "REACTION", "When hit by a creature within 5 feet, deal 2d8 lightning or thunder damage (DEX save for half).", "zap", null),

                def("subclass:war-war-priest", "War Priest", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "BONUS_ACTION", "Make one weapon attack as a bonus action.", "swords", null),

                def("subclass:grave-eyes-of-the-grave", "Eyes of the Grave", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "ACTION", "Detect undead within 60 feet that aren't behind total cover or warded against divination.", "sun", null),

                def("subclass:grave-sentinel-at-deaths-door", "Sentinel at Death's Door", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "REACTION", "Turn a critical hit against a creature within 30 feet into a normal hit.", "shield-check", null),

                def("subclass:peace-emboldening-bond", "Emboldening Bond", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "ACTION", "Forge a bond among up to PB creatures for 10 minutes. Bonded creatures can roll a d4 and add it to an attack roll, ability check, or saving throw once per turn.", "heart", null),

                def("subclass:twilight-eyes-of-night", "Eyes of Night", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "ACTION", "Grant darkvision out to 300 feet to up to PB willing creatures for 1 hour.", "moon", null),

                def("subclass:twilight-steps-of-night", "Steps of Night", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "BONUS_ACTION", "Gain a flying speed equal to your walking speed for 1 minute. Usable only in dim light or darkness.", "moon", null),

                def("subclass:forge-blessing-of-the-forge", "Blessing of the Forge", "CLASS",
                        "1", "longRest", null, null,
                        "ACTION", "Touch one nonmagical suit of armor or weapon. Until the end of your next long rest, it becomes a +1 magic item.", "flame", null),

                def("subclass:order-embodiment-of-the-law", "Embodiment of the Law", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "FREE", "Cast an enchantment spell of 1st level or higher with a casting time of 1 action as a bonus action.", "shield-check", null),

                // -- Druid --
                def("subclass:land-natural-recovery", "Natural Recovery", "CLASS",
                        "1", "longRest", null, null,
                        "FREE", "During a short rest, recover expended spell slots of combined level up to half your druid level (rounded up).", "paw-print", null),

                def("subclass:dreams-balm-of-the-summer-court", "Balm of the Summer Court", "CLASS",
                        "druidLevel", "longRest", null, null,
                        "BONUS_ACTION", "Heal a creature you can see within 120 feet. Spend dice (d6s) from this pool. Each die heals 1 HP + the die's result.", "heart", null),

                def("subclass:dreams-hidden-paths", "Hidden Paths", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "BONUS_ACTION", "Teleport up to 60 feet to an unoccupied space you can see.", "wind", null),

                def("subclass:shepherd-spirit-totem", "Spirit Totem", "CLASS",
                        "1", "shortRest", null, null,
                        "BONUS_ACTION", "Summon a spirit totem that grants an aura for 1 minute. Choose Bear (temp HP), Hawk (advantage on attacks), or Unicorn (healing on spell cast).", "paw-print", null),

                def("subclass:stars-guiding-bolts", "Guiding Bolts", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "FREE", "Cast Guiding Bolt without expending a spell slot. The number of free casts equals your proficiency bonus.", "sparkles", null),

                def("subclass:stars-cosmic-omen", "Cosmic Omen", "CLASS",
                        "1", "longRest", null, null,
                        "REACTION", "Use Weal (add d6 to ally's roll) or Woe (subtract d6 from enemy's roll). Which omen is available depends on the current star configuration rolled at the end of each long rest.", "sparkles", null),

                def("subclass:spores-fungal-infestation", "Fungal Infestation", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "REACTION", "When a Small or Medium beast or humanoid dies within 10 feet, animate it with 1 HP. It acts immediately after your turn for 1 hour or until your Symbiotic Entity ends.", "zap", null),

                def("subclass:wildfire-cauterizing-flames", "Cauterizing Flames", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "REACTION", "When a Small or larger creature dies within 30 feet of you or your wildfire spirit, wreathe it in spectral flames. A creature you choose within 30 feet regains 2d10 HP or takes 2d10 fire damage.", "flame", null),

                // -- Monk --
                def("subclass:open-hand-wholeness-of-body", "Wholeness of Body", "CLASS",
                        "1", "longRest", null, null,
                        "ACTION", "Regain hit points equal to 3x your monk level.", "heart", null),

                def("subclass:ascendant-dragon-breath-of-the-dragon", "Breath of the Dragon", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "FREE", "Replace one attack with a breath weapon in a 20-ft cone or 30-ft line (5-ft wide), dealing damage of your draconic type (2 rolls of your Martial Arts die). Free uses = PB; additional uses cost 2 ki each.", "wind", null),

                def("subclass:ascendant-dragon-wings-unfurled", "Wings Unfurled", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "BONUS_ACTION", "Gain a flying speed equal to your walking speed until the end of your turn.", "wind", null),

                // -- Ranger --
                def("subclass:horizon-walker-ethereal-step", "Ethereal Step", "CLASS",
                        "1", "shortRest", null, null,
                        "BONUS_ACTION", "Cast Etherealness as a bonus action without expending a spell slot. Lasts until the start of your next turn.", "wind", null),

                def("subclass:swarmkeeper-writhing-tide", "Writhing Tide", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "BONUS_ACTION", "Gain a flying speed of 10 feet and hover for 1 minute.", "wind", null),

                def("subclass:monster-slayer-hunters-sense", "Hunter's Sense", "CLASS",
                        "wisdomModifier", "longRest", null, null,
                        "ACTION", "Learn a creature's damage immunities, resistances, and vulnerabilities. The target must be within 60 feet and you must see it.", "sun", null),

                // -- Rogue --
                def("subclass:soulknife-psionic-power-dice", "Psionic Power Dice", "CLASS",
                        "2*proficiencyBonus", "longRest", "1", null,
                        "FREE", "Psionic Energy dice (d6) used for Psionic talents. Regain all on a long rest, or 1 die on a short rest.", "sparkles", null),

                def("subclass:phantom-wails-from-the-grave", "Wails from the Grave", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "FREE", "When you deal Sneak Attack damage, deal half your Sneak Attack dice (rounded up) as necrotic damage to a second creature within 30 feet.", "music", null),

                def("subclass:phantom-tokens-of-the-departed", "Tokens of the Departed", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "FREE", "When a creature dies within 30 feet, capture a soul trinket (PB max). Destroy a trinket to ask the spirit one question, gain advantage on death saves, or gain advantage on a CON save.", "zap", null),

                // -- Sorcerer --
                def("subclass:divine-soul-favored-by-gods", "Favored by the Gods", "CLASS",
                        "1", "shortRest", null, null,
                        "REACTION", "Add 2d4 to a failed saving throw or missed attack roll, potentially turning it into a success.", "sun", null),

                def("subclass:wild-magic-tides-of-chaos", "Tides of Chaos", "CLASS",
                        "1", "longRest", null, null,
                        "FREE", "Gain advantage on one attack roll, ability check, or saving throw. Refreshes when the DM forces a Wild Magic Surge.", "sparkles", null),

                def("subclass:shadow-strength-of-the-grave", "Strength of the Grave", "CLASS",
                        "1", "longRest", null, null,
                        "REACTION", "When reduced to 0 HP, make a CHA save (DC 5 + damage taken). On success, drop to 1 HP instead.", "moon", null),

                def("subclass:clockwork-soul-restore-balance", "Restore Balance", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "REACTION", "Negate advantage or disadvantage on a d20 roll made by a creature you can see within 60 feet.", "shield-check", null),

                // -- Warlock --
                def("subclass:archfey-fey-presence", "Fey Presence", "CLASS",
                        "1", "shortRest", null, null,
                        "ACTION", "Force creatures in a 10-ft cube originating from you to make a WIS save or become charmed or frightened until the end of your next turn.", "sparkles", null),

                def("subclass:archfey-misty-escape", "Misty Escape", "CLASS",
                        "1", "shortRest", null, null,
                        "REACTION", "When you take damage, turn invisible and teleport up to 60 feet. Invisibility lasts until the start of your next turn or until you attack/cast.", "wind", null),

                def("subclass:hexblade-curse", "Hexblade's Curse", "CLASS",
                        "1", "shortRest", null, null,
                        "BONUS_ACTION", "Curse a creature for 1 minute. You gain bonus damage equal to proficiency bonus, crit on 19-20, and the cursed target heals you when it dies.", "zap", null),

                def("subclass:fiend-dark-ones-own-luck", "Dark One's Own Luck", "CLASS",
                        "1", "shortRest", null, null,
                        "REACTION", "Add 1d10 to an ability check or saving throw you make.", "flame", null),

                def("subclass:great-old-one-entropic-ward", "Entropic Ward", "CLASS",
                        "1", "shortRest", null, null,
                        "REACTION", "Impose disadvantage on an attack roll against you. If it misses, your next attack against that creature has advantage.", "moon", null),

                def("subclass:celestial-healing-light", "Healing Light", "CLASS",
                        "1+warlockLevel", "longRest", null, null,
                        "BONUS_ACTION", "Heal a creature you can see within 60 feet. Spend up to CHA mod dice (d6s) from this pool per use.", "heart", null),

                def("subclass:fathomless-tentacle-of-the-deeps", "Tentacle of the Deeps", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "BONUS_ACTION", "Summon a spectral tentacle at a point within 60 feet for 1 minute. It deals 1d8 cold damage on a hit and reduces speed by 10 feet.", "wind", null),

                def("subclass:undead-form-of-dread", "Form of Dread", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "BONUS_ACTION", "Transform for 1 minute. Gain 1d10+warlockLevel temp HP, immunity to frightened, and once per turn your attack can force a WIS save vs frightened.", "zap", null),

                def("subclass:genie-bottled-respite", "Bottled Respite", "CLASS",
                        "1", "longRest", null, null,
                        "ACTION", "Enter your Genie's Vessel for up to PB x 2 hours. You can hear as normal outside. Party members can carry the vessel.", "sparkles", null),

                def("subclass:genie-elemental-gift", "Elemental Gift", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "BONUS_ACTION", "Gain a flying speed of 30 feet for 10 minutes.", "wind", null),

                // -- Wizard --
                def("subclass:divination-portent", "Portent", "CLASS",
                        "2", "longRest", null, null,
                        "FREE", "Roll 2d20 at the end of each long rest. Replace any attack roll, saving throw, or ability check with one of these rolls before the outcome is determined.", "sun", null),

                def("subclass:bladesinger-bladesong", "Bladesong", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "BONUS_ACTION", "Enter bladesong for 1 minute. Gain +INT mod to AC, +10ft walking speed, advantage on Acrobatics, and +INT mod to CON saves to maintain concentration.", "swords", null),

                def("subclass:chronurgy-chronal-shift", "Chronal Shift", "CLASS",
                        "2", "longRest", null, null,
                        "REACTION", "Force a creature you can see within 30 feet to reroll a d20 after seeing the result but before the outcome is determined.", "zap", null),

                def("subclass:chronurgy-momentary-stasis", "Momentary Stasis", "CLASS",
                        "intelligenceModifier", "longRest", null, null,
                        "ACTION", "Force a Large or smaller creature within 60 feet to make a CON save or be incapacitated until the end of your next turn.", "zap", null),

                def("subclass:graviturgy-violent-attraction", "Violent Attraction", "CLASS",
                        "intelligenceModifier", "longRest", null, null,
                        "REACTION", "When a creature within 60 feet hits with a weapon attack, increase the velocity: the attack deals an additional 1d10 force damage.", "zap", null),

                def("subclass:war-magic-power-surge", "Power Surge", "CLASS",
                        "intelligenceModifier", "longRest", null, null,
                        "FREE", "Deal extra force damage equal to half your wizard level when you damage a creature with a wizard spell. Resets to 1 on long rest (increasing when you use Dispel Magic or Counterspell).", "zap", null),

                def("subclass:scribes-manifest-mind", "Manifest Mind", "CLASS",
                        "proficiencyBonus", "longRest", null, null,
                        "ACTION", "Conjure a spectral mind within 60 feet for 10 minutes. You can see and cast spells through it as if you were in its space.", "sparkles", null),

                // ── Monster abilities ──
                def("monster:legendary-actions", "Legendary Actions", "MONSTER",
                        "3", "turn", null, null,
                        "FREE", "Can take up to 3 legendary actions per round, one per turn after another creature's turn.", "crown", null),

                def("monster:legendary-resistance", "Legendary Resistance", "MONSTER",
                        "3", "longRest", null, null,
                        "REACTION", "If the monster fails a saving throw, it can choose to succeed instead.", "shield-check", null),

                def("monster:breath-weapon-5-6", "Breath Weapon (Recharge 5–6)", "MONSTER",
                        "1", "shortRest", null, "1d6>=5",
                        "ACTION", "Breath weapon recharges on a roll of 5 or 6 on a d6 at the start of the monster's turn.", "wind", null),

                def("monster:breath-weapon-6", "Breath Weapon (Recharge 6)", "MONSTER",
                        "1", "shortRest", null, "1d6>=6",
                        "ACTION", "Breath weapon recharges on a roll of 6 on a d6 at the start of the monster's turn.", "wind", null)
        );

        poolDefRepo.saveAll(defs);
        log.info("Seeded {} resource pool definitions", defs.size());
    }

    // ── Class feature pools (junction table) ──────────────────────

    private void seedClassFeaturePools() {
        Map<String, CharacterClass> classByName = buildClassMap();

        List<ClassFeaturePool> mappings = new java.util.ArrayList<>();

        // Monk: Ki at level 2
        addMapping(mappings, classByName, "Monk", null, 2, "class:monk-ki");

        // Sorcerer: Sorcery Points at level 2
        addMapping(mappings, classByName, "Sorcerer", null, 2, "class:sorcerer-sorcery-points");

        // Cleric: Channel Divinity at level 2
        addMapping(mappings, classByName, "Cleric", null, 2, "class:cleric-channel-divinity");

        // Paladin: Channel Divinity at level 3
        addMapping(mappings, classByName, "Paladin", null, 3, "class:paladin-channel-divinity");

        // Druid: Wild Shape at level 2
        addMapping(mappings, classByName, "Druid", null, 2, "class:druid-wild-shape");

        // Fighter: Second Wind at level 1
        addMapping(mappings, classByName, "Fighter", null, 1, "class:fighter-second-wind");

        // Fighter: Action Surge at level 2
        addMapping(mappings, classByName, "Fighter", null, 2, "class:fighter-action-surge");

        // Bard: Bardic Inspiration at level 1
        addMapping(mappings, classByName, "Bard", null, 1, "class:bard-bardic-inspiration");

        // Barbarian: Rage at level 1
        addMapping(mappings, classByName, "Barbarian", null, 1, "class:barbarian-rage");

        // ── Subclass feature pools ──

        // -- Fighter --
        addMapping(mappings, classByName, "Fighter", "Battle Master", 3, "subclass:battle-master-superiority-dice");
        addMapping(mappings, classByName, "Fighter", "Arcane Archer", 3, "subclass:arcane-archer-arcane-shot");
        addMapping(mappings, classByName, "Fighter", "Psi Warrior", 3, "subclass:psi-warrior-psionic-energy");
        addMapping(mappings, classByName, "Fighter", "Samurai", 3, "subclass:samurai-fighting-spirit");
        addMapping(mappings, classByName, "Fighter", "Rune Knight", 3, "subclass:rune-knight-giants-might");
        addMapping(mappings, classByName, "Fighter", "Echo Knight", 3, "subclass:echo-knight-unleash-incarnation");
        addMapping(mappings, classByName, "Fighter", "Cavalier", 3, "subclass:cavalier-unwavering-mark");
        addMapping(mappings, classByName, "Fighter", "Cavalier", 7, "subclass:cavalier-warding-maneuver");

        // -- Barbarian --
        addMapping(mappings, classByName, "Barbarian", "Path of Wild Magic", 3, "subclass:wild-magic-magic-awareness");
        addMapping(mappings, classByName, "Barbarian", "Path of Wild Magic", 6, "subclass:wild-magic-bolstering-magic");
        addMapping(mappings, classByName, "Barbarian", "Path of the Beast", 10, "subclass:beast-infectious-fury");
        addMapping(mappings, classByName, "Barbarian", "Path of the Zealot", 10, "subclass:zealot-zealous-presence");

        // -- Bard --
        addMapping(mappings, classByName, "Bard", "College of Glamour", 6, "subclass:glamour-mantle-of-majesty");

        // -- Cleric --
        addMapping(mappings, classByName, "Cleric", "Light Domain", 1, "subclass:light-warding-flare");
        addMapping(mappings, classByName, "Cleric", "Tempest Domain", 1, "subclass:tempest-wrath-of-the-storm");
        addMapping(mappings, classByName, "Cleric", "War Domain", 1, "subclass:war-war-priest");
        addMapping(mappings, classByName, "Cleric", "Grave Domain", 1, "subclass:grave-eyes-of-the-grave");
        addMapping(mappings, classByName, "Cleric", "Grave Domain", 6, "subclass:grave-sentinel-at-deaths-door");
        addMapping(mappings, classByName, "Cleric", "Peace Domain", 1, "subclass:peace-emboldening-bond");
        addMapping(mappings, classByName, "Cleric", "Twilight Domain", 1, "subclass:twilight-eyes-of-night");
        addMapping(mappings, classByName, "Cleric", "Twilight Domain", 6, "subclass:twilight-steps-of-night");
        addMapping(mappings, classByName, "Cleric", "Forge Domain", 1, "subclass:forge-blessing-of-the-forge");
        addMapping(mappings, classByName, "Cleric", "Order Domain", 6, "subclass:order-embodiment-of-the-law");

        // -- Druid --
        addMapping(mappings, classByName, "Druid", "Circle of the Land", 2, "subclass:land-natural-recovery");
        addMapping(mappings, classByName, "Druid", "Circle of Dreams", 2, "subclass:dreams-balm-of-the-summer-court");
        addMapping(mappings, classByName, "Druid", "Circle of Dreams", 10, "subclass:dreams-hidden-paths");
        addMapping(mappings, classByName, "Druid", "Circle of the Shepherd", 2, "subclass:shepherd-spirit-totem");
        addMapping(mappings, classByName, "Druid", "Circle of Stars", 2, "subclass:stars-guiding-bolts");
        addMapping(mappings, classByName, "Druid", "Circle of Stars", 6, "subclass:stars-cosmic-omen");
        addMapping(mappings, classByName, "Druid", "Circle of Spores", 6, "subclass:spores-fungal-infestation");
        addMapping(mappings, classByName, "Druid", "Circle of Wildfire", 10, "subclass:wildfire-cauterizing-flames");

        // -- Monk --
        addMapping(mappings, classByName, "Monk", "Way of the Open Hand", 6, "subclass:open-hand-wholeness-of-body");
        addMapping(mappings, classByName, "Monk", "Way of the Ascendant Dragon", 3, "subclass:ascendant-dragon-breath-of-the-dragon");
        addMapping(mappings, classByName, "Monk", "Way of the Ascendant Dragon", 6, "subclass:ascendant-dragon-wings-unfurled");

        // -- Ranger --
        addMapping(mappings, classByName, "Ranger", "Horizon Walker", 7, "subclass:horizon-walker-ethereal-step");
        addMapping(mappings, classByName, "Ranger", "Swarmkeeper", 7, "subclass:swarmkeeper-writhing-tide");
        addMapping(mappings, classByName, "Ranger", "Monster Slayer", 3, "subclass:monster-slayer-hunters-sense");

        // -- Rogue --
        addMapping(mappings, classByName, "Rogue", "Soulknife", 3, "subclass:soulknife-psionic-power-dice");
        addMapping(mappings, classByName, "Rogue", "Phantom", 3, "subclass:phantom-wails-from-the-grave");
        addMapping(mappings, classByName, "Rogue", "Phantom", 9, "subclass:phantom-tokens-of-the-departed");

        // -- Sorcerer --
        addMapping(mappings, classByName, "Sorcerer", "Divine Soul", 1, "subclass:divine-soul-favored-by-gods");
        addMapping(mappings, classByName, "Sorcerer", "Wild Magic", 1, "subclass:wild-magic-tides-of-chaos");
        addMapping(mappings, classByName, "Sorcerer", "Shadow Magic", 1, "subclass:shadow-strength-of-the-grave");
        addMapping(mappings, classByName, "Sorcerer", "Clockwork Soul", 1, "subclass:clockwork-soul-restore-balance");

        // -- Warlock --
        addMapping(mappings, classByName, "Warlock", "The Archfey", 1, "subclass:archfey-fey-presence");
        addMapping(mappings, classByName, "Warlock", "The Archfey", 6, "subclass:archfey-misty-escape");
        addMapping(mappings, classByName, "Warlock", "The Hexblade", 1, "subclass:hexblade-curse");
        addMapping(mappings, classByName, "Warlock", "The Fiend", 6, "subclass:fiend-dark-ones-own-luck");
        addMapping(mappings, classByName, "Warlock", "The Great Old One", 6, "subclass:great-old-one-entropic-ward");
        addMapping(mappings, classByName, "Warlock", "The Celestial", 1, "subclass:celestial-healing-light");
        addMapping(mappings, classByName, "Warlock", "The Fathomless", 1, "subclass:fathomless-tentacle-of-the-deeps");
        addMapping(mappings, classByName, "Warlock", "The Undead", 1, "subclass:undead-form-of-dread");
        addMapping(mappings, classByName, "Warlock", "The Genie", 1, "subclass:genie-bottled-respite");
        addMapping(mappings, classByName, "Warlock", "The Genie", 6, "subclass:genie-elemental-gift");

        // -- Wizard --
        addMapping(mappings, classByName, "Wizard", "School of Divination", 2, "subclass:divination-portent");
        addMapping(mappings, classByName, "Wizard", "Bladesinging", 2, "subclass:bladesinger-bladesong");
        addMapping(mappings, classByName, "Wizard", "Chronurgy Magic", 2, "subclass:chronurgy-chronal-shift");
        addMapping(mappings, classByName, "Wizard", "Chronurgy Magic", 6, "subclass:chronurgy-momentary-stasis");
        addMapping(mappings, classByName, "Wizard", "Graviturgy Magic", 10, "subclass:graviturgy-violent-attraction");
        addMapping(mappings, classByName, "Wizard", "War Magic", 6, "subclass:war-magic-power-surge");
        addMapping(mappings, classByName, "Wizard", "Order of Scribes", 6, "subclass:scribes-manifest-mind");

        classFeaturePoolRepo.saveAll(mappings);
        log.info("Seeded {} class feature pool mappings", mappings.size());
    }

    // ── Monster pool triggers ─────────────────────────────────────

    private void seedMonsterTriggers() {
        List<MonsterPoolTrigger> triggers = new java.util.ArrayList<>();

        // Legendary actions: any monster with legendary actions
        triggers.add(MonsterPoolTrigger.builder()
                .poolDefinition(poolDefRepo.findById("monster:legendary-actions").orElse(null))
                .triggerCondition("{\"hasField\": \"legendary\"}")
                .priority(1)
                .build());

        // Legendary resistance: any monster with legendary actions (same trigger field)
        triggers.add(MonsterPoolTrigger.builder()
                .poolDefinition(poolDefRepo.findById("monster:legendary-resistance").orElse(null))
                .triggerCondition("{\"hasField\": \"legendary\"}")
                .priority(2)
                .build());

        // Breath weapon (any recharge): triggers for any monster with a recharge ability
        // Default to 5-6; 6-only creatures get the stricter one
        triggers.add(MonsterPoolTrigger.builder()
                .poolDefinition(poolDefRepo.findById("monster:breath-weapon-5-6").orElse(null))
                .triggerCondition("{\"hasRecharge\": true}")
                .priority(3)
                .build());

        monsterTriggerRepo.saveAll(triggers);
        log.info("Seeded {} monster pool triggers", triggers.size());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private ResourcePoolDefinition def(String poolId, String displayName, String sourceType,
                                        String maxUsesFormula, String resetOn, String resetAmount,
                                        String resetCheck, String spendActionType,
                                        String description, String icon, Map<String, Object> metadata) {
        String metadataJson = null;
        if (metadata != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (Exception ignored) {}
        }
        return ResourcePoolDefinition.builder()
                .poolId(poolId)
                .displayName(displayName)
                .sourceType(sourceType)
                .maxUsesFormula(maxUsesFormula)
                .resetOn(resetOn)
                .resetAmount(resetAmount)
                .resetCheck(resetCheck)
                .spendActionType(spendActionType)
                .description(description)
                .icon(icon)
                .metadata(metadataJson)
                .build();
    }

    private Map<String, CharacterClass> buildClassMap() {
        Map<String, CharacterClass> map = new LinkedHashMap<>();
        for (CharacterClass cc : classRepo.findAll()) {
            map.put(cc.getName(), cc);
        }
        return map;
    }

    private void addMapping(List<ClassFeaturePool> mappings, Map<String, CharacterClass> classMap,
                            String className, String subclassName, int minLevel, String poolId) {
        CharacterClass cc = classMap.get(className);
        if (cc == null) {
            log.warn("Class '{}' not found in database, skipping pool mapping for '{}'", className, poolId);
            return;
        }
        Subclass sc = null;
        if (subclassName != null) {
            sc = subclassRepo.findByName(subclassName);
            if (sc == null) {
                log.warn("Subclass '{}' not found in database, skipping pool mapping for '{}'", subclassName, poolId);
                return;
            }
        }
        ResourcePoolDefinition def = poolDefRepo.findById(poolId).orElse(null);
        if (def == null) {
            log.warn("Pool definition '{}' not found, skipping mapping", poolId);
            return;
        }

        mappings.add(ClassFeaturePool.builder()
                .characterClass(cc)
                .subclass(sc)
                .minLevel(minLevel)
                .poolDefinition(def)
                .build());
    }
}
