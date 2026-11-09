package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.combat.SummonRules.InitiativeMode;
import com.tabletophelper.rules.combat.SummonRules.Kind;
import com.tabletophelper.rules.combat.SummonRules.Option;
import com.tabletophelper.rules.combat.SummonRules.Spell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The summoning spells' numbers, typed out from the books (D182): Conjure Animals (PHB p.225), Summon Lesser Demons
 * (XGE p.167), Flock of Familiars (LLK p.57), Animate Dead (PHB p.212), Tiny Servant (XGE p.168), Find Familiar and
 * Find Steed (PHB p.240) and the Tasha's spirits (TCE p.109–114).
 */
class SummonRulesTest {

    private static Spell spell(String name) {
        return SummonRules.spell(name).orElseThrow();
    }

    private static List<Integer> counts(String name, int slotLevel) {
        return SummonRules.options(spell(name), slotLevel, false).stream().map(Option::count).toList();
    }

    @Nested
    @DisplayName("Conjure Animals (PHB p.225)")
    class ConjureAnimals {

        @ParameterizedTest(name = "a level {0} slot brings {1}, {2}, {3} or {4} beasts")
        @CsvSource({"3,1,2,4,8", "4,1,2,4,8", "5,2,4,8,16", "6,2,4,8,16", "7,3,6,12,24", "8,3,6,12,24",
                "9,4,8,16,32"})
        @DisplayName("Each option's count, twice at 5th, three times at 7th, four times at 9th")
        void counts_byLevel(int slot, int one, int two, int four, int eight) {
            assertEquals(List.of(one, two, four, eight), counts("Conjure Animals", slot));
        }

        @Test
        @DisplayName("The options cap the beasts' challenge rating at 2, 1, 1/2 and 1/4, named by count and cap")
        void options_capChallengeRating() {
            List<Option> options = SummonRules.options(spell("Conjure Animals"), 5, false);
            assertEquals(List.of(2.0, 1.0, 0.5, 0.25), options.stream().map(Option::maxCr).toList());
            assertEquals(List.of("2xCR2", "4xCR1", "8xCR1/2", "16xCR1/4"), options.stream().map(Option::id).toList());
        }

        @Test
        @DisplayName("A wolf (CR 1/4) fits eight; a brown bear (CR 1) doesn't fit the CR 1/4 option")
        void refusal_challengeRating() {
            Spell spell = spell("Conjure Animals");
            Option eight = SummonRules.options(spell, 3, false).get(3);
            assertTrue(SummonRules.refusal(spell, eight, "Wolf", "1/4", "beast", null, false).isEmpty());
            assertEquals("Brown Bear (CR 1) is above challenge rating 1/4",
                    SummonRules.refusal(spell, eight, "Brown Bear", "1", "beast", null, false).orElseThrow());
        }

        @Test
        @DisplayName("Only beasts that aren't swarms")
        void refusal_beastsOnly() {
            Spell spell = spell("Conjure Animals");
            Option one = SummonRules.options(spell, 3, false).getFirst();
            assertTrue(SummonRules.refusal(spell, one, "Goblin", "1/4", "humanoid", "goblinoid", false).isPresent());
            assertTrue(SummonRules.refusal(spell, one, "Swarm of Rats", "1/4", "beast", null, false).isPresent());
        }

        @Test
        @DisplayName("Fey, gone at 0 HP, a group's initiative, the follow-the-caster mode only as a table's choice")
        void facts() {
            Spell spell = spell("Conjure Animals");
            assertEquals("fey", spell.creatureType());
            assertTrue(spell.vanishesAtZero());
            assertTrue(spell.concentration());
            assertEquals(InitiativeMode.GROUP, spell.defaultInitiativeMode());
            assertEquals(List.of(InitiativeMode.FOLLOWS_CASTER), spell.houseRuleModes());
            assertFalse(spell.dmChooses());
        }

        @Test
        @DisplayName("The count must be exactly the option's")
        void countRefusal_exact() {
            Spell spell = spell("Conjure Animals");
            Option eight = SummonRules.options(spell, 3, false).get(3);
            assertTrue(SummonRules.countRefusal(spell, eight, 8).isEmpty());
            assertEquals("Conjure Animals brings 8 creatures with that option, but 7 were chosen",
                    SummonRules.countRefusal(spell, eight, 7).orElseThrow());
        }
    }

    @Nested
    @DisplayName("Summon Lesser Demons (XGE p.167)")
    class LesserDemons {

        @ParameterizedTest(name = "d6 {0} at level {1}: {2}")
        @CsvSource({"1,3,2xCR1", "2,3,2xCR1", "3,3,4xCR1/2", "4,3,4xCR1/2", "5,3,8xCR1/4", "6,3,8xCR1/4",
                "1,6,4xCR1", "4,7,8xCR1/2", "6,6,16xCR1/4", "1,8,6xCR1", "3,9,12xCR1/2", "5,8,24xCR1/4"})
        @DisplayName("The d6 table, twice as many at 6th or 7th, three times at 8th or 9th")
        void table(int d6, int slot, String option) {
            assertEquals(option, SummonRules.lesserDemons(d6, slot).id());
        }

        @Test
        @DisplayName("A d6 rolls 1 to 6")
        void table_outOfRange() {
            assertThrows(IllegalArgumentException.class, () -> SummonRules.lesserDemons(7, 3));
        }

        @Test
        @DisplayName("The DM chooses demons, which the DM controls; a dretch (CR 1/4) is a demon, an imp isn't")
        void demons() {
            Spell spell = spell("Summon Lesser Demons");
            assertTrue(spell.dmChooses());
            assertEquals(SummonRules.Controller.DM, spell.controller());
            assertTrue(spell.vanishesAtZero());
            Option eight = SummonRules.lesserDemons(5, 3);
            assertTrue(SummonRules.refusal(spell, eight, "Dretch", "1/4", "fiend", "demon", false).isEmpty());
            assertTrue(SummonRules.refusal(spell, eight, "Imp", "1", "fiend", "devil, shapechanger", false)
                    .isPresent());
            assertTrue(SummonRules.refusal(spell, eight, "Quasit", "1", "fiend", "demon, shapechanger", false)
                    .isPresent());
        }
    }

    @Nested
    @DisplayName("The others")
    class Others {

        @ParameterizedTest(name = "Flock of Familiars at level {0}: {1}")
        @CsvSource({"2,3", "3,4", "5,6", "9,10"})
        @DisplayName("Flock of Familiars: three, one more for each slot level above 2nd (LLK p.57)")
        void flock(int slot, int count) {
            assertEquals(List.of(count), counts("Flock of Familiars", slot));
            assertEquals(List.of(count - 1),
                    SummonRules.options(spell("Flock of Familiars"), slot, true).stream().map(Option::count).toList());
        }

        @ParameterizedTest(name = "{0} at level {1}: up to {2}")
        @CsvSource({"Animate Dead,3,1", "Animate Dead,4,3", "Animate Dead,9,13", "Tiny Servant,3,1",
                "Tiny Servant,5,5"})
        @DisplayName("Animate Dead and Tiny Servant: one, two more for each slot level above 3rd, fewer allowed")
        void animateDead(String name, int slot, int count) {
            assertEquals(List.of(count), counts(name, slot));
            Option option = SummonRules.options(spell(name), slot, false).getFirst();
            assertTrue(SummonRules.countRefusal(spell(name), option, 1).isEmpty());
            assertTrue(SummonRules.countRefusal(spell(name), option, count + 1).isPresent());
        }

        @Test
        @DisplayName("A familiar: the fifteen forms, Pact of the Chain's four more, and it can't attack")
        void familiar() {
            Spell spell = spell("Find Familiar");
            Option one = SummonRules.options(spell, 1, false).getFirst();
            assertEquals(15, SummonRules.FAMILIAR_FORMS.size());
            assertTrue(SummonRules.refusal(spell, one, "Owl", "0", "beast", null, false).isEmpty());
            assertTrue(SummonRules.refusal(spell, one, "Imp", "1", "fiend", "devil", false).isPresent());
            assertTrue(SummonRules.refusal(spell, one, "Imp", "1", "fiend", "devil", true).isEmpty());
            assertTrue(spell.cannotAttack());
            assertEquals(InitiativeMode.INDIVIDUAL, spell.defaultInitiativeMode());
            assertEquals(List.of("celestial", "fey", "fiend"), spell.typeChoices());
            assertFalse(spell.castInCombat());
        }

        @Test
        @DisplayName("Flock of Familiars needs concentration and takes no Pact of the Chain form")
        void flock_facts() {
            Spell spell = spell("Flock of Familiars");
            assertTrue(spell.concentration());
            Option option = SummonRules.options(spell, 2, false).getFirst();
            assertTrue(SummonRules.refusal(spell, option, "Imp", "1", "fiend", "devil", true).isPresent());
        }

        @Test
        @DisplayName("Find Steed: five forms, its own initiative or the rider's as a controlled mount")
        void steed() {
            Spell spell = spell("Find Steed");
            Option one = SummonRules.options(spell, 2, false).getFirst();
            assertTrue(SummonRules.refusal(spell, one, "Warhorse", "1/2", "beast", null, false).isEmpty());
            assertTrue(SummonRules.refusal(spell, one, "Riding Horse", "1/4", "beast", null, false).isPresent());
            assertEquals(List.of(InitiativeMode.INDIVIDUAL, InitiativeMode.FOLLOWS_CASTER), spell.initiativeModes());
            assertTrue(spell.houseRuleModes().isEmpty());
        }

        @Test
        @DisplayName("Animate Dead's undead need a command and stay at 0 HP; they take one group roll per kind")
        void animateDead_facts() {
            Spell spell = spell("Animate Dead");
            assertTrue(spell.needsCommand());
            assertFalse(spell.vanishesAtZero());
            assertFalse(spell.concentration());
            assertTrue(spell.groupsByCreature());
            assertTrue(SummonRules.refusal(spell, SummonRules.options(spell, 3, false).getFirst(), "Ghoul", "1",
                    "undead", null, false).isPresent());
        }

        @ParameterizedTest(name = "{0}: level {1}, follows its caster")
        @CsvSource({"Summon Beast,2", "Summon Fey,3", "Summon Shadowspawn,3", "Summon Undead,3"})
        @DisplayName("Tasha's spirits follow their caster, one spirit at any level")
        void spirits(String name, int level) {
            Spell spell = spell(name);
            assertEquals(Kind.SPIRIT, spell.kind());
            assertEquals(level, spell.level());
            assertEquals(List.of(InitiativeMode.FOLLOWS_CASTER), spell.initiativeModes());
            assertEquals(List.of(1), counts(name, 9));
        }

        @Test
        @DisplayName("Spell names are read in any case, and other spells aren't summonings")
        void spell_lookup() {
            assertTrue(SummonRules.spell("conjure animals").isPresent());
            assertTrue(SummonRules.spell("Fireball").isEmpty());
            assertEquals(11, SummonRules.spells().size());
        }
    }

    @Nested
    @DisplayName("Labels and reprints")
    class Labels {

        private static String label(String name, int slot, boolean hasFamiliar) {
            return SummonRules.options(spell(name), slot, hasFamiliar).getFirst().label();
        }

        @Test
        @DisplayName("Each option counts its creatures in the singular for one and the plural for more")
        void plurals() {
            assertEquals(List.of("1 beast of challenge rating 2 or lower", "2 beasts of challenge rating 1 or lower",
                            "4 beasts of challenge rating 1/2 or lower", "8 beasts of challenge rating 1/4 or lower"),
                    SummonRules.options(spell("Conjure Animals"), 3, false).stream().map(Option::label).toList());
            assertEquals("2 beasts of challenge rating 2 or lower", label("Conjure Animals", 5, false));
            assertEquals("2 demons of challenge rating 1 or lower", label("Summon Lesser Demons", 3, false));
            assertEquals("1 tiny servant", label("Tiny Servant", 3, false));
            assertEquals("3 tiny servants", label("Tiny Servant", 4, false));
            assertEquals("1 undead", label("Animate Dead", 3, false));
            assertEquals("3 undead", label("Animate Dead", 4, false));
            assertEquals("3 familiars", label("Flock of Familiars", 2, false));
            assertEquals("2 familiars", label("Flock of Familiars", 2, true));
            assertEquals("1 familiar", label("Find Familiar", 1, false));
            assertEquals("1 steed", label("Find Steed", 2, false));
            assertEquals("1 spirit", label("Summon Beast", 2, false));
        }

        @Test
        @DisplayName("Monsters of the Multiverse's printing replaces Volo's and the Tome of Foes', not the other way")
        void reprints() {
            assertTrue(SummonRules.reprints("MPMM", "VGM"));
            assertTrue(SummonRules.reprints("MPMM", "MTF"));
            assertFalse(SummonRules.reprints("VGM", "MPMM"));
            assertFalse(SummonRules.reprints("MM", "VGM"));
            assertFalse(SummonRules.reprints(null, "VGM"));
        }
    }
}
