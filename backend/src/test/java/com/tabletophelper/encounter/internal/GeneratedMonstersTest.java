package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 168 bestiary monsters that had actions but no entry at all in monster-action-definitions.json, which
 * generate_monster_actions.py now gives a whole entry from their stat blocks (D205): every monster the bestiary loads
 * with actions, bonus actions or reactions has an entry, or is one of the 20 creatures whose numbers scale with their
 * caster, and the iconic ones read as their stat blocks do.
 */
class GeneratedMonstersTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final String BESTIARY = "classpath:data/5etools/bestiary/bestiary-*.json";
    private static final String ADDED = "_entryFromStatBlock";
    private static final int ADDED_MONSTERS = 168;

    /**
     * The stat blocks with actions that have no entry, and why (D205). The spell spirits take their actions from
     * summon-spirits.json when summoned (D182); every one of these has numbers that scale with whoever summoned it, "your
     * spell attack modifier to hit" or "+ PB", so its attacks have no bonus to read.
     */
    private static final Map<String, String> NO_ENTRY = excluded();

    private static Map<String, String> excluded() {
        Map<String, String> reasons = new TreeMap<>();
        String spirit = "a spell's spirit, scaled by the spell's level and its caster (summonedBySpell; D182)";
        for (String name : List.of("Aberrant Spirit", "Bestial Spirit", "Celestial Spirit", "Construct Spirit",
                "Elemental Spirit", "Fey Spirit", "Fiendish Spirit", "Shadow Spirit", "Undead Spirit")) {
            reasons.put(key(name, "TCE"), spirit);
        }
        reasons.put(key("Draconic Spirit", "FTD"), spirit);
        reasons.put(key("Reaper Spirit", "BMT"), spirit);
        String companion = "a class feature's companion, scaled by its owner's proficiency bonus or spellcasting "
                + "(summonedByClass)";
        for (String name : List.of("Beast of the Land", "Beast of the Sea", "Beast of the Sky", "Dancing Item",
                "Homunculus Servant", "Steel Defender", "Wildfire Spirit")) {
            reasons.put(key(name, "TCE"), companion);
        }
        reasons.put(key("Drake Companion", "FTD"), companion);
        reasons.put(key("Deck Defender", "BMT"), "its statistics come from the character who drew the Knight card, "
                + "\"PB + 3 to hit\" (BMT)");
        return reasons;
    }

    private static final List<JsonNode> definitions = new ArrayList<>();
    // Stat blocks by "name|source", the first of each as the bestiary loads them (MonsterLoader skips _copy entries)
    private static final Map<String, JsonNode> statBlocks = new LinkedHashMap<>();

    private final MonsterEffects monsterEffects = new MonsterEffects(new CombatFixture.ScriptedDice());

    @BeforeAll
    static void readTheData() throws IOException {
        ObjectMapper mapper = JacksonDefaults.mapper();
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            mapper.readTree(in).forEach(definitions::add);
        }
        Resource[] files = new PathMatchingResourcePatternResolver().getResources(BESTIARY);
        Arrays.sort(files, Comparator.comparing(Resource::getFilename));
        for (Resource file : files) {
            String name = file.getFilename();
            if (name == null || name.contains("fluff") || name.contains("index")) {
                continue;
            }
            try (InputStream in = file.getInputStream()) {
                for (JsonNode monster : mapper.readTree(in).path("monster")) {
                    if (!monster.has("_copy")) {
                        statBlocks.putIfAbsent(key(monster.path("name").asString(), monster.path("source").asString()),
                                monster);
                    }
                }
            }
        }
    }

    // The data is large, and the test JVM runs the other classes after this one (D203)
    @AfterAll
    static void letTheDataGo() {
        definitions.clear();
        statBlocks.clear();
    }

    @Test
    @DisplayName("Every monster the bestiary loads with actions, bonus actions or reactions has an entry, but the 20 "
            + "summoned creatures whose numbers scale with their caster, and the 168 added are marked")
    void everyMonsterWithActionsHasAnEntry() {
        Map<String, JsonNode> byKey = new HashMap<>();
        definitions.forEach(d -> byKey.putIfAbsent(key(d.path("monsterName").asString(), d.path("source").asString()),
                d));
        List<String> without = new ArrayList<>();
        List<String> excludedWithEntry = new ArrayList<>();
        int withActions = 0;
        for (Map.Entry<String, JsonNode> block : statBlocks.entrySet()) {
            JsonNode monster = block.getValue();
            if (!hasActions(monster)) {
                continue;
            }
            withActions++;
            boolean hasEntry = byKey.containsKey(block.getKey());
            if (NO_ENTRY.containsKey(block.getKey())) {
                if (hasEntry) {
                    excludedWithEntry.add(block.getKey());
                }
                // Each exclusion is a summoned creature, but for the deck defender
                assertTrue(monster.has("summonedBySpell") || monster.has("summonedByClass")
                        || block.getKey().equals(key("Deck Defender", "BMT")), block.getKey());
            } else if (!hasEntry) {
                without.add(block.getKey());
            }
        }
        assertEquals(List.of(), without);
        assertEquals(List.of(), excludedWithEntry);
        assertEquals(20, NO_ENTRY.size());
        assertTrue(withActions > 2600, "monsters with actions walked: " + withActions);

        long added = definitions.stream().filter(d -> d.path(ADDED).asBoolean(false)).count();
        assertEquals(ADDED_MONSTERS, added);
        assertTrue(definitions.stream().filter(d -> d.path(ADDED).asBoolean(false))
                .allMatch(d -> d.path("_actionsFromStatBlock").asBoolean(false)));
    }

    @Test
    @DisplayName("Balor (MM p.55): two attacks, its longsword +14 for 3d8 + 8 slashing and 3d8 lightning, its whip "
            + "reaching 30 feet with its pull the DM's, and Teleport the DM's")
    void balor() {
        MonsterTemplates templates = templates("Balor", "MM");
        assertEquals(List.of("Longsword", "Whip"), resolved(templates).stream().map(c -> c.name()).toList());
        MonsterTemplates.Action longsword = templates.find("ACTION", "Longsword").orElseThrow();
        assertEquals(14, longsword.attackBonus());
        assertEquals(List.of("3d8 + 8 slashing", "3d8 lightning"), damage(longsword));
        MonsterTemplates.Action whip = templates.find("ACTION", "Whip").orElseThrow();
        assertEquals(List.of("2d6 + 8 slashing", "3d6 fire"), damage(whip));
        assertTrue(whip.dmNote().contains("pulled up to 25 feet"), whip.dmNote());
        assertFalse(monsterEffects.plan(templates.find("ACTION", "Teleport").orElseThrow(), templates,
                List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE).automated());
    }

    @Test
    @DisplayName("Purple Worm (MM p.255): a bite and its tail stinger; the bite swallows a Large or smaller creature "
            + "on a failed DC 19 Dexterity save, blinded and restrained while swallowed, 6d6 acid at the start of the "
            + "worm's turns, 30 damage from inside calling for a DC 21 Constitution save, 20 feet to leave its corpse")
    void purpleWorm() {
        MonsterTemplates templates = templates("Purple Worm", "MM");
        assertEquals(List.of("Bite", "Tail Stinger"), resolved(templates).stream().map(c -> c.name()).toList());

        MonsterEffects.Step bite = plan(templates, "Bite").steps().getFirst();
        Effect.Attack attack = assertInstanceOf(Effect.Attack.class, bite.effect().delivery());
        assertEquals(14, attack.bonus());
        assertEquals(Ability.DEX, attack.hitSave().ability());
        assertEquals(19, attack.hitSave().dc());
        assertEquals(Set.of("swallowed", "blinded", "restrained"), Set.copyOf(attack.hitSave().conditions()));
        assertEquals("Large", bite.riders().maxTargetSize().get("swallowed"));
        MonsterEffects.Lasting lasting = bite.riders().lasting();
        assertEquals(new MonsterTemplates.Swallow(null, 30, 21, 20), lasting.swallows().get("swallowed"));
        assertEquals(Map.of("blinded", "swallowed", "restrained", "swallowed"), lasting.endsWith());
        MonsterTemplates.Part acid = lasting.parts().getFirst();
        assertEquals("ONGOING_DAMAGE", acid.effectType());
        assertEquals("6d6", acid.damageDice());
        assertEquals("USER", acid.turnOf());

        MonsterTemplates.Action stinger = templates.find("ACTION", "Tail Stinger").orElseThrow();
        MonsterTemplates.Part poison = stinger.effects().get(1);
        assertEquals("12d6", poison.damageDice());
        assertEquals("CON", poison.saveAbility());
        assertEquals(19, poison.saveDc());
        assertTrue(poison.halfOnSave());
    }

    @Test
    @DisplayName("Iron Golem (MM p.170): \"two melee attacks\" lists its slam and sword twice each, for the DM to skip, "
            + "and its Poison Breath recharges on a 5 or 6, a 15-foot cone DC 19 Constitution save for half of 10d8")
    void ironGolem() {
        MonsterTemplates templates = templates("Iron Golem", "MM");
        assertEquals(List.of("Slam", "Slam", "Sword", "Sword"),
                templates.find("ACTION", "Multiattack").orElseThrow().multiattackComponents());
        MonsterTemplates.Action breath = templates.find("ACTION", "Poison Breath").orElseThrow();
        assertEquals("5-6", breath.recharge());
        assertNotNull(MonsterPools.rechargePool(breath, "Iron Golem", "ACTION"));
        Effect.Save save = assertInstanceOf(Effect.Save.class, plan(templates, "Poison Breath").steps().getFirst()
                .effect().delivery());
        assertEquals(new Effect.Save(Ability.CON, 19, true), save);
        assertEquals(List.of("10d8 poison"), damage(breath));
    }

    @Test
    @DisplayName("Marilith (MM p.61): seven attacks, six longswords and its tail, which grapples a Medium or smaller "
            + "creature (escape DC 19) and restrains it while it does; its Parry is the DM's")
    void marilith() {
        MonsterTemplates templates = templates("Marilith", "MM");
        List<String> names = resolved(templates).stream().map(c -> c.name()).toList();
        assertEquals(List.of("Longsword", "Longsword", "Longsword", "Longsword", "Longsword", "Longsword", "Tail"),
                names);
        MonsterEffects.Step tail = plan(templates, "Tail").steps().getFirst();
        assertEquals(19, tail.effect().conditionEnds().get("grappled").escapeDc());
        assertEquals("Medium", tail.riders().maxTargetSize().get("grappled"));
        assertEquals(Map.of("restrained", "grappled"), tail.riders().lasting().endsWith());
        assertFalse(monsterEffects.plan(templates.find("REACTION", "Parry").orElseThrow(), templates,
                List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE).automated());
    }

    @Test
    @DisplayName("Roc (MM p.260): a beak and talons; the talons grapple (escape DC 19), restrain while they do, and "
            + "can't be used on another target meanwhile")
    void roc() {
        MonsterTemplates templates = templates("Roc", "MM");
        assertEquals(List.of("Beak", "Talons"), resolved(templates).stream().map(c -> c.name()).toList());
        assertEquals(List.of("4d8 + 9 piercing"), damage(templates.find("ACTION", "Beak").orElseThrow()));
        MonsterEffects.Step talons = plan(templates, "Talons").steps().getFirst();
        assertEquals(19, talons.effect().conditionEnds().get("grappled").escapeDc());
        assertEquals(1, talons.riders().lasting().grappleLimit());
        assertTrue(talons.riders().lasting().grappleLimitBlocks());
    }

    @Test
    @DisplayName("The behir's and remorhaz's Swallow is their own bite against a Medium or smaller creature they "
            + "grapple (MM p.25, p.258), no longer a Constitution save")
    void swallowsAreBites() {
        MonsterEffects.Step behir = plan(templates("Behir", "MM"), "Swallow").steps().getFirst();
        assertEquals(10, assertInstanceOf(Effect.Attack.class, behir.effect().delivery()).bonus());
        assertTrue(behir.riders().lasting().targetGrappled());
        assertEquals(new MonsterTemplates.Swallow(1, 30, 14, 15), behir.riders().lasting().swallows().get("swallowed"));
        assertEquals("Medium", behir.riders().maxTargetSize().get("swallowed"));

        MonsterEffects.Step remorhaz = plan(templates("Remorhaz", "MM"), "Swallow").steps().getFirst();
        assertEquals(11, assertInstanceOf(Effect.Attack.class, remorhaz.effect().delivery()).bonus());
        assertEquals(2, remorhaz.effect().damage().size());
        assertEquals(new MonsterTemplates.Swallow(null, 30, 15, 15),
                remorhaz.riders().lasting().swallows().get("swallowed"));
    }

    @Test
    @DisplayName("The horned devil's tail (MM p.74): an infernal wound losing 3d6 hit points at the start of each of "
            + "the target's turns on a failed DC 17 Constitution save, passing over constructs and undead")
    void hornedDevil() {
        MonsterTemplates templates = templates("Horned Devil", "MM");
        assertEquals(List.of("Fork", "Fork", "Tail"),
                templates.find("ACTION", "Multiattack").orElseThrow().multiattackComponents());
        MonsterTemplates.Part wound = templates.find("ACTION", "Tail").orElseThrow().effects().get(1);
        assertEquals("ONGOING_DAMAGE", wound.effectType());
        assertEquals("3d6", wound.damageDice());
        assertEquals(17, wound.saveDc());
        assertEquals("CON", wound.saveAbility());
        assertEquals(List.of("construct", "undead"), wound.exemptTypes());
        MonsterEffects.Step tail = plan(templates, "Tail").steps().getFirst();
        assertEquals(Ability.CON, assertInstanceOf(Effect.Attack.class, tail.effect().delivery()).hitSave().ability());
    }

    @Test
    @DisplayName("The wider multiattack reading: a name in the singular (\"four Claw attacks\" for Claws), a last word "
            + "(\"its stinger\"), a count before a list, and \"each of which it can replace\"")
    void widerMultiattacks() {
        assertEquals(List.of("Bite", "Claws", "Claws", "Claws", "Claws"), components("Dire Troll", "MPMM"));
        assertEquals(List.of("Grinding Jaws", "Tail Stinger"), components("Bore Worm", "WDMM"));
        assertEquals(List.of("Longsword", "Longsword", "Longsword", "Longbow", "Longbow", "Longbow"),
                components("Naergoth Bladelord", "RoT"));
        assertEquals(List.of("Tentacle", "Tentacle"), components("Young Kraken", "LR"));
        // "as many Bite attacks as it has heads" is the DM's, as the hand-built hydra's is
        MonsterTemplates.Action hydra = templates("Ironscale Hydra", "MOT").find("ACTION", "Multiattack")
                .orElseThrow();
        assertTrue(hydra.multiattackComponents().isEmpty());
        assertNotNull(hydra.multiattackNote());
    }

    private List<String> components(String monster, String source) {
        return templates(monster, source).find("ACTION", "Multiattack").orElseThrow().multiattackComponents();
    }

    private List<MonsterEffects.MultiattackComponent> resolved(MonsterTemplates templates) {
        MonsterEffects.Multiattack multiattack = monsterEffects.multiattack(
                templates.find("ACTION", "Multiattack").orElseThrow(), templates, MonsterEffects.Overrides.NONE);
        assertTrue(multiattack.readable(), multiattack::unreadable);
        return multiattack.components();
    }

    private MonsterEffects.MonsterAction plan(MonsterTemplates templates, String action) {
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find("ACTION", action).orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        assertTrue(plan.automated(), plan::manualReason);
        return plan;
    }

    private static List<String> damage(MonsterTemplates.Action action) {
        return action.effects().stream().filter(p -> "DAMAGE".equals(p.effectType()))
                .map(p -> p.damageDice() + " " + p.damageType()).toList();
    }

    private static MonsterTemplates templates(String monster, String source) {
        JsonNode definition = definitions.stream()
                .filter(d -> monster.equals(d.path("monsterName").asString())
                        && source.equals(d.path("source").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No definition for " + monster + " (" + source + ")"));
        assertTrue(definition.path(ADDED).asBoolean(false), monster + " is one D205 added");
        return MonsterTemplates.of(definition);
    }

    private static boolean hasActions(JsonNode monster) {
        for (String list : List.of("action", "bonus", "reaction")) {
            if (monster.path(list).isArray() && !monster.path(list).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String key(String name, String source) {
        return name.trim().toLowerCase(Locale.ROOT) + "|" + source.trim().toLowerCase(Locale.ROOT);
    }
}
