package com.tabletophelper.encounter.internal;

import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.Metamagic.Option;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metamagic against every spell effect template the application loads (M18, the roadmap's "full testing against all
 * spells"): each template gives its facts, and each PHB option (p.102) can change the spells the rules say it can. The
 * counts are the data's as of M18, so a template change that moves one shows here. Distant Spell reads the spell's
 * range from the spells table, not the template, so MetamagicTest covers it.
 */
class MetamagicEligibilityGoldenTest {

    private static final String DEFINITIONS = "/data/spell-effects/spell-effect-definitions.json";

    private static final Map<String, Metamagic.SpellFacts> FACTS = new LinkedHashMap<>();

    @BeforeAll
    static void readTemplates() throws IOException {
        try (InputStream in = MetamagicEligibilityGoldenTest.class.getResourceAsStream(DEFINITIONS)) {
            assertNotNull(in, DEFINITIONS);
            JsonNode definitions = JacksonDefaults.mapper().readTree(in);
            for (JsonNode definition : definitions) {
                SpellTemplate template = SpellTemplate.of(definition);
                assertNull(template.unreadable(), definition.path("spellName").asString());
                FACTS.put(definition.path("spellName").asString(), SpellEffects.facts(template, null, null));
            }
        }
    }

    private static boolean eligible(String spell, Option option, int castLevel) {
        Metamagic.SpellFacts facts = FACTS.get(spell);
        assertNotNull(facts, spell);
        return Metamagic.ineligible(option, facts, castLevel).isEmpty();
    }

    @Test
    @DisplayName("All 288 templates give their facts, every casting time read")
    void everyTemplateReads() {
        assertEquals(288, FACTS.size());
        FACTS.forEach((spell, facts) -> assertNotNull(facts.castingTime(), spell));
    }

    @Test
    @DisplayName("At each spell's own level: Careful and Heightened 101, Empowered 103, Extended 186, Quickened 226, Subtle 288, Twinned 124")
    void countsAtOwnLevel() {
        Map<Option, Integer> counts = new EnumMap<>(Option.class);
        FACTS.forEach((spell, facts) -> {
            for (Option option : Option.values()) {
                if (option != Option.DISTANT && Metamagic.ineligible(option, facts, facts.level()).isEmpty()) {
                    counts.merge(option, 1, Integer::sum);
                }
            }
        });
        assertEquals(101, counts.get(Option.CAREFUL));
        assertEquals(101, counts.get(Option.HEIGHTENED));
        assertEquals(103, counts.get(Option.EMPOWERED));
        assertEquals(186, counts.get(Option.EXTENDED));
        assertEquals(226, counts.get(Option.QUICKENED));
        assertEquals(288, counts.get(Option.SUBTLE));
        assertEquals(124, counts.get(Option.TWINNED));
    }

    @Test
    @DisplayName("Twinned: a spell that gains targets at higher levels is twinned only at its own level, such as Hold Person and Invisibility")
    void twinnedOnlyAtOwnLevelWhenTargetsScale() {
        long scaling = FACTS.values().stream()
                .filter(facts -> facts.singleTarget() && facts.additionalTargetsPerLevel() != null)
                .peek(facts -> assertTrue(Metamagic.ineligible(Option.TWINNED, facts, facts.level() + 1).isPresent()))
                .count();
        assertEquals(17, scaling);
        assertTrue(eligible("Hold Person", Option.TWINNED, 2));
        assertFalse(eligible("Hold Person", Option.TWINNED, 3));
        assertTrue(eligible("Invisibility", Option.TWINNED, 2));
        assertFalse(eligible("Invisibility", Option.TWINNED, 4));
    }

    @Test
    @DisplayName("Spot checks against the PHB: multi-target and self spells aren't twinned, reactions aren't quickened")
    void spotChecks() {
        for (String spell : List.of("Magic Missile", "Scorching Ray", "Eldritch Blast", "Shield", "Fireball")) {
            assertFalse(eligible(spell, Option.TWINNED, FACTS.get(spell).level()), spell);
        }
        for (String spell : List.of("Fire Bolt", "Chromatic Orb", "Haste", "Witch Bolt", "Toll the Dead")) {
            assertTrue(eligible(spell, Option.TWINNED, FACTS.get(spell).level()), spell);
        }
        assertFalse(eligible("Shield", Option.QUICKENED, 1));
        assertFalse(eligible("Healing Word", Option.QUICKENED, 1));
        assertTrue(eligible("Fireball", Option.QUICKENED, 3));
        assertTrue(eligible("Fireball", Option.CAREFUL, 3));
        assertTrue(eligible("Fireball", Option.EMPOWERED, 3));
        assertFalse(eligible("Fireball", Option.EXTENDED, 3));
        assertFalse(eligible("Fire Bolt", Option.HEIGHTENED, 0));
        assertTrue(eligible("Mage Armor", Option.EXTENDED, 1));
        assertTrue(eligible("Haste", Option.EXTENDED, 3));
    }
}
