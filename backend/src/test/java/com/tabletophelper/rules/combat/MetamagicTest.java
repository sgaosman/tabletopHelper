package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.combat.Metamagic.Option;
import com.tabletophelper.rules.combat.Metamagic.SpellFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metamagic's rules (PHB p.102): each option's cost, the spells it can change, and which options go together.
 */
class MetamagicTest {

    // A 2nd-level single-target spell like Hold Person: WIS save, 1 minute, 60 feet, verbal and somatic, one more
    // target per slot level above the 2nd
    private static final SpellFacts HOLD_PERSON = new SpellFacts(2, CastingTime.ACTION, true, false, 10,
            "Concentration, up to 1 minute", true, false, 1, 1, "60 feet", true, true);
    // Fireball: DEX save, damage, instantaneous, 150 feet, a sphere
    private static final SpellFacts FIREBALL = new SpellFacts(3, CastingTime.ACTION, true, true, null,
            "Instantaneous", false, false, null, null, "150 feet", true, true);
    // Fire Bolt: a ranged spell attack on one target
    private static final SpellFacts FIRE_BOLT = new SpellFacts(0, CastingTime.ACTION, false, true, null,
            "Instantaneous", true, false, 1, null, "120 feet", true, true);
    // Shield: a reaction on the caster
    private static final SpellFacts SHIELD = new SpellFacts(1, CastingTime.REACTION, false, false, 1, "1 round",
            false, true, null, null, "Self", true, true);

    @Test
    @DisplayName("Costs: Careful, Distant, Empowered, Extended and Subtle 1, Quickened 2, Heightened 3, Twinned the cast level or 1 for a cantrip")
    void costs() {
        for (Option option : List.of(Option.CAREFUL, Option.DISTANT, Option.EMPOWERED, Option.EXTENDED,
                Option.SUBTLE)) {
            assertEquals(1, Metamagic.cost(option, 5), option.name());
        }
        assertEquals(2, Metamagic.cost(Option.QUICKENED, 3));
        assertEquals(3, Metamagic.cost(Option.HEIGHTENED, 1));
        assertEquals(1, Metamagic.cost(Option.TWINNED, 0));
        assertEquals(1, Metamagic.cost(Option.TWINNED, 1));
        assertEquals(4, Metamagic.cost(Option.TWINNED, 4));
    }

    @Test
    @DisplayName("Options are named by their PHB names or constants, in any case")
    void named() {
        assertEquals(Optional.of(Option.QUICKENED), Option.named("Quickened Spell"));
        assertEquals(Optional.of(Option.TWINNED), Option.named(" twinned spell "));
        assertEquals(Optional.of(Option.CAREFUL), Option.named("CAREFUL"));
        assertEquals(Optional.empty(), Option.named("Seeking Spell"));
        assertEquals(Optional.empty(), Option.named(null));
    }

    @Test
    @DisplayName("One option per casting, except that Empowered Spell may join another, and none twice")
    void combinations() {
        assertTrue(Metamagic.combinationProblem(List.of(Option.QUICKENED)).isEmpty());
        assertTrue(Metamagic.combinationProblem(List.of(Option.QUICKENED, Option.EMPOWERED)).isEmpty());
        assertTrue(Metamagic.combinationProblem(List.of(Option.EMPOWERED, Option.TWINNED)).isEmpty());
        assertTrue(Metamagic.combinationProblem(List.of(Option.QUICKENED, Option.TWINNED)).isPresent());
        assertTrue(Metamagic.combinationProblem(List.of(Option.EMPOWERED, Option.EMPOWERED)).isPresent());
    }

    @Test
    @DisplayName("Careful and Heightened need a saving throw; Empowered damage dice; Quickened a 1-action casting time")
    void saveDamageAndCastingTime() {
        assertTrue(Metamagic.ineligible(Option.CAREFUL, FIREBALL, 3).isEmpty());
        assertTrue(Metamagic.ineligible(Option.HEIGHTENED, HOLD_PERSON, 2).isEmpty());
        assertTrue(Metamagic.ineligible(Option.HEIGHTENED, FIRE_BOLT, 0).isPresent());
        assertTrue(Metamagic.ineligible(Option.EMPOWERED, FIRE_BOLT, 0).isEmpty());
        assertTrue(Metamagic.ineligible(Option.EMPOWERED, HOLD_PERSON, 2).isPresent());
        assertTrue(Metamagic.ineligible(Option.QUICKENED, FIREBALL, 3).isEmpty());
        assertEquals(Optional.of("Quickened Spell needs a casting time of 1 action"),
                Metamagic.ineligible(Option.QUICKENED, SHIELD, 1));
        assertTrue(Metamagic.ineligible(Option.QUICKENED, FIREBALL.withCastingTime(null), 3).isPresent());
    }

    @Test
    @DisplayName("Extended needs 1 minute or longer, by rounds or by the duration's words, and doubles to at most 24 hours")
    void extended() {
        assertTrue(Metamagic.ineligible(Option.EXTENDED, HOLD_PERSON, 2).isEmpty());
        assertTrue(Metamagic.ineligible(Option.EXTENDED, FIREBALL, 3).isPresent());
        assertTrue(Metamagic.ineligible(Option.EXTENDED, SHIELD, 1).isPresent());
        SpellFacts hourInWords = new SpellFacts(1, CastingTime.ACTION, false, false, null, "1 hour", false, false,
                null, null, "Touch", true, true);
        assertTrue(Metamagic.lastsAMinute(hourInWords));
        SpellFacts untilDispelled = new SpellFacts(1, CastingTime.ACTION, false, false, null, "Until dispelled",
                false, false, null, null, "Touch", true, true);
        assertFalse(Metamagic.lastsAMinute(untilDispelled));
        assertEquals(20, Metamagic.extendedRounds(10));
        assertEquals(9600, Metamagic.extendedRounds(4800));
        assertEquals(14400, Metamagic.extendedRounds(14400));
    }

    @Test
    @DisplayName("Distant needs a range of 5 feet or more, or touch; Subtle a verbal or somatic component")
    void distantAndSubtle() {
        assertTrue(Metamagic.ineligible(Option.DISTANT, FIREBALL, 3).isEmpty());
        SpellFacts touch = new SpellFacts(1, CastingTime.ACTION, false, false, null, "Instantaneous", true, false,
                1, null, "Touch", true, true);
        assertTrue(Metamagic.ineligible(Option.DISTANT, touch, 1).isEmpty());
        SpellFacts miles = new SpellFacts(5, CastingTime.ACTION, false, false, null, "Instantaneous", false, false,
                null, null, "1 mile", true, true);
        assertTrue(Metamagic.ineligible(Option.DISTANT, miles, 5).isEmpty());
        assertTrue(Metamagic.ineligible(Option.DISTANT, SHIELD, 1).isPresent());
        SpellFacts sight = new SpellFacts(4, CastingTime.ACTION, false, false, null, "Instantaneous", true, false,
                1, null, "Sight", true, true);
        assertTrue(Metamagic.ineligible(Option.DISTANT, sight, 4).isPresent());
        assertTrue(Metamagic.ineligible(Option.SUBTLE, FIREBALL, 3).isEmpty());
        SpellFacts materialOnly = new SpellFacts(1, CastingTime.ACTION, false, false, null, "Instantaneous", false,
                false, null, null, "30 feet", false, false);
        assertTrue(Metamagic.ineligible(Option.SUBTLE, materialOnly, 1).isPresent());
    }

    @Test
    @DisplayName("Twinned needs one target at the cast level and no range of self: Hold Person at 2nd but not 3rd, Fire Bolt, not Fireball or Shield")
    void twinned() {
        assertTrue(Metamagic.ineligible(Option.TWINNED, HOLD_PERSON, 2).isEmpty());
        assertTrue(Metamagic.ineligible(Option.TWINNED, HOLD_PERSON, 3).isPresent());
        assertEquals(Optional.of(2), Metamagic.maxCastLevel(Option.TWINNED, HOLD_PERSON));
        assertTrue(Metamagic.ineligible(Option.TWINNED, FIRE_BOLT, 0).isEmpty());
        assertEquals(Optional.empty(), Metamagic.maxCastLevel(Option.TWINNED, FIRE_BOLT));
        assertTrue(Metamagic.ineligible(Option.TWINNED, FIREBALL, 3).isPresent());
        assertEquals(Optional.of("Twinned Spell can't change a spell with a range of self"),
                Metamagic.ineligible(Option.TWINNED, SHIELD, 1));
    }

    @Test
    @DisplayName("Careful protects up to the CHA modifier, at least one; Empowered rerolls up to the CHA modifier")
    void charismaCounts() {
        assertEquals(1, Metamagic.carefulCreatures(-1));
        assertEquals(1, Metamagic.carefulCreatures(0));
        assertEquals(4, Metamagic.carefulCreatures(4));
        assertEquals(0, Metamagic.empoweredDice(0));
        assertEquals(5, Metamagic.empoweredDice(5));
    }
}
