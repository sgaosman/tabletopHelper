package com.tabletophelper.rules.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class CastingTimeTest {

    /**
     * Every distinct {@code spells.casting_time} in the data, as SpellLoader writes it from the 5eTools spell files
     * (17 values over 525 spells, read from the database on 2026-11-06), with what it costs.
     */
    private static final Map<String, CastingTime> EVERY_SPELL_CASTING_TIME = new LinkedHashMap<>();

    static {
        EVERY_SPELL_CASTING_TIME.put("1 action", CastingTime.ACTION);
        EVERY_SPELL_CASTING_TIME.put("1 bonus action", CastingTime.BONUS_ACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, which you take when a creature you can see within 60 feet of "
                + "yourself succeeds on an attack roll, an ability check, or a saving throw", CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, which you take when a humanoid you can see within 60 feet of you "
                + "dies", CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, taken when a creature you can see makes an attack roll or starts "
                + "to cast a spell", CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, which you take when you or a creature within 60 feet of you falls",
                CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, which you take when you speak to another creature",
                CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, which you take in response to being damaged by a creature within "
                + "60 feet of you that you can see", CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, which you take when you see a creature within 60 feet of you "
                + "casting a spell", CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, which you take when you take acid, cold, fire, lightning, or "
                + "thunder damage", CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 reaction, which you take when you are hit by an attack or targeted by the "
                + "magic missile spell", CastingTime.REACTION);
        EVERY_SPELL_CASTING_TIME.put("1 minute", CastingTime.LONGER);
        EVERY_SPELL_CASTING_TIME.put("10 minutes", CastingTime.LONGER);
        EVERY_SPELL_CASTING_TIME.put("1 hour", CastingTime.LONGER);
        EVERY_SPELL_CASTING_TIME.put("8 hours", CastingTime.LONGER);
        EVERY_SPELL_CASTING_TIME.put("12 hours", CastingTime.LONGER);
        EVERY_SPELL_CASTING_TIME.put("24 hours", CastingTime.LONGER);
    }

    @Test
    @DisplayName("Reads every casting time the spells table holds")
    void everySpellCastingTime() {
        assertEquals(17, EVERY_SPELL_CASTING_TIME.size());
        EVERY_SPELL_CASTING_TIME.forEach((text, expected) -> assertEquals(expected, CastingTime.of(text), text));
    }

    @Test
    @DisplayName("Reads every castingTime in the spell effect templates")
    void everyTemplateCastingTime() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            assertNotNull(in, "the spell effect templates are on the classpath");
            for (JsonNode template : JsonMapper.builder().build().readTree(in)) {
                JsonNode time = template.get("castingTime");
                if (time != null && !time.isNull()) {
                    found.add(time.asString());
                }
            }
        }

        assertEquals(new TreeSet<>(java.util.List.of("10_MINUTES", "1_HOUR", "1_MINUTE", "ACTION", "BONUS_ACTION",
                "REACTION")), found);
        assertEquals(CastingTime.ACTION, CastingTime.of("ACTION"));
        assertEquals(CastingTime.BONUS_ACTION, CastingTime.of("BONUS_ACTION"));
        assertEquals(CastingTime.REACTION, CastingTime.of("REACTION"));
        assertEquals(CastingTime.LONGER, CastingTime.of("1_MINUTE"));
        assertEquals(CastingTime.LONGER, CastingTime.of("10_MINUTES"));
        assertEquals(CastingTime.LONGER, CastingTime.of("1_HOUR"));
    }

    @Test
    @DisplayName("Ignores case, surrounding spaces and a bare reaction's missing trigger")
    void ignoresCaseAndSpaces() {
        assertEquals(CastingTime.ACTION, CastingTime.of("  1 Action "));
        assertEquals(CastingTime.BONUS_ACTION, CastingTime.of("1 BONUS ACTION"));
        assertEquals(CastingTime.REACTION, CastingTime.of("1 reaction"));
        assertEquals(CastingTime.LONGER, CastingTime.of("1 round"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "2 actions", "1 reactionary", "special", "1 bonus", "action or bonus action"})
    @DisplayName("Refuses a missing casting time and one it can't read")
    void refusesWhatItCantRead(String text) {
        assertThrows(IllegalArgumentException.class, () -> CastingTime.of(text));
    }

    @Test
    @DisplayName("An action, a bonus action and a reaction cost their own; longer costs nothing from a turn")
    void costs() {
        assertEquals(Optional.of(ActionCost.ACTION), CastingTime.ACTION.cost());
        assertEquals(Optional.of(ActionCost.BONUS_ACTION), CastingTime.BONUS_ACTION.cost());
        assertEquals(Optional.of(ActionCost.REACTION), CastingTime.REACTION.cost());
        assertEquals(Optional.empty(), CastingTime.LONGER.cost());
    }
}
