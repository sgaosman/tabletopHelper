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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the DM says about one use of a monster's attack (D241): that it charged first, as an aurochs's Gore "If the
 * aurochs moved at least 20 feet straight toward the target immediately before the hit, the target takes an extra 9
 * (2d8) piercing damage, and the target must succeed on a DC 15 Strength saving throw or be knocked prone" (MPMM);
 * that a versatile weapon was held in two hands, as a hobgoblin's longsword (MM p.186); and the form its damage depends
 * on, as a wereraven's beak's "1 piercing damage in raven form, or 4 (1d4 + 2) piercing damage in hybrid form"
 * (VRGR). Damage is flat so only the attack roll and saves are scripted; the fighter has 60 hit points, AC 10 and
 * every ability at 10 (saves +0).
 */
class ChargeGripFormTest {

    private static final String CHARGED = "{\"attackerCharged\": true, \"text\": \"if it moved at least 20 feet "
            + "straight toward the target first\"}";
    private static final String TEMPLATES = """
            {"actions": [
               {"name": "Multiattack", "isMultiattack": true, "multiattackComponents": ["Gore", "Longsword"]},
               {"name": "Gore", "deliveryMethod": "ATTACK_ROLL", "attackType": "MELEE_WEAPON", "attackBonus": 7,
                "effects": [{"effectType": "DAMAGE", "damageDice": "10", "damageType": "piercing", "flatDamage": true},
                            {"effectType": "DAMAGE", "damageDice": "5", "damageType": "piercing", "flatDamage": true,
                             "onlyIf": %1$s},
                            {"effectType": "CONDITION", "condition": "prone", "saveDC": 15, "saveAbility": "STR",
                             "onlyIf": %1$s}],
                "charge": {"feet": 20, "trait": "Pounce", "note": "If the target is prone, the aurochs can make one bite attack against it as a bonus action."}},
               {"name": "Longsword", "deliveryMethod": "ATTACK_ROLL", "attackType": "MELEE_WEAPON", "attackBonus": 3,
                "effects": [{"effectType": "DAMAGE", "damageDice": "5", "damageType": "slashing", "flatDamage": true,
                             "twoHandedDamageDice": "6"}]},
               {"name": "Spear", "deliveryMethod": "ATTACK_ROLL", "attackType": "MELEE_OR_RANGED_WEAPON", "attackBonus": 3,
                "effects": [{"effectType": "DAMAGE", "damageDice": "4", "damageType": "piercing", "flatDamage": true}],
                "versatile": {"twoHandedDamageDice": "7", "twoHandedDamageType": "piercing"}},
               {"name": "Beak (Raven or Hybrid Form Only)", "deliveryMethod": "ATTACK_ROLL",
                "attackType": "MELEE_WEAPON", "attackBonus": 4,
                "effects": [{"effectType": "DAMAGE", "damageDice": "4", "damageType": "piercing", "flatDamage": true},
                            {"effectType": "DAMAGE", "damageDice": "1", "damageType": "piercing", "flatDamage": true,
                             "onlyIf": {"attackerForm": "raven form", "text": "in raven form"},
                             "replacesDamage": true}]}]}""".formatted(CHARGED);
    private static final String BEAK = "Beak (Raven or Hybrid Form Only)";

    private CombatFixture f;
    private EncounterParticipant fighter;
    private EncounterParticipant beast;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        fighter = f.player("Fighter", 60, 10);
        beast = f.monster("Beast", 50, 12);
        beast.getMonster().setActionTemplates(MonsterTemplates.parse(TEMPLATES));
    }

    private MonsterActionRequest request(String action, Boolean charged, Boolean twoHanded, String form) {
        return new MonsterActionRequest(beast.getId(), action, "ACTION", List.of(fighter.getId()), null, null, null,
                null, null, null, null, charged, twoHanded, form, null, null, null);
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
        assertEquals(60, fighter.getHpCurrent(), "a refused action changes nothing");
        return refused;
    }

    private boolean logged(String text) {
        return f.descriptions().stream().anyMatch(line -> line.contains(text));
    }

    @Nested
    @DisplayName("Charge")
    class Charge {

        @Test
        @DisplayName("Without a charge the Gore deals its own damage only, with no save and no prone")
        void notCharged() {
            act(request("Gore", null, null, null), 15);
            assertEquals(50, fighter.getHpCurrent());
            assertEquals(List.of(), CombatFixture.conditionNames(fighter));
            assertTrue(logged("Beast uses Gore."), f.descriptions()::toString);
            assertFalse(logged("Pounce"), f.descriptions()::toString);
        }

        @Test
        @DisplayName("Charging adds the extra damage and the Strength save, and the log names the charge and the DM's "
                + "note")
        void charged() {
            act(request("Gore", true, null, null), 15, 5);
            assertEquals(45, fighter.getHpCurrent(), "10 + 5 after the charge");
            assertEquals(List.of("prone"), CombatFixture.conditionNames(fighter));
            assertTrue(logged("Beast uses Gore (charging)."), f.descriptions()::toString);
            assertTrue(logged("Pounce: If the target is prone, the aurochs can make one bite attack against it as a "
                    + "bonus action."), f.descriptions()::toString);
        }

        @Test
        @DisplayName("A charging target that makes its save takes the extra damage but stays up")
        void chargedSaved() {
            act(request("Gore", true, null, null), 15, 18);
            assertEquals(45, fighter.getHpCurrent());
            assertEquals(List.of(), CombatFixture.conditionNames(fighter));
        }

        @Test
        @DisplayName("A charge said for an attack without one is a 400")
        void noCharge() {
            assertEquals("Longsword has no charge to make",
                    refused(request("Longsword", true, null, null)).getMessage());
        }
    }

    @Nested
    @DisplayName("Versatile weapons")
    class Grip {

        @Test
        @DisplayName("One-handed, the longsword deals its first damage; two-handed, the data's two-handed damage")
        void twoHanded() {
            act(request("Longsword", null, false, null), 15);
            assertEquals(55, fighter.getHpCurrent());
            act(request("Longsword", null, true, null), 15);
            assertEquals(49, fighter.getHpCurrent(), "6 with two hands");
            assertTrue(logged("Beast uses Longsword (two-handed)."), f.descriptions()::toString);
        }

        @Test
        @DisplayName("The action-level versatile of the generated entries is read the same way")
        void actionLevelVersatile() {
            act(request("Spear", null, true, null), 15);
            assertEquals(53, fighter.getHpCurrent(), "7 with two hands");
        }

        @Test
        @DisplayName("Two hands said for an attack that isn't versatile is a 400")
        void notVersatile() {
            assertTrue(refused(request("Gore", null, true, null)).getMessage()
                    .startsWith("Gore isn't a versatile weapon attack"));
        }
    }

    @Nested
    @DisplayName("Forms")
    class Forms {

        @Test
        @DisplayName("In raven form the beak deals 1 in place of its usual 4")
        void ravenForm() {
            act(request(BEAK, null, null, "Raven Form"), 15);
            assertEquals(59, fighter.getHpCurrent());
            assertTrue(logged("Beast uses " + BEAK + " (in raven form)."), f.descriptions()::toString);
        }

        @Test
        @DisplayName("In its usual form, or with no form said and none the app can see, it deals its usual 4")
        void usualForm() {
            act(request(BEAK, null, null, "usual"), 15);
            assertEquals(56, fighter.getHpCurrent());
            act(request(BEAK, null, null, null), 15);
            assertEquals(52, fighter.getHpCurrent());
            assertTrue(logged("left to the DM"), f.descriptions()::toString);
        }

        @Test
        @DisplayName("A form the damage doesn't depend on is a 400")
        void unknownForm() {
            assertEquals("wolf form isn't a form " + BEAK + " depends on: raven form or usual",
                    refused(request(BEAK, null, null, "wolf form")).getMessage());
            assertEquals("Gore's damage doesn't depend on a form",
                    refused(request("Gore", null, null, "raven form")).getMessage());
        }
    }

    @Nested
    @DisplayName("Multiattack")
    class Multiattack {

        private MonsterActionRequest multiattack(List<Boolean> charged, List<Boolean> twoHanded) {
            return new MonsterActionRequest(beast.getId(), "Multiattack", "ACTION", List.of(), null, null, null,
                    List.of(List.of(fighter.getId()), List.of(fighter.getId())), null, null, null, null, null, null,
                    charged, twoHanded, null);
        }

        @Test
        @DisplayName("Each attack takes its own charge and grip, and the log names them")
        void perAttack() {
            act(multiattack(List.of(true, false), List.of(false, true)), 15, 18, 15);
            assertEquals(39, fighter.getHpCurrent(), "15 charging Gore, 6 two-handed Longsword");
            assertTrue(logged("Beast uses Multiattack (Gore: charging; Longsword: two-handed)."),
                    f.descriptions()::toString);
        }

        @Test
        @DisplayName("A choice an attack doesn't have, a list of the wrong size, or a single choice is a 400")
        void refusals() {
            assertEquals("Longsword (attack 2) has no charge to make",
                    refused(multiattack(List.of(false, true), null)).getMessage());
            assertEquals("Multiattack makes 2 attacks, but grips were given for 1",
                    refused(multiattack(null, List.of(true))).getMessage());
            assertTrue(refused(new MonsterActionRequest(beast.getId(), "Multiattack", "ACTION",
                    List.of(fighter.getId()), null, null, null, null, null, null, null, true, null, null, null, null,
                    null)).getMessage().contains("for each attack"));
            assertTrue(refused(new MonsterActionRequest(beast.getId(), "Gore", "ACTION", List.of(fighter.getId()),
                    null, null, null, null, null, null, null, null, null, null, Arrays.asList(true, null), null,
                    null)).getMessage().startsWith("Only a multiattack"));
        }

        @Test
        @DisplayName("The multiattack's options say which attack charges, how far, its two-handed damage and its forms")
        void options() {
            List<MultiattackOption.ComponentOption> components = f.monsterActions.multiattackOptions(f.encounterId(),
                    beast.getId(), f.dmUserId).getFirst().components();
            assertTrue(components.get(0).charge());
            assertEquals(20, components.get(0).chargeFeet());
            assertNull(components.get(0).twoHandedDamage());
            assertFalse(components.get(1).charge());
            assertEquals("6 slashing", components.get(1).twoHandedDamage());
            assertEquals(List.of(), components.get(1).forms());
        }
    }

    @Test
    @DisplayName("Templates: a charge's parts are left out unless charged, and the form's alternative replaces the usual "
            + "damage when chosen")
    void templates() {
        MonsterTemplates templates = MonsterTemplates.parse(TEMPLATES);
        MonsterTemplates.Action gore = templates.find("ACTION", "Gore").orElseThrow();
        assertTrue(gore.hasCharge());
        assertEquals(1, gore.withCharge(false).effects().size());
        assertEquals(3, gore.withCharge(true).effects().size());
        assertTrue(gore.withCharge(true).effects().stream().noneMatch(MonsterTemplates.Part::chargeOnly));
        MonsterTemplates.Action beak = templates.find("ACTION", BEAK).orElseThrow();
        assertEquals(List.of("raven form"), beak.forms());
        assertEquals(1, beak.withForm("usual").effects().size());
        assertTrue(beak.withForm("raven form").effects().get(1).replacesDamage());
        MonsterTemplates.Action longsword = templates.find("ACTION", "Longsword").orElseThrow();
        assertTrue(longsword.isVersatile());
        assertEquals("6", longsword.withGrip(true).effects().getFirst().damageDice());
        assertFalse(gore.isVersatile());
    }
}
