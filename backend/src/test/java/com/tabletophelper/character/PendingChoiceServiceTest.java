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
import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellsLearned;
import com.tabletophelper.character.state.SubclassChosen;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatAbilityIncrease;
import com.tabletophelper.reference.FeatEffects;
import com.tabletophelper.reference.FeatPrerequisite;
import com.tabletophelper.reference.Subclass;
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
            .effects(FeatEffects.parse("{\"resource\": {\"name\": \"Luck Points\", \"maxUses\": 3, \"resetOn\": \"longRest\"}}")).build();
    private final Feat alert = Feat.builder().id(UUID.randomUUID()).name("Alert").description("Always on the lookout")
            .effects(FeatEffects.parse("{\"initiativeBonus\": 5}")).build();
    private final Feat tough = Feat.builder().id(UUID.randomUUID()).name("Tough").description("Hit points")
            .effects(FeatEffects.parse("{\"hpPerLevel\": 2}")).build();
    private final Feat elementalAdept = Feat.builder().id(UUID.randomUUID()).name("Elemental Adept")
            .description("You can select this feat multiple times.").effects(FeatEffects.parse("{\"repeatable\": true}")).build();

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

    // A feat like Fey Touched: +1 to INT, WIS or CHA, and a spell cast with the ability chosen
    private Feat touchedFeat(String name) {
        Feat feat = Feat.builder().id(UUID.randomUUID()).name(name).description(name + " magic")
                .abilityScoreIncrease(FeatAbilityIncrease.parse("[{\"choose\": {\"from\": [\"int\", \"wis\", \"cha\"], \"amount\": 1}}]"))
                .build();
        lenient().when(f.featRepository.findById(feat.getId())).thenReturn(Optional.of(feat));
        return feat;
    }

    // The feat with the ability chosen and Misty Step
    private ApplyChoicesRequest.AsiChoice touchedAnswer(Feat feat, String ability) {
        UUID mistyStep = UUID.randomUUID();
        lenient().when(f.spellRepository.findById(mistyStep)).thenReturn(Optional.of(
                Spell.builder().id(mistyStep).name("Misty Step").level(2).build()));
        ApplyChoicesRequest.AsiChoice answer = featAnswer(feat);
        answer.setFeatAbility(ability);
        answer.setSpellIds(List.of(mistyStep));
        return answer;
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
                    .containsExactly("2 SPELLS", "3 METAMAGIC×2", "3 SPELLS", "4 ASI", "4 CANTRIPS", "4 SPELLS", "5 SPELLS");

            List<KnownSpell> all = new ArrayList<>(levelOne);
            for (String name : List.of("Misty Step", "Fireball", "Hex", "Cure Wounds")) {
                all.add(classSpell(name, 1, "Sorcerer"));
            }
            PlayerCharacter given = create(f.sorcerer, 5, request -> request.setSpellsKnown(all));
            assertThat(described(given.getPendingChoices())).containsExactly("3 METAMAGIC×2", "4 ASI", "4 CANTRIPS");
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
        @DisplayName("A race's feat that chooses a spellcasting ability sets it, and undoing the feat that set it hands it to the race's feat")
        void raceFeatSpellcastingAbility() {
            Feat feyTouched = touchedFeat("Fey Touched");
            Feat shadowTouched = touchedFeat("Shadow Touched");

            PlayerCharacter pc = create(f.barbarian, 1, request -> request.setRaceId(variantHuman.getId()));
            f.choose(pc, f.pending(pc, Type.FEAT), request -> request.setFeat(touchedAnswer(feyTouched, "WIS")));

            assertThat(pc.getSpellcastingAbility()).isEqualTo("WIS");
            assertThat(pc.getLevelHistory().getFirst().choices().feats().getFirst().appliedEffects()
                    .setsSpellcastingAbility()).isTrue();

            // The 4th level's feat is chosen first and sets WIS; the race's, made after, chooses CHA without setting it
            PlayerCharacter later = create(f.barbarian, 4, request -> request.setRaceId(variantHuman.getId()));
            f.choose(later, f.pending(later, Type.ASI), request -> request.setAsi(touchedAnswer(feyTouched, "WIS")));
            f.choose(later, f.pending(later, Type.FEAT),
                    request -> request.setFeat(touchedAnswer(shadowTouched, "CHA")));
            assertThat(later.getSpellcastingAbility()).isEqualTo("WIS");

            f.levelDown(later);

            assertThat(later.getSpellcastingAbility()).isEqualTo("CHA");
            assertThat(later.getLevelHistory().getFirst().choices().feats().getFirst().appliedEffects()
                    .setsSpellcastingAbility()).isTrue();
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
    @DisplayName("Feat prerequisites (D159)")
    class FeatPrerequisitesChecked {

        private Feat featNeeding(String name, String prerequisite) {
            Feat feat = Feat.builder().id(UUID.randomUUID()).name(name).description(name)
                    .prerequisite(FeatPrerequisite.parse(prerequisite)).effects(FeatEffects.parse("{}")).build();
            lenient().when(f.featRepository.findById(feat.getId())).thenReturn(Optional.of(feat));
            return feat;
        }

        @Test
        @DisplayName("An ASI's feat whose prerequisite the character doesn't meet is a 400 naming what it misses, changing nothing; one it meets is taken")
        void asiFeatRefusedUnlessMet() {
            Feat leader = featNeeding("Inspiring Leader", "[{\"abilities\": [{\"ability\": \"CHA\", \"minimum\": 15}]}]");
            Feat grappler = featNeeding("Grappler", "[{\"abilities\": [{\"ability\": \"STR\", \"minimum\": 13}]}]");
            PlayerCharacter pc = create(f.fighter, 4, request -> {});

            assertThatThrownBy(() -> f.takeFeat(pc, leader.getId()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria doesn't meet the prerequisite of Inspiring Leader: Charisma 15 (you have 13)");
            assertThat(pc.getLevelHistory().get(3).choices() == null
                    || pc.getLevelHistory().get(3).choices().asi() == null).isTrue();
            assertThat(f.pending(pc, Type.ASI)).isNotNull();

            f.takeFeat(pc, grappler.getId());
            assertThat(pc.getLevelHistory().get(3).choices().asi().featName()).isEqualTo("Grappler");
        }

        @Test
        @DisplayName("A race's feat at creation is checked too: a Barbarian with no spells can't take War Caster")
        void raceFeatRefusedUnlessMet() {
            Feat warCaster = featNeeding("War Caster", "[{\"spellcasting\": true}]");
            PlayerCharacter pc = create(f.barbarian, 1, request -> request.setRaceId(variantHuman.getId()));

            assertThatThrownBy(() -> f.choose(pc, f.pending(pc, Type.FEAT),
                    request -> request.setFeat(featAnswer(warCaster))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria doesn't meet the prerequisite of War Caster: The ability to cast at least one "
                            + "spell");
            assertThat(pc.getFeatures()).extracting(Feature::name).doesNotContain("War Caster");
        }

        @Test
        @DisplayName("A campaign or a requirement in words never refuses a feat")
        void campaignAndWordsNotRefused() {
            Feat scion = featNeeding("Scion of the Outer Planes", "[{\"campaigns\": [\"Planescape\"]}]");
            Feat mark = featNeeding("Aberrant Dragonmark", "[{\"other\": \"No other dragonmark\"}]");
            PlayerCharacter pc = create(f.fighter, 8, request -> {});

            f.takeFeat(pc, scion.getId());
            f.takeFeat(pc, mark.getId());
            assertThat(pc.getFeatures()).extracting(Feature::name).contains("Scion of the Outer Planes",
                    "Aberrant Dragonmark");
        }

        @Test
        @DisplayName("What the sheet brings: a race's spell counts as casting a spell but not as Spellcasting, and not before it unlocks; a 1st-level Paladin has no Spellcasting yet, a 2nd-level one has")
        void facts() {
            PlayerCharacter pc = create(f.fighter, 4, request -> {});
            assertThat(f.featEligibility.facts(pc).castsSpell()).isFalse();
            pc.setSpellsKnown(List.of(new KnownSpell(null, "Hellish Rebuke", 1, "race:Tiefling", null, null, null, 1,
                    5)));
            assertThat(f.featEligibility.facts(pc).castsSpell()).isFalse();
            pc.setSpellsKnown(List.of(new KnownSpell(null, "Thaumaturgy", 0, "race:Tiefling", null, null, true, null,
                    null)));
            assertThat(f.featEligibility.facts(pc).castsSpell()).isTrue();
            assertThat(f.featEligibility.facts(pc).spellcastingFeature()).isFalse();
            assertThat(f.featEligibility.facts(pc).scores().get(com.tabletophelper.rules.Ability.STR)).isEqualTo(16);
            assertThat(f.featEligibility.facts(pc).classLevels()).isEqualTo(Map.of("Fighter", 4));

            PlayerCharacter paladin = create(f.paladin, 1, request -> {});
            assertThat(f.featEligibility.facts(paladin).spellcastingFeature()).isFalse();
            PlayerCharacter paladin2 = create(f.paladin, 2, request -> {});
            assertThat(f.featEligibility.facts(paladin2).spellcastingFeature()).isTrue();
            assertThat(f.featEligibility.facts(paladin2).castsSpell()).isTrue();
        }

        @Test
        @DisplayName("A feat whose prerequisite the character no longer meets stays, and is listed for the recompute job")
        void heldFeatsKept() {
            Feat grappler = featNeeding("Grappler", "[{\"abilities\": [{\"ability\": \"STR\", \"minimum\": 13}]}]");
            PlayerCharacter pc = create(f.fighter, 4, request -> {});
            f.takeFeat(pc, grappler.getId());

            pc.setStrength(10);

            assertThat(DmAdjustments.hasFeat(pc, "Grappler")).isTrue();
            assertThat(f.featEligibility.heldFeatsUnmet(pc))
                    .containsExactly("Grappler (level 4 Ability Score Improvement): Strength 13 (you have 10)");
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
                    f.fighter.getId(), "Fighter", 10, 1, style.options(), null, null, null);
            pc.setPendingChoices(List.of(second));
            assertThatThrownBy(() -> f.choose(pc, second, request -> request.setFightingStyle("Archery")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria already has the Archery Fighting Style");
        }

        @Test
        @DisplayName("Metamagic: two options at Sorcerer 3 become features of the level, one known or off the list is a 400, and levelling down removes them")
        void metamagic() {
            PlayerCharacter pc = create(f.sorcerer, 3, request -> {});
            PendingChoice metamagic = f.pending(pc, Type.METAMAGIC);
            assertThat(metamagic.count()).isEqualTo(2);
            assertThat(metamagic.options()).containsExactly("Careful Spell", "Distant Spell", "Empowered Spell",
                    "Extended Spell", "Heightened Spell", "Quickened Spell", "Subtle Spell", "Twinned Spell");

            assertThatThrownBy(() -> f.choose(pc, metamagic, request -> request.setMetamagic(List.of("Seeking Spell",
                    "Quickened Spell"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageStartingWith("A Sorcerer chooses Metamagic from Careful Spell, ");
            assertThatThrownBy(() -> f.choose(pc, metamagic, request -> request.setMetamagic(List.of("Quickened Spell"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Choose 2 Metamagic options, not 1");
            assertThatThrownBy(() -> f.choose(pc, metamagic, request -> request.setFightingStyle("Archery")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("This METAMAGIC choice takes metamagic, not fightingStyle");

            f.choose(pc, metamagic, request -> request.setMetamagic(List.of("quickened spell", "Twinned Spell")));

            assertThat(pc.getFeatures()).extracting(Feature::name)
                    .contains("Metamagic: Quickened Spell", "Metamagic: Twinned Spell");
            assertThat(pc.getLevelHistory().get(2).choices().metamagic())
                    .containsExactly("Quickened Spell", "Twinned Spell");
            assertThat(pc.getPendingChoices()).noneMatch(c -> c.type() == Type.METAMAGIC);

            // The 10th level's option can't be one the sorcerer knows
            PendingChoice tenth = new PendingChoice(UUID.randomUUID(), Type.METAMAGIC, Source.LEVEL_UP, 3,
                    f.sorcerer.getId(), "Sorcerer", 10, 1, metamagic.options(), null, null, null);
            pc.setPendingChoices(List.of(tenth));
            assertThatThrownBy(() -> f.choose(pc, tenth, request -> request.setMetamagic(List.of("Twinned Spell"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria already knows Twinned Spell");
            pc.setPendingChoices(List.of());

            f.levelDown(pc);
            assertThat(pc.getFeatures()).extracting(Feature::name).noneMatch(name -> name.startsWith("Metamagic: "));
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
            Subclass hexblade = Subclass.builder().id(UUID.randomUUID()).name("The Hexblade")
                    .characterClass(f.warlock).build();
            when(f.subclassRepository.findById(hexblade.getId())).thenReturn(Optional.of(hexblade));
            pc.setMulticlassEntries(List.of(entry.withSubclass(hexblade.getId(), "The Hexblade")));

            f.choose(pc, f.pending(pc, Type.SPELLS), request -> request.setSpells(List.of("Shield", "Hex")));

            assertThat(names(pc, "class:Warlock")).containsExactly("Shield", "Hex");
        }

        @Test
        @DisplayName("An Eldritch Knight learns from the wizard's list, up to the level its own table gives")
        void eldritchKnightUsesTheWizardsList() {
            PlayerCharacter pc = create(f.fighter, 3, request -> {});
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(f.eldritchKnight.getId()));
            PendingChoice spells = f.pending(pc, Type.SPELLS);
            assertThat(spells.spellList()).isEqualTo("Wizard");
            assertThat(f.pending(pc, Type.CANTRIPS).spellList()).isEqualTo("Wizard");

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
    @DisplayName("A subclass's bonus proficiencies")
    class SubclassBonusProficiencies {

        // As the content loader stores them: the 5etools features, whose 1st level is only the feature named after the
        // subclass, and the bonus proficiencies from subclass-proficiencies.json
        private final Subclass lifeDomain = Subclass.builder().id(UUID.randomUUID()).name("Life Domain")
                .characterClass(f.cleric)
                .features("[{\"level\":1,\"name\":\"Life Domain\",\"description\":\"The Life domain\"},"
                        + "{\"level\":2,\"name\":\"Channel Divinity: Preserve Life\",\"description\":\"Heal\"}]")
                .additionalProficiencies("{\"armor\":[\"heavy\"]}")
                .build();
        private final Subclass runeKnight = Subclass.builder().id(UUID.randomUUID()).name("Rune Knight")
                .characterClass(f.fighter)
                .features("[{\"level\":3,\"name\":\"Bonus Proficiencies\",\"description\":\"Smith's tools, Giant\"}]")
                .additionalProficiencies("{\"tools\":[\"smith's tools\"],\"languages\":[\"Giant\"]}")
                .build();

        @BeforeEach
        void knowTheSubclasses() {
            lenient().when(f.subclassRepository.findById(lifeDomain.getId())).thenReturn(Optional.of(lifeDomain));
            lenient().when(f.subclassRepository.findById(runeKnight.getId())).thenReturn(Optional.of(runeKnight));
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.fighter.getId()))
                    .thenReturn(List.of(f.champion, f.eldritchKnight, runeKnight));
        }

        private PlayerCharacter lifeCleric() {
            return create(f.cleric, 1, request -> {
                request.setSubclassId(lifeDomain.getId());
                // The class's own armour, as the creation wizard sends it from the class data
                request.setArmorProficiencies(List.of("light", "medium", "shields"));
                request.setWeaponProficiencies(List.of("simple"));
            });
        }

        @Test
        @DisplayName("A Life Domain cleric created with its subclass gains heavy armour, recorded on Cleric 1 (PHB p.60)")
        void lifeDomainAtCreation() {
            PlayerCharacter pc = lifeCleric();

            assertThat(pc.getArmorProficiencies()).containsExactly("light", "medium", "shields", "heavy");
            assertThat(pc.getLevelHistory().getFirst().proficienciesGained())
                    .isEqualTo(new ProficienciesGained(List.of("heavy"), null, null, null, null));
            assertThat(pc.getFeatures()).extracting(Feature::name).contains("Life Domain")
                    .doesNotContain("Disciple of Life");
        }

        @Test
        @DisplayName("A proficiency the sheet has already, in any case, isn't added or recorded again")
        void alreadyProficient() {
            PlayerCharacter pc = create(f.cleric, 1, request -> {
                request.setSubclassId(lifeDomain.getId());
                request.setArmorProficiencies(List.of("Heavy Armor", "Heavy"));
            });

            assertThat(pc.getArmorProficiencies()).containsExactly("Heavy Armor", "Heavy");
            assertThat(pc.getLevelHistory().getFirst().proficienciesGained()).isNull();
        }

        @Test
        @DisplayName("A subclass chosen on the sheet gives its proficiencies on its level, and levelling down past that "
                + "level takes them away with it")
        void chosenLaterAndReversed() {
            PlayerCharacter pc = create(f.fighter, 3, request -> request.setToolProficiencies(List.of("dice set")));

            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(runeKnight.getId()));

            assertThat(pc.getToolProficiencies()).containsExactly("dice set", "smith's tools");
            assertThat(pc.getLanguageProficiencies()).containsExactly("Giant");
            assertThat(pc.getLevelHistory().get(2).proficienciesGained())
                    .isEqualTo(new ProficienciesGained(null, null, List.of("smith's tools"), null, List.of("Giant")));

            f.levelDown(pc);

            assertThat(pc.getSubclass()).isNull();
            assertThat(pc.getToolProficiencies()).containsExactly("dice set");
            assertThat(pc.getLanguageProficiencies()).isEmpty();
        }

        @Test
        @DisplayName("A character given its subclass before subclasses granted proficiencies is found missing them, "
                + "recorded on the level that records the subclass, and a second look finds nothing")
        void missingForTheJob() {
            PlayerCharacter pc = lifeCleric();
            pc.setArmorProficiencies(List.of("light", "medium", "shields"));
            pc.setLevelHistory(List.of(pc.getLevelHistory().getFirst().withProficienciesGained(null)));

            List<PendingChoiceService.MissingSubclassProficiencies> missing =
                    f.choices.missingSubclassProficiencies(pc);

            assertThat(missing).singleElement().satisfies(m -> {
                assertThat(m.subclassName()).isEqualTo("Life Domain");
                assertThat(m.description()).isEqualTo("armour heavy");
                assertThat(m.recorded()).isTrue();
                assertThat(m.className()).isEqualTo("Cleric");
                assertThat(m.classLevel()).isEqualTo(1);
            });
            assertThat(pc.getArmorProficiencies()).doesNotContain("heavy");

            f.choices.storeMissingSubclassProficiencies(pc);

            assertThat(pc.getArmorProficiencies()).containsExactly("light", "medium", "shields", "heavy");
            assertThat(pc.getLevelHistory().getFirst().proficienciesGained().armor()).containsExactly("heavy");
            assertThat(f.choices.missingSubclassProficiencies(pc)).isEmpty();
        }

        @Test
        @DisplayName("Without a level that records the subclass, the missing proficiencies are given unrecorded")
        void missingUnrecorded() {
            PlayerCharacter pc = lifeCleric();
            pc.setArmorProficiencies(List.of("light"));
            pc.setLevelHistory(List.of(pc.getLevelHistory().getFirst().withProficienciesGained(null)
                    .withChoices(LevelChoices.NONE)));

            List<PendingChoiceService.MissingSubclassProficiencies> missing =
                    f.choices.missingSubclassProficiencies(pc);

            assertThat(missing).singleElement().satisfies(m -> assertThat(m.recorded()).isFalse());
            f.choices.storeMissingSubclassProficiencies(pc);
            assertThat(pc.getArmorProficiencies()).containsExactly("light", "heavy");
            assertThat(pc.getLevelHistory().getFirst().proficienciesGained()).isNull();
        }

        @Test
        @DisplayName("A subclass whose proficiencies can't be read is a 409")
        void unreadable() {
            PlayerCharacter pc = lifeCleric();
            pc.setArmorProficiencies(List.of());
            lifeDomain.setAdditionalProficiencies("{\"armor\": \"heavy\"");

            assertThatThrownBy(() -> f.choices.missingSubclassProficiencies(pc))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("The bonus proficiencies of the subclass Life Domain can't be read");
        }
    }

    @Nested
    @DisplayName("A subclass's bonus proficiencies of the player's choice")
    class ChosenBonusProficiencies {

        // As the content loader stores them, the lists written out (shortened here)
        private final Subclass knowledgeDomain = Subclass.builder().id(UUID.randomUUID()).name("Knowledge Domain")
                .characterClass(f.cleric)
                .features("[{\"level\":1,\"name\":\"Blessings of Knowledge\",\"description\":\"Languages and skills\"}]")
                .additionalProficiencies("{\"choices\":[{\"count\":2,\"languages\":[\"Common\",\"Dwarvish\",\"Elvish\","
                        + "\"Giant\"]},{\"count\":2,\"skills\":[\"Arcana\",\"History\",\"Nature\",\"Religion\"],"
                        + "\"expertise\":true}]}")
                .build();
        private final Subclass cavalier = Subclass.builder().id(UUID.randomUUID()).name("Cavalier")
                .characterClass(f.fighter)
                .features("[{\"level\":3,\"name\":\"Bonus Proficiency\",\"description\":\"A skill or a language\"}]")
                .additionalProficiencies("{\"choices\":[{\"count\":1,\"skills\":[\"Animal Handling\",\"History\","
                        + "\"Insight\",\"Performance\",\"Persuasion\"],\"languages\":[\"Common\",\"Draconic\"]}]}")
                .build();
        private final Subclass bladesinging = Subclass.builder().id(UUID.randomUUID()).name("Bladesinging")
                .characterClass(f.wizard)
                .features("[{\"level\":2,\"name\":\"Training in War and Song\",\"description\":\"Light armour\"}]")
                .additionalProficiencies("{\"armor\":[\"light\"],\"skills\":[\"Performance\"],\"choices\":[{\"count\":1,"
                        + "\"weapons\":[\"Dagger\",\"Longsword\",\"Rapier\"]}]}")
                .build();

        @BeforeEach
        void knowTheSubclasses() {
            for (Subclass subclass : List.of(knowledgeDomain, cavalier, bladesinging)) {
                lenient().when(f.subclassRepository.findById(subclass.getId())).thenReturn(Optional.of(subclass));
            }
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.cleric.getId()))
                    .thenReturn(List.of(knowledgeDomain));
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.fighter.getId()))
                    .thenReturn(List.of(f.champion, cavalier));
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.wizard.getId()))
                    .thenReturn(List.of(bladesinging));
        }

        private PlayerCharacter knowledgeCleric() {
            return create(f.cleric, 1, request -> {
                request.setSubclassId(knowledgeDomain.getId());
                request.setSkillProficiencies(List.of("History", "Insight"));
                request.setLanguageProficiencies(List.of("Common"));
            });
        }

        private PendingChoice proficiencyChoice(PlayerCharacter pc, boolean expertise) {
            return f.stored(pc.getId()).getPendingChoices().stream()
                    .filter(c -> c.type() == Type.PROFICIENCY && Boolean.TRUE.equals(c.expertise()) == expertise)
                    .findFirst()
                    .orElseThrow();
        }

        private void choose(PlayerCharacter pc, PendingChoice choice, String... names) {
            f.choose(pc, choice, request -> request.setProficiencies(List.of(names)));
        }

        @Test
        @DisplayName("A Knowledge Domain cleric created with its subclass owes two languages and two of Arcana, "
                + "History, Nature or Religion with Expertise, on Cleric 1 (PHB p.59)")
        void knowledgeOwedAtCreation() {
            PlayerCharacter pc = knowledgeCleric();

            assertThat(pc.getPendingChoices()).filteredOn(c -> c.type() == Type.PROFICIENCY)
                    .extracting(PendingChoice::characterLevel, PendingChoice::classLevel, PendingChoice::count,
                            PendingChoice::expertise, PendingChoice::source)
                    .containsExactlyInAnyOrder(tuple(1, 1, 2, null, Source.CREATION),
                            tuple(1, 1, 2, true, Source.CREATION));
            assertThat(proficiencyChoice(pc, true).proficiencyOptions().skills())
                    .containsExactly("Arcana", "History", "Nature", "Religion");
            assertThat(proficiencyChoice(pc, false).proficiencyOptions().languages())
                    .containsExactly("Common", "Dwarvish", "Elvish", "Giant");
        }

        @Test
        @DisplayName("Its skills gain proficiency and Expertise; one the cleric is proficient in already gains only "
                + "Expertise; both are recorded on Cleric 1")
        void knowledgeSkills() {
            PlayerCharacter pc = knowledgeCleric();

            choose(pc, proficiencyChoice(pc, true), "history", "Arcana");

            assertThat(pc.getSkillProficiencies()).containsExactly("History", "Insight", "Arcana");
            assertThat(pc.getSkillExpertises()).containsExactly("History", "Arcana");
            LevelHistoryEntry first = pc.getLevelHistory().getFirst();
            assertThat(first.proficienciesGained()).isEqualTo(new ProficienciesGained(null, null, null,
                    List.of("Arcana"), null));
            assertThat(first.choices().expertise()).containsExactly("History", "Arcana");
            assertThat(first.choices().proficiencies()).containsExactly("History", "Arcana");
            assertThat(pc.getPendingChoices()).filteredOn(c -> c.type() == Type.PROFICIENCY).hasSize(1);
        }

        @Test
        @DisplayName("Its languages are learned, recorded on Cleric 1; one known already, one not offered, too few "
                + "and another type's answer are 400s")
        void knowledgeLanguages() {
            PlayerCharacter pc = knowledgeCleric();
            PendingChoice languages = proficiencyChoice(pc, false);

            assertThatThrownBy(() -> choose(pc, languages, "Common", "Elvish"))
                    .isInstanceOf(ValidationException.class).hasMessage("Aria already has Common");
            assertThatThrownBy(() -> choose(pc, languages, "Orc", "Elvish"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Choose from Common, Dwarvish, Elvish, Giant, not Orc");
            assertThatThrownBy(() -> choose(pc, languages, "Elvish"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Choose 2 of the proficiencies offered, not 1");
            assertThatThrownBy(() -> f.choose(pc, languages, request -> request.setMetamagic(List.of("Elvish"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("This PROFICIENCY choice takes proficiencies, not metamagic");

            choose(pc, languages, "Elvish", "giant");

            assertThat(pc.getLanguageProficiencies()).containsExactly("Common", "Elvish", "Giant");
            assertThat(pc.getLevelHistory().getFirst().proficienciesGained().languages())
                    .containsExactly("Elvish", "Giant");
            assertThat(pc.getSkillExpertises()).isNullOrEmpty();
        }

        @Test
        @DisplayName("A Knowledge Domain taken on the sheet brings its choices to the level that records it, and "
                + "levelling down past that level takes the skills, Expertise and pending languages away with it")
        void knowledgeLaterAndReversed() {
            PlayerCharacter pc = create(f.fighter, 1, request -> {
                request.setWisdom(14);
                request.setSkillProficiencies(List.of("Athletics"));
            });
            f.levelUp(pc, f.cleric);
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(knowledgeDomain.getId()));

            assertThat(pc.getPendingChoices()).filteredOn(c -> c.type() == Type.PROFICIENCY)
                    .extracting(PendingChoice::characterLevel, PendingChoice::className, PendingChoice::classLevel)
                    .containsOnly(tuple(2, "Cleric", 1));
            choose(pc, proficiencyChoice(pc, true), "Arcana", "Religion");
            assertThat(pc.getSkillExpertises()).containsExactly("Arcana", "Religion");

            f.levelDown(pc);

            assertThat(pc.getSkillProficiencies()).containsExactly("Athletics");
            assertThat(pc.getSkillExpertises()).isEmpty();
            assertThat(pc.getPendingChoices()).extracting(PendingChoice::type).doesNotContain(Type.PROFICIENCY);
        }

        @Test
        @DisplayName("A Cavalier chooses a skill or a language (XGE p.30), brought by the subclass after it")
        void cavalierLanguage() {
            PlayerCharacter pc = create(f.fighter, 3, request -> request.setLanguageProficiencies(List.of("Common")));

            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(cavalier.getId()));
            PendingChoice choice = f.pending(pc, Type.PROFICIENCY);
            assertThat(choice.characterLevel()).isEqualTo(3);
            assertThat(choice.proficiencyOptions().skills()).hasSize(5);

            choose(pc, choice, "Draconic");

            assertThat(pc.getLanguageProficiencies()).containsExactly("Common", "Draconic");
            assertThat(pc.getLevelHistory().get(2).proficienciesGained())
                    .isEqualTo(new ProficienciesGained(null, null, null, null, List.of("Draconic")));
        }

        @Test
        @DisplayName("A Bladesinger's weapon can't be one it's proficient with already, by name, in the plural or by "
                + "category (TCE p.76), and with every option held the choice takes none")
        void bladesingingWeapon() {
            PlayerCharacter pc = create(f.wizard, 2, request -> request.setWeaponProficiencies(List.of("Daggers")));
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(bladesinging.getId()));
            PendingChoice choice = f.pending(pc, Type.PROFICIENCY);

            assertThatThrownBy(() -> choose(pc, choice, "Dagger"))
                    .isInstanceOf(ValidationException.class).hasMessage("Aria already has Dagger");
            choose(pc, choice, "Rapier");
            assertThat(pc.getWeaponProficiencies()).containsExactly("Daggers", "Rapier");
            assertThat(pc.getArmorProficiencies()).contains("light");

            PlayerCharacter martial = create(f.wizard, 2, request -> request.setWeaponProficiencies(
                    List.of("simple", "Martial Weapons")));
            f.choose(martial, f.pending(martial, Type.SUBCLASS), request -> request.setSubclassId(
                    bladesinging.getId()));
            choose(martial, f.pending(martial, Type.PROFICIENCY));
            assertThat(martial.getWeaponProficiencies()).containsExactly("simple", "Martial Weapons");
            assertThat(martial.getLevelHistory().get(1).choices().proficiencies()).isEmpty();
        }

        @Test
        @DisplayName("The backfill finds the choices a subclass taken before they were owed misses, on the level that "
                + "records it, or else the class's subclass level, and finds nothing once they are pending or made, "
                + "each choice on its own")
        void backfill() {
            PlayerCharacter pc = knowledgeCleric();
            pc.setPendingChoices(List.of());

            List<PendingChoice> missed = f.choices.missedChoices(pc);
            assertThat(described(missed)).containsExactly("1 PROFICIENCY×2", "1 PROFICIENCY×2");
            assertThat(missed).extracting(PendingChoice::source).containsOnly(Source.LEVEL_UP);

            pc.setLevelHistory(List.of(pc.getLevelHistory().getFirst().withChoices(LevelChoices.NONE)));
            assertThat(described(f.choices.missedChoices(pc))).containsExactly("1 PROFICIENCY×2", "1 PROFICIENCY×2");

            f.choices.addPending(pc, missed);
            assertThat(f.choices.missedChoices(pc)).isEmpty();

            choose(pc, proficiencyChoice(pc, true), "Arcana", "Nature");
            List<PendingChoice> languages = f.stored(pc.getId()).getPendingChoices();
            pc.setPendingChoices(List.of());
            // The skills chosen don't make the languages choice too
            assertThat(f.choices.missedChoices(pc)).singleElement().satisfies(choice -> {
                assertThat(choice.expertise()).isNull();
                assertThat(choice.proficiencyOptions().languages()).contains("Giant");
            });

            pc.setPendingChoices(languages);
            choose(pc, proficiencyChoice(pc, false), "Dwarvish", "Giant");
            pc.setPendingChoices(List.of());
            assertThat(f.choices.missedChoices(pc)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Conditional grants: a proficiency, or another when the character has it")
    class ConditionalGrants {

        // Path of the Giant: "you learn to speak, read, and write Giant or one other language of your choice if you
        // already know Giant" (BGG p.11), as the content loader stores it (the languages shortened here)
        private final Subclass giant = Subclass.builder().id(UUID.randomUUID()).name("Path of the Giant")
                .characterClass(f.barbarian)
                .features("[{\"level\":3,\"name\":\"Giant Power\",\"description\":\"Giant, and a cantrip\"}]")
                .additionalProficiencies("{\"conditional\":[{\"languages\":[\"Giant\"],\"otherwise\":{\"count\":1,"
                        + "\"languages\":[\"Common\",\"Elvish\",\"Giant\",\"Sylvan\"]}}]}")
                .build();

        @BeforeEach
        void knowTheSubclass() {
            lenient().when(f.subclassRepository.findById(giant.getId())).thenReturn(Optional.of(giant));
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.barbarian.getId()))
                    .thenReturn(List.of(giant));
        }

        private PlayerCharacter giantBarbarian(String... languages) {
            return create(f.barbarian, 3, request -> {
                request.setSubclassId(giant.getId());
                request.setLanguageProficiencies(List.of(languages));
            });
        }

        @Test
        @DisplayName("A character without Giant learns it on the subclass level, owing nothing")
        void grantedWhenLacking() {
            PlayerCharacter pc = giantBarbarian("Common");

            assertThat(pc.getLanguageProficiencies()).containsExactly("Common", "Giant");
            assertThat(pc.getLevelHistory().get(2).proficienciesGained())
                    .isEqualTo(new ProficienciesGained(null, null, null, null, List.of("Giant")));
            assertThat(pc.getPendingChoices()).noneMatch(c -> c.type() == Type.PROFICIENCY);
        }

        @Test
        @DisplayName("A character who knows Giant already owes one other language on the subclass level instead, "
                + "which levelling down takes away")
        void otherwiseWhenHeld() {
            PlayerCharacter pc = giantBarbarian("Common", "giant");

            assertThat(pc.getLanguageProficiencies()).containsExactly("Common", "giant");
            assertThat(pc.getLevelHistory().get(2).proficienciesGained()).isNull();
            PendingChoice choice = f.pending(pc, Type.PROFICIENCY);
            assertThat(choice.characterLevel()).isEqualTo(3);
            assertThat(choice.count()).isEqualTo(1);
            assertThat(choice.proficiencyOptions().languages()).contains("Sylvan");
            assertThat(choice.kenseiWeapons()).isNull();

            assertThatThrownBy(() -> f.choose(pc, choice, request -> request.setProficiencies(List.of("Giant"))))
                    .isInstanceOf(ValidationException.class).hasMessage("Aria already has Giant");
            f.choose(pc, choice, request -> request.setProficiencies(List.of("Sylvan")));
            assertThat(pc.getLanguageProficiencies()).containsExactly("Common", "giant", "Sylvan");
            assertThat(pc.getLevelHistory().get(2).choices().proficiencies()).containsExactly("Sylvan");

            f.levelDown(pc);
            assertThat(pc.getLanguageProficiencies()).containsExactly("Common", "giant");
        }

        @Test
        @DisplayName("Choosing the subclass on the sheet grants Giant, or owes another language when the character "
                + "knows it")
        void chosenOnTheSheet() {
            PlayerCharacter lacking = create(f.barbarian, 3, request -> request.setLanguageProficiencies(
                    List.of("Common")));
            f.choose(lacking, f.pending(lacking, Type.SUBCLASS), request -> request.setSubclassId(giant.getId()));
            assertThat(lacking.getLanguageProficiencies()).containsExactly("Common", "Giant");
            assertThat(f.pending(lacking, Type.PROFICIENCY)).isNull();

            PlayerCharacter knowing = create(f.barbarian, 3, request -> request.setLanguageProficiencies(
                    List.of("Common", "Giant")));
            f.choose(knowing, f.pending(knowing, Type.SUBCLASS), request -> request.setSubclassId(giant.getId()));
            assertThat(knowing.getLanguageProficiencies()).containsExactly("Common", "Giant");
            assertThat(f.pending(knowing, Type.PROFICIENCY)).satisfies(c -> assertThat(c.characterLevel())
                    .isEqualTo(3));
        }

        @Test
        @DisplayName("For a subclass taken before conditional grants: one the sheet lacks is the recompute job's to "
                + "grant and the backfill owes nothing; one it has that the level didn't give owes the choice in the "
                + "backfill and the recompute grants nothing, so neither grants twice")
        void jobsNeverGrantTwice() {
            PlayerCharacter lacking = giantBarbarian("Common");
            lacking.setLanguageProficiencies(List.of("Common"));
            lacking.setLevelHistory(withoutGains(lacking.getLevelHistory()));

            assertThat(f.choices.missedChoices(lacking)).isEmpty();
            assertThat(f.choices.missingSubclassProficiencies(lacking)).singleElement()
                    .satisfies(m -> assertThat(m.description()).isEqualTo("languages Giant"));
            f.choices.storeMissingSubclassProficiencies(lacking);
            assertThat(lacking.getLanguageProficiencies()).containsExactly("Common", "Giant");
            assertThat(f.choices.missingSubclassProficiencies(lacking)).isEmpty();
            assertThat(f.choices.missedChoices(lacking)).as("Giant is now recorded as the level's gain").isEmpty();

            PlayerCharacter knowing = giantBarbarian("Common", "Giant");
            knowing.setPendingChoices(List.of());

            assertThat(f.choices.missingSubclassProficiencies(knowing)).isEmpty();
            List<PendingChoice> missed = f.choices.missedChoices(knowing);
            assertThat(described(missed)).containsExactly("3 PROFICIENCY");
            f.choices.addPending(knowing, missed);
            assertThat(f.choices.missedChoices(knowing)).isEmpty();
            f.choose(knowing, f.pending(knowing, Type.PROFICIENCY),
                    request -> request.setProficiencies(List.of("Elvish")));
            knowing.setPendingChoices(List.of());
            assertThat(f.choices.missedChoices(knowing)).isEmpty();
            assertThat(f.choices.missingSubclassProficiencies(knowing)).isEmpty();
        }

        private static List<LevelHistoryEntry> withoutGains(List<LevelHistoryEntry> history) {
            return history.stream().map(level -> level.withProficienciesGained(null)).toList();
        }
    }

    @Nested
    @DisplayName("Kensei weapons (XGE p.34)")
    class Kensei {

        private final CharacterClass monk = CharacterClass.builder().id(UUID.randomUUID()).name("Monk").hitDice(8)
                .subclassLevel(3).isSpellcaster(false).isPactMagic(false)
                .multiclassRequirements("[{\"ability\":\"DEX\",\"minimum\":13},{\"ability\":\"WIS\",\"minimum\":13}]")
                .build();
        // As the content loader stores it, the lists written out (shortened here)
        private final Subclass kensei = Subclass.builder().id(UUID.randomUUID()).name("Way of the Kensei")
                .characterClass(monk)
                .features("[{\"level\":3,\"name\":\"Path of the Kensei\",\"description\":\"Kensei weapons\"}]")
                .additionalProficiencies("{\"choices\":["
                        + "{\"count\":1,\"tools\":[\"Calligrapher's Supplies\",\"Painter's Supplies\"]},"
                        + "{\"count\":1,\"weapons\":[\"Shortsword\",\"Longsword\",\"Spear\"],\"kensei\":true},"
                        + "{\"count\":1,\"weapons\":[\"Shortbow\",\"Longbow\"],\"kensei\":true},"
                        + level(6) + "," + level(11) + "," + level(17) + "]}")
                .build();

        private static String level(int level) {
            return "{\"level\":" + level + ",\"count\":1,\"weapons\":[\"Shortsword\",\"Longsword\",\"Spear\","
                    + "\"Shortbow\",\"Longbow\"],\"kensei\":true}";
        }

        @BeforeEach
        void knowTheMonk() {
            lenient().when(f.classRepository.findById(monk.getId())).thenReturn(Optional.of(monk));
            lenient().when(f.subclassRepository.findById(kensei.getId())).thenReturn(Optional.of(kensei));
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(monk.getId()))
                    .thenReturn(List.of(kensei));
        }

        private PlayerCharacter kenseiMonk(int level) {
            return create(monk, level, request -> {
                request.setSubclassId(kensei.getId());
                request.setWeaponProficiencies(List.of("simple", "shortswords"));
            });
        }

        private List<PendingChoice> kenseiChoices(PlayerCharacter pc) {
            return f.stored(pc.getId()).getPendingChoices().stream()
                    .filter(c -> c.type() == Type.PROFICIENCY && Boolean.TRUE.equals(c.kenseiWeapons()))
                    .toList();
        }

        private PendingChoice kenseiChoice(PlayerCharacter pc, String option) {
            return kenseiChoices(pc).stream()
                    .filter(c -> c.proficiencyOptions().weapons().contains(option))
                    .findFirst().orElseThrow();
        }

        private void choose(PlayerCharacter pc, PendingChoice choice, String weapon) {
            f.choose(pc, choice, request -> request.setProficiencies(List.of(weapon)));
        }

        @Test
        @DisplayName("A Monk 3 created as a kensei owes its tool and one melee and one ranged kensei weapon on Monk 3")
        void owedAtCreation() {
            PlayerCharacter pc = kenseiMonk(3);

            assertThat(described(pc.getPendingChoices().stream().filter(c -> c.type() == Type.PROFICIENCY).toList()))
                    .containsExactly("3 PROFICIENCY", "3 PROFICIENCY", "3 PROFICIENCY");
            assertThat(kenseiChoices(pc)).extracting(c -> c.proficiencyOptions().weapons())
                    .containsExactlyInAnyOrder(List.of("Shortsword", "Longsword", "Spear"),
                            List.of("Shortbow", "Longbow"));
        }

        @Test
        @DisplayName("A weapon the monk is proficient with becomes a kensei weapon without a new proficiency, one it "
                + "isn't gains it on the level, and both are recorded on the level as kensei weapons")
        void chosen() {
            PlayerCharacter pc = kenseiMonk(3);

            choose(pc, kenseiChoice(pc, "Shortsword"), "Shortsword");
            choose(pc, kenseiChoice(pc, "Longbow"), "Longbow");

            assertThat(KenseiWeapons.of(pc)).containsExactly("Shortsword", "Longbow");
            assertThat(pc.getWeaponProficiencies()).containsExactly("simple", "shortswords", "Longbow");
            LevelHistoryEntry level3 = pc.getLevelHistory().get(2);
            assertThat(level3.choices().kenseiWeapons()).containsExactly("Shortsword", "Longbow");
            assertThat(level3.choices().proficiencies()).isNull();
            assertThat(level3.proficienciesGained().weapons()).containsExactly("Longbow");
            assertThat(f.mapper.toResponse(pc).getKenseiWeapons()).containsExactly("Shortsword", "Longbow");
        }

        @Test
        @DisplayName("Monk 6, 11 and 17 each owe one more kensei weapon, melee or ranged, but not one the monk has; "
                + "levelling down takes it away with its proficiency")
        void laterLevels() {
            PlayerCharacter pc = kenseiMonk(3);
            choose(pc, kenseiChoice(pc, "Spear"), "Spear");
            choose(pc, kenseiChoice(pc, "Shortbow"), "Shortbow");

            f.levelUp(pc, monk);
            f.levelUp(pc, monk);
            assertThat(kenseiChoices(pc)).isEmpty();
            f.levelUp(pc, monk);

            PendingChoice sixth = kenseiChoices(pc).getFirst();
            assertThat(kenseiChoices(pc)).hasSize(1);
            assertThat(sixth.characterLevel()).isEqualTo(6);
            assertThat(sixth.classLevel()).isEqualTo(6);
            assertThatThrownBy(() -> choose(pc, sixth, "Spear"))
                    .isInstanceOf(ValidationException.class).hasMessage("Aria already has the kensei weapon Spear");
            choose(pc, sixth, "Longsword");
            assertThat(KenseiWeapons.of(pc)).containsExactly("Spear", "Shortbow", "Longsword");
            assertThat(pc.getWeaponProficiencies()).contains("Longsword");

            f.levelDown(pc);
            assertThat(KenseiWeapons.of(pc)).containsExactly("Spear", "Shortbow");
            assertThat(pc.getWeaponProficiencies()).doesNotContain("Longsword");
        }

        @Test
        @DisplayName("A kensei created at Monk 6, or given the subclass on the sheet at Monk 6, owes the Monk 6 "
                + "weapon too")
        void createdOrChosenPastTheLevel() {
            PlayerCharacter created = kenseiMonk(6);
            assertThat(kenseiChoices(created)).extracting(PendingChoice::characterLevel).containsExactly(3, 3, 6);

            PlayerCharacter chosen = create(monk, 6, request -> { });
            f.choose(chosen, f.pending(chosen, Type.SUBCLASS), request -> request.setSubclassId(kensei.getId()));
            assertThat(kenseiChoices(chosen)).extracting(PendingChoice::characterLevel).containsExactly(3, 3, 6);
        }

        @Test
        @DisplayName("The backfill owes a kensei who chose its tool before kensei weapons were owed its weapons on "
                + "Monk 3 and 6, each choice on its own, and nothing once they are pending or made")
        void backfill() {
            PlayerCharacter pc = kenseiMonk(6);
            PendingChoice tool = f.stored(pc.getId()).getPendingChoices().stream()
                    .filter(c -> c.type() == Type.PROFICIENCY && c.kenseiWeapons() == null)
                    .findFirst().orElseThrow();
            f.choose(pc, tool, request -> request.setProficiencies(List.of("Painter's Supplies")));
            pc.setPendingChoices(List.of());

            List<PendingChoice> missed = f.choices.missedChoices(pc);
            assertThat(missed).filteredOn(c -> c.type() == Type.PROFICIENCY)
                    .extracting(PendingChoice::characterLevel, PendingChoice::kenseiWeapons)
                    .containsExactly(tuple(3, true), tuple(3, true), tuple(6, true));

            f.choices.addPending(pc, missed);
            assertThat(f.choices.missedChoices(pc)).isEmpty();

            choose(pc, kenseiChoice(pc, "Spear"), "Spear");
            pc.setPendingChoices(List.of());
            assertThat(f.choices.missedChoices(pc)).filteredOn(c -> c.type() == Type.PROFICIENCY)
                    .extracting(PendingChoice::characterLevel).containsExactly(3, 6);
        }
    }

    @Nested
    @DisplayName("Later conditional grants, saving throws and subclass Expertise")
    class LaterGrants {

        // As the content loader stores them
        private final Subclass samurai = Subclass.builder().id(UUID.randomUUID()).name("Samurai")
                .characterClass(f.fighter)
                .features("[{\"level\":7,\"name\":\"Elegant Courtier\",\"description\":\"Wisdom saves\"}]")
                .additionalProficiencies("{\"conditional\":[{\"saves\":[\"WIS\"],\"level\":7,\"otherwise\":"
                        + "{\"count\":1,\"saves\":[\"INT\",\"CHA\"]}}]}")
                .build();
        private final Subclass purpleDragonKnight = Subclass.builder().id(UUID.randomUUID())
                .name("Purple Dragon Knight (Banneret)").characterClass(f.fighter)
                .features("[{\"level\":7,\"name\":\"Royal Envoy\",\"description\":\"Persuasion\"}]")
                .additionalProficiencies("{\"conditional\":[{\"skills\":[\"Persuasion\"],\"expertise\":true,"
                        + "\"level\":7,\"otherwise\":{\"count\":1,\"skills\":[\"Animal Handling\",\"Insight\","
                        + "\"Intimidation\",\"Performance\"]}}]}")
                .build();
        private final Subclass scout = Subclass.builder().id(UUID.randomUUID()).name("Scout")
                .characterClass(f.rogue)
                .features("[{\"level\":3,\"name\":\"Survivalist\",\"description\":\"Nature and Survival\"}]")
                .additionalProficiencies("{\"skills\":[\"Nature\",\"Survival\"],\"expertise\":[\"Nature\","
                        + "\"Survival\"]}")
                .build();

        @BeforeEach
        void knowTheSubclasses() {
            for (Subclass subclass : List.of(samurai, purpleDragonKnight, scout)) {
                lenient().when(f.subclassRepository.findById(subclass.getId())).thenReturn(Optional.of(subclass));
            }
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.fighter.getId()))
                    .thenReturn(List.of(f.champion, purpleDragonKnight, samurai));
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.rogue.getId()))
                    .thenReturn(List.of(scout));
        }

        private PlayerCharacter fighter(Subclass subclass, int level, Consumer<CharacterCreateRequest> adjust) {
            return create(f.fighter, level, request -> {
                request.setSubclassId(subclass.getId());
                request.setSavingThrowProficiencies(List.of("STR", "CON"));
                adjust.accept(request);
            });
        }

        @Test
        @DisplayName("A Samurai gains Wisdom saving throws at Fighter 7, recorded on that level, and levelling down past "
                + "it takes them away (XGE p.31)")
        void samuraiWisdom() {
            PlayerCharacter pc = fighter(samurai, 6, request -> { });
            assertThat(pc.getSavingThrowProficiencies()).containsExactly("STR", "CON");

            f.levelUp(pc, f.fighter);

            assertThat(pc.getSavingThrowProficiencies()).containsExactly("STR", "CON", "WIS");
            assertThat(pc.getLevelHistory().get(6).proficienciesGained())
                    .isEqualTo(new ProficienciesGained(null, null, null, null, null, List.of("WIS")));
            assertThat(pc.getPendingChoices()).noneMatch(c -> c.type() == Type.PROFICIENCY);

            f.levelDown(pc);
            assertThat(pc.getSavingThrowProficiencies()).containsExactly("STR", "CON");
        }

        @Test
        @DisplayName("A Samurai with Wisdom saving throws already, by any spelling, owes Intelligence or Charisma at "
                + "Fighter 7 instead; created there or given the subclass on the sheet past it")
        void samuraiOtherwise() {
            PlayerCharacter pc = fighter(samurai, 7, request -> request.setSavingThrowProficiencies(
                    List.of("STR", "CON", "Wisdom")));

            PendingChoice choice = f.pending(pc, Type.PROFICIENCY);
            assertThat(choice.characterLevel()).isEqualTo(7);
            assertThat(choice.proficiencyOptions().saves()).containsExactly("INT", "CHA");
            assertThatThrownBy(() -> f.choose(pc, choice, request -> request.setProficiencies(List.of("WIS"))))
                    .isInstanceOf(ValidationException.class).hasMessage("Choose from INT, CHA, not WIS");

            f.choose(pc, choice, request -> request.setProficiencies(List.of("cha")));
            assertThat(pc.getSavingThrowProficiencies()).containsExactly("STR", "CON", "Wisdom", "CHA");
            assertThat(pc.getLevelHistory().get(6).choices().proficiencies()).containsExactly("CHA");
            f.levelDown(pc);
            assertThat(pc.getSavingThrowProficiencies()).containsExactly("STR", "CON", "Wisdom");

            PlayerCharacter chosen = create(f.fighter, 8, request -> request.setSavingThrowProficiencies(
                    List.of("STR", "CON")));
            f.choose(chosen, f.pending(chosen, Type.SUBCLASS), request -> request.setSubclassId(samurai.getId()));
            assertThat(chosen.getSavingThrowProficiencies()).containsExactly("STR", "CON", "WIS");
            assertThat(chosen.getLevelHistory().get(6).proficienciesGained().saves()).containsExactly("WIS");
        }

        @Test
        @DisplayName("A Purple Dragon Knight gains Persuasion with Expertise at Fighter 7; one proficient already owes "
                + "another skill and gains the Expertise anyway (SCAG p.128); levelling down takes both away")
        void royalEnvoy() {
            PlayerCharacter lacking = fighter(purpleDragonKnight, 7, request -> request.setSkillProficiencies(
                    List.of("Athletics")));
            assertThat(lacking.getSkillProficiencies()).containsExactly("Athletics", "Persuasion");
            assertThat(lacking.getSkillExpertises()).containsExactly("Persuasion");
            assertThat(lacking.getLevelHistory().get(6).choices().expertise()).containsExactly("Persuasion");
            assertThat(lacking.getPendingChoices()).noneMatch(c -> c.type() == Type.PROFICIENCY);
            f.levelDown(lacking);
            assertThat(lacking.getSkillProficiencies()).containsExactly("Athletics");
            assertThat(lacking.getSkillExpertises()).isEmpty();

            PlayerCharacter held = fighter(purpleDragonKnight, 7, request -> request.setSkillProficiencies(
                    List.of("persuasion")));
            assertThat(held.getSkillExpertises()).containsExactly("persuasion");
            PendingChoice other = f.pending(held, Type.PROFICIENCY);
            assertThat(other.characterLevel()).isEqualTo(7);
            assertThat(other.expertise()).isNull();
            f.choose(held, other, request -> request.setProficiencies(List.of("Insight")));
            assertThat(held.getSkillProficiencies()).containsExactly("persuasion", "Insight");
            assertThat(held.getSkillExpertises()).containsExactly("persuasion");
        }

        @Test
        @DisplayName("A Scout gains Nature and Survival with Expertise in both, a skill held already gaining only "
                + "Expertise, recorded on Rogue 3, and levelling down takes them away (XGE p.47)")
        void scoutExpertise() {
            PlayerCharacter pc = create(f.rogue, 3, request -> {
                request.setSubclassId(scout.getId());
                request.setSkillProficiencies(List.of("Survival", "Stealth"));
            });

            assertThat(pc.getSkillProficiencies()).containsExactly("Survival", "Stealth", "Nature");
            assertThat(pc.getSkillExpertises()).containsExactlyInAnyOrder("Nature", "Survival");
            LevelHistoryEntry third = pc.getLevelHistory().get(2);
            assertThat(third.proficienciesGained().skills()).containsExactly("Nature");
            assertThat(third.choices().expertise()).containsExactlyInAnyOrder("Nature", "Survival");

            f.levelDown(pc);
            assertThat(pc.getSkillProficiencies()).containsExactly("Survival", "Stealth");
            assertThat(pc.getSkillExpertises()).isEmpty();
        }

        @Test
        @DisplayName("The jobs: a Fighter 7 Samurai lacking Wisdom saves gets them from the recompute, recorded on "
                + "Fighter 7; one with them that Fighter 7 didn't give owes the choice in the backfill; a Scout "
                + "lacking its Expertise gets it from the recompute; neither grants twice")
        void jobs() {
            PlayerCharacter lacking = fighter(samurai, 7, request -> { });
            lacking.setSavingThrowProficiencies(List.of("STR", "CON"));
            lacking.setLevelHistory(lacking.getLevelHistory().stream()
                    .map(level -> level.withProficienciesGained(null)).toList());

            assertThat(f.choices.missedChoices(lacking)).isEmpty();
            assertThat(f.choices.missingSubclassProficiencies(lacking)).singleElement().satisfies(m -> {
                assertThat(m.description()).isEqualTo("saving throws WIS");
                assertThat(m.className()).isEqualTo("Fighter");
                assertThat(m.classLevel()).isEqualTo(7);
            });
            f.choices.storeMissingSubclassProficiencies(lacking);
            assertThat(lacking.getSavingThrowProficiencies()).containsExactly("STR", "CON", "WIS");
            assertThat(lacking.getLevelHistory().get(6).proficienciesGained().saves()).containsExactly("WIS");
            assertThat(f.choices.missingSubclassProficiencies(lacking)).isEmpty();
            assertThat(f.choices.missedChoices(lacking)).isEmpty();

            PlayerCharacter below = fighter(samurai, 6, request -> { });
            assertThat(f.choices.missingSubclassProficiencies(below)).as("Fighter 7 isn't reached").isEmpty();

            PlayerCharacter held = fighter(samurai, 7, request -> request.setSavingThrowProficiencies(
                    List.of("STR", "CON", "WIS")));
            held.setPendingChoices(List.of());
            assertThat(f.choices.missingSubclassProficiencies(held)).isEmpty();
            List<PendingChoice> missed = f.choices.missedChoices(held);
            assertThat(described(missed.stream().filter(c -> c.type() == Type.PROFICIENCY).toList()))
                    .containsExactly("7 PROFICIENCY");
            f.choices.addPending(held, missed);
            assertThat(f.choices.missedChoices(held)).isEmpty();

            PlayerCharacter scoutPc = create(f.rogue, 3, request -> request.setSubclassId(scout.getId()));
            scoutPc.setSkillExpertises(List.of());
            scoutPc.setLevelHistory(scoutPc.getLevelHistory().stream()
                    .map(level -> level.withChoices(level.choices().withExpertise(null))).toList());
            assertThat(f.choices.missingSubclassProficiencies(scoutPc)).singleElement()
                    .satisfies(m -> assertThat(m.description()).isEqualTo("expertise Nature, Survival"));
            f.choices.storeMissingSubclassProficiencies(scoutPc);
            assertThat(scoutPc.getSkillExpertises()).containsExactly("Nature", "Survival");
            assertThat(scoutPc.getLevelHistory().get(2).choices().expertise()).containsExactly("Nature", "Survival");
            assertThat(f.choices.missingSubclassProficiencies(scoutPc)).isEmpty();
        }
    }

    @Nested
    @DisplayName("A subclass's cantrips of the player's choice")
    class SubclassCantrips {

        // As the content loader stores them
        private final Subclass arcaneArcher = Subclass.builder().id(UUID.randomUUID()).name("Arcane Archer")
                .characterClass(f.fighter)
                .features("[{\"level\":3,\"name\":\"Arcane Archer Lore\",\"description\":\"A skill and a cantrip\"}]")
                .additionalProficiencies("{\"cantrips\":[{\"count\":1,\"spells\":[\"Prestidigitation\","
                        + "\"Druidcraft\"]}]}")
                .build();
        private final Subclass natureDomain = Subclass.builder().id(UUID.randomUUID()).name("Nature Domain")
                .characterClass(f.cleric)
                .features("[{\"level\":1,\"name\":\"Acolyte of Nature\",\"description\":\"A druid cantrip\"}]")
                .additionalProficiencies("{\"cantrips\":[{\"count\":1,\"spellList\":\"Druid\"}]}")
                .build();

        @BeforeEach
        void knowTheSubclasses() {
            spell("Druidcraft", 0, "Druid");
            spell("Prestidigitation", 0, "Bard", "Sorcerer", "Warlock", "Wizard");
            spell("Shillelagh", 0, "Druid");
            for (Subclass subclass : List.of(arcaneArcher, natureDomain)) {
                lenient().when(f.subclassRepository.findById(subclass.getId())).thenReturn(Optional.of(subclass));
            }
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.fighter.getId()))
                    .thenReturn(List.of(arcaneArcher, f.champion));
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.cleric.getId()))
                    .thenReturn(List.of(natureDomain));
        }

        private void learn(PlayerCharacter pc, PendingChoice choice, String... names) {
            f.choose(pc, choice, request -> request.setSpells(List.of(names)));
        }

        @Test
        @DisplayName("An Arcane Archer owes prestidigitation or druidcraft on Fighter 3, learned as the subclass's, "
                + "recorded on the level, and forgotten by levelling down past it (XGE p.28)")
        void arcaneArcher() {
            PlayerCharacter pc = create(f.fighter, 3, request -> { });
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(arcaneArcher.getId()));

            PendingChoice choice = f.pending(pc, Type.SUBCLASS_CANTRIP);
            assertThat(choice.characterLevel()).isEqualTo(3);
            assertThat(choice.options()).containsExactly("Prestidigitation", "Druidcraft");
            assertThat(choice.maxSpellLevel()).isZero();
            assertThatThrownBy(() -> learn(pc, choice, "Shillelagh"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Choose from Prestidigitation, Druidcraft, not Shillelagh");
            assertThatThrownBy(() -> learn(pc, choice, "Druidcraft", "Prestidigitation"))
                    .isInstanceOf(ValidationException.class).hasMessage("Choose 1 cantrip, not 2");
            assertThatThrownBy(() -> f.choose(pc, choice, request -> request.setProficiencies(List.of("Druidcraft"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("This SUBCLASS_CANTRIP choice takes spells, not proficiencies");

            learn(pc, choice, "druidcraft");

            assertThat(names(pc, "subclass:Arcane Archer")).containsExactly("Druidcraft");
            assertThat(pc.getLevelHistory().get(2).choices().subclassCantrips()).containsExactly("Druidcraft");
            assertThat(f.pending(pc, Type.SUBCLASS_CANTRIP)).isNull();

            f.levelDown(pc);
            assertThat(names(pc, "subclass:Arcane Archer")).isEmpty();
        }

        @Test
        @DisplayName("A cantrip known already can't be chosen, and with one option left only it is taken")
        void knownAlready() {
            PlayerCharacter pc = create(f.fighter, 3, request -> request.setSpellsKnown(List.of(
                    new KnownSpell(null, "Druidcraft", 0, "race:Firbolg", null, null, true, null, null))));
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(arcaneArcher.getId()));
            PendingChoice choice = f.pending(pc, Type.SUBCLASS_CANTRIP);

            assertThatThrownBy(() -> learn(pc, choice, "Druidcraft"))
                    .isInstanceOf(ValidationException.class).hasMessage("Aria already knows Druidcraft");
            learn(pc, choice, "Prestidigitation");
            assertThat(names(pc, "subclass:Arcane Archer")).containsExactly("Prestidigitation");
        }

        @Test
        @DisplayName("A Nature Domain cleric created with its subclass owes a druid cantrip on Cleric 1, from the druid "
                + "list, apart from its cleric cantrips (PHB p.61)")
        void natureDomain() {
            PlayerCharacter pc = create(f.cleric, 1, request -> request.setSubclassId(natureDomain.getId()));

            PendingChoice choice = f.pending(pc, Type.SUBCLASS_CANTRIP);
            assertThat(choice.characterLevel()).isEqualTo(1);
            assertThat(choice.options()).isNull();
            assertThat(choice.spellList()).isEqualTo("Druid");
            assertThat(choice.source()).isEqualTo(Source.CREATION);
            assertThatThrownBy(() -> learn(pc, choice, "Fire Bolt"))
                    .isInstanceOf(ValidationException.class).hasMessage("Fire Bolt isn't on the Druid spell list");
            assertThatThrownBy(() -> learn(pc, choice, "Cure Wounds"))
                    .isInstanceOf(ValidationException.class).hasMessage("Cure Wounds is a level 1 spell, not a cantrip");

            learn(pc, choice, "Shillelagh");
            assertThat(names(pc, "subclass:Nature Domain")).containsExactly("Shillelagh");
            assertThat(f.pending(pc, Type.CANTRIPS)).isNotNull();
        }

        @Test
        @DisplayName("The backfill owes a subclass's cantrip on the level that records the subclass, and nothing once "
                + "it is pending or made")
        void backfill() {
            PlayerCharacter pc = create(f.cleric, 1, request -> request.setSubclassId(natureDomain.getId()));
            pc.setPendingChoices(List.of());

            List<PendingChoice> missed = f.choices.missedChoices(pc);
            assertThat(missed).filteredOn(c -> c.type() == Type.SUBCLASS_CANTRIP).singleElement()
                    .satisfies(c -> assertThat(c.spellList()).isEqualTo("Druid"));
            f.choices.addPending(pc, missed);
            assertThat(f.choices.missedChoices(pc)).isEmpty();

            learn(pc, f.pending(pc, Type.SUBCLASS_CANTRIP), "Druidcraft");
            pc.setPendingChoices(List.of());
            assertThat(f.choices.missedChoices(pc)).noneMatch(c -> c.type() == Type.SUBCLASS_CANTRIP);
        }
    }

    @Nested
    @DisplayName("Improved Minor Illusion (PHB p.118)")
    class ImprovedMinorIllusion {

        // As the content loader stores it
        private final Subclass illusion = Subclass.builder().id(UUID.randomUUID()).name("School of Illusion")
                .characterClass(f.wizard)
                .features("[{\"level\":2,\"name\":\"Improved Minor Illusion\",\"description\":\"Minor illusion\"}]")
                .additionalProficiencies("{\"cantrips\":[{\"count\":1,\"grant\":\"Minor Illusion\","
                        + "\"spellList\":\"Wizard\"}]}")
                .build();

        @BeforeEach
        void knowTheSchool() {
            spell("Minor Illusion", 0, "Bard", "Sorcerer", "Warlock", "Wizard");
            spell("Fire Bolt", 0, "Sorcerer", "Wizard");
            spell("Druidcraft", 0, "Druid");
            lenient().when(f.subclassRepository.findById(illusion.getId())).thenReturn(Optional.of(illusion));
            lenient().when(f.subclassRepository.findByCharacterClassIdOrderByNameAsc(f.wizard.getId()))
                    .thenReturn(List.of(illusion));
        }

        private PlayerCharacter illusionist(KnownSpell... known) {
            PlayerCharacter pc = create(f.wizard, 2, request -> request.setSpellsKnown(List.of(known)));
            f.choose(pc, f.pending(pc, Type.SUBCLASS), request -> request.setSubclassId(illusion.getId()));
            return pc;
        }

        // The character as one given the school before the cantrip was granted: no subclass cantrip, none recorded
        private void asBefore(PlayerCharacter pc) {
            List<KnownSpell> known = new ArrayList<>(pc.getSpellsKnown());
            known.removeIf(s -> "subclass:School of Illusion".equals(s.source()));
            pc.setSpellsKnown(known);
            List<LevelHistoryEntry> history = new ArrayList<>(pc.getLevelHistory());
            LevelHistoryEntry level = history.get(1);
            history.set(1, level.withChoices(level.choices().withSubclassCantrips(null)));
            pc.setLevelHistory(history);
            pc.setPendingChoices(List.of());
        }

        @Test
        @DisplayName("Choosing the school teaches Minor Illusion as the subclass's, recorded on Wizard 2, with no "
                + "choice owed; levelling down forgets it")
        void granted() {
            PlayerCharacter pc = illusionist();

            assertThat(names(pc, "subclass:School of Illusion")).containsExactly("Minor Illusion");
            assertThat(pc.getLevelHistory().get(1).choices().subclassCantrips()).containsExactly("Minor Illusion");
            assertThat(f.pending(pc, Type.SUBCLASS_CANTRIP)).isNull();

            f.levelDown(pc);
            assertThat(names(pc, "subclass:School of Illusion")).isEmpty();
        }

        @Test
        @DisplayName("A wizard who knows Minor Illusion owes another wizard cantrip instead, learned as the subclass's")
        void knownAlready() {
            PlayerCharacter pc = illusionist(classSpell("Minor Illusion", 0, "Wizard"));

            PendingChoice choice = f.pending(pc, Type.SUBCLASS_CANTRIP);
            assertThat(choice.options()).isNull();
            assertThat(choice.spellList()).isEqualTo("Wizard");
            assertThat(names(pc, "subclass:School of Illusion")).isEmpty();
            assertThatThrownBy(() -> f.choose(pc, choice, request -> request.setSpells(List.of("Minor Illusion"))))
                    .isInstanceOf(ValidationException.class).hasMessage("Aria already knows Minor Illusion");
            assertThatThrownBy(() -> f.choose(pc, choice, request -> request.setSpells(List.of("Druidcraft"))))
                    .isInstanceOf(ValidationException.class).hasMessage("Druidcraft isn't on the Wizard spell list");

            f.choose(pc, choice, request -> request.setSpells(List.of("Fire Bolt")));
            assertThat(names(pc, "subclass:School of Illusion")).containsExactly("Fire Bolt");
            assertThat(pc.getLevelHistory().get(1).choices().subclassCantrips()).containsExactly("Fire Bolt");
        }

        @Test
        @DisplayName("A wizard created with the school learns it too")
        void atCreation() {
            PlayerCharacter pc = create(f.wizard, 2, request -> request.setSubclassId(illusion.getId()));
            assertThat(names(pc, "subclass:School of Illusion")).containsExactly("Minor Illusion");
            assertThat(f.pending(pc, Type.SUBCLASS_CANTRIP)).isNull();
        }

        @Test
        @DisplayName("For a wizard given the school before: recompute grants Minor Illusion when unknown, and the "
                + "backfill owes the choice only when it is known")
        void existingCharacters() {
            PlayerCharacter pc = illusionist();
            asBefore(pc);

            assertThat(f.choices.missedChoices(pc)).noneMatch(c -> c.type() == Type.SUBCLASS_CANTRIP);
            assertThat(f.choices.missingSubclassCantrips(pc)).singleElement().satisfies(missing -> {
                assertThat(missing.cantrip()).isEqualTo("Minor Illusion");
                assertThat(missing.levelIndex()).isEqualTo(1);
            });
            f.choices.storeMissingSubclassCantrips(pc);
            assertThat(names(pc, "subclass:School of Illusion")).containsExactly("Minor Illusion");
            assertThat(pc.getLevelHistory().get(1).choices().subclassCantrips()).containsExactly("Minor Illusion");
            assertThat(f.choices.missingSubclassCantrips(pc)).isEmpty();

            PlayerCharacter knowing = illusionist(classSpell("Minor Illusion", 0, "Wizard"));
            asBefore(knowing);
            assertThat(f.choices.missingSubclassCantrips(knowing)).isEmpty();
            assertThat(f.choices.missedChoices(knowing)).filteredOn(c -> c.type() == Type.SUBCLASS_CANTRIP)
                    .singleElement().satisfies(c -> assertThat(c.spellList()).isEqualTo("Wizard"));
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
            Subclass thief = Subclass.builder().id(UUID.randomUUID()).name("Thief").characterClass(f.rogue).build();
            when(f.subclassRepository.findById(thief.getId())).thenReturn(Optional.of(thief));
            rogue.setMulticlassEntries(List.of(rogue.getMulticlassEntries().getFirst().withSubclass(thief.getId(),
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
        @DisplayName("A sorcerer levelled before M18 is owed Metamagic at 3rd and 10th level, less the Metamagic features no level records")
        void metamagicBackfilled() {
            PlayerCharacter sorcerer = legacy(f.sorcerer, 10);
            recordAsi(sorcerer, 4);
            recordAsi(sorcerer, 8);

            assertThat(described(f.choices.missedChoices(sorcerer))).containsExactly("3 METAMAGIC×2", "10 METAMAGIC");
            assertThat(f.choices.missedChoices(sorcerer).getFirst().options()).hasSize(8);

            sorcerer.setFeatures(new ArrayList<>(List.of(new Feature("Metamagic: Subtle Spell", null, "Sorcerer"))));
            assertThat(described(f.choices.missedChoices(sorcerer))).containsExactly("3 METAMAGIC", "10 METAMAGIC");
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
