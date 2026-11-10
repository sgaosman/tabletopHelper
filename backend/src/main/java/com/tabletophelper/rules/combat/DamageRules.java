package com.tabletophelper.rules.combat;

import java.util.List;
import java.util.Set;

/**
 * Damage, healing and dying under the 2014 PHB, chapter 9 (D161). Each function takes a creature's vitals and
 * returns the new ones, with the flags the EffectApplier acts on; nothing here changes a participant.
 */
public final class DamageRules {

    /** A creature's defence against one damage type (PHB p.197). */
    public enum Defence { RESISTANCE, VULNERABILITY, IMMUNITY }

    /** The thirteen damage types (PHB p.196), as combat names them, in alphabetical order. */
    public static final List<String> DAMAGE_TYPES = List.of("acid", "bludgeoning", "cold", "fire", "force",
            "lightning", "necrotic", "piercing", "poison", "psychic", "radiant", "slashing", "thunder");

    private DamageRules() {}

    /**
     * Half the damage, rounding down with no minimum, so half of 1 is 0 (PHB p.7): damage on a successful save
     * for half, and resistance.
     */
    public static int halved(int damage) {
        requireNotNegative(damage, "Damage");
        return damage / 2;
    }

    /**
     * Damage after the target's defences against its type. Immunity makes it 0. Otherwise resistance halves it,
     * rounding down, and then vulnerability doubles it, after every other modifier (PHB p.197). Several
     * resistances to the same type count once, as a set holds each defence once.
     */
    public static int applyDefences(int damage, Set<Defence> defences) {
        requireNotNegative(damage, "Damage");
        if (defences.contains(Defence.IMMUNITY)) return 0;
        int result = defences.contains(Defence.RESISTANCE) ? halved(damage) : damage;
        return defences.contains(Defence.VULNERABILITY) ? result * 2 : result;
    }

    /**
     * Damage the creature takes, after its defences. Temporary hit points absorb it first, and the rest comes off
     * its hit points (PHB p.198); damage the temporary hit points absorb entirely does nothing more.
     * <ul>
     *   <li>From above 0 HP, a monster that reaches 0 dies. A PC falls unconscious and prone, starts dying with
     *       both death save counts reset, and loses concentration, unless the damage left over after reaching 0
     *       is at least its HP maximum, which kills it outright (PHB p.197).</li>
     *   <li>At 0 HP, damage at least the HP maximum kills outright. Otherwise it is one death save failure, or two
     *       from a critical hit, and a stable PC starts dying again; the third failure kills (PHB p.197).</li>
     * </ul>
     * The dead are unchanged.
     */
    public static VitalsChange takeDamage(Vitals vitals, int damage, boolean critical) {
        requireNotNegative(damage, "Damage");
        if (damage == 0 || vitals.lifeState() == LifeState.DEAD) {
            return VitalsChange.only(vitals);
        }

        int absorbed = Math.min(vitals.tempHp(), damage);
        int leftover = damage - absorbed;
        if (leftover == 0) {
            return VitalsChange.only(withTempHp(vitals, vitals.tempHp() - absorbed));
        }
        // Damage left over means the temporary hit points are gone
        return vitals.lifeState() == LifeState.ALIVE
                ? damageAboveZero(vitals, leftover)
                : damageAtZero(vitals, leftover, critical);
    }

    /**
     * Healing. Hit points can't rise above the maximum, and temporary hit points are untouched, since healing
     * can't restore them. A dying or stable PC regains consciousness with both death save counts reset, and stays
     * prone. The dead can't regain hit points: only a revival, through {@link #setHp}, brings them back
     * (PHB p.197).
     */
    public static VitalsChange heal(Vitals vitals, int amount) {
        requireNotNegative(amount, "Healing");
        if (amount == 0 || vitals.lifeState() == LifeState.DEAD) {
            return VitalsChange.only(vitals);
        }
        int room = Math.max(0, vitals.maxHp() - vitals.hp());
        int hp = vitals.hp() + Math.min(amount, room);
        if (vitals.lifeState() == LifeState.ALIVE) {
            return VitalsChange.only(withHp(vitals, hp));
        }
        return regainedHitPoints(vitals, hp);
    }

    /**
     * Temporary hit points don't stack: the creature keeps the larger amount, its old ones or the new (the PHB
     * lets it choose, p.198; D161 makes the larger the default). A PC at 0 HP can gain them, though they don't
     * wake it up; the dead gain none.
     */
    public static VitalsChange gainTempHp(Vitals vitals, int amount) {
        requireNotNegative(amount, "Temporary hit points");
        if (vitals.lifeState() == LifeState.DEAD) {
            return VitalsChange.only(vitals);
        }
        return VitalsChange.only(withTempHp(vitals, Math.max(vitals.tempHp(), amount)));
    }

    /**
     * The DM's Set HP: a correction, or a revival such as Revivify, and the only way back from Dead. The hit
     * points are kept between 0 and the maximum. Above 0 the creature is Alive, and one that was down or dead
     * comes back with both death save counts reset. At 0, a creature that was up drops as if damaged, without
     * massive damage, and one already at 0 is unchanged.
     */
    public static VitalsChange setHp(Vitals vitals, int hp) {
        int newHp = Math.max(0, Math.min(vitals.maxHp(), hp));
        if (newHp > 0) {
            return vitals.lifeState() == LifeState.ALIVE
                    ? VitalsChange.only(withHp(vitals, newHp))
                    : regainedHitPoints(vitals, newHp);
        }
        if (vitals.lifeState() != LifeState.ALIVE) {
            return VitalsChange.only(vitals);
        }
        return dropToZero(vitals, vitals.tempHp(), 0);
    }

    /**
     * A dying PC's death saving throw, from the d20 as rolled. 10 or higher succeeds and lower fails; a 1 counts
     * as two failures, and a 20 brings the PC back with 1 HP. The third success makes it stable and the third
     * failure kills it. Both counts reset when it regains hit points or becomes stable (PHB p.197). Only the
     * dying make death saves, so anyone else is unchanged.
     */
    public static VitalsChange deathSave(Vitals vitals, int d20) {
        return deathSave(vitals, d20, d20);
    }

    /**
     * As {@link #deathSave(Vitals, int)}, with the total after what Ongoing Modifiers add to the d20, such as Bless's d4
     * (PHB p.219, D184): a natural 1 and a natural 20 count as rolled, and otherwise the total must reach 10.
     */
    public static VitalsChange deathSave(Vitals vitals, int d20, int total) {
        if (d20 < 1 || d20 > 20) {
            throw new IllegalArgumentException("A d20 rolls 1 to 20, not " + d20);
        }
        if (vitals.lifeState() != LifeState.DYING) {
            return VitalsChange.only(vitals);
        }
        if (d20 == 20) {
            return regainedHitPoints(vitals, 1);
        }
        if (d20 != 1 && total >= 10) {
            int successes = vitals.deathSaveSuccesses() + 1;
            return successes >= 3
                    ? VitalsChange.only(atZero(vitals, LifeState.STABLE, 0, 0))
                    : VitalsChange.only(atZero(vitals, LifeState.DYING, successes, vitals.deathSaveFailures()));
        }
        return failDeathSaves(vitals, d20 == 1 ? 2 : 1);
    }

    private static VitalsChange damageAboveZero(Vitals vitals, int damage) {
        if (damage < vitals.hp()) {
            return VitalsChange.only(new Vitals(vitals.hp() - damage, vitals.maxHp(), 0, LifeState.ALIVE,
                    vitals.deathSaveSuccesses(), vitals.deathSaveFailures(), vitals.playerCharacter()));
        }
        return dropToZero(vitals, 0, damage - vitals.hp());
    }

    private static VitalsChange damageAtZero(Vitals vitals, int damage, boolean critical) {
        Vitals withoutTempHp = withTempHp(vitals, 0);
        if (damage >= vitals.maxHp()) {
            Vitals dead = atZero(withoutTempHp, LifeState.DEAD, vitals.deathSaveSuccesses(), vitals.deathSaveFailures());
            return new VitalsChange(dead, false, true, true, false, false, false);
        }
        return failDeathSaves(withoutTempHp, critical ? 2 : 1);
    }

    // From above 0 HP to 0, with the damage left over after reaching 0
    private static VitalsChange dropToZero(Vitals vitals, int tempHp, int leftover) {
        if (!vitals.playerCharacter()) {
            Vitals dead = new Vitals(0, vitals.maxHp(), tempHp, LifeState.DEAD,
                    vitals.deathSaveSuccesses(), vitals.deathSaveFailures(), false);
            return new VitalsChange(dead, true, true, false, true, false, false);
        }
        if (leftover >= vitals.maxHp()) {
            Vitals dead = new Vitals(0, vitals.maxHp(), tempHp, LifeState.DEAD, 0, 0, true);
            return new VitalsChange(dead, true, true, true, true, false, false);
        }
        Vitals dying = new Vitals(0, vitals.maxHp(), tempHp, LifeState.DYING, 0, 0, true);
        return new VitalsChange(dying, true, false, false, true, true, false);
    }

    private static VitalsChange failDeathSaves(Vitals vitals, int failures) {
        int total = Math.min(3, vitals.deathSaveFailures() + failures);
        if (total < 3) {
            return VitalsChange.only(atZero(vitals, LifeState.DYING, vitals.deathSaveSuccesses(), total));
        }
        Vitals dead = atZero(vitals, LifeState.DEAD, vitals.deathSaveSuccesses(), 3);
        return new VitalsChange(dead, false, true, false, false, false, false);
    }

    // A creature that wasn't Alive comes back with hit points and both death save counts reset. A PC loses the
    // unconscious condition dropping to 0 gave it; a monster never had one.
    private static VitalsChange regainedHitPoints(Vitals vitals, int hp) {
        Vitals alive = new Vitals(hp, vitals.maxHp(), vitals.tempHp(), LifeState.ALIVE, 0, 0, vitals.playerCharacter());
        return new VitalsChange(alive, false, false, false, false, false, vitals.playerCharacter());
    }

    private static Vitals atZero(Vitals vitals, LifeState state, int successes, int failures) {
        return new Vitals(0, vitals.maxHp(), vitals.tempHp(), state, successes, failures, vitals.playerCharacter());
    }

    private static Vitals withHp(Vitals vitals, int hp) {
        return new Vitals(hp, vitals.maxHp(), vitals.tempHp(), vitals.lifeState(),
                vitals.deathSaveSuccesses(), vitals.deathSaveFailures(), vitals.playerCharacter());
    }

    private static Vitals withTempHp(Vitals vitals, int tempHp) {
        return new Vitals(vitals.hp(), vitals.maxHp(), tempHp, vitals.lifeState(),
                vitals.deathSaveSuccesses(), vitals.deathSaveFailures(), vitals.playerCharacter());
    }

    private static void requireNotNegative(int amount, String what) {
        if (amount < 0) {
            throw new IllegalArgumentException(what + " can't be negative: " + amount);
        }
    }
}
