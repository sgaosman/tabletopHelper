package com.tabletophelper.character.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.DerivedStats;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.AbilityScoreMaximums;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.character.HitPoints;
import com.tabletophelper.rules.character.TestClassTables;
import com.tabletophelper.rules.combat.DamageRules;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one derivation of a character's Derived Stats (D158, D167). The expected values are worked out by hand from the
 * PHB: hit points from each level's Hit Die Result and the current Constitution modifier (PHB p.15, p.177), the
 * class tables (PHB p.164) and the Spellcasting features. The Hit Dice cases took CharacterUpdatesTest's 7.4 to 7.6
 * and the one after them, now that the totals are worked out rather than counted up and down.
 */
class CharacterStatsTest {

    private final CharacterClassRepository classRepository = mock(CharacterClassRepository.class);
    private final SubclassRepository subclassRepository = mock(SubclassRepository.class);
    private final ClassFeaturePoolRepository featurePools = mock(ClassFeaturePoolRepository.class);
    private final CharacterStats stats = new CharacterStats(classRepository, subclassRepository,
            new ResourcePoolService(featurePools), new ObjectMapper(), TestClassTables.load());

    private final CharacterClass fighter = caster("Fighter", 10, null, false);
    private final CharacterClass wizard = caster("Wizard", 6, "INT", false);
    private final CharacterClass cleric = caster("Cleric", 8, "WIS", false);
    private final CharacterClass sorcerer = caster("Sorcerer", 6, "CHA", false);
    private final CharacterClass paladin = caster("Paladin", 10, "CHA", false);
    private final CharacterClass warlock = caster("Warlock", 8, "CHA", true);
    private final CharacterClass barbarian = caster("Barbarian", 12, null, false);

    private final Race human = Race.builder().id(UUID.randomUUID()).name("Human").speed("{\"walk\": 30}").build();

    private static CharacterClass caster(String name, int hitDie, String ability, boolean pactMagic) {
        return CharacterClass.builder().id(UUID.randomUUID()).name(name).hitDice(hitDie)
                .isSpellcaster(ability != null).isPactMagic(pactMagic).spellcastingAbility(ability).build();
    }

    @BeforeEach
    void knowTheClasses() {
        for (CharacterClass c : List.of(fighter, wizard, cleric, sorcerer, paladin, warlock, barbarian)) {
            lenient().when(classRepository.findById(c.getId())).thenReturn(Optional.of(c));
        }
        // A subclass a test names by ID only is one without effects
        lenient().when(subclassRepository.findById(any())).thenAnswer(call -> Optional.of(
                Subclass.builder().id(call.getArgument(0)).name("A subclass").build()));
    }

    /** The class levels in the order taken, as pairs of a class and a count, each level at its fixed hit die result. */
    private static List<LevelHistoryEntry> history(Object... classesAndCounts) {
        List<LevelHistoryEntry> history = new ArrayList<>();
        for (int i = 0; i < classesAndCounts.length; i += 2) {
            CharacterClass cls = (CharacterClass) classesAndCounts[i];
            int count = (Integer) classesAndCounts[i + 1];
            for (int classLevel = 1; classLevel <= count; classLevel++) {
                int characterLevel = history.size() + 1;
                history.add(new LevelHistoryEntry(characterLevel, cls.getId(), cls.getName(), classLevel, 0,
                        HitPoints.fixedHitDieResult(characterLevel, cls.getHitDice()), List.of(), LevelChoices.NONE,
                        null, null));
            }
        }
        return history;
    }

    private static List<ClassEntry> classes(Object... classesAndLevels) {
        List<ClassEntry> entries = new ArrayList<>();
        for (int i = 0; i < classesAndLevels.length; i += 2) {
            CharacterClass cls = (CharacterClass) classesAndLevels[i];
            entries.add(new ClassEntry(cls.getId(), cls.getName(), null, null, (Integer) classesAndLevels[i + 1]));
        }
        return entries;
    }

    /** A character with these classes and levels: STR 10, DEX 14, CON 14, INT 16, WIS 12, CHA 8. */
    private PlayerCharacter character(Object... classesAndLevels) {
        List<LevelHistoryEntry> history = history(classesAndLevels);
        return PlayerCharacter.builder()
                .id(UUID.randomUUID()).name("Aria").raceRef(human).characterClass(
                        ((CharacterClass) classesAndLevels[0]).getName())
                .level(history.size())
                .strength(10).dexterity(14).constitution(14).intelligence(16).wisdom(12).charisma(8)
                .multiclassEntries(classes(classesAndLevels))
                .levelHistory(history)
                .build();
    }

    @Nested
    @DisplayName("Hit points")
    class HitPointMaximum {

        @Test
        @DisplayName("Each level's hit die result plus the current CON modifier: Fighter 3 with CON 14 has 10+2, 6+2 and 6+2")
        void fromHitDieResults() {
            assertThat(stats.derive(character(fighter, 3)).hpMax()).isEqualTo(28);
        }

        @Test
        @DisplayName("A higher Constitution counts for every level, as though the character had it from 1st level")
        void constitutionCountsForEveryLevel() {
            PlayerCharacter pc = character(fighter, 3);
            pc.setConstitution(16);

            assertThat(stats.derive(pc).hpMax()).isEqualTo(31);
        }

        @Test
        @DisplayName("Each level gives at least 1, even with CON 3")
        void atLeastOneALevel() {
            PlayerCharacter pc = character(wizard, 3);
            pc.setConstitution(3);

            // 6 - 4 = 2, then 4 - 4 = 0 twice, which count as 1
            assertThat(stats.derive(pc).hpMax()).isEqualTo(4);
        }

        @Test
        @DisplayName("Tough, from the hpPerLevel its feat data recorded, adds 2 for every level, those before it was taken included")
        void tough() {
            PlayerCharacter pc = character(fighter, 4);
            pc.setFeatures(List.of(new Feature("Tough", "Feat", "Feat")));
            AppliedFeatEffects tough = new AppliedFeatEffects(null, null, null, null, null, null, null, null, null,
                    null, null, 2, null, null, null, null, "Tough");
            List<LevelHistoryEntry> history = new ArrayList<>(pc.getLevelHistory());
            history.set(3, history.get(3).withChoices(LevelChoices.NONE.withAsi(
                    new AbilityScoreImprovement("feat", null, "Tough", null, null, tough, null))));
            pc.setLevelHistory(history);

            assertThat(stats.derive(pc).hpMax()).isEqualTo(36 + 8);
        }

        @Test
        @DisplayName("A Hill Dwarf adds 1 a level, and the Draconic Bloodline 1 a sorcerer level only, from their effects")
        void hillDwarfAndDraconicBloodline() {
            PlayerCharacter dwarf = character(fighter, 2);
            dwarf.setRaceRef(Race.builder().id(UUID.randomUUID()).name("Hill Dwarf").speed("{\"walk\": 25}")
                    .effects("{\"hpPerLevel\": 1}").build());
            PlayerCharacter dragon = character(fighter, 2, sorcerer, 3);
            Subclass draconic = Subclass.builder().id(UUID.randomUUID()).name("Draconic Bloodline")
                    .characterClass(sorcerer).effects("{\"hpPerClassLevel\": 1}").build();
            when(subclassRepository.findById(draconic.getId())).thenReturn(Optional.of(draconic));
            dragon.setMulticlassEntries(List.of(new ClassEntry(fighter.getId(), "Fighter", null, null, 2),
                    new ClassEntry(sorcerer.getId(), "Sorcerer", draconic.getId(), "Draconic Bloodline", 3)));

            assertThat(stats.derive(dwarf).hpMax()).isEqualTo(12 + 8 + 2);
            // 12 + 8, then three sorcerer levels of 4 + 2, with 1 each from Draconic Resilience
            assertThat(stats.derive(dragon).hpMax()).isEqualTo(20 + 18 + 3);
        }

        @Test
        @DisplayName("A race or subclass gets its hit points from its effects, whatever its name: a renamed race with the effect has them, a Hill Dwarf without it none")
        void effectsNotNames() {
            PlayerCharacter renamed = character(fighter, 2);
            renamed.setRaceRef(Race.builder().id(UUID.randomUUID()).name("Dwarf (Hill)").speed("{\"walk\": 25}")
                    .effects("{\"hpPerLevel\": 1}").build());
            PlayerCharacter unmarked = character(fighter, 2);
            unmarked.setRaceRef(Race.builder().id(UUID.randomUUID()).name("Hill Dwarf").speed("{\"walk\": 25}")
                    .build());
            PlayerCharacter sorcererByName = character(sorcerer, 3);
            sorcererByName.setMulticlassEntries(List.of(
                    new ClassEntry(sorcerer.getId(), "Sorcerer", UUID.randomUUID(), "Draconic Bloodline", 3)));

            assertThat(stats.derive(renamed).hpMax()).isEqualTo(12 + 8 + 2);
            assertThat(stats.derive(unmarked).hpMax()).isEqualTo(12 + 8);
            // 6 + 2, then 4 + 2 twice: the subclass named Draconic Bloodline here has no effects
            assertThat(stats.derive(sorcererByName).hpMax()).isEqualTo(20);
        }

        @Test
        @DisplayName("A race whose effects can't be read is a 409, not a missing bonus")
        void unreadableEffectsAreConflict() {
            PlayerCharacter pc = character(fighter, 1);
            pc.setRaceRef(Race.builder().id(UUID.randomUUID()).name("Hill Dwarf").speed("{\"walk\": 25}")
                    .effects("{\"hpPerLevel\": \"one\"}").build());

            assertThatThrownBy(() -> stats.derive(pc)).isInstanceOf(ConflictException.class)
                    .hasMessageContaining("The effects of the race Hill Dwarf can't be read");
        }

        @Test
        @DisplayName("A level recorded without a hit die result counts as the fixed value, the die's maximum only at character level 1")
        void missingHitDieResultIsTheFixedValue() {
            PlayerCharacter pc = character(fighter, 1, wizard, 1);
            List<LevelHistoryEntry> history = new ArrayList<>();
            for (LevelHistoryEntry entry : pc.getLevelHistory()) {
                history.add(new LevelHistoryEntry(entry.characterLevel(), entry.classId(), entry.className(),
                        entry.classLevel(), 0, null, List.of(), LevelChoices.NONE, null, null));
            }
            pc.setLevelHistory(history);

            // 10 + 2, then the d6's fixed 4 + 2 for the Wizard's first level
            assertThat(stats.derive(pc).hpMax()).isEqualTo(18);
        }

        @Test
        @DisplayName("A character without level history can't be worked out: a 409")
        void noHistoryIsConflict() {
            PlayerCharacter pc = character(fighter, 1);
            pc.setLevelHistory(List.of());

            assertThatThrownBy(() -> stats.derive(pc)).isInstanceOf(ConflictException.class)
                    .hasMessage("Aria has no level history to work its hit points out from");
        }
    }

    @Nested
    @DisplayName("Proficiency, initiative and speed")
    class ProficiencyInitiativeAndSpeed {

        @Test
        @DisplayName("The proficiency bonus follows the character level")
        void proficiencyBonus() {
            assertThat(stats.derive(character(fighter, 4)).proficiencyBonus()).isEqualTo(2);
            assertThat(stats.derive(character(fighter, 3, wizard, 2)).proficiencyBonus()).isEqualTo(3);
        }

        @Test
        @DisplayName("Initiative is the DEX modifier plus the bonus Alert recorded; speed the race's plus Mobile's")
        void initiativeAndSpeed() {
            PlayerCharacter pc = character(fighter, 4);
            AppliedFeatEffects alert = new AppliedFeatEffects(null, null, null, null, null, null, null, null, null,
                    null, 5, null, null, null, null, null, "Alert");
            AppliedFeatEffects mobile = new AppliedFeatEffects(null, null, null, null, null, null, null, null, null,
                    10, null, null, null, null, null, null, "Mobile");
            List<LevelHistoryEntry> history = new ArrayList<>(pc.getLevelHistory());
            history.set(3, history.get(3).withChoices(LevelChoices.NONE.withAsi(
                    new AbilityScoreImprovement("feat", null, "Alert", null, null, alert, null))));
            pc.setLevelHistory(history);

            DerivedStats alerted = stats.derive(pc);
            history.set(3, history.get(3).withChoices(LevelChoices.NONE.withAsi(
                    new AbilityScoreImprovement("feat", null, "Mobile", null, null, mobile, null))));
            pc.setLevelHistory(history);
            pc.setDexterity(9);

            assertThat(alerted.initiativeBonus()).isEqualTo(2 + 5);
            assertThat(alerted.speed()).isEqualTo(30);
            assertThat(stats.derive(pc).initiativeBonus()).isEqualTo(-1);
            assertThat(stats.derive(pc).speed()).isEqualTo(40);
        }

        @Test
        @DisplayName("A race's speed may be a plain number; one that can't be read is a 409")
        void raceSpeed() {
            PlayerCharacter pc = character(fighter, 1);
            pc.setRaceRef(Race.builder().name("Dwarf").speed("25").build());
            assertThat(stats.derive(pc).speed()).isEqualTo(25);

            pc.setRaceRef(Race.builder().name("Ghost").speed("{\"fly\": 30}").build());
            assertThatThrownBy(() -> stats.derive(pc)).isInstanceOf(ConflictException.class)
                    .hasMessage("The speed of the race Ghost can't be read: {\"fly\": 30}");
        }
    }

    @Nested
    @DisplayName("Spell slots and Hit Dice")
    class SlotsAndHitDice {

        @Test
        @DisplayName("A Wizard 5 has 4/3/2 slots; used counts are kept and capped at the new maximum")
        void slotsKeepUsedCounts() {
            PlayerCharacter pc = character(wizard, 5);
            pc.setSpellSlots(Map.of("1", new SpellSlot(4, 3), "2", new SpellSlot(3, 3), "3", new SpellSlot(5, 5)));

            assertThat(stats.derive(pc).spellSlots()).isEqualTo(Map.of(
                    "1", new SpellSlot(4, 3), "2", new SpellSlot(3, 3), "3", new SpellSlot(2, 2)));
        }

        @Test
        @DisplayName("A warlock's pact slots keep their used count when they go up a level; a Fighter has none")
        void pactSlotsCarryUsedCount() {
            PlayerCharacter pc = character(warlock, 3);
            pc.setSpellSlots(Map.of("pact_1", new SpellSlot(2, 1)));

            assertThat(stats.derive(pc).spellSlots()).isEqualTo(Map.of("pact_2", new SpellSlot(2, 1)));
            assertThat(stats.derive(character(fighter, 5)).spellSlots()).isNull();
        }

        @Test
        @DisplayName("Hit Dice: one per level in each class, so a level up adds a die to the total and the remaining alike")
        void hitDiceOnLevelUp() {
            PlayerCharacter pc = character(fighter, 4);
            // Fighter 3 had 3d10 with 2 spent
            pc.setHitDice(Map.of("Fighter", new HitDicePool(10, 3, 1)));

            assertThat(stats.derive(pc).hitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 4, 2)));
        }

        @Test
        @DisplayName("Hit Dice: a level down caps the dice spent, a class left goes, and a new class starts full")
        void hitDiceOnLevelDownAndMulticlass() {
            PlayerCharacter down = character(fighter, 2);
            down.setHitDice(Map.of("Fighter", new HitDicePool(10, 3, 0), "Wizard", new HitDicePool(6, 1, 1)));
            PlayerCharacter dip = character(fighter, 3, wizard, 1);
            dip.setHitDice(Map.of("Fighter", new HitDicePool(10, 3, 3)));

            assertThat(stats.derive(down).hitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 2, 0)));
            Map<String, HitDicePool> both = new LinkedHashMap<>();
            both.put("Fighter", new HitDicePool(10, 3, 3));
            both.put("Wizard", new HitDicePool(6, 1, 1));
            assertThat(stats.derive(dip).hitDice()).containsExactlyEntriesOf(both);
        }
    }

    @Nested
    @DisplayName("Spellcasting")
    class Spellcasting {

        @Test
        @DisplayName("A Wizard 5 with INT 16: DC 14, +6, 3rd-level spells, 4 cantrips and INT + 5 prepared")
        void wizard() {
            ClassSpellcasting wizard5 = stats.derive(character(wizard, 5)).spellcasting().getFirst();

            assertThat(wizard5).isEqualTo(new ClassSpellcasting("Wizard", null, 5, "INT", 14, 6, 3, 4, null, 8, false));
        }

        @Test
        @DisplayName("Each class of a multiclass caster has its own ability, DC and attack bonus, from its own table")
        void multiclassCasterHasEachClassNumbers() {
            DerivedStats derived = stats.derive(character(wizard, 3, cleric, 2));

            assertThat(derived.spellcasting()).containsExactly(
                    new ClassSpellcasting("Wizard", null, 3, "INT", 14, 6, 2, 3, null, 6, false),
                    new ClassSpellcasting("Cleric", null, 2, "WIS", 12, 4, 1, 3, null, 3, false));
            // Two Spellcasting Classes use the multiclass table: caster level 5
            assertThat(derived.spellSlots()).containsOnlyKeys("1", "2", "3");
        }

        @Test
        @DisplayName("A Paladin 1 prepares no spells and has none to learn, and a Warlock's are Pact Magic")
        void paladinAndWarlock() {
            assertThat(stats.derive(character(paladin, 1)).spellcasting()).containsExactly(
                    new ClassSpellcasting("Paladin", null, 1, "CHA", 9, 1, 0, 0, null, 0, false));
            assertThat(stats.derive(character(warlock, 2)).spellcasting()).containsExactly(
                    new ClassSpellcasting("Warlock", null, 2, "CHA", 9, 1, 1, 2, 3, null, true));
        }

        @Test
        @DisplayName("An Eldritch Knight casts with INT from Fighter 3, knowing 3 spells of 1st level and 2 cantrips")
        void eldritchKnight() {
            PlayerCharacter pc = character(fighter, 3);
            pc.setMulticlassEntries(List.of(new ClassEntry(fighter.getId(), "Fighter", UUID.randomUUID(),
                    "Eldritch Knight", 3)));

            DerivedStats derived = stats.derive(pc);

            assertThat(derived.spellcasting()).containsExactly(
                    new ClassSpellcasting("Fighter", "Eldritch Knight", 3, "INT", 13, 5, 1, 2, 3, null, false));
            assertThat(derived.spellSlots()).isEqualTo(Map.of("1", new SpellSlot(2, 0)));
        }

        @Test
        @DisplayName("The main numbers use the ability chosen for race and feat spells, or else the first casting class's")
        void mainSpellNumbers() {
            PlayerCharacter fairy = character(barbarian, 6);
            fairy.setSpellcastingAbility("WIS");
            PlayerCharacter dip = character(fighter, 3, wizard, 1);

            DerivedStats fairyStats = stats.derive(fairy);
            DerivedStats dipStats = stats.derive(dip);

            // WIS 12: +1 with a +3 proficiency bonus
            assertThat(fairyStats.spellcastingAbility()).isEqualTo("WIS");
            assertThat(fairyStats.spellSaveDc()).isEqualTo(12);
            assertThat(fairyStats.spellAttackBonus()).isEqualTo(4);
            assertThat(dipStats.spellcastingAbility()).isEqualTo("INT");
            assertThat(dipStats.spellSaveDc()).isEqualTo(13);
            DerivedStats none = stats.derive(character(fighter, 2));
            assertThat(none.spellcastingAbility()).isNull();
            assertThat(none.spellSaveDc()).isNull();
            assertThat(none.spellAttackBonus()).isNull();
        }

        @Test
        @DisplayName("More cantrips than the class knows is a warning, and changes nothing")
        void tooManyCantripsIsAWarning() {
            PlayerCharacter pc = character(cleric, 9);
            List<KnownSpell> cantrips = new ArrayList<>();
            for (String name : List.of("Guidance", "Light", "Mending", "Sacred Flame", "Thaumaturgy")) {
                cantrips.add(new KnownSpell(null, name, 0, "class:Cleric", null, null, null, null, null));
            }
            pc.setSpellsKnown(cantrips);

            assertThat(stats.derive(pc).warnings())
                    .containsExactly("knows 5 Cleric cantrips, but a level 9 Cleric knows 4");
            assertThat(pc.getSpellsKnown()).hasSize(5);
        }
    }

    @Nested
    @DisplayName("Ability scores")
    class AbilityScoreMaximum {

        @Test
        @DisplayName("Each score's maximum is 20, and 24 for STR and CON once a barbarian has Primal Champion")
        void maximums() {
            assertThat(stats.derive(character(barbarian, 19)).abilityScoreMaximums())
                    .isEqualTo(new AbilityScoreMaximums(20, 20, 20, 20, 20, 20));
            assertThat(stats.derive(character(barbarian, 20)).abilityScoreMaximums())
                    .isEqualTo(new AbilityScoreMaximums(24, 20, 24, 20, 20, 20));
            assertThat(stats.derive(character(fighter, 1)).abilityScores()).containsEntry(Ability.INT, 16);
        }
    }

    @Nested
    @DisplayName("Pools")
    class Pools {

        @Test
        @DisplayName("Each levelled spell with uses of its own that the character can cast now has an innate pool, keeping its uses (D165)")
        void innatePools() {
            PlayerCharacter pc = character(barbarian, 4);
            pc.setSpellsKnown(List.of(
                    new KnownSpell(null, "Druidcraft", 0, "race:Fairy", null, null, true, null, 1),
                    new KnownSpell(null, "Faerie Fire", 1, "race:Fairy", null, null, null, 1, 3),
                    new KnownSpell(null, "Enlarge/Reduce", 2, "race:Fairy", null, null, null, 1, 5),
                    new KnownSpell(null, "Absorb Elements", 1, "feat:Magic Initiate", null, null, null, 1, null)));
            pc.setResourcePools(List.of(ResourcePoolEntry.innate("Faerie Fire", 1, "RACE", "Fairy").withCurrentUses(0)));

            List<ResourcePoolEntry> pools = stats.derive(pc).resourcePools();

            assertThat(pools).extracting(ResourcePoolEntry::poolId)
                    .containsExactly("innate:faerie-fire", "innate:absorb-elements");
            assertThat(pools.getFirst().currentUses()).isZero();
            assertThat(pools.get(1)).isEqualTo(ResourcePoolEntry.innate("Absorb Elements", 1, "FEAT", "Magic Initiate"));
        }
    }

    @Nested
    @DisplayName("DM Adjustments (D158)")
    class Adjustments {

        private static final UUID DM = UUID.randomUUID();
        private static final Instant ADDED = Instant.parse("2026-11-03T10:00:00Z");

        private static DmAdjustment stat(String stat, int amount) {
            return new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.STAT, stat, amount, null, null, null,
                    "A magic item", DM, ADDED, null, null);
        }

        private static DmAdjustment granted(AppliedFeatEffects effects) {
            return new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.FEAT, effects.featName(), null, null,
                    UUID.randomUUID(), effects, "A boon", DM, ADDED, null, null);
        }

        private static AppliedFeatEffects feat(String name, Integer speed, Integer initiative,
                                               FeatResourceEntry resource) {
            return new AppliedFeatEffects(null, null, null, null, null, null, null, null, null, speed, initiative,
                    null, null, null, resource, null, name);
        }

        @Test
        @DisplayName("Ability adjustments apply first: a Wizard 5's CON +2 counts for every level, DEX +2 reaches initiative and INT +2 the DC, attack bonus and prepared spells")
        void abilityAdjustmentsApplyFirst() {
            PlayerCharacter pc = character(wizard, 5);
            pc.setDmAdjustments(List.of(stat("CON", 2), stat("DEX", 2), stat("INT", 2)));

            DerivedStats derived = stats.derive(pc);

            // 6+3, then 4+3 for each of the other four levels
            assertThat(derived.hpMax()).isEqualTo(37);
            assertThat(derived.initiativeBonus()).isEqualTo(3);
            assertThat(derived.abilityScores()).containsEntry(Ability.CON, 16).containsEntry(Ability.DEX, 16)
                    .containsEntry(Ability.INT, 18);
            ClassSpellcasting casting = derived.spellcasting().getFirst();
            assertThat(casting.spellSaveDc()).isEqualTo(15);
            assertThat(casting.spellAttackBonus()).isEqualTo(7);
            assertThat(casting.preparedSpells()).isEqualTo(4 + 5);
            assertThat(derived.spellSaveDc()).isEqualTo(15);
            // The sheet's own scores are untouched
            assertThat(pc.getConstitution()).isEqualTo(14);
        }

        @Test
        @DisplayName("An ability adjustment may take a score past its maximum of 20, but never above 30 or below 1")
        void abilityAdjustmentsStayWithinOneToThirty() {
            PlayerCharacter pc = character(fighter, 1);
            pc.setDmAdjustments(List.of(stat("STR", 12), stat("STR", 13), stat("CHA", -10), stat("WIS", 9)));

            DerivedStats derived = stats.derive(pc);

            assertThat(derived.abilityScores()).containsEntry(Ability.STR, 30).containsEntry(Ability.CHA, 1)
                    .containsEntry(Ability.WIS, 21);
            assertThat(derived.abilityScoreMaximums().strength()).isEqualTo(20);
        }

        @Test
        @DisplayName("Hit point, initiative and speed adjustments add on top; speed stops at 0 and the maximum at 1")
        void flatAdjustments() {
            PlayerCharacter pc = character(fighter, 3);
            pc.setDmAdjustments(List.of(stat("HP_MAX", 5), stat("INITIATIVE", 2), stat("SPEED", 10),
                    stat("HP_MAX", 2)));
            PlayerCharacter cursed = character(fighter, 3);
            cursed.setDmAdjustments(List.of(stat("HP_MAX", -100), stat("SPEED", -50)));

            assertThat(stats.derive(pc).hpMax()).isEqualTo(28 + 7);
            assertThat(stats.derive(pc).initiativeBonus()).isEqualTo(2 + 2);
            assertThat(stats.derive(pc).speed()).isEqualTo(40);
            assertThat(stats.derive(cursed).hpMax()).isEqualTo(1);
            assertThat(stats.derive(cursed).speed()).isZero();
        }

        @Test
        @DisplayName("Spell save DC and attack bonus adjustments apply to every class's numbers and to the main ones")
        void spellAdjustmentsApplyToEveryClass() {
            PlayerCharacter pc = character(wizard, 3, cleric, 2);
            pc.setDmAdjustments(List.of(stat("SPELL_SAVE_DC", 1), stat("SPELL_ATTACK_BONUS", 2)));

            DerivedStats derived = stats.derive(pc);

            assertThat(derived.spellcasting()).extracting(ClassSpellcasting::spellSaveDc).containsExactly(15, 13);
            assertThat(derived.spellcasting()).extracting(ClassSpellcasting::spellAttackBonus).containsExactly(8, 6);
            assertThat(derived.spellSaveDc()).isEqualTo(15);
            assertThat(derived.spellAttackBonus()).isEqualTo(8);
            assertThat(stats.spellcasting(pc)).isEqualTo(derived.spellcasting());
        }

        @Test
        @DisplayName("A removed adjustment counts for nothing, and an AC or DEFENCE adjustment changes no Derived Stat")
        void removedAndOtherKindsDontCount() {
            PlayerCharacter pc = character(fighter, 3);
            DerivedStats before = stats.derive(pc);
            pc.setDmAdjustments(List.of(stat("CON", 4).removed(DM, ADDED), stat("ARMOUR_CLASS", 2),
                    new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.DEFENCE, "fire", null,
                            DamageRules.Defence.RESISTANCE, null, null, "A ring", DM, ADDED, null, null)));

            assertThat(stats.derive(pc)).isEqualTo(before);
        }

        @Test
        @DisplayName("A feat the DM granted counts as one a level took: Alert's initiative, Mobile's speed and Lucky's pool, until the grant is removed")
        void grantedFeatsCount() {
            PlayerCharacter pc = character(fighter, 3);
            DmAdjustment lucky = granted(feat("Lucky", null, null,
                    new FeatResourceEntry("Lucky", "Luck Points", 3, 3, "longRest")));
            pc.setDmAdjustments(List.of(granted(feat("Alert", null, 5, null)), granted(feat("Mobile", 10, null, null)),
                    lucky));

            DerivedStats granted = stats.derive(pc);
            pc.setDmAdjustments(List.of(lucky.removed(DM, ADDED)));
            DerivedStats removed = stats.derive(pc);

            assertThat(granted.initiativeBonus()).isEqualTo(2 + 5);
            assertThat(granted.speed()).isEqualTo(40);
            assertThat(granted.resourcePools())
                    .containsExactly(ResourcePoolEntry.feat("Lucky", "Luck Points", 3, "longRest"));
            assertThat(removed.initiativeBonus()).isEqualTo(2);
            assertThat(removed.speed()).isEqualTo(30);
            assertThat(removed.resourcePools()).isEmpty();
        }

        @Test
        @DisplayName("Applying a hit point maximum adjustment raises current hit points by as much, and removing it caps them (D167)")
        void currentHitPointsFollowAnAdjustedMaximum() {
            PlayerCharacter pc = character(fighter, 3);
            stats.apply(pc);
            pc.setHpCurrent(28);
            DmAdjustment amulet = stat("HP_MAX", 5);
            pc.setDmAdjustments(List.of(amulet));

            stats.apply(pc);
            assertThat(pc.getHpMax()).isEqualTo(33);
            assertThat(pc.getHpCurrent()).isEqualTo(33);

            pc.setDmAdjustments(List.of(amulet.removed(DM, ADDED)));
            stats.apply(pc);
            assertThat(pc.getHpMax()).isEqualTo(28);
            assertThat(pc.getHpCurrent()).isEqualTo(28);
        }
    }

    @Nested
    @DisplayName("Applying")
    class Applying {

        @Test
        @DisplayName("Deriving changes nothing; applying writes the stored copies")
        void applyWritesStoredCopies() {
            PlayerCharacter pc = character(wizard, 5);
            pc.setHpMax(1);
            pc.setHpCurrent(1);
            pc.setProficiencyBonus(2);
            pc.setInitiativeBonus(0);

            stats.derive(pc);
            assertThat(pc.getProficiencyBonus()).isEqualTo(2);

            stats.apply(pc);

            assertThat(pc.getHpMax()).isEqualTo(32);
            assertThat(pc.getProficiencyBonus()).isEqualTo(3);
            assertThat(pc.getInitiativeBonus()).isEqualTo(2);
            assertThat(pc.getSpeed()).isEqualTo(30);
            assertThat(pc.getSpellSlots()).containsOnlyKeys("1", "2", "3");
            assertThat(pc.getHitDice()).isEqualTo(Map.of("Wizard", new HitDicePool(6, 5, 5)));
            assertThat(pc.getSpellSaveDc()).isEqualTo(14);
            assertThat(pc.getSpellAttackBonus()).isEqualTo(6);
        }

        @Test
        @DisplayName("A higher maximum raises current hit points by as much, and a lower one caps them (D167)")
        void currentHitPointsFollowTheMaximum() {
            PlayerCharacter raised = character(fighter, 3);
            raised.setHpMax(20);
            raised.setHpCurrent(10);
            PlayerCharacter lowered = character(fighter, 3);
            lowered.setHpMax(40);
            lowered.setHpCurrent(35);
            PlayerCharacter hurt = character(fighter, 3);
            hurt.setHpMax(40);
            hurt.setHpCurrent(5);

            stats.apply(raised);
            stats.apply(lowered);
            stats.apply(hurt);

            assertThat(raised.getHpCurrent()).isEqualTo(18);
            assertThat(lowered.getHpCurrent()).isEqualTo(28);
            assertThat(hurt.getHpCurrent()).isEqualTo(5);
        }

        @Test
        @DisplayName("A class the sheet names that doesn't exist is a 409")
        void missingClassIsConflict() {
            PlayerCharacter pc = character(fighter, 1);
            pc.setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Psion", null, null, 1)));

            assertThatThrownBy(() -> stats.apply(pc)).isInstanceOf(ConflictException.class)
                    .hasMessage("The class Psion that the character has can't be found");
        }
    }
}
