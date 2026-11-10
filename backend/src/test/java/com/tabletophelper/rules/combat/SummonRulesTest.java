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
 * Find Steed (PHB p.240) and the Tasha's spirits (TCE p.109–114); and the 4th-level ones: Conjure Minor Elementals and
 * Conjure Woodland Beings (PHB p.226), Giant Insect (PHB p.245), Find Greater Steed (XGE p.156), Summon Greater Demon
 * (XGE p.166), Summon Aberration, Construct and Elemental (TCE p.109–111) and Spirit of Death (BMT p.50); and the
 * 5th-level ones: Summon Celestial (TCE p.110), Summon Draconic Spirit (FTD p.21), Conjure Elemental (PHB p.225),
 * Infernal Calling (XGE p.158), Animate Objects (PHB p.213) and Danse Macabre (XGE p.153).
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
            // Conjure Celestial joined at 7th level (D189)
            assertEquals(27, SummonRules.spells().size());
        }
    }

    @Nested
    @DisplayName("Conjure Minor Elementals and Conjure Woodland Beings (PHB p.226)")
    class FourthLevelConjurings {

        @ParameterizedTest(name = "{0} at level {1}: {2}, {3}, {4} or {5}")
        @CsvSource({"Conjure Minor Elementals,4,1,2,4,8", "Conjure Minor Elementals,5,1,2,4,8",
                "Conjure Minor Elementals,6,2,4,8,16", "Conjure Minor Elementals,7,2,4,8,16",
                "Conjure Minor Elementals,8,3,6,12,24", "Conjure Minor Elementals,9,3,6,12,24",
                "Conjure Woodland Beings,4,1,2,4,8", "Conjure Woodland Beings,6,2,4,8,16",
                "Conjure Woodland Beings,8,3,6,12,24"})
        @DisplayName("Conjure Animals' tiers, twice as many at 6th and three times at 8th")
        void counts_byLevel(String name, int slot, int one, int two, int four, int eight) {
            assertEquals(List.of(one, two, four, eight), counts(name, slot));
            assertEquals(List.of(2.0, 1.0, 0.5, 0.25), SummonRules.options(spell(name), slot, false).stream()
                    .map(Option::maxCr).toList());
        }

        @Test
        @DisplayName("Elementals only, not swarms; a fire elemental (CR 5) is above the CR 2 option")
        void elementals() {
            Spell spell = spell("Conjure Minor Elementals");
            Option one = SummonRules.options(spell, 4, false).getFirst();
            assertTrue(SummonRules.refusal(spell, one, "Mud Mephit", "1/4", "elemental", null, false).isEmpty());
            assertEquals("Wolf isn't an elemental Conjure Minor Elementals can summon",
                    SummonRules.refusal(spell, one, "Wolf", "1/4", "beast", null, false).orElseThrow());
            assertEquals("Fire Elemental (CR 5) is above challenge rating 2",
                    SummonRules.refusal(spell, one, "Fire Elemental", "5", "elemental", null, false).orElseThrow());
            Option eight = SummonRules.options(spell, 4, false).get(3);
            assertTrue(SummonRules.refusal(spell, eight, "Magma Mephit", "1/2", "elemental", null, false)
                    .isPresent());
        }

        @Test
        @DisplayName("Fey only: a pixie fits, a wolf doesn't")
        void fey() {
            Spell spell = spell("Conjure Woodland Beings");
            Option eight = SummonRules.options(spell, 4, false).get(3);
            assertTrue(SummonRules.refusal(spell, eight, "Pixie", "1/4", "fey", null, false).isEmpty());
            assertEquals("Wolf isn't a fey creature Conjure Woodland Beings can summon",
                    SummonRules.refusal(spell, eight, "Wolf", "1/4", "beast", null, false).orElseThrow());
        }

        @Test
        @DisplayName("Minor Elementals takes a minute so the DM places them; Woodland Beings is cast in a turn; both "
                + "roll as a group, concentrate, vanish at 0 HP, and keep their own type")
        void facts() {
            Spell elementals = spell("Conjure Minor Elementals");
            Spell fey = spell("Conjure Woodland Beings");
            assertFalse(elementals.castInCombat());
            assertTrue(fey.castInCombat());
            for (Spell spell : List.of(elementals, fey)) {
                assertEquals(Kind.CONJURE, spell.kind());
                assertEquals(4, spell.level());
                assertTrue(spell.concentration());
                assertTrue(spell.vanishesAtZero());
                assertTrue(spell.tiered());
                assertEquals(InitiativeMode.GROUP, spell.defaultInitiativeMode());
                assertEquals(List.of(InitiativeMode.FOLLOWS_CASTER), spell.houseRuleModes());
                assertEquals(SummonRules.Controller.CASTER, spell.controller());
                assertEquals(null, spell.creatureType());
                assertEquals("PHB", spell.source());
                assertEquals(226, spell.page());
            }
            assertEquals(List.of("1 elemental of challenge rating 2 or lower", "2 elementals of challenge rating 1 or "
                            + "lower", "4 elementals of challenge rating 1/2 or lower",
                    "8 elementals of challenge rating 1/4 or lower"),
                    SummonRules.options(elementals, 4, false).stream().map(Option::label).toList());
            assertEquals("2 fey creatures of challenge rating 2 or lower",
                    SummonRules.options(fey, 6, false).getFirst().label());
        }
    }

    @Nested
    @DisplayName("Giant Insect (PHB p.245)")
    class GiantInsect {

        @Test
        @DisplayName("Up to ten centipedes, three spiders, five wasps or one scorpion, each option its own form")
        void options() {
            Spell spell = spell("Giant Insect");
            List<Option> options = SummonRules.options(spell, 4, false);
            assertEquals(List.of(10, 3, 5, 1), options.stream().map(Option::count).toList());
            assertEquals(List.of("10xGiant Centipede", "3xGiant Spider", "5xGiant Wasp", "1xGiant Scorpion"),
                    options.stream().map(Option::id).toList());
            assertEquals(List.of("Up to 10 giant centipedes", "Up to 3 giant spiders", "Up to 5 giant wasps",
                    "Up to 1 giant scorpion"), options.stream().map(Option::label).toList());
            // No higher-level effect
            assertEquals(List.of(10, 3, 5, 1), counts("Giant Insect", 9));
        }

        @Test
        @DisplayName("A spider fits the spider option only; a giant wolf spider is no option's form")
        void forms() {
            Spell spell = spell("Giant Insect");
            Option spiders = SummonRules.options(spell, 4, false).get(1);
            Option wasps = SummonRules.options(spell, 4, false).get(2);
            assertTrue(SummonRules.refusal(spell, spiders, "Giant Spider", "1", "beast", null, false).isEmpty());
            assertEquals("That option of Giant Insect makes Giant Wasp, not Giant Spider",
                    SummonRules.refusal(spell, wasps, "Giant Spider", "1", "beast", null, false).orElseThrow());
            assertTrue(SummonRules.refusal(spell, spiders, "Giant Wolf Spider", "1/4", "beast", null, false)
                    .isPresent());
        }

        @Test
        @DisplayName("Fewer are allowed; they follow the caster, the DM resolves them, and they revert at 0 HP")
        void facts() {
            Spell spell = spell("Giant Insect");
            Option centipedes = SummonRules.options(spell, 4, false).getFirst();
            assertTrue(SummonRules.countRefusal(spell, centipedes, 4).isEmpty());
            assertTrue(SummonRules.countRefusal(spell, centipedes, 11).isPresent());
            assertEquals(List.of(InitiativeMode.FOLLOWS_CASTER), spell.initiativeModes());
            assertEquals(SummonRules.Controller.DM, spell.controller());
            assertTrue(spell.concentration());
            assertEquals(100, spell.durationRounds());
            assertTrue(spell.vanishesAtZero());
            assertFalse(spell.dmChooses());
        }
    }

    @Nested
    @DisplayName("Find Greater Steed (XGE p.156)")
    class GreaterSteed {

        @Test
        @DisplayName("Six forms, a celestial, fey or fiend, its own initiative or the rider's, placed by the DM")
        void forms() {
            Spell spell = spell("Find Greater Steed");
            Option one = SummonRules.options(spell, 4, false).getFirst();
            assertEquals(Kind.STEED, spell.kind());
            for (String form : List.of("Griffon", "Pegasus", "Peryton", "Dire Wolf", "Rhinoceros",
                    "Saber-Toothed Tiger")) {
                assertTrue(SummonRules.refusal(spell, one, form, "2", "beast", null, false).isEmpty(), form);
            }
            assertEquals("Warhorse isn't one of Find Greater Steed's forms",
                    SummonRules.refusal(spell, one, "Warhorse", "1/2", "beast", null, false).orElseThrow());
            assertTrue(SummonRules.refusal(spell("Find Steed"), one, "Griffon", "2", "monstrosity", null, false)
                    .isPresent());
            assertEquals(List.of("celestial", "fey", "fiend"), spell.typeChoices());
            assertEquals(List.of(InitiativeMode.INDIVIDUAL, InitiativeMode.FOLLOWS_CASTER), spell.initiativeModes());
            assertFalse(spell.castInCombat());
            assertFalse(spell.concentration());
            assertEquals("XGE", spell.source());
        }

        @Test
        @DisplayName("One mount at a time, shared with Find Steed; familiars share only with familiars")
        void sharedBond() {
            Spell steed = spell("Find Steed");
            Spell greater = spell("Find Greater Steed");
            assertTrue(SummonRules.sharesBond(steed, greater));
            assertTrue(SummonRules.sharesBond(greater, steed));
            assertTrue(SummonRules.sharesBond(greater, greater));
            assertFalse(SummonRules.sharesBond(greater, spell("Find Familiar")));
            assertFalse(SummonRules.sharesBond(spell("Find Familiar"), spell("Flock of Familiars")));
            assertFalse(SummonRules.sharesBond(spell("Conjure Animals"), spell("Conjure Animals")));
        }
    }

    @Nested
    @DisplayName("Summon Greater Demon (XGE p.166)")
    class GreaterDemon {

        @ParameterizedTest(name = "level {0}: one demon of CR {1} or lower")
        @CsvSource({"4,5,1xCR5", "5,6,1xCR6", "6,7,1xCR7", "9,10,1xCR10"})
        @DisplayName("One demon of challenge rating 5, one higher for each slot level above 4th")
        void crCap(int slot, double cap, String id) {
            List<Option> options = SummonRules.options(spell("Summon Greater Demon"), slot, false);
            assertEquals(1, options.size());
            assertEquals(cap, options.getFirst().maxCr());
            assertEquals(id, options.getFirst().id());
            assertEquals(1, options.getFirst().count());
        }

        @Test
        @DisplayName("Demons only: a barlgura (CR 5) at 4th, not a vrock (CR 6) until 5th, nor a bearded devil")
        void demons() {
            Spell spell = spell("Summon Greater Demon");
            Option fourth = SummonRules.options(spell, 4, false).getFirst();
            Option fifth = SummonRules.options(spell, 5, false).getFirst();
            assertTrue(SummonRules.refusal(spell, fourth, "Barlgura", "5", "fiend", "demon", false).isEmpty());
            assertEquals("Vrock (CR 6) is above challenge rating 5",
                    SummonRules.refusal(spell, fourth, "Vrock", "6", "fiend", "demon", false).orElseThrow());
            assertTrue(SummonRules.refusal(spell, fifth, "Vrock", "6", "fiend", "demon", false).isEmpty());
            assertEquals("Bearded Devil isn't a demon",
                    SummonRules.refusal(spell, fourth, "Bearded Devil", "3", "fiend", "devil", false).orElseThrow());
        }

        @Test
        @DisplayName("Its own initiative, the caster's to choose and control until it saves to break free")
        void facts() {
            Spell spell = spell("Summon Greater Demon");
            assertEquals(Kind.GREATER_DEMON, spell.kind());
            assertEquals(List.of(InitiativeMode.INDIVIDUAL), spell.initiativeModes());
            assertEquals(SummonRules.Controller.CASTER, spell.controller());
            assertFalse(spell.dmChooses());
            assertTrue(spell.savesToBreakControl());
            assertFalse(spell("Summon Lesser Demons").savesToBreakControl());
            assertTrue(spell.concentration());
            assertTrue(spell.vanishesAtZero());
            assertEquals(1, SummonRules.multiplier(spell, 9));
        }
    }

    @Nested
    @DisplayName("The 4th-level spirits (TCE p.109–111, BMT p.50)")
    class FourthLevelSpirits {

        @ParameterizedTest(name = "{0}: {1} p.{2}")
        @CsvSource({"Summon Aberration,TCE,109", "Summon Construct,TCE,111", "Summon Elemental,TCE,111",
                "Spirit of Death,BMT,50"})
        @DisplayName("Level 4 spirits that follow the caster, each from its own book")
        void spirits(String name, String source, int page) {
            Spell spell = spell(name);
            assertEquals(Kind.SPIRIT, spell.kind());
            assertEquals(4, spell.level());
            assertEquals(source, spell.source());
            assertEquals(page, spell.page());
            assertEquals(List.of(InitiativeMode.FOLLOWS_CASTER), spell.initiativeModes());
            assertTrue(spell.concentration());
            assertEquals(List.of(1), counts(name, 7));
        }
    }

    @Nested
    @DisplayName("The 5th-level summonings (D187)")
    class FifthLevel {

        @ParameterizedTest(name = "{0}: {1} p.{2}")
        @CsvSource({"Summon Celestial,TCE,110", "Summon Draconic Spirit,FTD,21"})
        @DisplayName("Summon Celestial and Summon Draconic Spirit: level 5 spirits that follow the caster")
        void spirits(String name, String source, int page) {
            Spell spell = spell(name);
            assertEquals(Kind.SPIRIT, spell.kind());
            assertEquals(5, spell.level());
            assertEquals(source, spell.source());
            assertEquals(page, spell.page());
            assertEquals(List.of(InitiativeMode.FOLLOWS_CASTER), spell.initiativeModes());
            assertTrue(spell.concentration());
            assertTrue(spell.castInCombat());
            assertEquals(List.of(1), counts(name, 9));
        }

        @ParameterizedTest(name = "Conjure Elemental at level {0}: CR {1} or lower")
        @CsvSource({"5,5", "6,6", "9,9"})
        @DisplayName("Conjure Elemental: one elemental of CR 5 or lower, one higher per slot level above 5th")
        void conjureElemental_challengeRating(int slot, double maxCr) {
            List<Option> options = SummonRules.options(spell("Conjure Elemental"), slot, false);
            assertEquals(1, options.size());
            assertEquals(1, options.getFirst().count());
            assertEquals(maxCr, options.getFirst().maxCr());
        }

        @Test
        @DisplayName("Conjure Elemental: a minute to cast, its own initiative, the caster's, and it stays hostile when "
                + "concentration breaks")
        void conjureElemental_spell() {
            Spell spell = spell("Conjure Elemental");
            assertEquals(Kind.CONJURE_ELEMENTAL, spell.kind());
            assertFalse(spell.castInCombat());
            assertEquals(List.of(InitiativeMode.INDIVIDUAL), spell.initiativeModes());
            assertEquals(SummonRules.Controller.CASTER, spell.controller());
            assertTrue(spell.vanishesAtZero());
            assertTrue(spell.turnsHostileWhenConcentrationBreaks());
            assertFalse(spell("Summon Greater Demon").turnsHostileWhenConcentrationBreaks());
            assertEquals(SummonRules.ONE_HOUR, spell.durationRounds());
        }

        @Test
        @DisplayName("Conjure Elemental takes an elemental of its rating, not a swarm, a beast or one too strong")
        void conjureElemental_refusal() {
            Spell spell = spell("Conjure Elemental");
            Option option = SummonRules.options(spell, 5, false).getFirst();
            assertTrue(SummonRules.refusal(spell, option, "Fire Elemental", "5", "elemental", null, false).isEmpty());
            assertTrue(SummonRules.refusal(spell, option, "Air Elemental", "5", "elemental", null, false).isEmpty());
            assertTrue(SummonRules.refusal(spell, option, "Xorn", "5", "elemental", null, false).isEmpty());
            assertTrue(SummonRules.refusal(spell, option, "Invisible Stalker", "6", "elemental", null, false)
                    .isPresent());
            assertTrue(SummonRules.refusal(spell, option, "Brown Bear", "1", "beast", null, false).isPresent());
            Option sixth = SummonRules.options(spell, 6, false).getFirst();
            assertTrue(SummonRules.refusal(spell, sixth, "Invisible Stalker", "6", "elemental", null, false)
                    .isEmpty());
        }

        @ParameterizedTest(name = "Infernal Calling at level {0}: CR {1} or lower")
        @CsvSource({"5,6", "7,8", "9,10"})
        @DisplayName("Infernal Calling: one devil of CR 6 or lower, one higher per slot level above 5th")
        void infernalCalling_challengeRating(int slot, double maxCr) {
            Option option = SummonRules.options(spell("Infernal Calling"), slot, false).getFirst();
            assertEquals(1, option.count());
            assertEquals(maxCr, option.maxCr());
        }

        @Test
        @DisplayName("Infernal Calling: a devil, the DM's, with its own initiative and a minute to cast")
        void infernalCalling_spell() {
            Spell spell = spell("Infernal Calling");
            assertEquals(Kind.INFERNAL_CALLING, spell.kind());
            assertEquals(SummonRules.Controller.DM, spell.controller());
            assertEquals(List.of(InitiativeMode.INDIVIDUAL), spell.initiativeModes());
            assertFalse(spell.castInCombat());
            Option option = SummonRules.options(spell, 5, false).getFirst();
            assertTrue(SummonRules.refusal(spell, option, "Barbed Devil", "5", "fiend", "devil", false).isEmpty());
            assertTrue(SummonRules.refusal(spell, option, "Chain Devil", "8", "fiend", "devil", false).isPresent());
            assertTrue(SummonRules.refusal(spell, option, "Vrock", "6", "fiend", "demon", false).isPresent());
        }

        @ParameterizedTest(name = "Animate Objects at level {0}: up to {1} objects")
        @CsvSource({"5,10", "6,12", "9,18"})
        @DisplayName("Animate Objects: ten objects, two more per slot level above 5th")
        void animateObjects_budget(int slot, int objects) {
            assertEquals(List.of(objects), counts("Animate Objects", slot));
        }

        @Test
        @DisplayName("Animate Objects counts a Tiny or Small object as one, Medium two, Large four and Huge eight")
        void animateObjects_weights() {
            Spell spell = spell("Animate Objects");
            assertTrue(spell.weighted());
            assertEquals(1, SummonRules.weight(spell, "Tiny"));
            assertEquals(1, SummonRules.weight(spell, "Small"));
            assertEquals(2, SummonRules.weight(spell, "Medium"));
            assertEquals(4, SummonRules.weight(spell, "Large"));
            assertEquals(8, SummonRules.weight(spell, "Huge"));
            assertEquals(1, SummonRules.weight(spell("Danse Macabre"), "Medium"));
        }

        @Test
        @DisplayName("Animate Objects: up to its budget, counted by size; a Huge and a Large is twelve, too many")
        void animateObjects_count() {
            Spell spell = spell("Animate Objects");
            Option option = SummonRules.options(spell, 5, false).getFirst();
            assertTrue(SummonRules.countRefusal(spell, option, 10).isEmpty());
            assertTrue(SummonRules.countRefusal(spell, option, 3).isEmpty());
            assertTrue(SummonRules.countRefusal(spell, option, 12).isPresent());
            assertTrue(SummonRules.countRefusal(spell, option, 0).isPresent());
        }

        @Test
        @DisplayName("Animate Objects: its five stat blocks, their hit points, a command and a dead row at 0")
        void animateObjects_spell() {
            Spell spell = spell("Animate Objects");
            assertEquals(Kind.ANIMATE_OBJECTS, spell.kind());
            assertTrue(spell.castInCombat());
            assertTrue(spell.needsCommand());
            assertFalse(spell.vanishesAtZero());
            assertTrue(spell.groupsByCreature());
            assertTrue(spell.fewerAllowed());
            assertEquals(10, spell.durationRounds());
            Option option = SummonRules.options(spell, 5, false).getFirst();
            assertTrue(SummonRules.refusal(spell, option, "Animated Object (Huge)", null, "construct", null, false)
                    .isEmpty());
            assertTrue(SummonRules.refusal(spell, option, "Animated Armor", "1", "construct", null, false)
                    .isPresent());
            assertEquals(List.of(20, 25, 40, 50, 80), SummonRules.ANIMATED_OBJECT_FORMS.stream()
                    .map(name -> SummonRules.statBlockHitPoints(name).orElseThrow()).toList());
            assertTrue(SummonRules.statBlockHitPoints("Zombie").isEmpty());
        }

        @ParameterizedTest(name = "Danse Macabre at level {0}: up to {1} undead")
        @CsvSource({"5,5", "6,7", "9,13"})
        @DisplayName("Danse Macabre: five corpses, two more per slot level above 5th")
        void danseMacabre_count(int slot, int count) {
            assertEquals(List.of(count), counts("Danse Macabre", slot));
        }

        @Test
        @DisplayName("Danse Macabre: zombies or skeletons, fewer allowed, commanded, with the caster's modifier")
        void danseMacabre_spell() {
            Spell spell = spell("Danse Macabre");
            assertEquals(Kind.DANSE_MACABRE, spell.kind());
            assertTrue(spell.concentration());
            assertTrue(spell.castInCombat());
            assertTrue(spell.needsCommand());
            assertTrue(spell.addsSpellcastingModifier());
            assertFalse(spell("Animate Dead").addsSpellcastingModifier());
            Option option = SummonRules.options(spell, 5, false).getFirst();
            assertTrue(SummonRules.refusal(spell, option, "Zombie", "1/4", "undead", null, false).isEmpty());
            assertTrue(SummonRules.refusal(spell, option, "Ghoul", "1", "undead", null, false).isPresent());
            assertTrue(SummonRules.countRefusal(spell, option, 4).isEmpty());
            assertTrue(SummonRules.countRefusal(spell, option, 6).isPresent());
        }

        @ParameterizedTest(name = "{0} with {1} is {2}")
        @CsvSource({"1d6 + 2,3,1d6 + 5", "1d6+1,4,1d6 + 5", "1d6,3,1d6 + 3", "1d6 + 2,-2,1d6", "2d8 - 1,3,2d8 + 2",
                "1d6 + 2,0,1d6 + 2"})
        @DisplayName("A flat bonus folds into the damage's own number (XGE p.153)")
        void withFlatBonus(String damage, int bonus, String expected) {
            assertEquals(expected, SummonRules.withFlatBonus(damage, bonus));
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
            assertEquals("1 elemental of challenge rating 5 or lower", label("Conjure Elemental", 5, false));
            assertEquals("1 devil of challenge rating 6 or lower", label("Infernal Calling", 5, false));
            assertEquals("5 undead", label("Danse Macabre", 5, false));
            assertEquals("Up to 10 objects (Medium counts as 2, Large as 4, Huge as 8)",
                    label("Animate Objects", 5, false));
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
