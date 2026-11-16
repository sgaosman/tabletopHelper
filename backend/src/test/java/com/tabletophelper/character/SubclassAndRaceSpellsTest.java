package com.tabletophelper.character;

import com.tabletophelper.character.dto.CharacterCreateRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.PendingChoice.Type;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * D231: what a subclass grants besides its features, and a race's spells as the race loader gives them now. A Circle of
 * the Land druid picks its terrain, whose circle spells only it has always prepared (PHB p.68-69); the subclasses whose
 * spells the data marks known teach them at their class levels, from "subclass:" and the subclass's name, and levelling
 * down forgets them (a Gloom Stalker's, XGE p.42; a Divine Soul's affinity spell, XGE p.50); fixed subclass cantrips are
 * granted unless known (Light Domain, PHB p.60) and a cantrip may be chosen by school (Death Domain, DMG p.96); and the
 * recompute-characters job brings existing characters' subclass and race spells up to the data.
 */
class SubclassAndRaceSpellsTest {

    private final CharacterFixture f = new CharacterFixture();
    private final Map<String, Spell> spells = new HashMap<>();

    private final CharacterClass druid = CharacterClass.builder().id(UUID.randomUUID()).name("Druid").hitDice(8)
            .subclassLevel(2).isSpellcaster(true).isPactMagic(false).spellcastingAbility("WIS")
            .multiclassRequirements("[{\"ability\":\"WIS\",\"minimum\":13}]").build();
    private final CharacterClass ranger = CharacterClass.builder().id(UUID.randomUUID()).name("Ranger").hitDice(10)
            .subclassLevel(3).isSpellcaster(true).isPactMagic(false).spellcastingAbility("WIS")
            .multiclassRequirements("[{\"ability\":\"DEX\",\"minimum\":13},{\"ability\":\"WIS\",\"minimum\":13}]")
            .build();

    // As the class loader stores them (version 9)
    private final Subclass land = Subclass.builder().id(UUID.randomUUID()).name("Circle of the Land")
            .characterClass(druid)
            .additionalProficiencies("{\"cantrips\":[{\"count\":1,\"spellList\":\"Druid\"}]}")
            .grantedSpells("{\"options\":["
                    + "{\"name\":\"Arctic\",\"prepared\":{\"3\":[\"hold person\",\"spike growth\"],"
                    + "\"5\":[\"sleet storm\",\"slow\"]}},"
                    + "{\"name\":\"Underdark\",\"prepared\":{\"3\":[\"spider climb\",\"web\"],"
                    + "\"5\":[\"gaseous form\",\"stinking cloud\"]}}]}")
            .build();
    private final Subclass gloomStalker = Subclass.builder().id(UUID.randomUUID()).name("Gloom Stalker")
            .characterClass(ranger)
            .grantedSpells("{\"known\":{\"3\":[\"disguise self\"],\"5\":[\"rope trick\"]}}")
            .build();
    private final Subclass divineSoul = Subclass.builder().id(UUID.randomUUID()).name("Divine Soul")
            .characterClass(f.sorcerer)
            .grantedSpells("{\"options\":[{\"name\":\"Good\",\"known\":{\"1\":[\"cure wounds\"]}},"
                    + "{\"name\":\"Evil\",\"known\":{\"1\":[\"inflict wounds\"]}}]}")
            .build();
    private final Subclass lightDomain = Subclass.builder().id(UUID.randomUUID()).name("Light Domain")
            .characterClass(f.cleric)
            .alwaysPreparedSpells("{\"1\":[\"burning hands\",\"faerie fire\"]}")
            .additionalProficiencies("{\"cantrips\":[{\"count\":1,\"grant\":\"Light\"}]}")
            .build();
    private final Subclass deathDomain = Subclass.builder().id(UUID.randomUUID()).name("Death Domain")
            .characterClass(f.cleric)
            .additionalProficiencies("{\"weapons\":[\"martial\"],\"cantrips\":[{\"count\":1,\"school\":\"Necromancy\"}]}")
            .build();

    @BeforeEach
    void knowTheReferenceData() {
        spell("Disguise Self", 1, "Illusion", "Bard", "Sorcerer", "Wizard");
        spell("Rope Trick", 2, "Transmutation", "Wizard");
        spell("Cure Wounds", 1, "Evocation", "Cleric", "Druid", "Ranger");
        spell("Inflict Wounds", 1, "Necromancy", "Cleric");
        spell("Light", 0, "Evocation", "Cleric", "Wizard");
        spell("Chill Touch", 0, "Necromancy", "Wizard");
        spell("Toll the Dead", 0, "Necromancy", "Cleric", "Wizard");
        spell("Fire Bolt", 0, "Evocation", "Wizard");
        spell("Druidcraft", 0, "Transmutation", "Druid");
        spell("Hex", 1, "Enchantment", "Warlock");
        spell("Detect Magic", 1, "Divination", "Wizard");
        lenient().when(f.spellRepository.findByNameIgnoreCase(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(spells.get(inv.<String>getArgument(0).toLowerCase(Locale.ROOT))));
        for (CharacterClass c : List.of(druid, ranger)) {
            lenient().when(f.classRepository.findById(c.getId())).thenReturn(Optional.of(c));
        }
        lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(druid.getId())).thenReturn(List.of(land));
        lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(ranger.getId()))
                .thenReturn(List.of(gloomStalker));
        lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.sorcerer.getId()))
                .thenReturn(List.of(divineSoul));
        lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.cleric.getId()))
                .thenReturn(List.of(deathDomain, lightDomain));
        for (Subclass subclass : List.of(land, gloomStalker, divineSoul, lightDomain, deathDomain)) {
            lenient().when(f.subclassRepository.findById(subclass.getId())).thenReturn(Optional.of(subclass));
        }
    }

    private void spell(String name, int level, String school, String... classes) {
        String lists = "[" + String.join(",", List.of(classes).stream().map(c -> "\"" + c + "\"").toList()) + "]";
        spells.put(name.toLowerCase(Locale.ROOT), Spell.builder().id(UUID.randomUUID()).name(name).level(level)
                .school(school).classes(lists).build());
    }

    private PlayerCharacter create(Race race, CharacterClass characterClass, int level,
                                   Consumer<CharacterCreateRequest> adjust) {
        CharacterCreateRequest request = new CharacterCreateRequest();
        request.setName("Aria");
        request.setRaceId(race.getId());
        int[] scores = {10, 14, 12, 10, 16, 13};
        request.setStrength(scores[0]);
        request.setDexterity(scores[1]);
        request.setConstitution(scores[2]);
        request.setIntelligence(scores[3]);
        request.setWisdom(scores[4]);
        request.setCharisma(scores[5]);
        request.setClassId(characterClass.getId());
        request.setLevel(level);
        adjust.accept(request);
        return f.stored(f.service.createCharacter(request, f.userId).getId());
    }

    private static List<String> names(PlayerCharacter pc, String source) {
        return pc.getSpellsKnown().stream().filter(s -> source.equals(s.source())).map(KnownSpell::name).toList();
    }

    @Nested
    @DisplayName("Circle of the Land terrain (PHB p.68-69)")
    class LandTerrain {

        private PlayerCharacter landDruid() {
            PlayerCharacter pc = create(f.human, druid, 2, request -> {});
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(land.getId()));
            return pc;
        }

        @Test
        @DisplayName("Choosing the circle owes a terrain choice of its terrains, and until it is made the druid has "
                + "no circle spells")
        void owedWithTheSubclass() {
            PlayerCharacter pc = landDruid();

            PendingChoice terrain = f.pending(pc, Type.SUBCLASS_OPTION);
            assertThat(terrain.options()).containsExactly("Arctic", "Underdark");
            assertThat(terrain.characterLevel()).isEqualTo(2);
            assertThat(terrain.classLevel()).isEqualTo(2);
            assertThat(f.service.getCharacter(pc.getId(), f.userId).getSubclassAlwaysPreparedSpells()).isNull();
        }

        @Test
        @DisplayName("The terrain picked is recorded on Druid 2, and only its circle spells are always prepared, the "
                + "3rd level's from Druid 3; another answer is a 400 and a second pick a 409")
        void pickedTerrainsSpells() {
            PlayerCharacter pc = landDruid();
            PendingChoice terrain = f.pending(pc, Type.SUBCLASS_OPTION);

            assertThatThrownBy(() -> f.choose(pc, terrain, request -> request.setSubclassOption("Swamp")))
                    .isInstanceOf(ValidationException.class).hasMessage("Choose one of Arctic, Underdark, not Swamp");
            assertThatThrownBy(() -> f.choose(pc, terrain, request -> request.setSpells(List.of("Web"))))
                    .isInstanceOf(ValidationException.class);
            CharacterResponse chosen = f.choose(pc, terrain, request -> request.setSubclassOption("underdark"));

            assertThat(pc.getLevelHistory().get(1).choices().subclassOption()).isEqualTo("Underdark");
            assertThat(chosen.getSubclassAlwaysPreparedSpells()).isEqualTo(Map.of("Circle of the Land", Map.of(
                    "3", List.of("spider climb", "web"), "5", List.of("gaseous form", "stinking cloud"))));
            assertThat(f.pending(pc, Type.SUBCLASS_OPTION)).isNull();
            assertThatThrownBy(() -> f.choose(pc, terrain, request -> request.setSubclassOption("Arctic")))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("Levelling down past Druid 2 takes the circle and its terrain away")
        void levelDown() {
            PlayerCharacter pc = landDruid();
            f.choose(pc, f.pending(pc, Type.SUBCLASS_OPTION), request -> request.setSubclassOption("Arctic"));

            f.levelDown(pc);
            assertThat(SubclassSpells.optionOf(pc, druid.getId())).isNull();
            assertThat(f.service.getCharacter(pc.getId(), f.userId).getSubclassAlwaysPreparedSpells()).isNull();
        }

        @Test
        @DisplayName("A Land druid made before terrains were chosen owes the choice through the backfill, once")
        void backfill() {
            PlayerCharacter pc = landDruid();
            pc.setPendingChoices(List.of());

            List<PendingChoice> missed = f.choices.missedChoices(pc);
            assertThat(missed).filteredOn(c -> c.type() == Type.SUBCLASS_OPTION).singleElement()
                    .satisfies(c -> assertThat(c.options()).containsExactly("Arctic", "Underdark"));
            f.choices.addPending(pc, missed);
            assertThat(f.choices.missedChoices(pc)).noneMatch(c -> c.type() == Type.SUBCLASS_OPTION);
        }
    }

    @Nested
    @DisplayName("Known subclass spells (XGE p.42, p.50)")
    class KnownSpells {

        @Test
        @DisplayName("A Gloom Stalker learns disguise self at Ranger 3 and rope trick at Ranger 5 as the subclass's, "
                + "each recorded on its level, and levelling down forgets rope trick")
        void gloomStalker() {
            PlayerCharacter pc = create(f.human, ranger, 3, request -> request.setSubclassId(gloomStalker.getId()));

            assertThat(names(pc, "subclass:Gloom Stalker")).containsExactly("Disguise Self");
            assertThat(pc.getLevelHistory().get(2).choices().subclassSpells()).containsExactly("Disguise Self");

            f.levelUp(pc, ranger);
            assertThat(names(pc, "subclass:Gloom Stalker")).containsExactly("Disguise Self");
            f.levelUp(pc, ranger);
            assertThat(names(pc, "subclass:Gloom Stalker")).containsExactly("Disguise Self", "Rope Trick");
            assertThat(pc.getLevelHistory().get(4).choices().subclassSpells()).containsExactly("Rope Trick");

            f.levelDown(pc);
            assertThat(names(pc, "subclass:Gloom Stalker")).containsExactly("Disguise Self");
        }

        @Test
        @DisplayName("A spell the ranger knows already isn't learned again, so levelling down leaves it")
        void knownAlready() {
            PlayerCharacter pc = create(f.human, ranger, 3, request -> {
                request.setSubclassId(gloomStalker.getId());
                request.setSpellsKnown(List.of(new KnownSpell(null, "Disguise Self", 1, "race:Human", null, null,
                        null, 1, null)));
            });

            assertThat(names(pc, "subclass:Gloom Stalker")).isEmpty();
            assertThat(pc.getLevelHistory().get(2).choices().subclassSpells()).isNull();
        }

        @Test
        @DisplayName("A Divine Soul owes its affinity, and picking Good teaches cure wounds as the subclass's")
        void divineSoulAffinity() {
            PlayerCharacter pc = create(f.human, f.sorcerer, 1, request -> request.setSubclassId(divineSoul.getId()));

            PendingChoice affinity = f.pending(pc, Type.SUBCLASS_OPTION);
            assertThat(affinity.options()).containsExactly("Good", "Evil");
            f.choose(pc, affinity, request -> request.setSubclassOption("Good"));

            assertThat(names(pc, "subclass:Divine Soul")).containsExactly("Cure Wounds");
            assertThat(pc.getLevelHistory().getFirst().choices().subclassOption()).isEqualTo("Good");
            assertThat(pc.getLevelHistory().getFirst().choices().subclassSpells()).containsExactly("Cure Wounds");
        }

        @Test
        @DisplayName("For a Gloom Stalker given the subclass before its spells were granted, the recompute job's "
                + "missing spells teach them on their levels, once")
        void recompute() {
            PlayerCharacter pc = create(f.human, ranger, 5, request -> request.setSubclassId(gloomStalker.getId()));
            List<KnownSpell> known = new ArrayList<>(pc.getSpellsKnown());
            known.removeIf(s -> "subclass:Gloom Stalker".equals(s.source()));
            pc.setSpellsKnown(known);
            List<LevelHistoryEntry> history = new ArrayList<>(pc.getLevelHistory());
            for (int i = 0; i < history.size(); i++) {
                history.set(i, history.get(i).withChoices(history.get(i).choices().withSubclassSpells(null)));
            }
            pc.setLevelHistory(history);

            assertThat(f.choices.missingSubclassSpells(pc)).extracting(PendingChoiceService.MissingSubclassSpell::spell,
                    PendingChoiceService.MissingSubclassSpell::classLevel)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("disguise self", 3),
                            org.assertj.core.groups.Tuple.tuple("rope trick", 5));
            f.choices.storeMissingSubclassSpells(pc);
            assertThat(names(pc, "subclass:Gloom Stalker")).containsExactly("Disguise Self", "Rope Trick");
            assertThat(pc.getLevelHistory().get(4).choices().subclassSpells()).containsExactly("Rope Trick");
            assertThat(f.choices.missingSubclassSpells(pc)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Subclass cantrips")
    class Cantrips {

        @Test
        @DisplayName("A Light Domain cleric learns light as the subclass's (PHB p.60); one who knows it owes nothing")
        void lightDomain() {
            PlayerCharacter pc = create(f.human, f.cleric, 1, request -> request.setSubclassId(lightDomain.getId()));
            assertThat(names(pc, "subclass:Light Domain")).containsExactly("Light");
            assertThat(f.pending(pc, Type.SUBCLASS_CANTRIP)).isNull();

            PlayerCharacter knowing = create(f.human, f.cleric, 1, request -> {
                request.setSubclassId(lightDomain.getId());
                request.setSpellsKnown(List.of(new KnownSpell(null, "Light", 0, "class:Cleric", null, null, null,
                        null, null)));
            });
            assertThat(names(knowing, "subclass:Light Domain")).isEmpty();
            assertThat(f.pending(knowing, Type.SUBCLASS_CANTRIP)).isNull();
            assertThat(f.choices.missedChoices(knowing)).noneMatch(c -> c.type() == Type.SUBCLASS_CANTRIP);
        }

        @Test
        @DisplayName("A Death Domain cleric chooses a necromancy cantrip from any class's list (DMG p.96)")
        void deathDomain() {
            PlayerCharacter pc = create(f.human, f.cleric, 1, request -> request.setSubclassId(deathDomain.getId()));

            PendingChoice reaper = f.pending(pc, Type.SUBCLASS_CANTRIP);
            assertThat(reaper.school()).isEqualTo("Necromancy");
            assertThat(reaper.spellList()).isNull();
            assertThatThrownBy(() -> f.choose(pc, reaper, request -> request.setSpells(List.of("Fire Bolt"))))
                    .isInstanceOf(ValidationException.class).hasMessage("Fire Bolt isn't a necromancy cantrip");
            f.choose(pc, reaper, request -> request.setSpells(List.of("Chill Touch")));
            assertThat(names(pc, "subclass:Death Domain")).containsExactly("Chill Touch");
        }
    }

    @Nested
    @DisplayName("Race spells (D231)")
    class RaceSpellsGroup {

        private final Race hexblood = Race.builder().id(UUID.randomUUID()).name("Hexblood").speed("{\"walk\": 30}")
                .additionalSpells("{\"fixedSpells\":[{\"name\":\"Hex\",\"level\":1,\"atWill\":false,"
                        + "\"unlocksAtLevel\":1,\"usesPerLongRest\":1}]}")
                .build();
        private final Race firbolg = Race.builder().id(UUID.randomUUID()).name("Firbolg").speed("{\"walk\": 30}")
                .additionalSpells("{\"fixedSpells\":[{\"name\":\"Detect Magic\",\"level\":1,\"atWill\":false,"
                        + "\"unlocksAtLevel\":1,\"usesPerLongRest\":1,\"usesResetOn\":\"shortRest\"}]}")
                .build();

        @BeforeEach
        void knowTheRaces() {
            for (Race race : List.of(hexblood, firbolg)) {
                lenient().when(f.raceRepository.findById(race.getId())).thenReturn(Optional.of(race));
            }
        }

        @Test
        @DisplayName("A Hexblood whose hex was loaded at will casts it once a long rest after the recompute (VRGR p.18)")
        void hexOnceALongRest() {
            PlayerCharacter pc = create(hexblood, f.fighter, 1, request -> request.setSpellsKnown(List.of(
                    new KnownSpell(null, "Hex", 0, "race:Hexblood", null, null, true, null, 1))));

            assertThat(f.service.outdatedRaceSpells(pc)).singleElement().satisfies(change -> {
                assertThat(change.spell()).isEqualTo("Hex");
                assertThat(change.before()).isEqualTo("level 0, at will");
                assertThat(change.after()).isEqualTo("level 1, 1/long rest");
            });
            f.service.storeRaceSpells(pc);
            f.service.recalculate(pc);

            KnownSpell hex = pc.getSpellsKnown().getFirst();
            assertThat(hex.level()).isEqualTo(1);
            assertThat(hex.atWill()).isNull();
            assertThat(hex.usesPerLongRest()).isEqualTo(1);
            assertThat(pc.getResourcePools()).filteredOn(p -> p.poolId().equals("innate:hex")).singleElement()
                    .satisfies(pool -> assertThat(pool.resetOn()).isEqualTo("longRest"));
            assertThat(f.service.outdatedRaceSpells(pc)).isEmpty();
        }

        @Test
        @DisplayName("A VGM firbolg made without detect magic gains it, once between short or long rests (VGM p.107)")
        void firbolgShortRest() {
            PlayerCharacter pc = create(firbolg, f.fighter, 1, request -> {});

            assertThat(f.service.outdatedRaceSpells(pc)).singleElement()
                    .satisfies(change -> assertThat(change.before()).isEqualTo("none"));
            f.service.storeRaceSpells(pc);
            f.service.recalculate(pc);

            assertThat(pc.getSpellsKnown()).singleElement().satisfies(spell -> {
                assertThat(spell.source()).isEqualTo("race:Firbolg");
                assertThat(spell.usesResetOn()).isEqualTo("shortRest");
            });
            ResourcePoolEntry pool = pc.getResourcePools().stream()
                    .filter(p -> p.poolId().equals("innate:detect-magic")).findFirst().orElseThrow();
            assertThat(pool.resetOn()).isEqualTo("shortRest");
            assertThat(pool.maxUses()).isEqualTo(1);
        }
    }
}
