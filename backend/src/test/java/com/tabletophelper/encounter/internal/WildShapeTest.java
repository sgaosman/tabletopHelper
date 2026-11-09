package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.dto.WildShapeHealRequest;
import com.tabletophelper.encounter.dto.WildShapeOptions;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * Wild Shape at the table (Druid 2, PHB p.66–67; Circle of the Moon, p.69). Rowan is a Druid 2 with 20 HP and AC 14 who
 * goes first; a goblin with 30 HP goes second. The beasts are as the Monster Manual has them: a wolf (CR 1/4, 11 HP,
 * AC 13, STR 12, DEX 15, CON 12), an ape (CR 1/2), a constrictor snake (CR 1/4, swims) and a brown bear (CR 1). The DM
 * acts unless a test says otherwise.
 */
class WildShapeTest {

    private static final String POOL = WildShapeEffects.POOL_ID;

    private static final String BITE = """
            {"actions": [
               {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4, "attackType": "MELEE_WEAPON",
                "effects": [{"effectType": "DAMAGE", "damageDice": "2d4 + 2", "damageType": "piercing"}]}
            ]}""";

    private CombatFixture f;
    private EncounterParticipant rowan;
    private EncounterParticipant goblin;
    private Monster wolf;
    private Monster ape;
    private Monster snake;
    private Monster bear;
    private Monster bandit;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        rowan = f.player("Rowan", 20, 14);
        goblin = f.monster("Goblin", 30, 10);
        druid(rowan, 2, null);
        rowan.getCharacter().setWisdom(16);
        rowan.getCharacter().setSavingThrowProficiencies(List.of("INT", "WIS"));
        rowan.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry(POOL, "Wild Shape", "CLASS",
                null, 2, "2", 2, "shortRest", null, null, "ACTION", "paw-print", Map.of("effect", "wild-shape")))));

        wolf = beast("Wolf", "1/4", 11, 13, "{\"walk\": 40}");
        wolf.setSkills("{\"perception\": \"+3\", \"stealth\": \"+4\"}");
        wolf.setSenses("[\"passive Perception 13\"]");
        wolf.setActionTemplates(MonsterTemplates.parse(BITE));
        ape = beast("Ape", "1/2", 19, 12, "{\"walk\": 30, \"climb\": 30}");
        snake = beast("Constrictor Snake", "1/4", 13, 12, "{\"walk\": 30, \"swim\": 30}");
        bear = beast("Brown Bear", "1", 34, 11, "{\"walk\": 40, \"climb\": 30}");
        bandit = Monster.builder().id(UUID.randomUUID()).name("Bandit").type("humanoid").challengeRating("1/8")
                .hitPoints(11).armourClass(12).build();
        for (Monster monster : List.of(wolf, ape, snake, bear, bandit)) {
            lenient().when(f.monsterRepository.findById(monster.getId())).thenReturn(Optional.of(monster));
        }
        lenient().when(f.monsterRepository.findByTypeIgnoreCase("beast")).thenReturn(List.of(bear, ape, snake, wolf));
    }

    private static void druid(EncounterParticipant pc, int level, String circle) {
        pc.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Druid", null, circle,
                level)));
        pc.getCharacter().setLevel(level);
    }

    private static Monster beast(String name, String cr, int hp, int ac, String speed) {
        return Monster.builder().id(UUID.randomUUID()).name(name).type("beast").challengeRating(cr).hitPoints(hp)
                .armourClass(ac).strength(12).dexterity(15).constitution(12).intelligence(3).wisdom(12).charisma(6)
                .speed(speed).source("MM").build();
    }

    private EncounterResponse shape(Monster beast) {
        return f.poolUse.usePool(f.encounterId(), UsePoolRequest.wildShape(rowan.getId(), POOL, beast.getId()), f.dmUserId);
    }

    // The DM deals untyped damage to Rowan
    private EncounterResponse damage(int amount) {
        DamageRequest request = new DamageRequest();
        request.setTargetId(rowan.getId());
        request.setAmount(amount);
        return f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId);
    }

    private void revert() {
        f.poolUse.usePool(f.encounterId(), new UsePoolRequest(rowan.getId(), POOL, null, "end", null), f.dmUserId);
    }

    private static int usesLeft(EncounterParticipant participant) {
        return participant.getResourcePoolsCurrent().stream().filter(p -> p.poolId().equals(POOL)).findFirst()
                .orElseThrow().currentUses();
    }

    private List<String> descriptions(CombatActionType type) {
        return f.entries(type).stream().map(entry -> entry.getDescription()).toList();
    }

    @Nested
    @DisplayName("Transforming and reverting")
    class Transforming {

        @Test
        @DisplayName("Wild Shape spends a use and the action: Rowan takes the wolf's hit points and AC, keeping his own")
        void becomesAWolf() {
            shape(wolf);

            assertEquals(11, rowan.getHpCurrent());
            assertEquals(11, rowan.getHpMax());
            assertEquals(13, rowan.getArmourClass());
            WildShapeForm form = WildShapeForm.of(rowan).orElseThrow();
            assertEquals("Wolf", form.beastName());
            assertEquals(20, form.normalHpCurrent());
            assertEquals(20, form.normalHpMax());
            assertEquals(14, form.normalArmourClass());
            assertEquals(Map.of("walk", 40), form.speed());
            assertEquals("passive Perception 13", form.senses());
            assertEquals(1, usesLeft(rowan));
            assertEquals(1, rowan.getTurnEconomy().actionsUsed());
            assertFalse(rowan.getTurnEconomy().bonusActionUsed());
            assertEquals(List.of("Rowan uses Wild Shape: becomes a Wolf (CR 1/4, 11 HP, AC 13) for up to 1 hour; 1/2 "
                    + "left"), descriptions(CombatActionType.POOL_USE));
            assertEquals(Map.of("walk", 40), TurnEconomies.speed(rowan, f.objectMapper));
        }

        @Test
        @DisplayName("Reverting is a bonus action that spends no use and gives back Rowan's own hit points and AC")
        void revertsEarly() {
            shape(wolf);
            CombatFixture.newTurn(rowan);
            rowan.setHpCurrent(5);

            revert();

            assertTrue(WildShapeForm.of(rowan).isEmpty());
            assertEquals(20, rowan.getHpCurrent());
            assertEquals(20, rowan.getHpMax());
            assertEquals(14, rowan.getArmourClass());
            assertEquals(1, usesLeft(rowan));
            assertTrue(rowan.getTurnEconomy().bonusActionUsed());
            assertTrue(descriptions(CombatActionType.CONDITION_REMOVE).contains(
                    "Rowan reverts from Wolf form to normal form (bonus action), with 20/20 HP and AC 14"));
        }

        @Test
        @DisplayName("A beast beyond the Beast Shapes limits, a creature that isn't a beast, a second Wild Shape and "
                + "reverting without one are refused, spending nothing")
        void refusals() {
            ConflictException tooStrong = assertThrows(ConflictException.class, () -> shape(ape));
            ConflictException swims = assertThrows(ConflictException.class, () -> shape(snake));
            ValidationException notABeast = assertThrows(ValidationException.class, () -> shape(bandit));
            ConflictException notShaped = assertThrows(ConflictException.class, WildShapeTest.this::revert);
            ValidationException noBeast = assertThrows(ValidationException.class, () -> f.poolUse.usePool(
                    f.encounterId(), new UsePoolRequest(rowan.getId(), POOL, null), f.dmUserId));
            shape(wolf);
            ConflictException again = assertThrows(ConflictException.class, () -> shape(wolf));

            assertEquals("Ape is challenge rating 1/2, above the limit of 1/4 for Rowan", tooStrong.getMessage());
            assertEquals("Constrictor Snake has a swimming speed, which needs druid level 4 for Rowan",
                    swims.getMessage());
            assertEquals("Bandit isn't a beast, so Wild Shape can't take its form", notABeast.getMessage());
            assertEquals("Rowan isn't in Wild Shape", notShaped.getMessage());
            assertEquals("Choose the beast Rowan becomes", noBeast.getMessage());
            assertEquals("Rowan is already in Wild Shape as a Wolf: revert first", again.getMessage());
            assertEquals(1, usesLeft(rowan));
        }

        @Test
        @DisplayName("A druid below 2nd level has no Wild Shape")
        void firstLevel() {
            druid(rowan, 1, null);
            ConflictException refused = assertThrows(ConflictException.class, () -> shape(wolf));
            assertEquals("Rowan has no Wild Shape: it comes at druid level 2", refused.getMessage());
        }

        @Test
        @DisplayName("A Circle of the Moon druid of 2nd level becomes a brown bear, CR 1, as a bonus action")
        void circleOfTheMoon() {
            druid(rowan, 2, "Circle of the Moon");

            shape(bear);

            assertEquals(34, rowan.getHpCurrent());
            assertEquals(11, rowan.getArmourClass());
            assertTrue(rowan.getTurnEconomy().bonusActionUsed());
            assertEquals(0, rowan.getTurnEconomy().actionsUsed());
        }

        @Test
        @DisplayName("The options list the beasts within the limits, by challenge rating, with the cost and duration")
        void options() {
            WildShapeOptions options = f.wildShape.options(f.encounterId(), rowan.getId(), f.dmUserId);
            assertEquals("1/4", options.maxChallengeRating());
            assertEquals("ACTION", options.cost());
            assertEquals(1, options.durationHours());
            assertEquals(List.of("Wolf"), options.beasts().stream().map(WildShapeOptions.Beast::name).toList());

            druid(rowan, 4, "Circle of the Moon");
            options = f.wildShape.options(f.encounterId(), rowan.getId(), f.dmUserId);
            assertEquals("BONUS_ACTION", options.cost());
            assertEquals(List.of("Constrictor Snake", "Wolf", "Ape", "Brown Bear"),
                    options.beasts().stream().map(WildShapeOptions.Beast::name).toList());
        }
    }

    @Nested
    @DisplayName("Hit points in beast form (PHB p.67)")
    class HitPoints {

        @Test
        @DisplayName("Damage that drops the wolf to 0 reverts Rowan, and the 4 damage left over carries to his own 20")
        void damageCarriesOver() {
            shape(wolf);

            damage(15);

            assertTrue(WildShapeForm.of(rowan).isEmpty());
            assertEquals(16, rowan.getHpCurrent());
            assertEquals(20, rowan.getHpMax());
            assertEquals(14, rowan.getArmourClass());
            assertEquals(LifeState.ALIVE, rowan.getLifeState());
            assertTrue(descriptions(CombatActionType.CONDITION_REMOVE).contains("Rowan reverts from Wolf form to "
                    + "normal form (its beast form drops to 0 HP; 4 damage carries over), with 16/20 HP and AC 14"));
        }

        @Test
        @DisplayName("Damage exactly to 0 reverts Rowan at his own hit points, not unconscious")
        void exactlyZero() {
            shape(wolf);

            damage(11);

            assertEquals(20, rowan.getHpCurrent());
            assertEquals(LifeState.ALIVE, rowan.getLifeState());
            assertFalse(CombatFixture.conditionNames(rowan).contains("unconscious"));
        }

        @Test
        @DisplayName("Damage that carries over past Rowan's own hit points drops him to 0, dying")
        void carriesToZero() {
            rowan.setHpCurrent(3);
            shape(wolf);

            damage(16);

            assertEquals(0, rowan.getHpCurrent());
            assertEquals(LifeState.DYING, rowan.getLifeState());
            assertTrue(CombatFixture.conditionNames(rowan).containsAll(List.of("unconscious", "prone")));
            assertTrue(WildShapeForm.of(rowan).isEmpty());
        }

        @Test
        @DisplayName("An attack on Rowan in beast form is against the wolf's AC and takes the wolf's hit points")
        void attackedInBeastForm() {
            shape(wolf);
            AttackRollRequest scimitar = new AttackRollRequest();
            scimitar.setTargetId(rowan.getId());
            scimitar.setAttackBonus(4);
            scimitar.setDamageDice("1d6+2");
            f.dice.script(9, 3);

            f.attacks.rollAttack(f.encounterId(), scimitar, goblin.getId(), f.dmUserId);

            assertTrue(f.entriesAbout(rowan).getLast().getDescription().contains("= 13 vs AC 13"));
            assertEquals(6, rowan.getHpCurrent());
            assertEquals(20, WildShapeForm.of(rowan).orElseThrow().normalHpCurrent());
        }

        @Test
        @DisplayName("Falling unconscious in beast form reverts Rowan; concentration carries on through the change")
        void unconsciousAndConcentration() {
            ConcentrationInstance faerieFire = ConcentrationInstance.start("Faerie Fire", 1, 1, 10);
            rowan.setConcentration(faerieFire);
            shape(wolf);
            assertEquals(faerieFire, rowan.getConcentration());

            ConditionRequest unconscious = new ConditionRequest();
            unconscious.setTargetId(rowan.getId());
            unconscious.setCondition("unconscious");
            f.conditionService.addCondition(f.encounterId(), unconscious, f.dmUserId);

            assertTrue(WildShapeForm.of(rowan).isEmpty());
            assertEquals(20, rowan.getHpCurrent());
            assertTrue(descriptions(CombatActionType.CONDITION_REMOVE).contains(
                    "Rowan reverts from Wolf form to normal form (falls unconscious), with 20/20 HP and AC 14"));
        }
    }

    @Nested
    @DisplayName("Statistics in beast form (PHB p.67)")
    class Statistics {

        @Test
        @DisplayName("Rowan has the wolf's STR, DEX and CON and his own WIS and save proficiencies, and the higher of "
                + "his and the wolf's skill bonus")
        void scores() {
            shape(wolf);

            assertEquals(12, f.stats.abilityScore(rowan, Ability.STR));
            assertEquals(15, f.stats.abilityScore(rowan, Ability.DEX));
            assertEquals(16, f.stats.abilityScore(rowan, Ability.WIS));
            // Turn order keeps Rowan's own Dexterity of 10
            assertEquals(10, CreatureStats.score(rowan, Ability.DEX));
            assertEquals(2, f.stats.saveModifier(rowan, Ability.DEX));
            assertEquals(5, f.stats.saveModifier(rowan, Ability.WIS));
            // Stealth: the wolf's +4 beats Rowan's DEX +2 without proficiency
            assertEquals(4, f.stats.skillModifier(rowan, "Stealth", Ability.DEX));
        }

        @Test
        @DisplayName("Rowan can't cast spells or attack with weapons in beast form, and his attack options are empty")
        void noSpellsOrWeapons() {
            shape(wolf);
            CastSpellRequest cast = new CastSpellRequest();
            cast.setSpellName("Thunderwave");
            cast.setSlotLevel(1);
            cast.setTargetIds(List.of(goblin.getId()));
            AttackRollRequest club = new AttackRollRequest();
            club.setTargetId(goblin.getId());
            club.setWeapon("Club");

            ConflictException spell = assertThrows(ConflictException.class,
                    () -> f.spellcasting.castSpell(f.encounterId(), cast, rowan.getId(), f.dmUserId));
            ConflictException weapon = assertThrows(ConflictException.class,
                    () -> f.attacks.rollAttack(f.encounterId(), club, rowan.getId(), f.dmUserId));

            assertEquals("Rowan can't cast spells in Wild Shape", spell.getMessage());
            assertEquals("Rowan is in Wild Shape as a Wolf: attack with the beast's actions", weapon.getMessage());
            assertEquals(List.of(), f.attacks.attackOptions(f.encounterId(), rowan.getId(), f.dmUserId));
        }

        @Test
        @DisplayName("Rowan's player bites with the wolf's Bite on his turn, charged to his action, but not off it")
        void beastAttack() {
            shape(wolf);
            CombatFixture.newTurn(rowan);
            UUID player = f.playerControlling(rowan);
            f.dice.script(10, 3, 3);

            f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(rowan.getId(), "Bite", "ACTION",
                    List.of(goblin.getId()), null, null, null, null), player);

            assertEquals(30 - 8, goblin.getHpCurrent());
            assertEquals(1, rowan.getTurnEconomy().actionsUsed());
            assertTrue(f.entriesAbout(goblin).getLast().getDescription()
                    .startsWith("Rowan uses Bite on Goblin: d20(10) + 4 = 14 vs AC 10"));

            f.turnOf(goblin);
            assertThrows(ForbiddenException.class, () -> f.monsterActions.monsterAction(f.encounterId(),
                    new MonsterActionRequest(rowan.getId(), "Bite", "ACTION", List.of(goblin.getId()), null, null,
                            null, null), player));
        }

        @Test
        @DisplayName("A player character not in Wild Shape has no monster actions, and a beast has no legendary ones")
        void noMonsterActions() {
            ValidationException none = assertThrows(ValidationException.class, () -> f.monsterActions.monsterAction(
                    f.encounterId(), new MonsterActionRequest(rowan.getId(), "Bite", "ACTION",
                            List.of(goblin.getId()), null, null, null, null), f.dmUserId));
            shape(wolf);
            ValidationException legendary = assertThrows(ValidationException.class, () -> f.monsterActions
                    .monsterAction(f.encounterId(), new MonsterActionRequest(rowan.getId(), "Bite", "LEGENDARY",
                            List.of(goblin.getId()), null, null, null, null), f.dmUserId));

            assertEquals("Rowan has no monster actions: only a monster, or a druid in Wild Shape, takes them",
                    none.getMessage());
            assertTrue(legendary.getMessage().startsWith("A druid in Wild Shape takes the beast's actions"));
        }
    }

    @Nested
    @DisplayName("Combat Wild Shape healing (PHB p.69)")
    class CombatHealing {

        @BeforeEach
        void moon() {
            druid(rowan, 2, "Circle of the Moon");
            Map<String, SpellSlot> slots = new LinkedHashMap<>();
            slots.put("1", new SpellSlot(3, 0, 0));
            rowan.setSpellSlotsCurrent(slots);
        }

        @Test
        @DisplayName("A Moon druid in beast form spends a slot and a bonus action to regain 1d8 per slot level")
        void heals() {
            shape(bear);
            CombatFixture.newTurn(rowan);
            rowan.setHpCurrent(20);
            f.dice.script(6);

            f.wildShape.heal(f.encounterId(), new WildShapeHealRequest(rowan.getId(), 1, null), f.dmUserId);

            assertEquals(26, rowan.getHpCurrent());
            assertEquals(1, rowan.getSpellSlotsCurrent().get("1").used());
            assertTrue(rowan.getTurnEconomy().bonusActionUsed());
            assertEquals(List.of("Rowan regains 6 HP (Combat Wild Shape as a Brown Bear, bonus action: level 1 spell "
                    + "slot (2/3 left), 1d8(6))"), descriptions(CombatActionType.HEAL));
        }

        @Test
        @DisplayName("Healing is refused outside Wild Shape, for a druid of another circle, and without the slot")
        void refusals() {
            ConflictException notShaped = assertThrows(ConflictException.class, () -> f.wildShape.heal(
                    f.encounterId(), new WildShapeHealRequest(rowan.getId(), 1, null), f.dmUserId));
            shape(bear);
            ConflictException noSlot = assertThrows(ConflictException.class, () -> f.wildShape.heal(
                    f.encounterId(), new WildShapeHealRequest(rowan.getId(), 2, null), f.dmUserId));
            druid(rowan, 2, "Circle of the Land");
            ConflictException land = assertThrows(ConflictException.class, () -> f.wildShape.heal(
                    f.encounterId(), new WildShapeHealRequest(rowan.getId(), 1, null), f.dmUserId));

            assertEquals("Rowan can heal with Combat Wild Shape only while in Wild Shape", notShaped.getMessage());
            assertEquals("Rowan has no level 2 spell slot left", noSlot.getMessage());
            assertEquals("Rowan isn't a Circle of the Moon druid, whose Combat Wild Shape this is", land.getMessage());
        }
    }

    @Nested
    @DisplayName("Undo (D176)")
    class Undo {

        @Test
        @DisplayName("Undoing Wild Shape gives back the use and the action, and Rowan's own hit points, maximum and AC")
        void undoTransform() {
            f.recordCommands();
            f.started();
            f.inTransaction(() -> shape(wolf));
            CombatCommand shaped = f.commands.getLast();

            f.inTransaction(() -> f.undoService.undo(f.encounterId(), new UndoRequest(shaped.getId(), 1),
                    f.dmUserId));

            assertTrue(WildShapeForm.of(rowan).isEmpty());
            assertEquals(20, rowan.getHpCurrent());
            assertEquals(20, rowan.getHpMax());
            assertEquals(14, rowan.getArmourClass());
            assertEquals(2, usesLeft(rowan));
            assertEquals(0, rowan.getTurnEconomy().actionsUsed());
        }

        @Test
        @DisplayName("Undoing the damage that reverted Rowan puts him back in wolf form, with its hit points and AC")
        void undoRevert() {
            f.recordCommands();
            f.started();
            f.inTransaction(() -> shape(wolf));
            f.inTransaction(() -> damage(15));
            assertTrue(WildShapeForm.of(rowan).isEmpty());
            CombatCommand damage = f.commands.getLast();

            f.inTransaction(() -> f.undoService.undo(f.encounterId(), new UndoRequest(damage.getId(), 1),
                    f.dmUserId));

            assertEquals("Wolf", WildShapeForm.of(rowan).orElseThrow().beastName());
            assertEquals(11, rowan.getHpCurrent());
            assertEquals(11, rowan.getHpMax());
            assertEquals(13, rowan.getArmourClass());
        }
    }
}
