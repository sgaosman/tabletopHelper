package com.tabletophelper.character;

import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A character's sheet with its DM Adjustments on top (D158, D161): only the active adjustments count, an ability score
 * stays within 1 to 30, the armour class is the one the character was created with plus the AC adjustments, and a
 * defence the sheet and an adjustment both give counts once.
 */
class DmAdjustmentsTest {

    private static final UUID DM = UUID.randomUUID();
    private static final Instant ADDED = Instant.parse("2026-11-03T10:00:00Z");

    private static PlayerCharacter character(DmAdjustment... adjustments) {
        return PlayerCharacter.builder().name("Aria")
                .strength(16).dexterity(14).constitution(14).intelligence(10).wisdom(12).charisma(8)
                .armourClass(12)
                .dmAdjustments(List.of(adjustments))
                .build();
    }

    private static DmAdjustment stat(String stat, int amount) {
        return new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.STAT, stat, amount, null, null, null,
                "A magic item", DM, ADDED, null, null);
    }

    private static DmAdjustment defence(Defence defence, String damageType) {
        return new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.DEFENCE, damageType, null, defence, null, null,
                "A ring", DM, ADDED, null, null);
    }

    private static DmAdjustment granted(String featName) {
        return new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.FEAT, featName, null, null, UUID.randomUUID(),
                effects(featName), "A boon", DM, ADDED, null, null);
    }

    private static AppliedFeatEffects effects(String featName) {
        return new AppliedFeatEffects(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, featName);
    }

    @Test
    @DisplayName("A character saved before V22, or built without adjustments, has none")
    void noAdjustments() {
        PlayerCharacter pc = PlayerCharacter.builder().strength(16).build();
        PlayerCharacter unset = character();
        unset.setDmAdjustments(null);

        assertThat(pc.getDmAdjustments()).isEmpty();
        assertThat(DmAdjustments.active(unset)).isEmpty();
        assertThat(DmAdjustments.abilityScore(unset, Ability.STR)).isEqualTo(16);
    }

    @Test
    @DisplayName("Only the active STAT adjustments to a stat add up, a removed one and another stat's left out")
    void totalCountsOnlyActiveAdjustmentsToTheStat() {
        PlayerCharacter pc = character(stat("ARMOUR_CLASS", 1), stat("armour_class", 2),
                stat("ARMOUR_CLASS", 5).removed(DM, ADDED), stat("SPEED", 10), defence(Defence.RESISTANCE, "fire"));

        assertThat(DmAdjustments.total(pc, DmAdjustment.Stat.ARMOUR_CLASS)).isEqualTo(3);
        assertThat(DmAdjustments.total(pc, DmAdjustment.Stat.INITIATIVE)).isZero();
        assertThat(DmAdjustments.active(pc)).hasSize(4);
    }

    @Test
    @DisplayName("An ability score adds its adjustments, past the maximum of 20 if they take it there, but stays within 1 to 30")
    void abilityScoresStayWithinOneToThirty() {
        PlayerCharacter pc = character(stat("STR", 6), stat("STR", 10), stat("CHA", -9), stat("DEX", 2));

        assertThat(DmAdjustments.abilityScore(pc, Ability.STR)).isEqualTo(30);
        assertThat(DmAdjustments.abilityScore(pc, Ability.CHA)).isEqualTo(1);
        assertThat(DmAdjustments.abilityScore(pc, Ability.DEX)).isEqualTo(16);
        assertThat(DmAdjustments.abilityScore(pc, Ability.WIS)).isEqualTo(12);
        // The sheet keeps the scores without them
        assertThat(pc.getStrength()).isEqualTo(16);
    }

    @Test
    @DisplayName("The armour class is the one the character was created with plus the AC adjustments")
    void armourClass() {
        assertThat(DmAdjustments.armourClass(character())).isEqualTo(12);
        assertThat(DmAdjustments.armourClass(character(stat("ARMOUR_CLASS", 1), stat("ARMOUR_CLASS", 2))))
                .isEqualTo(15);
        assertThat(DmAdjustments.armourClass(character(stat("ARMOUR_CLASS", -4)))).isEqualTo(8);
    }

    @Test
    @DisplayName("A defence the sheet and an adjustment both give counts once, in any case; vulnerabilities come only from adjustments")
    void defencesCountOnce() {
        PlayerCharacter pc = character(defence(Defence.RESISTANCE, "fire"), defence(Defence.VULNERABILITY, "cold"),
                defence(Defence.IMMUNITY, "poison").removed(DM, ADDED));
        pc.setDamageResistances(List.of("Fire", "acid"));
        pc.setDamageImmunities(List.of("necrotic"));

        assertThat(DmAdjustments.damageTypes(pc, Defence.RESISTANCE)).containsExactly("Fire", "acid");
        assertThat(DmAdjustments.damageTypes(pc, Defence.IMMUNITY)).containsExactly("necrotic");
        assertThat(DmAdjustments.damageTypes(pc, Defence.VULNERABILITY)).containsExactly("cold");
        assertThat(DmAdjustments.defences(pc, "FIRE")).isEqualTo(Set.of(Defence.RESISTANCE));
        assertThat(DmAdjustments.defences(pc, "cold")).isEqualTo(Set.of(Defence.VULNERABILITY));
        assertThat(DmAdjustments.defences(pc, "poison")).isEmpty();
        assertThat(DmAdjustments.defences(pc, null)).isEmpty();
    }

    @Test
    @DisplayName("Every feat taken with its effects applied: the level history's first, then the DM's active grants")
    void featsTaken() {
        PlayerCharacter pc = character(granted("Lucky"), granted("Alert").removed(DM, ADDED), granted("Mobile"));
        pc.setLevelHistory(List.of(
                new LevelHistoryEntry(1, null, "Fighter", 1, 12, 10, null, LevelChoices.NONE, null, null),
                new LevelHistoryEntry(4, null, "Fighter", 4, 8, 6, null, LevelChoices.NONE.withAsi(
                        new AbilityScoreImprovement("feat", null, "Tough", null, null, effects("Tough"), null)),
                        null, null),
                // A feat taken by name before R6 recorded no effects
                new LevelHistoryEntry(6, null, "Fighter", 6, 8, 6, null, LevelChoices.NONE.withAsi(
                        new AbilityScoreImprovement("feat", null, "Sentinel", null, null, null, null)), null, null)));

        assertThat(DmAdjustments.featsTaken(pc)).extracting(AppliedFeatEffects::featName)
                .containsExactly("Tough", "Lucky", "Mobile");
    }
}
