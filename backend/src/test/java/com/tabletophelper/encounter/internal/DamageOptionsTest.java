package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.dto.MultiattackOption;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Damage of a type the monster chooses when it uses the action (D207), as a dracohydra's Bite of "a type chosen by the
 * dracohydra: acid, cold, fire, lightning, or poison" and its Prismatic Breath (FTD). The request names the type, which
 * must be one of the action's damageOptions; a multiattack names one for each attack. The tiefling resists fire, has
 * every ability at 10 (saves +0), 60 hit points and AC 10.
 */
class DamageOptionsTest {

    private static final String OPTIONS = "\"damageOptions\": [\"acid\", \"cold\", \"fire\", \"lightning\", \"poison\"]";
    private static final String DRACOHYDRA = """
            {"actions": [
               {"name": "Multiattack", "isMultiattack": true, "multiattackComponents": [],
                "heads": {"count": 5, "attack": "Bite", "rules": "Whenever the dracohydra takes 30 or more damage in a single turn, one of its heads dies."}},
               {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackType": "MELEE_WEAPON", "attackBonus": 9,
                "reach": 10, "targetCount": 1,
                "effects": [{"effectType": "DAMAGE", "damageDice": "10", "damageType": "option", "flatDamage": true}],
                %s},
               {"name": "Fang", "deliveryMethod": "ATTACK_ROLL", "attackType": "MELEE_WEAPON", "attackBonus": 9,
                "effects": [{"effectType": "DAMAGE", "damageDice": "2d4", "damageType": "option"}], %s},
               {"name": "Prismatic Breath", "deliveryMethod": "SAVING_THROW", "saveDC": 17, "saveAbility": "DEX",
                "halfOnSave": true, "targetType": "AOE_CONE", "aoeSize": 60,
                "effects": [{"effectType": "DAMAGE", "damageDice": "20", "damageType": "option", "flatDamage": true}],
                %s},
               {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 9,
                "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "slashing", "flatDamage": true}]}]}"""
            .formatted(OPTIONS, OPTIONS, OPTIONS);

    private CombatFixture f;
    private EncounterParticipant tiefling;
    private EncounterParticipant dracohydra;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        tiefling = f.player("Tiefling", 60, 10);
        tiefling.getCharacter().setDamageResistances(List.of("fire"));
        dracohydra = f.monster("Dracohydra", 157, 16);
        dracohydra.getMonster().setActionTemplates(MonsterTemplates.parse(DRACOHYDRA));
    }

    private MonsterActionResponse act(String action, String damageType, int... rolls) {
        return act(new MonsterActionRequest(dracohydra.getId(), action, "ACTION", List.of(tiefling.getId()), null,
                null, null, null, null, damageType, null), rolls);
    }

    private MonsterActionResponse act(MonsterActionRequest request, int... rolls) {
        f.dice.script(rolls);
        MonsterActionResponse response = f.monsterActions.monsterAction(f.encounterId(), request, f.dmUserId);
        assertEquals(0, f.dice.remaining(), "every scripted roll is used");
        return response;
    }

    private ValidationException refused(MonsterActionRequest request) {
        ValidationException refused = assertThrows(ValidationException.class,
                () -> f.monsterActions.monsterAction(f.encounterId(), request, f.dmUserId));
        assertEquals(60, tiefling.getHpCurrent(), "a refused action changes nothing");
        return refused;
    }

    private boolean logged(String text) {
        return f.descriptions().stream().anyMatch(line -> line.contains(text));
    }

    private void heads(int count) {
        dracohydra.setResourcePoolsCurrent(List.of(MonsterPools.headsPool("Dracohydra", count)));
    }

    @Nested
    @DisplayName("One action")
    class Single {

        @Test
        @DisplayName("A fire Bite is halved by the tiefling's fire resistance, and the log names the type")
        void fireResisted() {
            act("Bite", "fire", 15);
            assertEquals(55, tiefling.getHpCurrent());
            assertTrue(logged("Dracohydra uses Bite (fire)"), f.descriptions()::toString);
            assertTrue(logged("5 fire damage"), f.descriptions()::toString);
        }

        @Test
        @DisplayName("An acid Bite isn't, and the type is read in any case")
        void acidNotResisted() {
            act("Bite", "Acid", 15);
            assertEquals(50, tiefling.getHpCurrent());
            assertTrue(logged("Dracohydra uses Bite (acid)"), f.descriptions()::toString);
        }

        @Test
        @DisplayName("A critical hit doubles the dice of the type chosen, which the resistance then halves")
        void critical() {
            act("Fang", "fire", 20, 4, 4, 4, 4);
            assertEquals(52, tiefling.getHpCurrent(), "4d4 of 16 fire, halved to 8");
        }

        @Test
        @DisplayName("A save action deals the type chosen: a failed save takes 20 fire, halved to 10")
        void saveAction() {
            act("Prismatic Breath", "fire", 1);
            assertEquals(50, tiefling.getHpCurrent());
            assertTrue(logged("Dracohydra uses Prismatic Breath (fire)"), f.descriptions()::toString);
        }

        @Test
        @DisplayName("No type, or one not among the options, is a 400 that names them")
        void missingOrWrong() {
            assertEquals("Choose a damage type for Bite: acid, cold, fire, lightning, poison",
                    refused(new MonsterActionRequest(dracohydra.getId(), "Bite", "ACTION", List.of(tiefling.getId()),
                            null, null, null, null, null, null, null)).getMessage());
            assertEquals("radiant isn't a damage type Bite can deal: acid, cold, fire, lightning, poison",
                    refused(new MonsterActionRequest(dracohydra.getId(), "Bite", "ACTION", List.of(tiefling.getId()),
                            null, null, null, null, null, "radiant", null)).getMessage());
        }

        @Test
        @DisplayName("A type for an action without options, or a list of them for one that isn't a multiattack, is a "
                + "400")
        void notAnOption() {
            assertEquals("Claw has no damage type to choose",
                    refused(new MonsterActionRequest(dracohydra.getId(), "Claw", "ACTION", List.of(tiefling.getId()),
                            null, null, null, null, null, "fire", null)).getMessage());
            assertTrue(refused(new MonsterActionRequest(dracohydra.getId(), "Bite", "ACTION",
                    List.of(tiefling.getId()), null, null, null, null, null, null, List.of("fire"))).getMessage()
                    .startsWith("Only a multiattack takes a damage type for each attack"));
        }
    }

    @Nested
    @DisplayName("A multiattack: a type for each Bite, one per head (D206)")
    class Multiattack {

        private MonsterActionRequest multiattack(List<String> types) {
            List<List<UUID>> targets = Collections.nCopies(types.size(), List.of(tiefling.getId()));
            return new MonsterActionRequest(dracohydra.getId(), "Multiattack", "ACTION", List.of(), null, null, null,
                    targets, null, null, types);
        }

        @Test
        @DisplayName("Three heads bite with fire, acid and cold: 5 + 10 + 10, and the log names each type")
        void threeTypes() {
            heads(3);
            act(multiattack(List.of("fire", "acid", "cold")), 15, 15, 15);
            assertEquals(35, tiefling.getHpCurrent());
            assertTrue(logged("Dracohydra uses Multiattack (Bite: fire; Bite: acid; Bite: cold)"),
                    f.descriptions()::toString);
        }

        @Test
        @DisplayName("With the targets for the whole action, the types still go attack by attack")
        void wholeActionTargets() {
            heads(2);
            act(new MonsterActionRequest(dracohydra.getId(), "Multiattack", "ACTION", List.of(tiefling.getId()), null,
                    null, null, null, null, null, List.of("fire", "fire")), 15, 15);
            assertEquals(50, tiefling.getHpCurrent());
        }

        @Test
        @DisplayName("A Bite without a type is a 400 naming which attack, as are a single type, a list of the wrong "
                + "length and a type not among the options")
        void refusals() {
            heads(3);
            List<String> missing = new java.util.ArrayList<>(List.of("fire", "acid", "cold"));
            missing.set(1, null);
            assertEquals("Choose a damage type for Bite (attack 2): acid, cold, fire, lightning, poison",
                    refused(multiattack(missing)).getMessage());
            assertEquals("Choose a damage type for Bite (attack 1): acid, cold, fire, lightning, poison",
                    refused(new MonsterActionRequest(dracohydra.getId(), "Multiattack", "ACTION",
                            List.of(tiefling.getId()), null, null, null, null, null, null, null)).getMessage());
            assertTrue(refused(new MonsterActionRequest(dracohydra.getId(), "Multiattack", "ACTION",
                    List.of(tiefling.getId()), null, null, null, null, null, "fire", null)).getMessage()
                    .contains("chooses a damage type for each attack"));
            assertEquals("Multiattack makes 3 attacks, but damage types were given for 2",
                    refused(new MonsterActionRequest(dracohydra.getId(), "Multiattack", "ACTION",
                            List.of(tiefling.getId()), null, null, null, null, null, null, List.of("fire", "acid")))
                            .getMessage());
            assertTrue(refused(multiattack(List.of("fire", "force", "cold"))).getMessage()
                    .startsWith("force isn't a damage type Bite (attack 2) can deal"));
        }

        @Test
        @DisplayName("An attack given no target needs no type")
        void skippedAttack() {
            heads(2);
            List<String> types = new java.util.ArrayList<>(List.of("acid", "acid"));
            types.set(1, null);
            act(new MonsterActionRequest(dracohydra.getId(), "Multiattack", "ACTION", List.of(), null, null, null,
                    List.of(List.of(tiefling.getId()), List.of()), null, null, types), 15);
            assertEquals(50, tiefling.getHpCurrent());
        }

        @Test
        @DisplayName("The multiattack's options list each Bite's damage types")
        void options() {
            heads(2);
            List<MultiattackOption> options = f.monsterActions.multiattackOptions(f.encounterId(), dracohydra.getId(),
                    f.dmUserId);
            assertEquals(List.of(List.of("acid", "cold", "fire", "lightning", "poison"),
                            List.of("acid", "cold", "fire", "lightning", "poison")),
                    options.getFirst().components().stream().map(MultiattackOption.ComponentOption::damageOptions)
                            .toList());
        }
    }
}
