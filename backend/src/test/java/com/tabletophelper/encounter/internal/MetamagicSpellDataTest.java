package com.tabletophelper.encounter.internal;

import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.Metamagic.Option;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every spell effect template against all eight Metamagic options of the PHB (p.101–102), the roadmap's M18 "full
 * testing against all currently implemented spells". Each spell's expected eligibility is derived independently of the
 * template, from the 5etools spell data the spells table is loaded from: its casting time (Quickened), components
 * (Subtle), duration (Extended), range (Distant), saving throws (Careful, Heightened), damage (Empowered) and area tags
 * with its range and higher-level text (Twinned). Where the app departs from that reading on purpose, the spell is
 * listed below with the decision that put it there; anything else that disagrees fails.
 */
class MetamagicSpellDataTest {

    private static final String DEFINITIONS = "/data/spell-effects/spell-effect-definitions.json";
    private static final Map<String, JsonNode> TEMPLATES = new LinkedHashMap<>();
    private static final Map<String, JsonNode> SPELLS = new LinkedHashMap<>();
    private static final Map<String, Metamagic.SpellFacts> FACTS = new LinkedHashMap<>();

    // ── Where the app reads a spell differently from the plain data, and why ──

    // Careful and Heightened Spell need a save the cast itself forces (D179, D199): a save that comes later, on a
    // weapon hit, an action, a turn or a trigger, a save the caster makes, or one the DM resolves by hand isn't one
    private static final Set<String> NO_SAVE_ON_CAST = Set.of(
            // the smites and strikes, weapon riders whose save comes on the hit (D199)
            "Blinding Smite", "Ensnaring Strike", "Hail of Thorns", "Lightning Arrow", "Searing Smite",
            "Staggering Smite", "Thunderous Smite", "Wrathful Smite",
            // lasting areas and zones whose cast forces no save (D199)
            "Antipathy/Sympathy", "Control Water", "Control Winds", "Cordon of Arrows", "Delayed Blast Fireball",
            "Forcecage", "Gust of Wind", "Hallow", "Hunger of Hadar", "Magic Circle", "Ravenous Void",
            "Stinking Cloud", "Symbol", "Temple of the Gods", "Wall of Stone",
            // saves on a later action, turn or hit
            "Contagion", "Detect Thoughts", "Dispel Evil and Good", "Dragon's Breath", "Holy Aura", "Holy Weapon",
            "Investiture of Flame", "Investiture of Ice", "Investiture of Stone", "Investiture of Wind",
            "Otto's Irresistible Dance", "Power Word Pain", "Power Word Stun", "Ray of Enfeeblement",
            "Summon Draconic Spirit", "Summon Greater Demon", "Magic Jar", "Heat Metal",
            // the caster's own save
            "Contact Other Plane", "Tenser's Transformation",
            // no save at all (D196)
            "Sanctuary",
            // saves the DM resolves for a spell resolved by hand
            "Dream", "Scrying", "Seeming");

    // Empowered Spell rerolls damage rolled when the spell is cast (D179): dice that come later, through Ongoing
    // Modifiers (D184, D196, D197), a later use, a retaliation or a turn (D199), or only from the DM, aren't
    private static final Set<String> NO_DAMAGE_ON_CAST = Set.of(
            "Alter Self", "Animate Objects", "Armor of Agathys", "Ashardalon's Stride", "Banishing Smite",
            "Bestow Curse", "Blade Barrier", "Blinding Smite", "Bones of the Earth", "Branding Smite",
            "Contact Other Plane", "Control Water", "Cordon of Arrows", "Crown of Stars", "Crusader's Mantle",
            "Delayed Blast Fireball", "Dimension Door", "Divine Favor", "Dragon's Breath", "Dream", "Earthquake",
            "Elemental Bane", "Elemental Weapon", "Ensnaring Strike", "Etherealness", "Fire Shield", "Flame Arrows",
            "Forbiddance", "Geas", "Guardian of Nature", "Hail of Thorns", "Hex", "Holy Weapon", "Hunger of Hadar",
            "Illusory Dragon", "Investiture of Flame", "Investiture of Ice", "Investiture of Wind", "Kinetic Jaunt",
            "Lightning Arrow", "Mordenkainen's Faithful Hound", "Phantasmal Force", "Phantasmal Killer",
            "Prismatic Wall", "Reality Break", "Searing Smite", "Shadow Blade", "Shadow of Moil", "Spike Growth",
            "Spirit Shroud", "Staggering Smite", "Symbol", "Tasha's Caustic Brew", "Teleport",
            "Tenser's Transformation", "Thunderous Smite", "Transmute Rock", "Web", "Weird", "Wish",
            "Wrathful Smite", "Zephyr Strike");

    // Damage the 5etools data leaves untyped because the weapon or ammunition gives it, rolled on the cast
    private static final Set<String> WEAPON_DAMAGE_ON_CAST = Set.of("Conjure Barrage", "Conjure Volley");

    // Twinned Spell (PHB p.102): one creature, never self. The area tags miss these single-creature spells
    private static final Set<String> ONE_CREATURE_UNTAGGED = Set.of("Air Bubble", "Antagonize", "Counterspell",
            "Fast Friends", "Fizban's Platinum Shield", "Intellect Fortress", "Phantasmal Force", "Spider Climb",
            "True Seeing", "Vortex Warp",
            // a dead creature is still named a creature (rules call); they take an hour to cast besides
            "Raise Dead", "Reincarnate", "Resurrection");
    // ...and tag these as one target, which is an object, a space or something the spell creates, never a creature:
    // the template's noCreatureTarget; or which the DM resolves by hand as a special target
    private static final Set<String> NOT_A_CREATURE = Set.of("Catapult", "Grasping Vine", "Heat Metal", "Identify",
            "Immovable Object", "Magic Stone", "Maximilian's Earthen Grasp", "Mordenkainen's Sword",
            "Spiritual Weapon", "Create Magen", "Dream", "Simulacrum", "Snare");

    private static final Pattern MORE_TARGETS = Pattern.compile("additional (creature|target|willing|humanoid|beast|"
            + "globes)|target (one|two) additional|up to three willing creatures", Pattern.CASE_INSENSITIVE);

    @BeforeAll
    static void read() throws IOException {
        try (InputStream in = MetamagicSpellDataTest.class.getResourceAsStream(DEFINITIONS)) {
            assertNotNull(in, DEFINITIONS);
            for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                TEMPLATES.put(definition.path("spellName").asString(), definition);
            }
        }
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath:data/5etools/spells/spells-*.json");
        for (Resource file : files) {
            try (InputStream in = file.getInputStream()) {
                for (JsonNode spell : JacksonDefaults.mapper().readTree(in).path("spell")) {
                    SPELLS.putIfAbsent(key(spell.path("name").asString()), spell);
                }
            }
        }
        TEMPLATES.forEach((name, json) -> {
            JsonNode spell = SPELLS.get(key(name));
            assertNotNull(spell, name + " is in the 5etools data");
            FACTS.put(name, SpellEffects.facts(SpellTemplate.of(json), null, range(spell)));
        });
    }

    static Stream<String> spells() {
        return TEMPLATES.keySet().stream();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // The range as the PHB words it: an area from the caster is "Self (15-foot cone)" (SpellLoader.parseRange)
    private static String range(JsonNode spell) {
        JsonNode range = spell.path("range");
        String type = range.path("type").asString("");
        JsonNode distance = range.path("distance");
        String unit = distance.path("type").asString("");
        if (type.equals("special")) {
            return "Special";
        }
        if (!type.equals("point")) {
            return "Self (" + distance.path("amount").asInt() + "-" + (unit.startsWith("mile") ? "mile" : "foot")
                    + " " + type + ")";
        }
        return switch (unit) {
            case "self" -> "Self";
            case "touch" -> "Touch";
            case "sight" -> "Sight";
            case "unlimited" -> "Unlimited";
            default -> distance.path("amount").asInt() + " " + unit;
        };
    }

    // ── The independent reading ──

    private static Map<Option, Boolean> expected(String name) {
        JsonNode spell = SPELLS.get(key(name));
        Map<Option, Boolean> expected = new EnumMap<>(Option.class);
        // Quickened: a casting time of 1 action
        JsonNode time = spell.path("time").get(0);
        expected.put(Option.QUICKENED, time.path("number").asInt() == 1
                && time.path("unit").asString().equals("action"));
        // Subtle: a verbal or somatic component
        expected.put(Option.SUBTLE, spell.path("components").path("v").asBoolean(false)
                || spell.path("components").path("s").asBoolean(false));
        // Extended: a duration of 1 minute or longer
        boolean minute = false;
        for (JsonNode duration : spell.path("duration")) {
            if (duration.path("type").asString().equals("timed")) {
                int amount = duration.path("duration").path("amount").asInt();
                minute |= switch (duration.path("duration").path("type").asString()) {
                    case "round" -> amount >= 10;
                    case "minute", "hour", "day" -> amount >= 1;
                    default -> false;
                };
            }
        }
        expected.put(Option.EXTENDED, minute);
        // Distant: a range of 5 feet or more, or touch; never self, sight, unlimited or special
        String range = range(spell).toLowerCase(Locale.ROOT);
        expected.put(Option.DISTANT, range.equals("touch") || range.matches("\\d+ (feet|miles?)")
                && (range.contains("mile") || Integer.parseInt(range.split(" ")[0]) >= 5));
        // Careful and Heightened: the spell forces a saving throw
        boolean save = !spell.path("savingThrow").isEmpty() && !NO_SAVE_ON_CAST.contains(name);
        expected.put(Option.CAREFUL, save);
        expected.put(Option.HEIGHTENED, save);
        // Empowered: the spell rolls damage
        expected.put(Option.EMPOWERED, (!spell.path("damageInflict").isEmpty() || WEAPON_DAMAGE_ON_CAST.contains(name))
                && !NO_DAMAGE_ON_CAST.contains(name));
        // Twinned: one creature, not a range of self
        List<String> tags = new ArrayList<>();
        spell.path("areaTags").forEach(tag -> tags.add(tag.asString()));
        boolean oneCreature = tags.contains("ST") && tags.stream().allMatch(tag -> tag.equals("ST") || tag.equals("N"));
        expected.put(Option.TWINNED, !range.startsWith("self") && !NOT_A_CREATURE.contains(name)
                && (oneCreature || ONE_CREATURE_UNTAGGED.contains(name)));
        return expected;
    }

    private static boolean eligible(String spell, Option option, int castLevel) {
        Metamagic.SpellFacts facts = FACTS.get(spell);
        assertNotNull(facts, spell);
        return Metamagic.ineligible(option, facts, castLevel).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("spells")
    @DisplayName("Each spell takes each option as the PHB reading of its data says (PHB p.102)")
    void eachOption(String spell) {
        Map<Option, Boolean> expected = expected(spell);
        int level = FACTS.get(spell).level();
        List<Executable> checks = new ArrayList<>();
        for (Option option : Option.values()) {
            checks.add(() -> assertEquals(expected.get(option), eligible(spell, option, level),
                    spell + ", " + option.displayName() + ": "
                            + Metamagic.ineligible(option, FACTS.get(spell), level).orElse("eligible")));
        }
        assertAll(spell, checks);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("spells")
    @DisplayName("A twinnable spell that gains targets with a higher slot is twinned only at its own level, as its higher-level text says")
    void twinnedTargetScaling(String spell) {
        Metamagic.SpellFacts facts = FACTS.get(spell);
        if (!eligible(spell, Option.TWINNED, facts.level()) || facts.level() == 0 || facts.level() == 9) {
            return;
        }
        boolean gains = MORE_TARGETS.matcher(SPELLS.get(key(spell)).path("entriesHigherLevel").toString()).find();
        assertEquals(!gains, eligible(spell, Option.TWINNED, facts.level() + 1), spell);
    }

    @Test
    @DisplayName("Every template is matched to the spells table's data")
    void everyTemplateMatched() {
        assertEquals(525, FACTS.size());
    }

    @Test
    @DisplayName("The tricky ones, by hand (PHB p.102, Sage Advice's strict reading of Twinned, D179, D199)")
    void trickyOnes() {
        // Ice Knife: one creature, its burst over others; twinnable by the user's ruling (D179, D199)
        assertTrue(eligible("Ice Knife", Option.TWINNED, 1));
        assertTrue(eligible("Ice Knife", Option.CAREFUL, 1));
        // Darts, rays and beams that can each go elsewhere aren't one creature
        for (String spell : List.of("Magic Missile", "Scorching Ray", "Eldritch Blast")) {
            assertFalse(eligible(spell, Option.TWINNED, Math.max(1, FACTS.get(spell).level())), spell);
        }
        assertTrue(eligible("Chromatic Orb", Option.TWINNED, 1));
        // Hold Person: one humanoid at 2nd level, two from 3rd
        assertTrue(eligible("Hold Person", Option.TWINNED, 2));
        assertFalse(eligible("Hold Person", Option.TWINNED, 3));
        assertTrue(eligible("Polymorph", Option.TWINNED, 4));
        assertTrue(eligible("Haste", Option.TWINNED, 3));
        // Dragon's Breath: touch, a bonus action, its breath later
        assertTrue(eligible("Dragon's Breath", Option.TWINNED, 2));
        assertTrue(eligible("Dragon's Breath", Option.DISTANT, 2));
        assertFalse(eligible("Dragon's Breath", Option.QUICKENED, 2));
        assertFalse(eligible("Dragon's Breath", Option.EMPOWERED, 2));
        // Booming Blade and Green-Flame Blade have a range of self (5-foot radius) (TCE p.106–107): not twinned, not
        // made distant; Green-Flame Blade's fire dice from 5th level can be empowered
        for (String blade : List.of("Booming Blade", "Green-Flame Blade")) {
            assertFalse(eligible(blade, Option.TWINNED, 0), blade);
            assertFalse(eligible(blade, Option.DISTANT, 0), blade);
            assertTrue(eligible(blade, Option.EMPOWERED, 0), blade);
            assertTrue(eligible(blade, Option.QUICKENED, 0), blade);
        }
        assertTrue(eligible("Toll the Dead", Option.TWINNED, 0));
        assertTrue(eligible("Toll the Dead", Option.HEIGHTENED, 0));
        // Fireball: an area, never twinned
        assertFalse(eligible("Fireball", Option.TWINNED, 3));
        assertTrue(eligible("Fireball", Option.CAREFUL, 3));
        // Shield is a reaction, Hex a bonus action: neither quickened
        assertFalse(eligible("Shield", Option.QUICKENED, 1));
        assertFalse(eligible("Shield", Option.TWINNED, 1));
        assertFalse(eligible("Hex", Option.QUICKENED, 1));
        assertTrue(eligible("Hex", Option.TWINNED, 1));
        assertTrue(eligible("Witch Bolt", Option.TWINNED, 1));
        assertTrue(eligible("Witch Bolt", Option.EXTENDED, 1));
        // Spirit Guardians: self (15-foot radius), no Distant or Twinned; its save on the cast (D199)
        assertFalse(eligible("Spirit Guardians", Option.DISTANT, 3));
        assertFalse(eligible("Spirit Guardians", Option.TWINNED, 3));
        assertTrue(eligible("Spirit Guardians", Option.EXTENDED, 3));
        assertFalse(eligible("Animate Objects", Option.TWINNED, 5));
        assertFalse(eligible("Mass Cure Wounds", Option.TWINNED, 5));
        assertTrue(eligible("Counterspell", Option.TWINNED, 3));
        assertFalse(eligible("Counterspell", Option.QUICKENED, 3));
        // Distant reads the range, not whom the spell affects: Dimension Door's 500 feet, Shillelagh's touch
        assertTrue(eligible("Dimension Door", Option.DISTANT, 4));
        assertTrue(eligible("Shillelagh", Option.DISTANT, 0));
        assertFalse(eligible("Burning Hands", Option.DISTANT, 1));
        // Extended: an instantaneous spell can't be extended, whatever lingers
        assertFalse(eligible("Silvery Barbs", Option.EXTENDED, 1));
        assertFalse(eligible("Synaptic Static", Option.EXTENDED, 5));
        assertTrue(eligible("Shape Water", Option.EXTENDED, 0));
        // An object's spell isn't twinned
        assertFalse(eligible("Heat Metal", Option.TWINNED, 2));
        assertFalse(eligible("Magic Weapon", Option.TWINNED, 2));
        // Prismatic Spray's 10d6 rays are rolled on the cast; Plane Shift's follow-up Charisma save is the spell's
        assertTrue(eligible("Prismatic Spray", Option.EMPOWERED, 7));
        assertTrue(eligible("Plane Shift", Option.HEIGHTENED, 7));
    }
}
