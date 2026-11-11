package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What generate_monster_actions.py reads for steps 6 and 7 of the rider survey (D204), on the shipped data: swallowing
 * and engulfing, petrification over two saves, ability drain, what a drop to 0 hit points brings, what rises from the
 * slain and possession, with their counts pinned, and the samples the rules came from.
 */
class MonsterRidersTailDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final List<String> LISTS = List.of("actions", "bonusActions", "reactions", "legendaryActions");
    private static final Set<String> TAIL = Set.of("COUNTED_SAVES", "ABILITY_REDUCTION", "AT_ZERO", "ON_KILL",
            "POSSESSION");
    private static final List<JsonNode> definitions = new ArrayList<>();

    private final MonsterEffects monsterEffects = new MonsterEffects(new CombatFixture.ScriptedDice());

    @BeforeAll
    static void readTheData() throws IOException {
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            JacksonDefaults.mapper().readTree(in).forEach(definitions::add);
        }
    }

    // The data is large, and the test JVM runs the other classes after this one
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

    private MonsterEffects.Step step(String monster, String source, String action) {
        return step(monster, source, "ACTION", action);
    }

    // Each action of the data, with its monster
    private record Found(JsonNode definition, JsonNode action) {
    }

    private static List<Found> actions() {
        List<Found> found = new ArrayList<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                definition.path(list).forEach(action -> found.add(new Found(definition, action)));
            }
        }
        return found;
    }

    @Test
    @DisplayName("The counts: 40 swallows (32 regurgitating, 19 with a limit, 7 only for a grappled creature), 1 "
            + "engulfing, 14 actions only for a grappled target, 6 petrifications over two saves, 2 ability drains, 12 "
            + "drops to 0 (11 stable but poisoned), 7 risings, 7 possessions, 16 actions rebuilt and 5 stale notes "
            + "replaced")
    void counts() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Found found : actions()) {
            JsonNode action = found.action();
            if (action.path("targetGrappled").asBoolean(false)) {
                counts.merge("targetGrappled", 1, Integer::sum);
            }
            if (action.has("_riderReplaced")) {
                counts.merge("rebuilt", 1, Integer::sum);
            }
            if (action.has("_riderNoteReplaced")) {
                counts.merge("noteReplaced", 1, Integer::sum);
            }
            for (JsonNode part : action.path("effects")) {
                String type = part.path("effectType").asString("");
                if (TAIL.contains(type)) {
                    counts.merge(type, 1, Integer::sum);
                }
                if ("AT_ZERO".equals(type) && part.path("atZero").path("stable").asBoolean(false)) {
                    counts.merge("stable", 1, Integer::sum);
                }
                String condition = part.path("condition").asString("");
                if ("CONDITION".equals(type) && Set.of("swallowed", "engulfed").contains(condition)) {
                    counts.merge(condition, 1, Integer::sum);
                    if (part.path("swallow").path("regurgitateDamage").isNumber()) {
                        counts.merge("regurgitates", 1, Integer::sum);
                    }
                    if (part.path("swallow").path("limit").isNumber()) {
                        counts.merge("limit", 1, Integer::sum);
                    }
                    if (part.has("onlyIf")) {
                        counts.merge("onlyIfGrappled", 1, Integer::sum);
                    }
                }
            }
        }
        // D205's whole entries add 12 swallows (the purple worm's, both behirs', the remorhaz's, the young kraken's,
        // the udaak's, ...), 11 regurgitating, 4 with a limit and 1 only for a grappled creature, 5 actions only for a
        // grappled target and the spectral cloud's rising; 28, 21, 15, 6, 9 and 6 before
        assertEquals(Map.ofEntries(Map.entry("ABILITY_REDUCTION", 2), Map.entry("AT_ZERO", 12),
                Map.entry("COUNTED_SAVES", 6), Map.entry("ON_KILL", 7), Map.entry("POSSESSION", 7),
                Map.entry("engulfed", 1), Map.entry("limit", 19), Map.entry("noteReplaced", 5),
                Map.entry("onlyIfGrappled", 7), Map.entry("rebuilt", 16), Map.entry("regurgitates", 32),
                Map.entry("stable", 11), Map.entry("swallowed", 40), Map.entry("targetGrappled", 14)), counts);
    }

    @Test
    @DisplayName("Every swallowing or engulfing action still resolves, and every one of its tied parts names it")
    void swallowsResolve() {
        List<String> wrong = new ArrayList<>();
        for (Found found : actions()) {
            JsonNode action = found.action();
            boolean swallows = false;
            for (JsonNode part : action.path("effects")) {
                swallows |= part.has("swallow");
            }
            if (!swallows) {
                continue;
            }
            MonsterTemplates templates = MonsterTemplates.of(found.definition());
            MonsterTemplates.Action read = templates.component(action.path("name").asString()).orElseThrow();
            MonsterEffects.MonsterAction plan = monsterEffects.plan(read, templates, List.of(UUID.randomUUID()),
                    MonsterEffects.Overrides.NONE);
            String name = found.definition().path("monsterName").asString() + " " + read.name();
            if (!plan.automated()) {
                wrong.add(name + ": " + plan.manualReason());
                continue;
            }
            MonsterEffects.Lasting lasting = plan.steps().getFirst().riders().lasting();
            if (lasting.swallows().isEmpty()) {
                wrong.add(name + " has no gullet");
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    @DisplayName("A purple-worm-like froghemoth's bite: a Medium or smaller target swallowed with no save, blinded "
            + "and restrained while swallowed, 3d6 acid at the start of the froghemoth's turns, two at a time, and 20 "
            + "damage from inside calling for its DC 20 Constitution save (VGM p.145)")
    void froghemoth() {
        MonsterEffects.Step step = step("Froghemoth", "VGM", "Bite");
        assertEquals(List.of("swallowed", "blinded", "restrained"), step.effect().conditions());
        assertNull(assertInstanceOf(Effect.Attack.class, step.effect().delivery()).hitSave());
        MonsterEffects.Lasting lasting = step.riders().lasting();
        assertEquals(new MonsterTemplates.Swallow(2, 20, 20, 10), lasting.swallows().get("swallowed"));
        assertEquals("Medium", step.riders().maxTargetSize().get("swallowed"));
        assertEquals(Map.of("blinded", "swallowed", "restrained", "swallowed"), lasting.endsWith());
        MonsterTemplates.Part acid = lasting.parts().getFirst();
        assertEquals("ONGOING_DAMAGE", acid.effectType());
        assertEquals("3d6", acid.damageDice());
        assertEquals("USER", acid.turnOf());
        assertEquals("swallowed", acid.endsWith());
    }

    @Test
    @DisplayName("A neothelid's tentacles swallow on a failed DC 18 Strength save, which guards the blindness, the "
            + "restraint and the acid too (VGM p.80)")
    void neothelid() {
        MonsterEffects.Step step = step("Neothelid", "VGM", "Tentacles");
        Effect.HitSave save = assertInstanceOf(Effect.Attack.class, step.effect().delivery()).hitSave();
        assertEquals(Ability.STR, save.ability());
        assertEquals(18, save.dc());
        assertEquals(Set.of("swallowed", "blinded", "restrained"), Set.copyOf(save.conditions()));
    }

    @Test
    @DisplayName("The kraken's bite swallows only a creature it grapples (MM p.197)")
    void kraken() {
        MonsterEffects.Lasting lasting = step("Kraken", "MM", "Bite").riders().lasting();
        MonsterTemplates.DamageCondition only = lasting.onlyIf().get("swallowed");
        assertEquals("grappled", only.targetCondition());
        assertTrue(only.byAttacker());
    }

    @Test
    @DisplayName("The giant toad's Swallow is its bite, +4 for 1d10 + 2 piercing and 1d10 poison, against a Medium or "
            + "smaller creature it grapples, swallowing one at a time (MM p.329); the hand-built action is kept to "
            + "restore")
    void giantToad() {
        JsonNode swallow = action("Giant Toad", "MM", "Swallow");
        assertEquals("NONE", swallow.path("_riderReplaced").path("deliveryMethod").asString());
        assertTrue(swallow.path("targetGrappled").asBoolean());
        MonsterEffects.Step step = step("Giant Toad", "MM", "Swallow");
        Effect.Attack attack = assertInstanceOf(Effect.Attack.class, step.effect().delivery());
        assertEquals(4, attack.bonus());
        assertEquals(2, step.effect().damage().size());
        assertTrue(step.riders().lasting().targetGrappled());
        assertEquals(1, step.riders().lasting().swallows().get("swallowed").limit());
        assertEquals("Medium", step.riders().maxTargetSize().get("swallowed"));
    }

    @Test
    @DisplayName("The tarrasque's Swallow is a bite, no longer 16d6 acid on a failed Constitution save (MM p.286)")
    void tarrasque() {
        MonsterEffects.Step step = step("Tarrasque", "MM", "Swallow");
        assertInstanceOf(Effect.Attack.class, step.effect().delivery());
        assertEquals("4d12+10", action("Tarrasque", "MM", "Swallow").path("effects").get(0).path("damageDice")
                .asString());
        assertEquals(new MonsterTemplates.Swallow(null, 60, 20, 30),
                step.riders().lasting().swallows().get("swallowed"));
    }

    @Test
    @DisplayName("The gelatinous cube's Engulf: 3d6 acid and engulfed on a failed DEX save, escaped with a DC 12 "
            + "Strength check, restrained while engulfed and 6d6 acid at the start of the cube's turns (MM p.242)")
    void gelatinousCube() {
        MonsterEffects.Step step = step("Gelatinous Cube", "MM", "Engulf");
        assertInstanceOf(Effect.Save.class, step.effect().delivery());
        assertEquals(List.of("engulfed", "restrained"), step.effect().conditions());
        assertEquals(12, step.effect().conditionEnds().get("engulfed").escapeDc());
        assertEquals("Large", step.riders().maxTargetSize().get("engulfed"));
        assertEquals("6d6", step.riders().lasting().parts().getFirst().damageDice());
    }

    @Test
    @DisplayName("The cockatrice's bite: a DC 11 CON save guards the restraint and the count, whose failure "
            + "petrifies for 24 hours (MM p.42)")
    void cockatrice() {
        MonsterEffects.Step step = step("Cockatrice", "MM", "Bite");
        Effect.HitSave save = assertInstanceOf(Effect.Attack.class, step.effect().delivery()).hitSave();
        assertEquals(Ability.CON, save.ability());
        assertEquals(11, save.dc());
        assertEquals(List.of("restrained"), save.conditions());
        MonsterTemplates.Part counted = step.riders().lasting().parts().getFirst();
        assertEquals("COUNTED_SAVES", counted.effectType());
        assertEquals("petrified", counted.counted().failureCondition());
        assertEquals("Petrified for 24 hours.", counted.counted().onFailures());
        assertNull(step.effect().conditionEnds().get("restrained"), "no escape DC or repeat save of its own");
    }

    @Test
    @DisplayName("The gorgon's breath and the undercity medusa's gaze count saves too, the gaze petrifying at once "
            + "on a save failed by 5 (MM p.171, GGR p.226)")
    void gorgonAndMedusa() {
        MonsterTemplates.Part breath = step("Gorgon", "MM", "Petrifying Breath").riders().lasting().parts()
                .getFirst();
        assertEquals(13, breath.saveToEndDc());
        assertNull(breath.counted().failBy());
        MonsterTemplates.Part gaze = step("Undercity Medusa", "GGR", "Petrifying Gaze").riders().lasting().parts()
                .getFirst();
        assertEquals(5, gaze.counted().failBy());
    }

    @Test
    @DisplayName("The shadow's Strength Drain lowers Strength by 1d4 until a short or long rest, killing at 0, and a "
            + "humanoid it kills rises later as a shadow (MM p.269)")
    void shadow() {
        List<MonsterTemplates.Part> parts = step("Shadow", "MM", "Strength Drain").riders().lasting().parts();
        MonsterTemplates.Part drain = parts.get(0);
        assertEquals("ABILITY_REDUCTION", drain.effectType());
        assertEquals("STR", drain.ability());
        assertEquals("1d4", drain.damageDice());
        assertTrue(drain.diesAtZero());
        assertEquals("SHORT_REST", drain.until());
        MonsterTemplates.OnKill rises = parts.get(1).onKill();
        assertEquals("Shadow", rises.form());
        assertFalse(rises.immediately());
        assertFalse(action("Shadow", "MM", "Strength Drain").has("dmNote"));
    }

    @Test
    @DisplayName("The giant spider's poison: stable but poisoned for an hour and paralyzed while poisoned, when the "
            + "poison drops the target to 0 (MM p.328)")
    void giantSpider() {
        MonsterTemplates.Part atZero = step("Giant Spider", "MM", "Bite").riders().lasting().parts().getFirst();
        assertEquals("poison", atZero.damageType());
        assertEquals(new MonsterTemplates.AtZero(false, true, "poisoned", 600, List.of("paralyzed")), atZero.atZero());
    }

    @Test
    @DisplayName("The ghost's Possession: a humanoid's failed DC 13 CHA save, immune for 24 hours after (MM p.147)")
    void ghost() {
        MonsterEffects.Step step = step("Ghost", "MM", "Possession");
        Effect.Save save = assertInstanceOf(Effect.Save.class, step.effect().delivery());
        assertEquals(Ability.CHA, save.ability());
        assertEquals(13, save.dc());
        assertEquals(List.of("possessed"), step.effect().conditions());
        assertEquals(List.of("humanoid"), step.riders().lasting().targetTypes());
        assertEquals("for 24 hours", step.riders().lasting().immuneAfterSave());
        assertEquals("POSSESSION", step.riders().lasting().parts().getFirst().effectType());
    }
}
