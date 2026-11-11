package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.JsonNode;
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
 * The hit riders generate_monster_actions.py reads from the stat blocks (D202), checked on the shipped data: the
 * generated entries' parsing rules, and what its rider pass adds to the hand-built entries, each a stat-block sentence
 * the samples quote. A hand-built entry keeps its own attack bonus and damage; what the pass adds is marked
 * ("_rider", "_riderKeys", "_riderRemoved", "_riderNote") so a later run takes it away first.
 */
class MonsterRidersDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final List<JsonNode> definitions = new ArrayList<>();

    private final MonsterEffects monsterEffects = new MonsterEffects(new CombatFixture.ScriptedDice());

    @BeforeAll
    static void readTheData() throws IOException {
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            JacksonDefaults.mapper().readTree(in).forEach(definitions::add);
        }
    }

    private static JsonNode definition(String monster, String source) {
        return definitions.stream()
                .filter(d -> monster.equals(d.path("monsterName").asString())
                        && source.equals(d.path("source").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No definition for " + monster + " (" + source + ")"));
    }

    private static JsonNode json(String monster, String source, String action) {
        for (JsonNode each : definition(monster, source).path("actions")) {
            if (action.equals(each.path("name").asString())) {
                return each;
            }
        }
        throw new AssertionError(monster + " has no " + action);
    }

    // The action's one step as planned against one target
    private MonsterEffects.Step step(String monster, String source, String action) {
        MonsterTemplates templates = MonsterTemplates.of(definition(monster, source));
        MonsterEffects.MonsterAction plan = monsterEffects.plan(templates.find("ACTION", action).orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        assertTrue(plan.automated(), plan::manualReason);
        assertEquals(1, plan.steps().size());
        return plan.steps().getFirst();
    }

    private static Effect.HitSave hitSave(MonsterEffects.Step step) {
        return assertInstanceOf(Effect.Attack.class, step.effect().delivery()).hitSave();
    }

    private static List<String> damage(List<Effect.DamagePart> parts) {
        return parts.stream().map(p -> p.dice() + " " + p.type()).toList();
    }

    // ── Step 2: damage a hit's save guards ───────────────────────

    @ParameterizedTest(name = "{0} ({1}) {2}")
    @CsvSource(delimiter = '|', value = {
            "Phase Spider|MM|Bite|1d10 + 2 piercing|4d8 poison|CON|11|true",
            "Giant Spider|MM|Bite|1d8 + 3 piercing|2d8 poison|CON|11|true",
            "Assassin|MM|Shortsword|1d6 + 3 piercing|7d6 poison|CON|15|true",
            "Guardian Naga|MM|Bite|1d8 + 4 piercing|10d8 poison|CON|15|true",
            "Scorpion|MM|Sting|1 piercing|1d8 poison|CON|9|true",
            "Arcanaloth|MM|Claws|2d4 + 3 slashing|3d6 poison|CON|14|true",
            "Green Abishai|MPMM|Fiendish Claw|2d8 + 3 force|3d10 poison|CON|16|false"})
    @DisplayName("Poison dealt only on a failed save is no longer added to every hit: the hit's save guards it, half or "
            + "none on a success as the stat block says")
    void damageOnAFailedSave(String monster, String source, String action, String hit, String guarded,
                             String ability, int dc, boolean half) {
        MonsterEffects.Step step = step(monster, source, action);
        assertEquals(List.of(hit), damage(step.effect().damage()));
        Effect.HitSave save = hitSave(step);
        assertEquals(Ability.valueOf(ability), save.ability());
        assertEquals(dc, save.dc());
        assertEquals(List.of(guarded), damage(save.damage()));
        assertEquals(half, save.halfDamageOnSuccess());
    }

    @Test
    @DisplayName("The phase spider's poisoning and paralysis come only at 0 hit points, so the pass takes them off its "
            + "bite, keeps them for a later run, and notes the rule for the DM")
    void phaseSpiderConditionsAtZeroHitPoints() {
        MonsterEffects.Step step = step("Phase Spider", "MM", "Bite");
        assertEquals(List.of(), step.effect().conditions());
        assertEquals(List.of(), hitSave(step).conditions());
        JsonNode bite = json("Phase Spider", "MM", "Bite");
        assertEquals(2, bite.path("_riderRemoved").size());
        assertTrue(bite.path("dmNote").asString().startsWith("Also on a hit, applied by hand: If the poison damage "
                + "reduces the target to 0 hit points"), bite.path("dmNote").asString());
    }

    @Test
    @DisplayName("The scorpion's lost 1 piercing is back, marked as the pass's")
    void scorpionKeepsItsPiercing() {
        JsonNode sting = json("Scorpion", "MM", "Sting");
        JsonNode added = sting.path("effects").get(1);
        assertEquals("1", added.path("damageDice").asString());
        assertEquals("added", added.path("_rider").asString());
        assertEquals("[\"saveDC\",\"saveAbility\",\"halfOnSave\"]",
                sting.path("effects").get(0).path("_riderKeys").toString());
    }

    // ── Step 2: damage that waits on a condition ─────────────────

    @ParameterizedTest(name = "{0} ({1}) {2}")
    @CsvSource(delimiter = '|', value = {
            "Howler|MPMM|Rending Bite|4d10 psychic|targetCondition|frightened|false",
            "Warforged Titan|ERLW|Axehand|2d10 slashing|targetCondition|prone|false",
            "Yeth Hound|VGM|Bite|4d6 psychic|targetCondition|frightened|false",
            "Grippli Warrior|CM|Shortbow|1d4 piercing|attackerAdvantage|true|false",
            "Darkling|VGM|Dagger|2d6 piercing|attackerAdvantage|true|false",
            "Undying Soldier|ERLW|Spear|2d8 radiant|targetType|fiend undead|false",
            "Duergar|MM|War Pick|2d8 + 2 piercing|attackerForm|enlarged|true",
            "Lizardfolk Shaman|MM|Bite|1d10 + 2 piercing|attackerForm|crocodile form|true",
            "Swarm of Rats|MM|Bite|1d6 piercing|attackerBloodied|true|true"})
    @DisplayName("Extra or alternative damage that waits on a condition is a rider, not part of every hit")
    void conditionalDamage(String monster, String source, String action, String dice, String kind, String value,
                           boolean replaces) {
        MonsterEffects.Step step = step(monster, source, action);
        MonsterEffects.ConditionalDamage conditional = step.riders().conditionalDamage().getFirst();
        assertEquals(List.of(dice), damage(conditional.damage()));
        assertEquals(replaces, conditional.replacesDamage());
        MonsterTemplates.DamageCondition when = conditional.onlyIf();
        switch (kind) {
            case "targetCondition" -> assertEquals(value, when.targetCondition());
            case "attackerAdvantage" -> assertTrue(when.attackerAdvantage());
            case "targetType" -> assertEquals(List.of(value.split(" ")), when.targetType());
            case "attackerForm" -> assertEquals(value, when.attackerForm());
            default -> assertTrue(when.attackerBloodied());
        }
        assertFalse(damage(step.effect().damage()).contains(dice), "not in every hit's damage");
    }

    @Test
    @DisplayName("The swarm of rats deals its 2d6 normally and 1d6 at half its hit points or fewer, where the "
            + "hand-built entry dealt 1d6 always")
    void swarmOfRats() {
        MonsterEffects.Step step = step("Swarm of Rats", "MM", "Bite");
        assertEquals(List.of("2d6 piercing"), damage(step.effect().damage()));
        assertEquals(1, step.riders().firstDamageParts());
    }

    @Test
    @DisplayName("Geryon's claw deals its extra damage only to a creature Geryon itself grapples")
    void grappledByTheAttacker() {
        MonsterTemplates.DamageCondition when = step("Geryon", "MTF", "Claw").riders().conditionalDamage()
                .getFirst().onlyIf();
        assertEquals("grappled", when.targetCondition());
        assertTrue(when.byAttacker());
    }

    // ── Step 1: parsing ──────────────────────────────────────────

    @Test
    @DisplayName("A condition in the hit's first sentence, worded \"have the stunned condition\", is read with its "
            + "save (the cloud giant of evil air's Storm Boomerang)")
    void firstSentenceHasTheCondition() {
        MonsterEffects.Step step = step("Cloud Giant of Evil Air", "BGG", "Storm Boomerang");
        assertEquals(List.of("stunned"), step.effect().conditions());
        assertEquals(new Effect.HitSave(Ability.CON, 16, List.of("stunned")), hitSave(step));
    }

    @Test
    @DisplayName("\"When used with two hands\" is versatile, so Manshoon's staff has no choice note")
    void whenUsedWithTwoHands() {
        JsonNode staff = json("Manshoon", "WDH", "Staff of Power");
        assertEquals("1d8 + 2", staff.path("versatile").path("twoHandedDamageDice").asString());
        assertFalse(staff.path("dmNote").asString().contains("settles the choice"));
    }

    @Test
    @DisplayName("A condition with no save comes with the hit: the archdruid's Wildfire blinds until the start of its "
            + "next turn, and the hobgoblin devastator's bolt knocks prone")
    void noSaveConditions() {
        MonsterEffects.Step wildfire = step("Archdruid", "MPMM", "Wildfire");
        assertEquals(List.of("blinded"), wildfire.effect().conditions());
        assertNull(hitSave(wildfire));
        assertEquals(1, wildfire.effect().conditionEnds().get("blinded").durationRounds());

        MonsterEffects.Step bolt = step("Hobgoblin Devastator", "MPMM", "Devastating Bolt");
        assertEquals(List.of("prone"), bolt.effect().conditions());
        assertNull(hitSave(bolt));
    }

    @Test
    @DisplayName("\"Until the end of the yagnoloth's next turn\" ends at the end of the user's turn")
    void untilTheEndOfTheUsersNextTurn() {
        Effect.ConditionEnd end = step("Yagnoloth", "MPMM", "Massive Arm").effect().conditionEnds().get("stunned");
        assertTrue(end.endOfUsersTurn());
    }

    @ParameterizedTest(name = "{0} ({1}) {2}")
    @CsvSource(delimiter = '|', value = {
            "Ankheg|MM|Bite|grappled|Large",
            "Chuul|MM|Pincer|grappled|Large",
            "Meletian Hoplite|MOT|Shield Bash|prone|Medium",
            "Duergar Despot|MPMM|Iron Fist|prone|Large"})
    @DisplayName("A condition for \"a Large or smaller creature\" carries that size limit")
    void sizeLimits(String monster, String source, String action, String condition, String size) {
        assertEquals(size, step(monster, source, action).riders().maxTargetSize().get(condition));
    }

    @Test
    @DisplayName("Flat extra damage is read: the vox seeker's pincer adds its 3 lightning")
    void flatExtraDamage() {
        assertEquals(List.of("1d4 piercing", "3 lightning"),
                damage(step("Vox Seeker", "EGW", "Pincer").effect().damage()));
    }

    @Test
    @DisplayName("A hand-built tail that knocked prone on every hit, its save kept in a key nothing reads, now knocks "
            + "prone only on a failed DC 17 Strength save (adult deep dragon)")
    void handBuiltConditionGetsItsSave() {
        assertEquals(new Effect.HitSave(Ability.STR, 17, List.of("prone")),
                hitSave(step("Adult Deep Dragon", "FTD", "Tail")));
    }

    @Test
    @DisplayName("The hand-built entries' riders the pass can't automate are a dmNote for the DM")
    void handBuiltNote() {
        assertEquals("Also on a hit, applied by hand: Until this grapple ends, the ankheg can bite only the grappled "
                + "creature and has advantage on attack rolls to do so.", json("Ankheg", "MM", "Bite").path("dmNote")
                .asString());
    }

    @Test
    @DisplayName("No attack's hit calls for two different saves once its damage can be guarded too")
    void noAttackHasTwoSaves() {
        List<String> found = new ArrayList<>();
        for (JsonNode definition : definitions) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            for (MonsterTemplates.Action action : templates.actions()) {
                if (!"ATTACK_ROLL".equalsIgnoreCase(action.deliveryMethod())) {
                    continue;
                }
                MonsterEffects.MonsterAction plan = monsterEffects.plan(action, templates,
                        List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
                if (!plan.automated() && plan.manualReason().contains("different saving throws")) {
                    found.add(definition.path("monsterName").asString() + " " + action.name());
                }
            }
        }
        assertEquals(List.of(), found);
    }
}
