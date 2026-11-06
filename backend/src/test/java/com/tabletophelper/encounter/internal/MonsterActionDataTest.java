package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.Effect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The seeded monster action data, monster-action-definitions.json, as combat reads it (D165). Every entry fits the
 * template records. A condition an attack gives on a hit records no DC without its ability: a grapple's or a
 * restraint's escape DC is an escapeDC, never a save. The bites whose DC is the monster's own save to regurgitate what
 * it swallowed, the Kraken's (MM), the Froghemoth's (VGM, MPMM), the Froghemoth Elder's (QftIS) and Slarkrethel's
 * (SKT), deal their piercing damage with no save for the target and leave the swallowing to the DM in a note. The
 * Mindwitness's and Ulitharid's tentacles (MPMM) grapple on every hit, and only the restraint or the stun waits for
 * the Intelligence save their text gives.
 */
class MonsterActionDataTest {

    private static final String FILE = "data/monster-actions/monster-action-definitions.json";
    private static final List<String> LISTS = List.of("actions", "legendaryActions", "lairActions", "bonusActions",
            "reactions", "traits", "mythicActions");

    private static JsonNode definitions;

    private final MonsterEffects monsterEffects = new MonsterEffects(new CombatFixture.ScriptedDice());

    @BeforeAll
    static void readTheData() throws IOException {
        try (InputStream in = new ClassPathResource(FILE).getInputStream()) {
            definitions = new ObjectMapper().readTree(in);
        }
    }

    @Test
    @DisplayName("Every entry fits the template records")
    void everyEntryIsReadable() {
        List<String> unreadable = new ArrayList<>();
        for (JsonNode definition : definitions) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            if (templates.unreadable() != null) {
                unreadable.add(name(definition) + ": " + templates.unreadable());
            }
        }
        assertEquals(List.of(), unreadable);
    }

    @Test
    @DisplayName("No condition an attack gives on a hit records a DC without an ability")
    void noHitConditionHasADcWithoutAnAbility() {
        List<String> found = new ArrayList<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    if (!"ATTACK_ROLL".equals(action.path("deliveryMethod").asText())) {
                        continue;
                    }
                    for (JsonNode effect : action.path("effects")) {
                        if (!"CONDITION".equalsIgnoreCase(effect.path("effectType").asText())) {
                            continue;
                        }
                        boolean ownDc = effect.path("saveDC").isNumber();
                        boolean ownAbility = effect.path("saveAbility").isTextual();
                        boolean actionDcWithoutAbility = action.path("saveDC").isNumber()
                                && !action.path("saveAbility").isTextual() && !ownDc && !ownAbility;
                        if (ownDc && !ownAbility || actionDcWithoutAbility) {
                            found.add(name(definition) + " " + action.path("name").asText() + " "
                                    + effect.path("condition").asText());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), found);
    }

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource({"Kraken, MM, 3d8+10", "Froghemoth, VGM, 3d10 + 6", "Froghemoth, MPMM, 3d10 + 6",
            "Froghemoth Elder, QftIS, 3d10 + 7", "Slarkrethel, SKT, 3d8 + 10"})
    @DisplayName("A swallowing bite deals its piercing damage with no save for the target, and notes the swallowing")
    void swallowingBites(String monster, String source, String piercing) {
        JsonNode bite = action(monster, source, "Bite");

        assertFalse(bite.has("saveDC") || bite.has("saveAbility") || bite.has("secondarySaveDC")
                || bite.has("secondarySaveAbility") || bite.has("targetType") || bite.has("aoeSize"), bite::toString);
        assertEquals(1, bite.path("effects").size());
        assertEquals("DAMAGE", bite.path("effects").get(0).path("effectType").asText());
        assertEquals(piercing, bite.path("effects").get(0).path("damageDice").asText());
        assertEquals("piercing", bite.path("effects").get(0).path("damageType").asText());
        assertTrue(bite.path("dmNote").asText().startsWith("Swallowing is applied by hand. On a hit, "));
        assertTrue(bite.path("dmNote").asText().contains("Constitution saving throw"));

        MonsterTemplates templates = MonsterTemplates.of(definition(monster, source));
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find("ACTION", "Bite").orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        Effect effect = plan.steps().getFirst().effect();
        Effect.Attack attack = assertInstanceOf(Effect.Attack.class, effect.delivery());
        assertNull(attack.hitSave());
        assertEquals(List.of(), effect.conditions());
        assertEquals(List.of(bite.path("dmNote").asText()), plan.notes());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"Mindwitness, 13, restrained, 13", "Ulitharid, 14, stunned, 17"})
    @DisplayName("An MPMM tentacle grapples on a hit with its escape DC, and only its other condition waits for an "
            + "Intelligence save")
    void tentacles(String monster, int escapeDc, String condition, int saveDc) {
        MonsterTemplates templates = MonsterTemplates.of(definition(monster, "MPMM"));
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find("ACTION", "Tentacle").orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);

        Effect effect = plan.steps().getFirst().effect();
        Effect.Attack attack = assertInstanceOf(Effect.Attack.class, effect.delivery());
        assertEquals(new Effect.HitSave(Ability.INT, saveDc, List.of(condition)), attack.hitSave());
        assertEquals(List.of("grappled", condition), effect.conditions());
        assertEquals(escapeDc, effect.conditionEnds().get("grappled").escapeDc());
    }

    private static JsonNode action(String monster, String source, String name) {
        for (JsonNode action : definition(monster, source).path("actions")) {
            if (name.equals(action.path("name").asText())) {
                return action;
            }
        }
        throw new AssertionError(monster + " (" + source + ") has no " + name);
    }

    private static JsonNode definition(String monster, String source) {
        for (JsonNode definition : definitions) {
            if (monster.equals(definition.path("monsterName").asText())
                    && source.equals(definition.path("source").asText())) {
                return definition;
            }
        }
        throw new AssertionError("No definition for " + monster + " (" + source + ")");
    }

    private static String name(JsonNode definition) {
        return definition.path("monsterName").asText() + " (" + definition.path("source").asText() + ")";
    }
}
