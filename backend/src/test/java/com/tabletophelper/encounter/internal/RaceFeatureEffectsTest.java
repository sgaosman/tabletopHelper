package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.rules.combat.LifeState;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Racial features used through the pool-use command (M24.5b, D208), and Relentless Endurance on the damage path. The
 * DM acts unless a test says otherwise; each character's numbers are set by its test.
 */
class RaceFeatureEffectsTest {

    private CombatFixture f;
    private EncounterParticipant goblin;
    private EncounterParticipant orc;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        goblin = f.monster("Goblin", 30, 13);
        orc = f.monster("Orc", 30, 13);
    }

    private static ResourcePoolEntry pool(String poolId, String name, String cost, int max, Map<String, Object> metadata) {
        return new ResourcePoolEntry(poolId, name, "RACE", null, max, String.valueOf(max), max, "shortRest", null, null,
                cost, "wind", metadata);
    }

    private static void give(EncounterParticipant pc, ResourcePoolEntry... pools) {
        pc.setResourcePoolsCurrent(new ArrayList<>(List.of(pools)));
    }

    private static void level(EncounterParticipant pc, int level, int proficiency) {
        pc.getCharacter().setLevel(level);
        pc.getCharacter().setProficiencyBonus(proficiency);
    }

    private void use(EncounterParticipant user, String poolId, String option, List<Integer> amounts,
                     EncounterParticipant... targets) {
        List<UUID> ids = targets.length == 0 ? null : java.util.Arrays.stream(targets).map(EncounterParticipant::getId)
                .toList();
        f.poolUse.usePool(f.encounterId(), new UsePoolRequest(user.getId(), poolId, 1, option, ids, amounts),
                f.dmUserId);
    }

    private static int usesLeft(EncounterParticipant participant, String poolId) {
        return participant.getResourcePoolsCurrent().stream().filter(p -> p.poolId().equals(poolId)).findFirst()
                .orElseThrow().currentUses();
    }

    private static ConditionEntry entry(EncounterParticipant participant, String name) {
        return participant.getActiveConditions() == null ? null : participant.getActiveConditions().stream()
                .filter(c -> c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private void damage(EncounterParticipant target, int amount) {
        DamageRequest request = new DamageRequest();
        request.setTargetId(target.getId());
        request.setAmount(amount);
        f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId);
    }

    private String lastPoolUse() {
        // The use-pool command's own entry, which ends with the uses left, not the entries its effect writes per creature
        List<String> uses = f.entries(CombatActionType.POOL_USE).stream().map(e -> e.getDescription())
                .filter(d -> d.endsWith(" left") || d.contains(" regains ")).toList();
        return uses.getLast();
    }

    @Nested
    @DisplayName("Breath Weapon (PHB p.34)")
    class BreathWeapon {

        private static final String BREATH = "race:breath-weapon";
        private EncounterParticipant kriv;

        @BeforeEach
        void dragonborn() {
            kriv = f.player("Kriv", 40, 16);
            f.turnOf(kriv);
            level(kriv, 6, 3);
            kriv.getCharacter().setConstitution(14);
            give(kriv, pool(BREATH, "Breath Weapon", "ACTION", 1, Map.of("effect", "breath-weapon")));
        }

        private void ancestry(String dragon) {
            kriv.getCharacter().setLevelHistory(List.of(new LevelHistoryEntry(1, null, "Fighter", 1, 10, null, null,
                    LevelChoices.NONE.withDraconicAncestry(dragon), null, null)));
        }

        @Test
        @DisplayName("A red dragonborn of 6th level breathes 3d6 fire, DEX save DC 8 + 2 + 3 = 13, half on a success; "
                + "a creature resistant to fire takes half again")
        void redBreath() {
            ancestry("Red");
            goblin.getMonster().setDamageResistances("[\"fire\"]");
            // The damage once for the area, then each creature's save: the goblin fails, the orc succeeds
            f.dice.script(4, 5, 6, 5, 18);

            use(kriv, BREATH, null, null, goblin, orc);

            assertEquals(30 - 7, goblin.getHpCurrent(), "15 fire, halved by its resistance");
            assertEquals(30 - 7, orc.getHpCurrent(), "15 fire, halved by its save");
            assertEquals(0, usesLeft(kriv, BREATH));
            assertEquals(1, kriv.getTurnEconomy().actionsUsed());
            assertTrue(lastPoolUse().contains("Red ancestry, 15 ft. cone, DEX save DC 13): 3d6 fire damage"),
                    lastPoolUse());
            assertEquals(0, f.dice.remaining());
        }

        @Test
        @DisplayName("Without a recorded ancestry the option names it: a white dragonborn's cold breath is a CON save")
        void ancestryFromOption() {
            orc.getMonster().setConstitution(20);
            f.dice.script(1, 1, 1, 8);

            use(kriv, BREATH, "white", null, orc);

            // CON +5 makes 13: a success, so half of 3
            assertEquals(30 - 1, orc.getHpCurrent());
            assertTrue(lastPoolUse().contains("White ancestry, 15 ft. cone, CON save DC 13"), lastPoolUse());
        }

        @Test
        @DisplayName("A breath with no ancestry recorded or named, or another than the recorded one, is refused before "
                + "anything is spent")
        void ancestryRefusals() {
            assertThrows(ValidationException.class, () -> use(kriv, BREATH, null, null, goblin));
            assertThrows(ValidationException.class, () -> use(kriv, BREATH, "purple", null, goblin));
            ancestry("Blue");
            assertThrows(ValidationException.class, () -> use(kriv, BREATH, "red", null, goblin));

            assertEquals(1, usesLeft(kriv, BREATH));
        }

        @Test
        @DisplayName("The breath needs the creatures in its area, not the dragonborn itself")
        void targetRefusals() {
            ancestry("Red");
            assertThrows(ValidationException.class, () -> use(kriv, BREATH, null, null));
            assertThrows(ValidationException.class, () -> use(kriv, BREATH, null, null, kriv));
            assertEquals(1, usesLeft(kriv, BREATH));
        }
    }

    @Nested
    @DisplayName("Relentless Endurance (PHB p.41)")
    class RelentlessEndurance {

        private static final String ENDURANCE = "race:relentless-endurance";
        private EncounterParticipant grosh;

        @BeforeEach
        void halfOrc() {
            grosh = f.player("Grosh", 20, 14);
            give(grosh, pool(ENDURANCE, "Relentless Endurance", "FREE", 1,
                    Map.of("effect", "relentless-endurance")));
        }

        @Test
        @DisplayName("Damage that would drop the half-orc to 0 without killing it leaves it at 1 HP and spends the use")
        void dropsToOne() {
            damage(grosh, 25);

            assertEquals(1, grosh.getHpCurrent());
            assertEquals(LifeState.ALIVE, grosh.getLifeState());
            assertEquals(0, usesLeft(grosh, ENDURANCE));
            assertTrue(f.descriptions().stream().anyMatch(d -> d.contains("Grosh drops to 1 hit point instead of 0 "
                    + "(Relentless Endurance; 0/1 left)")), f.descriptions().toString());
        }

        @Test
        @DisplayName("Massive damage kills outright, so the trait does nothing and keeps its use")
        void massiveDamageKills() {
            // 20 HP and 40 damage: 20 left over at 0, at least the maximum
            damage(grosh, 40);

            assertEquals(LifeState.DEAD, grosh.getLifeState());
            assertEquals(1, usesLeft(grosh, ENDURANCE));
        }

        @Test
        @DisplayName("With the use spent, the next drop to 0 leaves the half-orc dying")
        void secondDropIsDying() {
            damage(grosh, 25);
            damage(grosh, 5);

            assertEquals(0, grosh.getHpCurrent());
            assertEquals(LifeState.DYING, grosh.getLifeState());
        }

        @Test
        @DisplayName("Damage that leaves it above 0 spends nothing, and the pool can't be used by hand")
        void otherwiseUntouched() {
            damage(grosh, 10);

            assertEquals(10, grosh.getHpCurrent());
            assertEquals(1, usesLeft(grosh, ENDURANCE));
            assertThrows(ConflictException.class, () -> use(grosh, ENDURANCE, null, null));
        }

        @Test
        @DisplayName("Temporary hit points take the damage first; the trait meets only what gets through them")
        void temporaryHitPointsFirst() {
            grosh.setHpTemp(5);

            damage(grosh, 30);

            assertEquals(1, grosh.getHpCurrent());
            assertEquals(0, grosh.getHpTemp());
            assertEquals(0, usesLeft(grosh, ENDURANCE));
        }
    }

    @Nested
    @DisplayName("Healing and temporary hit points")
    class Healing {

        @Test
        @DisplayName("Stone's Endurance rolls 1d12 + CON and restores that much of the damage just taken, as a "
                + "reaction, never more than that damage (VGM p.109)")
        void stonesEndurance() {
            EncounterParticipant goliath = f.player("Kavaki", 30, 15);
            goliath.getCharacter().setConstitution(14);
            goliath.setHpCurrent(18);
            give(goliath, pool("race:stones-endurance", "Stone's Endurance", "REACTION", 2,
                    Map.of("effect", "stones-endurance")));
            f.dice.script(3);

            use(goliath, "race:stones-endurance", null, List.of(12));

            assertEquals(23, goliath.getHpCurrent(), "3 + 2 = 5 of the 12 restored");
            assertTrue(goliath.getTurnEconomy().reactionUsed());

            f.dice.script(12);
            CombatFixture.newTurn(goliath);
            use(goliath, "race:stones-endurance", null, List.of(4));
            assertEquals(27, goliath.getHpCurrent(), "14 rolled, but only the 4 damage taken");

            assertThrows(ValidationException.class, () -> use(goliath, "race:stones-endurance", null, null));
        }

        @Test
        @DisplayName("Healing Hands restores the aasimar's level to the creature it touches, itself by default "
                + "(VGM p.105)")
        void healingHands() {
            EncounterParticipant aasimar = f.player("Lumen", 30, 14);
            EncounterParticipant ally = f.player("Ally", 30, 14);
            f.turnOf(aasimar);
            level(aasimar, 4, 2);
            ally.setHpCurrent(10);
            give(aasimar, pool("race:healing-hands", "Healing Hands", "ACTION", 1,
                    Map.of("effect", "healing-hands", "healing", "totalLevel")));

            use(aasimar, "race:healing-hands", null, null, ally);

            assertEquals(14, ally.getHpCurrent());
            assertEquals(0, usesLeft(aasimar, "race:healing-hands"));
        }

        @Test
        @DisplayName("Hungry Jaws gives CON-modifier temporary hit points when the bite hit, and nothing when it "
                + "missed (VGM p.113)")
        void hungryJaws() {
            EncounterParticipant lizard = f.player("Scales", 30, 15);
            f.turnOf(lizard);
            lizard.getCharacter().setConstitution(14);
            give(lizard, pool("race:hungry-jaws", "Hungry Jaws", "BONUS_ACTION", 2,
                    Map.of("effect", "hungry-jaws", "tempHp", "max(1, constitutionModifier)")));

            use(lizard, "race:hungry-jaws", "miss", null);
            assertEquals(0, lizard.getHpTemp());

            CombatFixture.newTurn(lizard);
            use(lizard, "race:hungry-jaws", null, null);
            assertEquals(2, lizard.getHpTemp());
            assertEquals(0, usesLeft(lizard, "race:hungry-jaws"));
        }

        @Test
        @DisplayName("A Beasthide shifter shifts for 1 minute with level + CON + 1d6 temporary hit points (ERLW p.34)")
        void beasthideShifting() {
            EncounterParticipant shifter = f.player("Fang", 30, 15);
            f.turnOf(shifter);
            level(shifter, 3, 2);
            shifter.getCharacter().setConstitution(12);
            give(shifter, pool("race:shifting-beasthide", "Shifting (Beasthide)", "BONUS_ACTION", 1,
                    Map.of("effect", "shifting", "tempHp", "max(1, totalLevel + constitutionModifier) + 1d6",
                            "rider", "+1 AC while shifted (the DM adds it)")));
            f.dice.script(5);

            use(shifter, "race:shifting-beasthide", null, null);

            assertEquals(3 + 1 + 5, shifter.getHpTemp());
            ConditionEntry shifted = entry(shifter, RaceFeatureEffects.SHIFTED);
            assertNotNull(shifted);
            assertEquals(10, shifted.duration());
            assertTrue(lastPoolUse().contains("+1 AC while shifted"), lastPoolUse());
        }
    }

    @Nested
    @DisplayName("Conditions")
    class Conditions {

        @Test
        @DisplayName("Hidden Step makes the firbolg invisible until the start of its next turn, ending if it attacks "
                + "or casts (VGM p.107)")
        void hiddenStep() {
            EncounterParticipant firbolg = f.player("Moss", 30, 14);
            f.turnOf(firbolg);
            give(firbolg, pool("race:hidden-step", "Hidden Step", "BONUS_ACTION", 1, Map.of("effect", "hidden-step")));

            use(firbolg, "race:hidden-step", null, null);

            ConditionEntry invisible = entry(firbolg, "invisible");
            assertNotNull(invisible);
            assertEquals(1, invisible.duration());
            assertEquals(Boolean.TRUE, invisible.endsOnAttackOrCast());
            assertTrue(firbolg.getTurnEconomy().bonusActionUsed());
        }

        @Test
        @DisplayName("Daunting Roar frightens a creature that fails a WIS save DC 8 + CON + PB until the end of the "
                + "leonin's next turn; a deafened creature is unaffected (MOT p.21)")
        void dauntingRoar() {
            EncounterParticipant leonin = f.player("Roa", 30, 14);
            f.turnOf(leonin);
            leonin.getCharacter().setConstitution(14);
            orc.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("deafened", null, 1))));
            give(leonin, pool("race:daunting-roar", "Daunting Roar", "BONUS_ACTION", 1,
                    Map.of("effect", "daunting-roar")));
            f.dice.script(5);

            use(leonin, "race:daunting-roar", null, null, goblin, orc);

            ConditionEntry frightened = entry(goblin, "frightened");
            assertNotNull(frightened);
            assertEquals(EndRule.endOfTurn(leonin.getId()), frightened.endRule());
            assertNull(entry(orc, "frightened"));
            assertTrue(f.descriptions().stream().anyMatch(d -> d.contains("Orc is deafened")));
            assertTrue(lastPoolUse().contains("WIS save DC 12"), lastPoolUse());
        }

        @Test
        @DisplayName("Necrotic Shroud transforms the fallen aasimar for 1 minute and frightens the creatures picked "
                + "that fail a CHA save DC 8 + PB + CHA (VGM p.105)")
        void necroticShroud() {
            EncounterParticipant fallen = f.player("Vesper", 30, 14);
            f.turnOf(fallen);
            level(fallen, 5, 3);
            fallen.getCharacter().setCharisma(16);
            give(fallen, pool("race:necrotic-shroud", "Necrotic Shroud", "ACTION", 1,
                    Map.of("effect", "necrotic-shroud")));
            f.dice.script(13, 14);

            use(fallen, "race:necrotic-shroud", null, null, goblin, orc);

            assertEquals(10, entry(fallen, RaceFeatureEffects.NECROTIC_SHROUD_ENTRY).duration());
            assertNotNull(entry(goblin, "frightened"), "13 is below DC 14");
            assertNull(entry(orc, "frightened"), "14 meets DC 14");
            assertTrue(lastPoolUse().contains("5 extra necrotic damage"), lastPoolUse());
        }

        @Test
        @DisplayName("Radiant Soul transforms the protector aasimar for 1 minute with no target")
        void radiantSoul() {
            EncounterParticipant protector = f.player("Sol", 30, 14);
            f.turnOf(protector);
            level(protector, 3, 2);
            give(protector, pool("race:radiant-soul", "Radiant Soul", "ACTION", 1, Map.of("effect", "radiant-soul")));

            use(protector, "race:radiant-soul", null, null);

            assertEquals(10, entry(protector, RaceFeatureEffects.RADIANT_SOUL_ENTRY).duration());
            assertTrue(lastPoolUse().contains("flying speed of 30 feet"), lastPoolUse());
            assertThrows(ConflictException.class, () -> use(protector, "race:radiant-soul", null, null));
        }
    }

    @Nested
    @DisplayName("Notes")
    class Notes {

        @Test
        @DisplayName("Fury of the Small logs its extra damage with the goblin's level filled in, for the DM to apply")
        void furyOfTheSmall() {
            EncounterParticipant nib = f.player("Nib", 20, 14);
            level(nib, 4, 2);
            give(nib, pool("race:fury-of-the-small", "Fury of the Small", "FREE", 1, Map.of("effect", "racial-note",
                    "note", "deals extra damage equal to its level ({level}) to a larger creature")));

            use(nib, "race:fury-of-the-small", null, null);

            assertTrue(lastPoolUse().contains("extra damage equal to its level (4)"), lastPoolUse());
            assertEquals(0, usesLeft(nib, "race:fury-of-the-small"));
        }

        @Test
        @DisplayName("Feline Agility is regained by hand after a turn without moving, and not when it is full or for "
                + "a pool that only rests restore")
        void felineAgility() {
            EncounterParticipant tabaxi = f.player("Whisker", 20, 14);
            give(tabaxi, pool("race:feline-agility", "Feline Agility", "FREE", 1, Map.of("effect", "racial-note",
                    "note", "doubles its speed until the end of the turn", "regain", true)),
                    pool("race:saving-face", "Saving Face", "FREE", 1, Map.of("effect", "racial-note")));

            assertThrows(ConflictException.class, () -> use(tabaxi, "race:feline-agility", "regain", null));
            use(tabaxi, "race:feline-agility", null, null);
            assertEquals(0, usesLeft(tabaxi, "race:feline-agility"));

            use(tabaxi, "race:feline-agility", "regain", null);

            assertEquals(1, usesLeft(tabaxi, "race:feline-agility"));
            assertTrue(lastPoolUse().startsWith("Whisker regains Feline Agility"), lastPoolUse());
            assertThrows(ValidationException.class, () -> use(tabaxi, "race:saving-face", "regain", null));
        }
    }
}
