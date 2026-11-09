package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.Feature;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.dto.AttackCost;
import com.tabletophelper.encounter.dto.AttackOption;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.StandardActionRequest;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.rules.combat.StandardActions;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.tabletophelper.reference.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.tabletophelper.encounter.internal.CombatFixture.weapon;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Weapon attacks (D161, D164). From the old CombatServiceTest: the attack rolls 10.14 to 10.21 and the on-turn
 * access cases, now attacks entered by hand, which are Manual Modifiers. A natural 20 or a forced critical doubles
 * the dice; the dice come from the Rng, where the old test mocked ThreadLocalRandom and DiceRoller. The weapon
 * attacks the server works out use the PHB's weapons as the items table holds them.
 */
class AttackServiceTest {

    private CombatFixture f;
    private EncounterParticipant rogue;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        rogue = f.player("Rogue", 30, 14);
        f.weapons(
                weapon("Longsword", "Melee Weapon", "martial", "1d8", "slashing", "V"),
                weapon("Rapier", "Melee Weapon", "martial", "1d8", "piercing", "F"),
                weapon("Greatsword", "Melee Weapon", "martial", "2d6", "slashing", "H", "2H"),
                weapon("Dagger", "Melee Weapon", "simple", "1d4", "piercing", "F", "L", "T"),
                weapon("Longbow", "Ranged Weapon", "martial", "1d8", "piercing", "A", "H", "2H"));
    }

    private static AttackRollRequest request(EncounterParticipant target, int bonus, String dice) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setAttackBonus(bonus);
        request.setDamageDice(dice);
        return request;
    }

    private static AttackRollRequest weaponRequest(EncounterParticipant target, String weapon) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setWeapon(weapon);
        return request;
    }

    private void attack(AttackRollRequest request, int... rolls) {
        f.dice.script(rolls);
        f.attacks.rollAttack(f.encounterId(), request, rogue.getId(), f.dmUserId);
    }

    // The attack's own entry, about its target
    private String entryAbout(EncounterParticipant target) {
        return f.entriesAbout(target).getFirst().getDescription();
    }

    @Nested
    @DisplayName("Attacks entered by hand")
    class Entered {

        @Test
        @DisplayName("10.14 A normal hit deals the damage, with its type")
        void normalHit() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            AttackRollRequest request = request(goblin, 5, "1d8+3");
            request.setDamageType("slashing");

            // 12 + 5 = 17 against AC 15, then 5 on the d8
            attack(request, 12, 5);

            assertEquals(12, goblin.getHpCurrent());
            CombatLog entry = f.entriesAbout(goblin).getFirst();
            assertEquals(12, entry.getRollValue());
            assertEquals(17, entry.getRollTotal());
            assertEquals(8, entry.getDamageDealt());
            assertEquals("Rogue attacks Goblin: d20(12) + 5 = 17 vs AC 15 — hit, 8 slashing damage.", entry.getDescription());
        }

        @Test
        @DisplayName("10.15 A miss does no damage")
        void miss() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 18);

            attack(request(goblin, 5, "1d8+3"), 8);

            assertEquals(20, goblin.getHpCurrent());
            assertEquals("Rogue attacks Goblin: d20(8) + 5 = 13 vs AC 18 — miss.", entryAbout(goblin));
        }

        @Test
        @DisplayName("10.16 A natural 20 hits whatever the AC, with critical damage")
        void naturalTwentyAlwaysHits() {
            EncounterParticipant goblin = f.monster("Goblin", 30, 25);

            // The d8 twice: 4 + 6 + 3 = 13
            attack(request(goblin, 2, "1d8+3"), 20, 4, 6);

            assertEquals(17, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("10.17 A natural 1 misses whatever the total")
        void naturalOneAlwaysMisses() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 10);

            attack(request(goblin, 15, "1d8+3"), 1);

            assertEquals(20, goblin.getHpCurrent());
            assertTrue(entryAbout(goblin).endsWith("natural 1, miss."));
        }

        @Test
        @DisplayName("10.18 Advantage uses the higher of two d20 rolls")
        void advantage() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            AttackRollRequest request = request(goblin, 3, "1d8+3");
            request.setAdvantage(true);

            attack(request, 8, 15, 5);

            assertEquals(12, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("10.19 Disadvantage uses the lower of two d20 rolls")
        void disadvantage() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            AttackRollRequest request = request(goblin, 3, "1d8+3");
            request.setAdvantage(false);

            attack(request, 8, 15);

            assertEquals(20, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("10.20 The DM's forced critical hits as a critical hit whatever the roll, logged as a Manual Modifier")
        void forcedCritical() {
            EncounterParticipant goblin = f.monster("Goblin", 30, 15);
            AttackRollRequest request = request(goblin, 5, "1d8+3");
            request.setForceCrit(true);

            attack(request, 5, 6, 6);

            assertEquals(15, goblin.getHpCurrent());
            assertEquals(List.of("Manual Modifier (DM): Rogue's attack uses attack bonus +5 and 1d8 + 3 damage.",
                    "Manual Modifier (DM): Rogue's attack is a critical hit."), f.descriptions().subList(0, 2));
            assertTrue(entryAbout(goblin).contains("critical hit (forced)"));
        }

        @Test
        @DisplayName("10.21 Separate attacks apply their damage independently")
        void separateAttacks() {
            EncounterParticipant goblin = f.monster("Goblin", 30, 15);

            attack(request(goblin, 5, "1d8+3"), 15, 5);
            assertEquals(22, goblin.getHpCurrent());
            attack(request(goblin, 5, "1d8+3"), 12, 3);

            assertEquals(16, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("An attack's damage meets the target's defences, which weapon attacks skipped before (D161)")
        void weaponDamageMeetsDefences() {
            EncounterParticipant skeleton = f.monster("Skeleton", 20, 10);
            skeleton.getMonster().setDamageVulnerabilities("[\"bludgeoning\"]");
            AttackRollRequest request = request(skeleton, 5, "1d6+2");
            request.setDamageType("Bludgeoning");

            attack(request, 15, 4);

            assertEquals(8, skeleton.getHpCurrent());
        }

        @Test
        @DisplayName("Without a within5Ft value the attacker counts as within 5 feet: a hit on an unconscious PC is critical")
        void withinFiveFeetByDefault() {
            EncounterParticipant fighter = f.player("Fighter", 30, 12);
            CombatFixture.downed(fighter, LifeState.DYING, 0);
            AttackRollRequest request = request(fighter, 5, "4");
            request.setWithin5Ft(null);

            attack(request, 3, 14);

            assertEquals(2, fighter.getDeathSaveFailures());
        }

        @Test
        @DisplayName("The DM's attack as no one is logged as the DM's Manual Modifier (D164)")
        void dmAsNoOne() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 10);
            f.dice.script(15, 3);

            f.attacks.rollAttack(f.encounterId(), request(goblin, 4, "1d6"), null, f.dmUserId);

            assertEquals(List.of("Manual Modifier (DM): The DM's attack uses attack bonus +4 and 1d6 damage.",
                    "DM attacks Goblin: d20(15) + 4 = 19 vs AC 10 — hit, 3 damage."), f.descriptions());
        }

        @Test
        @DisplayName("A player's attack entered by hand is a Manual Modifier: used and logged while the campaign allows it")
        void playerEntersAnAttack() {
            UUID playerUserId = f.playerControlling(rogue);
            EncounterParticipant goblin = f.monster("Goblin", 30, 15);
            AttackRollRequest request = request(goblin, 5, "1d6");
            request.setForceCrit(true);

            f.dice.script(3, 4, 4);
            f.attacks.rollAttack(f.encounterId(), request, rogue.getId(), playerUserId);

            assertEquals(22, goblin.getHpCurrent());
            assertEquals(List.of("Manual Modifier (player): Rogue's attack uses attack bonus +5 and 1d6 damage.",
                    "Manual Modifier (player): Rogue's attack is a critical hit."), f.descriptions().subList(0, 2));
        }

        @Test
        @DisplayName("A player's attack entered by hand is a 403 once the campaign disallows Manual Modifiers; nothing rolls")
        void playerCannotEnterAnAttackWhenDisallowed() {
            UUID playerUserId = f.playerControlling(rogue);
            f.campaign.setAllowPlayerManualModifiers(false);
            EncounterParticipant goblin = f.monster("Goblin", 7, 15);

            ForbiddenException ex = assertThrows(ForbiddenException.class,
                    () -> f.attacks.rollAttack(f.encounterId(), request(goblin, 5, "1d6"), rogue.getId(), playerUserId));

            assertEquals("This campaign doesn't allow players to enter Manual Modifiers", ex.getMessage());
            assertEquals(7, goblin.getHpCurrent());
            assertTrue(f.log.isEmpty());
        }

        @Test
        @DisplayName("An attack with neither a weapon nor both numbers, or with a weapon's extras, is a 400")
        void incompleteOrMixed() {
            EncounterParticipant goblin = f.monster("Goblin", 7, 15);
            AttackRollRequest noDice = request(goblin, 5, null);
            AttackRollRequest withTrade = request(goblin, 5, "1d6");
            withTrade.setPowerAttack(true);

            ValidationException incomplete = assertThrows(ValidationException.class, () -> attack(noDice));
            ValidationException mixed = assertThrows(ValidationException.class, () -> attack(withTrade));

            assertEquals("Choose a weapon, or enter the attack bonus and damage dice", incomplete.getMessage());
            assertEquals("Great Weapon Master, Sharpshooter and extra bonuses go with a weapon attack; an attack entered "
                    + "by hand includes them in its numbers", mixed.getMessage());
        }
    }

    @Nested
    @DisplayName("Weapon attacks the server works out (D164)")
    class Weapons {

        @BeforeEach
        void armRogue() {
            // STR 16 (+3) and DEX 18 (+4), proficient at +2 with simple weapons, rapiers and longswords
            rogue.getCharacter().setStrength(16);
            rogue.getCharacter().setDexterity(18);
            CombatFixture.carries(rogue, List.of("simple", "hand crossbow", "longsword", "rapier", "shortsword"),
                    "Longsword", "Rapier", "Greatsword", "Dagger", "Longbow");
        }

        @Test
        @DisplayName("A longsword attacks with STR, adding proficiency to hit, and deals its d8 plus the STR modifier")
        void longsword() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);

            // 12 + 5 = 17 against AC 15, then 5 on the d8 + 3
            attack(weaponRequest(goblin, "Longsword"), 12, 5);

            assertEquals(12, goblin.getHpCurrent());
            assertEquals(List.of("Rogue uses Longsword on Goblin: d20(12) + 5 = 17 vs AC 15 — hit, 8 slashing damage."),
                    f.descriptions());
        }

        @Test
        @DisplayName("A finesse rapier attacks with the higher DEX; a weapon without proficiency adds none")
        void finesseAndProficiency() {
            EncounterParticipant goblin = f.monster("Goblin", 30, 10);

            attack(weaponRequest(goblin, "rapier"), 10, 2);
            attack(weaponRequest(goblin, "Greatsword"), 10, 1, 1);

            assertEquals("Rogue uses Rapier on Goblin: d20(10) + 6 = 16 vs AC 10 — hit, 6 piercing damage.",
                    f.entriesAbout(goblin).get(0).getDescription());
            assertEquals("Rogue uses Greatsword on Goblin: d20(10) + 3 = 13 vs AC 10 — hit, 5 slashing damage.",
                    f.entriesAbout(goblin).get(1).getDescription());
        }

        @Test
        @DisplayName("A critical hit doubles the weapon's dice but not the modifier")
        void criticalHit() {
            EncounterParticipant goblin = f.monster("Goblin", 30, 25);

            // 2d6 twice over: 1 + 2 + 3 + 4, plus 3
            attack(weaponRequest(goblin, "Greatsword"), 20, 1, 2, 3, 4);

            assertEquals(17, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("A ranged weapon attacks with DEX and from beyond 5 feet unless the attacker says otherwise")
        void rangedWeapon() {
            EncounterParticipant fighter = f.player("Fighter", 30, 12);
            CombatFixture.downed(fighter, LifeState.DYING, 0);

            // Unconscious and prone from beyond 5 feet: advantage and disadvantage cancel, and a hit isn't critical;
            // the rogue isn't proficient with a longbow, so DEX alone
            attack(weaponRequest(fighter, "Longbow"), 15, 3);
            AttackRollRequest close = weaponRequest(fighter, "Longbow");
            close.setWithin5Ft(true);
            attack(close, 2, 15, 3, 3);

            List<String> attacks = f.entriesAbout(fighter).stream().map(CombatLog::getDescription)
                    .filter(description -> description.startsWith("Rogue uses Longbow")).toList();
            assertEquals("Rogue uses Longbow on Fighter: d20(15) + 4 = 19 vs AC 12 — hit, 7 piercing damage.",
                    attacks.getFirst());
            assertTrue(attacks.get(1).contains("with advantage") && attacks.get(1).contains("critical hit"));
        }

        @Test
        @DisplayName("An unarmed strike is always there: proficient, 1 + the STR modifier bludgeoning")
        void unarmedStrike() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 10);

            attack(weaponRequest(goblin, "Unarmed Strike"), 10);

            assertEquals(16, goblin.getHpCurrent());
            assertEquals("Rogue uses Unarmed Strike on Goblin: d20(10) + 5 = 15 vs AC 10 — hit, 4 bludgeoning damage.",
                    entryAbout(goblin));
        }

        @Test
        @DisplayName("Great Weapon Master takes 5 off the attack roll for 10 more damage with a heavy weapon")
        void greatWeaponMaster() {
            rogue.getCharacter().setWeaponProficiencies(List.of("simple", "martial"));
            rogue.getCharacter().setFeatures(List.of(new Feature("Great Weapon Master", null, "Feat")));
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            AttackRollRequest request = weaponRequest(ogre, "Greatsword");
            request.setPowerAttack(true);

            // 14 + 5 - 5 = 14 against AC 11; 2d6 + 3 + 10
            attack(request, 14, 3, 4);

            assertEquals(59 - 20, ogre.getHpCurrent());
            assertEquals("Rogue uses Greatsword (Great Weapon Master) on Ogre: d20(14) + 0 = 14 vs AC 11 — hit, "
                    + "20 slashing damage.", entryAbout(ogre));
        }

        @Test
        @DisplayName("Sharpshooter takes 5 off the attack roll for 10 more damage with a ranged weapon")
        void sharpshooter() {
            rogue.getCharacter().setWeaponProficiencies(List.of("simple", "martial"));
            rogue.getCharacter().setLevelHistory(List.of(CombatFixture.featTaken("Sharpshooter")));
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            AttackRollRequest request = weaponRequest(ogre, "Longbow");
            request.setPowerAttack(true);

            // 9 + 6 - 5 = 10 misses AC 11
            attack(request, 9);
            request.setAdvantage(true);
            attack(request, 9, 14, 8);

            assertEquals(59 - 22, ogre.getHpCurrent());
        }

        @Test
        @DisplayName("The trade is a 400 without the feat, without proficiency, or with a weapon that allows neither")
        void tradeRefusals() {
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            AttackRollRequest greatsword = weaponRequest(ogre, "Greatsword");
            greatsword.setPowerAttack(true);
            AttackRollRequest longsword = weaponRequest(ogre, "Longsword");
            longsword.setPowerAttack(true);
            AttackRollRequest thrownDagger = weaponRequest(ogre, "Dagger");
            thrownDagger.setPowerAttack(true);
            thrownDagger.setWithin5Ft(false);

            ValidationException noFeat = assertThrows(ValidationException.class, () -> attack(greatsword));
            rogue.getCharacter().setFeatures(List.of(new Feature("Great Weapon Master", null, "Variant Human")));
            ValidationException notProficient = assertThrows(ValidationException.class, () -> attack(greatsword));
            ValidationException notHeavy = assertThrows(ValidationException.class, () -> attack(longsword));
            ValidationException thrown = assertThrows(ValidationException.class, () -> attack(thrownDagger));

            assertEquals("Rogue doesn't have the Great Weapon Master feat", noFeat.getMessage());
            assertEquals("Great Weapon Master needs a weapon Rogue is proficient with, and Rogue isn't proficient with "
                    + "the Greatsword", notProficient.getMessage());
            assertEquals("Great Weapon Master needs a heavy melee weapon and Sharpshooter a ranged weapon, and the "
                    + "Longsword is neither", notHeavy.getMessage());
            assertEquals("Great Weapon Master needs a heavy melee weapon and Sharpshooter a ranged weapon, and the "
                    + "Dagger is neither", thrown.getMessage());
            assertEquals(59, ogre.getHpCurrent());
            assertTrue(f.log.isEmpty());
        }

        @Test
        @DisplayName("A magic weapon's bonus is a Manual Modifier: added to the numbers and logged with its values")
        void magicBonus() {
            EncounterParticipant goblin = f.monster("Goblin", 30, 18);
            AttackRollRequest request = weaponRequest(goblin, "Longsword");
            request.setExtraAttackBonus(1);
            request.setExtraDamageDice("+1");
            AttackRollRequest flameTongue = weaponRequest(goblin, "Longsword");
            flameTongue.setExtraDamageDice("2d6");
            flameTongue.setExtraDamageType("Fire");

            // 12 + 5 + 1 = 18 against AC 18; 4 + 3 + 1
            attack(request, 12, 4);
            // The second attack on a later turn, so it has an action to take (D174)
            CombatFixture.newTurn(rogue);
            // 15 + 5 hits; 4 + 3 slashing and 2 + 2 fire
            attack(flameTongue, 15, 4, 2, 2);

            assertEquals(30 - 8 - 11, goblin.getHpCurrent());
            assertEquals(List.of("Manual Modifier (DM): Rogue's Longsword attack adds +1 to the attack roll.",
                    "Manual Modifier (DM): Rogue's Longsword attack adds 1 slashing damage.",
                    "Rogue uses Longsword on Goblin: d20(12) + 6 = 18 vs AC 18 — hit, 8 slashing damage.",
                    "Manual Modifier (DM): Rogue's Longsword attack adds 2d6 fire damage.",
                    "Rogue uses Longsword on Goblin: d20(15) + 5 = 20 vs AC 18 — hit, 11 damage (7 slashing, 4 fire)."),
                    f.descriptions());
        }

        @Test
        @DisplayName("A player is refused a weapon's Manual Modifiers with a 403 when the campaign disallows them, but can "
                + "still make the attack the server works out")
        void playerManualModifierGate() {
            UUID playerUserId = f.playerControlling(rogue);
            f.campaign.setAllowPlayerManualModifiers(false);
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            AttackRollRequest magic = weaponRequest(goblin, "Longsword");
            magic.setExtraAttackBonus(1);
            AttackRollRequest crit = weaponRequest(goblin, "Longsword");
            crit.setForceCrit(true);

            assertThrows(ForbiddenException.class,
                    () -> f.attacks.rollAttack(f.encounterId(), magic, rogue.getId(), playerUserId));
            assertThrows(ForbiddenException.class,
                    () -> f.attacks.rollAttack(f.encounterId(), crit, rogue.getId(), playerUserId));
            assertTrue(f.log.isEmpty());
            f.dice.script(12, 5);
            f.attacks.rollAttack(f.encounterId(), weaponRequest(goblin, "Longsword"), rogue.getId(), playerUserId);

            assertEquals(12, goblin.getHpCurrent());
            assertEquals(1, f.log.size());
        }

        @Test
        @DisplayName("An unknown weapon, numbers entered with a weapon, a type without dice and a monster's weapon are 400s")
        void weaponRefusals() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            AttackRollRequest withNumbers = weaponRequest(goblin, "Longsword");
            withNumbers.setAttackBonus(9);
            AttackRollRequest typeOnly = weaponRequest(goblin, "Longsword");
            typeOnly.setExtraDamageType("fire");
            AttackRollRequest badDice = weaponRequest(goblin, "Longsword");
            badDice.setExtraDamageDice("lots");

            ValidationException unknown = assertThrows(ValidationException.class,
                    () -> attack(weaponRequest(goblin, "Vorpal Sword")));
            ValidationException numbers = assertThrows(ValidationException.class, () -> attack(withNumbers));
            ValidationException type = assertThrows(ValidationException.class, () -> attack(typeOnly));
            assertThrows(ValidationException.class, () -> attack(badDice));
            ValidationException monster = assertThrows(ValidationException.class, () -> f.attacks.rollAttack(
                    f.encounterId(), weaponRequest(rogue, "Longsword"), goblin.getId(), f.dmUserId));

            assertEquals("Rogue has no Vorpal Sword to attack with", unknown.getMessage());
            assertEquals("A weapon attack takes its numbers from the weapon: add a magic bonus as an extra attack bonus "
                    + "or extra damage", numbers.getMessage());
            assertEquals("Enter the extra damage's dice along with its type", type.getMessage());
            assertEquals("Only a player character attacks with a weapon from their inventory", monster.getMessage());
            assertTrue(f.log.isEmpty());
        }

        @Test
        @DisplayName("The attack options list each weapon with its numbers, and the trade where the character may take it")
        void attackOptions() {
            rogue.getCharacter().setFeatures(List.of(new Feature("Sharpshooter", null, "Feat")));
            rogue.getCharacter().setWeaponProficiencies(List.of("simple", "martial"));
            UUID playerUserId = f.playerControlling(rogue);
            EncounterParticipant fighter = f.player("Fighter", 30, 16);
            UUID otherPlayer = f.playerControlling(fighter);

            List<AttackOption> options = f.attacks.attackOptions(f.encounterId(), rogue.getId(), playerUserId);

            assertEquals(List.of(
                    new AttackOption("Longsword", Ability.STR, true, 5, "1d8 + 3", "slashing", false, null),
                    new AttackOption("Rapier", Ability.DEX, true, 6, "1d8 + 4", "piercing", false, null),
                    new AttackOption("Greatsword", Ability.STR, true, 5, "2d6 + 3", "slashing", false, null, false,
                            true, null, List.of(), 20, null, false),
                    new AttackOption("Dagger", Ability.DEX, true, 6, "1d4 + 4", "piercing", false, null),
                    new AttackOption("Longbow", Ability.DEX, true, 6, "1d8 + 4", "piercing", true,
                            new AttackOption.PowerAttackOption("Sharpshooter", 1, "1d8 + 14"), false, true, null,
                            List.of(), 20, null, false),
                    new AttackOption("Unarmed Strike", Ability.STR, true, 5, "4", "bludgeoning", false, null)), options);
            assertThrows(ForbiddenException.class,
                    () -> f.attacks.attackOptions(f.encounterId(), rogue.getId(), otherPlayer));
            EncounterParticipant goblin = f.monster("Goblin", 7, 15);
            assertTrue(f.attacks.attackOptions(f.encounterId(), goblin.getId(), f.dmUserId).isEmpty());
        }
    }

    @Test
    @DisplayName("The dead can't be attacked: a 409 (D161)")
    void deadTargetIsRefused() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        CombatFixture.dead(goblin);

        ConflictException ex = assertThrows(ConflictException.class, () -> attack(request(goblin, 5, "1d6"), 15));

        assertEquals("Goblin is dead", ex.getMessage());
    }

    @Test
    @DisplayName("A player who names a hidden monster gets the 400 of an unknown one (D157)")
    void playerCannotTargetAHiddenMonster() {
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant bugbear = f.monster("Bugbear", 27, 16);
        bugbear.setIsVisibleToPlayers(false);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> f.attacks.rollAttack(f.encounterId(), weaponRequest(bugbear, "Unarmed Strike"), rogue.getId(),
                        playerUserId));

        assertEquals("Participant not found", ex.getMessage());
    }

    @Test
    @DisplayName("A player attacking when it is not their turn gets 403 (D028)")
    void playerAttackOutOfTurnIsForbidden() {
        EncounterParticipant fighter = f.player("Fighter", 30, 16);
        f.turnOf(fighter);
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> f.attacks.rollAttack(f.encounterId(), weaponRequest(goblin, "Unarmed Strike"), rogue.getId(),
                        playerUserId));

        assertEquals("You can only attack on your turn", ex.getMessage());
        assertEquals(7, goblin.getHpCurrent());
        verify(f.combatLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("After the encounter ends, a player's attack off their turn is refused because it ended (D166)")
    void playerAttackAfterEncounterEnded() {
        EncounterParticipant fighter = f.player("Fighter", 30, 16);
        f.turnOf(fighter);
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        f.encounter.setStatus(EncounterStatus.COMPLETED);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> f.attacks.rollAttack(f.encounterId(), weaponRequest(goblin, "Unarmed Strike"), rogue.getId(),
                        playerUserId));

        assertEquals("The encounter has ended", ex.getMessage());
    }

    @Test
    @DisplayName("A player may attack on their own turn (D028)")
    void playerAttackOnTurnIsAllowed() {
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        f.dice.script(15, 3);

        assertDoesNotThrow(() -> f.attacks.rollAttack(f.encounterId(), request(goblin, 5, "1d6"), rogue.getId(),
                playerUserId));
        assertEquals(4, goblin.getHpCurrent());
    }

    @Nested
    @DisplayName("The action economy (D174)")
    class Economy {

        private AttackRollRequest costing(AttackRollRequest request, AttackCost cost) {
            request.setCost(cost);
            return request;
        }

        @Test
        @DisplayName("A player's second attack without Extra Attack is a 409 before anything rolls or is logged")
        void playerOverBudgetRefused() {
            UUID playerUserId = f.playerControlling(rogue);
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            f.dice.script(15, 3);
            f.attacks.rollAttack(f.encounterId(), request(goblin, 5, "1d6"), rogue.getId(), playerUserId);
            int entries = f.log.size();

            // No dice are scripted, so any roll would fail the test
            ConflictException ex = assertThrows(ConflictException.class, () -> f.attacks.rollAttack(f.encounterId(),
                    request(goblin, 5, "1d6"), rogue.getId(), playerUserId));

            assertEquals("It has no attacks left in its Attack action and no action left this turn", ex.getMessage());
            assertEquals(17, goblin.getHpCurrent());
            assertEquals(entries, f.log.size());
            assertEquals(1, rogue.getTurnEconomy().actionsUsed());
        }

        @Test
        @DisplayName("With Extra Attack the first attack opens the Attack action and the second uses it")
        void extraAttack() {
            rogue.getCharacter().setFeatures(List.of(new Feature("Extra Attack", null, null)));
            UUID playerUserId = f.playerControlling(rogue);
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);

            f.dice.script(15, 3);
            f.attacks.rollAttack(f.encounterId(), request(goblin, 5, "1d6"), rogue.getId(), playerUserId);
            assertEquals(1, rogue.getTurnEconomy().attacksLeft());
            f.dice.script(15, 3);
            f.attacks.rollAttack(f.encounterId(), request(goblin, 5, "1d6"), rogue.getId(), playerUserId);

            assertEquals(0, rogue.getTurnEconomy().attacksLeft());
            assertEquals(1, rogue.getTurnEconomy().actionsUsed());
            assertThrows(ConflictException.class, () -> f.attacks.rollAttack(f.encounterId(),
                    request(goblin, 5, "1d6"), rogue.getId(), playerUserId));
        }

        @Test
        @DisplayName("The DM's attack over budget still rolls, and the log says the player character went over")
        void dmOverBudgetLoggedForAPlayerCharacter() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            attack(request(goblin, 5, "1d6"), 15, 3);

            attack(request(goblin, 5, "1d6"), 15, 4);

            assertEquals(20 - 3 - 4, goblin.getHpCurrent());
            assertTrue(f.descriptions().contains("Rogue's attack is over the action economy: it has no attacks left "
                    + "in its Attack action and no action left this turn"));
            assertEquals(2, rogue.getTurnEconomy().actionsUsed());
        }

        @Test
        @DisplayName("The DM's attack over budget gives no attacks back: the player's next attack is still a 409")
        void dmOverBudgetGrantsNoAttacks() {
            rogue.getCharacter().setFeatures(List.of(new Feature("Extra Attack", null, null)));
            UUID playerUserId = f.playerControlling(rogue);
            EncounterParticipant goblin = f.monster("Goblin", 40, 15);
            for (int i = 0; i < 2; i++) {
                f.dice.script(15, 3);
                f.attacks.rollAttack(f.encounterId(), request(goblin, 5, "1d6"), rogue.getId(), playerUserId);
            }

            attack(request(goblin, 5, "1d6"), 15, 3);

            assertEquals(0, rogue.getTurnEconomy().attacksLeft());
            assertEquals(2, rogue.getTurnEconomy().actionsUsed());
            assertTrue(f.descriptions().contains("Rogue's attack is over the action economy: it has no attacks left "
                    + "in its Attack action and no action left this turn"));
            ConflictException ex = assertThrows(ConflictException.class, () -> f.attacks.rollAttack(f.encounterId(),
                    request(goblin, 5, "1d6"), rogue.getId(), playerUserId));
            assertEquals("It has no attacks left in its Attack action and no action left this turn", ex.getMessage());
            assertEquals(40 - 9, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("The DM's attack after the action went elsewhere opens no attacks for the player either")
        void dmOverBudgetAfterAnotherActionGrantsNoAttacks() {
            rogue.getCharacter().setFeatures(List.of(new Feature("Extra Attack", null, null)));
            UUID playerUserId = f.playerControlling(rogue);
            EncounterParticipant goblin = f.monster("Goblin", 40, 15);
            f.standardActions.standardAction(f.encounterId(), new StandardActionRequest(rogue.getId(),
                    StandardActions.Action.DASH, null, null, null), playerUserId);

            attack(request(goblin, 5, "1d6"), 15, 3);

            assertEquals(0, rogue.getTurnEconomy().attacksLeft());
            assertEquals(2, rogue.getTurnEconomy().actionsUsed());
            assertThrows(ConflictException.class, () -> f.attacks.rollAttack(f.encounterId(),
                    request(goblin, 5, "1d6"), rogue.getId(), playerUserId));
        }

        @Test
        @DisplayName("The DM's monster over budget is tracked but not logged as over")
        void dmOverBudgetNotLoggedForAMonster() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            f.turnOf(goblin);
            for (int i = 0; i < 2; i++) {
                f.dice.script(15, 3);
                f.attacks.rollAttack(f.encounterId(), request(rogue, 5, "1d6"), goblin.getId(), f.dmUserId);
            }

            assertEquals(30 - 6, rogue.getHpCurrent());
            assertEquals(2, goblin.getTurnEconomy().actionsUsed());
            assertTrue(f.descriptions().stream().noneMatch(entry -> entry.contains("over the action economy")));
        }

        @Test
        @DisplayName("An off-hand attack needs the Attack action and a light melee weapon, and adds no positive ability "
                + "modifier to its damage")
        void offHandAttack() {
            rogue.getCharacter().setDexterity(16);
            CombatFixture.carries(rogue, List.of("simple", "martial"), "Dagger", "Longsword");
            UUID playerUserId = f.playerControlling(rogue);
            EncounterParticipant goblin = f.monster("Goblin", 30, 10);

            ConflictException first = assertThrows(ConflictException.class, () -> f.attacks.rollAttack(
                    f.encounterId(), costing(weaponRequest(goblin, "Dagger"), AttackCost.BONUS_ACTION), rogue.getId(),
                    playerUserId));
            assertEquals("An off-hand attack needs the Attack action first this turn", first.getMessage());

            f.dice.script(15, 4);
            f.attacks.rollAttack(f.encounterId(), weaponRequest(goblin, "Dagger"), rogue.getId(), playerUserId);
            ValidationException heavy = assertThrows(ValidationException.class, () -> f.attacks.rollAttack(
                    f.encounterId(), costing(weaponRequest(goblin, "Longsword"), AttackCost.BONUS_ACTION),
                    rogue.getId(), playerUserId));
            assertEquals("An off-hand attack needs a light melee weapon, and the Longsword isn't one",
                    heavy.getMessage());

            // 4 + 3 for the Attack action's dagger, then 4 alone for the off-hand dagger
            f.dice.script(15, 4);
            f.attacks.rollAttack(f.encounterId(), costing(weaponRequest(goblin, "Dagger"), AttackCost.BONUS_ACTION),
                    rogue.getId(), playerUserId);

            assertEquals(30 - 7 - 4, goblin.getHpCurrent());
            assertTrue(f.entriesAbout(goblin).getLast().getDescription()
                    .startsWith("Rogue uses Dagger (bonus action) on Goblin: d20(15) + 5 = 20"));
            assertTrue(rogue.getTurnEconomy().bonusActionUsed());
        }

        @Test
        @DisplayName("A player's opportunity attack is a reaction on another creature's turn, once until their own turn")
        void opportunityAttack() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 15);
            f.turnOf(goblin);
            UUID playerUserId = f.playerControlling(rogue);

            f.dice.script(15, 3);
            f.attacks.rollAttack(f.encounterId(), costing(request(goblin, 5, "1d6"), AttackCost.REACTION),
                    rogue.getId(), playerUserId);
            ConflictException ex = assertThrows(ConflictException.class, () -> f.attacks.rollAttack(f.encounterId(),
                    costing(request(goblin, 5, "1d6"), AttackCost.REACTION), rogue.getId(), playerUserId));

            assertEquals(17, goblin.getHpCurrent());
            assertEquals("Rogue attacks (reaction) Goblin: d20(15) + 5 = 20 vs AC 15 — hit, 3 damage.",
                    entryAbout(goblin));
            assertEquals("It has already taken its reaction; it comes back at the start of its turn", ex.getMessage());
            assertEquals(0, rogue.getTurnEconomy().actionsUsed());
        }
    }

    @Nested
    @DisplayName("Armour worn without proficiency (M13.5, PHB p.144)")
    class ArmourPenalty {

        private final Item chainMail = CombatFixture.armour("Chain Mail", Item.ArmourCategory.HEAVY, 16, true);

        @Test
        @DisplayName("A weapon attack, which uses STR or DEX, has disadvantage")
        void weaponAttackHasDisadvantage() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 10);
            CombatFixture.carries(rogue, List.of("martial"), "Longsword");
            f.wears(rogue, chainMail);

            attack(weaponRequest(goblin, "Longsword"), 15, 3);

            String entry = entryAbout(goblin);
            assertTrue(entry.contains("2d20(15, 3) with disadvantage"), entry);
            assertTrue(entry.contains("miss"), entry);
        }

        @Test
        @DisplayName("Advantage the attacker has cancels it, so one d20 is rolled")
        void advantageCancels() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 10);
            CombatFixture.carries(rogue, List.of("martial"), "Longsword");
            f.wears(rogue, chainMail);
            AttackRollRequest request = weaponRequest(goblin, "Longsword");
            request.setAdvantage(true);

            attack(request, 15, 5);

            assertTrue(entryAbout(goblin).startsWith("Rogue uses Longsword on Goblin: d20(15)"), entryAbout(goblin));
        }

        @Test
        @DisplayName("Every source cancels at once (PHB p.173): the attacker's advantage, the armour's disadvantage and a Dodging target's disadvantage roll one d20")
        void allSourcesCancelTogether() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 10);
            goblin.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual(StandardActions.DODGING, null, 1))));
            CombatFixture.carries(rogue, List.of("martial"), "Longsword");
            f.wears(rogue, chainMail);
            AttackRollRequest request = weaponRequest(goblin, "Longsword");
            request.setAdvantage(true);

            attack(request, 15, 5);

            assertTrue(entryAbout(goblin).startsWith("Rogue uses Longsword on Goblin: d20(15)"), entryAbout(goblin));
        }

        @Test
        @DisplayName("Proficiency with the armour removes it")
        void proficientArmour() {
            EncounterParticipant goblin = f.monster("Goblin", 20, 10);
            CombatFixture.carries(rogue, List.of("martial"), "Longsword");
            f.wears(rogue, chainMail);
            rogue.getCharacter().setArmorProficiencies(List.of("Heavy Armor"));

            attack(weaponRequest(goblin, "Longsword"), 15, 5);

            assertTrue(entryAbout(goblin).startsWith("Rogue uses Longsword on Goblin: d20(15)"), entryAbout(goblin));
        }
    }
}
