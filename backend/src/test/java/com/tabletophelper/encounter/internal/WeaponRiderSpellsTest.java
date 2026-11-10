package com.tabletophelper.encounter.internal;

import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules behind Zephyr Strike, Flame Arrows and Ray of Enfeeblement (D197). */
class WeaponRiderSpellsTest {

    private static OngoingModifier rider(String requires, boolean oneShot, boolean meleeOnly) {
        return new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.HIT_DAMAGE, null, "1d8", 0, null, null,
                List.of("force"), true, meleeOnly, oneShot, null, false, requires, List.of(), null, null);
    }

    @Test
    @DisplayName("Zephyr Strike: its one-shot 1d8 is spent by the next weapon attack, hit or miss (XGE p.171)")
    void zephyrSpentOnAttack() {
        OngoingModifier zephyr = rider(OngoingModifiers.ON_ATTACK, true, false);
        assertThat(OngoingModifiers.spentOnAttack(zephyr, true, true)).isTrue();
        assertThat(OngoingModifiers.spentOnAttack(zephyr, true, false)).isTrue();
        // a spell attack isn't a weapon attack, so it neither adds the force damage nor spends it
        assertThat(OngoingModifiers.spentOnAttack(zephyr, false, true)).isFalse();
    }

    @Test
    @DisplayName("A one-shot rider without the attack marker, as Absorb Elements', waits for a hit (XGE p.150)")
    void otherRidersWaitForAHit() {
        assertThat(OngoingModifiers.spentOnAttack(rider(null, true, true), true, true)).isFalse();
    }

    @Test
    @DisplayName("Flame Arrows: only a weapon attack from beyond 5 feet draws a piece and adds 1d6 fire (XGE p.156)")
    void flameArrowsRanged() {
        OngoingModifier arrows = rider(OngoingModifiers.AMMUNITION, false, false);
        List<OngoingModifiers.Labelled> labelled = List.of(new OngoingModifiers.Labelled("Flame Arrows", arrows));
        assertThat(OngoingModifiers.spentOnAttack(arrows, true, false)).isTrue();
        assertThat(OngoingModifiers.spentOnAttack(arrows, true, true)).isFalse();
        assertThat(OngoingModifiers.hitDamage(true, false, labelled)).hasSize(1);
        assertThat(OngoingModifiers.hitDamage(true, true, labelled)).isEmpty();
    }

    @Test
    @DisplayName("Ray of Enfeeblement halves each type of damage, rounding down (PHB p.271, p.197)")
    void halvesDamage() {
        assertThat(Enfeeblement.halvedDamage(List.of(new Resolver.Damage("slashing", 9),
                new Resolver.Damage("fire", 1))))
                .containsExactly(new Resolver.Damage("slashing", 4), new Resolver.Damage("fire", 0));
    }

    @Test
    @DisplayName("Ray of Enfeeblement: an entered or monster weapon attack counts as using Strength from within 5 feet")
    void strengthAttacks() {
        Effect melee = new Effect("Club", new Effect.Attack(4, RollMode.NORMAL, true, false),
                List.of(new Effect.DamagePart(Dice.parse("1d4"), "bludgeoning")), null, List.of(), false, null);
        Effect ranged = new Effect("Bow", new Effect.Attack(4, RollMode.NORMAL, false, false),
                List.of(new Effect.DamagePart(Dice.parse("1d6"), "piercing")), null, List.of(), false, null);
        assertThat(Enfeeblement.strengthAttack(melee)).isTrue();
        assertThat(Enfeeblement.strengthAttack(ranged)).isFalse();
        assertThat(Enfeeblement.noted(melee).name()).isEqualTo("Club (half damage, Ray of Enfeeblement)");
    }

    @ParameterizedTest(name = "slot {0}: {1}d8")
    @CsvSource({"2, 2", "3, 3", "4, 3", "5, 4", "6, 4", "7, 5", "9, 5"})
    @DisplayName("Shadow Blade's psychic dice grow at 3rd, 5th and 7th level (XGE p.164)")
    void shadowBladeDice(int slot, int dice) {
        assertThat(ShadowBlade.damage(slot)).isEqualTo(new Dice(dice, 8, 0));
        assertThat(ShadowBlade.weapon(slot).finesse()).isTrue();
        assertThat(ShadowBlade.weapon(slot).light()).isTrue();
        assertThat(ShadowBlade.named(" shadow blade ")).isTrue();
    }

    @Test
    @DisplayName("Ray of Enfeeblement's modifier reads as a hindrance in the log")
    void describes() {
        OngoingModifier weak = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.HALF_STRENGTH_DAMAGE, null,
                null, 0, null, null, List.of(), false, false, false, null, false, null, List.of(), null, null);
        assertThat(weak.describe()).isEqualTo("half damage with weapon attacks that use Strength");
    }
}
