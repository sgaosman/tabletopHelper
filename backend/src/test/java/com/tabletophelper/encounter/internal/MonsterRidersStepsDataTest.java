package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.OngoingModifier;
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
 * What generate_monster_actions.py reads for steps 3 to 5 of the rider survey (D203), on the shipped data: the lasting
 * riders, the conditions tied to another, the creature types a rider passes over, the grapple limits and the immunity
 * a successful save gives, with their counts pinned, and the samples the rules came from.
 */
class MonsterRidersStepsDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final List<String> LISTS = List.of("actions", "bonusActions", "reactions", "legendaryActions");
    private static final Set<String> LASTING = Set.of("HP_MAX_REDUCTION", "LIFE_DRAIN", "EXHAUSTION", "MODIFIER",
            "ONGOING_DAMAGE");
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

    private MonsterEffects.Step step(String monster, String source, String action) {
        MonsterTemplates templates = MonsterTemplates.of(definition(monster, source));
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find("ACTION", action).orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        assertTrue(plan.automated(), plan::manualReason);
        return plan.steps().getFirst();
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
    @DisplayName("The counts the rider pass and the generator give: 43 hit point maximums, 25 life drains, 5 "
            + "exhaustions, 57 Ongoing Modifiers, 30 damages each turn, 118 tied conditions, 5 condition exemptions, "
            + "149 grapple limits (64 refusing another target), 96 immunities after a save and 8 action exemptions")
    void counts() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Found found : actions()) {
            JsonNode action = found.action();
            if (action.path("grappleLimit").isNumber()) {
                counts.merge("grappleLimit", 1, Integer::sum);
                if (action.path("grappleLimitBlocks").asBoolean(false)) {
                    counts.merge("grappleLimitBlocks", 1, Integer::sum);
                }
            }
            if (action.path("immuneAfterSave").isString()) {
                counts.merge("immuneAfterSave", 1, Integer::sum);
            }
            if (action.path("exemptTypes").isArray()) {
                counts.merge("actionExempt", 1, Integer::sum);
            }
            for (JsonNode part : action.path("effects")) {
                String type = part.path("effectType").asString("");
                if (LASTING.contains(type)) {
                    counts.merge(type, 1, Integer::sum);
                }
                if ("CONDITION".equals(type) && part.path("endsWith").isString()) {
                    counts.merge("endsWith", 1, Integer::sum);
                }
                if ("CONDITION".equals(type) && part.path("exemptTypes").isArray()) {
                    counts.merge("conditionExempt", 1, Integer::sum);
                }
            }
        }
        assertEquals(Map.ofEntries(Map.entry("EXHAUSTION", 5), Map.entry("HP_MAX_REDUCTION", 43),
                Map.entry("LIFE_DRAIN", 25), Map.entry("MODIFIER", 57), Map.entry("ONGOING_DAMAGE", 30),
                Map.entry("actionExempt", 8), Map.entry("conditionExempt", 5), Map.entry("endsWith", 118),
                Map.entry("grappleLimit", 149), Map.entry("grappleLimitBlocks", 64),
                Map.entry("immuneAfterSave", 96)), counts);
    }

    @Test
    @DisplayName("Every Ongoing Modifier a hit leaves is a kind the engine knows, with a mode where its kind needs one")
    void modifierKindsKnown() {
        List<String> unknown = new ArrayList<>();
        for (Found found : actions()) {
            for (JsonNode part : found.action().path("effects")) {
                if (!"MODIFIER".equals(part.path("effectType").asString(""))) {
                    continue;
                }
                String kind = part.path("modifier").path("kind").asString("");
                try {
                    OngoingModifier.Kind known = OngoingModifier.Kind.valueOf(kind);
                    if (known.name().endsWith("_MODE") && !part.path("modifier").path("mode").isString()) {
                        unknown.add(found.definition().path("monsterName").asString() + " " + kind + " has no mode");
                    }
                } catch (IllegalArgumentException e) {
                    unknown.add(found.definition().path("monsterName").asString() + " " + kind);
                }
            }
        }
        assertEquals(List.of(), unknown);
    }

    @Test
    @DisplayName("Every condition or rider tied to another names one its own action gives")
    void linksNameTheirAction() {
        List<String> dangling = new ArrayList<>();
        for (Found found : actions()) {
            List<String> given = new ArrayList<>();
            found.action().path("effects").forEach(part -> given.add(part.path("condition").asString("")));
            for (JsonNode part : found.action().path("effects")) {
                String anchor = part.path("endsWith").asString(null);
                if (anchor != null && !given.contains(anchor)) {
                    dangling.add(found.definition().path("monsterName").asString() + " "
                            + found.action().path("name").asString() + " " + anchor);
                }
            }
        }
        assertEquals(List.of(), dangling);
    }

    @Test
    @DisplayName("Every attack with a lasting rider still resolves, its saves agreeing")
    void lastingRidersResolve() {
        List<String> manual = new ArrayList<>();
        for (Found found : actions()) {
            JsonNode action = found.action();
            boolean lasting = false;
            for (JsonNode part : action.path("effects")) {
                lasting |= LASTING.contains(part.path("effectType").asString(""));
            }
            if (!lasting || !"ATTACK_ROLL".equals(action.path("deliveryMethod").asString(""))
                    || !action.path("automatable").asBoolean(true)) {
                continue;
            }
            MonsterTemplates templates = MonsterTemplates.of(found.definition());
            MonsterTemplates.Action read = templates.component(action.path("name").asString()).orElseThrow();
            MonsterEffects.MonsterAction plan = monsterEffects.plan(read, templates, List.of(UUID.randomUUID()),
                    MonsterEffects.Overrides.NONE);
            if (!plan.automated()) {
                manual.add(found.definition().path("monsterName").asString() + " " + read.name() + ": "
                        + plan.manualReason());
            }
        }
        assertEquals(List.of(), manual);
    }

    @Test
    @DisplayName("The wight's Life Drain: its CON save guards a hit point maximum lowered until a long rest, and the "
            + "wight's death at 0 (MM p.300)")
    void wight() {
        MonsterEffects.Step step = step("Wight", "MM", "Life Drain");
        Effect.HitSave save = assertInstanceOf(Effect.Attack.class, step.effect().delivery()).hitSave();
        assertEquals(new Effect.HitSave(Ability.CON, 13, List.of(), List.of(), false,
                List.of("hit point maximum reduced")), save);
        MonsterTemplates.Part part = step.riders().lasting().parts().getFirst();
        assertEquals("LONG_REST", part.until());
        assertTrue(part.diesAtZero());
    }

    @Test
    @DisplayName("A vampire's bite lowers the maximum by its necrotic damage and the vampire regains as much (MM "
            + "p.297)")
    void vampire() {
        List<String> types = step("Vampire", "MM", "Bite (Bat or Vampire Form Only)").riders().lasting().parts()
                .stream().map(p -> p.effectType() + " " + p.damageType()).toList();
        assertEquals(List.of("HP_MAX_REDUCTION necrotic", "LIFE_DRAIN necrotic"), types);
    }

    @Test
    @DisplayName("The giant constrictor snake's restraint lasts while its grapple does, which holds one creature, and "
            + "the snake can't constrict another (MM p.324)")
    void constrictor() {
        MonsterEffects.Step step = step("Giant Constrictor Snake", "MM", "Constrict");
        assertEquals(Map.of("restrained", "grappled"), step.riders().lasting().endsWith());
        assertEquals(1, step.riders().lasting().grappleLimit());
        assertTrue(step.riders().lasting().grappleLimitBlocks());
        // The restraint keeps no escape of its own: escaping the grapple ends it
        assertNull(step.effect().conditionEnds().get("restrained"));
        assertEquals(16, step.effect().conditionEnds().get("grappled").escapeDc());
    }

    @Test
    @DisplayName("The chain devil's chain grapples only while the devil grapples no one, restrains while it does, "
            + "and deals 2d6 piercing at the start of each turn then, no longer on the hit (MM p.72)")
    void chainDevil() {
        MonsterEffects.Step step = step("Chain Devil", "MM", "Chain");
        assertEquals(1, step.riders().lasting().grappleLimit());
        assertFalse(step.riders().lasting().grappleLimitBlocks());
        assertEquals(List.of("2d6 + 4 slashing"), step.effect().damage().stream()
                .map(p -> p.dice() + " " + p.type()).toList());
        MonsterTemplates.Part each = step.riders().lasting().parts().getFirst();
        assertEquals(List.of("ONGOING_DAMAGE", "2d6", "piercing", "grappled"),
                List.of(each.effectType(), each.damageDice(), each.damageType(), each.endsWith()));
    }

    @Test
    @DisplayName("The carrion crawler's paralysis lasts while its poison does, and the bearded devil's beard bars "
            + "healing then (MM p.37, p.70)")
    void whilePoisoned() {
        assertEquals(Map.of("paralyzed", "poisoned"),
                step("Carrion Crawler", "MM", "Tentacle").riders().lasting().endsWith());
        MonsterTemplates.Part ban = step("Bearded Devil", "MM", "Beard").riders().lasting().parts().getFirst();
        assertEquals(List.of("NO_HEALING", "poisoned"), List.of(ban.modifier().kind(), ban.endsWith()));
    }

    @Test
    @DisplayName("The ghoul's paralysis passes over elves and undead (MM p.148)")
    void ghoul() {
        assertEquals(Map.of("paralyzed", List.of("elf", "undead")),
                step("Ghoul", "MM", "Claw").riders().lasting().exemptTypes());
    }

    @Test
    @DisplayName("The stirge attaches and drains 1d4 + 3 at the start of its own turns, 10 at most (MM p.284)")
    void stirge() {
        MonsterTemplates.Part drain = step("Stirge", "MM", "Blood Drain").riders().lasting().parts().getFirst();
        assertEquals(List.of("ONGOING_DAMAGE", "1d4 + 3", "USER", "attached"),
                List.of(drain.effectType(), drain.damageDice(), drain.turnOf(), drain.endsWith()));
        assertEquals(10, drain.total());
        assertNull(drain.damageType());
    }

    @Test
    @DisplayName("An adult red dragon's Frightful Presence leaves a creature that saved immune for the next 24 hours, "
            + "and a banshee's wail passes over constructs and undead (MM p.98, p.23)")
    void saveActions() {
        assertEquals("for the next 24 hours",
                step("Adult Red Dragon", "MM", "Frightful Presence").riders().lasting().immuneAfterSave());
        MonsterTemplates templates = MonsterTemplates.of(definition("Banshee", "MM"));
        assertEquals(List.of("construct", "undead"), templates.actions().stream()
                .filter(a -> a.name().startsWith("Wail")).findFirst().orElseThrow().exemptTypes());
    }
}
