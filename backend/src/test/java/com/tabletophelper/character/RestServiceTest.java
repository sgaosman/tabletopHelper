package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.ShortRestRequest;
import com.tabletophelper.character.dto.ShortRestRequest.HitDieSpent;
import com.tabletophelper.character.dto.ShortRestRequest.SongOfRest;
import com.tabletophelper.character.dto.ShortRestResponse;
import com.tabletophelper.character.dto.ShortRestResponse.HitDieRoll;
import com.tabletophelper.character.dto.ShortRestResponse.SongOfRestRoll;
import com.tabletophelper.character.dto.SongOfRestResponse;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.FeatEffects;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Short and long rests on the server (D158). They took over CharacterServiceTest's rest cases 15.12 to 15.20, which
 * keep their numbers here:
 * <ul>
 *   <li>15.12 and 15.13 spend Hit Dice with the rolls the player entered, or the server's dice, instead of the fixed
 *       average the old code healed.</li>
 *   <li>15.14, more dice than remain, is now a 400, as a roll outside the die is.</li>
 *   <li>15.15 is unchanged: only the pact slots come back on a short rest.</li>
 *   <li>15.16 and 15.20 read the pools that replaced feat_resources: Lucky's luck points are the feat:lucky pool.</li>
 *   <li>15.17 to 15.19 add what the old long rest didn't do: temporary hit points go, and a multiclass character's
 *       largest Hit Dice come back first.</li>
 * </ul>
 * A short rest answers with each die's roll, who rolled it and what it healed beside the character. The characters are
 * created through the service, so their hit points, Hit Dice, slots and pools are the server's.
 */
class RestServiceTest {

    private final CharacterFixture f = new CharacterFixture();
    private final UUID otherUserId = UUID.randomUUID();

    // STR 16, DEX 14, CON 14, INT 16, WIS 10, CHA 16
    private static final int[] CON_14 = {16, 14, 14, 16, 10, 16};

    private static ShortRestRequest dice(HitDieSpent... dice) {
        return new ShortRestRequest(Arrays.asList(dice));
    }

    private static HitDieSpent die(String className, Integer roll) {
        return new HitDieSpent(className, roll);
    }

    private PlayerCharacter hurt(PlayerCharacter pc, int hp) {
        pc.setHpCurrent(hp);
        return pc;
    }

    private static void remaining(PlayerCharacter pc, String className, int remaining) {
        Map<String, HitDicePool> hitDice = new LinkedHashMap<>(pc.getHitDice());
        HitDicePool pool = hitDice.get(className);
        hitDice.put(className, new HitDicePool(pool.dieSize(), pool.total(), remaining));
        pc.setHitDice(hitDice);
    }

    // Every slot spent
    private static void spendEverySlot(PlayerCharacter pc) {
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        pc.getSpellSlots().forEach((key, slot) -> slots.put(key, new SpellSlot(slot.max(), slot.max())));
        pc.setSpellSlots(slots);
    }

    private static void uses(PlayerCharacter pc, String poolId, int uses) {
        pc.setResourcePools(pc.getResourcePools().stream()
                .map(pool -> pool.poolId().equals(poolId) ? pool.withCurrentUses(uses) : pool)
                .toList());
    }

    /** A Fighter 4 who took Lucky with the level's Ability Score Improvement, so it has the feat:lucky pool. */
    private PlayerCharacter luckyFighter() {
        PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 4);
        Feat lucky = Feat.builder().id(UUID.randomUUID()).name("Lucky").description("Luck points")
                .effects(FeatEffects.parse("{\"resource\": {\"name\": \"Luck Points\", \"maxUses\": 3, \"resetOn\": \"longRest\"}}"))
                .build();
        when(f.featRepository.findById(lucky.getId())).thenReturn(Optional.of(lucky));
        f.takeFeat(pc, lucky.getId());
        return f.stored(pc.getId());
    }

    @Nested
    @DisplayName("Short Rest")
    class ShortRest {

        @Test
        @DisplayName("15.12 A Hit Die heals the roll the player entered plus the CON modifier, and is spent")
        void enteredRollHeals() {
            // Fighter 5 with CON 14: 12 + 4 × 8 = 44 hit points
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 20);
            remaining(pc, "Fighter", 3);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), dice(die("Fighter", 7)), f.userId);

            CharacterResponse response = rested.character();
            assertThat(response.getHpMax()).isEqualTo(44);
            assertThat(response.getHpCurrent()).isEqualTo(29);
            assertThat(response.getHitDice().get("Fighter")).isEqualTo(new HitDicePool(10, 5, 2));
            assertThat(rested.rolls()).containsExactly(new HitDieRoll("Fighter", 10, 7, false, 9));
        }

        @Test
        @DisplayName("A Hit Die without a roll is rolled by the server, and the answer says what it rolled")
        void serverRollsADieWithoutARoll() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 20);
            f.rolls.add(4);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), dice(die("Fighter", null)), f.userId);

            assertThat(rested.character().getHpCurrent()).isEqualTo(26);
            assertThat(rested.rolls()).containsExactly(new HitDieRoll("Fighter", 10, 4, true, 6));
            assertThat(f.rolls).isEmpty();
        }

        @Test
        @DisplayName("Each die comes back in the order named, with its roll, who rolled it and what it healed")
        void rollsComeBackInTheOrderNamed() {
            // Fighter 3 / Wizard 2 with CON 12: 35 hit points
            PlayerCharacter pc = hurt(f.create(f.human, CharacterFixture.scores(), f.fighter, 3, f.wizard, 2), 15);
            f.rolls.add(5);
            f.rolls.add(2);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(),
                    dice(die("Wizard", null), die("Fighter", 4), die("Wizard", null)), f.userId);

            assertThat(rested.rolls()).containsExactly(
                    new HitDieRoll("Wizard", 6, 5, true, 6),
                    new HitDieRoll("Fighter", 10, 4, false, 5),
                    new HitDieRoll("Wizard", 6, 2, true, 3));
            // 15 + 6 + 5 + 3
            assertThat(rested.character().getHpCurrent()).isEqualTo(29);
        }

        @Test
        @DisplayName("15.13 A multiclass character spends each class's own Hit Dice, each of its own size")
        void multiclassSpendsEachClassesDice() {
            // Fighter 3 / Wizard 2 with CON 12: 11 + 2 × 7 + 2 × 5 = 35 hit points
            PlayerCharacter pc = hurt(f.create(f.human, CharacterFixture.scores(), f.fighter, 3, f.wizard, 2), 15);
            f.rolls.add(3);

            CharacterResponse response = f.rests.shortRest(pc.getId(),
                    dice(die("Fighter", 6), die("Wizard", null)), f.userId).character();

            // (6 + 1) + (3 + 1)
            assertThat(response.getHpCurrent()).isEqualTo(26);
            assertThat(response.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 3, 2))
                    .containsEntry("Wizard", new HitDicePool(6, 2, 1));
        }

        @Test
        @DisplayName("Healing stops at the hit point maximum, though the die still says what it heals")
        void healingStopsAtTheMaximum() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 42);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), dice(die("Fighter", 10)), f.userId);

            assertThat(rested.character().getHpCurrent()).isEqualTo(44);
            assertThat(rested.character().getHitDice().get("Fighter").remaining()).isEqualTo(4);
            assertThat(rested.rolls()).containsExactly(new HitDieRoll("Fighter", 10, 10, false, 12));
        }

        @Test
        @DisplayName("A Hit Die never heals less than 0, however low the CON modifier, and is still spent")
        void aDieNeverHealsLessThanZero() {
            PlayerCharacter pc = hurt(f.create(f.human, new int[]{16, 14, 6, 10, 10, 10}, f.fighter, 2), 3);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), dice(die("Fighter", 1)), f.userId);

            assertThat(rested.character().getHpCurrent()).isEqualTo(3);
            assertThat(rested.character().getHitDice().get("Fighter").remaining()).isEqualTo(1);
            assertThat(rested.rolls()).containsExactly(new HitDieRoll("Fighter", 10, 1, false, 0));
        }

        @Test
        @DisplayName("15.14 Spending more Hit Dice than remain is a 400 and changes nothing")
        void moreDiceThanRemainIs400() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 3), 10);
            remaining(pc, "Fighter", 1);

            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(),
                    dice(die("Fighter", 5), die("Fighter", 5)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria has 1 Fighter Hit Die left, not 2");
            assertThat(pc.getHpCurrent()).isEqualTo(10);
            assertThat(pc.getHitDice().get("Fighter").remaining()).isEqualTo(1);
        }

        @Test
        @DisplayName("A roll outside 1 to the die's size is a 400, and no die is rolled for the others")
        void impossibleRollIs400() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 3), 10);

            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(),
                    dice(die("Fighter", null), die("Fighter", 11)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A d10 rolls 1 to 10, not 11");
            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), dice(die("Fighter", 0)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A d10 rolls 1 to 10, not 0");
            assertThat(pc.getHpCurrent()).isEqualTo(10);
            assertThat(pc.getHitDice().get("Fighter").remaining()).isEqualTo(3);
        }

        @Test
        @DisplayName("Hit Dice of a class the character doesn't have are a 400")
        void unknownClassIs400() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 3);

            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), dice(die("Wizard", 3)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria has no Wizard Hit Dice");
        }

        @Test
        @DisplayName("Song of Rest heals its die more when Hit Dice healed, the player's roll or the server's (D223)")
        void songOfRestHealsItsDieMore() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 20);
            f.rolls.add(5);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), new ShortRestRequest(
                    List.of(die("Fighter", 7)), null, null, new SongOfRest(8, null)), f.userId);

            // 20 + (7 + 2) + 5: Song of Rest's die adds no modifier
            assertThat(rested.character().getHpCurrent()).isEqualTo(34);
            assertThat(rested.songOfRest()).isEqualTo(new SongOfRestRoll(8, 5, true, 5));
            assertThat(f.rolls).isEmpty();

            ShortRestResponse again = f.rests.shortRest(pc.getId(), new ShortRestRequest(
                    List.of(die("Fighter", 1)), null, null, new SongOfRest(6, 4)), f.userId);
            assertThat(again.character().getHpCurrent()).isEqualTo(34 + 3 + 4);
            assertThat(again.songOfRest()).isEqualTo(new SongOfRestRoll(6, 4, false, 4));
        }

        @Test
        @DisplayName("Song of Rest gives nothing without Hit Dice spent, or when the dice regained no hit points (D223)")
        void songOfRestNeedsHitDiceThatHealed() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 20);

            ShortRestResponse noDice = f.rests.shortRest(pc.getId(),
                    new ShortRestRequest(List.of(), null, null, new SongOfRest(6, 6)), f.userId);
            assertThat(noDice.songOfRest()).isNull();
            assertThat(noDice.character().getHpCurrent()).isEqualTo(20);

            hurt(pc, 44);
            ShortRestResponse atMaximum = f.rests.shortRest(pc.getId(),
                    new ShortRestRequest(List.of(die("Fighter", 3)), null, null, new SongOfRest(6, 6)), f.userId);
            assertThat(atMaximum.songOfRest()).isNull();
            assertThat(atMaximum.character().getHpCurrent()).isEqualTo(44);
        }

        @Test
        @DisplayName("Song of Rest stops at the hit point maximum")
        void songOfRestStopsAtTheMaximum() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 38);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), new ShortRestRequest(
                    List.of(die("Fighter", 2)), null, null, new SongOfRest(12, 12)), f.userId);

            assertThat(rested.character().getHpCurrent()).isEqualTo(44);
            assertThat(rested.songOfRest()).isEqualTo(new SongOfRestRoll(12, 12, false, 12));
        }

        @Test
        @DisplayName("A Song of Rest die other than d6 to d12, or a roll outside it, is a 400 and changes nothing")
        void songOfRestRefusals() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 20);

            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), new ShortRestRequest(
                    List.of(die("Fighter", 5)), null, null, new SongOfRest(4, null)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Song of Rest heals with a d6, d8, d10 or d12, not a d4");
            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), new ShortRestRequest(
                    List.of(die("Fighter", 5)), null, null, new SongOfRest(6, 7)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A d6 rolls 1 to 6, not 7");
            assertThat(pc.getHpCurrent()).isEqualTo(20);
            assertThat(pc.getHitDice().get("Fighter").remaining()).isEqualTo(5);
        }

        @Test
        @DisplayName("15.15 A warlock's pact slots come back on a short rest, and its other slots don't")
        void pactSlotsComeBack() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.warlock, 3, f.wizard, 2);
            spendEverySlot(pc);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), null, f.userId);

            assertThat(rested.character().getSpellSlots()).containsEntry("pact_2", new SpellSlot(2, 0))
                    .containsEntry("1", new SpellSlot(3, 3));
            assertThat(rested.rolls()).isEmpty();
        }

        @Test
        @DisplayName("Arcane Recovery brings back expended slots adding up to half the wizard level, rounded up "
                + "(PHB p.115)")
        void arcaneRecovery() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.wizard, 5);
            spendEverySlot(pc);

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), new ShortRestRequest(List.of(), List.of(2, 1)),
                    f.userId);

            assertThat(rested.character().getSpellSlots()).containsEntry("1", new SpellSlot(4, 3))
                    .containsEntry("2", new SpellSlot(3, 2)).containsEntry("3", new SpellSlot(2, 2));
        }

        @Test
        @DisplayName("Arcane Recovery refuses too many levels, a 6th-level slot, a slot not expended and a non-wizard, "
                + "changing nothing")
        void arcaneRecoveryRefusals() {
            PlayerCharacter wizard = hurt(f.create(f.human, CON_14, f.wizard, 5), 10);
            assertThatThrownBy(() -> f.rests.shortRest(wizard.getId(),
                    new ShortRestRequest(List.of(die("Wizard", 4)), List.of(1)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Only 0 level 1 spell slots are expended, not 1");
            assertThat(f.stored(wizard.getId()).getHpCurrent()).isEqualTo(10);
            spendEverySlot(wizard);
            assertThatThrownBy(() -> f.rests.shortRest(wizard.getId(), new ShortRestRequest(List.of(), List.of(3, 1)),
                    f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Arcane Recovery recovers slots whose levels add up to 3 at most (half of wizard "
                            + "level 5, rounded up), not 4");
            assertThatThrownBy(() -> f.rests.shortRest(wizard.getId(), new ShortRestRequest(List.of(), List.of(6)),
                    f.userId))
                    .isInstanceOf(ValidationException.class);

            PlayerCharacter fighter = f.create(f.human, CON_14, f.fighter, 5);
            assertThatThrownBy(() -> f.rests.shortRest(fighter.getId(), new ShortRestRequest(List.of(), List.of(1)),
                    f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Arcane Recovery is a wizard's feature");
        }

        @Test
        @DisplayName("15.16 The pools that reset on a short rest come back, and a feat's long-rest uses don't")
        void shortRestPoolsComeBack() {
            PlayerCharacter pc = luckyFighter();
            uses(pc, "class:fighter-action-surge", 0);
            uses(pc, "feat:lucky", 1);

            CharacterResponse response = f.rests.shortRest(pc.getId(), dice(), f.userId).character();

            assertThat(response.getResourcePools())
                    .extracting(ResourcePoolEntry::poolId, ResourcePoolEntry::currentUses, ResourcePoolEntry::maxUses)
                    .containsExactly(tuple("class:fighter-action-surge", 1, 1), tuple("feat:lucky", 1, 3));
        }

        @Test
        @DisplayName("A short rest is refused while the character is in an Active or Paused encounter (D166)")
        void refusedDuringAnEncounter() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 3), 10);
            when(f.liveEncounters.includes(pc.getId())).thenReturn(true);

            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), dice(die("Fighter", 5)), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria is in an encounter that is under way, so can't take a short rest until it ends");
            assertThat(pc.getHpCurrent()).isEqualTo(10);
        }

        @Test
        @DisplayName("Only the character's owner can rest it, and a stranger never locks its row")
        void onlyTheOwnerRests() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 3);

            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), dice(), otherUserId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("You do not own this character");
            verify(f.characterRepository, never()).lockById(any());
        }
    }

    @Nested
    @DisplayName("Natural Recovery (PHB p.68, D222)")
    class NaturalRecovery {

        private final CharacterClass druid = CharacterClass.builder().id(UUID.randomUUID()).name("Druid").hitDice(8)
                .subclassLevel(2).isSpellcaster(true).isPactMagic(false).spellcastingAbility("WIS")
                .multiclassRequirements("[{\"ability\":\"WIS\",\"minimum\":13}]")
                .build();
        private final Subclass land = Subclass.builder().id(UUID.randomUUID()).name("Circle of the Land")
                .characterClass(druid).features("[]").build();
        private final ResourcePoolDefinition naturalRecovery = ResourcePoolDefinition.builder()
                .poolId(RestService.NATURAL_RECOVERY).displayName("Natural Recovery").sourceType("CLASS")
                .maxUsesFormula("1").resetOn("longRest").build();

        // STR 10, DEX 14, CON 14, INT 14, WIS 16, CHA 10
        private static final int[] CASTER = {10, 14, 14, 14, 16, 10};

        /** A druid of these levels, with a wizard's after when given, in the Circle of the Land. */
        private PlayerCharacter landDruid(int druidLevel, int wizardLevel) {
            when(f.classRepository.findById(druid.getId())).thenReturn(Optional.of(druid));
            when(f.subclassRepository.findById(land.getId())).thenReturn(Optional.of(land));
            when(f.featurePoolRepository.findByClassIdAndLevelAndSubclass(eq(druid.getId()), anyInt(),
                    eq(land.getId()))).thenReturn(List.of(ClassFeaturePool.builder().characterClass(druid)
                    .subclass(land).minLevel(2).poolDefinition(naturalRecovery).build()));
            PlayerCharacter pc = wizardLevel > 0 ? f.create(f.human, CASTER, druid, druidLevel, f.wizard, wizardLevel)
                    : f.create(f.human, CASTER, druid, druidLevel);
            pc.setMulticlassEntries(pc.getMulticlassEntries().stream()
                    .map(entry -> druid.getId().equals(entry.classId())
                            ? new ClassEntry(entry.classId(), entry.className(), land.getId(), land.getName(),
                            entry.level())
                            : entry)
                    .toList());
            return pc;
        }

        private static ShortRestRequest natural(Integer... levels) {
            return new ShortRestRequest(List.of(), null, Arrays.asList(levels));
        }

        @Test
        @DisplayName("Natural Recovery brings back expended slots adding up to half the druid level, rounded up, and "
                + "spends its use until a long rest")
        void recoversSlots() {
            PlayerCharacter pc = landDruid(5, 0);
            spendEverySlot(pc);

            CharacterResponse response = f.rests.shortRest(pc.getId(), natural(2, 1), f.userId).character();

            assertThat(response.getSpellSlots()).containsEntry("1", new SpellSlot(4, 3))
                    .containsEntry("2", new SpellSlot(3, 2)).containsEntry("3", new SpellSlot(2, 2));
            assertThat(response.getResourcePools()).filteredOn(p -> p.poolId().equals(RestService.NATURAL_RECOVERY))
                    .extracting(ResourcePoolEntry::currentUses).containsExactly(0);
            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), natural(1), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria has used Natural Recovery since their last long rest");

            f.rests.longRest(pc.getId(), f.userId);
            spendEverySlot(pc);
            assertThat(f.rests.shortRest(pc.getId(), natural(3), f.userId).character().getSpellSlots())
                    .containsEntry("3", new SpellSlot(2, 1));
        }

        @Test
        @DisplayName("Natural Recovery refuses too many levels, a 6th-level slot and a character without the Circle "
                + "of the Land, changing nothing")
        void refusals() {
            PlayerCharacter pc = landDruid(5, 0);
            spendEverySlot(pc);
            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), natural(3, 1), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Natural Recovery recovers slots whose levels add up to 3 at most (half of druid "
                            + "level 5, rounded up), not 4");
            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), natural(6), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Natural Recovery recovers slots of 1st to 5th level, not 6");
            assertThat(f.stored(pc.getId()).getSpellSlots().get("1")).isEqualTo(new SpellSlot(4, 4));

            PlayerCharacter wizard = f.create(f.human, CON_14, f.wizard, 5);
            spendEverySlot(wizard);
            assertThatThrownBy(() -> f.rests.shortRest(wizard.getId(), natural(1), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Natural Recovery is a Circle of the Land druid's feature");
        }

        @Test
        @DisplayName("A wizard and Circle of the Land druid can use both on one rest, but never recover one slot twice")
        void bothOnOneRest() {
            PlayerCharacter pc = landDruid(3, 2);
            Map<String, SpellSlot> slots = new LinkedHashMap<>(pc.getSpellSlots());
            slots.put("1", new SpellSlot(4, 1));
            slots.put("2", new SpellSlot(3, 2));
            pc.setSpellSlots(slots);

            assertThatThrownBy(() -> f.rests.shortRest(pc.getId(), new ShortRestRequest(List.of(), List.of(1),
                    List.of(1)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Only 0 level 1 spell slots are expended, not 1");

            CharacterResponse response = f.rests.shortRest(pc.getId(), new ShortRestRequest(List.of(), List.of(1),
                    List.of(2)), f.userId).character();
            assertThat(response.getSpellSlots()).containsEntry("1", new SpellSlot(4, 0))
                    .containsEntry("2", new SpellSlot(3, 1));
        }
    }

    @Nested
    @DisplayName("Long Rest")
    class LongRest {

        @Test
        @DisplayName("15.17 A long rest restores every hit point and clears temporary hit points")
        void restoresHitPoints() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 20);
            pc.setHpTemp(5);

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            assertThat(response.getHpCurrent()).isEqualTo(44);
            assertThat(response.getHpMax()).isEqualTo(44);
            assertThat(response.getHpTemp()).isZero();
        }

        @Test
        @DisplayName("A long rest lowers exhaustion by one, taking food and drink as had, and fills the hit points to "
                + "the maximum the new level leaves (PHB p.186, p.291)")
        void lowersExhaustion() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 10);
            pc.setExhaustionLevel(4);

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            assertThat(response.getExhaustionLevel()).isEqualTo(3);
            assertThat(response.getHpCurrent()).isEqualTo(44);
            assertThat(response.getHpMaxEffective()).isEqualTo(44);

            PlayerCharacter worn = hurt(f.create(f.human, CON_14, f.fighter, 5), 10);
            worn.setExhaustionLevel(5);
            CharacterResponse still = f.rests.longRest(worn.getId(), f.userId);
            assertThat(still.getExhaustionLevel()).isEqualTo(4);
            assertThat(still.getHpCurrent()).isEqualTo(22);
            assertThat(still.getHpMaxEffective()).isEqualTo(22);
            assertThat(still.getHpMax()).isEqualTo(44);
        }

        @Test
        @DisplayName("15.18 A long rest restores every spell slot, pact slots included")
        void restoresEverySlot() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.warlock, 3, f.wizard, 2);
            spendEverySlot(pc);

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            assertThat(response.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(3, 0), "pact_2", new SpellSlot(2, 0)));
        }

        @Test
        @DisplayName("A long rest ends the slots Flexible Casting created, and a short rest keeps them (PHB p.101)")
        void createdSlotsVanish() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.sorcerer, 3);
            pc.setSpellSlots(new LinkedHashMap<>(Map.of("1", new SpellSlot(4, 1, 1),
                    "2", new SpellSlot(2, 2), "3", new SpellSlot(0, 0, 1))));

            ShortRestResponse rested = f.rests.shortRest(pc.getId(), new ShortRestRequest(List.of()), f.userId);
            assertThat(rested.character().getSpellSlots()).containsEntry("1", new SpellSlot(4, 1, 1))
                    .containsEntry("3", new SpellSlot(0, 0, 1));

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            assertThat(response.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(4, 0), "2", new SpellSlot(2, 0)));
        }

        @Test
        @DisplayName("15.19 A long rest brings back half the character's Hit Dice, rounded down, and at least one")
        void bringsBackHalfTheHitDice() {
            PlayerCharacter seven = f.create(f.human, CON_14, f.fighter, 7);
            remaining(seven, "Fighter", 0);
            PlayerCharacter one = f.create(f.human, CON_14, f.fighter, 1);
            remaining(one, "Fighter", 0);

            assertThat(f.rests.longRest(seven.getId(), f.userId).getHitDice().get("Fighter"))
                    .isEqualTo(new HitDicePool(10, 7, 3));
            assertThat(f.rests.longRest(one.getId(), f.userId).getHitDice().get("Fighter"))
                    .isEqualTo(new HitDicePool(10, 1, 1));
        }

        @Test
        @DisplayName("A multiclass character's largest Hit Dice come back first, from half its total (D158)")
        void largestHitDiceFirst() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.wizard, 2, f.fighter, 3);
            remaining(pc, "Fighter", 0);
            remaining(pc, "Wizard", 0);

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            // Five Hit Dice bring back two, both d10s, though the wizard's were taken first
            assertThat(response.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 3, 2))
                    .containsEntry("Wizard", new HitDicePool(6, 2, 0));
        }

        @Test
        @DisplayName("15.20 A long rest restores every pool, a feat's and a spell's innate uses included")
        void restoresEveryPool() {
            PlayerCharacter pc = luckyFighter();
            pc.setSpellsKnown(List.of(new KnownSpell(null, "Faerie Fire", 1, "race:Fairy", null, null, null, 1, null)));
            f.characterStats.apply(pc);
            uses(pc, "class:fighter-action-surge", 0);
            uses(pc, "feat:lucky", 0);
            uses(pc, "innate:faerie-fire", 0);

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            assertThat(response.getResourcePools())
                    .extracting(ResourcePoolEntry::poolId, ResourcePoolEntry::currentUses)
                    .containsExactly(tuple("class:fighter-action-surge", 1), tuple("feat:lucky", 3),
                            tuple("innate:faerie-fire", 1));
        }

        @Test
        @DisplayName("A long rest sets a War Wizard's Power Surge to one, from none or from all three, and a short rest leaves it (XGE p.60, D230)")
        void powerSurgeResetsToOne() {
            String poolId = "subclass:war-magic-power-surge";
            Subclass warMagic = Subclass.builder().id(UUID.randomUUID()).name("School of War Magic")
                    .characterClass(f.wizard).features("[]").build();
            ResourcePoolDefinition surge = ResourcePoolDefinition.builder().poolId(poolId).displayName("Power Surge")
                    .sourceType("CLASS").maxUsesFormula("max(1, intelligenceModifier)").resetOn("longRest")
                    .resetAmount("1").build();
            when(f.subclassRepository.findById(warMagic.getId())).thenReturn(Optional.of(warMagic));
            when(f.featurePoolRepository.findByClassIdAndLevelAndSubclass(eq(f.wizard.getId()), anyInt(),
                    eq(warMagic.getId()))).thenReturn(List.of(ClassFeaturePool.builder().characterClass(f.wizard)
                    .subclass(warMagic).minLevel(2).poolDefinition(surge).build()));
            PlayerCharacter pc = f.create(f.human, CON_14, f.wizard, 6);
            pc.setMulticlassEntries(List.of(new ClassEntry(f.wizard.getId(), "Wizard", warMagic.getId(),
                    warMagic.getName(), 6)));
            f.characterStats.apply(pc);
            // INT 16: up to three surges
            assertThat(pc.getResourcePools()).filteredOn(p -> p.poolId().equals(poolId))
                    .extracting(ResourcePoolEntry::maxUses).containsExactly(3);

            uses(pc, poolId, 0);
            CharacterResponse shortRested = f.rests.shortRest(pc.getId(), new ShortRestRequest(List.of()), f.userId)
                    .character();
            assertThat(shortRested.getResourcePools()).filteredOn(p -> p.poolId().equals(poolId))
                    .extracting(ResourcePoolEntry::currentUses).containsExactly(0);

            assertThat(f.rests.longRest(pc.getId(), f.userId).getResourcePools())
                    .filteredOn(p -> p.poolId().equals(poolId))
                    .extracting(ResourcePoolEntry::currentUses).containsExactly(1);

            uses(pc, poolId, 3);
            assertThat(f.rests.longRest(pc.getId(), f.userId).getResourcePools())
                    .filteredOn(p -> p.poolId().equals(poolId))
                    .extracting(ResourcePoolEntry::currentUses).containsExactly(1);
        }

        @Test
        @DisplayName("A long rest between a level-down and the level-up restores only the Hit Dice it would at the full "
                + "level: a Fighter 5 with all five spent, levelled to 1, rested and levelled back, has three spent (D234)")
        void longRestAfterLevelDownRestoresHalfTheFullLevelsHitDice() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 5);
            pc.setHitDice(Map.of("Fighter", new HitDicePool(10, 5, 0)));
            for (int i = 0; i < 4; i++) {
                f.levelDown(pc);
            }
            assertThat(pc.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 1, 0));

            f.rests.longRest(pc.getId(), f.userId);
            assertThat(pc.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 1, 1));
            for (int i = 0; i < 4; i++) {
                f.levelUp(pc, f.fighter);
            }

            // A Fighter 5 resting with five spent gets two back (PHB p.186), so three stay spent
            assertThat(pc.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 5, 2));
            assertThat(pc.getLevelDownUses()).isEmpty();
        }

        @Test
        @DisplayName("A multiclass character's long rest at the lower level restores the full level's largest dice first (D234)")
        void longRestAfterLevelDownRestoresTheLargestDiceFirst() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 2, f.wizard, 2);
            pc.setHitDice(Map.of("Fighter", new HitDicePool(10, 2, 0), "Wizard", new HitDicePool(6, 2, 0)));
            // Down to Fighter 2: the two wizard dice go spent with their levels
            f.levelDown(pc);
            f.levelDown(pc);

            // Four dice bring back two at the full level, both d10s, though a rest at level 2 brings back one
            f.rests.longRest(pc.getId(), f.userId);
            assertThat(pc.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 2, 2));
            f.levelUp(pc, f.wizard);
            f.levelUp(pc, f.wizard);

            assertThat(pc.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 2, 2))
                    .containsEntry("Wizard", new HitDicePool(6, 2, 0));
        }

        @Test
        @DisplayName("A War Wizard's Power Surge removed with its level and gained back after a long rest has the one "
                + "use the rest resets it to, spent or not before (XGE p.60, D230, D234)")
        void powerSurgeAfterLevelDownAndLongRest() {
            String poolId = "subclass:war-magic-power-surge";
            Subclass warMagic = Subclass.builder().id(UUID.randomUUID()).name("School of War Magic")
                    .characterClass(f.wizard).features("[]").build();
            ResourcePoolDefinition surge = ResourcePoolDefinition.builder().poolId(poolId).displayName("Power Surge")
                    .sourceType("CLASS").maxUsesFormula("max(1, intelligenceModifier)").resetOn("longRest")
                    .resetAmount("1").build();
            ClassFeaturePool surgePool = ClassFeaturePool.builder().characterClass(f.wizard).subclass(warMagic)
                    .minLevel(2).poolDefinition(surge).build();
            when(f.subclassRepository.findById(warMagic.getId())).thenReturn(Optional.of(warMagic));
            when(f.featurePoolRepository.findByClassIdAndLevelAndSubclass(eq(f.wizard.getId()), anyInt(),
                    eq(warMagic.getId()))).thenAnswer(inv -> inv.<Integer>getArgument(1) >= 2
                    ? List.of(surgePool) : List.of());

            for (int spentBefore : new int[]{3, 0}) {
                PlayerCharacter pc = f.create(f.human, CON_14, f.wizard, 2);
                pc.setMulticlassEntries(List.of(new ClassEntry(f.wizard.getId(), "Wizard", warMagic.getId(),
                        warMagic.getName(), 2)));
                f.characterStats.apply(pc);
                uses(pc, poolId, 3 - spentBefore);

                f.levelDown(pc);
                assertThat(pc.getResourcePools()).noneMatch(p -> p.poolId().equals(poolId));
                f.rests.longRest(pc.getId(), f.userId);
                f.levelUp(pc, f.wizard);

                assertThat(pc.getResourcePools()).filteredOn(p -> p.poolId().equals(poolId))
                        .extracting(ResourcePoolEntry::maxUses, ResourcePoolEntry::currentUses)
                        .as("spent %d before", spentBefore)
                        .containsExactly(tuple(3, 1));
            }
        }

        @Test
        @DisplayName("A long rest between a level-down and the level-up still restores the slots and pools it fills (D234)")
        void longRestAfterLevelDownStillFillsSlotsAndPools() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 2, f.wizard, 1);
            uses(pc, "class:fighter-action-surge", 0);
            pc.setSpellSlots(Map.of("1", new SpellSlot(2, 2)));
            f.levelDown(pc);
            f.levelDown(pc);

            f.rests.longRest(pc.getId(), f.userId);
            assertThat(pc.getLevelDownUses()).isEmpty();
            f.levelUp(pc, f.fighter);
            f.levelUp(pc, f.wizard);

            assertThat(pc.getResourcePools()).filteredOn(p -> p.poolId().equals("class:fighter-action-surge"))
                    .extracting(ResourcePoolEntry::currentUses).containsExactly(1);
            assertThat(pc.getSpellSlots()).containsEntry("1", new SpellSlot(2, 0));
        }

        @Test
        @DisplayName("A long rest gives a magic item's pool its own dice of charges back, and leaves one that never recharges (D210)")
        void itemPoolsRechargeByTheirRule() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 5);
            Item wand = item("Wand of Web", "dawn", "3");
            Item ring = item("Ring of Three Wishes", null, null);
            when(f.itemRepository.findAllById(any())).thenReturn(List.of(wand, ring));
            pc.setEquipment(List.of(entry(wand, null), entry(ring, null)));
            f.characterStats.apply(pc);
            uses(pc, ResourcePoolEntry.itemPoolId(wand.getName()), 1);
            uses(pc, ResourcePoolEntry.itemPoolId(ring.getName()), 2);

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            assertThat(response.getResourcePools())
                    .extracting(ResourcePoolEntry::poolId, ResourcePoolEntry::currentUses)
                    .contains(tuple("item:wand-of-web", 4), tuple("item:ring-of-three-wishes", 2));
        }

        @Test
        @DisplayName("A long rest recharges the spent charges an unattuned item's entry records, as its pool would, and then the item attuned again has them (D210)")
        void unattunedItemRecharges() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 5);
            Item wand = item("Wand of Web", "dawn", "3");
            Item ring = item("Ring of Three Wishes", null, null);
            when(f.itemRepository.findAllById(any())).thenReturn(List.of(wand, ring));
            pc.setEquipment(List.of(entry(wand, 6), entry(ring, 2)));
            f.characterStats.apply(pc);
            assertThat(pc.getResourcePools()).noneMatch(pool -> pool.poolId().startsWith("item:"));

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            assertThat(response.getEquipment()).extracting(InventoryItem::chargesSpent).containsExactly(3, 2);
        }

        private static Item item(String name, String recharge, String rechargeAmount) {
            return Item.builder().id(UUID.randomUUID()).name(name).source("DMG").requiresAttunement(true).charges(7)
                    .recharge(recharge).rechargeAmount(rechargeAmount).build();
        }

        // An entry for the item, attuned when it records no spent charges, else unattuned with them
        private static InventoryItem entry(Item item, Integer chargesSpent) {
            return new InventoryItem(item.getName(), 1, null, item.getId(), null, chargesSpent == null, null,
                    chargesSpent);
        }

        @Test
        @DisplayName("A long rest needs at least 1 hit point (PHB p.186), and changes nothing without it")
        void needsAHitPoint() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 0);
            remaining(pc, "Fighter", 0);

            assertThatThrownBy(() -> f.rests.longRest(pc.getId(), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria needs at least 1 hit point to benefit from a long rest");
            assertThat(pc.getHpCurrent()).isZero();
            assertThat(pc.getHitDice().get("Fighter").remaining()).isZero();
        }

        @Test
        @DisplayName("A long rest is refused while the character is in an Active or Paused encounter (D166)")
        void refusedDuringAnEncounter() {
            PlayerCharacter pc = hurt(f.create(f.human, CON_14, f.fighter, 5), 20);
            when(f.liveEncounters.includes(pc.getId())).thenReturn(true);

            assertThatThrownBy(() -> f.rests.longRest(pc.getId(), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria is in an encounter that is under way, so can't take a long rest until it ends");
            assertThat(pc.getHpCurrent()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("The Song of Rest offered (D223)")
    class SongOfRestOffered {

        private final Campaign campaign = Campaign.builder().id(UUID.randomUUID()).name("Lost Mine")
                .dm(f.user).inviteCode("ABCD2345").build();

        // A character with these Bard levels, in the campaign
        private PlayerCharacter bard(String name, int bardLevel) {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 1);
            pc.setName(name);
            pc.setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Bard", null, null, bardLevel)));
            pc.setCampaign(campaign);
            return pc;
        }

        @Test
        @DisplayName("The campaign's living bard with the highest Bard level from 2nd offers its die")
        void bestLivingBardOffersItsDie() {
            PlayerCharacter resting = f.create(f.human, CON_14, f.fighter, 5);
            resting.setCampaign(campaign);
            PlayerCharacter apprentice = bard("Lark", 1);
            PlayerCharacter singer = bard("Wren", 9);
            PlayerCharacter fallen = bard("Finch", 17);
            fallen.setDead(true);
            when(f.characterRepository.findByCampaignIdAndIsActiveTrue(campaign.getId()))
                    .thenReturn(List.of(resting, apprentice, singer, fallen));

            assertThat(f.rests.songOfRestOffered(resting.getId(), f.userId))
                    .isEqualTo(new SongOfRestResponse(8, "Wren", 9));
        }

        @Test
        @DisplayName("A character in no campaign is offered its own Song of Rest, or none")
        void noCampaignOffersItsOwn() {
            PlayerCharacter fighter = f.create(f.human, CON_14, f.fighter, 5);
            assertThat(f.rests.songOfRestOffered(fighter.getId(), f.userId)).isEqualTo(SongOfRestResponse.NONE);

            PlayerCharacter soloBard = bard("Lark", 13);
            soloBard.setCampaign(null);
            assertThat(f.rests.songOfRestOffered(soloBard.getId(), f.userId))
                    .isEqualTo(new SongOfRestResponse(10, "Lark", 13));
        }

        @Test
        @DisplayName("Another user who isn't the campaign's DM gets a 403")
        void strangerIs403() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.fighter, 5);

            assertThatThrownBy(() -> f.rests.songOfRestOffered(pc.getId(), otherUserId))
                    .isInstanceOf(ForbiddenException.class);
        }
    }
}
