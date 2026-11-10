package com.tabletophelper.rules.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The Polymorph family's limits on a form (D192). */
class ShapeSpellsTest {

    @Test
    @DisplayName("The five spells are found by name, and no other spell is")
    void found() {
        assertThat(ShapeSpells.of("polymorph")).contains(ShapeSpells.POLYMORPH);
        assertThat(ShapeSpells.of("Mass Polymorph")).contains(ShapeSpells.MASS_POLYMORPH);
        assertThat(ShapeSpells.of("True Polymorph")).contains(ShapeSpells.TRUE_POLYMORPH);
        assertThat(ShapeSpells.of("Shapechange")).contains(ShapeSpells.SHAPECHANGE);
        assertThat(ShapeSpells.of("Animal Shapes")).contains(ShapeSpells.ANIMAL_SHAPES);
        assertThat(ShapeSpells.of("Fireball")).isEmpty();
        assertThat(ShapeSpells.of(null)).isEmpty();
    }

    @Test
    @DisplayName("Polymorph's limit is the target's challenge rating, or its level (PHB p.266)")
    void polymorphLimit() {
        assertThat(ShapeSpells.maxChallengeRating(ShapeSpells.POLYMORPH, "5", 0, 9)).isEqualTo(5);
        assertThat(ShapeSpells.maxChallengeRating(ShapeSpells.POLYMORPH, "1/2", 0, 9)).isEqualTo(0.5);
        assertThat(ShapeSpells.maxChallengeRating(ShapeSpells.POLYMORPH, null, 7, 9)).isEqualTo(7);
    }

    @Test
    @DisplayName("Mass Polymorph's limit is half the level of a target without a challenge rating (XGE p.160)")
    void massPolymorphLimit() {
        assertThat(ShapeSpells.maxChallengeRating(ShapeSpells.MASS_POLYMORPH, null, 5, 17)).isEqualTo(2.5);
        assertThat(ShapeSpells.maxChallengeRating(ShapeSpells.MASS_POLYMORPH, "3", 0, 17)).isEqualTo(3);
        assertThat(ShapeSpells.refusal(ShapeSpells.MASS_POLYMORPH, "Giant Elk", "beast", "2", "Huge", 42, 2.5))
                .isEmpty();
        assertThat(ShapeSpells.refusal(ShapeSpells.MASS_POLYMORPH, "Giant Scorpion", "beast", "3", "Large", 52, 2.5))
                .hasValueSatisfying(reason -> assertThat(reason).contains("above the limit of 2.5"));
    }

    @Test
    @DisplayName("Shapechange's limit is the caster's level, and it refuses constructs and undead (PHB p.274)")
    void shapechangeLimit() {
        assertThat(ShapeSpells.maxChallengeRating(ShapeSpells.SHAPECHANGE, "5", 3, 17)).isEqualTo(17);
        assertThat(ShapeSpells.refusal(ShapeSpells.SHAPECHANGE, "Adult Red Dragon", "dragon", "17", "Huge", 256, 17))
                .isEmpty();
        assertThat(ShapeSpells.refusal(ShapeSpells.SHAPECHANGE, "Iron Golem", "construct", "16", "Large", 210, 17))
                .isPresent();
        assertThat(ShapeSpells.refusal(ShapeSpells.SHAPECHANGE, "Lich", "undead", "21", "Medium", 135, 21))
                .isPresent();
    }

    @Test
    @DisplayName("Polymorph and Animal Shapes take only beasts; True Polymorph any creature")
    void beastsOnly() {
        assertThat(ShapeSpells.refusal(ShapeSpells.POLYMORPH, "Ogre", "giant", "2", "Large", 59, 5))
                .hasValueSatisfying(reason -> assertThat(reason).contains("isn't a beast"));
        assertThat(ShapeSpells.refusal(ShapeSpells.TRUE_POLYMORPH, "Ogre", "giant", "2", "Large", 59, 5)).isEmpty();
    }

    @Test
    @DisplayName("Animal Shapes: CR 4 or lower and Large or smaller (PHB p.212)")
    void animalShapes() {
        assertThat(ShapeSpells.maxChallengeRating(ShapeSpells.ANIMAL_SHAPES, "10", 15, 15)).isEqualTo(4);
        assertThat(ShapeSpells.refusal(ShapeSpells.ANIMAL_SHAPES, "Elephant", "beast", "4", "Huge", 76, 4))
                .hasValueSatisfying(reason -> assertThat(reason).contains("larger than Large"));
        assertThat(ShapeSpells.refusal(ShapeSpells.ANIMAL_SHAPES, "Giant Scorpion", "beast", "3", "L", 52, 4))
                .isEmpty();
        assertThat(ShapeSpells.refusal(ShapeSpells.ANIMAL_SHAPES, "Giant Crocodile", "beast", "5", "Huge", 85, 4))
                .isPresent();
    }

    @Test
    @DisplayName("A form needs hit points and a readable challenge rating")
    void needsNumbers() {
        assertThat(ShapeSpells.refusal(ShapeSpells.POLYMORPH, "Shade", "beast", "1", "Medium", 0, 5)).isPresent();
        assertThat(ShapeSpells.refusal(ShapeSpells.POLYMORPH, "Odd", "beast", "?", "Medium", 5, 5)).isPresent();
    }

    @Test
    @DisplayName("Limits are written as the stat blocks write ratings")
    void labels() {
        assertThat(ShapeSpells.label(0.125)).isEqualTo("1/8");
        assertThat(ShapeSpells.label(0.5)).isEqualTo("1/2");
        assertThat(ShapeSpells.label(7)).isEqualTo("7");
        assertThat(ShapeSpells.label(2.5)).isEqualTo("2.5");
    }
}
