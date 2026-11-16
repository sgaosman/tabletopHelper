package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The numbers of the racial features with limited uses (M24.5b, D208), as pure functions: a Dragonborn's draconic
 * ancestry and Breath Weapon (PHB p.34), the save DC racial traits share, and the rounds the 1-minute traits last.
 */
public final class RacialFeatures {

    /** One minute in rounds, as the Aasimar transformations and a Shifter's shift last (VGM p.105, ERLW p.33). */
    public static final int MINUTE_ROUNDS = 10;

    private RacialFeatures() {
    }

    /** The shape of a Breath Weapon, which the player respects in picking the creatures it reaches: there is no map. */
    public enum Shape {
        LINE("5 by 30 ft. line"),
        CONE("15 ft. cone");

        private final String text;

        Shape(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * A Dragonborn's draconic ancestry (PHB p.34, Draconic Ancestry table): the dragon, the damage type of its breath
     * and resistance, the breath's shape and the save its creatures make. The EGW Draconblood and Ravenite subraces use
     * the same table.
     */
    public enum DraconicAncestry {
        BLACK("Black", "acid", Shape.LINE, Ability.DEX),
        BLUE("Blue", "lightning", Shape.LINE, Ability.DEX),
        BRASS("Brass", "fire", Shape.LINE, Ability.DEX),
        BRONZE("Bronze", "lightning", Shape.LINE, Ability.DEX),
        COPPER("Copper", "acid", Shape.LINE, Ability.DEX),
        GOLD("Gold", "fire", Shape.CONE, Ability.DEX),
        GREEN("Green", "poison", Shape.CONE, Ability.CON),
        RED("Red", "fire", Shape.CONE, Ability.DEX),
        SILVER("Silver", "cold", Shape.CONE, Ability.CON),
        WHITE("White", "cold", Shape.CONE, Ability.CON);

        private final String dragon;
        private final String damageType;
        private final Shape shape;
        private final Ability save;

        DraconicAncestry(String dragon, String damageType, Shape shape, Ability save) {
            this.dragon = dragon;
            this.damageType = damageType;
            this.shape = shape;
            this.save = save;
        }

        /** The dragon, as the table names it, such as "Red". */
        public String dragon() {
            return dragon;
        }

        /** The damage type of the breath and of the resistance, such as "fire". */
        public String damageType() {
            return damageType;
        }

        public Shape shape() {
            return shape;
        }

        /** The saving throw a creature in the area makes: Dexterity, or Constitution against poison and cold. */
        public Ability save() {
            return save;
        }

        /** The dragon of this name, in any case, such as "red" or "Red". */
        public static Optional<DraconicAncestry> of(String dragon) {
            if (dragon == null || dragon.isBlank()) {
                return Optional.empty();
            }
            String wanted = dragon.trim().toLowerCase(Locale.ROOT);
            return Arrays.stream(values()).filter(a -> a.dragon.toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
        }

        /** The ten dragons, in the table's order. */
        public static List<String> dragons() {
            return Arrays.stream(values()).map(DraconicAncestry::dragon).toList();
        }
    }

    /**
     * A Breath Weapon's damage at the character's total level (PHB p.34): 2d6, 3d6 at 6th level, 4d6 at 11th and 5d6
     * at 16th.
     */
    public static Dice breathWeaponDamage(int characterLevel) {
        int dice = characterLevel >= 16 ? 5 : characterLevel >= 11 ? 4 : characterLevel >= 6 ? 3 : 2;
        return new Dice(dice, 6, 0);
    }

    /**
     * The save DC of a racial trait: 8 + the ability's modifier + the proficiency bonus, as Breath Weapon's (Constitution,
     * PHB p.34), Daunting Roar's (Constitution, MOT p.21) and Necrotic Shroud's (Charisma, VGM p.105).
     */
    public static int saveDc(int proficiencyBonus, int abilityScore) {
        return 8 + proficiencyBonus + Ability.modifier(abilityScore);
    }
}
