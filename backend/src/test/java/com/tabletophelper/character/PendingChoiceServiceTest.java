package com.tabletophelper.character;

import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.dto.CharacterCreateRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.PendingChoice.Source;
import com.tabletophelper.character.state.PendingChoice.Type;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellsLearned;
import com.tabletophelper.character.state.SubclassChosen;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.OptionalFeature;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pending Choices on the sheet (D159): the choices creation and levelling add, less what creation chose itself;
 * making each type by its ID, with the answer checked and recorded on the level that owes it; levelling down
 * reversing them; and the choices the backfill-pending-choices job finds characters' levels missed. The default
 * character has STR 16, DEX 14, CON 12, INT 16, WIS 10 and CHA 13.
 * <p>
 * The "Cantrips and spells" group took the interim known-spells command's cases from CharacterSpellServiceTest, with
 * the same checks of the class's list, the level it can learn and the number it knows: replacing the spells known
 * became learning a SPELLS choice's and swapping one, replacing the cantrips learning a CANTRIPS choice's, refusing
 * what the class can't know the same 400s, and the subclass's additions and the Eldritch Knight's wizard list the cases
 * of those names.
 */
class PendingChoiceServiceTest {

    private final CharacterFixture f = new CharacterFixture();
    private final Map<String, Spell> spells = new HashMap<>();

    private final Feat lucky = Feat.builder().id(UUID.randomUUID()).name("Lucky").description("Luck points")
            .effects("{\"resource\": {\"name\": \"Luck Points\", \"maxUses\": 3, \"resetOn\": \"longRest\"}}").build();
    private final Feat alert = Feat.builder().id(UUID.randomUUID()).name("Alert").description("Always on the lookout")
            .effects("{\"initiativeBonus\": 5}").build();
    private final Feat tough = Feat.builder().id(UUID.randomUUID()).name("Tough").description("Hit points")
            .effects("{\"hpPerLevel\": 2}").build();
    private final Feat elementalAdept = Feat.builder().id(UUID.randomUUID()).name("Elemental Adept")
            .description("You can select this feat multiple times.").effects("{}").build();

    private final Race variantHuman = Race.builder().id(UUID.randomUUID()).name("Variant Human")
            .speed("{\"walk\": 30}").raceChoices("{\"feats\": 1, \"skills\": [{\"any\": 1}]}").build();
    private final Background rewarded = Background.builder().id(UUID.randomUUID()).name("Rewarded")
            .feats("[\"Lucky\", \"Magic initiate\", \"Skilled\"]").build();

    @BeforeEach
    void knowTheReferenceData() {
        spell("Magic Missile", 1, "Sorcerer", "Wizard");
        spell("Shield", 1, "Sorcerer", "Wizard", "Warlock (The Hexblade)");
        spell("Misty Step", 2, "Sorcerer", "Warlock", "Wizard");
        spell("Fireball", 3, "Sorcerer", "Wizard");
        spell("Fire Bolt", 0, "Sorcerer", "Wizard");
        spell("Cure Wounds", 1, "Bard", "Cleric", "Druid", "Paladin", "Ranger");
        spell("Hex", 1, "Warlock");
        spell("Armor of Agathys", 1, "Warlock");
        spell("Hellish Rebuke", 1, "Warlock");
        spell("Witch Bolt", 1, "Sorcerer", "Warlock", "Wizard");
        spell("Eldritch Blast", 0, "Warlock");
        spell("Chill Touch", 0, "Sorcerer", "Warlock", "Wizard");
        spell("Minor Illusion", 0, "Bard", "Sorcerer", "Warlock", "Wizard");
        spell("Wish", 9);
        lenient().when(f.spellRepository.findByNameIgnoreCase(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(spells.get(inv.<String>getArgument(0).toLowerCase(Locale.ROOT))));
        for (Feat feat : List.of(lucky, alert, tough, elementalAdept)) {
            lenient().when(f.featRepository.findById(feat.getId())).thenReturn(Optional.of(feat));
            lenient().when(f.featRepository.findByNameIgnoreCase(feat.getName().toLowerCase(Locale.ROOT)))
                    .thenReturn(Optional.of(feat));
            lenient().when(f.featRepository.findByNameIgnoreCase(feat.getName())).thenReturn(Optional.of(feat));
        }
        lenient().when(f.raceRepository.findById(variantHuman.getId())).thenReturn(Optional.of(variantHuman));
        lenient().when(f.backgroundRepository.findById(rewarded.getId())).thenReturn(Optional.of(rewarded));
        lenient().when(f.optionalFeatureRepository.findByFeatureTypeOrderByNameAsc("FightingStyle")).thenReturn(List.of(
                OptionalFeature.builder().name("Archery").featureType("FightingStyle")
                        .description("+2 to ranged attack rolls").build(),
                OptionalFeature.builder().name("Defense").featureType("FightingStyle")
                        .description("+1 AC in armour").build()));
    }

    private void spell(String name, int level, String... classes) {
        String lists = classes.length == 0 ? null : "[" + String.join(",",
                Arrays.stream(classes).map(c -> "\"" + c + "\"").toList()) + "]";
        spells.put(name.toLowerCase(Locale.ROOT), Spell.builder().id(UUID.randomUUID()).name(name).level(level)
                .classes(lists).build());
    }

    /** A character created in one class with the request a test adjusts, as stored. */
    private PlayerCharacter create(CharacterClass characterClass, int level, Consumer<CharacterCreateRequest> adjust) {
        CharacterCreateRequest request = new CharacterCreateRequest();
        request.setName("Aria");
        request.setRaceId(f.human.getId());
        int[] scores = CharacterFixture.scores();
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

    private static List<String> described(List<PendingChoice> choices) {
        return choices.stream()
                .map(c -> c.characterLevel() + " " + c.type() + (c.count() > 1 ? "×" + c.count() : ""))
                .toList();
    }

    private static KnownSpell classSpell(String name, int level, String className) {
        return new KnownSpell(null, name, level, "class:" + className, null, null, null, null, null);
    }

    private static List<String> names(PlayerCharacter pc, String source) {
        return pc.getSpellsKnown().stream().filter(s -> source.equals(s.source())).map(KnownSpell::name).toList();
    }

    private ApplyChoicesRequest.AsiChoice featAnswer(Feat feat) {
        ApplyChoicesRequest.AsiChoice answer = new ApplyChoicesRequest.AsiChoice();
        answer.setType("feat");
        answer.setFeatId(feat.getId());
        return answer;
    }

    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        @DisplayName("A Fighter created at 5th level owes the choices of its levels 1 to 5, each made on the sheet")
        void levelsOneToN() {
            PlayerCharacter pc = create(f.fighter, 5, request -> {});

            assertThat(described(pc.getPendingChoices()))
                    .containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS", "4 ASI");
            assertThat(pc.getPendingChoices()).extracting(PendingChoice::source).containsOnly(Source.CREATION);
        }

        @Test
        @DisplayName("A Fighter created at 8th level owes all three of its ASIs, and each is made on its own level (D128)")
        void everyAsiOfAHighLevelCharacter() {
            PlayerCharacter pc = create(f.fighter, 8, request -> {});
            assertThat(pc.getPendingChoices()).filteredOn(c -> c.type() == Type.ASI)
                    .extracting(PendingChoice::characterLevel).containsExactly(4, 6, 8);

            f.improve(pc, "strength", 2);
            f.improve(pc, "dexterity", 2);
            f.improve(pc, "constitution", 2);

            assertThat(pc.getPendingChoices()).noneMatch(c -> c.type() == Type.ASI);
            assertThat(pc.getStrength()).isEqualTo(18);
            assertThat(pc.getDexterity()).isEqualTo(16);
            assertThat(pc.getConstitution()).isEqualTo(14);
            assertThat(pc.getLevelHistory().get(3).choices().asi().applied().getFirst().ability())
                    .isEqualTo("constitution");
            assertThat(pc.getLevelHistory().get(7).choices().asi().applied().getFirst().ability())
                    .isEqualTo("strength");
        }

        @Test
        @DisplayName("The cantrips and spells a request gives cover its class's choices oldest first, and the rest stay pending")
        void spellsGivenCoverTheirChoices() {
            List<KnownSpell> levelOne = List.of(classSpell("Fire Bolt", 0, "Sorcerer"),
                    classSpell("Chill Touch", 0, "Sorcerer"), classSpell("Minor Illusion", 0, "Sorcerer"),
                    classSpell("Light", 0, "Sorcerer"), classSpell("Magic Missile", 1, "Sorcerer"),
                    classSpell("Witch Bolt", 1, "Sorcerer"));
            PlayerCharacter sorcerer = create(f.sorcerer, 5, request -> request.setSpellsKnown(levelOne));

            assertThat(described(sorcerer.getPendingChoices()))
                    .containsExactly("2 SPELLS", "3 SPELLS", "4 ASI", "4 CANTRIPS", "4 SPELLS", "5 SPELLS");

            List<KnownSpell> all = new ArrayList<>(levelOne);
            for (String name : List.of("Misty Step", "Fireball", "Hex", "Cure Wounds")) {
                all.add(classSpell(name, 1, "Sorcerer"));
            }
            PlayerCharacter given = create(f.sorcerer, 5, request -> request.setSpellsKnown(all));
            assertThat(described(given.getPendingChoices())).containsExactly("4 ASI", "4 CANTRIPS");
        }

        @Test
        @DisplayName("Expertise the request gives is recorded on the levels that give it, oldest first, and the rest is pending")
        void expertiseGivenIsRecorded() {
            PlayerCharacter rogue = create(f.rogue, 6, request -> request.setSkillExpertises(
                    List.of("Stealth", "Perception")));

            assertThat(rogue.getLevelHistory().getFirst().choices().expertise()).containsExactly("Stealth", "Perception");
            assertThat(described(rogue.getPendingChoices()).stream().filter(c -> c.contains("EXPERTISE")))
                    .containsExactly("6 EXPERTISE×2");

            PlayerCharacter partly = create(f.rogue, 1, request -> request.setSkillExpertises(List.of("Stealth")));
            assertThat(partly.getPendingChoices()).singleElement().satisfies(c -> {
                assertThat(c.type()).isEqualTo(Type.EXPERTISE);
                assertThat(c.count()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("A subclass the request names is recorded on its level; one below the class's subclass level is a 400")
        void subclassGivenIsRecorded() {
            PlayerCharacter champion = create(f.fighter, 3, request -> request.setSubclassId(f.champion.getId()));

            assertThat(champion.getLevelHistory().get(2).choices().subclass())
                    .isEqualTo(new SubclassChosen(f.fighter.getId(), f.champion.getId(), "Champion"));
            assertThat(champion.getPendingChoices()).noneMatch(c -> c.type() == Type.SUBCLASS);
            assertThat(champion.getFeatures()).extracting(Feature::name).contains("Improved Critical");

            assertThatThrownBy(() -> create(f.fighter, 2, request -> request.setSubclassId(f.champion.getId())))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A Fighter chooses its subclass at level 3, so a level 2 Fighter has none yet");
        }

        @Test
        @DisplayName("A Variant Human's feat and a background's are FEAT choices on the 1st level, the background's from its list")
        void raceAndBackgroundFeats() {
            PlayerCharacter pc = create(f.barbarian, 1, request -> {
                request.setRaceId(variantHuman.getId());
                request.setBackgroundId(rewarded.getId());
            });

            assertThat(pc.getPendingChoices()).extracting(PendingChoice::type, PendingChoice::source,
                    PendingChoice::characterLevel).containsExactly(tuple(Type.FEAT, Source.RACE, 1),
                    tuple(Type.FEAT, Source.BACKGROUND, 1));
            assertThat(pc.getPendingChoices().getFirst().options()).isNull();
            // Named as the feats table names them where it has them
            assertThat(pc.getPendingChoices().getLast().options()).containsExactly("Lucky", "Magic initiate",
                    "Skilled");
        }
    }

    @Nested
    @DisplayName("Feats at creation")
    class CreationFeats {

        @Test
        @DisplayName("A Variant Human's Lucky is recorded on the 1st level and gets its feat pool; Alert its initiative")
        void featPoolAndInitiative() {
            PlayerCharacter pc = create(f.barbarian, 1, request -> request.setRaceId(variantHuman.getId()));
            PendingChoice feat = f.pending(pc, Type.FEAT);

            CharacterResponse response = f.choose(pc, feat, request -> request.setFeat(featAnswer(lucky)));

            assertThat(response.getResourcePools()).extracting(ResourcePoolEntry::poolId, ResourcePoolEntry::maxUses,
                    ResourcePoolEntry::currentUses).contains(tuple("feat:lucky", 3, 3));
            assertThat(pc.getLevelHistory().getFirst().choices().feats()).singleElement()
                    .satisfies(taken -> assertThat(taken.featName()).isEqualTo("Lucky"));
            assertThat(pc.getFeatures()).extracting(Feature::name, Feature::source).contains(tuple("Lucky", "Feat"));
            assertThat(pc.getPendingChoices()).isEmpty();

            PlayerCharacter alertHuman = create(f.barbarian, 1, request -> request.setRaceId(variantHuman.getId()));
            f.choose(alertHuman, f.pending(alertHuman, Type.FEAT), request -> request.setFeat(featAnswer(alert)));
            assertThat(alertHuman.getInitiativeBonus()).isEqualTo(2 + 5);
        }

        @Test
        @DisplayName("A background's feat is one it lists, and a feat the character has already is refused unless it can be taken again")
        void backgroundFeatsAndRepeats() {
            PlayerCharacter pc = create(f.barbarian, 1, request -> {
                request.setRaceId(variantHuman.getId());
                request.setBackgroundId(rewarded.getId());
            });
            PendingChoice race = pc.getPendingChoices().getFirst();
            PendingChoice background = pc.getPendingChoices().getLast();

            assertThatThrownBy(() -> f.choose(pc, background, request -> request.setFeat(featAnswer(alert))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Choose one of Lucky, Magic initiate, Skilled, not Alert");
            f.choose(pc, race, request -> request.setFeat(featAnswer(lucky)));
            assertThatThrownBy(() -> f.choose(pc, background, request -> request.setFeat(featAnswer(lucky))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria already has Lucky, which can't be taken again");

            // An ASI's feat is checked against the feats taken at creation too, unless it can be taken again
            PlayerCharacter fighter = create(f.fighter, 4, request -> request.setRaceId(variantHuman.getId()));
            f.choose(fighter, f.pending(fighter, Type.FEAT), request -> request.setFeat(featAnswer(lucky)));
            assertThatThrownBy(() -> f.takeFeat(fighter, lucky.getId()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria already has Lucky, which can't be taken again");
            assertThat(fighter.getLevelHistory().get(3).choices().asi()).isNull();
            PlayerCharacter adept = create(f.fighter, 4, request -> request.setRaceId(variantHuman.getId()));
            f.choose(adept, f.pending(adept, Type.FEAT), request -> request.setFeat(featAnswer(elementalAdept)));
            f.takeFeat(adept, elementalAdept.getId());
            assertThat(adept.getFeatures()).filteredOn(feature -> "Elemental Adept".equals(feature.name())).hasSize(2);
        }

        @Test
        @DisplayName("A Tough taken at creation counts for every level, as one taken at an ASI does")
        void toughAtCreation() {
            PlayerCharacter pc = create(f.fighter, 2, request -> request.setRaceId(variantHuman.getId()));
            int before = pc.getHpMax();

            f.choose(pc, f.pending(pc, Type.FEAT), request -> request.setFeat(featAnswer(tough)));

            assertThat(pc.getHpMax()).isEqualTo(before + 4);
        }
    }

    @Nested
    @DisplayName("Making choices")
    class MakingChoices {

        @Test
        @DisplayName("A subclass chosen later brings its features to each level from its own, and the choices it gives them")
        void subclassChosenLater() {
            PlayerCharacter pc = create(f.fighter, 5, request -> {});
            PendingChoice subclass = f.pending(pc, Type.SUBCLASS);

            f.choose(pc, subclass, request -> request.setSubclassId(f.eldritchKnight.getId()));

            assertThat(pc.getMulticlassEntries().getFirst().subclassName()).isEqualTo("Eldritch Knight");
            assertThat(pc.getSubclass()).isEqualTo("Eldritch Knight");
            assertThat(pc.getLevelHistory().get(2).featuresGained()).extracting(Feature::name)
                    .contains("Spellcasting", "Weapon Bond");
            assertThat(pc.getLevelHistory().get(2).choices().subclass().subclassName()).isEqualTo("Eldritch Knight");
            assertThat(described(pc.getPendingChoices()))
                    .containsExactly("1 FIGHTING_STYLE", "3 CANTRIPS×2", "3 SPELLS×3", "4 ASI", "4 SPELLS");
        }

        @Test
        @DisplayName("A subclass of another class, one a class has already, or one off the list is a 400 or 409")
        void subclassRefusals() {
            PlayerCharacter pc = create(f.fighter, 3, request -> {});
            PendingChoice subclass = f.pending(pc, Type.SUBCLASS);

            assertThatThrownBy(() -> f.choose(pc, subclass, request -> request.setSubclassId(f.evocation.getId())))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("School of Evocation isn't a Fighter subclass");
            assertThatThrownBy(() -> f.choose(pc, subclass, request -> request.setSubclassId(UUID.randomUUID())))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Subclass not found");
            pc.setMulticlassEntries(List.of(pc.getMulticlassEntries().getFirst()
                    .withSubclass(f.champion.getId(), "Champion")));
            assertThatThrownBy(() -> f.choose(pc, subclass, request -> request.setSubclassId(f.champion.getId())))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria's Fighter already has a subclass");
        }

        @Test
        @DisplayName("A Fighting Style becomes a feature of its level, and a style the character has, or one off the class's list, is a 400")
        void fightingStyle() {
            PlayerCharacter pc = create(f.fighter, 1, request -> {});
            PendingChoice style = f.pending(pc, Type.FIGHTING_STYLE);

            assertThatThrownBy(() -> f.choose(pc, style, request -> request.setFightingStyle("Blessed Warrior")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageStartingWith("A Fighter chooses its Fighting Style from Archery, Defense, ");
            f.choose(pc, style, request -> request.setFightingStyle("archery"));

            assertThat(pc.getFeatures()).contains(new Feature("Fighting Style: Archery", "+2 to ranged attack rolls",
                    "Fighter"));
            assertThat(pc.getLevelHistory().getFirst().choices().fightingStyle()).isEqualTo("Archery");
            assertThat(pc.getLevelHistory().getFirst().featuresGained()).extracting(Feature::name)
                    .contains("Fighting Style: Archery");

            // The Champion's Additional Fighting Style can't take the same one again
            PendingChoice second = new PendingChoice(UUID.randomUUID(), Type.FIGHTING_STYLE, Source.LEVEL_UP, 1,
                    f.fighter.getId(), "Fighter", 10, 1, style.options(), null, null);
            pc.setPendingChoices(List.of(second));
            assertThatThrownBy(() -> f.choose(pc, second, request -> request.setFightingStyle("Archery")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria already has the Archery Fighting Style");
        }

        @Test
        @DisplayName("An answer with another type's field is a 400, and a choice the character doesn't have a 409, each changing nothing")
        void wrongAnswers() {
            PlayerCharacter pc = create(f.fighter, 4, request -> {});
            PendingChoice asi = f.pending(pc, Type.ASI);

            assertThatThrownBy(() -> f.choose(pc, asi, request -> request.setSubclassId(f.champion.getId())))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("This ASI choice takes asi, not subclassId");
            assertThatThrownBy(() -> f.choose(pc, asi, request -> {}))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("An Ability Score Improvement needs its increases, or a feat in its place");
            assertThatThrownBy(() -> f.choose(pc, null, request -> request.setFightingStyle("Archery")))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageStartingWith("Aria has no pending choice ");
            assertThat(pc.getPendingChoices()).contains(asi);
        }

        @Test
        @DisplayName("Choices wait while the character is in an Active or Paused encounter")
        void waitsForEncounters() {
            PlayerCharacter pc = create(f.fighter, 4, request -> {});
            when(f.liveEncounters.includes(pc.getId())).thenReturn(true);

            assertThatThrownBy(() -> f.improve(pc, "strength", 2))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria is in an encounter that is under way, so can't make the choices its levels bring "
                            + "until it ends");
        }
    }

    @Nested
    @DisplayName("Cantrips and spells")
    class CantripsAndSpells {

        // A Warlock 1 who knows nothing yet owes 2 cantrips and 2 spells
        private PlayerCharacter warlock() {
            return create(f.warlock, 1, request -> {});
        }

        @Test
        @DisplayName("A warlock learns the cantrips and spells its 1st level owes, and its race spells are kept")
        void learnsWhatTheLevelOwes() {
            PlayerCharacter pc = warlock();
            pc.setSpellsKnown(List.of(new KnownSpell(null, "Hellish Rebuke", 1, "race:Tiefling", null, null, null, 1,
                    null)));

            f.choose(pc, f.pending(pc, Type.CANTRIPS), request -> request.setSpells(List.of("Eldritch Blast",
                    "minor illusion")));
            f.choose(pc, f.pending(pc, Type.SPELLS), request -> request.setSpells(List.of("Hex", "Armor of Agathys")));

            assertThat(pc.getSpellsKnown()).extracting(KnownSpell::name, KnownSpell::source).containsExactly(
                    tuple("Hellish Rebuke", "race:Tiefling"), tuple("Eldritch Blast", "class:Warlock"),
                    tuple("Minor Illusion", "class:Warlock"), tuple("Hex", "class:Warlock"),
                    tuple("Armor of Agathys", "class:Warlock"));
            assertThat(pc.getLevelHistory().getFirst().choices().cantrips()).containsExactly("Eldritch Blast",
                    "Minor Illusion");
            assertThat(pc.getLevelHistory().getFirst().choices().spells())
                    .isEqualTo(new SpellsLearned(List.of("Hex", "Armor of Agathys"), null, null));
            assertThat(pc.getPendingChoices()).isEmpty();
        }

        @Test
        @DisplayName("Another number of cantrips, or a levelled spell among them, is a 400")
        void cantripRefusals() {
            PlayerCharacter pc = warlock();
            PendingChoice cantrips = f.pending(pc, Type.CANTRIPS);

            assertThatThrownBy(() -> f.choose(pc, cantrips, request -> request.setSpells(List.of("Eldritch Blast",
                    "Minor Illusion", "Chill Touch"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Choose 2 Warlock cantrips, not 3");
            assertThatThrownBy(() -> f.choose(pc, cantrips, request -> request.setSpells(List.of("Eldritch Blast",
                    "Hex"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Hex is a level 1 spell, not a cantrip");
            assertThat(pc.getSpellsKnown()).isNull();
        }

        @Test
        @DisplayName("A spell off the class's list, above the level it can learn, or one it knows already is a 400")
        void spellRefusals() {
            PlayerCharacter pc = warlock();
            PendingChoice spells = f.pending(pc, Type.SPELLS);

            assertThatThrownBy(() -> f.choose(pc, spells, request -> request.setSpells(List.of("Hex", "Wish"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Wish isn't on the Warlock spell list");
            assertThatThrownBy(() -> f.choose(pc, spells, request -> request.setSpells(List.of("Hex", "Misty Step"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 1 Warlock learns spells up to level 1, so can't take Misty Step (level 2)");
            pc.setSpellsKnown(List.of(classSpell("Hex", 1, "Warlock")));
            assertThatThrownBy(() -> f.choose(pc, spells, request -> request.setSpells(List.of("Hex",
                    "Armor of Agathys"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria already knows Hex");
            assertThatThrownBy(() -> f.choose(pc, spells, request -> {
                request.setSpells(List.of("Witch Bolt", "Armor of Agathys"));
                request.setSwap(swap("Hex", "Hellish Rebuke"));
            }))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("This choice doesn't let the Warlock replace a spell it knows");
        }

        @Test
        @DisplayName("More spells than the class knows is a 400, as a sheet over its limit before R6 can be")
        void limit() {
            PlayerCharacter pc = warlock();
            pc.setSpellsKnown(List.of(classSpell("Hex", 1, "Warlock"), classSpell("Hellish Rebuke", 1, "Warlock")));

            assertThatThrownBy(() -> f.choose(pc, f.pending(pc, Type.SPELLS), request -> request.setSpells(List.of(
                    "Witch Bolt", "Armor of Agathys"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 1 Warlock knows 2 spells, not 4");
        }

        @Test
        @DisplayName("A known caster's next level learns a spell and may swap one it knows; levelling down undoes both")
        void swapAndLevelDown() {
            PlayerCharacter pc = warlock();
            f.choose(pc, f.pending(pc, Type.CANTRIPS), request -> request.setSpells(List.of("Eldritch Blast",
                    "Minor Illusion")));
            f.choose(pc, f.pending(pc, Type.SPELLS), request -> request.setSpells(List.of("Hex", "Armor of Agathys")));
            f.levelUp(pc, f.warlock);
            PendingChoice second = f.pending(pc, Type.SPELLS);
            assertThat(second.swap()).isTrue();

            f.choose(pc, second, request -> {
                request.setSpells(List.of("Witch Bolt"));
                request.setSwap(swap("hex", "Hellish Rebuke"));
            });

            assertThat(names(pc, "class:Warlock")).containsExactly("Eldritch Blast", "Minor Illusion",
                    "Armor of Agathys", "Hellish Rebuke", "Witch Bolt");
            SpellsLearned learned = pc.getLevelHistory().getLast().choices().spells();
            assertThat(learned.learned()).containsExactly("Witch Bolt");
            assertThat(learned.replaced().name()).isEqualTo("Hex");
            assertThat(learned.replacement()).isEqualTo("Hellish Rebuke");

            f.levelDown(pc);

            assertThat(names(pc, "class:Warlock")).containsExactly("Eldritch Blast", "Minor Illusion",
                    "Armor of Agathys", "Hex");
        }

        @Test
        @DisplayName("A swap needs a spell the class knows, and a replacement it doesn't")
        void swapRefusals() {
            PlayerCharacter pc = warlock();
            f.choose(pc, f.pending(pc, Type.SPELLS), request -> request.setSpells(List.of("Hex", "Armor of Agathys")));
            f.levelUp(pc, f.warlock);
            PendingChoice second = f.pending(pc, Type.SPELLS);

            assertThatThrownBy(() -> f.choose(pc, second, request -> {
                request.setSpells(List.of("Witch Bolt"));
                request.setSwap(swap("Shield", "Hellish Rebuke"));
            }))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria knows no Warlock spell called Shield");
            assertThatThrownBy(() -> f.choose(pc, second, request -> {
                request.setSpells(List.of("Witch Bolt"));
                request.setSwap(swap("Hex", "Witch Bolt"));
            }))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Witch Bolt is already among the spells chosen");
            assertThatThrownBy(() -> f.choose(pc, second, request -> {
                request.setSpells(List.of("Witch Bolt"));
                request.setSwap(swap("Hex", "Armor of Agathys"));
            }))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria already knows Armor of Agathys");
        }

        @Test
        @DisplayName("A swap can't replace a spell a later level learned, as choices made out of order could; one an earlier level learned it can, and levelling down gives it back")
        void swapOnlyReplacesWhatWasKnownBefore() {
            PlayerCharacter pc = sorcererOwingLevelsFourAndFive();
            f.choose(pc, spellsOf(pc, 5), request -> request.setSpells(List.of("Fireball")));

            assertThatThrownBy(() -> f.choose(pc, spellsOf(pc, 4), request -> {
                request.setSpells(List.of("Shield"));
                request.setSwap(swap("Fireball", "Misty Step"));
            }))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Fireball was learned at level 5, after this choice's level, so this choice's swap "
                            + "can't replace it");
            assertThat(names(pc, "class:Sorcerer")).contains("Fireball").doesNotContain("Shield", "Misty Step");

            PlayerCharacter inOrder = sorcererOwingLevelsFourAndFive();
            f.choose(inOrder, spellsOf(inOrder, 4), request -> request.setSpells(List.of("Shield")));
            f.choose(inOrder, spellsOf(inOrder, 5), request -> {
                request.setSpells(List.of("Fireball"));
                request.setSwap(swap("Shield", "Misty Step"));
            });
            assertThat(names(inOrder, "class:Sorcerer")).contains("Fireball", "Misty Step").doesNotContain("Shield");

            f.levelDown(inOrder);

            assertThat(names(inOrder, "class:Sorcerer")).contains("Shield").doesNotContain("Fireball", "Misty Step");
        }

        // A Sorcerer 5 that knows its 1st level's cantrips and spells and its 2nd and 3rd levels' spells, so its 4th
        // and 5th levels' SPELLS choices, each one spell with a swap, are pending
        private PlayerCharacter sorcererOwingLevelsFourAndFive() {
            List<KnownSpell> known = List.of(classSpell("Fire Bolt", 0, "Sorcerer"),
                    classSpell("Chill Touch", 0, "Sorcerer"), classSpell("Minor Illusion", 0, "Sorcerer"),
                    classSpell("Light", 0, "Sorcerer"), classSpell("Magic Missile", 1, "Sorcerer"),
                    classSpell("Witch Bolt", 1, "Sorcerer"), classSpell("Burning Hands", 1, "Sorcerer"),
                    classSpell("Sleep", 1, "Sorcerer"));
            return create(f.sorcerer, 5, request -> request.setSpellsKnown(known));
        }

        private PendingChoice spellsOf(PlayerCharacter pc, int characterLevel) {
            return pc.getPendingChoices().stream()
                    .filter(c -> c.type() == Type.SPELLS && c.characterLevel() == characterLevel)
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        @DisplayName("A subclass's additions to its class's list count, as \"Warlock (The Hexblade)\" in the spells table")
        void subclassAdditionsCount() {
            PlayerCharacter pc = warlock();
            ClassEntry entry = pc.getMulticlassEntries().getFirst();
            pc.setMulticlassEntries(List.of(entry.withSubclass(UUID.randomUUID(), "The Hexblade")));

            f.choose(pc, f.pending(pc, Type.SPELLS), request -> request.setSpells(List.of("Shield", "Hex")));

            assertThat(names(pc, "class:Warlock")).containsExactly("Shield", "Hex");
        }

        @Test
        @DisplayName("An Eldritch Knight learns from the wizard's list, up to the level its own table gives")
        void eldritchKnightUsesTheWizardsList() {
            PlayerCharacter pc = create(f.fighter, 3, request -> {});
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(f.eldritchKnight.getId()));
            PendingChoice spells = f.pending(pc, Type.SPELLS);

            assertThatThrownBy(() -> f.choose(pc, spells, request -> request.setSpells(List.of("Shield",
                    "Magic Missile", "Cure Wounds"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Cure Wounds isn't on the Wizard spell list");
            assertThatThrownBy(() -> f.choose(pc, spells, request -> request.setSpells(List.of("Shield",
                    "Magic Missile", "Misty Step"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 3 Fighter learns spells up to level 1, so can't take Misty Step (level 2)");
            f.choose(pc, f.pending(pc, Type.CANTRIPS), request -> request.setSpells(List.of("Fire Bolt",
                    "Minor Illusion")));
            f.choose(pc, spells, request -> request.setSpells(List.of("Shield", "Magic Missile", "Witch Bolt")));

            assertThat(names(pc, "class:Fighter")).containsExactly("Fire Bolt", "Minor Illusion", "Shield",
                    "Magic Missile", "Witch Bolt");
        }

        @Test
        @DisplayName("A wizard copies its level's two spells into its spellbook unprepared, with no swap")
        void wizardSpellbook() {
            PlayerCharacter pc = create(f.wizard, 2, request -> {});
            PendingChoice spellbook = f.pending(pc, Type.SPELLS);
            assertThat(spellbook.characterLevel()).isEqualTo(2);

            assertThatThrownBy(() -> f.choose(pc, spellbook, request -> {
                request.setSpells(List.of("Shield", "Magic Missile"));
                request.setSwap(swap("Shield", "Witch Bolt"));
            }))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("This choice doesn't let the Wizard replace a spell it knows");
            f.choose(pc, spellbook, request -> request.setSpells(List.of("Shield", "Magic Missile")));

            assertThat(pc.getSpellsKnown()).extracting(KnownSpell::name, KnownSpell::prepared)
                    .containsExactly(tuple("Shield", false), tuple("Magic Missile", false));
            f.levelDown(pc);
            assertThat(pc.getSpellsKnown()).isEmpty();
        }

        private ApplyChoicesRequest.SpellSwap swap(String replace, String with) {
            ApplyChoicesRequest.SpellSwap swap = new ApplyChoicesRequest.SpellSwap();
            swap.setReplace(replace);
            swap.setWith(with);
            return swap;
        }
    }

    @Nested
    @DisplayName("Levelling down")
    class LevellingDown {

        @Test
        @DisplayName("Levelling down removes the level's choices still pending, and leaves the earlier levels'")
        void removesTheLevelsPendingChoices() {
            PlayerCharacter pc = create(f.fighter, 4, request -> {});

            f.levelDown(pc);

            assertThat(described(pc.getPendingChoices())).containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS");
        }

        @Test
        @DisplayName("Levelling down past a subclass's level takes the subclass and its features away, and owes it again when regained")
        void reversesTheSubclass() {
            PlayerCharacter pc = create(f.fighter, 3, request -> {});
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(f.champion.getId()));
            f.choose(pc, f.pending(pc, Type.FIGHTING_STYLE), request -> request.setFightingStyle("Defense"));

            f.levelDown(pc);

            assertThat(pc.getMulticlassEntries().getFirst().subclassId()).isNull();
            assertThat(pc.getSubclass()).isNull();
            assertThat(pc.getSubclassRef()).isNull();
            assertThat(pc.getFeatures()).extracting(Feature::name).doesNotContain("Improved Critical")
                    .contains("Fighting Style: Defense");

            f.levelUp(pc, f.fighter);
            assertThat(f.pending(pc, Type.SUBCLASS).characterLevel()).isEqualTo(3);
        }

        @Test
        @DisplayName("Levelling down a dip's first level reverses its Fighting Style, cantrips and spellbook")
        void reversesADip() {
            PlayerCharacter pc = create(f.wizard, 1, request -> {});
            f.choose(pc, f.pending(pc, Type.CANTRIPS), request -> request.setSpells(List.of("Fire Bolt",
                    "Minor Illusion", "Chill Touch")));
            f.levelUp(pc, f.fighter);
            f.choose(pc, f.pending(pc, Type.FIGHTING_STYLE), request -> request.setFightingStyle("Archery"));

            f.levelDown(pc);

            assertThat(pc.getFeatures()).extracting(Feature::name).doesNotContain("Fighting Style: Archery");
            assertThat(names(pc, "class:Wizard")).containsExactly("Fire Bolt", "Minor Illusion", "Chill Touch");
            assertThat(pc.getPendingChoices()).extracting(PendingChoice::type).containsExactly(Type.SPELLS);
        }
    }

    @Nested
    @DisplayName("The backfill")
    class Backfill {

        // As a character levelled before R6 workstream F is: no Pending Choices, and only some choices recorded
        private PlayerCharacter legacy(CharacterClass characterClass, int level) {
            PlayerCharacter pc = create(characterClass, level, request -> {});
            pc.setPendingChoices(List.of());
            return pc;
        }

        private void recordAsi(PlayerCharacter pc, int characterLevel) {
            List<LevelHistoryEntry> history = new ArrayList<>(pc.getLevelHistory());
            int index = characterLevel - 1;
            history.set(index, history.get(index).withChoices(LevelChoices.NONE.withAsi(new AbilityScoreImprovement(
                    "ability", List.of(), null, null, null, null, null))));
            pc.setLevelHistory(history);
        }

        @Test
        @DisplayName("ASIs no level records, a subclass a class is owed, and Expertise no level records are missed")
        void findsWhatWasMissed() {
            PlayerCharacter fighter = legacy(f.fighter, 9);
            recordAsi(fighter, 4);

            assertThat(described(f.choices.missedChoices(fighter)))
                    .containsExactly("3 SUBCLASS", "6 ASI", "8 ASI");
            assertThat(f.choices.missedChoices(fighter)).extracting(PendingChoice::source).containsOnly(Source.LEVEL_UP);

            PlayerCharacter rogue = legacy(f.rogue, 6);
            rogue.setMulticlassEntries(List.of(rogue.getMulticlassEntries().getFirst().withSubclass(UUID.randomUUID(),
                    "Thief")));
            recordAsi(rogue, 4);
            assertThat(described(f.choices.missedChoices(rogue))).containsExactly("1 EXPERTISE×2", "6 EXPERTISE×2");
        }

        @Test
        @DisplayName("Expertise the sheet has that no level records covers the earliest levels that give it")
        void sheetExpertiseCovers() {
            PlayerCharacter rogue = legacy(f.rogue, 6);
            rogue.setSkillExpertises(List.of("Stealth", "Perception"));

            assertThat(described(f.choices.missedChoices(rogue))).containsExactly("4 ASI", "6 EXPERTISE×2");

            rogue.setSkillExpertises(List.of("Stealth", "Perception", "Insight"));
            assertThat(described(f.choices.missedChoices(rogue))).containsExactly("4 ASI", "6 EXPERTISE");
        }

        @Test
        @DisplayName("Once added the missed choices are pending, so a second run finds none, and Fighting Styles and spells aren't backfilled")
        void secondRunFindsNothing() {
            PlayerCharacter fighter = legacy(f.fighter, 4);
            List<PendingChoice> missed = f.choices.missedChoices(fighter);
            assertThat(described(missed)).containsExactly("3 SUBCLASS", "4 ASI");

            f.choices.addPending(fighter, missed);

            assertThat(fighter.getPendingChoices()).isEqualTo(missed);
            assertThat(f.choices.missedChoices(fighter)).isEmpty();
            assertThat(f.choices.missedChoices(legacy(f.warlock, 3))).isEmpty();
        }
    }
}
