package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollPreview;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.dto.FearSightRequest;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.RepeatSaveRequest;
import com.tabletophelper.encounter.dto.StandardActionRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.Fear;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.StandardActions.Action;
import com.tabletophelper.shared.JacksonDefaults;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The frightened condition (PHB p.290, D201): disadvantage on the creature's attack rolls and ability checks while the
 * source of its fear is in sight, worked out without a map, and the source each way of being frightened records. Vex
 * is a fighter, Aria a paladin and caster (save DC 13), the ogre and the goblin monsters, and the Adult Red Dragon has
 * its Frightful Presence as the game data ships it. The DM acts.
 */
class FrightenedTest {

    private static final String DEFINITIONS = "/data/spell-effects/spell-effect-definitions.json";
    private static final List<String> SPELLS = List.of("Cause Fear", "Fear", "Wrathful Smite", "Phantasmal Killer");

    private static final String FRIGHTFUL_PRESENCE = """
            {"actions": [{"name": "Frightful Presence", "actionType": "ACTION", "deliveryMethod": "SAVING_THROW",
              "saveDC": 19, "saveAbility": "WIS", "halfOnSave": false,
              "effects": [{"effectType": "CONDITION", "condition": "frightened", "durationText": "1 minute",
                           "durationRounds": 10, "saveToEndEachTurn": true, "saveToEndDC": 19,
                           "saveToEndAbility": "WIS"}]}]}""";

    private static final String PALADIN_CD = "class:paladin-channel-divinity";

    private CombatFixture f;
    private EncounterParticipant vex;
    private EncounterParticipant aria;
    private EncounterParticipant ogre;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() throws IOException {
        f = new CombatFixture();
        vex = f.player("Vex", 40, 18);
        aria = f.player("Aria", 40, 18);
        aria.getCharacter().setCharisma(16);
        aria.setSpellSaveDc(13);
        aria.setSpellAttackBonus(5);
        aria.setSpellcastingAbility("CHA");
        ogre = f.monster("Ogre", 60, 11);
        ogre.getMonster().setType("giant");
        goblin = f.monster("Goblin", 20, 11);
        goblin.getMonster().setType("humanoid");
        try (InputStream in = FrightenedTest.class.getResourceAsStream(DEFINITIONS)) {
            for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                if (SPELLS.contains(definition.path("spellName").asString())) {
                    f.spell(definition.path("spellName").asString(), definition.path("spellLevel").asInt(),
                            definition.toString());
                }
            }
        }
    }

    // A frightened entry the DM added by hand, of the source (null for none)
    private static ConditionEntry frightenedOf(EncounterParticipant creature, EncounterParticipant source) {
        ConditionEntry entry = ConditionEntry.manual("frightened", null, 1, source != null ? source.getId() : null);
        List<ConditionEntry> conditions = new ArrayList<>(creature.getActiveConditions() != null
                ? creature.getActiveConditions() : List.of());
        conditions.add(entry);
        creature.setActiveConditions(conditions);
        return entry;
    }

    private static void has(EncounterParticipant creature, String condition) {
        List<ConditionEntry> conditions = new ArrayList<>(creature.getActiveConditions() != null
                ? creature.getActiveConditions() : List.of());
        conditions.add(ConditionEntry.manual(condition, null, 1));
        creature.setActiveConditions(conditions);
    }

    private static ConditionEntry frightened(EncounterParticipant creature) {
        return creature.getActiveConditions().stream().filter(c -> c.name().equals("frightened")).findFirst()
                .orElseThrow();
    }

    private List<AttackRollPreview.RollSource> preview(EncounterParticipant attacker, EncounterParticipant target) {
        return f.attacks.attackRollPreview(f.encounterId(), attacker.getId(), target.getId(), true, f.dmUserId)
                .sources();
    }

    private static AttackRollPreview.RollSource disadvantage(String reason) {
        return new AttackRollPreview.RollSource(RollMode.DISADVANTAGE, reason);
    }

    private void cast(String spell, int slotLevel, List<EncounterParticipant> targets) {
        f.spellCaster.cast(f.encounter, aria, spell, slotLevel, targets, "CHA", 5, 13, null, "",
                MetamagicCasting.Plan.NONE, null, null);
    }

    private void markOutOfSight(EncounterParticipant creature, ConditionEntry condition, boolean outOfSight) {
        f.conditionService.markFearSourceSight(f.encounterId(),
                new FearSightRequest(creature.getId(), condition.id(), outOfSight), f.dmUserId);
    }

    private static ConditionRequest addRequest(EncounterParticipant target, String condition,
                                               EncounterParticipant source) {
        ConditionRequest request = new ConditionRequest();
        request.setTargetId(target.getId());
        request.setCondition(condition);
        request.setSourceParticipantId(source != null ? source.getId() : null);
        return request;
    }

    @Nested
    @DisplayName("Attack rolls")
    class Attacks {

        @Test
        @DisplayName("Frightened of the goblin, in sight: disadvantage on Vex's attack rolls against anyone, named")
        void inSight() {
            frightenedOf(vex, goblin);

            assertThat(preview(vex, ogre)).containsExactly(disadvantage("attacker frightened (Goblin in sight)"));
            assertThat(preview(vex, goblin)).containsExactly(disadvantage("attacker frightened (Goblin in sight)"));
        }

        @Test
        @DisplayName("The attack rolls two d20s and keeps the lower, naming the fear in the log")
        void rolled() {
            frightenedOf(vex, goblin);
            AttackRollRequest request = new AttackRollRequest();
            request.setTargetId(ogre.getId());
            request.setAttackBonus(5);
            request.setDamageDice("1d8");
            request.setDamageType("slashing");
            f.dice.script(15, 4);

            f.attacks.rollAttack(f.encounterId(), request, vex.getId(), f.dmUserId);

            assertThat(f.descriptions()).anyMatch(line -> line.contains("(disadvantage: attacker frightened (Goblin "
                    + "in sight)): 2d20(15, 4) with disadvantage → 4"));
            assertEquals(0, f.dice.remaining());
        }

        @Test
        @DisplayName("No disadvantage while the goblin is dead, banished, gone from the encounter or dismissed")
        void sourceGone() {
            frightenedOf(vex, goblin);
            CombatFixture.dead(goblin);
            assertThat(preview(vex, ogre)).isEmpty();

            goblin.setLifeState(com.tabletophelper.rules.combat.LifeState.ALIVE);
            has(goblin, EffectApplier.BANISHED);
            assertThat(preview(vex, ogre)).isEmpty();

            goblin.setActiveConditions(null);
            goblin.setDismissed(true);
            assertThat(preview(vex, ogre)).isEmpty();

            goblin.setDismissed(null);
            f.encounter.getParticipants().remove(goblin);
            assertThat(preview(vex, ogre)).isEmpty();
        }

        @Test
        @DisplayName("No disadvantage while the goblin is invisible, unless the frightened creature has truesight")
        void invisibleSource() {
            frightenedOf(vex, goblin);
            has(goblin, "invisible");
            assertThat(preview(vex, ogre)).isEmpty();

            EncounterParticipant seer = f.monster("Seer", 20, 11);
            seer.getMonster().setSenses("[\"truesight 120 ft.\"]");
            frightenedOf(seer, goblin);
            assertThat(preview(seer, ogre)).containsExactly(disadvantage("attacker frightened (Goblin in sight)"));
        }

        @Test
        @DisplayName("No disadvantage from the fear while Vex is blinded, though blindness gives its own")
        void blinded() {
            frightenedOf(vex, goblin);
            has(vex, "blinded");

            assertThat(preview(vex, ogre)).containsExactly(disadvantage("attacker blinded"));
        }

        @Test
        @DisplayName("A fear with no recorded source gives disadvantage, named plainly")
        void noSource() {
            frightenedOf(vex, null);

            assertThat(preview(vex, ogre)).containsExactly(disadvantage("attacker frightened"));
        }

        @Test
        @DisplayName("Two fears in sight name each source; one marked out of sight drops out")
        void twoFears() {
            ConditionEntry ofGoblin = frightenedOf(vex, goblin);
            frightenedOf(vex, ogre);
            assertThat(preview(vex, aria)).containsExactly(disadvantage("attacker frightened (Goblin in sight)"),
                    disadvantage("attacker frightened (Ogre in sight)"));

            markOutOfSight(vex, ofGoblin, true);
            assertThat(preview(vex, aria)).containsExactly(disadvantage("attacker frightened (Ogre in sight)"));
        }
    }

    @Nested
    @DisplayName("The DM's out-of-sight mark")
    class Mark {

        @Test
        @DisplayName("Marking the source out of sight ends the disadvantage, logged; marking it back brings it back")
        void toggle() {
            ConditionEntry entry = frightenedOf(vex, goblin);

            markOutOfSight(vex, entry, true);
            assertThat(frightened(vex).sourceOutOfSight()).isTrue();
            assertThat(preview(vex, ogre)).isEmpty();
            assertThat(Fears.of(vex).getFirst().sight()).isEqualTo(Fear.Sight.MARKED_OUT_OF_SIGHT);
            assertThat(f.descriptions()).contains("The DM marks Goblin out of Vex's sight: its frightened no longer "
                    + "gives disadvantage on attack rolls and ability checks");

            markOutOfSight(vex, entry, false);
            assertThat(frightened(vex).sourceOutOfSight()).isNull();
            assertThat(preview(vex, ogre)).containsExactly(disadvantage("attacker frightened (Goblin in sight)"));
            assertThat(f.descriptions()).contains("The DM marks Goblin back in Vex's sight: its frightened gives "
                    + "disadvantage on attack rolls and ability checks while it is in sight");
        }

        @Test
        @DisplayName("The mark is refused for an unknown condition or one that isn't frightened")
        void refused() {
            has(vex, "poisoned");
            ConditionEntry poisoned = vex.getActiveConditions().getFirst();

            assertThrows(ValidationException.class, () -> markOutOfSight(vex, poisoned, true));
            assertThrows(ValidationException.class, () -> f.conditionService.markFearSourceSight(f.encounterId(),
                    new FearSightRequest(vex.getId(), UUID.randomUUID(), true), f.dmUserId));
        }
    }

    @Nested
    @DisplayName("Ability checks and saves")
    class Checks {

        @Test
        @DisplayName("The checks the app rolls have disadvantage while the source is in sight, not once it is out")
        void checks() {
            ConditionEntry entry = frightenedOf(vex, goblin);

            assertThat(AbilityChecks.sources(vex, Ability.DEX, OngoingModifier.INITIATIVE_CHECK)).containsExactly(
                    new AttackRules.Source(RollMode.DISADVANTAGE, "frightened (Goblin in sight)"));
            assertThat(AbilityChecks.sources(vex, Ability.WIS, null)).hasSize(1);

            markOutOfSight(vex, entry, true);
            assertThat(AbilityChecks.sources(vex, Ability.DEX, OngoingModifier.INITIATIVE_CHECK)).isEmpty();
        }

        @Test
        @DisplayName("Poisoned gives disadvantage on the checks the app rolls too (PHB p.292)")
        void poisoned() {
            f.effectApplier.addCondition(f.encounter, vex, "poisoned", null);
            assertThat(AbilityChecks.sources(vex, Ability.DEX, OngoingModifier.INITIATIVE_CHECK)).containsExactly(
                    new AttackRules.Source(RollMode.DISADVANTAGE, "poisoned"));
        }

        @Test
        @DisplayName("Hide rolls Dexterity (Stealth) with disadvantage from the fear")
        void hide() {
            frightenedOf(vex, goblin);
            f.turnOf(vex);
            f.dice.script(14, 6);

            f.standardActions.standardAction(f.encounterId(),
                    new StandardActionRequest(vex.getId(), Action.HIDE, null, null, null), f.dmUserId);

            assertThat(f.descriptions()).anyMatch(line -> line.contains("Dexterity (Stealth) 2d20(14, 6) with "
                    + "disadvantage from frightened (Goblin in sight) → 6"));
        }

        @Test
        @DisplayName("Saving throws aren't affected: Vex's WIS save against the dragon rolls one d20")
        void saves() {
            EncounterParticipant dragon = dragon();
            frightenedOf(vex, dragon);
            f.turnOf(dragon);
            f.dice.script(12);

            f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(dragon.getId(),
                    "Frightful Presence", "ACTION", List.of(vex.getId()), null, null, null, null), f.dmUserId);

            assertEquals(0, f.dice.remaining());
        }

        @Test
        @DisplayName("Wrathful Smite's WIS check to end the fear has disadvantage while Aria is in sight, none while "
                + "she is invisible (PHB p.289)")
        void wrathfulSmiteCheck() {
            cast("Wrathful Smite", 1, List.of());
            AttackRollRequest request = new AttackRollRequest();
            request.setTargetId(ogre.getId());
            request.setAttackBonus(5);
            request.setDamageDice("1d8");
            request.setDamageType("slashing");
            request.setWithin5Ft(true);
            // 15 + 5 hits; 1d8 4 and 1d6 3; the WIS save rolls 5 against DC 13
            f.dice.script(15, 4, 3, 5);
            f.attacks.rollAttack(f.encounterId(), request, aria.getId(), f.dmUserId);
            ConditionEntry fear = frightened(ogre);
            assertEquals(aria.getId(), fear.sourceParticipantId());

            has(aria, "invisible");
            f.turnOf(ogre);
            CombatFixture.newTurn(ogre);
            f.dice.script(9);
            f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(ogre.getId(), fear.id()),
                    f.dmUserId);

            assertThat(f.descriptions()).anyMatch(line -> line.contains("uses its action on a WIS check against "
                    + "frightened: d20(9)"));
            assertEquals(0, f.dice.remaining());
        }
    }

    @Nested
    @DisplayName("The source each way of being frightened records")
    class Sources {

        @Test
        @DisplayName("Cause Fear and Fear record their caster, so the ogre fears Aria")
        void spells() {
            f.dice.script(2);
            cast("Cause Fear", 1, List.of(ogre));
            assertEquals(aria.getId(), frightened(ogre).sourceParticipantId());
            assertThat(preview(ogre, vex)).containsExactly(disadvantage("attacker frightened (Aria in sight)"));

            f.dice.script(2);
            cast("Fear", 3, List.of(goblin));
            assertEquals(aria.getId(), frightened(goblin).sourceParticipantId());
            assertThat(Fears.of(goblin).getFirst().label()).isEqualTo("Aria");
        }

        @Test
        @DisplayName("Phantasmal Killer's fear is of its illusion: in sight though Aria turns invisible")
        void illusion() {
            f.dice.script(2);
            cast("Phantasmal Killer", 4, List.of(ogre));
            has(aria, "invisible");

            assertThat(preview(ogre, vex)).containsExactly(
                    disadvantage("attacker frightened (Aria's Phantasmal Killer in sight)"));
        }

        @Test
        @DisplayName("A monster's Frightful Presence records the monster")
        void monsterAction() {
            EncounterParticipant dragon = dragon();
            f.turnOf(dragon);
            f.dice.script(3);

            f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(dragon.getId(),
                    "Frightful Presence", "ACTION", List.of(vex.getId()), null, null, null, null), f.dmUserId);

            assertEquals(dragon.getId(), frightened(vex).sourceParticipantId());
            assertThat(preview(vex, ogre)).containsExactly(
                    disadvantage("attacker frightened (Adult Red Dragon in sight)"));
        }

        @Test
        @DisplayName("Abjure Enemy records the paladin (PHB p.88)")
        void abjureEnemy() {
            aria.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Paladin",
                    UUID.randomUUID(), "Oath of Vengeance", 3)));
            aria.getCharacter().setLevel(3);
            aria.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry(PALADIN_CD,
                    "Channel Divinity", "CLASS", null, 1, "1", 1, "shortRest", null, null, "ACTION", "sun",
                    Map.of("effect", "channel-divinity")))));
            f.dice.script(4);

            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(aria.getId(), PALADIN_CD, null, "abjure-enemy",
                    List.of(goblin.getId())), f.dmUserId);

            assertEquals(aria.getId(), frightened(goblin).sourceParticipantId());
            assertThat(preview(goblin, vex)).containsExactly(disadvantage("attacker frightened (Aria in sight)"));
        }

        @Test
        @DisplayName("The DM's frightened names its source, logged; another source adds a second fear, the same one "
                + "nothing")
        void manual() {
            f.conditionService.addCondition(f.encounterId(), addRequest(vex, "frightened", goblin), f.dmUserId);
            assertEquals(goblin.getId(), frightened(vex).sourceParticipantId());
            assertThat(f.descriptions()).contains("Vex gains condition: frightened of Goblin");

            f.conditionService.addCondition(f.encounterId(), addRequest(vex, "frightened", goblin), f.dmUserId);
            f.conditionService.addCondition(f.encounterId(), addRequest(vex, "frightened", ogre), f.dmUserId);
            assertThat(vex.getActiveConditions()).extracting(ConditionEntry::sourceParticipantId)
                    .containsExactly(goblin.getId(), ogre.getId());
        }

        @Test
        @DisplayName("A source is refused on another condition, or naming the creature itself")
        void manualRefused() {
            assertThrows(ValidationException.class, () -> f.conditionService.addCondition(f.encounterId(),
                    addRequest(vex, "poisoned", goblin), f.dmUserId));
            assertThrows(ValidationException.class, () -> f.conditionService.addCondition(f.encounterId(),
                    addRequest(vex, "frightened", vex), f.dmUserId));
            ConditionRequest unknown = addRequest(vex, "frightened", null);
            unknown.setSourceParticipantId(UUID.randomUUID());
            assertThrows(ValidationException.class, () -> f.conditionService.addCondition(f.encounterId(), unknown,
                    f.dmUserId));
            assertThat(vex.getActiveConditions()).isNull();
        }
    }

    private EncounterParticipant dragon() {
        EncounterParticipant dragon = f.monster("Adult Red Dragon", 256, 19);
        dragon.getMonster().setType("dragon");
        dragon.getMonster().setActionTemplates(MonsterTemplates.parse(FRIGHTFUL_PRESENCE));
        return dragon;
    }
}
