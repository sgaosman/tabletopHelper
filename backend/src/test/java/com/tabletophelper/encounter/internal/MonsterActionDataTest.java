package com.tabletophelper.encounter.internal;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.Effect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The seeded monster action data, monster-action-definitions.json, as combat reads it (D165). Every entry fits the
 * template records. A condition an attack gives on a hit records no DC without its ability: a grapple's or a
 * restraint's escape DC is an escapeDC, never a save. The bites whose DC is the monster's own save to regurgitate what
 * it swallowed, the Kraken's (MM), the Froghemoth's (VGM, MPMM), the Froghemoth Elder's (QftIS) and Slarkrethel's
 * (SKT), deal their piercing damage with no save for the target and swallow, the DC on their gullet (D204). The
 * Mindwitness's and Ulitharid's tentacles (VGM and MPMM) grapple on every hit, and only the restraint or the stun
 * waits for the Intelligence save their text gives. No saving throw records a DC without its ability: where the text
 * names no single ability, or calls for a check rather than a save, the action is the DM's, with a note.
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
            definitions = JacksonDefaults.mapper().readTree(in);
        }
    }

    // The data is large, and the test JVM runs the other classes after this one (D203)
    @AfterAll
    static void letTheDataGo() {
        definitions = null;
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
    @DisplayName("350 actions, bonus actions, reactions and legendary actions have limited uses the pools count (MM "
            + "p.11): 246 actions, 46 bonus actions, 56 reactions and 2 legendary actions; none also has a d6 recharge")
    void limitedUses() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> alsoRecharge = new ArrayList<>();
        for (JsonNode definition : definitions) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            for (String source : List.of("ACTION", "BONUS", "REACTION", "LEGENDARY")) {
                for (MonsterTemplates.Action action : templates.list(source)) {
                    if (LimitedUsage.of(action).isEmpty()) {
                        continue;
                    }
                    counts.merge(source, 1, Integer::sum);
                    if (MonsterPools.rechargePool(action, "", "ACTION") != null) {
                        alsoRecharge.add(name(definition) + " " + action.name());
                    }
                }
            }
        }
        assertEquals(Map.of("ACTION", 246, "BONUS", 46, "REACTION", 56, "LEGENDARY", 2), counts);
        assertEquals(List.of(), alsoRecharge);
    }

    @Test
    @DisplayName("Every bonus action and reaction is found under its source and plans: 65 of the 266 bonus actions "
            + "and 86 of the 359 reactions resolve automatically, and the rest are logged for the DM (D137, D138)")
    void bonusActionsAndReactions() {
        List<String> missing = new ArrayList<>();
        Map<String, int[]> counts = new LinkedHashMap<>(Map.of("BONUS", new int[2], "REACTION", new int[2]));
        for (JsonNode definition : definitions) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            for (String source : counts.keySet()) {
                for (MonsterTemplates.Action action : templates.list(source)) {
                    if (templates.find(source, action.name()).isEmpty()) {
                        missing.add(name(definition) + " " + source + " " + action.name());
                    }
                    MonsterEffects.MonsterAction plan = monsterEffects.plan(action, templates,
                            List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
                    counts.get(source)[plan.automated() ? 0 : 1]++;
                }
            }
        }

        assertEquals(List.of(), missing);
        // D202 reads the later "has the X condition" wording, so the Death Giant Shrouded One's Frightening Teleport,
        // the Fomorian Noble's Beguiling Presence (bonus actions) and the Shator Warden's Poisonous Slime (a reaction)
        // now give their conditions automatically: 63 and 85 before
        assertArrayEquals(new int[]{65, 201}, counts.get("BONUS"));
        assertArrayEquals(new int[]{86, 273}, counts.get("REACTION"));
    }

    @Test
    @DisplayName("A reaction that changes a hit or its damage after it is resolved, such as each of the 47 Parries and "
            + "19 Uncanny Dodges, is the DM's: it logs its stat block and what to adjust by hand (Q4)")
    void reactionsThatChangeAResolvedHit() {
        int parries = 0;
        int uncannyDodges = 0;
        for (JsonNode definition : definitions) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            for (MonsterTemplates.Action reaction : templates.reactions()) {
                boolean parry = "Parry".equals(reaction.name());
                boolean uncannyDodge = "Uncanny Dodge".equals(reaction.name());
                if (!parry && !uncannyDodge) {
                    continue;
                }
                MonsterEffects.MonsterAction plan = monsterEffects.plan(reaction, templates,
                        List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
                String what = name(definition) + " " + reaction.name();
                assertFalse(plan.automated(), what);
                assertEquals(reaction.dmNote(), plan.manualReason(), what);
                assertTrue(plan.manualReason().endsWith(parry
                        ? "if the higher AC makes it miss, the DM restores its damage and removes anything else the "
                        + "hit did, by hand."
                        : "the DM restores the part this reaction prevents, by hand."), what);
                parries += parry ? 1 : 0;
                uncannyDodges += uncannyDodge ? 1 : 0;
            }
        }
        assertEquals(47, parries);
        assertEquals(19, uncannyDodges);
    }

    @ParameterizedTest(name = "{0} ({1}) {2}")
    @CsvSource({"Autumn Eladrin, MPMM, Foster Peace", "Zuggtmoy, MPMM, Protective Thrall",
            "Kas the Betrayer, VEoR, Parrying Riposte", "Nilbog, VGM, Reversal of Fortune",
            "Borivik Windheim, MaBJoV, Enduring Response", "The Gardener, QftIS, Pacification"})
    @DisplayName("A reaction whose effects misread its text, charming the attacker, dealing damage with no attack or "
            + "later, or knocking out a creature the damage doesn't drop, is the DM's, with a note")
    void misreadReactionsAreTheDms(String monster, String source, String name) {
        MonsterTemplates templates = MonsterTemplates.of(definition(monster, source));
        MonsterTemplates.Action reaction = templates.find("REACTION", name).orElseThrow();

        MonsterEffects.MonsterAction plan = monsterEffects.plan(reaction, templates, List.of(UUID.randomUUID()),
                MonsterEffects.Overrides.NONE);

        assertEquals(List.of(), plan.steps());
        assertTrue(plan.manualReason().contains("by hand"), plan::manualReason);
    }

    @Test
    @DisplayName("No condition an attack gives on a hit records a DC without an ability")
    void noHitConditionHasADcWithoutAnAbility() {
        List<String> found = new ArrayList<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    if (!"ATTACK_ROLL".equals(action.path("deliveryMethod").asString())) {
                        continue;
                    }
                    for (JsonNode effect : action.path("effects")) {
                        if (!"CONDITION".equalsIgnoreCase(effect.path("effectType").asString())) {
                            continue;
                        }
                        boolean ownDc = effect.path("saveDC").isNumber();
                        boolean ownAbility = effect.path("saveAbility").isString();
                        // An escape DC is a check's, never a save's, as a parasite's detach check (D203)
                        boolean actionDcWithoutAbility = action.path("saveDC").isNumber()
                                && !action.path("saveAbility").isString() && !ownDc && !ownAbility
                                && !effect.path("escapeDC").isNumber();
                        if (ownDc && !ownAbility || actionDcWithoutAbility) {
                            found.add(name(definition) + " " + action.path("name").asString() + " "
                                    + effect.path("condition").asString());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    @DisplayName("No saving throw records a DC without an ability")
    void noSaveHasADcWithoutAnAbility() {
        List<String> found = new ArrayList<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    if ("SAVING_THROW".equals(action.path("deliveryMethod").asString())
                            && action.path("saveDC").isNumber() && !action.path("saveAbility").isString()) {
                        found.add(name(definition) + " " + list + " " + action.path("name").asString());
                    }
                }
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    @DisplayName("The Gardener's Vine grapples once on a hit, and its 0 hit point rule is the DM's note")
    void gardenersVine() {
        JsonNode vine = action("The Gardener", "QftIS", "Vine");
        MonsterTemplates templates = MonsterTemplates.of(definition("The Gardener", "QftIS"));
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find("ACTION", "Vine").orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);

        Effect effect = plan.steps().getFirst().effect();
        Effect.Attack attack = assertInstanceOf(Effect.Attack.class, effect.delivery());
        assertNull(attack.hitSave());
        assertEquals(List.of("grappled"), effect.conditions());
        assertEquals(17, effect.conditionEnds().get("grappled").escapeDc());
        assertTrue(vine.path("dmNote").asString().contains("reduced to 0 hit points"));
        assertEquals(List.of(vine.path("dmNote").asString()), plan.notes());
    }

    @Test
    @DisplayName("An action left to the DM gives its note as the reason")
    void manualActionGivesItsNote() {
        JsonNode roar = action("Androsphinx", "MM", "Roar");
        MonsterTemplates templates = MonsterTemplates.of(definition("Androsphinx", "MM"));
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find("ACTION", "Roar").orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);

        assertEquals(List.of(), plan.steps());
        assertEquals(roar.path("dmNote").asString(), plan.manualReason());
    }

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource({"Kraken, MM, 3d8+10", "Froghemoth, VGM, 3d10 + 6", "Froghemoth, MPMM, 3d10 + 6",
            "Froghemoth Elder, QftIS, 3d10 + 7", "Slarkrethel, SKT, 3d8 + 10"})
    @DisplayName("A swallowing bite deals its piercing damage with no save for the target, and swallows (D204)")
    void swallowingBites(String monster, String source, String piercing) {
        JsonNode bite = action(monster, source, "Bite");

        assertFalse(bite.has("saveDC") || bite.has("saveAbility") || bite.has("secondarySaveDC")
                || bite.has("secondarySaveAbility") || bite.has("targetType") || bite.has("aoeSize"), bite::toString);
        assertEquals("DAMAGE", bite.path("effects").get(0).path("effectType").asString());
        assertEquals(piercing, bite.path("effects").get(0).path("damageDice").asString());
        assertEquals("piercing", bite.path("effects").get(0).path("damageType").asString());
        // The regurgitation's Constitution save is the swallower's own, on its gullet (D204)
        JsonNode swallowed = bite.path("effects").get(1);
        assertEquals("swallowed", swallowed.path("condition").asString());
        assertTrue(swallowed.path("swallow").path("regurgitateDC").isNumber(), swallowed::toString);
        assertFalse(swallowed.has("saveDC"));
        assertFalse(bite.has("dmNote"), bite::toString);
        assertTrue(bite.path("_riderNoteReplaced").asString().startsWith("Swallowing is applied by hand. On a hit, "));

        MonsterTemplates templates = MonsterTemplates.of(definition(monster, source));
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find("ACTION", "Bite").orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        Effect effect = plan.steps().getFirst().effect();
        Effect.Attack attack = assertInstanceOf(Effect.Attack.class, effect.delivery());
        assertNull(attack.hitSave());
        assertEquals(List.of("swallowed", "blinded", "restrained"), effect.conditions());
        assertEquals(List.of(), plan.notes());
    }

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource({"Mindwitness, MPMM, 13, restrained, 13", "Ulitharid, MPMM, 14, stunned, 17",
            "Mindwitness, VGM, 13, stunned, 13", "Ulitharid, VGM, 14, stunned, 17"})
    @DisplayName("A tentacle grapples on a hit with its escape DC, and only its other condition waits for an "
            + "Intelligence save")
    void tentacles(String monster, String source, int escapeDc, String condition, int saveDc) {
        MonsterTemplates templates = MonsterTemplates.of(definition(monster, source));
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
            if (name.equals(action.path("name").asString())) {
                return action;
            }
        }
        throw new AssertionError(monster + " (" + source + ") has no " + name);
    }

    private static JsonNode definition(String monster, String source) {
        for (JsonNode definition : definitions) {
            if (monster.equals(definition.path("monsterName").asString())
                    && source.equals(definition.path("source").asString())) {
                return definition;
            }
        }
        throw new AssertionError("No definition for " + monster + " (" + source + ")");
    }

    private static String name(JsonNode definition) {
        return definition.path("monsterName").asString() + " (" + definition.path("source").asString() + ")";
    }
}
