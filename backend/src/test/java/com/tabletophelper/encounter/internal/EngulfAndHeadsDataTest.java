package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped data generate_monster_actions.py gives the shambling mound, the hydras and the other actions that deal
 * damage each turn behind a save (D206).
 */
class EngulfAndHeadsDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    private static final List<JsonNode> definitions = new ArrayList<>();
    private final MonsterEffects monsterEffects = new MonsterEffects(new com.tabletophelper.rules.Rng() {
        @Override
        public int roll(int sides) {
            return 1;
        }
    });

    @BeforeAll
    static void load() throws IOException {
        ObjectMapper mapper = JacksonDefaults.builder().build();
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            mapper.readTree(in).forEach(definitions::add);
        }
    }

    // The definitions are large; the class lets them go once done, as the other data tests do
    @AfterAll
    static void release() {
        definitions.clear();
    }

    private static MonsterTemplates templates(String monster, String source) {
        return MonsterTemplates.of(definitions.stream()
                .filter(d -> monster.equals(d.path("monsterName").asString())
                        && source.equals(d.path("source").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No definition for " + monster + " (" + source + ")")));
    }

    @Test
    @DisplayName("The shambling mound's Engulf: no roll, only on a Medium or smaller creature it grapples, one at a "
            + "time, the grapple kept and no cover, blinded and restrained while engulfed, and 2d8 + 4 bludgeoning "
            + "behind a DC 14 Constitution save at the start of the mound's turns (MM p.270)")
    void shamblingMoundEngulf() {
        MonsterTemplates mound = templates("Shambling Mound", "MM");
        MonsterEffects.Step step = monsterEffects.plan(mound.find("ACTION", "Engulf").orElseThrow(), mound,
                List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE).steps().getFirst();
        assertInstanceOf(Effect.Automatic.class, step.effect().delivery());
        assertTrue(step.effect().damage().isEmpty());
        assertEquals(List.of("engulfed", "blinded", "restrained"), step.effect().conditions());
        MonsterEffects.Lasting lasting = step.riders().lasting();
        assertTrue(lasting.targetGrappled());
        assertEquals(new MonsterTemplates.Swallow(1, null, null, null, true, false), lasting.swallows().get("engulfed"));
        assertEquals("Medium", step.riders().maxTargetSize().get("engulfed"));
        assertEquals(Map.of("engulfed", "grappled", "blinded", "engulfed", "restrained", "engulfed"),
                lasting.endsWith());
        MonsterTemplates.Part damage = lasting.parts().getFirst();
        assertEquals("2d8 + 4", damage.damageDice());
        assertEquals(new MonsterTemplates.TurnSave(14, "CON", false), damage.turnSave());
        assertEquals("USER", damage.turnOf());

        MonsterTemplates.OnAllHit follow = mound.find("ACTION", "Multiattack").orElseThrow().onAllHit();
        assertEquals("Slam", follow.attack());
        assertEquals("Engulf", follow.uses());
        assertEquals("grappled", follow.effects().getFirst().condition());
        assertEquals(14, follow.effects().getFirst().escapeDc());
        assertEquals("Medium", follow.effects().getFirst().maxTargetSize());
    }

    @Test
    @DisplayName("Every damage each turn behind a save made each time: the shambling mound's, the boneless's and the "
            + "parasite-infested behir's parasites (halved on a success)")
    void turnSaves() {
        Map<String, MonsterTemplates.TurnSave> found = new TreeMap<>();
        for (JsonNode definition : definitions) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            for (MonsterTemplates.Action action : templates.actions()) {
                for (MonsterTemplates.Part part : action.effects()) {
                    if (part.turnSave() != null) {
                        found.put(definition.path("monsterName").asString() + " " + action.name(), part.turnSave());
                    }
                }
            }
        }
        assertEquals(Map.of("Boneless Crushing Embrace", new MonsterTemplates.TurnSave(13, "STR", false),
                "Parasite-infested Behir Swallow", new MonsterTemplates.TurnSave(19, "CON", true),
                "Shambling Mound Engulf", new MonsterTemplates.TurnSave(14, "CON", false)), found);
    }

    @Test
    @DisplayName("The boneless's Crushing Embrace: attached to a Large or smaller creature it grapples, blinded while "
            + "attached, detached with a DC 13 Strength check (VRGR)")
    void boneless() {
        MonsterTemplates boneless = templates("Boneless", "VRGR");
        MonsterTemplates.Action embrace = boneless.find("ACTION", "Crushing Embrace").orElseThrow();
        assertTrue(embrace.targetGrappled());
        assertEquals("AUTO_HIT", embrace.deliveryMethod());
        MonsterTemplates.Part attached = embrace.effects().getFirst();
        assertEquals("attached", attached.condition());
        assertEquals(13, attached.escapeDc());
        assertEquals("Large", attached.maxTargetSize());
        assertNull(boneless.find("ACTION", "Multiattack").orElseThrow().onAllHit().uses(),
                "the boneless can use Crushing Embrace afterwards, so the DM does");
    }

    @Test
    @DisplayName("Six monsters make a Bite (or Beak) for each head, five heads each by their Multiple Heads trait")
    void heads() {
        Map<String, String> found = new TreeMap<>();
        for (JsonNode definition : definitions) {
            MonsterTemplates.of(definition).heads().ifPresent(heads -> found.put(
                    definition.path("monsterName").asString() + " (" + definition.path("source").asString() + ")",
                    heads.count() + " " + heads.attack()));
        }
        assertEquals(Map.of("Dracohydra (FTD)", "5 Bite", "Goose Mother (MCV4EC)", "5 Beak", "Hydra (MM)", "5 Bite",
                "Ironscale Hydra (MOT)", "5 Bite", "Polukranos (MOT)", "5 Bite", "Snapping Hydra (MCV4EC)", "5 Bite"),
                found);
        // Polukranos' five hand-built Bites give way to its heads, and the Goose Mother keeps its Wing after its Beaks
        MonsterTemplates polukranos = templates("Polukranos", "MOT");
        assertEquals(3, monsterEffects.multiattack(polukranos.find("ACTION", "Multiattack").orElseThrow(), polukranos,
                MonsterEffects.Overrides.NONE, 3).components().size());
        MonsterTemplates goose = templates("Goose Mother", "MCV4EC");
        assertEquals(List.of("Beak", "Beak", "Wing"), monsterEffects.multiattack(
                goose.find("ACTION", "Multiattack").orElseThrow(), goose, MonsterEffects.Overrides.NONE, 2)
                .components().stream().map(MonsterEffects.MultiattackComponent::name).toList());
    }
}
