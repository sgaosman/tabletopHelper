package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.dto.SpellOption.SourceOption;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.rules.combat.CastingTime;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Spell Source rules on their own (D164, D165): which sources pay for which spells, and what a cast is
 * refused for. SpellcastingServiceTest runs them end to end.
 */
class SpellSourcesTest {

    private static SpellSources.Known classSpell(String name, int level, String className, boolean prepared) {
        return new SpellSources.Known(name, level, "class:" + className, prepared, false, false, null, null);
    }

    private static SpellSources.Known monsterSpell(String name, int level, boolean atWill, Integer uses) {
        return new SpellSources.Known(name, level, SpellSources.MONSTER, false, false, atWill, uses, null);
    }

    private static SpellSources.Purse purse(Map<String, SpellSlot> slots) {
        return new SpellSources.Purse(slots, List.of(), 5);
    }

    private static SpellOption only(List<SpellOption> options) {
        assertEquals(1, options.size());
        return options.getFirst();
    }

    @Nested
    @DisplayName("Options")
    class Options {

        @Test
        @DisplayName("Each option carries its casting time, an action when the spells table doesn't list it, and a "
                + "reaction spell's trigger (D174)")
        void castingTime() {
            List<SpellOption> options = SpellSources.options(List.of(
                    new SpellSources.Known("Shield", 1, "class:Wizard", true, false, false, null, null,
                            "1 reaction, which you take when you are hit by an attack"),
                    new SpellSources.Known("Misty Step", 2, "class:Wizard", true, false, false, null, null,
                            "1 bonus action"),
                    new SpellSources.Known("Identify", 1, "class:Wizard", true, false, false, null, null,
                            "1 minute"),
                    classSpell("Homebrew Bolt", 1, "Wizard", true)),
                    purse(Map.of("1", new SpellSlot(2, 0), "2", new SpellSlot(2, 0))));

            assertEquals(List.of("Homebrew Bolt", "Identify", "Shield", "Misty Step"),
                    options.stream().map(SpellOption::name).toList());
            assertEquals(List.of(CastingTime.ACTION, CastingTime.LONGER, CastingTime.REACTION,
                    CastingTime.BONUS_ACTION), options.stream().map(SpellOption::castingTime).toList());
            assertEquals("which you take when you are hit by an attack", options.get(2).reactionTrigger());
            assertNull(options.get(3).reactionTrigger());
        }

        @Test
        @DisplayName("A cantrip costs nothing whatever slots the caster has")
        void cantrip() {
            SpellOption fireBolt = only(SpellSources.options(List.of(classSpell("Fire Bolt", 0, "Wizard", false)),
                    purse(Map.of("1", new SpellSlot(2, 0)))));

            assertEquals(List.of(new SourceOption(SpellSource.NONE, List.of(0), null, null)), fireBolt.sources());
        }

        @Test
        @DisplayName("A levelled spell lists each slot level from its own up with a slot left, and the pact slot")
        void slotsAndPactSlot() {
            SpellOption fireball = only(SpellSources.options(List.of(classSpell("Fireball", 3, "Sorcerer", false)),
                    purse(Map.of("2", new SpellSlot(3, 0), "3", new SpellSlot(3, 3), "4", new SpellSlot(1, 0),
                            "5", new SpellSlot(1, 0), "pact_3", new SpellSlot(2, 1)))));

            assertEquals(List.of(
                    new SourceOption(SpellSource.SLOT, List.of(4, 5), null, null),
                    new SourceOption(SpellSource.PACT_SLOT, List.of(3), 1, 2)), fireball.sources());
        }

        @Test
        @DisplayName("Spent sources stay listed with no levels; a spell no slot could ever pay for is left out")
        void spentAndImpossible() {
            List<SpellOption> options = SpellSources.options(List.of(
                            classSpell("Shield", 1, "Sorcerer", false),
                            classSpell("Fireball", 3, "Sorcerer", false)),
                    purse(Map.of("1", new SpellSlot(2, 2), "pact_2", new SpellSlot(1, 1))));

            assertEquals(List.of("Shield"), options.stream().map(SpellOption::name).toList());
            assertEquals(List.of(
                    new SourceOption(SpellSource.SLOT, List.of(), null, null),
                    new SourceOption(SpellSource.PACT_SLOT, List.of(), 0, 1)), options.getFirst().sources());
        }

        @Test
        @DisplayName("A prepared caster lists only prepared or always-prepared spells; a known caster lists them all")
        void preparedCasters() {
            List<SpellOption> options = SpellSources.options(List.of(
                    classSpell("Bless", 1, "Cleric", false),
                    new SpellSources.Known("Cure Wounds", 1, "class:Cleric", false, true, false, null, null),
                    classSpell("Guiding Bolt", 1, "Cleric", true),
                    classSpell("Shield", 1, "Sorcerer", false),
                    classSpell("Hex", 1, "Warlock", false)), purse(Map.of("1", new SpellSlot(4, 0))));

            assertEquals(List.of("Cure Wounds", "Guiding Bolt", "Hex", "Shield"),
                    options.stream().map(SpellOption::name).toList());
        }

        @Test
        @DisplayName("A race spell unlocks at its level, then has its own uses and can take a slot too")
        void raceSpell() {
            SpellSources.Known rebuke = new SpellSources.Known("Hellish Rebuke", 1, "race:Tiefling", false, false,
                    false, 1, 3);
            Map<String, SpellSlot> slots = Map.of("2", new SpellSlot(1, 0));

            assertTrue(SpellSources.options(List.of(rebuke), new SpellSources.Purse(slots, List.of(), 2)).isEmpty());
            SpellOption unlocked = only(SpellSources.options(List.of(rebuke),
                    new SpellSources.Purse(slots, List.of(), 3)));
            assertEquals(List.of(
                    new SourceOption(SpellSource.SLOT, List.of(2), null, null),
                    new SourceOption(SpellSource.INNATE, List.of(1), 1, 1)), unlocked.sources());
        }

        @Test
        @DisplayName("A monster's daily spell takes only its uses, from its pool when it has one; at will costs nothing")
        void monsterInnateAndAtWill() {
            ResourcePoolEntry pool = SpellSources.innatePool("Fireball", 3, "MONSTER", "Mage").withCurrentUses(1);
            List<SpellOption> options = SpellSources.options(List.of(
                            monsterSpell("Fireball", 3, false, 3),
                            monsterSpell("Detect Magic", 1, true, null)),
                    new SpellSources.Purse(Map.of("3", new SpellSlot(2, 0)), List.of(pool), null));

            assertEquals(List.of(new SourceOption(SpellSource.NONE, List.of(1), null, null)),
                    options.get(0).sources());
            assertEquals(List.of(new SourceOption(SpellSource.INNATE, List.of(3), 1, 3)), options.get(1).sources());
        }

        @Test
        @DisplayName("A spell a monster has by slot and by the day, listed twice, is one option with both sources")
        void sameSpellTwice() {
            SpellOption fireball = only(SpellSources.options(List.of(
                            monsterSpell("Fireball", 3, false, null),
                            monsterSpell("fireball", 3, false, 1)),
                    new SpellSources.Purse(Map.of("3", new SpellSlot(1, 0)), List.of(), null)));

            assertEquals(List.of(SpellSource.SLOT, SpellSource.INNATE),
                    fireball.sources().stream().map(SourceOption::source).toList());
        }
    }

    @Nested
    @DisplayName("Payment")
    class Payment {

        private final List<SpellSources.Known> sorcerer = List.of(
                classSpell("Fire Bolt", 0, "Sorcerer", false),
                classSpell("Hold Person", 2, "Sorcerer", false),
                new SpellSources.Known("Misty Step", 2, "feat:Fey Touched", false, false, false, 1, null));
        private final SpellSources.Purse slots = purse(Map.of("2", new SpellSlot(2, 0), "3", new SpellSlot(1, 1),
                "4", new SpellSlot(1, 0), "pact_3", new SpellSlot(1, 0)));

        private SpellSources.Payment pay(String spell, SpellSource source, int slotLevel) {
            return SpellSources.pay("Sorcerer", sorcerer, slots, spell, source, slotLevel);
        }

        private String refusal(String spell, SpellSource source, int slotLevel) {
            return assertThrows(ValidationException.class, () -> pay(spell, source, slotLevel)).getMessage();
        }

        @Test
        @DisplayName("A slot casts at its level, upcasting from the spell's own; the name is matched in any case")
        void slot() {
            SpellSources.Payment payment = pay("hold person", SpellSource.SLOT, 4);

            assertEquals(new SpellSources.Payment("Hold Person", 2, SpellSource.SLOT, 4, "4", null, "class:Sorcerer",
                    CastingTime.ACTION),
                    payment);
        }

        @Test
        @DisplayName("A pact slot casts at the pact slots' level, named or left as 0")
        void pactSlot() {
            assertEquals(3, pay("Hold Person", SpellSource.PACT_SLOT, 0).castLevel());
            assertEquals("pact_3", pay("Hold Person", SpellSource.PACT_SLOT, 3).slotKey());
            assertEquals("Pact slots are level 3, so a pact slot casts Hold Person at level 3",
                    refusal("Hold Person", SpellSource.PACT_SLOT, 4));
        }

        @Test
        @DisplayName("An innate use casts at the spell's own level and names the pool, full, for a caster without it")
        void innate() {
            SpellSources.Payment payment = pay("Misty Step", SpellSource.INNATE, 0);

            assertEquals(2, payment.castLevel());
            assertEquals("innate:misty-step", payment.pool().poolId());
            assertEquals(1, payment.pool().currentUses());
            assertEquals("FEAT", payment.pool().sourceType());
            assertEquals("Fey Touched", payment.pool().sourceName());
            assertEquals("longRest", payment.pool().resetOn());
            assertEquals("feat:Fey Touched", payment.origin());
            assertEquals("Misty Step is cast at its own level, 2, with an innate use: innate spells aren't upcast",
                    refusal("Misty Step", SpellSource.INNATE, 3));
        }

        @Test
        @DisplayName("Without a source, a slot level asks for a slot, and 0 for nothing or an innate use")
        void inferred() {
            assertEquals(SpellSource.SLOT, pay("Hold Person", null, 2).source());
            assertEquals(SpellSource.NONE, pay("Fire Bolt", null, 0).source());
            assertEquals(SpellSource.INNATE, pay("Misty Step", null, 0).source());
            assertEquals("Hold Person is a level 2 spell, so it needs a spell slot, a pact slot or an innate use",
                    refusal("Hold Person", null, 0));
        }

        @Test
        @DisplayName("Each refusal says what is wrong: an unknown spell, a low slot, a spent slot, the wrong source")
        void refusals() {
            assertEquals("Sorcerer can't cast Fireball: it isn't one of their spells", refusal("Fireball", null, 3));
            assertEquals("Hold Person is a level 2 spell, so it needs a slot of level 2 or higher",
                    refusal("Hold Person", SpellSource.SLOT, 1));
            assertEquals("Sorcerer has no level 3 spell slots left", refusal("Hold Person", SpellSource.SLOT, 3));
            assertEquals("Hold Person can't be cast with an innate use", refusal("Hold Person", SpellSource.INNATE, 0));
            assertEquals("Fire Bolt is a cantrip, so it is cast without a slot", refusal("Fire Bolt", SpellSource.NONE, 1));
        }

        @Test
        @DisplayName("A spent innate use is refused, and a spell above every slot says so")
        void spent() {
            ResourcePoolEntry used = SpellSources.innatePool("Misty Step", 1, "FEAT", "Fey Touched").withCurrentUses(0);
            SpellSources.Purse purse = new SpellSources.Purse(Map.of("1", new SpellSlot(2, 0)), List.of(used), 3);

            assertEquals("Sorcerer has no uses of Misty Step left", assertThrows(ValidationException.class,
                    () -> SpellSources.pay("Sorcerer", sorcerer, purse, "Misty Step", SpellSource.INNATE, 0))
                    .getMessage());
            assertEquals("Sorcerer has no spell slot of level 2 or higher, which Hold Person needs",
                    assertThrows(ValidationException.class, () -> SpellSources.pay("Sorcerer", sorcerer, purse,
                            "Hold Person", null, 2)).getMessage());
        }

        @Test
        @DisplayName("An unprepared spell of a prepared caster, and a locked race spell, are refused with the reason")
        void notCastableNow() {
            List<SpellSources.Known> wizard = List.of(classSpell("Fireball", 3, "Wizard", false),
                    new SpellSources.Known("Darkness", 2, "race:Drow", false, false, false, 1, 5));
            SpellSources.Purse purse = purse(Map.of("3", new SpellSlot(2, 0)));

            assertEquals("Wizard hasn't prepared Fireball", assertThrows(ValidationException.class,
                    () -> SpellSources.pay("Wizard", wizard, purse, "Fireball", null, 3)).getMessage());
            assertEquals("Wizard can cast Darkness from level 5", assertThrows(ValidationException.class,
                    () -> SpellSources.pay("Wizard", wizard, new SpellSources.Purse(Map.of(), List.of(), 4),
                            "Darkness", SpellSource.INNATE, 0)).getMessage());
        }
    }

    @Test
    @DisplayName("A spell had from a race and a class is cast from the class, whose numbers it uses (D164), unless an innate use pays")
    void originPrefersAClass() {
        List<SpellSources.Known> tiefling = List.of(
                new SpellSources.Known("Hellish Rebuke", 1, "race:Tiefling", false, false, false, 1, null),
                classSpell("Hellish Rebuke", 1, "Warlock", false));
        SpellSources.Purse purse = purse(Map.of("pact_1", new SpellSlot(1, 0)));

        assertEquals("class:Warlock", SpellSources.origin(tiefling));
        assertEquals("class:Warlock",
                SpellSources.pay("Tiefling", tiefling, purse, "Hellish Rebuke", SpellSource.PACT_SLOT, 0).origin());
        assertEquals("race:Tiefling",
                SpellSources.pay("Tiefling", tiefling, purse, "Hellish Rebuke", SpellSource.INNATE, 0).origin());
        assertEquals("race:Tiefling", SpellSources.origin(tiefling.subList(0, 1)));
        assertNull(SpellSources.origin(List.of()));
    }

    @Nested
    @DisplayName("A warlock's Mystic Arcanum (PHB p.108)")
    class MysticArcanum {

        // A Warlock 11 with its 6th-level arcanum, a 5th-level pact slot and a 5th-level spell known
        private final List<SpellSources.Known> warlock = List.of(
                classSpell("Hold Monster", 5, "Warlock", false),
                new SpellSources.Known("Mass Suggestion", 6, "arcanum:Warlock", false, false, false, null, null));
        private final Map<String, SpellSlot> pactSlots = Map.of("pact_5", new SpellSlot(3, 0));

        private SpellSources.Purse purse(ResourcePoolEntry... pools) {
            return new SpellSources.Purse(pactSlots, List.of(pools), 11);
        }

        @Test
        @DisplayName("The arcanum spell is offered with its one use at its own level; no pact slot is high enough")
        void option() {
            List<SpellOption> options = SpellSources.options(warlock, purse());

            assertEquals(List.of("Hold Monster", "Mass Suggestion"), options.stream().map(SpellOption::name).toList());
            assertEquals(List.of(new SourceOption(SpellSource.MYSTIC_ARCANUM, List.of(6), 1, 1)),
                    options.get(1).sources());
            // The 5th-level spell is cast with a pact slot as before, and has no arcanum
            assertEquals(List.of(new SourceOption(SpellSource.PACT_SLOT, List.of(5), 3, 3)),
                    options.get(0).sources());
        }

        @Test
        @DisplayName("A spent arcanum stays listed with no levels until a long rest refills its pool")
        void spentOption() {
            ResourcePoolEntry spent = ResourcePoolEntry.mysticArcanum(6, "Mass Suggestion").withCurrentUses(0);

            SpellOption massSuggestion = SpellSources.options(warlock, purse(spent)).get(1);

            assertEquals(List.of(new SourceOption(SpellSource.MYSTIC_ARCANUM, List.of(), 0, 1)),
                    massSuggestion.sources());
        }

        @Test
        @DisplayName("Casting spends the arcanum's pool at the spell's own level, never a slot, and isn't upcast")
        void payment() {
            SpellSources.Payment payment = SpellSources.pay("Warlock", warlock, purse(), "mass suggestion",
                    SpellSource.MYSTIC_ARCANUM, 0);

            assertEquals(SpellSource.MYSTIC_ARCANUM, payment.source());
            assertEquals(6, payment.castLevel());
            assertNull(payment.slotKey());
            assertEquals("mystic-arcanum:6", payment.pool().poolId());
            assertEquals("arcanum:Warlock", payment.origin());
            // Left to the server, a cast at 0 or the spell's level uses the arcanum
            assertEquals(SpellSource.MYSTIC_ARCANUM,
                    SpellSources.pay("Warlock", warlock, purse(), "Mass Suggestion", null, 0).source());
            assertEquals(SpellSource.MYSTIC_ARCANUM,
                    SpellSources.pay("Warlock", warlock, purse(), "Mass Suggestion", null, 6).source());
            assertEquals("Mass Suggestion is cast at its own level, 6, with a Mystic Arcanum: it isn't upcast",
                    assertThrows(ValidationException.class, () -> SpellSources.pay("Warlock", warlock, purse(),
                            "Mass Suggestion", SpellSource.MYSTIC_ARCANUM, 7)).getMessage());
            assertEquals("Mass Suggestion can't be cast with a pact slot", assertThrows(ValidationException.class,
                    () -> SpellSources.pay("Warlock", warlock, purse(), "Mass Suggestion", SpellSource.PACT_SLOT, 0))
                    .getMessage());
        }

        @Test
        @DisplayName("A spent arcanum is refused until a long rest")
        void spent() {
            ResourcePoolEntry spent = ResourcePoolEntry.mysticArcanum(6, "Mass Suggestion").withCurrentUses(0);

            assertEquals("Warlock has used their level 6 Mystic Arcanum; it comes back on a long rest",
                    assertThrows(ValidationException.class, () -> SpellSources.pay("Warlock", warlock, purse(spent),
                            "Mass Suggestion", SpellSource.MYSTIC_ARCANUM, 0)).getMessage());
        }

        @Test
        @DisplayName("A multiclass caster with a slot of the arcanum's level may cast it with the slot too (PHB p.164)")
        void slotOfItsLevel() {
            SpellOption option = SpellSources.options(warlock, new SpellSources.Purse(
                    Map.of("6", new SpellSlot(1, 0), "pact_5", new SpellSlot(3, 0)), List.of(), 20)).get(1);

            assertEquals(List.of(SpellSource.SLOT, SpellSource.MYSTIC_ARCANUM),
                    option.sources().stream().map(SourceOption::source).toList());
        }
    }

    @Test
    @DisplayName("An innate pool is \"innate:\" and the spell's name in kebab case")
    void innatePoolId() {
        assertEquals("innate:melf-s-acid-arrow", SpellSources.innatePoolId("Melf's Acid Arrow"));
        assertEquals("innate:enlarge-reduce", SpellSources.innatePoolId(" Enlarge/Reduce "));
    }
}
