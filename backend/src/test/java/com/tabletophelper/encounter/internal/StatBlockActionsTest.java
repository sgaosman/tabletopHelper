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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The action templates backend/scripts/generate_monster_actions.py builds from the stat blocks of the 126 bestiary
 * monsters D193 gave spellcasting only (D194), checked against the 5etools stat blocks they come from: every one has
 * an action, every attack's bonus and first damage part are the stat block's, every multiattack names its own actions,
 * and the samples read as their stat blocks do.
 */
class StatBlockActionsTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final String BESTIARY = "classpath:data/5etools/bestiary/bestiary-*.json";
    private static final String MARK = "_actionsFromStatBlock";
    private static final int GENERATED_MONSTERS = 126;

    // A tag such as {@condition poisoned} or {@spell fly|phb}, read as its first part
    private static final Pattern TAG = Pattern.compile("\\{@\\w+ ([^|}]*)[^}]*}");
    private static final Pattern RECHARGE_TAG = Pattern.compile("\\s*\\{@recharge[^}]*}");
    private static final Pattern PLAIN_RECHARGE = Pattern.compile("[;\\s]*Recharge \\d(?:\\s*[–-]\\s*6)?");
    // The first damage of a hit: "22 ({@damage 4d6 + 8}) piercing", the dice sometimes untagged
    private static final Pattern HIT_DAMAGE =
            Pattern.compile("\\{@h}\\s*\\d+\\s*\\((?:\\{@damage )?(\\d+d\\d+(?:\\s*[+-]\\s*\\d+)?)}?\\)\\s*([a-z]+)");
    private static final Pattern HIT_BONUS = Pattern.compile("\\{@hit (-?\\d+)}");

    private static final List<JsonNode> generated = new ArrayList<>();
    // Stat blocks by "name|source", the first of each as the bestiary loads them
    private static final Map<String, JsonNode> statBlocks = new HashMap<>();

    private final MonsterEffects monsterEffects = new MonsterEffects(new CombatFixture.ScriptedDice());

    @BeforeAll
    static void readTheData() throws IOException {
        ObjectMapper mapper = JacksonDefaults.mapper();
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            for (JsonNode definition : mapper.readTree(in)) {
                if (definition.path(MARK).asBoolean(false)) {
                    generated.add(definition);
                }
            }
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
        generated.clear();
        statBlocks.clear();
    }

    @Test
    @DisplayName("All 126 monsters D193 gave spellcasting only now have at least one action, and every entry fits the "
            + "template records")
    void everyGeneratedMonsterHasAnAction() {
        assertEquals(GENERATED_MONSTERS, generated.size());
        List<String> without = new ArrayList<>();
        for (JsonNode definition : generated) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            assertNull(templates.unreadable(), () -> name(definition) + ": " + templates.unreadable());
            assertNotNull(templates.spellcasting(), name(definition));
            if (templates.actions().isEmpty()) {
                without.add(name(definition));
            }
        }
        assertEquals(List.of(), without);
    }

    @Test
    @DisplayName("Every generated attack's bonus and first damage dice and type are the stat block's")
    void everyAttackMatchesItsStatBlock() {
        List<String> mismatches = new ArrayList<>();
        int attacks = 0;
        for (JsonNode definition : generated) {
            JsonNode statBlock = statBlocks.get(key(definition.path("monsterName").asString(),
                    definition.path("source").asString()));
            assertNotNull(statBlock, name(definition));
            for (String[] lists : new String[][]{{"actions", "action"}, {"bonusActions", "bonus"},
                    {"reactions", "reaction"}}) {
                for (JsonNode action : definition.path(lists[0])) {
                    if (!"ATTACK_ROLL".equals(action.path("deliveryMethod").asString())) {
                        continue;
                    }
                    attacks++;
                    String text = statBlockText(statBlock.path(lists[1]), action.path("name").asString());
                    String what = name(definition) + " " + action.path("name").asString();
                    if (text == null) {
                        mismatches.add(what + ": not in the stat block");
                        continue;
                    }
                    Matcher bonus = HIT_BONUS.matcher(text);
                    if (!bonus.find() || Integer.parseInt(bonus.group(1)) != action.path("attackBonus").asInt()) {
                        mismatches.add(what + ": attack bonus " + action.path("attackBonus").asInt());
                    }
                    Matcher damage = HIT_DAMAGE.matcher(text);
                    JsonNode first = action.path("effects").path(0);
                    if (damage.find() && (!damage.group(1).equals(first.path("damageDice").asString())
                            || !damage.group(2).equals(first.path("damageType").asString()))) {
                        mismatches.add(what + ": damage " + first.path("damageDice").asString() + " "
                                + first.path("damageType").asString() + ", stat block " + damage.group(1) + " "
                                + damage.group(2));
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(attacks >= 190, "attacks checked: " + attacks);
    }

    @Test
    @DisplayName("Every generated multiattack names only its own actions, so it resolves")
    void everyMultiattackResolves() {
        List<String> unreadable = new ArrayList<>();
        for (JsonNode definition : generated) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            for (MonsterTemplates.Action action : templates.actions()) {
                if (!action.multiattack()) {
                    continue;
                }
                MonsterEffects.Multiattack multiattack =
                        monsterEffects.multiattack(action, templates, MonsterEffects.Overrides.NONE);
                if (!multiattack.readable()) {
                    unreadable.add(name(definition) + ": " + multiattack.unreadable());
                }
            }
        }
        assertEquals(List.of(), unreadable);
    }

    @ParameterizedTest(name = "{0} ({1}) {2}")
    @CsvSource({"Pit Fiend, MM, Bite, 14, 4d6 + 8, piercing", "Pit Fiend, MM, Mace, 14, 2d6 + 8, bludgeoning",
            "Death Knight, MM, Longsword, 11, 1d8 + 5, slashing", "Djinni, MM, Scimitar, 9, 2d6 + 5, slashing",
            "Storm Giant, MM, Rock, 14, 4d12 + 9, bludgeoning", "Rakshasa, MM, Claw, 7, 2d6 + 2, slashing",
            "Efreeti, MM, Hurl Flame, 7, 5d6, fire"})
    @DisplayName("A sample of attacks reads as its stat block")
    void sampleAttacks(String monster, String source, String name, int bonus, String dice, String type) {
        MonsterTemplates templates = MonsterTemplates.of(definition(monster, source));
        MonsterTemplates.Action action = templates.find("ACTION", name).orElseThrow();

        assertEquals("ATTACK_ROLL", action.deliveryMethod());
        assertEquals(bonus, action.attackBonus());
        assertEquals(dice, action.effects().getFirst().damageDice());
        assertEquals(type, action.effects().getFirst().damageType());
        MonsterEffects.MonsterAction plan = monsterEffects.plan(action, templates, List.of(UUID.randomUUID()),
                MonsterEffects.Overrides.NONE);
        assertTrue(plan.automated(), plan::manualReason);
    }

    @Test
    @DisplayName("The pit fiend's multiattack is its bite, claw, mace and tail; its bite poisons on a failed DC 21 "
            + "Constitution save, repeated each turn, with no healing and 6d6 poison each turn while it lasts (D203), "
            + "and its mace adds 6d6 fire")
    void pitFiend() {
        MonsterTemplates templates = MonsterTemplates.of(definition("Pit Fiend", "MM"));
        MonsterTemplates.Action multiattack = templates.find("ACTION", "Multiattack").orElseThrow();
        assertEquals(List.of("Bite", "Claw", "Mace", "Tail"), multiattack.multiattackComponents());

        MonsterEffects.MonsterAction bite = monsterEffects.plan(templates.find("ACTION", "Bite").orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        Effect.Attack attack = assertInstanceOf(Effect.Attack.class, bite.steps().getFirst().effect().delivery());
        assertEquals(new Effect.HitSave(Ability.CON, 21, List.of("poisoned"), List.of(), false,
                List.of("can't regain hit points", "6d6 poison damage each turn")), attack.hitSave());
        assertNotNull(bite.steps().getFirst().effect().conditionEnds().get("poisoned").repeatSave());

        MonsterTemplates.Action mace = templates.find("ACTION", "Mace").orElseThrow();
        assertEquals(List.of("2d6 + 8 bludgeoning", "6d6 fire"),
                mace.effects().stream().map(p -> p.damageDice() + " " + p.damageType()).toList());
    }

    @Test
    @DisplayName("The death knight's longsword is versatile, its Hellfire Orb a 1/Day DC 18 Dexterity save for half "
            + "in a 20-foot sphere, and its Parry the DM's")
    void deathKnight() {
        JsonNode definition = definition("Death Knight", "MM");
        JsonNode longsword = action(definition, "actions", "Longsword");
        assertEquals("1d10 + 5", longsword.path("versatile").path("twoHandedDamageDice").asString());
        JsonNode orb = action(definition, "actions", "Hellfire Orb (1/Day)");
        assertEquals(1, orb.path("usesPerDay").asInt());
        assertEquals("AOE_SPHERE", orb.path("targetType").asString());
        assertEquals(20, orb.path("aoeSize").asInt());

        MonsterTemplates templates = MonsterTemplates.of(definition);
        MonsterTemplates.Action hellfire = templates.find("ACTION", "Hellfire Orb (1/Day)").orElseThrow();
        assertEquals(new LimitedUsage(1, "longRest"), LimitedUsage.of(hellfire).orElseThrow());
        Effect.Save save = assertInstanceOf(Effect.Save.class, monsterEffects.plan(hellfire, templates,
                List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE).steps().getFirst().effect().delivery());
        assertEquals(new Effect.Save(Ability.DEX, 18, true), save);

        MonsterEffects.MonsterAction parry = monsterEffects.plan(templates.find("REACTION", "Parry").orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        assertFalse(parry.automated());
    }

    @Test
    @DisplayName("The storm giant's Lightning Strike recharges on a 5 or 6 and is a DC 17 Dexterity save for half of "
            + "12d8 lightning")
    void stormGiant() {
        MonsterTemplates templates = MonsterTemplates.of(definition("Storm Giant", "MM"));
        MonsterTemplates.Action strike = templates.find("ACTION", "Lightning Strike").orElseThrow();

        assertEquals("5-6", strike.recharge());
        assertNotNull(MonsterPools.rechargePool(strike, "Storm Giant", "ACTION"));
        assertEquals("SAVING_THROW", strike.deliveryMethod());
        assertEquals(17, strike.saveDc());
        assertEquals("DEX", strike.saveAbility());
        assertTrue(strike.halfOnSave());
        assertEquals("12d8", strike.effects().getFirst().damageDice());
    }

    // The raw text of the stat-block entry this generated name comes from, or null
    private static String statBlockText(JsonNode entries, String generatedName) {
        for (JsonNode entry : entries) {
            if (cleanName(entry.path("name").asString()).equals(generatedName)) {
                StringBuilder text = new StringBuilder();
                entry.path("entries").forEach(e -> text.append(e.isString() ? e.asString() : "").append(' '));
                return text.toString();
            }
        }
        return null;
    }

    private static String cleanName(String name) {
        String cleaned = RECHARGE_TAG.matcher(name).replaceAll("");
        Matcher tag = TAG.matcher(cleaned);
        while (tag.find()) {
            cleaned = tag.replaceAll("$1");
            tag = TAG.matcher(cleaned);
        }
        cleaned = PLAIN_RECHARGE.matcher(cleaned).replaceAll("");
        return cleaned.replaceAll("[;\\s]+\\)", ")").replace("()", "").trim();
    }

    private static JsonNode action(JsonNode definition, String list, String name) {
        for (JsonNode action : definition.path(list)) {
            if (name.equals(action.path("name").asString())) {
                return action;
            }
        }
        throw new AssertionError(name(definition) + " has no " + name);
    }

    private static JsonNode definition(String monster, String source) {
        return generated.stream()
                .filter(d -> monster.equals(d.path("monsterName").asString())
                        && source.equals(d.path("source").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No generated definition for " + monster + " (" + source + ")"));
    }

    private static String key(String name, String source) {
        return name.trim().toLowerCase(Locale.ROOT) + "|" + source.trim().toLowerCase(Locale.ROOT);
    }

    private static String name(JsonNode definition) {
        return definition.path("monsterName").asString() + " (" + definition.path("source").asString() + ")";
    }
}
