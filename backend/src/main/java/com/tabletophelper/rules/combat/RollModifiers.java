package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Dice;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What the Ongoing Modifiers (D184) of the creatures involved do to one roll against one target: the named sources of
 * advantage and disadvantage an attack roll gets, the dice added to the attack roll or to the target's saving throw,
 * the extra damage a hit deals, and whether the target's invisibility is no use to it.
 *
 * @param attackSources  sources of advantage or disadvantage on an attack roll against the target, each named after
 *                       its spell, such as "Faerie Fire"; they combine with the others once (PHB p.173)
 * @param attackBonuses  what is added to, or taken from, an attack roll against the target
 * @param hitDamage      the damage a hit on the target adds, or takes away
 * @param saveBonuses    what is added to, or taken from, the target's saving throw
 * @param attackOneShots the one-shot modifiers spent once the attack is rolled, such as Vicious Mockery's
 *                       disadvantage (PHB p.285)
 * @param revealed       the target can't benefit from being invisible (Faerie Fire, PHB p.239)
 */
public record RollModifiers(List<AttackRules.Source> attackSources, List<Bonus> attackBonuses,
                            List<HitDamage> hitDamage, List<Bonus> saveBonuses, Set<UUID> attackOneShots,
                            boolean revealed) {

    /** Nothing changed. */
    public static final RollModifiers NONE = new RollModifiers(List.of(), List.of(), List.of(), List.of(), Set.of(),
            false);

    public RollModifiers {
        attackSources = attackSources != null ? List.copyOf(attackSources) : List.of();
        attackBonuses = attackBonuses != null ? List.copyOf(attackBonuses) : List.of();
        hitDamage = hitDamage != null ? List.copyOf(hitDamage) : List.of();
        saveBonuses = saveBonuses != null ? List.copyOf(saveBonuses) : List.of();
        attackOneShots = attackOneShots != null ? Set.copyOf(attackOneShots) : Set.of();
    }

    /** Only these save bonuses. */
    public static RollModifiers saves(List<Bonus> saveBonuses) {
        return new RollModifiers(List.of(), List.of(), List.of(), saveBonuses, Set.of(), false);
    }

    /**
     * Dice or a flat amount added to a d20 roll, or taken from it, such as Bless's +1d4 (PHB p.219) or Bane's -1d4 (PHB
     * p.216).
     *
     * @param label      what the log calls it: its spell's name
     * @param dice       what is rolled or added; a flat amount has no dice
     * @param sign       1 to add it, -1 to take it away
     * @param modifierId the Ongoing Modifier it comes from, null for none
     * @param oneShot    it is spent once rolled
     */
    public record Bonus(String label, Dice dice, int sign, UUID modifierId, boolean oneShot) {

        public Bonus {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(dice, "dice");
            if (sign != 1 && sign != -1) {
                throw new IllegalArgumentException("A sign is 1 or -1, not " + sign);
            }
        }
    }

    /** A bonus as rolled: its signed total, as "+1d4 (3) Bless" names it. */
    public record RolledBonus(String label, Dice dice, int sign, int total) {

        /** The log's words for it, such as "+1d4 (3) Bless" or "+1 Magic Weapon". */
        public String describe() {
            String sign = this.sign < 0 ? "-" : "+";
            if (dice.count() == 0) {
                return sign + Math.abs(total) + " " + label;
            }
            return sign + dice + " (" + Math.abs(total) + ") " + label;
        }
    }

    /**
     * Damage a hit adds or takes away (D184), such as Hunter's Mark's 1d6 (PHB p.251) or Reduce's -1d4 (PHB p.237).
     * Its dice are doubled on a critical hit (PHB p.196).
     *
     * @param type the damage type; null for the attack's own first type, as Hunter's Mark deals the weapon's
     */
    public record HitDamage(String label, Dice dice, int sign, String type, UUID modifierId, boolean oneShot) {

        public HitDamage {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(dice, "dice");
            if (sign != 1 && sign != -1) {
                throw new IllegalArgumentException("A sign is 1 or -1, not " + sign);
            }
        }
    }

    /** Extra damage as rolled on a hit. */
    public record RolledDamage(String label, Dice dice, int sign, String type, int amount) {

        /** The log's words for it, such as "Hunter's Mark 1d6 (4)" or "Reduce -1d4 (2)". */
        public String describe() {
            String expression = dice.count() == 0 ? String.valueOf(dice.modifier()) : dice.toString();
            return label + " " + (sign < 0 ? "-" : "") + expression + " (" + amount + ")";
        }
    }
}
