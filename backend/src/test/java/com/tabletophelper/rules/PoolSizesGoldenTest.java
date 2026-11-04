package com.tabletophelper.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.seeder.ResourcePoolSeeder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The maximum uses of every resource pool definition (D169, D172), typed out here from the 2014-era books,
 * independently of the definitions: the PHB, XGE and TCE, and for the few subclasses printed elsewhere, EGW, FTD and
 * VRGR. Each pool is checked at every class level from 1 to 20, absent below the level that grants it, with every
 * ability modifier at -1, 0 and +3 (scores of 8, 10 and 16) and the proficiency bonus of the PHB's table (p.15).
 * <p>
 * The definitions and their class mappings are the ones {@link ResourcePoolSeeder} writes, read through mocked
 * repositories, and each size is the one {@link ResourcePoolService#reconciledPools} gives a single-class character,
 * so the test reads the formulas and the context the app uses. The page given for each pool is the feature's
 * {@code page} in the bundled 5etools class data, whose text was read for it; for the PHB's own class features
 * (not its subclasses') that field gives the page the class's chapter begins on.
 * <p>
 * {@link #KNOWN_WRONG} lists the cases today's definitions get wrong, with the value they give today. Each is
 * asserted to be still wrong, with that value, so the suite passes and a case that changes fails until the list
 * does; R7 workstream D corrects the definitions and empties it.
 */
class PoolSizesGoldenTest {

    // A pool with no limit: Rage at Barbarian 20 (Primal Champion's table row) and Wild Shape at Druid 20 (Archdruid)
    private static final int UNLIMITED = Integer.MAX_VALUE;

    private static final int[] MODIFIERS = {-1, 0, 3};

    // PHB p.15, Character Advancement: the proficiency bonus at levels 1 to 20
    private static final int[] PROFICIENCY_BONUS = {2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6};

    private static int pb(int level) {
        return PROFICIENCY_BONUS[level - 1];
    }

    /** A pool's maximum uses at a class level, with every ability modifier the same. */
    @FunctionalInterface
    private interface Size {
        int at(int level, int modifier);
    }

    private static Size flat(int uses) {
        return (level, modifier) -> uses;
    }

    private static final Size PB = (level, modifier) -> pb(level);
    private static final Size TWICE_PB = (level, modifier) -> 2 * pb(level);
    private static final Size LEVEL = (level, modifier) -> level;
    // "a number of times equal to your <ability> modifier (a minimum of once)"
    private static final Size MODIFIER_AT_LEAST_ONCE = (level, modifier) -> Math.max(1, modifier);

    /** What the book gives a class's or subclass's pool, from the level that grants it. */
    private record Book(String poolId, String className, String subclassName, int fromLevel, Size size, String source) {
        @Override
        public String toString() {
            return poolId;
        }
    }

    private static Book base(String poolId, String className, int fromLevel, Size size, String source) {
        return new Book(poolId, className, null, fromLevel, size, source);
    }

    private static Book sub(String poolId, String className, String subclassName, int fromLevel, Size size,
                            String source) {
        return new Book(poolId, className, subclassName, fromLevel, size, source);
    }

    // PHB p.46, the Barbarian table's Rages column, levels 1 to 20
    private static final int[] RAGES = {2, 2, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 6, 6, 6, UNLIMITED};

    private static final List<Book> CLASS_POOLS = List.of(
            // ── Classes ──
            base("class:monk-ki", "Monk", 2, LEVEL,
                    "PHB p.76, Ki: the Ki Points column of the Monk table, equal to the monk level from 2nd"),
            base("class:sorcerer-sorcery-points", "Sorcerer", 2, LEVEL,
                    "PHB p.99, Font of Magic: the Sorcery Points column of the Sorcerer table, equal to the sorcerer level from 2nd"),
            base("class:cleric-channel-divinity", "Cleric", 2, (level, modifier) -> level >= 18 ? 3 : level >= 6 ? 2 : 1,
                    "PHB p.56, Channel Divinity: once between rests from 2nd, twice from 6th, three times from 18th"),
            base("class:paladin-channel-divinity", "Paladin", 3, flat(1),
                    "PHB p.82, Channel Divinity: once, then a short or long rest; the paladin never gains more uses"),
            base("class:druid-wild-shape", "Druid", 2, (level, modifier) -> level == 20 ? UNLIMITED : 2,
                    "PHB p.64, Wild Shape: twice between rests from 2nd; Archdruid (20th): an unlimited number of times"),
            base("class:fighter-second-wind", "Fighter", 1, flat(1),
                    "PHB p.72, Second Wind: once, then a short or long rest"),
            base("class:fighter-action-surge", "Fighter", 2, (level, modifier) -> level >= 17 ? 2 : 1,
                    "PHB p.72, Action Surge: once from 2nd; twice before a rest from 17th"),
            base("class:bard-bardic-inspiration", "Bard", 1, MODIFIER_AT_LEAST_ONCE,
                    "PHB p.51, Bardic Inspiration: a number of times equal to the Charisma modifier (a minimum of once)"),
            base("class:barbarian-rage", "Barbarian", 1, (level, modifier) -> RAGES[level - 1],
                    "PHB p.46, Rage: the Rages column of the Barbarian table, 2/3/4/5/6 at 1st/3rd/6th/12th/17th, unlimited at 20th"),

            // ── Fighter ──
            sub("subclass:battle-master-superiority-dice", "Fighter", "Battle Master", 3,
                    (level, modifier) -> level >= 15 ? 6 : level >= 7 ? 5 : 4,
                    "PHB p.73, Combat Superiority: four superiority dice, another at 7th level and one more at 15th"),
            sub("subclass:arcane-archer-arcane-shot", "Fighter", "Arcane Archer", 3, flat(2),
                    "XGE p.28, Arcane Shot: two uses, regained on a short or long rest"),
            sub("subclass:psi-warrior-psionic-energy", "Fighter", "Psi Warrior", 3, TWICE_PB,
                    "TCE p.42, Psionic Power: Psionic Energy dice equal to twice the proficiency bonus"),
            sub("subclass:samurai-fighting-spirit", "Fighter", "Samurai", 3, flat(3),
                    "XGE p.31, Fighting Spirit: three times, regained on a long rest"),
            sub("subclass:rune-knight-giants-might", "Fighter", "Rune Knight", 3, PB,
                    "TCE p.44, Giant's Might: a number of times equal to the proficiency bonus"),
            sub("subclass:echo-knight-unleash-incarnation", "Fighter", "Echo Knight", 3, MODIFIER_AT_LEAST_ONCE,
                    "EGW p.183, Unleash Incarnation: the Constitution modifier (a minimum of once)"),
            sub("subclass:cavalier-unwavering-mark", "Fighter", "Cavalier", 3, MODIFIER_AT_LEAST_ONCE,
                    "XGE p.30, Unwavering Mark: the special attack, the Strength modifier (minimum of once)"),
            sub("subclass:cavalier-warding-maneuver", "Fighter", "Cavalier", 7, MODIFIER_AT_LEAST_ONCE,
                    "XGE p.30, Warding Maneuver: the Constitution modifier (minimum of once)"),

            // ── Barbarian ──
            sub("subclass:wild-magic-magic-awareness", "Barbarian", "Path of Wild Magic", 3, PB,
                    "TCE p.25, Magic Awareness: a number of times equal to the proficiency bonus"),
            sub("subclass:wild-magic-bolstering-magic", "Barbarian", "Path of Wild Magic", 6, PB,
                    "TCE p.25, Bolstering Magic: a number of times equal to the proficiency bonus"),
            sub("subclass:beast-infectious-fury", "Barbarian", "Path of the Beast", 10, PB,
                    "TCE p.24, Infectious Fury: a number of times equal to the proficiency bonus"),
            sub("subclass:zealot-zealous-presence", "Barbarian", "Path of the Zealot", 10, flat(1),
                    "XGE p.11, Zealous Presence: once, then a long rest"),

            // ── Bard ──
            sub("subclass:glamour-mantle-of-majesty", "Bard", "College of Glamour", 6, flat(1),
                    "XGE p.14, Mantle of Majesty: once, then a long rest"),

            // ── Cleric ──
            sub("subclass:light-warding-flare", "Cleric", "Light Domain", 1, MODIFIER_AT_LEAST_ONCE,
                    "PHB p.60, Warding Flare: the Wisdom modifier (a minimum of once)"),
            sub("subclass:tempest-wrath-of-the-storm", "Cleric", "Tempest Domain", 1, MODIFIER_AT_LEAST_ONCE,
                    "PHB p.62, Wrath of the Storm: the Wisdom modifier (a minimum of once)"),
            sub("subclass:war-war-priest", "Cleric", "War Domain", 1, MODIFIER_AT_LEAST_ONCE,
                    "PHB p.63, War Priest: the Wisdom modifier (a minimum of once)"),
            sub("subclass:grave-eyes-of-the-grave", "Cleric", "Grave Domain", 1, MODIFIER_AT_LEAST_ONCE,
                    "XGE p.19, Eyes of the Grave: the Wisdom modifier (minimum of once)"),
            sub("subclass:grave-sentinel-at-deaths-door", "Cleric", "Grave Domain", 6, MODIFIER_AT_LEAST_ONCE,
                    "XGE p.19, Sentinel at Death's Door: the Wisdom modifier (minimum of once)"),
            sub("subclass:peace-emboldening-bond", "Cleric", "Peace Domain", 1, PB,
                    "TCE p.32, Emboldening Bond: a number of times equal to the proficiency bonus"),
            sub("subclass:twilight-eyes-of-night", "Cleric", "Twilight Domain", 1, flat(1),
                    "TCE p.34, Eyes of Night: sharing the darkvision once, then a long rest (or a spell slot to share it again)"),
            sub("subclass:twilight-steps-of-night", "Cleric", "Twilight Domain", 6, PB,
                    "TCE p.34, Steps of Night: a number of times equal to the proficiency bonus"),
            sub("subclass:forge-blessing-of-the-forge", "Cleric", "Forge Domain", 1, flat(1),
                    "XGE p.18, Blessing of the Forge: once, then a long rest"),
            sub("subclass:order-embodiment-of-the-law", "Cleric", "Order Domain", 6, MODIFIER_AT_LEAST_ONCE,
                    "TCE p.31, Embodiment of the Law: the Wisdom modifier (minimum of once)"),

            // ── Druid ──
            sub("subclass:land-natural-recovery", "Druid", "Circle of the Land", 2, flat(1),
                    "PHB p.68, Natural Recovery: once, then a long rest"),
            sub("subclass:dreams-balm-of-the-summer-court", "Druid", "Circle of Dreams", 2, LEVEL,
                    "XGE p.22, Balm of the Summer Court: a number of d6s equal to the druid level"),
            sub("subclass:dreams-hidden-paths", "Druid", "Circle of Dreams", 10, MODIFIER_AT_LEAST_ONCE,
                    "XGE p.22, Hidden Paths: the Wisdom modifier (minimum of once)"),
            sub("subclass:shepherd-spirit-totem", "Druid", "Circle of the Shepherd", 2, flat(1),
                    "XGE p.23, Spirit Totem: once, then a short or long rest"),
            sub("subclass:stars-guiding-bolts", "Druid", "Circle of Stars", 2, PB,
                    "TCE p.38, Star Map: guiding bolt without a slot a number of times equal to the proficiency bonus"),
            sub("subclass:stars-cosmic-omen", "Druid", "Circle of Stars", 6, PB,
                    "TCE p.38, Cosmic Omen: the reaction a number of times equal to the proficiency bonus"),
            sub("subclass:spores-fungal-infestation", "Druid", "Circle of Spores", 6, MODIFIER_AT_LEAST_ONCE,
                    "TCE p.36, Fungal Infestation: the Wisdom modifier (minimum of once)"),
            sub("subclass:wildfire-cauterizing-flames", "Druid", "Circle of Wildfire", 10, PB,
                    "TCE p.39, Cauterizing Flames: a number of times equal to the proficiency bonus"),

            // ── Monk ──
            sub("subclass:open-hand-wholeness-of-body", "Monk", "Way of the Open Hand", 6, flat(1),
                    "PHB p.79, Wholeness of Body: once, then a long rest"),
            sub("subclass:ascendant-dragon-breath-of-the-dragon", "Monk", "Way of the Ascendant Dragon", 3, PB,
                    "FTD p.13, Breath of the Dragon: a number of times equal to the proficiency bonus (then 2 ki a use)"),
            sub("subclass:ascendant-dragon-wings-unfurled", "Monk", "Way of the Ascendant Dragon", 6, PB,
                    "FTD p.13, Wings Unfurled: a number of times equal to the proficiency bonus"),

            // ── Ranger ──
            sub("subclass:horizon-walker-ethereal-step", "Ranger", "Horizon Walker", 7, flat(1),
                    "XGE p.42, Ethereal Step: once, then a short or long rest"),
            sub("subclass:swarmkeeper-writhing-tide", "Ranger", "Swarmkeeper", 7, PB,
                    "TCE p.59, Writhing Tide: a number of times equal to the proficiency bonus"),
            sub("subclass:monster-slayer-hunters-sense", "Ranger", "Monster Slayer", 3, MODIFIER_AT_LEAST_ONCE,
                    "XGE p.43, Hunter's Sense: the Wisdom modifier (minimum of once)"),

            // ── Rogue ──
            sub("subclass:soulknife-psionic-power-dice", "Rogue", "Soulknife", 3, TWICE_PB,
                    "TCE p.63, Psionic Power: Psionic Energy dice equal to twice the proficiency bonus"),
            sub("subclass:phantom-wails-from-the-grave", "Rogue", "Phantom", 3, PB,
                    "TCE p.62, Wails from the Grave: a number of times equal to the proficiency bonus"),
            sub("subclass:phantom-tokens-of-the-departed", "Rogue", "Phantom", 9, PB,
                    "TCE p.62, Tokens of the Departed: at most as many soul trinkets as the proficiency bonus"),

            // ── Sorcerer ──
            sub("subclass:divine-soul-favored-by-gods", "Sorcerer", "Divine Soul", 1, flat(1),
                    "XGE p.50, Favored by the Gods: once, then a short or long rest"),
            sub("subclass:wild-magic-tides-of-chaos", "Sorcerer", "Wild Magic", 1, flat(1),
                    "PHB p.103, Tides of Chaos: once, then a long rest (or a Wild Magic Surge)"),
            sub("subclass:shadow-strength-of-the-grave", "Sorcerer", "Shadow Magic", 1, flat(1),
                    "XGE p.50, Strength of the Grave: once it succeeds, then a long rest"),
            sub("subclass:clockwork-soul-restore-balance", "Sorcerer", "Clockwork Soul", 1, PB,
                    "TCE p.68, Restore Balance: a number of times equal to the proficiency bonus"),

            // ── Warlock ──
            sub("subclass:archfey-fey-presence", "Warlock", "The Archfey", 1, flat(1),
                    "PHB p.108, Fey Presence: once, then a short or long rest"),
            sub("subclass:archfey-misty-escape", "Warlock", "The Archfey", 6, flat(1),
                    "PHB p.108, Misty Escape: once, then a short or long rest"),
            sub("subclass:hexblade-curse", "Warlock", "The Hexblade", 1, flat(1),
                    "XGE p.55, Hexblade's Curse: once, then a short or long rest"),
            sub("subclass:fiend-dark-ones-own-luck", "Warlock", "The Fiend", 6, flat(1),
                    "PHB p.109, Dark One's Own Luck: once, then a short or long rest"),
            sub("subclass:great-old-one-entropic-ward", "Warlock", "The Great Old One", 6, flat(1),
                    "PHB p.109, Entropic Ward: once, then a short or long rest"),
            sub("subclass:celestial-healing-light", "Warlock", "The Celestial", 1, (level, modifier) -> 1 + level,
                    "XGE p.54, Healing Light: a pool of d6s equal to 1 + the warlock level"),
            sub("subclass:fathomless-tentacle-of-the-deeps", "Warlock", "The Fathomless", 1, PB,
                    "TCE p.72, Tentacle of the Deeps: a number of times equal to the proficiency bonus"),
            sub("subclass:undead-form-of-dread", "Warlock", "The Undead", 1, PB,
                    "VRGR p.30, Form of Dread: a number of times equal to the proficiency bonus"),
            sub("subclass:genie-bottled-respite", "Warlock", "The Genie", 1, flat(1),
                    "TCE p.73, Bottled Respite: once, then a long rest"),
            sub("subclass:genie-elemental-gift", "Warlock", "The Genie", 6, PB,
                    "TCE p.73, Elemental Gift: the flying speed a number of times equal to the proficiency bonus"),

            // ── Wizard ──
            sub("subclass:divination-portent", "Wizard", "School of Divination", 2,
                    (level, modifier) -> level >= 14 ? 3 : 2,
                    "PHB p.116, Portent: two d20s; Greater Portent (14th): three"),
            sub("subclass:bladesinger-bladesong", "Wizard", "Bladesinging", 2, PB,
                    "TCE p.76, Bladesong: a number of times equal to the proficiency bonus (TCE's printing; SCAG's was twice a short rest)"),
            sub("subclass:chronurgy-chronal-shift", "Wizard", "Chronurgy Magic", 2, flat(2),
                    "EGW p.184, Chronal Shift: twice, then a long rest"),
            sub("subclass:chronurgy-momentary-stasis", "Wizard", "Chronurgy Magic", 6, MODIFIER_AT_LEAST_ONCE,
                    "EGW p.184, Momentary Stasis: the Intelligence modifier (a minimum of once)"),
            sub("subclass:graviturgy-violent-attraction", "Wizard", "Graviturgy Magic", 10, MODIFIER_AT_LEAST_ONCE,
                    "EGW p.185, Violent Attraction: the Intelligence modifier (a minimum of once)"),
            sub("subclass:war-magic-power-surge", "Wizard", "War Magic", 6, MODIFIER_AT_LEAST_ONCE,
                    "XGE p.59, Power Surge: at most the Intelligence modifier (minimum of one) power surges"),
            sub("subclass:scribes-manifest-mind", "Wizard", "Order of Scribes", 6, PB,
                    "TCE p.78, Manifest Mind: casting through the mind a number of times a day equal to the proficiency bonus"));

    /**
     * The monster pools' defaults. A monster's own stat block replaces the two legendary counts when it gives one, and
     * each recharge action gets a pool of its own (D165), so these are only the values used without one.
     */
    private static final Map<String, Integer> MONSTER_POOLS = Map.of(
            // MM p.11, Legendary Actions: "can take 3 legendary actions", as most legendary stat blocks print it
            "monster:legendary-actions", 3,
            // MM: Legendary Resistance (3/Day), as most legendary stat blocks print it
            "monster:legendary-resistance", 3,
            // MM p.11, Recharge X-Y: one use, recharged on the d6 at the start of each of the monster's turns
            "monster:breath-weapon-5-6", 1,
            "monster:breath-weapon-6", 1);

    /** Cases today's definitions get wrong: the pool, the class levels and modifiers, and what it gives today. */
    private record Wrong(String poolId, int fromLevel, int toLevel, Set<Integer> modifiers, Size today) {
        boolean covers(String pool, int level, int modifier) {
            return poolId.equals(pool) && level >= fromLevel && level <= toLevel && modifiers.contains(modifier);
        }
    }

    private static final Set<Integer> EVERY_MODIFIER = Set.of(-1, 0, 3);
    // A pool sized by an ability modifier with no minimum: -1 and 0 give -1 and 0 uses today
    private static final Set<Integer> MODIFIERS_BELOW_ONE = Set.of(-1, 0);
    private static final Size RAW_MODIFIER = (level, modifier) -> modifier;

    private static final List<Wrong> KNOWN_WRONG = List.of(
            // A flat 1: no second use at 6th or third at 18th
            new Wrong("class:cleric-channel-divinity", 6, 20, EVERY_MODIFIER, flat(1)),
            // A flat 2: not unlimited at 20th
            new Wrong("class:druid-wild-shape", 20, 20, EVERY_MODIFIER, flat(2)),
            // A flat 1: no second use at 17th
            new Wrong("class:fighter-action-surge", 17, 20, EVERY_MODIFIER, flat(1)),
            // The raw Charisma modifier, with no minimum of once
            new Wrong("class:bard-bardic-inspiration", 1, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            // A flat 2, whatever the level
            new Wrong("class:barbarian-rage", 3, 20, EVERY_MODIFIER, flat(2)),
            // 3+ceil(fighterLevel+1/7), read left to right, gives 6 at 14th: the 6th die comes at 15th
            new Wrong("subclass:battle-master-superiority-dice", 14, 14, EVERY_MODIFIER, flat(6)),
            // The proficiency bonus rather than once
            new Wrong("subclass:twilight-eyes-of-night", 1, 20, EVERY_MODIFIER, PB),
            // A flat 1 rather than the proficiency bonus
            new Wrong("subclass:stars-cosmic-omen", 6, 20, EVERY_MODIFIER, flat(1)),
            // A flat 2: no third die at 14th
            new Wrong("subclass:divination-portent", 14, 20, EVERY_MODIFIER, flat(2)),
            // The raw ability modifier, with no minimum of once
            new Wrong("subclass:echo-knight-unleash-incarnation", 3, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:cavalier-unwavering-mark", 3, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:cavalier-warding-maneuver", 7, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:light-warding-flare", 1, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:tempest-wrath-of-the-storm", 1, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:war-war-priest", 1, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:grave-eyes-of-the-grave", 1, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:grave-sentinel-at-deaths-door", 6, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:order-embodiment-of-the-law", 6, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:dreams-hidden-paths", 10, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:spores-fungal-infestation", 6, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:monster-slayer-hunters-sense", 3, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:chronurgy-momentary-stasis", 6, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:graviturgy-violent-attraction", 10, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER),
            new Wrong("subclass:war-magic-power-surge", 6, 20, MODIFIERS_BELOW_ONE, RAW_MODIFIER));

    // ── What the seeder writes ───────────────────────────────────

    private static final Map<String, ResourcePoolDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final List<ClassFeaturePool> MAPPINGS = new ArrayList<>();
    private static final Map<String, CharacterClass> CLASSES = new LinkedHashMap<>();
    private static final Map<String, Subclass> SUBCLASSES = new LinkedHashMap<>();
    private static ResourcePoolService service;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void seed() {
        for (String name : List.of("Barbarian", "Bard", "Cleric", "Druid", "Fighter", "Monk", "Paladin", "Ranger",
                "Rogue", "Sorcerer", "Warlock", "Wizard")) {
            CLASSES.put(name, CharacterClass.builder().id(UUID.randomUUID()).name(name).source("PHB").build());
        }

        ResourcePoolDefinitionRepository definitions = mock(ResourcePoolDefinitionRepository.class);
        when(definitions.count()).thenReturn(0L);
        when(definitions.saveAll(anyIterable())).thenAnswer(invocation -> {
            Iterable<ResourcePoolDefinition> saved = invocation.getArgument(0);
            saved.forEach(definition -> DEFINITIONS.put(definition.getPoolId(), definition));
            return saved;
        });
        when(definitions.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(DEFINITIONS.get(invocation.<String>getArgument(0))));
        ClassFeaturePoolRepository mappings = mock(ClassFeaturePoolRepository.class);
        when(mappings.saveAll(anyIterable())).thenAnswer(invocation -> {
            Iterable<ClassFeaturePool> saved = invocation.getArgument(0);
            saved.forEach(MAPPINGS::add);
            return saved;
        });
        MonsterPoolTriggerRepository triggers = mock(MonsterPoolTriggerRepository.class);
        when(triggers.saveAll(anyIterable())).thenAnswer(invocation -> invocation.getArgument(0));
        CharacterClassRepository classes = mock(CharacterClassRepository.class);
        when(classes.findAll()).thenReturn(new ArrayList<>(CLASSES.values()));
        SubclassRepository subclasses = mock(SubclassRepository.class);
        when(subclasses.findByName(anyString())).thenAnswer(invocation -> SUBCLASSES.computeIfAbsent(
                invocation.getArgument(0), name -> Subclass.builder().id(UUID.randomUUID()).name(name).build()));

        new ResourcePoolSeeder(definitions, mappings, triggers, classes, subclasses, new ObjectMapper()).seed();

        // The class's pools as the repository's queries find them
        ClassFeaturePoolRepository lookups = mock(ClassFeaturePoolRepository.class);
        when(lookups.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(any(), anyInt()))
                .thenAnswer(invocation -> poolsOf(invocation.getArgument(0), invocation.getArgument(1), null));
        when(lookups.findByClassIdAndLevelAndSubclass(any(), anyInt(), any()))
                .thenAnswer(invocation -> poolsOf(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
        service = new ResourcePoolService(lookups);
    }

    private static List<ClassFeaturePool> poolsOf(UUID classId, int level, UUID subclassId) {
        return MAPPINGS.stream()
                .filter(pool -> pool.getCharacterClass().getId().equals(classId) && pool.getMinLevel() <= level)
                .filter(pool -> pool.getSubclass() == null
                        || (subclassId != null && pool.getSubclass().getId().equals(subclassId)))
                .toList();
    }

    // The pool's maximum uses for a single-class character at the level, with every ability score giving the
    // modifier, or null when it doesn't have the pool
    private static Integer sizeOf(Book book, int level, int modifier) {
        int score = 10 + 2 * modifier;
        CharacterClass characterClass = CLASSES.get(book.className());
        Subclass subclass = book.subclassName() != null ? SUBCLASSES.get(book.subclassName()) : null;
        PlayerCharacter character = PlayerCharacter.builder().level(level).proficiencyBonus(pb(level))
                .strength(score).dexterity(score).constitution(score)
                .intelligence(score).wisdom(score).charisma(score)
                .multiclassEntries(List.of(new ClassEntry(characterClass.getId(), characterClass.getName(),
                        subclass != null ? subclass.getId() : null, book.subclassName(), level)))
                .build();
        return service.reconciledPools(character, service.buildCharacterContext(character)).stream()
                .filter(pool -> pool.poolId().equals(book.poolId()))
                .map(ResourcePoolEntry::maxUses)
                .findFirst()
                .orElse(null);
    }

    static Stream<Book> classPools() {
        return CLASS_POOLS.stream();
    }

    // ── Tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("Every pool definition the seeder writes has its sizes typed out here, and every one typed out here is written")
    void everyDefinitionIsCovered() {
        Set<String> typed = new TreeSet<>(MONSTER_POOLS.keySet());
        CLASS_POOLS.forEach(book -> typed.add(book.poolId()));
        assertEquals(CLASS_POOLS.size() + MONSTER_POOLS.size(), typed.size(), "a pool is typed out twice");
        assertEquals(typed, new TreeSet<>(DEFINITIONS.keySet()));
        assertEquals(CLASS_POOLS.size(), MAPPINGS.size(), "each class pool has one class mapping");
    }

    @Test
    @DisplayName("Each known-wrong case names a pool typed out here")
    void knownWrongNamesTypedPools() {
        Set<String> typed = CLASS_POOLS.stream().map(Book::poolId).collect(Collectors.toSet());
        KNOWN_WRONG.forEach(wrong -> assertTrue(typed.contains(wrong.poolId()), wrong.poolId()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("classPools")
    @DisplayName("A class pool has the book's maximum uses at every class level from the one that grants it, and none before; the known-wrong cases still give today's value")
    void classPoolSizes(Book book) {
        List<String> failures = new ArrayList<>();
        for (int level = 1; level <= 20; level++) {
            for (int modifier : MODIFIERS) {
                Integer actual = sizeOf(book, level, modifier);
                Integer expected = level >= book.fromLevel() ? book.size().at(level, modifier) : null;
                String at = "level " + level + ", modifier " + modifier + ": ";
                Wrong wrong = knownWrong(book.poolId(), level, modifier);
                if (wrong == null) {
                    if (!Objects.equals(expected, actual)) {
                        failures.add(at + "the book gives " + show(expected) + ", today " + show(actual));
                    }
                } else {
                    int today = wrong.today().at(level, modifier);
                    if (Objects.equals(expected, actual)) {
                        failures.add(at + "now gives the book's " + show(expected) + "; take it out of KNOWN_WRONG");
                    } else if (!Objects.equals(today, actual)) {
                        failures.add(at + "KNOWN_WRONG expects " + show(today) + " today, but it gives " + show(actual));
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), book.poolId() + " (" + book.source() + "):\n" + String.join("\n", failures));
    }

    @Test
    @DisplayName("The monster pools' defaults are the Monster Manual's usual counts")
    void monsterPoolDefaults() {
        for (var pool : MONSTER_POOLS.entrySet()) {
            ResourcePoolDefinition definition = DEFINITIONS.get(pool.getKey());
            for (int modifier : MODIFIERS) {
                Map<String, Integer> context = new LinkedHashMap<>();
                context.put("proficiencyBonus", 2);
                context.put("totalLevel", 1);
                for (Ability ability : Ability.values()) {
                    context.put(ability.fullName() + "Modifier", modifier);
                }
                assertEquals(pool.getValue(), ExpressionEvaluator.evaluate(definition.getMaxUsesFormula(), context),
                        pool.getKey() + ", modifier " + modifier);
            }
        }
    }

    private static Wrong knownWrong(String poolId, int level, int modifier) {
        return KNOWN_WRONG.stream().filter(wrong -> wrong.covers(poolId, level, modifier)).findFirst().orElse(null);
    }

    private static String show(Integer uses) {
        return uses == null ? "no pool" : uses == UNLIMITED ? "unlimited" : String.valueOf(uses);
    }
}
