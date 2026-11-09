package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackCost;
import com.tabletophelper.encounter.dto.AttackOption;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.DeftStrikeRequest;
import com.tabletophelper.encounter.dto.StunningStrikeRequest;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.WeaponHit;
import com.tabletophelper.rules.combat.KenseiFeatures;
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

import static com.tabletophelper.encounter.internal.CombatFixture.versatileWeapon;
import static com.tabletophelper.encounter.internal.CombatFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Way of the Kensei's combat features (XGE p.34-35): Agile Parry, Kensei's Shot, Deft Strike, Sharpen the Blade and
 * Unerring Accuracy. Ren is a Kensei monk with DEX 16, WIS 14 and +3 proficiency whose kensei weapons are the longsword
 * and the longbow; the goblin has AC 10 and 60 HP. Ren's player acts, on Ren's turn unless a test says otherwise.
 */
class KenseiTest {

    private CombatFixture f;
    private EncounterParticipant ren;
    private EncounterParticipant goblin;
    private UUID renPlayer;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.weapons(versatileWeapon("Longsword", "martial", "1d8", "1d10", "slashing"),
                versatileWeapon("Quarterstaff", "simple", "1d6", "1d8", "bludgeoning"),
                weapon("Longbow", "Ranged Weapon", "martial", "1d8", "piercing", "A", "H", "2H"));
    }

    // A Kensei monk of the level, carrying the weapons, whose kensei weapons are those named
    private void kensei(int level, List<String> kenseiWeapons, String... carried) {
        ren = f.player("Ren", 40, 15);
        ren.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Monk", null,
                "Way of the Kensei", level)));
        ren.getCharacter().setLevel(level);
        ren.getCharacter().setDexterity(16);
        ren.getCharacter().setWisdom(14);
        ren.getCharacter().setProficiencyBonus(3);
        ren.getCharacter().setLevelHistory(List.of(new LevelHistoryEntry(3, null, "Monk", 3, 0, null, null,
                LevelChoices.NONE.withKenseiWeapons(kenseiWeapons), null, null)));
        CombatFixture.carries(ren, List.of("simple", "martial"), carried);
        ren.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry(AttackService.KI_POOL,
                "Ki Points", "CLASS", null, level, String.valueOf(level), level, "shortRest", null, null, "FREE", "zap",
                Map.of("effect", "ki")))));
        renPlayer = f.playerControlling(ren);
        goblin = f.monster("Goblin", 60, 10);
    }

    private void kensei(int level) {
        kensei(level, List.of("Longsword", "Longbow"), "Longsword", "Quarterstaff", "Longbow");
    }

    private AttackRollRequest request(String weapon) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setWeapon(weapon);
        return request;
    }

    private void attack(AttackRollRequest request, int... rolls) {
        f.dice.script(rolls);
        f.attacks.rollAttack(f.encounterId(), request, ren.getId(), renPlayer);
    }

    private void ki(String option, Integer uses, String weapon) {
        f.poolUse.usePool(f.encounterId(), new UsePoolRequest(ren.getId(), AttackService.KI_POOL, uses, option, null,
                null, null, weapon), renPlayer);
    }

    private int kiLeft() {
        return ren.getResourcePoolsCurrent().getFirst().currentUses();
    }

    private ConditionEntry entry(String name) {
        return ren.getActiveConditions() == null ? null : ren.getActiveConditions().stream()
                .filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    private String lastAboutGoblin() {
        return f.entriesAbout(goblin).getLast().getDescription();
    }

    @Nested
    @DisplayName("Agile Parry (XGE p.34)")
    class AgileParry {

        @Test
        @DisplayName("An unarmed strike of the Attack action while carrying the longsword gives +2 AC until the start "
                + "of Ren's next turn, none while incapacitated")
        void parries() {
            kensei(3);
            AttackRollRequest strike = request("Unarmed Strike");
            attack(strike, 10, 3);

            ConditionEntry parry = entry(KenseiFeatures.AGILE_PARRY);
            assertEquals(2, parry.bonus());
            assertEquals("Longsword", parry.weapon());
            assertEquals(EndRule.startOfTurn(ren.getId()), parry.endRule());
            assertEquals(17, CreatureStats.armourClass(ren));
            assertTrue(f.descriptions().contains("Ren parries with its Longsword (Agile Parry): +2 AC until the start "
                    + "of its next turn, while it isn't incapacitated"));

            ren.getActiveConditions().add(ConditionEntry.manual("stunned", null, 1));
            assertEquals(15, CreatureStats.armourClass(ren));
            ren.getActiveConditions().removeIf(c -> c.name().equals("stunned"));

            // Ren's turn ends, the goblin's passes, and the parry ends as Ren's next turn starts
            f.turns.advanceTurn(f.encounterId(), f.dmUserId);
            assertEquals(17, CreatureStats.armourClass(ren));
            f.turns.advanceTurn(f.encounterId(), f.dmUserId);
            assertNull(entry(KenseiFeatures.AGILE_PARRY));
            assertEquals(15, CreatureStats.armourClass(ren));
        }

        @Test
        @DisplayName("No parry from a weapon attack, Martial Arts' bonus strike, or without a melee kensei weapon")
        void noParry() {
            kensei(3);
            attack(request("Longsword"), 10, 3);
            AttackRollRequest bonus = request("Unarmed Strike");
            bonus.setCost(AttackCost.MARTIAL_ARTS);
            attack(bonus, 10, 3);
            assertNull(entry(KenseiFeatures.AGILE_PARRY));

            f = new CombatFixture();
            f.weapons(weapon("Longbow", "Ranged Weapon", "martial", "1d8", "piercing", "A", "H", "2H"));
            kensei(3, List.of("Longbow"), "Longbow");
            attack(request("Unarmed Strike"), 10, 3);
            assertNull(entry(KenseiFeatures.AGILE_PARRY));
        }

        @Test
        @DisplayName("Undo takes the parry back (D176)")
        void undo() {
            kensei(3);
            f.recordCommands();
            f.started();
            f.dice.script(10, 3);
            f.inTransaction(() -> f.attacks.rollAttack(f.encounterId(), request("Unarmed Strike"), ren.getId(),
                    renPlayer));
            UUID struck = f.commands.getLast().getId();
            f.inTransaction(() -> f.undoService.undo(f.encounterId(), new UndoRequest(struck, 1), f.dmUserId));

            assertNull(entry(KenseiFeatures.AGILE_PARRY));
            assertEquals(15, CreatureStats.armourClass(ren));
        }
    }

    @Nested
    @DisplayName("Kensei's Shot (XGE p.34)")
    class KenseisShot {

        @Test
        @DisplayName("A bonus action spending no ki adds 1d4 piercing to the longbow's hit, until the end of the turn")
        void shot() {
            kensei(3);
            ki(FeatureEffects.KENSEIS_SHOT, null, null);

            assertEquals(3, kiLeft());
            assertTrue(ren.getTurnEconomy().bonusActionUsed());
            assertEquals(EndRule.endOfTurn(ren.getId()), entry(KenseiFeatures.KENSEIS_SHOT).endRule());
            assertTrue(f.descriptions().getLast().startsWith("Ren uses Ki Points (bonus action) for Kensei's Shot, "
                    + "spending no ki"));

            attack(request("Longbow"), 10, 5, 3);
            assertEquals("Ren uses Longbow (Martial Arts, Kensei's Shot +1d4) on Goblin: d20(10) + 6 = 16 vs AC 10 — "
                    + "hit, 11 piercing damage.", lastAboutGoblin());
            // A melee attack gets nothing from it (a second Attack action, as the economy doesn't matter here)
            CombatFixture.newTurn(ren);
            attack(request("Longsword"), 10, 5);
            assertEquals(60 - 11 - 8, goblin.getHpCurrent());

            f.turns.advanceTurn(f.encounterId(), f.dmUserId);
            assertNull(entry(KenseiFeatures.KENSEIS_SHOT));
        }

        @Test
        @DisplayName("Another monk's Kensei's Shot is a 400")
        void notKensei() {
            kensei(3);
            ren.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Monk", null,
                    "Way of the Open Hand", 3)));
            ValidationException refused = assertThrows(ValidationException.class,
                    () -> ki(FeatureEffects.KENSEIS_SHOT, null, null));
            assertEquals("Kensei's Shot needs Monk level 3 in the Way of the Kensei, and Ren isn't a Kensei monk",
                    refused.getMessage());
        }
    }

    @Nested
    @DisplayName("Deft Strike (XGE p.34)")
    class DeftStrike {

        @Test
        @DisplayName("After a longbow hit, 1 ki adds the martial arts d6; once a turn, and Stunning Strike can't follow "
                + "a ranged hit")
        void deftStrike() {
            kensei(6);
            attack(request("Longbow"), 10, 5);
            WeaponHit hit = ren.getTurnEconomy().lastMeleeHit();
            assertTrue(hit.ranged());
            assertTrue(hit.deftStrikeOffered());
            assertFalse(hit.stunningStrikeOffered());

            ConflictException stun = assertThrows(ConflictException.class, () -> f.hitFollowUps.stunningStrike(
                    f.encounterId(), new StunningStrikeRequest(ren.getId(), null), renPlayer));
            f.dice.script(4);
            f.hitFollowUps.deftStrike(f.encounterId(), new DeftStrikeRequest(ren.getId(), hit.attackLogId()),
                    renPlayer);

            assertEquals("Stunning Strike follows a melee weapon hit, and Ren's Longbow hit was a ranged attack",
                    stun.getMessage());
            assertEquals("Ren uses Deft Strike (1d6 piercing; 1 ki, 5/6 left) after the Longbow hit on Goblin: 4 "
                    + "piercing damage.", lastAboutGoblin());
            assertEquals(5, kiLeft());
            assertTrue(ren.getTurnEconomy().deftStrikeUsed());

            attack(request("Longsword"), 10, 5);
            ConflictException again = assertThrows(ConflictException.class, () -> f.hitFollowUps.deftStrike(
                    f.encounterId(), new DeftStrikeRequest(ren.getId(), null), renPlayer));
            assertEquals("Ren has already used Deft Strike this turn; it can be used once on each of its turns",
                    again.getMessage());
            assertEquals(5, kiLeft());
        }

        @Test
        @DisplayName("Not after an unarmed strike, nor off Ren's turn")
        void needsKenseiWeaponOnOwnTurn() {
            kensei(6);
            attack(request("Unarmed Strike"), 10, 3);
            ConflictException unarmed = assertThrows(ConflictException.class, () -> f.hitFollowUps.deftStrike(
                    f.encounterId(), new DeftStrikeRequest(ren.getId(), null), renPlayer));
            assertEquals("Deft Strike follows a hit with a kensei weapon, and Ren's Unarmed Strike isn't one",
                    unarmed.getMessage());

            f.turnOf(goblin);
            AttackRollRequest opportunity = request("Longsword");
            opportunity.setCost(AttackCost.REACTION);
            attack(opportunity, 10, 5);
            assertFalse(ren.getTurnEconomy().lastMeleeHit().deftStrikeOffered());
            ConflictException offTurn = assertThrows(ConflictException.class, () -> f.hitFollowUps.deftStrike(
                    f.encounterId(), new DeftStrikeRequest(ren.getId(), null), renPlayer));
            assertEquals("Deft Strike is used on Ren's own turn", offTurn.getMessage());
            assertEquals(6, kiLeft());
        }

        @Test
        @DisplayName("A critical hit doubles the die")
        void critical() {
            kensei(6);
            attack(request("Longsword"), 20, 5, 5);
            f.dice.script(3, 4);
            f.hitFollowUps.deftStrike(f.encounterId(), new DeftStrikeRequest(ren.getId(), null), renPlayer);
            assertEquals("Ren uses Deft Strike (2d6 slashing; 1 ki, 5/6 left; critical hit) after the Longsword hit on "
                    + "Goblin: 7 slashing damage.", lastAboutGoblin());
        }

        @Test
        @DisplayName("Undo gives the ki and the use back (D176)")
        void undo() {
            kensei(6);
            f.recordCommands();
            f.started();
            f.dice.script(10, 5);
            f.inTransaction(() -> f.attacks.rollAttack(f.encounterId(), request("Longsword"), ren.getId(), renPlayer));
            f.dice.script(4);
            f.inTransaction(() -> f.hitFollowUps.deftStrike(f.encounterId(), new DeftStrikeRequest(ren.getId(), null),
                    renPlayer));
            UUID struck = f.commands.getLast().getId();
            f.inTransaction(() -> f.undoService.undo(f.encounterId(), new UndoRequest(struck, 1), f.dmUserId));

            assertEquals(6, kiLeft());
            assertFalse(ren.getTurnEconomy().deftStrikeUsed());
            assertEquals(60 - 8, goblin.getHpCurrent());
        }
    }

    @Nested
    @DisplayName("Sharpen the Blade (XGE p.35)")
    class SharpenTheBlade {

        @Test
        @DisplayName("2 ki give the longsword +2 to attack and damage for 1 minute; using it again replaces it")
        void sharpen() {
            kensei(11);
            ki(FeatureEffects.SHARPEN_THE_BLADE, 2, "longsword");

            ConditionEntry sharpened = entry(KenseiFeatures.SHARPEN_THE_BLADE);
            assertEquals(2, sharpened.bonus());
            assertEquals("Longsword", sharpened.weapon());
            assertEquals(10, sharpened.duration());
            assertEquals(EndRule.afterRounds(ren.getId()), sharpened.endRule());
            assertEquals(9, kiLeft());
            assertTrue(ren.getTurnEconomy().bonusActionUsed());

            AttackOption longsword = f.attacks.attackOptions(f.encounterId(), ren.getId(), renPlayer).stream()
                    .filter(o -> o.weapon().equals("Longsword")).findFirst().orElseThrow();
            assertEquals(3 + 3 + 2, longsword.attackBonus());
            assertEquals("1d8 + 5", longsword.damage());
            assertTrue(longsword.features().contains("Sharpen the Blade +2"));
            assertTrue(longsword.kenseiWeapon());

            attack(request("Longsword"), 10, 5);
            assertEquals("Ren uses Longsword (Sharpen the Blade +2, Martial Arts) on Goblin: d20(10) + 8 = 18 vs AC "
                    + "10 — hit, 10 slashing damage.", lastAboutGoblin());

            CombatFixture.newTurn(ren);
            ki(FeatureEffects.SHARPEN_THE_BLADE, 1, "Longbow");
            assertEquals(1, ren.getActiveConditions().stream()
                    .filter(c -> c.name().equals(KenseiFeatures.SHARPEN_THE_BLADE)).count());
            assertEquals("Longbow", entry(KenseiFeatures.SHARPEN_THE_BLADE).weapon());
        }

        @Test
        @DisplayName("4 ki, a weapon that isn't a kensei weapon, or no weapon with two to choose from are 400s")
        void refusals() {
            kensei(11);
            assertEquals("Sharpen the Blade spends 1 to 3 ki points, not 4", assertThrows(ValidationException.class,
                    () -> ki(FeatureEffects.SHARPEN_THE_BLADE, 4, "Longsword")).getMessage());
            assertEquals("Sharpen the Blade sharpens a kensei weapon Ren carries, and Quarterstaff isn't one; choose "
                    + "Longsword, Longbow", assertThrows(ValidationException.class,
                    () -> ki(FeatureEffects.SHARPEN_THE_BLADE, 1, "Quarterstaff")).getMessage());
            assertEquals("Name the kensei weapon Sharpen the Blade sharpens: Longsword, Longbow",
                    assertThrows(ValidationException.class,
                            () -> ki(FeatureEffects.SHARPEN_THE_BLADE, 1, null)).getMessage());
            assertEquals(11, kiLeft());
        }
    }

    @Nested
    @DisplayName("Unerring Accuracy (XGE p.35)")
    class UnerringAccuracy {

        @Test
        @DisplayName("A miss with the longsword is rerolled once a turn, and the new roll stands")
        void reroll() {
            kensei(17);
            AttackRollRequest attack = request("Longsword");
            attack.setUnerringAccuracy(true);
            attack(attack, 1, 15, 6);

            assertEquals("Ren uses Longsword (Martial Arts) on Goblin: d20(1) misses, Unerring Accuracy rerolls: "
                    + "d20(15) + 6 = 21 vs AC 10 — hit, 9 slashing damage.", lastAboutGoblin());
            assertTrue(ren.getTurnEconomy().unerringAccuracyUsed());

            ConflictException again = assertThrows(ConflictException.class, () -> attack(attack, 1));
            assertEquals("Ren has already used Unerring Accuracy this turn; it can be used once on each of its turns",
                    again.getMessage());
        }

        @Test
        @DisplayName("A hit spends nothing, and the unarmed strike isn't a monk weapon")
        void hitOrUnarmed() {
            kensei(17);
            AttackRollRequest attack = request("Longsword");
            attack.setUnerringAccuracy(true);
            attack(attack, 12, 6);
            assertFalse(ren.getTurnEconomy().unerringAccuracyUsed());

            AttackRollRequest strike = request("Unarmed Strike");
            strike.setUnerringAccuracy(true);
            ValidationException unarmed = assertThrows(ValidationException.class, () -> attack(strike, 1));
            assertEquals("Unerring Accuracy rerolls an attack with a monk weapon, and an unarmed strike isn't one for "
                    + "Ren", unarmed.getMessage());
        }

        @Test
        @DisplayName("Below Monk 17 it is a 400; off Ren's turn a 409")
        void refusals() {
            kensei(11);
            AttackRollRequest attack = request("Longsword");
            attack.setUnerringAccuracy(true);
            assertThrows(ValidationException.class, () -> attack(attack, 1));

            f = new CombatFixture();
            f.weapons(versatileWeapon("Longsword", "martial", "1d8", "1d10", "slashing"));
            kensei(17, List.of("Longsword"), "Longsword");
            f.turnOf(goblin);
            AttackRollRequest opportunity = request("Longsword");
            opportunity.setCost(AttackCost.REACTION);
            opportunity.setUnerringAccuracy(true);
            assertEquals("Unerring Accuracy rerolls a miss on Ren's own turn",
                    assertThrows(ConflictException.class, () -> attack(opportunity, 1)).getMessage());
        }

        @Test
        @DisplayName("Attack options offer it with the kensei weapons and the quarterstaff, not the unarmed strike")
        void options() {
            kensei(17);
            List<AttackOption> options = f.attacks.attackOptions(f.encounterId(), ren.getId(), renPlayer);
            assertEquals(List.of(true, true, true, false), options.stream().map(AttackOption::unerringAccuracy)
                    .toList());
            assertEquals(List.of(true, false, true, false), options.stream().map(AttackOption::kenseiWeapon)
                    .toList());
        }
    }
}
