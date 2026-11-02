package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.state.KnownSpell;
import com.tabletophelper.encounter.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.MonsterPoolTrigger;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every monster pool and a monster's spellcasting, built by one class (D165), with the seeded triggers as V18 left
 * them: legendary actions for a monster that has them, and Legendary Resistance for a monster with the trait of
 * that name. The old builders in ResourcePoolService and MonsterSpellCastService had no tests of their own.
 */
class MonsterPoolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MonsterPoolTriggerRepository triggerRepository = mock(MonsterPoolTriggerRepository.class);
    private final SpellRepository spellRepository = mock(SpellRepository.class);
    private final MonsterPools monsterPools = new MonsterPools(triggerRepository, spellRepository, objectMapper);

    @BeforeEach
    void setUp() {
        when(triggerRepository.findAllByOrderByPriorityAsc()).thenReturn(List.of(
                trigger("monster:legendary-actions", "Legendary Actions", "3", "turn", "{\"hasField\": \"legendary\"}", 1),
                trigger("monster:legendary-resistance", "Legendary Resistance", "3", "longRest",
                        "{\"hasFeature\": \"Legendary Resistance\"}", 2)));
        spell("Fireball", 3);
        spell("Mage Hand", 0);
        spell("Plane Shift", 7);
        spell("Detect Magic", 1);
        spell("Magic Missile", 1);
        spell("Shocking Grasp", 0);
    }

    private static MonsterPoolTrigger trigger(String poolId, String name, String maxUses, String resetOn,
                                              String condition, int priority) {
        ResourcePoolDefinition definition = ResourcePoolDefinition.builder().poolId(poolId).displayName(name)
                .sourceType("MONSTER").maxUsesFormula(maxUses).resetOn(resetOn).spendActionType("FREE").build();
        return MonsterPoolTrigger.builder().id(UUID.randomUUID()).poolDefinition(definition)
                .triggerCondition(condition).priority(priority).build();
    }

    private void spell(String name, int level) {
        Spell spell = Spell.builder().id(UUID.randomUUID()).name(name).level(level).build();
        lenient().when(spellRepository.findByNameIgnoreCase(argThat(name::equalsIgnoreCase)))
                .thenReturn(Optional.of(spell));
    }

    private static Monster monster(String name, String legendaryActions, String traits, String templates) {
        return Monster.builder().id(UUID.randomUUID()).name(name).challengeRating("10")
                .strength(10).dexterity(10).constitution(10).intelligence(10).wisdom(10).charisma(10)
                .legendaryActions(legendaryActions).traits(traits).actionTemplates(templates).build();
    }

    private static ResourcePoolEntry pool(List<ResourcePoolEntry> pools, String poolId) {
        return pools.stream().filter(pool -> pool.poolId().equals(poolId)).findFirst().orElse(null);
    }

    private static List<String> poolIds(List<ResourcePoolEntry> pools) {
        return pools.stream().map(ResourcePoolEntry::poolId).toList();
    }

    @Nested
    @DisplayName("Pools")
    class Pools {

        @Test
        @DisplayName("Legendary Resistance follows the trait's exact name, notes in brackets aside, with the template's count")
        void legendaryResistanceFromTheTrait() {
            Monster dragon = monster("Adult Red Dragon", "[{\"name\": \"Tail Attack\"}]",
                    "[{\"name\": \"Legendary Resistance (2/Day)\"}]",
                    "{\"legendaryActionCount\": 3, \"legendaryResistanceCount\": 2}");

            List<ResourcePoolEntry> pools = monsterPools.pools(dragon);

            assertEquals(List.of("monster:legendary-actions", "monster:legendary-resistance"), poolIds(pools));
            ResourcePoolEntry resistance = pool(pools, "monster:legendary-resistance");
            assertEquals(2, resistance.maxUses());
            assertEquals(2, resistance.currentUses());
            assertEquals("longRest", resistance.resetOn());
        }

        @Test
        @DisplayName("A monster with legendary actions but no Legendary Resistance trait gets no Legendary Resistance (D165)")
        void noLegendaryResistanceWithoutTheTrait() {
            Monster aboleth = monster("Aboleth", "[{\"name\": \"Psychic Drain\", \"entries\": [\"legendary\"]}]",
                    "[{\"name\": \"Amphibious\"}, {\"name\": \"Mucous Cloud\", \"entries\": [\"Legendary Resistance\"]}]",
                    "{\"legendaryActionCount\": 3}");

            assertEquals(List.of("monster:legendary-actions"), poolIds(monsterPools.pools(aboleth)));
        }

        @Test
        @DisplayName("Legendary actions reset each turn, at the template's count, or the definition's without one")
        void legendaryActions() {
            ResourcePoolEntry fromTemplate = pool(monsterPools.pools(monster("Lich", "[{\"name\": \"Cantrip\"}]", null,
                    "{\"legendaryActionCount\": 4}")), "monster:legendary-actions");
            ResourcePoolEntry fromDefinition = pool(monsterPools.pools(monster("Kraken", "[{\"name\": \"Tentacle\"}]",
                    null, null)), "monster:legendary-actions");

            assertEquals(4, fromTemplate.maxUses());
            assertEquals("turn", fromTemplate.resetOn());
            assertEquals(3, fromDefinition.maxUses());
            assertEquals("turn", fromDefinition.resetOn());
            assertEquals("Kraken", fromDefinition.sourceName());
        }

        @Test
        @DisplayName("A recharge action gets a pool with no resetOn and a d6 check, which the turn rolls (D146)")
        void rechargePools() {
            Monster dragon = monster("Young Red Dragon", null, null, """
                    {"actions": [{"name": "Fire Breath", "recharge": "5-6"}, {"name": "Lightning Burst", "recharge": "6"},
                                 {"name": "Bite"}, {"name": "Healing Touch", "recharge": "short or long rest"}]}""");

            List<ResourcePoolEntry> pools = monsterPools.pools(dragon);

            assertEquals(List.of("monster:recharge:fire-breath", "monster:recharge:lightning-burst"), poolIds(pools));
            ResourcePoolEntry breath = pool(pools, "monster:recharge:fire-breath");
            assertNull(breath.resetOn());
            assertEquals("1d6>=5", breath.resetCheck());
            assertEquals(1, breath.currentUses());
            assertEquals("1d6>=6", pool(pools, "monster:recharge:lightning-burst").resetCheck());
        }

        @Test
        @DisplayName("Daily keys give uses, never levels; a cantrip by the day costs nothing, so it gets no pool")
        void innatePools() {
            Monster drow = monster("Drow Mage", null, null, """
                    {"spellcasting": {"innateSpells": true, "saveDC": 12,
                     "dailySpells": {"3e/day each": ["fireball"], "1/day": ["mage hand"], "1e": ["plane shift (self only)"]}}}""");

            List<ResourcePoolEntry> pools = monsterPools.pools(drow);

            assertEquals(List.of("innate:fireball", "innate:plane-shift"), poolIds(pools));
            ResourcePoolEntry fireball = pool(pools, "innate:fireball");
            assertEquals(3, fireball.maxUses());
            assertEquals("Fireball", fireball.displayName());
            assertEquals("MONSTER", fireball.sourceType());
            assertEquals("Drow Mage", fireball.sourceName());
            assertEquals("longRest", fireball.resetOn());
            assertEquals(1, pool(pools, "innate:plane-shift").maxUses());
        }

        @Test
        @DisplayName("A trigger condition that isn't legendary actions or a trait, such as the old breath weapon one, adds nothing")
        void unknownTrigger() {
            when(triggerRepository.findAllByOrderByPriorityAsc()).thenReturn(List.of(trigger(
                    "monster:breath-weapon-5-6", "Breath Weapon", "1", "shortRest", "{\"hasRecharge\": true}", 3)));

            assertTrue(monsterPools.pools(monster("Dragon", null, null,
                    "{\"actions\": [{\"name\": \"Breath\", \"recharge\": \"5-6\"}]}")).stream()
                    .noneMatch(pool -> pool.poolId().startsWith("monster:breath-weapon")));
        }
    }

    @Nested
    @DisplayName("Spellcasting")
    class Spellcasting {

        private EncounterParticipant participant(Monster monster) {
            return EncounterParticipant.builder().id(UUID.randomUUID()).participantType(ParticipantType.MONSTER)
                    .monster(monster).displayName(monster.getName()).hpMax(10).hpCurrent(10).armourClass(12).build();
        }

        @Test
        @DisplayName("A monster joining gets its pools, slots, numbers and spells, named and levelled by the spells table")
        void setUp() {
            Monster mage = monster("Mage", null, null, """
                    {"spellcasting": {"ability": "INT", "attackBonus": 6, "saveDC": 14, "slots": {"1": 4, "3": 2},
                     "spellsByLevel": {"0": ["shocking grasp*"], "1": ["magic missile"], "3": ["fireball"]},
                     "atWillSpells": ["detect magic"], "dailySpells": {"1e": ["plane shift (self only)"]}}}""");
            EncounterParticipant participant = participant(mage);

            monsterPools.setUp(participant, mage);

            assertEquals(Map.of("1", new SpellSlot(4, 0), "3", new SpellSlot(2, 0)), participant.getSpellSlotsCurrent());
            assertEquals(6, participant.getSpellAttackBonus());
            assertEquals(14, participant.getSpellSaveDc());
            assertEquals("INT", participant.getSpellcastingAbility());
            List<KnownSpell> spells = participant.getSpellsKnown();
            assertEquals(List.of("Shocking Grasp", "Magic Missile", "Fireball", "Plane Shift", "Detect Magic"),
                    spells.stream().map(KnownSpell::name).toList());
            assertEquals(List.of(0, 1, 3, 7, 1), spells.stream().map(KnownSpell::level).toList());
            assertEquals(1, spells.get(3).usesPerLongRest());
            assertTrue(spells.get(4).atWill());
            assertEquals("monster", spells.get(0).source());
            assertEquals(List.of("innate:plane-shift"), poolIds(participant.getResourcePoolsCurrent()));
        }

        @Test
        @DisplayName("A monster without spellcasting gets none, and its slots stay unset")
        void noSpellcasting() {
            Monster ogre = monster("Ogre", null, null, "{\"actions\": [{\"name\": \"Greatclub\"}]}");
            EncounterParticipant participant = participant(ogre);

            monsterPools.setUp(participant, ogre);

            assertNull(participant.getSpellSlotsCurrent());
            assertNull(participant.getSpellsKnown());
            assertTrue(participant.getResourcePoolsCurrent().isEmpty());
        }

        @Test
        @DisplayName("Filling in a monster that joined without its spellcasting keeps whatever it has already spent")
        void fillIn() {
            Monster mage = monster("Mage", null, null, """
                    {"spellcasting": {"saveDC": 14, "attackBonus": 6, "slots": {"1": 4, "2": 3},
                     "spellsByLevel": {"1": ["magic missile"]}}}""");
            EncounterParticipant joined = participant(mage);
            joined.setSpellSlotsCurrent(Map.of("1", new SpellSlot(4, 3)));
            EncounterParticipant bare = participant(mage);

            monsterPools.fillIn(joined);
            monsterPools.fillIn(bare);

            assertEquals(Map.of("1", new SpellSlot(4, 3)), joined.getSpellSlotsCurrent());
            assertEquals(14, joined.getSpellSaveDc());
            assertEquals(List.of("Magic Missile"), joined.getSpellsKnown().stream().map(KnownSpell::name).toList());
            assertEquals(Map.of("1", new SpellSlot(4, 0), "2", new SpellSlot(3, 0)), bare.getSpellSlotsCurrent());
        }

        @Test
        @DisplayName("The Spell Sources read a monster's spells from its stat block, whatever the participant holds")
        void spellsForTheSources() {
            Monster djinni = monster("Djinni", null, null, """
                    {"spellcasting": {"innateSpells": {"atWill": ["detect magic"], "3/day each": ["fireball"]}}}""");

            List<SpellSources.Known> spells = monsterPools.spells(djinni);

            assertEquals(List.of(
                    new SpellSources.Known("Detect Magic", 1, "monster", false, false, true, null, null),
                    new SpellSources.Known("Fireball", 3, "monster", false, false, false, 3, null)), spells);
        }
    }

    @Nested
    @DisplayName("Reading the stat block's spells")
    class Spellbook {

        private MonsterSpellbook read(String templates) throws Exception {
            return MonsterSpellbook.read(objectMapper.readTree(templates));
        }

        @Test
        @DisplayName("Each daily key gives that many uses: 1e, 1/day, 3e/day each, 2/day each and a plain 2")
        void dailyKeys() {
            assertEquals(1, MonsterSpellbook.dailyUses("1e").orElseThrow());
            assertEquals(1, MonsterSpellbook.dailyUses("1/day").orElseThrow());
            assertEquals(3, MonsterSpellbook.dailyUses("3e/day each").orElseThrow());
            assertEquals(2, MonsterSpellbook.dailyUses("2/day each").orElseThrow());
            assertEquals(2, MonsterSpellbook.dailyUses("2").orElseThrow());
            assertTrue(MonsterSpellbook.dailyUses("at_will").isEmpty());
            assertTrue(MonsterSpellbook.dailyUses("0").isEmpty());
        }

        @Test
        @DisplayName("spellsByLevel lists levels, but its daily and at-will keys are read as such")
        void mixedSpellsByLevel() throws Exception {
            MonsterSpellbook book = read("""
                    {"spellcasting": {"spellsByLevel": {"0": ["light"], "2": ["shatter"], "1e/day": ["scrying"],
                     "at_will": ["detect thoughts"]}}}""");

            assertEquals(List.of(
                    new MonsterSpellbook.Entry("light", MonsterSpellbook.Kind.LEVELLED, 0, 0),
                    new MonsterSpellbook.Entry("shatter", MonsterSpellbook.Kind.LEVELLED, 2, 0),
                    new MonsterSpellbook.Entry("scrying", MonsterSpellbook.Kind.DAILY, null, 1),
                    new MonsterSpellbook.Entry("detect thoughts", MonsterSpellbook.Kind.AT_WILL, null, 0)),
                    book.spells());
            assertTrue(book.slots().isEmpty());
        }

        @Test
        @DisplayName("A name's notes in brackets and asterisks don't count when it is looked up")
        void lookupNames() {
            assertEquals("plane shift", new MonsterSpellbook.Entry("plane shift (self only)",
                    MonsterSpellbook.Kind.DAILY, null, 1).lookupName());
            assertEquals("shocking grasp", new MonsterSpellbook.Entry("shocking grasp*",
                    MonsterSpellbook.Kind.LEVELLED, 0, 0).lookupName());
            assertEquals("eldritch blast", new MonsterSpellbook.Entry(
                    "eldritch blast (range 300 ft., +3 bonus to each damage roll)", MonsterSpellbook.Kind.AT_WILL, null,
                    0).lookupName());
        }

        @Test
        @DisplayName("A monster without a spellcasting block has none")
        void none() throws Exception {
            assertFalse(read("{\"actions\": []}").present());
            assertFalse(MonsterSpellbook.read(null).present());
            assertTrue(read("{\"spellcasting\": {}}").present());
        }

        @Test
        @DisplayName("Slots below 1 are left out, and the spellcasting numbers are read when given")
        void slotsAndNumbers() throws Exception {
            MonsterSpellbook book = read("""
                    {"spellcasting": {"ability": "WIS", "saveDC": 13, "slots": {"1": 3, "2": 0, "3": 1}}}""");

            assertEquals(new ArrayList<>(List.of("1", "3")), new ArrayList<>(book.slots().keySet()));
            assertEquals("WIS", book.ability());
            assertEquals(13, book.saveDc());
            assertNull(book.attackBonus());
        }
    }
}
