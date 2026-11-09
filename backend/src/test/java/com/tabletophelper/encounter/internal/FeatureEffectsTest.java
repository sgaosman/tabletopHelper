package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackOption;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.reference.Item;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.StandardActions;
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * Class features used through the pool-use command and read by the rules (M17, 2014 PHB): Rage (p.48), Reckless Attack
 * (p.48), Second Wind (p.72), Lay on Hands (p.84), and Patient Defense and Step of the Wind (p.78). Grog is a Barbarian
 * 9 with Reckless Attack, Vex a Fighter 5, Aria a Paladin 5 and Kai a Monk 5; the DM acts unless a test says otherwise.
 */
class FeatureEffectsTest {

    private static final String RAGE = "class:barbarian-rage";
    private static final String SECOND_WIND = "class:fighter-second-wind";
    private static final String LAY_ON_HANDS = "class:paladin-lay-on-hands";
    private static final String KI = "class:monk-ki";

    private CombatFixture f;
    private EncounterParticipant grog;
    private EncounterParticipant vex;
    private EncounterParticipant aria;
    private EncounterParticipant kai;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        grog = f.player("Grog", 80, 15);
        vex = f.player("Vex", 44, 18);
        aria = f.player("Aria", 40, 18);
        kai = f.player("Kai", 33, 16);
        goblin = f.monster("Goblin", 30, 10);
        classes(grog, "Barbarian", 9);
        grog.getCharacter().setStrength(18);
        grog.getCharacter().setFeatures(List.of(new Feature("Rage", null, null),
                new Feature(ClassFeatures.RECKLESS_ATTACK, null, null)));
        grog.setResourcePoolsCurrent(pools(pool(RAGE, "Rage", "BONUS_ACTION", 4, "rage")));
        classes(vex, "Fighter", 5);
        vex.setResourcePoolsCurrent(pools(pool(SECOND_WIND, "Second Wind", "BONUS_ACTION", 1, "second-wind")));
        classes(aria, "Paladin", 5);
        aria.setResourcePoolsCurrent(pools(pool(LAY_ON_HANDS, "Lay on Hands", "ACTION", 25, "lay-on-hands")));
        classes(kai, "Monk", 5);
        kai.setResourcePoolsCurrent(pools(pool(KI, "Ki Points", "FREE", 5, "ki")));
        f.weapons(weapon("Greataxe", "Melee Weapon", "martial", "1d12", "slashing", "H", "2H"),
                weapon("Rapier", "Melee Weapon", "martial", "1d8", "piercing", "F"),
                weapon("Handaxe", "Melee Weapon", "simple", "1d6", "slashing", "L", "T"));
        CombatFixture.carries(grog, List.of("simple", "martial"), "Greataxe", "Rapier");
    }

    private static void classes(EncounterParticipant pc, String className, int level) {
        pc.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), className, null, null,
                level)));
        pc.getCharacter().setLevel(level);
    }

    private static ResourcePoolEntry pool(String poolId, String name, String cost, int max, String effect) {
        return new ResourcePoolEntry(poolId, name, "CLASS", null, max, String.valueOf(max), max, "longRest", null,
                null, cost, "zap", Map.of("effect", effect));
    }

    private static List<ResourcePoolEntry> pools(ResourcePoolEntry... entries) {
        return new ArrayList<>(List.of(entries));
    }

    // The DM uses the pool
    private void use(EncounterParticipant participant, String poolId, Integer uses, String option) {
        useAs(participant, poolId, uses, option, f.dmUserId);
    }

    // The DM uses the pool on the target
    private void useOn(EncounterParticipant participant, String poolId, Integer uses, String option, UUID target) {
        f.poolUse.usePool(f.encounterId(), new UsePoolRequest(participant.getId(), poolId, uses, option,
                List.of(target)), f.dmUserId);
    }

    private void useAs(EncounterParticipant participant, String poolId, Integer uses, String option, UUID userId) {
        f.poolUse.usePool(f.encounterId(), new UsePoolRequest(participant.getId(), poolId, uses, option, null),
                userId);
    }

    private static int usesLeft(EncounterParticipant participant, String poolId) {
        return participant.getResourcePoolsCurrent().stream().filter(p -> p.poolId().equals(poolId)).findFirst()
                .orElseThrow().currentUses();
    }

    private List<String> descriptions(CombatActionType type) {
        return f.entries(type).stream().map(entry -> entry.getDescription()).toList();
    }

    private static boolean raging(EncounterParticipant participant) {
        return ClassFeatures.raging(CombatFixture.conditionNames(participant));
    }

    private void damage(EncounterParticipant target, int amount, String type) {
        DamageRequest request = new DamageRequest();
        request.setTargetId(target.getId());
        request.setAmount(amount);
        request.setDamageType(type);
        f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId);
    }

    @Nested
    @DisplayName("Rage (PHB p.48)")
    class Rage {

        @BeforeEach
        void grogsTurn() {
            f.turnOf(grog);
        }

        @Test
        @DisplayName("A rage costs a bonus action and a use, lasts 10 rounds and says what it gives")
        void starts() {
            use(grog, RAGE, null, null);

            assertTrue(raging(grog));
            ConditionEntry marker = grog.getActiveConditions().getFirst();
            assertEquals(ClassFeatures.RAGE_ROUNDS, marker.duration());
            assertEquals(grog.getId(), marker.endRule().participantId());
            assertEquals(3, usesLeft(grog, RAGE));
            assertTrue(grog.getTurnEconomy().bonusActionUsed());
            assertEquals(List.of("Grog uses Rage (bonus action): rages for 1 minute, with +3 damage on melee weapon "
                    + "attacks using Strength, resistance to bludgeoning, piercing and slashing damage, and advantage "
                    + "on Strength checks and saving throws, but can't cast spells or concentrate on them; 3/4 left"),
                    descriptions(CombatActionType.POOL_USE));
        }

        @Test
        @DisplayName("Starting a rage ends the barbarian's concentration, and a raging barbarian can't cast")
        void concentrationAndCasting() {
            grog.setConcentration(ConcentrationInstance.start("Hunter's Mark", 1, 1, 600));

            use(grog, RAGE, null, null);

            assertNull(grog.getConcentration());
            assertTrue(descriptions(CombatActionType.CONCENTRATION_LOST).getFirst().endsWith("(starts raging)"));
            CastSpellRequest cast = new CastSpellRequest();
            cast.setSpellName("Hunter's Mark");
            cast.setSlotLevel(1);
            cast.setTargetIds(List.of(goblin.getId()));
            ConflictException refused = assertThrows(ConflictException.class,
                    () -> f.spellcasting.castSpell(f.encounterId(), cast, grog.getId(), f.dmUserId));
            assertEquals("Grog can't cast spells while raging", refused.getMessage());
        }

        @Test
        @DisplayName("A second rage while raging, or one in heavy armour, is a 409 that spends nothing")
        void refusals() {
            use(grog, RAGE, null, null);
            CombatFixture.newTurn(grog);
            ConflictException again = assertThrows(ConflictException.class, () -> use(grog, RAGE, null, null));
            assertEquals("Grog is already raging", again.getMessage());
            assertEquals(3, usesLeft(grog, RAGE));

            grog.setActiveConditions(null);
            f.wears(grog, CombatFixture.armour("Plate", Item.ArmourCategory.HEAVY, 18, true));
            ConflictException plate = assertThrows(ConflictException.class, () -> use(grog, RAGE, null, null));
            assertEquals("Rage gives no benefits in heavy armour, and Grog wears Plate", plate.getMessage());
            assertEquals(3, usesLeft(grog, RAGE));
        }

        @Test
        @DisplayName("Rage halves bludgeoning, piercing and slashing damage, but not fire")
        void resistance() {
            use(grog, RAGE, null, null);

            damage(grog, 11, "slashing");
            assertEquals(75, grog.getHpCurrent());
            damage(grog, 10, "fire");
            assertEquals(65, grog.getHpCurrent());
        }

        @Test
        @DisplayName("Ending a rage early costs a bonus action on the barbarian's turn and spends no use")
        void endsEarly() {
            use(grog, RAGE, null, null);
            CombatFixture.newTurn(grog);

            use(grog, RAGE, null, "end");

            assertFalse(raging(grog));
            assertEquals(3, usesLeft(grog, RAGE));
            assertTrue(grog.getTurnEconomy().bonusActionUsed());
            assertTrue(descriptions(CombatActionType.POOL_USE).contains("Grog ends their Rage (bonus action)"));
            assertTrue(descriptions(CombatActionType.CONDITION_REMOVE).contains("Rage ends on Grog (ended early)"));
            assertThrows(ConflictException.class, () -> use(grog, RAGE, null, "end"));
        }

        @Test
        @DisplayName("Dropping to 0 HP ends the rage")
        void unconscious() {
            use(grog, RAGE, null, null);

            // 90 fire on 80 HP leaves 10 over, short of instant death
            damage(grog, 90, "fire");

            assertEquals(LifeState.DYING, grog.getLifeState());
            assertFalse(raging(grog));
            assertTrue(descriptions(CombatActionType.CONDITION_REMOVE).contains("Rage ends on Grog (unconscious)"));
        }

        @Test
        @DisplayName("At the end of the barbarian's turn a rage ends unless it attacked or took damage since its last "
                + "turn, and the next turn starts the count again")
        void endOfTurn() {
            grog.setInitiative(20);
            goblin.setInitiative(10);
            vex.setInitiative(1);
            aria.setInitiative(1);
            kai.setInitiative(1);
            use(grog, RAGE, null, null);
            damage(grog, 4, "fire");

            f.turns.advanceTurn(f.encounterId(), f.dmUserId);
            assertTrue(raging(grog), "it took damage this turn");
            assertFalse(grog.getTurnEconomy().attackedOrDamaged(), "the count starts again");

            // Back to Grog, who neither attacks nor is hurt
            f.turnOf(grog);
            f.turns.advanceTurn(f.encounterId(), f.dmUserId);
            assertFalse(raging(grog));
            assertTrue(descriptions(CombatActionType.CONDITION_REMOVE).contains(
                    "Rage ends on Grog (it neither attacked nor took damage since its last turn)"));
        }

        @Test
        @DisplayName("Damage taken on another creature's turn survives the start of the barbarian's own turn")
        void damageBetweenTurns() {
            grog.setTurnEconomy(TurnEconomy.fresh());
            damage(grog, 4, "fire");

            f.effectApplier.refreshTurn(f.encounter, grog);

            assertTrue(grog.getTurnEconomy().attackedOrDamaged());
        }

        @Test
        @DisplayName("A raging barbarian adds its Rage Damage to a melee Strength attack, not doubled on a critical hit")
        void rageDamage() {
            use(grog, RAGE, null, null);

            // A natural 20: the d12 twice (5 + 6) + STR 4 + Rage 3 = 18
            f.dice.script(20, 5, 6);
            f.attacks.rollAttack(f.encounterId(), attack("Greataxe", null), grog.getId(), f.dmUserId);

            assertEquals(12, goblin.getHpCurrent());
            assertTrue(f.entriesAbout(goblin).getFirst().getDescription()
                    .startsWith("Grog uses Greataxe (Rage +3) on Goblin"));
            assertTrue(grog.getTurnEconomy().attackedOrDamaged());
        }

        @Test
        @DisplayName("A finesse weapon used with Dexterity gets no Rage Damage")
        void noRageDamageWithDexterity() {
            grog.getCharacter().setStrength(10);
            grog.getCharacter().setDexterity(16);
            use(grog, RAGE, null, null);

            // 10 + 5 hits AC 10; the d8 4 + DEX 3 = 7
            f.dice.script(10, 4);
            f.attacks.rollAttack(f.encounterId(), attack("Rapier", null), grog.getId(), f.dmUserId);

            assertEquals(23, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("Undo puts back the rage's use, its bonus action, the concentration it ended and the marker")
        void undo() {
            f.recordCommands();
            f.started();
            ConcentrationInstance mark = ConcentrationInstance.start("Hunter's Mark", 1, 1, 600);
            grog.setConcentration(mark);
            f.inTransaction(() -> f.poolUse.usePool(f.encounterId(), new UsePoolRequest(grog.getId(), RAGE, null),
                    f.dmUserId));
            assertTrue(raging(grog));
            CombatCommand rage = f.commands.getLast();

            f.inTransaction(() -> f.undoService.undo(f.encounterId(), new UndoRequest(rage.getId(), 1), f.dmUserId));

            assertFalse(raging(grog));
            assertEquals(4, usesLeft(grog, RAGE));
            assertFalse(grog.getTurnEconomy().bonusActionUsed());
            assertEquals(mark, grog.getConcentration());
        }
    }

    @Nested
    @DisplayName("Reckless Attack (PHB p.48)")
    class Reckless {

        @BeforeEach
        void grogsTurn() {
            f.turnOf(grog);
        }

        @Test
        @DisplayName("The first attack of the turn made recklessly rolls with advantage, and so do the turn's other "
                + "melee Strength attacks")
        void advantage() {
            // 3 and 15 with advantage: 15 + 7 hits; the d12 6 + 4
            f.dice.script(3, 15, 6);
            f.attacks.rollAttack(f.encounterId(), attack("Greataxe", true), grog.getId(), f.dmUserId);

            assertEquals(20, goblin.getHpCurrent());
            assertTrue(ClassFeatures.reckless(CombatFixture.conditionNames(grog)));
            assertTrue(f.entriesAbout(goblin).getFirst().getDescription()
                    .startsWith("Grog uses Greataxe (Reckless Attack) on Goblin: 2d20(3, 15) with advantage → 15"));

            // Grog has Extra Attack at 9th level: the second attack has advantage without asking again
            grog.getCharacter().setFeatures(new ArrayList<>(List.of(new Feature("Extra Attack", null, null),
                    new Feature(ClassFeatures.RECKLESS_ATTACK, null, null))));
            grog.setTurnEconomy(new TurnEconomy(1, 1, true, 1, false, false, false, false, false, false, false,
                    false));
            f.dice.script(2, 14, 1);
            f.attacks.rollAttack(f.encounterId(), attack("Greataxe", null), grog.getId(), f.dmUserId);
            assertEquals(15, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("Attacks against a reckless barbarian have advantage until the start of its next turn")
        void againstIt() {
            f.dice.script(15, 2, 6);
            f.attacks.rollAttack(f.encounterId(), attack("Greataxe", true), grog.getId(), f.dmUserId);

            AttackRollRequest claws = new AttackRollRequest();
            claws.setTargetId(grog.getId());
            claws.setAttackBonus(4);
            claws.setDamageDice("1d6");
            f.dice.script(2, 17, 3);
            f.attacks.rollAttack(f.encounterId(), claws, goblin.getId(), f.dmUserId);

            assertTrue(f.entriesAbout(grog).getFirst().getDescription().contains("2d20(2, 17) with advantage"));
        }

        @Test
        @DisplayName("Reckless Attack after the turn's first attack, or with a Dexterity weapon, or without the "
                + "feature, is refused")
        void refusals() {
            grog.setTurnEconomy(new TurnEconomy(1, 1, true, 1, false, false, false, false, false, false, false,
                    false));
            ConflictException late = assertThrows(ConflictException.class, () -> f.attacks.rollAttack(
                    f.encounterId(), attack("Greataxe", true), grog.getId(), f.dmUserId));
            assertEquals("Reckless Attack is chosen on the first attack of the turn, and Grog has already attacked",
                    late.getMessage());

            CombatFixture.newTurn(grog);
            grog.getCharacter().setStrength(10);
            grog.getCharacter().setDexterity(16);
            ValidationException finesse = assertThrows(ValidationException.class, () -> f.attacks.rollAttack(
                    f.encounterId(), attack("Rapier", true), grog.getId(), f.dmUserId));
            assertEquals("Reckless Attack is for melee weapon attacks using Strength, and this Rapier attack isn't one",
                    finesse.getMessage());

            grog.getCharacter().setFeatures(List.of());
            ValidationException none = assertThrows(ValidationException.class, () -> f.attacks.rollAttack(
                    f.encounterId(), attack("Greataxe", true), grog.getId(), f.dmUserId));
            assertEquals("Grog doesn't have Reckless Attack", none.getMessage());
            assertNull(grog.getActiveConditions());
        }

        @Test
        @DisplayName("The attack options offer Reckless Attack with a Strength melee weapon only")
        void options() {
            // DEX 20 over STR 18: the finesse rapier attacks with Dexterity
            grog.getCharacter().setDexterity(20);
            List<AttackOption> options = f.attacks.attackOptions(f.encounterId(), grog.getId(), f.dmUserId);

            assertTrue(options.stream().filter(o -> o.weapon().equals("Greataxe")).findFirst().orElseThrow()
                    .recklessAttack());
            assertFalse(options.stream().filter(o -> o.weapon().equals("Rapier")).findFirst().orElseThrow()
                    .recklessAttack());
        }
    }

    @Nested
    @DisplayName("Second Wind (PHB p.72)")
    class SecondWind {

        @Test
        @DisplayName("Second Wind heals the fighter 1d10 + its fighter level as a bonus action")
        void heals() {
            f.turnOf(vex);
            vex.setHpCurrent(20);
            f.dice.script(7);

            use(vex, SECOND_WIND, null, null);

            assertEquals(32, vex.getHpCurrent());
            assertEquals(0, usesLeft(vex, SECOND_WIND));
            assertTrue(vex.getTurnEconomy().bonusActionUsed());
            assertEquals(List.of("Vex regains 12 HP (Second Wind: 1d10(7) + 5 = 12)"),
                    descriptions(CombatActionType.HEAL));
            assertEquals(12, f.entries(CombatActionType.HEAL).getFirst().getHealingDone());
        }

        @Test
        @DisplayName("Second Wind takes no option, no target and one use at a time")
        void refusals() {
            f.turnOf(vex);
            assertThrows(ValidationException.class, () -> use(vex, SECOND_WIND, null, "cure"));
            assertThrows(ValidationException.class, () -> useOn(vex, SECOND_WIND, null, null, goblin.getId()));
            assertThrows(ValidationException.class, () -> use(vex, SECOND_WIND, 2, null));
            assertEquals(1, usesLeft(vex, SECOND_WIND));
        }
    }

    @Nested
    @DisplayName("Lay on Hands (PHB p.84)")
    class LayOnHands {

        @BeforeEach
        void ariasTurn() {
            f.turnOf(aria);
        }

        @Test
        @DisplayName("Lay on Hands restores as many hit points as the points spent to the creature touched, as an "
                + "action")
        void healsAnother() {
            vex.setHpCurrent(10);

            useOn(aria, LAY_ON_HANDS, 15, null, vex.getId());

            assertEquals(25, vex.getHpCurrent());
            assertEquals(10, usesLeft(aria, LAY_ON_HANDS));
            assertEquals(1, aria.getTurnEconomy().actionsUsed());
            assertEquals(List.of("Aria uses Lay on Hands ×15 on Vex to restore 15 HP; 10/25 left"),
                    descriptions(CombatActionType.POOL_USE));
            assertEquals(List.of("Aria heals Vex for 15 HP (Lay on Hands)"), descriptions(CombatActionType.HEAL));
        }

        @Test
        @DisplayName("Without a target the paladin heals itself, and a Dying creature wakes")
        void selfAndDying() {
            aria.setHpCurrent(30);
            use(aria, LAY_ON_HANDS, 5, null);
            assertEquals(35, aria.getHpCurrent());

            CombatFixture.newTurn(aria);
            CombatFixture.downed(vex, LifeState.DYING, 1);
            useOn(aria, LAY_ON_HANDS, 1, "heal", vex.getId());
            assertEquals(LifeState.ALIVE, vex.getLifeState());
            assertEquals(1, vex.getHpCurrent());
        }

        @Test
        @DisplayName("More points than the pool holds is a 409, and undead, constructs and the dead can't be touched")
        void refusals() {
            assertThrows(ConflictException.class, () -> useOn(aria, LAY_ON_HANDS, 26, null, vex.getId()));
            EncounterParticipant zombie = f.monster("Zombie", 22, 8);
            zombie.getMonster().setType("undead");
            ValidationException undead = assertThrows(ValidationException.class,
                    () -> useOn(aria, LAY_ON_HANDS, 5, null, zombie.getId()));
            assertEquals("Lay on Hands has no effect on undead", undead.getMessage());
            CombatFixture.dead(vex);
            assertThrows(ConflictException.class, () -> useOn(aria, LAY_ON_HANDS, 5, null, vex.getId()));
            assertEquals(25, usesLeft(aria, LAY_ON_HANDS));
            assertEquals(0, aria.getTurnEconomy() == null ? 0 : aria.getTurnEconomy().actionsUsed());
        }

        @Test
        @DisplayName("Curing spends 5 points for each disease or poison and is logged for the DM; other amounts are "
                + "refused")
        void cure() {
            assertThrows(ValidationException.class, () -> useOn(aria, LAY_ON_HANDS, 7, "cure", vex.getId()));

            useOn(aria, LAY_ON_HANDS, 10, "cure", vex.getId());

            assertEquals(15, usesLeft(aria, LAY_ON_HANDS));
            assertEquals(List.of("Aria uses Lay on Hands ×10 on Vex to cure 2 diseases or poisons; the DM "
                    + "removes what it cures; 15/25 left"), descriptions(CombatActionType.POOL_USE));
            assertTrue(f.entries(CombatActionType.HEAL).isEmpty());
        }
    }

    @Nested
    @DisplayName("Patient Defense and Step of the Wind (PHB p.78)")
    class Ki {

        private UUID player;

        @BeforeEach
        void kaisTurn() {
            f.turnOf(kai);
            player = f.playerControlling(kai);
        }

        @Test
        @DisplayName("Patient Defense spends 1 ki and the bonus action to take the Dodge action")
        void patientDefense() {
            useAs(kai, KI, null, "patient-defense", player);

            assertEquals(4, usesLeft(kai, KI));
            assertTrue(kai.getTurnEconomy().bonusActionUsed());
            assertEquals(0, kai.getTurnEconomy().actionsUsed());
            ConditionEntry dodge = kai.getActiveConditions().getFirst();
            assertEquals(StandardActions.DODGING, dodge.name());
            assertEquals("Patient Defense", dodge.sourceSpellName());
            assertTrue(descriptions(CombatActionType.POOL_USE).getFirst()
                    .startsWith("Kai uses Ki Points (bonus action) for Patient Defense: takes the Dodge action"));
        }

        @Test
        @DisplayName("Step of the Wind dashes or disengages for 1 ki and the bonus action; a second bonus action is a "
                + "409 for the player")
        void stepOfTheWind() {
            useAs(kai, KI, null, "step-of-the-wind-dash", player);

            assertTrue(kai.getTurnEconomy().dashed());
            assertTrue(kai.getTurnEconomy().bonusActionUsed());
            ConflictException second = assertThrows(ConflictException.class,
                    () -> useAs(kai, KI, null, "step-of-the-wind-disengage", player));
            assertEquals("It has already taken its bonus action this turn", second.getMessage());
            assertEquals(4, usesLeft(kai, KI));

            CombatFixture.newTurn(kai);
            useAs(kai, KI, null, "step-of-the-wind-disengage", player);
            assertTrue(kai.getTurnEconomy().disengaged());
        }

        @Test
        @DisplayName("Ki without an option is spent as before, at no cost from the turn; an unknown option, or more "
                + "than 1 ki for one, is a 400")
        void plainAndRefused() {
            useAs(kai, KI, 2, null, player);
            assertEquals(3, usesLeft(kai, KI));
            assertFalse(kai.getTurnEconomy() != null && kai.getTurnEconomy().bonusActionUsed());

            assertThrows(ValidationException.class, () -> useAs(kai, KI, null, "flurry", player));
            assertThrows(ValidationException.class, () -> useAs(kai, KI, 2, "patient-defense", player));
            assertEquals(3, usesLeft(kai, KI));
        }
    }

    @Test
    @DisplayName("A pool without an effect takes no option or target")
    void plainPool() {
        vex.setResourcePoolsCurrent(pools(new ResourcePoolEntry("class:test", "Knack", "CLASS", null, 2, "2", 2,
                "longRest", null, null, "FREE", "zap", null)));
        f.turnOf(vex);
        assertThrows(ValidationException.class, () -> use(vex, "class:test", null, "end"));
        assertThrows(ValidationException.class, () -> useOn(vex, "class:test", null, null, goblin.getId()));
    }

    // An attack at the goblin with a weapon Grog carries
    private AttackRollRequest attack(String weapon, Boolean reckless) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setWeapon(weapon);
        request.setRecklessAttack(reckless);
        return request;
    }
}
