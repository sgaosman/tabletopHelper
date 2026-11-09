package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackCost;
import com.tabletophelper.encounter.dto.AttackOption;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.DivineSmiteRequest;
import com.tabletophelper.encounter.dto.ProtectionRequest;
import com.tabletophelper.encounter.dto.StunningStrikeRequest;
import com.tabletophelper.encounter.state.ConcentrationCheck;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.WeaponHit;
import com.tabletophelper.reference.Item;
import com.tabletophelper.rules.combat.AttackFeatures;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tabletophelper.encounter.internal.CombatFixture.versatileWeapon;
import static com.tabletophelper.encounter.internal.CombatFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weapon attack riders (M17 workstream B, 2014 PHB): Fighting Styles, Improved Critical, Sneak Attack, Martial Arts and
 * Flurry of Blows on the attack, and Divine Smite, Stunning Strike and Protection after it. The DM acts unless a test
 * says otherwise, against a goblin with AC 10 and 60 HP whose abilities are all 10.
 */
class AttackRidersTest {

    private CombatFixture f;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.weapons(versatileWeapon("Longsword", "martial", "1d8", "1d10", "slashing"),
                versatileWeapon("Quarterstaff", "simple", "1d6", "1d8", "bludgeoning"),
                weapon("Greatsword", "Melee Weapon", "martial", "2d6", "slashing", "H", "2H"),
                weapon("Handaxe", "Melee Weapon", "simple", "1d6", "slashing", "L", "T"),
                weapon("Rapier", "Melee Weapon", "martial", "1d8", "piercing", "F"),
                weapon("Longbow", "Ranged Weapon", "martial", "1d8", "piercing", "A", "H", "2H"));
    }

    // A player character of the class and level, STR 16 and DEX 16, proficient with every weapon, carrying them all
    private EncounterParticipant pc(String name, String className, String subclass, int level, String... features) {
        EncounterParticipant pc = f.player(name, 40, 15);
        pc.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), className, null, subclass,
                level)));
        pc.getCharacter().setLevel(level);
        pc.getCharacter().setStrength(16);
        pc.getCharacter().setDexterity(16);
        pc.getCharacter().setFeatures(new ArrayList<>(List.of(features).stream()
                .map(name1 -> new Feature(name1, null, className)).toList()));
        CombatFixture.carries(pc, List.of("simple", "martial"), "Longsword", "Quarterstaff", "Greatsword", "Handaxe",
                "Rapier", "Longbow");
        return pc;
    }

    private static AttackRollRequest request(EncounterParticipant target, String weapon) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setWeapon(weapon);
        return request;
    }

    private void attack(EncounterParticipant attacker, AttackRollRequest request, int... rolls) {
        f.dice.script(rolls);
        f.attacks.rollAttack(f.encounterId(), request, attacker.getId(), f.dmUserId);
    }

    private String lastAbout(EncounterParticipant target) {
        List<com.tabletophelper.encounter.CombatLog> entries = f.entriesAbout(target);
        return entries.getLast().getDescription();
    }

    @Nested
    @DisplayName("Fighting Styles (PHB p.72)")
    class Styles {

        @Test
        @DisplayName("Archery adds 2 to a longbow's attack roll")
        void archery() {
            EncounterParticipant vex = pc("Vex", "Fighter", null, 1, "Fighting Style: Archery");
            goblin = f.monster("Goblin", 60, 10);

            attack(vex, request(goblin, "Longbow"), 5, 4);

            assertEquals("Vex uses Longbow (Archery +2) on Goblin: d20(5) + 7 = 12 vs AC 10 — hit, 7 piercing damage.",
                    lastAbout(goblin));
        }

        @Test
        @DisplayName("Dueling adds 2 to a longsword in one hand; two-handed it deals its d10 instead, and with a weapon in "
                + "each hand nothing is added")
        void dueling() {
            EncounterParticipant vex = pc("Vex", "Fighter", null, 1, "Fighting Style: Dueling");
            goblin = f.monster("Goblin", 60, 10);

            attack(vex, request(goblin, "Longsword"), 10, 5);
            AttackRollRequest twoHanded = request(goblin, "Longsword");
            twoHanded.setGrip(AttackFeatures.Grip.TWO_HANDED);
            attack(vex, twoHanded, 10, 9);
            AttackRollRequest twoWeapons = request(goblin, "Longsword");
            twoWeapons.setGrip(AttackFeatures.Grip.TWO_WEAPONS);
            attack(vex, twoWeapons, 10, 5);

            assertEquals(List.of(
                    "Vex uses Longsword (Dueling +2) on Goblin: d20(10) + 5 = 15 vs AC 10 — hit, 10 slashing damage.",
                    "Vex uses Longsword (two-handed) on Goblin: d20(10) + 5 = 15 vs AC 10 — hit, 12 slashing damage.",
                    "Vex uses Longsword on Goblin: d20(10) + 5 = 15 vs AC 10 — hit, 8 slashing damage."),
                    f.entriesAbout(goblin).stream().map(e -> e.getDescription()).toList());
        }

        @Test
        @DisplayName("Great Weapon Fighting rolls a greatsword's 1 again; holding it in one hand is a 400")
        void greatWeaponFighting() {
            EncounterParticipant vex = pc("Vex", "Fighter", null, 1, "Fighting Style: Great Weapon Fighting");
            goblin = f.monster("Goblin", 60, 10);

            // 1 rerolled as 5, then 4: 5 + 4 + 3
            attack(vex, request(goblin, "Greatsword"), 10, 1, 5, 4);
            AttackRollRequest oneHand = request(goblin, "Greatsword");
            oneHand.setGrip(AttackFeatures.Grip.ONE_HANDED);
            ValidationException refused = assertThrows(ValidationException.class, () -> attack(vex, oneHand));

            assertEquals("Vex uses Greatsword (Great Weapon Fighting) on Goblin: d20(10) + 5 = 15 vs AC 10 — hit, 12 "
                    + "slashing damage.", lastAbout(goblin));
            assertEquals("The Greatsword is two-handed and needs both hands to attack with", refused.getMessage());
        }

        @Test
        @DisplayName("Two-Weapon Fighting adds STR to the off-hand handaxe's damage")
        void twoWeaponFighting() {
            EncounterParticipant vex = pc("Vex", "Fighter", null, 1, "Fighting Style: Two-Weapon Fighting");
            goblin = f.monster("Goblin", 60, 10);

            attack(vex, request(goblin, "Handaxe"), 10, 2);
            AttackRollRequest offHand = request(goblin, "Handaxe");
            offHand.setCost(AttackCost.BONUS_ACTION);
            attack(vex, offHand, 10, 4);

            assertEquals("Vex uses Handaxe (Two-Weapon Fighting) (bonus action) on Goblin: d20(10) + 5 = 15 vs AC 10 — "
                    + "hit, 7 slashing damage.", lastAbout(goblin));
        }

        @Test
        @DisplayName("Protection, with a shield, takes the reaction and is logged for the DM; without a shield it is a "
                + "409, and without the style a 400")
        void protection() {
            EncounterParticipant vex = pc("Vex", "Fighter", null, 1, "Fighting Style: Protection");
            EncounterParticipant ally = pc("Ally", "Wizard", null, 1);
            EncounterParticipant bare = pc("Bare", "Fighter", null, 1);
            ProtectionRequest request = new ProtectionRequest(vex.getId(), ally.getId());

            ConflictException noShield = assertThrows(ConflictException.class,
                    () -> f.hitFollowUps.protection(f.encounterId(), request, f.dmUserId));
            f.wears(vex, CombatFixture.armour("Shield", Item.ArmourCategory.SHIELD, 2, false));
            f.hitFollowUps.protection(f.encounterId(), request, f.dmUserId);
            ValidationException noStyle = assertThrows(ValidationException.class, () -> f.hitFollowUps.protection(
                    f.encounterId(), new ProtectionRequest(bare.getId(), ally.getId()), f.dmUserId));

            assertEquals("Protection needs a shield, and Vex isn't wielding one", noShield.getMessage());
            assertEquals("Bare doesn't have the Protection fighting style", noStyle.getMessage());
            assertTrue(vex.getTurnEconomy().reactionUsed());
            assertEquals("Vex uses Protection (reaction): the attack against Ally has disadvantage; the DM rolls it so.",
                    f.descriptions().getLast());
        }
    }

    @Nested
    @DisplayName("Improved Critical (PHB p.72)")
    class Champion {

        @Test
        @DisplayName("A Champion 3's 19 is a critical hit against AC 30, doubling the dice; the attack options say so")
        void improvedCritical() {
            EncounterParticipant vex = pc("Vex", "Fighter", "Champion", 3);
            EncounterParticipant ogre = f.monster("Ogre", 60, 30);

            attack(vex, request(ogre, "Longsword"), 19, 3, 4);
            List<AttackOption> options = f.attacks.attackOptions(f.encounterId(), vex.getId(), f.dmUserId);

            assertEquals("Vex uses Longsword (critical on 19-20) on Ogre: d20(19) + 5 = 24 vs AC 30 — critical hit, 10 "
                    + "slashing damage.", lastAbout(ogre));
            assertEquals(19, options.getFirst().critRange());
            assertEquals("1d10 + 3", options.getFirst().versatileDamage());
            assertTrue(vex.getTurnEconomy().lastMeleeHit().critical());
        }
    }

    @Nested
    @DisplayName("Sneak Attack (PHB p.96)")
    class SneakAttack {

        private EncounterParticipant sly;

        @BeforeEach
        void rogue() {
            sly = pc("Sly", "Rogue", null, 3);
            goblin = f.monster("Goblin", 60, 10);
        }

        @Test
        @DisplayName("A rapier attack with advantage adds 2d6 on a hit, once per turn: the turn's second has none")
        void withAdvantage() {
            AttackRollRequest advantage = request(goblin, "Rapier");
            advantage.setAdvantage(true);

            attack(sly, advantage, 4, 15, 3, 2, 5);
            attack(sly, advantage, 4, 15, 3);

            assertEquals(List.of(
                    "Sly uses Rapier (Sneak Attack +2d6) on Goblin: 2d20(4, 15) with advantage → 15 + 5 = 20 vs AC 10 — "
                            + "hit, 13 piercing damage.",
                    "Sly uses Rapier on Goblin: 2d20(4, 15) with advantage → 15 + 5 = 20 vs AC 10 — hit, 6 piercing "
                            + "damage."),
                    f.entriesAbout(goblin).stream().map(e -> e.getDescription()).toList());
            assertEquals(EncounterTurns.stamp(f.encounter), sly.getTurnEconomy().sneakAttackTurn());
        }

        @Test
        @DisplayName("A restrained target's advantage brings Sneak Attack without the player choosing advantage; a "
                + "poisoned rogue's disadvantage cancels it and there is none (PHB p.96, p.292)")
        void advantageFromConditions() {
            goblin.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("restrained", null, 1))));

            attack(sly, request(goblin, "Rapier"), 4, 15, 3, 2, 5);
            CombatFixture.newTurn(sly);
            sly.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("poisoned", null, 1))));
            attack(sly, request(goblin, "Rapier"), 15, 3);

            assertEquals(List.of(
                    "Sly uses Rapier (Sneak Attack +2d6) on Goblin (advantage: target restrained): 2d20(4, 15) with "
                            + "advantage → 15 + 5 = 20 vs AC 10 — hit, 13 piercing damage.",
                    "Sly uses Rapier on Goblin (advantage: target restrained; disadvantage: attacker poisoned): d20(15) "
                            + "+ 5 = 20 vs AC 10 — hit, 6 piercing damage."),
                    f.entriesAbout(goblin).stream().map(e -> e.getDescription()).toList());
        }

        @Test
        @DisplayName("Sneak Attack's dice are part of the hit's damage, so a concentrating target makes one check "
                + "against the whole: 23 damage is DC 11 (PHB p.96, p.203)")
        void oneConcentrationCheck() {
            goblin.setConcentration(ConcentrationInstance.start("Hold Person", 2, 1, 10));
            AttackRollRequest advantage = request(goblin, "Rapier");
            advantage.setAdvantage(true);

            // 8 + 3 for the rapier and 6 + 6 for Sneak Attack, then the check's d20 10
            attack(sly, advantage, 4, 15, 8, 6, 6, 10);

            List<CombatLog> checks = f.entries(CombatActionType.CONCENTRATION_CHECK);
            assertEquals(1, checks.size());
            assertTrue(checks.getFirst().getDescription().endsWith("= 10 vs DC 11, and fails"));
            assertNull(goblin.getConcentration());
        }

        @Test
        @DisplayName("Without advantage it needs the player's claim of an ally next to the target; a claimed attack "
                + "with disadvantage has none, a longsword can't claim it, and a claim once dealt is a 409")
        void claimed() {
            AttackRollRequest plain = request(goblin, "Rapier");
            attack(sly, plain, 15, 3);
            AttackRollRequest disadvantaged = request(goblin, "Rapier");
            disadvantaged.setSneakAttack(true);
            disadvantaged.setAdvantage(false);
            attack(sly, disadvantaged, 15, 18, 3);
            AttackRollRequest claim = request(goblin, "Rapier");
            claim.setSneakAttack(true);
            attack(sly, claim, 15, 3, 2, 5);
            ConflictException again = assertThrows(ConflictException.class, () -> attack(sly, claim));
            AttackRollRequest longsword = request(goblin, "Longsword");
            longsword.setSneakAttack(true);
            ValidationException notFinesse = assertThrows(ValidationException.class, () -> attack(sly, longsword));

            assertEquals(List.of(
                    "Sly uses Rapier on Goblin: d20(15) + 5 = 20 vs AC 10 — hit, 6 piercing damage.",
                    "Sly uses Rapier (no Sneak Attack with disadvantage) on Goblin: 2d20(15, 18) with disadvantage → 15 + "
                            + "5 = 20 vs AC 10 — hit, 6 piercing damage.",
                    "Sly uses Rapier (Sneak Attack +2d6) on Goblin: d20(15) + 5 = 20 vs AC 10 — hit, 13 piercing "
                            + "damage."),
                    f.entriesAbout(goblin).stream().map(e -> e.getDescription()).toList());
            assertEquals("Sly has already dealt Sneak Attack damage this turn; it can be dealt once per turn",
                    again.getMessage());
            assertEquals("Sneak Attack needs a finesse or ranged weapon, and the Longsword is neither",
                    notFinesse.getMessage());
        }

        @Test
        @DisplayName("A miss doesn't spend it, and an opportunity attack on the goblin's turn may have it again")
        void onceEachTurn() {
            AttackRollRequest claim = request(goblin, "Rapier");
            claim.setSneakAttack(true);
            attack(sly, claim, 2);
            assertNull(sly.getTurnEconomy().sneakAttackTurn());
            attack(sly, claim, 15, 3, 2, 5);

            f.turnOf(goblin);
            AttackRollRequest opportunity = request(goblin, "Rapier");
            opportunity.setSneakAttack(true);
            opportunity.setCost(AttackCost.REACTION);
            attack(sly, opportunity, 15, 3, 1, 1);

            assertEquals("Sly uses Rapier (Sneak Attack +2d6) (reaction) on Goblin: d20(15) + 5 = 20 vs AC 10 — hit, 8 "
                    + "piercing damage.", lastAbout(goblin));
            assertEquals(60 - 13 - 8, goblin.getHpCurrent());
        }
    }

    @Nested
    @DisplayName("Martial Arts and Flurry of Blows (PHB p.78)")
    class Monk {

        private EncounterParticipant kai;
        private UUID kaiPlayer;

        @BeforeEach
        void monk() {
            kai = pc("Kai", "Monk", null, 5);
            kai.getCharacter().setStrength(10);
            kai.getCharacter().setWisdom(14);
            kai.getCharacter().setProficiencyBonus(3);
            kai.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry(AttackService.KI_POOL,
                    "Ki Points", "CLASS", null, 5, "5", 5, "shortRest", null, null, "FREE", "zap",
                    Map.of("effect", "ki")))));
            kaiPlayer = f.playerControlling(kai);
            goblin = f.monster("Goblin", 60, 10);
        }

        private void playerAttack(AttackRollRequest request, int... rolls) {
            f.dice.script(rolls);
            f.attacks.rollAttack(f.encounterId(), request, kai.getId(), kaiPlayer);
        }

        private AttackRollRequest strike(AttackCost cost) {
            AttackRollRequest request = request(goblin, "Unarmed Strike");
            request.setCost(cost);
            return request;
        }

        @Test
        @DisplayName("An unarmed strike uses DEX and the martial arts d6, and the Attack action with it allows a bonus "
                + "unarmed strike, which a player can't make before it")
        void martialArts() {
            ConflictException early = assertThrows(ConflictException.class,
                    () -> playerAttack(strike(AttackCost.MARTIAL_ARTS)));
            playerAttack(strike(AttackCost.ACTION), 10, 4);
            playerAttack(strike(AttackCost.MARTIAL_ARTS), 10, 2);

            assertEquals("Martial Arts' bonus unarmed strike follows the Attack action taken with an unarmed strike or "
                    + "a monk weapon this turn", early.getMessage());
            assertEquals(List.of(
                    "Kai uses Unarmed Strike (Martial Arts) on Goblin: d20(10) + 6 = 16 vs AC 10 — hit, 7 bludgeoning "
                            + "damage.",
                    "Kai uses Unarmed Strike (Martial Arts) (bonus action) on Goblin: d20(10) + 6 = 16 vs AC 10 — hit, 5 "
                            + "bludgeoning damage."),
                    f.entriesAbout(goblin).stream().map(e -> e.getDescription()).toList());
            assertTrue(kai.getTurnEconomy().bonusActionUsed());
        }

        @Test
        @DisplayName("Flurry of Blows spends 1 ki and the bonus action for two unarmed strikes; a third is a 409, and a "
                + "quarterstaff a 400")
        void flurry() {
            playerAttack(strike(AttackCost.ACTION), 10, 4);
            playerAttack(strike(AttackCost.FLURRY_OF_BLOWS), 10, 3);
            assertEquals(1, kai.getTurnEconomy().flurryStrikesLeft());
            playerAttack(strike(AttackCost.FLURRY_OF_BLOWS), 10, 3);
            ConflictException third = assertThrows(ConflictException.class,
                    () -> playerAttack(strike(AttackCost.FLURRY_OF_BLOWS)));
            AttackRollRequest staff = request(goblin, "Quarterstaff");
            staff.setCost(AttackCost.FLURRY_OF_BLOWS);
            ValidationException notUnarmed = assertThrows(ValidationException.class, () -> playerAttack(staff));

            assertEquals(4, kai.getResourcePoolsCurrent().getFirst().currentUses());
            assertEquals(0, kai.getTurnEconomy().flurryStrikesLeft());
            assertEquals("It has already taken its bonus action this turn", third.getMessage());
            assertEquals("Flurry of Blows strikes with unarmed strikes, not the Quarterstaff",
                    notUnarmed.getMessage());
            assertEquals(60 - 7 - 6 - 6, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("Stunning Strike after a hit spends 1 ki; a failed DC 13 CON save stuns the goblin until the end of "
                + "Kai's next turn; a second on the same hit is a 409")
        void stunningStrike() {
            playerAttack(strike(AttackCost.ACTION), 10, 4);
            WeaponHit hit = kai.getTurnEconomy().lastMeleeHit();
            assertTrue(hit.stunningStrikeOffered());
            assertFalse(hit.divineSmiteOffered());

            f.dice.script(5);
            f.hitFollowUps.stunningStrike(f.encounterId(), new StunningStrikeRequest(kai.getId(), hit.attackLogId()),
                    kaiPlayer);
            ConflictException again = assertThrows(ConflictException.class, () -> f.hitFollowUps.stunningStrike(
                    f.encounterId(), new StunningStrikeRequest(kai.getId(), null), kaiPlayer));

            ConditionEntry stunned = goblin.getActiveConditions().getFirst();
            assertEquals("stunned", stunned.name());
            assertEquals(EndRule.endOfTurn(kai.getId()), stunned.endRule());
            assertEquals(1, stunned.duration());
            assertEquals(4, kai.getResourcePoolsCurrent().getFirst().currentUses());
            assertEquals("Kai uses Stunning Strike (1 ki, 4/5 left) after the Unarmed Strike hit on Goblin: CON save "
                    + "d20(5) + 0 = 5 vs DC 13 — fails, gains stunned.", lastAbout(goblin));
            assertEquals("Kai has already used Stunning Strike with that hit", again.getMessage());

            // Kai's turn ends, the goblin's passes and Kai's next starts with the goblin still stunned; it ends with it
            f.turns.advanceTurn(f.encounterId(), f.dmUserId);
            f.turns.advanceTurn(f.encounterId(), f.dmUserId);
            assertEquals(List.of("stunned"), CombatFixture.conditionNames(goblin));
            f.turns.advanceTurn(f.encounterId(), f.dmUserId);
            assertTrue(CombatFixture.conditionNames(goblin).isEmpty());
            assertTrue(f.descriptions().contains("stunned ends on Goblin (end of Kai's turn)"));
        }

        @Test
        @DisplayName("Stunning Strike after a miss, or naming another hit, is a 409 that spends no ki")
        void stunningStrikeNeedsTheHit() {
            playerAttack(strike(AttackCost.ACTION), 1);
            ConflictException afterMiss = assertThrows(ConflictException.class, () -> f.hitFollowUps.stunningStrike(
                    f.encounterId(), new StunningStrikeRequest(kai.getId(), null), kaiPlayer));
            playerAttack(strike(AttackCost.ACTION), 10, 4);
            ConflictException stale = assertThrows(ConflictException.class, () -> f.hitFollowUps.stunningStrike(
                    f.encounterId(), new StunningStrikeRequest(kai.getId(), UUID.randomUUID()), kaiPlayer));

            assertEquals("Stunning Strike follows a melee weapon hit on the same turn, and Kai's last attack this turn "
                    + "wasn't one", afterMiss.getMessage());
            assertEquals("That attack is no longer Kai's latest hit; Stunning Strike follows the latest",
                    stale.getMessage());
            assertEquals(5, kai.getResourcePoolsCurrent().getFirst().currentUses());
        }

        @Test
        @DisplayName("In armour Martial Arts gives nothing: the unarmed strike is 1 + STR, and its bonus strike goes over "
                + "for the DM")
        void armour() {
            kai.getCharacter().setArmorProficiencies(List.of("light"));
            f.wears(kai, CombatFixture.armour("Leather", Item.ArmourCategory.LIGHT, 11, false));

            attack(kai, strike(AttackCost.ACTION), 10);
            attack(kai, strike(AttackCost.MARTIAL_ARTS), 10);

            assertEquals(List.of(
                    "Kai uses Unarmed Strike on Goblin: d20(10) + 3 = 13 vs AC 10 — hit, 1 bludgeoning damage.",
                    "Kai's Unarmed Strike attack (bonus action) is over the action economy: Martial Arts needs no armour "
                            + "and no shield",
                    "Kai uses Unarmed Strike (bonus action) on Goblin: d20(10) + 3 = 13 vs AC 10 — hit, 1 bludgeoning "
                            + "damage."),
                    f.descriptions());
        }
    }

    @Nested
    @DisplayName("Divine Smite (PHB p.85)")
    class Smite {

        private EncounterParticipant aria;

        @BeforeEach
        void paladin() {
            aria = pc("Aria", "Paladin", null, 5);
            Map<String, SpellSlot> slots = new LinkedHashMap<>();
            slots.put("1", new SpellSlot(4, 0));
            slots.put("2", new SpellSlot(1, 0));
            aria.setSpellSlotsCurrent(slots);
            goblin = f.monster("Goblin", 60, 10);
        }

        private void smite(int level, UUID attackLogId, int... rolls) {
            f.dice.script(rolls);
            f.hitFollowUps.divineSmite(f.encounterId(), new DivineSmiteRequest(aria.getId(), level, null, attackLogId),
                    f.dmUserId);
        }

        @Test
        @DisplayName("After a longsword hit, a 2nd-level slot deals 3d8 radiant and is spent; a second smite on the hit "
                + "is a 409, and with the slots of a level spent another is a 409")
        void afterAHit() {
            attack(aria, request(goblin, "Longsword"), 10, 5);
            smite(2, aria.getTurnEconomy().lastMeleeHit().attackLogId(), 4, 4, 4);
            ConflictException twice = assertThrows(ConflictException.class, () -> smite(1, null));
            attack(aria, request(goblin, "Longsword"), 10, 5);
            ConflictException noSlot = assertThrows(ConflictException.class, () -> smite(2, null));

            assertEquals(60 - 8 - 12 - 8, goblin.getHpCurrent());
            assertEquals(new SpellSlot(1, 1), aria.getSpellSlotsCurrent().get("2"));
            assertEquals("Aria uses Divine Smite (3d8 radiant; level 2 spell slot, 0/1 left) after the Longsword hit on "
                    + "Goblin: 12 radiant damage.", f.entriesAbout(goblin).get(1).getDescription());
            assertEquals("Aria has already smitten with that hit", twice.getMessage());
            assertEquals("Aria has no level 2 spell slot left", noSlot.getMessage());
        }

        @Test
        @DisplayName("On a critical hit the smite's dice are doubled, and an undead takes 1d8 more")
        void criticalUndead() {
            goblin.getMonster().setType("undead");

            attack(aria, request(goblin, "Longsword"), 20, 1, 1);
            smite(1, null, 1, 2, 3, 4, 5, 6);

            assertEquals(60 - 5 - 21, goblin.getHpCurrent());
            assertTrue(f.entriesAbout(goblin).getLast().getDescription().startsWith(
                    "Aria uses Divine Smite (6d8 radiant; level 1 spell slot, 3/4 left; critical hit; +1d8 against "
                            + "undead)"));
        }

        @Test
        @DisplayName("After a miss or a ranged hit there is nothing to smite, and slots are kept")
        void nothingToSmite() {
            attack(aria, request(goblin, "Longsword"), 2);
            ConflictException miss = assertThrows(ConflictException.class, () -> smite(1, null));
            attack(aria, request(goblin, "Longbow"), 15, 4);
            assertThrows(ConflictException.class, () -> smite(1, null));

            assertEquals("Divine Smite follows a melee weapon hit on the same turn, and Aria's last attack this turn "
                    + "wasn't one", miss.getMessage());
            assertEquals(new SpellSlot(4, 0), aria.getSpellSlotsCurrent().get("1"));
        }

        @Test
        @DisplayName("An unarmed strike is a melee weapon attack (PHB p.195), so its hit can be smitten, once")
        void unarmedStrike() {
            // 15 + 5 hits for 1 + STR 3; a 1st-level smite's 2d8 is 3 + 5
            attack(aria, request(goblin, "Unarmed Strike"), 15);
            assertTrue(aria.getTurnEconomy().lastMeleeHit().divineSmiteOffered());
            smite(1, null, 3, 5);
            ConflictException twice = assertThrows(ConflictException.class, () -> smite(1, null));

            assertEquals(60 - 4 - 8, goblin.getHpCurrent());
            assertEquals(new SpellSlot(4, 1), aria.getSpellSlotsCurrent().get("1"));
            assertTrue(f.entriesAbout(goblin).getLast().getDescription().startsWith(
                    "Aria uses Divine Smite (2d8 radiant; level 1 spell slot, 3/4 left) after the Unarmed Strike hit"));
            assertEquals("Aria has already smitten with that hit", twice.getMessage());
        }

        @Nested
        @DisplayName("A concentrating target makes one check against the hit and the smite together (PHB p.203)")
        class Concentration {

            @BeforeEach
            void concentrating() {
                goblin.setConcentration(ConcentrationInstance.start("Hold Person", 2, 1, 10));
            }

            private List<CombatLog> checks() {
                return f.entries(CombatActionType.CONCENTRATION_CHECK);
            }

            @Test
            @DisplayName("A check the hit's 8 damage passed with 12 fails against the 32 combined damage's DC 16, with "
                    + "no second roll")
            void higherDcFails() {
                // The hit: d20 10 hits, 5 + 3 = 8 damage, then the check's d20 12 against DC 10
                attack(aria, request(goblin, "Longsword"), 10, 5, 12);
                assertEquals(new ConcentrationCheck(goblin.getConcentration().id(), 8, 10, 12, true),
                        aria.getTurnEconomy().lastMeleeHit().concentrationCheck());
                // A 2nd-level smite's 3d8 is 24; the dice script holds no d20, so a second save would fail the test
                smite(2, null, 8, 8, 8);

                assertNull(goblin.getConcentration());
                assertEquals(2, checks().size());
                assertEquals("Goblin's concentration check covers the hit's whole damage (32): its total of 12 vs DC "
                        + "16, and fails", checks().getLast().getDescription());
                assertEquals(12, checks().getLast().getRollTotal());
                assertEquals(0, f.dice.remaining());
            }

            @Test
            @DisplayName("A check whose total reaches the combined damage's higher DC keeps the concentration")
            void higherDcHeld() {
                attack(aria, request(goblin, "Longsword"), 10, 5, 18);
                smite(2, null, 8, 8, 8);

                assertEquals("Hold Person", goblin.getConcentration().spell());
                assertEquals("Goblin's concentration check covers the hit's whole damage (32): its total of 18 vs DC "
                        + "16, and maintains Hold Person", checks().getLast().getDescription());
            }

            @Test
            @DisplayName("A smite that leaves the DC at 10 changes nothing and logs no second check")
            void sameDc() {
                attack(aria, request(goblin, "Longsword"), 10, 5, 11);
                smite(1, null, 1, 1);

                assertEquals("Hold Person", goblin.getConcentration().spell());
                assertEquals(1, checks().size());
                assertEquals(60 - 8 - 2, goblin.getHpCurrent());
            }

            @Test
            @DisplayName("After the hit's check failed, the smite rolls no check: the concentration is already gone")
            void hitCheckFailed() {
                attack(aria, request(goblin, "Longsword"), 10, 5, 3);
                assertNull(goblin.getConcentration());
                smite(2, null, 8, 8, 8);

                assertEquals(1, checks().size());
                assertEquals(60 - 8 - 24, goblin.getHpCurrent());
            }

            @Test
            @DisplayName("When the hit dealt no damage, so made no check, the smite's damage makes the one check")
            void hitDealtNoDamage() {
                goblin.getMonster().setDamageImmunities("[\"slashing\"]");

                attack(aria, request(goblin, "Longsword"), 10, 5);
                assertNull(aria.getTurnEconomy().lastMeleeHit().concentrationCheck());
                // 3d8 for 24 radiant, then the check's d20 9 against DC 12
                smite(2, null, 8, 8, 8, 9);

                assertNull(goblin.getConcentration());
                assertEquals(1, checks().size());
                assertTrue(checks().getFirst().getDescription().endsWith("= 9 vs DC 12, and fails"));
            }
        }

        @Test
        @DisplayName("The hit can't be smitten once the turn has passed")
        void sameTurn() {
            attack(aria, request(goblin, "Longsword"), 10, 5);
            f.turnOf(goblin);

            assertThrows(ConflictException.class, () -> smite(1, null));
        }
    }
}
