package com.tabletophelper.character;

import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.ShortRestRequest;
import com.tabletophelper.character.dto.ShortRestRequest.HitDieSpent;
import com.tabletophelper.character.dto.ShortRestResponse;
import com.tabletophelper.character.dto.ShortRestResponse.HitDieRoll;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatEffects;
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
        @DisplayName("15.18 A long rest restores every spell slot, pact slots included")
        void restoresEverySlot() {
            PlayerCharacter pc = f.create(f.human, CON_14, f.warlock, 3, f.wizard, 2);
            spendEverySlot(pc);

            CharacterResponse response = f.rests.longRest(pc.getId(), f.userId);

            assertThat(response.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(3, 0), "pact_2", new SpellSlot(2, 0)));
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
}
