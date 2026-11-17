package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.WarlockPatrons;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.character.state.SubclassChosen;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackCost;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tabletophelper.encounter.internal.CombatFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Genie's and the Fathomless's features in combat (TCE, D242): Genie's Wrath on a weapon or spell attack's hit,
 * Elemental Gift's resistance and flying speed, and Grasping Tentacles' temporary hit points and unbreakable
 * concentration. Vex is a warlock with every ability 10 and +2 proficiency carrying a dagger (+2 to hit, 1d4); the
 * goblin has AC 10 and 60 HP. Vex's player acts, on Vex's turn unless a test says otherwise.
 */
class GenieAndFathomlessTest {

    private static final String FIRE_BOLT = """
            {"spellName": "Fire Bolt", "spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
             "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "fire"}]}""";

    private static final String CURE_WOUNDS = """
            {"spellName": "Cure Wounds", "spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
             "healing": {"healingDice": "1d8+MOD", "upcastScaling": {"additionalDicePerLevel": "1d8"}}}""";

    private static final String BLACK_TENTACLES = """
            {"spellLevel": 4, "deliveryMethod": "SELF", "selfOnly": true, "concentration": true, "durationRounds": 10,
             "effects": []}""";

    private static final String ELEMENTAL_GIFT_POOL = "subclass:genie-elemental-gift";

    private CombatFixture f;
    private EncounterParticipant vex;
    private EncounterParticipant goblin;
    private UUID player;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.weapons(weapon("Dagger", "Melee Weapon", "simple", "1d4", "piercing", "F", "L"));
    }

    // A single-class warlock of the patron and level, its kind recorded on Warlock 1 when one is given
    private void warlock(String patron, int level, String kind) {
        vex = f.player("Vex", 40, 12);
        vex.getCharacter().setLevel(level);
        vex.getCharacter().setCharacterClass("Warlock");
        vex.getCharacter().setSubclass(patron);
        vex.getCharacter().setSpeed(30);
        vex.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Warlock", null, patron,
                level)));
        UUID classId = UUID.randomUUID();
        LevelChoices choices = LevelChoices.NONE.withSubclass(new SubclassChosen(classId, UUID.randomUUID(), patron));
        if (kind != null) choices = choices.withSubclassOption(kind);
        vex.getCharacter().setLevelHistory(new ArrayList<>(List.of(new LevelHistoryEntry(1, classId, "Warlock", 1, 8,
                null, null, choices, null, null))));
        CombatFixture.carries(vex, List.of("simple"), "Dagger");
        player = f.playerControlling(vex);
        goblin = f.monster("Goblin", 60, 10);
    }

    private AttackRollRequest dagger(boolean wrath) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setWeapon("Dagger");
        request.setGeniesWrath(wrath ? true : null);
        return request;
    }

    private void attack(AttackRollRequest request, int... rolls) {
        f.dice.script(rolls);
        f.attacks.rollAttack(f.encounterId(), request, vex.getId(), player);
    }

    private CastSpellRequest cast(String spell, int slotLevel, List<EncounterParticipant> targets, boolean wrath) {
        f.knows(vex, spell);
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName(spell);
        request.setSlotLevel(slotLevel);
        request.setTargetIds(targets.stream().map(EncounterParticipant::getId).toList());
        request.setGeniesWrath(wrath ? true : null);
        return request;
    }

    private boolean wrathUsed() {
        return vex.getTurnEconomy() != null && vex.getTurnEconomy().geniesWrathUsed();
    }

    @Nested
    @DisplayName("Genie's Wrath (Genie 1)")
    class Wrath {

        @Test
        @DisplayName("The attack options show it; a hit deals the proficiency bonus as the kind's type, once a turn")
        void onceATurn() {
            warlock(WarlockPatrons.GENIE, 1, "Efreeti");
            assertEquals("+2 fire", f.attacks.attackOptions(f.encounterId(), vex.getId(), player).getFirst()
                    .geniesWrath());

            attack(dagger(true), 15, 3);
            assertEquals(60 - 3 - 2, goblin.getHpCurrent());
            assertTrue(wrathUsed());
            assertTrue(f.descriptions().stream().anyMatch(d -> d.startsWith("Vex uses Genie's Wrath (+2 fire) after "
                    + "the hit on Goblin")), f.descriptions().toString());

            // A second hit this turn asks again and gets nothing more
            CombatFixture.newTurn(vex);
            vex.setTurnEconomy(vex.getTurnEconomy().withGeniesWrathUsed());
            attack(dagger(true), 15, 3);
            assertEquals(60 - 5 - 3, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("A miss spends nothing, so the turn's next hit deals it")
        void missKeepsIt() {
            warlock(WarlockPatrons.GENIE, 1, "Dao");
            attack(dagger(true), 2);
            assertFalse(wrathUsed());
            assertEquals(60, goblin.getHpCurrent());

            CombatFixture.newTurn(vex);
            attack(dagger(true), 15, 4);
            assertEquals(60 - 4 - 2, goblin.getHpCurrent());
            assertTrue(wrathUsed());
        }

        @Test
        @DisplayName("Not asked for, a hit deals none")
        void notAsked() {
            warlock(WarlockPatrons.GENIE, 1, "Marid");
            attack(dagger(false), 15, 3);
            assertEquals(57, goblin.getHpCurrent());
            assertFalse(wrathUsed());
        }

        @Test
        @DisplayName("Refused off Vex's turn, before its kind is chosen, and for another patron")
        void refused() {
            warlock(WarlockPatrons.GENIE, 1, "Djinni");
            f.turnOf(goblin);
            AttackRollRequest reaction = dagger(true);
            reaction.setCost(AttackCost.REACTION);
            assertThrows(ConflictException.class, () -> attack(reaction, 15, 3));
            assertEquals(60, goblin.getHpCurrent());

            f = new CombatFixture();
            f.weapons(weapon("Dagger", "Melee Weapon", "simple", "1d4", "piercing", "F", "L"));
            warlock(WarlockPatrons.GENIE, 1, null);
            assertNull(f.attacks.attackOptions(f.encounterId(), vex.getId(), player).getFirst().geniesWrath());
            assertThrows(ValidationException.class, () -> attack(dagger(true), 15, 3));

            f = new CombatFixture();
            f.weapons(weapon("Dagger", "Melee Weapon", "simple", "1d4", "piercing", "F", "L"));
            warlock("The Fiend", 1, "Djinni");
            assertThrows(ValidationException.class, () -> attack(dagger(true), 15, 3));
        }

        @Test
        @DisplayName("A spell attack's hit deals it too; the spell options offer it only for a spell with an attack roll")
        void spellAttack() {
            warlock(WarlockPatrons.GENIE, 1, "Djinni");
            f.spell("Fire Bolt", 0, FIRE_BOLT);
            f.spell("Cure Wounds", 1, CURE_WOUNDS);
            f.knows(vex, "Fire Bolt");
            f.knows(vex, "Cure Wounds");
            vex.setSpellSlotsCurrent(Map.of("1", new SpellSlot(1, 0)));

            List<SpellOption> options = f.spellcasting.spellOptions(f.encounterId(), vex.getId(), player);
            assertEquals("+2 thunder", options.stream().filter(o -> o.name().equals("Fire Bolt")).findFirst()
                    .orElseThrow().geniesWrath());
            assertNull(options.stream().filter(o -> o.name().equals("Cure Wounds")).findFirst().orElseThrow()
                    .geniesWrath());

            f.dice.script(15, 7);
            f.spellcasting.castSpell(f.encounterId(), cast("Fire Bolt", 0, List.of(goblin), true), vex.getId(),
                    player);
            assertEquals(60 - 7 - 2, goblin.getHpCurrent());
            assertTrue(wrathUsed());
        }

        @Test
        @DisplayName("Refused with a spell that makes no attack roll, before anything is spent")
        void noAttackRoll() {
            warlock(WarlockPatrons.GENIE, 1, "Djinni");
            f.spell("Cure Wounds", 1, CURE_WOUNDS);
            vex.setSpellSlotsCurrent(Map.of("1", new SpellSlot(1, 0)));
            assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(f.encounterId(),
                    cast("Cure Wounds", 1, List.of(vex), true), vex.getId(), player));
            assertEquals(1, vex.getSpellSlotsCurrent().get("1").remaining());
        }
    }

    @Nested
    @DisplayName("Elemental Gift (Genie 6)")
    class ElementalGift {

        @Test
        @DisplayName("Vex resists the kind's damage type in combat from Warlock 6")
        void resistance() {
            warlock(WarlockPatrons.GENIE, 6, "Marid");
            f.effectApplier.damage(f.encounter, null, "DM", vex, 10, "cold");
            assertEquals(35, vex.getHpCurrent());
        }

        @Test
        @DisplayName("A bonus action gives a flying speed of 30 feet for 10 minutes, spending a use")
        void flight() {
            warlock(WarlockPatrons.GENIE, 6, "Marid");
            vex.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry(ELEMENTAL_GIFT_POOL,
                    "Elemental Gift", "CLASS", null, 3, "proficiencyBonus", 3, "longRest", null, null,
                    "BONUS_ACTION", "wind", Map.of("effect", FeatureEffects.ELEMENTAL_GIFT)))));
            assertNull(TurnEconomies.speed(vex, f.objectMapper).get("fly"));

            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(vex.getId(), ELEMENTAL_GIFT_POOL, null, null, null,
                    null, null, null), player);

            assertEquals(2, vex.getResourcePoolsCurrent().getFirst().currentUses());
            assertTrue(vex.getTurnEconomy().bonusActionUsed());
            ConditionEntry entry = vex.getActiveConditions().stream()
                    .filter(c -> c.name().equals(FeatureEffects.ELEMENTAL_GIFT_ENTRY)).findFirst().orElseThrow();
            assertEquals(100, entry.duration());
            assertEquals(30, TurnEconomies.speed(vex, f.objectMapper).get("fly"));
            assertEquals(30, TurnEconomies.speed(vex, f.objectMapper).get("walk"));
        }
    }

    @Nested
    @DisplayName("Grasping Tentacles (Fathomless 10)")
    class GraspingTentacles {

        @Test
        @DisplayName("Any cast of Evard's black tentacles gives temporary hit points equal to the warlock level")
        void tempHitPoints() {
            warlock(WarlockPatrons.FATHOMLESS, 10, null);
            f.spell("Evard's Black Tentacles", 4, BLACK_TENTACLES);
            vex.setSpellSlotsCurrent(Map.of("5", new SpellSlot(2, 0)));
            f.spellcasting.castSpell(f.encounterId(), cast("Evard's Black Tentacles", 5, List.of(), false),
                    vex.getId(), player);
            assertEquals(10, vex.getHpTemp());
            assertTrue(f.descriptions().contains("Vex gains 10 temporary hit points from Grasping Tentacles"),
                    f.descriptions().toString());
        }

        @Test
        @DisplayName("Damage makes no concentration check on the spell; a 9th-level warlock still rolls one")
        void concentration() {
            warlock(WarlockPatrons.FATHOMLESS, 10, null);
            f.effectApplier.startConcentration(f.encounter, vex, "Evard's Black Tentacles", 4, 10);
            f.effectApplier.damage(f.encounter, null, "DM", vex, 30, "slashing");
            assertNotNull(vex.getConcentration());
            assertTrue(f.entries(CombatActionType.CONCENTRATION_CHECK).isEmpty());

            f = new CombatFixture();
            f.weapons(weapon("Dagger", "Melee Weapon", "simple", "1d4", "piercing", "F", "L"));
            warlock(WarlockPatrons.FATHOMLESS, 9, null);
            f.effectApplier.startConcentration(f.encounter, vex, "Evard's Black Tentacles", 4, 10);
            f.dice.script(2);
            f.effectApplier.damage(f.encounter, null, "DM", vex, 30, "slashing");
            assertNull(vex.getConcentration());
        }
    }
}
