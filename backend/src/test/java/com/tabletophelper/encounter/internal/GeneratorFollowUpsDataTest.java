package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The data D209 changes: the Regisaur's Swallow (BGG) read as a swallow, the Slithering Bloodfin's missing Swallow
 * (CRCotN) added, Windfall's Dragon's Flare (VEoR) on one creature, and the wrong "the DM settles the type" notes gone
 * from the Colossus of Akros's Flames of Akros (MOT) and the Quavilithku's Dissolving Web (VEoR).
 */
class GeneratorFollowUpsDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final List<String> LISTS = List.of("actions", "bonusActions", "reactions", "legendaryActions",
            "mythicActions");
    private static final List<JsonNode> definitions = new ArrayList<>();

    private final MonsterEffects monsterEffects = new MonsterEffects(new CombatFixture.ScriptedDice());

    @BeforeAll
    static void readTheData() throws IOException {
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            JacksonDefaults.mapper().readTree(in).forEach(definitions::add);
        }
    }

    @AfterAll
    static void letTheDataGo() {
        definitions.clear();
    }

    private static JsonNode definition(String monster, String source) {
        return definitions.stream()
                .filter(d -> monster.equals(d.path("monsterName").asString())
                        && source.equals(d.path("source").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No definition for " + monster + " (" + source + ")"));
    }

    private static JsonNode action(String monster, String source, String name) {
        for (String list : LISTS) {
            for (JsonNode action : definition(monster, source).path(list)) {
                if (name.equals(action.path("name").asString())) {
                    return action;
                }
            }
        }
        throw new AssertionError("No " + name + " for " + monster);
    }

    private MonsterEffects.Step step(String monster, String source, String list, String action) {
        MonsterTemplates templates = MonsterTemplates.of(definition(monster, source));
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find(list, action).orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        assertTrue(plan.automated(), plan::manualReason);
        return plan.steps().getFirst();
    }

    @Test
    @DisplayName("The regisaur's Swallow: a +13 bonus-action attack on a Huge or smaller creature it grapples, no "
            + "damage on the hit, blinded and restrained while swallowed, 2d6 acid at the start of the swallowed "
            + "creature's turns, two at a time, 25 damage from inside calling for a DC 16 Constitution save, landing "
            + "within 5 feet")
    void regisaur() {
        JsonNode swallow = action("Regisaur", "BGG", "Swallow");
        assertTrue(swallow.path("targetGrappled").asBoolean());
        assertFalse(swallow.has("automatable"));
        assertFalse(swallow.has("dmNote"));
        for (JsonNode part : swallow.path("effects")) {
            assertNotEquals("DAMAGE", part.path("effectType").asString(), "no damage on the hit");
        }
        MonsterEffects.Step step = step("Regisaur", "BGG", "BONUS", "Swallow");
        assertEquals(13, assertInstanceOf(Effect.Attack.class, step.effect().delivery()).bonus());
        assertTrue(step.effect().damage().isEmpty());
        assertEquals(List.of("swallowed", "blinded", "restrained"), step.effect().conditions());
        MonsterEffects.Lasting lasting = step.riders().lasting();
        assertTrue(lasting.targetGrappled());
        assertEquals(new MonsterTemplates.Swallow(2, 25, 16, null, null, null, 5),
                lasting.swallows().get("swallowed"));
        assertEquals("Huge", step.riders().maxTargetSize().get("swallowed"));
        assertEquals(Map.of("blinded", "swallowed", "restrained", "swallowed"), lasting.endsWith());
        MonsterTemplates.Part acid = lasting.parts().getFirst();
        assertEquals("ONGOING_DAMAGE", acid.effectType());
        assertEquals("2d6", acid.damageDice());
        assertEquals("acid", acid.damageType());
        assertEquals("TARGET", acid.turnOf());
        assertEquals("swallowed", acid.endsWith());
    }

    @Test
    @DisplayName("The slithering bloodfin's Swallow, missing from its hand-built entry, is added whole and marked: "
            + "+9 on a Medium or smaller creature it grapples, 4d6 necrotic at the start of the swallowed creature's "
            + "turns, one at a time, 30 damage calling for a DC 15 save, its healing a note for the DM")
    void bloodfin() {
        JsonNode bloodfin = definition("Slithering Bloodfin", "CRCotN");
        assertEquals("bonusActions", bloodfin.path("_riderAddedLists").path(0).asString());
        JsonNode swallow = action("Slithering Bloodfin", "CRCotN", "Swallow");
        assertTrue(swallow.path("_riderAddedAction").asBoolean());
        assertEquals("BONUS_ACTION", swallow.path("actionType").asString());
        assertTrue(swallow.path("targetGrappled").asBoolean());
        assertEquals("Also on a hit, applied by hand: The bloodfin regains hit points equal to the necrotic damage "
                + "dealt.", swallow.path("dmNote").asString());
        MonsterEffects.Step step = step("Slithering Bloodfin", "CRCotN", "BONUS", "Swallow");
        assertEquals(9, assertInstanceOf(Effect.Attack.class, step.effect().delivery()).bonus());
        assertTrue(step.effect().damage().isEmpty());
        MonsterEffects.Lasting lasting = step.riders().lasting();
        assertEquals(new MonsterTemplates.Swallow(1, 30, 15, null, null, null, 5),
                lasting.swallows().get("swallowed"));
        assertEquals("Medium", step.riders().maxTargetSize().get("swallowed"));
        assertEquals(Map.of("blinded", "swallowed", "restrained", "swallowed"), lasting.endsWith());
        MonsterTemplates.Part necrotic = lasting.parts().getFirst();
        assertEquals("4d6", necrotic.damageDice());
        assertEquals("necrotic", necrotic.damageType());
        assertEquals("TARGET", necrotic.turnOf());
    }

    @Test
    @DisplayName("Windfall's Dragon's Flare targets one creature, not a 30-foot sphere, keeping its five damage "
            + "options and half on a success")
    void windfall() {
        JsonNode flare = action("Windfall", "VEoR", "Dragon's Flare");
        assertEquals(1, flare.path("targetCount").asInt());
        assertFalse(flare.has("targetType"));
        assertFalse(flare.has("aoeSize"));
        assertTrue(flare.path("halfOnSave").asBoolean());
        List<String> options = new ArrayList<>();
        flare.path("damageOptions").forEach(option -> options.add(option.asString()));
        assertEquals(List.of("acid", "cold", "fire", "lightning", "poison"), options);
        MonsterTemplates templates = MonsterTemplates.of(definition("Windfall", "VEoR"));
        assertTrue(templates.find("LEGENDARY", "Dragon's Flare").isPresent());
    }

    @Test
    @DisplayName("No \"the DM settles the type\" note on the Flames of Akros or the Dissolving Web, whose stat blocks "
            + "offer no choice; the Fire Giant Dreadnought's Shield Charge, 18 or 29 damage, keeps its note")
    void choiceNotes() {
        assertFalse(action("Colossus of Akros", "MOT", "Flames of Akros").path("dmNote").asString()
                .contains("choice"));
        assertFalse(action("Quavilithku Spyder-Fiend", "VEoR", "Dissolving Web").path("dmNote").asString()
                .contains("choice"));
        List<String> noted = new ArrayList<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    if (action.path("dmNote").asString("").contains("a choice the DM settles by hand")) {
                        noted.add(definition.path("monsterName").asString() + " " + action.path("name").asString());
                    }
                }
            }
        }
        assertEquals(List.of("Fire Giant Dreadnought Shield Charge"), noted);
    }
}
