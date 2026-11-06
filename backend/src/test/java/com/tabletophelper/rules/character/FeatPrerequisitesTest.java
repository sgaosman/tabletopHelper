package com.tabletophelper.rules.character;

import com.tabletophelper.content.internal.FeatLoader;
import com.tabletophelper.content.internal.LoadContext;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.character.FeatPrerequisites.AbilityMinimum;
import com.tabletophelper.rules.character.FeatPrerequisites.Facts;
import com.tabletophelper.rules.character.FeatPrerequisites.FeatRequirement;
import com.tabletophelper.rules.character.FeatPrerequisites.LevelRequirement;
import com.tabletophelper.rules.character.FeatPrerequisites.PrerequisiteAlternative;
import com.tabletophelper.rules.character.FeatPrerequisites.ProficiencyKind;
import com.tabletophelper.rules.character.FeatPrerequisites.ProficiencyRequirement;
import com.tabletophelper.rules.character.FeatPrerequisites.RaceRequirement;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feat prerequisites (D159): every PHB, XGE and TCE feat's prerequisite as the books give it, read from the 5etools
 * data by FeatLoader, and whether characters meet them.
 */
class FeatPrerequisitesTest {

    private static Map<String, Feat> feats;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadFeats() {
        FeatRepository repository = mock(FeatRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.saveAll(anyIterable())).thenAnswer(i -> new ArrayList<>(i.<Collection<Feat>>getArgument(0)));
        new FeatLoader(repository, JacksonDefaults.mapper()).load(new LoadContext(JacksonDefaults.mapper()));
        ArgumentCaptor<Collection<Feat>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(repository).saveAll(saved.capture());
        feats = saved.getValue().stream().collect(Collectors.toMap(f -> f.getName() + "|" + f.getSource(), f -> f));
    }

    private static List<PrerequisiteAlternative> prerequisite(String name, String source) {
        Feat feat = feats.get(name + "|" + source);
        assertThat(feat).as(name + " (" + source + ")").isNotNull();
        return feat.getPrerequisite() == null ? List.of() : feat.getPrerequisite().alternatives();
    }

    // ── The books' prerequisites ─────────────────────────────────

    private static PrerequisiteAlternative ability(Ability ability, int minimum) {
        return new PrerequisiteAlternative(List.of(new AbilityMinimum(ability, minimum)), null, false, false, null,
                null, null, null, null, null);
    }

    private static PrerequisiteAlternative races(RaceRequirement... races) {
        return new PrerequisiteAlternative(null, List.of(races), false, false, null, null, null, null, null, null);
    }

    private static RaceRequirement race(String name) {
        return new RaceRequirement(name, null, null);
    }

    private static RaceRequirement subrace(String name, String subrace) {
        return new RaceRequirement(name, subrace, null);
    }

    private static PrerequisiteAlternative proficiency(ProficiencyKind kind, String name) {
        return new PrerequisiteAlternative(null, null, false, false,
                List.of(new ProficiencyRequirement(kind, name)), null, null, null, null, null);
    }

    private static final PrerequisiteAlternative CASTS_A_SPELL =
            new PrerequisiteAlternative(null, null, true, false, null, null, null, null, null, null);
    private static final PrerequisiteAlternative SPELLCASTING_FEATURE =
            new PrerequisiteAlternative(null, null, false, true, null, null, null, null, null, null);

    @Test
    @DisplayName("Every PHB feat's prerequisite is the book's (PHB pp.165-170), and the others have none")
    void phbPrerequisites() {
        Map<String, List<PrerequisiteAlternative>> expected = new LinkedHashMap<>();
        expected.put("Defensive Duelist", List.of(ability(Ability.DEX, 13)));
        expected.put("Elemental Adept", List.of(CASTS_A_SPELL));
        expected.put("Grappler", List.of(ability(Ability.STR, 13)));
        expected.put("Heavily Armored", List.of(proficiency(ProficiencyKind.ARMOR, "medium")));
        expected.put("Heavy Armor Master", List.of(proficiency(ProficiencyKind.ARMOR, "heavy")));
        expected.put("Inspiring Leader", List.of(ability(Ability.CHA, 13)));
        expected.put("Medium Armor Master", List.of(proficiency(ProficiencyKind.ARMOR, "medium")));
        expected.put("Moderately Armored", List.of(proficiency(ProficiencyKind.ARMOR, "light")));
        expected.put("Ritual Caster", List.of(new PrerequisiteAlternative(List.of(
                new AbilityMinimum(Ability.INT, 13), new AbilityMinimum(Ability.WIS, 13)),
                null, false, false, null, null, null, null, null, null)));
        expected.put("Skulker", List.of(ability(Ability.DEX, 13)));
        expected.put("Spell Sniper", List.of(CASTS_A_SPELL));
        expected.put("War Caster", List.of(CASTS_A_SPELL));
        assertBook("PHB", expected, 42);
    }

    @Test
    @DisplayName("Every XGE feat's prerequisite is the book's (XGE pp.73-75): the racial feats")
    void xgePrerequisites() {
        Map<String, List<PrerequisiteAlternative>> expected = new LinkedHashMap<>();
        expected.put("Bountiful Luck", List.of(races(race("halfling"))));
        expected.put("Dragon Fear", List.of(races(race("dragonborn"))));
        expected.put("Dragon Hide", List.of(races(race("dragonborn"))));
        expected.put("Drow High Magic", List.of(races(subrace("elf", "drow"))));
        expected.put("Dwarven Fortitude", List.of(races(race("dwarf"))));
        expected.put("Elven Accuracy", List.of(races(race("elf"), race("half-elf"))));
        expected.put("Fade Away", List.of(races(race("gnome"))));
        expected.put("Fey Teleportation", List.of(races(subrace("elf", "high"))));
        expected.put("Flames of Phlegethos", List.of(races(race("tiefling"))));
        expected.put("Infernal Constitution", List.of(races(race("tiefling"))));
        expected.put("Orcish Fury", List.of(races(race("half-orc"))));
        expected.put("Prodigy", List.of(races(race("half-elf"), race("half-orc"), race("human"))));
        expected.put("Second Chance", List.of(races(race("halfling"))));
        expected.put("Squat Nimbleness", List.of(races(race("dwarf"),
                new RaceRequirement("small race", null, "a Small race"))));
        expected.put("Wood Elf Magic", List.of(races(subrace("elf", "wood"))));
        assertBook("XGE", expected, 15);
    }

    @Test
    @DisplayName("Every TCE feat's prerequisite is the book's (TCE pp.79-81), and the others have none")
    void tcePrerequisites() {
        Map<String, List<PrerequisiteAlternative>> expected = new LinkedHashMap<>();
        expected.put("Eldritch Adept", List.of(SPELLCASTING_FEATURE));
        expected.put("Fighting Initiate", List.of(proficiency(ProficiencyKind.WEAPON, "martial")));
        expected.put("Metamagic Adept", List.of(SPELLCASTING_FEATURE));
        assertBook("TCE", expected, 15);
    }

    private static void assertBook(String source, Map<String, List<PrerequisiteAlternative>> expected, int featCount) {
        List<Feat> book = feats.values().stream().filter(f -> source.equals(f.getSource())).toList();
        assertThat(book).as(source + " feats").hasSize(featCount);
        for (Feat feat : book) {
            assertThat(prerequisite(feat.getName(), source)).as(feat.getName())
                    .isEqualTo(expected.getOrDefault(feat.getName(), List.of()));
        }
        assertThat(book).extracting(Feat::getName).containsAll(expected.keySet());
    }

    // ── Meeting them ─────────────────────────────────────────────

    private static Facts facts(Map<Ability, Integer> scores, String race, String baseRace, String size,
                               boolean castsSpell, boolean spellcastingFeature, List<String> armour,
                               List<String> weapons, Set<String> feats, int level, Map<String, Integer> classes,
                               String background) {
        return new Facts(scores, race, baseRace, size, castsSpell, spellcastingFeature, armour, weapons, feats, level,
                classes, background);
    }

    private static Map<Ability, Integer> scores(int strength) {
        Map<Ability, Integer> scores = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) scores.put(ability, 10);
        scores.put(Ability.STR, strength);
        return scores;
    }

    // A level 4 human fighter with 10s but its Strength, light armour and simple weapons, no spells and no feats
    private static Facts fighter(int strength) {
        return facts(scores(strength), "Human", null, "Medium", false, false, List.of("light"), List.of("simple"),
                Set.of(), 4, Map.of("Fighter", 4), "Soldier");
    }

    @Test
    @DisplayName("Grappler needs Strength 13: 12 doesn't meet it and says so, 13 does")
    void grappler() {
        List<PrerequisiteAlternative> grappler = prerequisite("Grappler", "PHB");
        assertThat(FeatPrerequisites.unmet(grappler, fighter(12))).containsExactly("Strength 13 (you have 12)");
        assertThat(FeatPrerequisites.meets(grappler, fighter(13))).isTrue();
    }

    @Test
    @DisplayName("Heavily Armored needs medium armour: proficiency with it meets it, in any of its spellings, light armour alone doesn't")
    void heavilyArmored() {
        List<PrerequisiteAlternative> heavily = prerequisite("Heavily Armored", "PHB");
        Facts medium = facts(scores(10), "Human", null, "Medium", false, false, List.of("light", "medium", "shield"),
                List.of(), Set.of(), 4, Map.of("Cleric", 4), null);
        Facts titled = facts(scores(10), "Human", null, "Medium", false, false, List.of("Light", "Medium armor"),
                List.of(), Set.of(), 4, Map.of("Cleric", 4), null);
        assertThat(FeatPrerequisites.meets(heavily, medium)).isTrue();
        assertThat(FeatPrerequisites.meets(heavily, titled)).isTrue();
        assertThat(FeatPrerequisites.unmet(heavily, fighter(15))).containsExactly("Proficiency with medium armor");
    }

    @Test
    @DisplayName("Elemental Adept and War Caster need a spell: a race's or a feat's spell counts as a class's does")
    void castsASpell() {
        Facts tiefling = facts(scores(10), "Tiefling", null, "Medium", true, false, List.of(), List.of(), Set.of(),
                4, Map.of("Fighter", 4), null);
        for (String feat : List.of("Elemental Adept", "War Caster", "Spell Sniper")) {
            List<PrerequisiteAlternative> prerequisite = prerequisite(feat, "PHB");
            assertThat(FeatPrerequisites.meets(prerequisite, tiefling)).as(feat).isTrue();
            assertThat(FeatPrerequisites.unmet(prerequisite, fighter(10))).as(feat)
                    .containsExactly("The ability to cast at least one spell");
        }
    }

    @Test
    @DisplayName("Eldritch Adept needs a class's Spellcasting or Pact Magic: a race's spell doesn't give it")
    void spellcastingFeature() {
        List<PrerequisiteAlternative> adept = prerequisite("Eldritch Adept", "TCE");
        Facts tiefling = facts(scores(10), "Tiefling", null, "Medium", true, false, List.of(), List.of(), Set.of(),
                4, Map.of("Fighter", 4), null);
        Facts warlock = facts(scores(10), "Human", null, "Medium", true, true, List.of("light"), List.of("simple"),
                Set.of(), 4, Map.of("Warlock", 4), null);
        assertThat(FeatPrerequisites.unmet(adept, tiefling)).containsExactly("The Spellcasting or Pact Magic feature");
        assertThat(FeatPrerequisites.meets(adept, warlock)).isTrue();
    }

    @Test
    @DisplayName("Strike of the Giants needs martial weapons or the Giant Foundling background: either alone meets it, neither names both")
    void alternatives() {
        List<PrerequisiteAlternative> strike = prerequisite("Strike of the Giants", "BGG");
        Facts martial = facts(scores(10), "Human", null, "Medium", false, false, List.of(), List.of("simple", "martial"),
                Set.of(), 1, Map.of("Fighter", 1), "Soldier");
        Facts foundling = facts(scores(10), "Human", null, "Medium", false, false, List.of(), List.of("simple"),
                Set.of(), 1, Map.of("Wizard", 1), "Giant Foundling");
        Facts neither = facts(scores(10), "Human", null, "Medium", false, false, List.of(), List.of("simple"),
                Set.of(), 1, Map.of("Wizard", 1), "Sage");
        assertThat(FeatPrerequisites.meets(strike, martial)).isTrue();
        assertThat(FeatPrerequisites.meets(strike, foundling)).isTrue();
        assertThat(FeatPrerequisites.unmet(strike, neither))
                .containsExactly("Proficiency with martial weapons, or the Giant Foundling background");
    }

    @Test
    @DisplayName("A race requirement is met by the race or a subrace of it; a subrace requirement only by that subrace; a Small race by its size")
    void races() {
        Facts drow = facts(scores(10), "Drow Elf", "Elf", "Medium", false, false, List.of(), List.of(), Set.of(), 4,
                Map.of("Rogue", 4), null);
        Facts woodElf = facts(scores(10), "Wood Elf", "Elf", "Medium", false, false, List.of(), List.of(), Set.of(), 4,
                Map.of("Rogue", 4), null);
        Facts halfling = facts(scores(10), "Lightfoot Halfling", "Halfling", "Small", false, false, List.of(),
                List.of(), Set.of(), 4, Map.of("Rogue", 4), null);
        Facts kaladesh = facts(scores(10), "Vahadar Elf (Kaladesh)", "Elf (Kaladesh)", "Medium", false, false,
                List.of(), List.of(), Set.of(), 4, Map.of("Rogue", 4), null);
        assertThat(FeatPrerequisites.meets(prerequisite("Elven Accuracy", "XGE"), drow)).isTrue();
        assertThat(FeatPrerequisites.meets(prerequisite("Elven Accuracy", "XGE"), kaladesh)).isTrue();
        assertThat(FeatPrerequisites.meets(prerequisite("Drow High Magic", "XGE"), drow)).isTrue();
        assertThat(FeatPrerequisites.unmet(prerequisite("Drow High Magic", "XGE"), woodElf))
                .containsExactly("Being an elf (drow) (you are a Wood Elf)");
        assertThat(FeatPrerequisites.meets(prerequisite("Wood Elf Magic", "XGE"), woodElf)).isTrue();
        assertThat(FeatPrerequisites.meets(prerequisite("Squat Nimbleness", "XGE"), halfling)).isTrue();
        assertThat(FeatPrerequisites.unmet(prerequisite("Squat Nimbleness", "XGE"), fighter(10)))
                .containsExactly("Being a dwarf or a Small race (you are a Human)");
    }

    @Test
    @DisplayName("A level, a class level and a feat are checked; a campaign and a requirement in words are shown but never refused")
    void levelsFeatsAndCampaigns() {
        List<PrerequisiteAlternative> ember = prerequisite("Ember of the Fire Giant", "BGG");
        Facts withStrike = facts(scores(10), "Human", null, "Medium", false, false, List.of(), List.of("martial"),
                Set.of("Strike of the Giants"), 4, Map.of("Fighter", 4), null);
        Facts lowLevel = facts(scores(10), "Human", null, "Medium", false, false, List.of(), List.of("martial"),
                Set.of("strike of the giants"), 3, Map.of("Fighter", 3), null);
        assertThat(FeatPrerequisites.meets(ember, withStrike)).isTrue();
        assertThat(FeatPrerequisites.unmet(ember, lowLevel)).containsExactly("4th level (you are level 3)");
        assertThat(FeatPrerequisites.unmet(ember, fighter(10)))
                .containsExactly("The Strike of the Giants (Fire Strike) feat");

        List<PrerequisiteAlternative> initiate = prerequisite("Initiate of High Sorcery", "DSotDQ");
        Facts sorcerer = facts(scores(10), "Human", null, "Medium", true, true, List.of(), List.of(), Set.of(), 1,
                Map.of("Sorcerer", 1), null);
        assertThat(FeatPrerequisites.meets(initiate, sorcerer)).isTrue();
        assertThat(FeatPrerequisites.meets(initiate, fighter(10))).isFalse();
        assertThat(FeatPrerequisites.describe(initiate)).isEqualTo("A Dragonlance campaign, 1st-level Sorcerer; "
                + "or a Dragonlance campaign, 1st-level Wizard; or a Dragonlance campaign, the Mage of High Sorcery "
                + "background");

        assertThat(FeatPrerequisites.meets(prerequisite("Scion of the Outer Planes", "SatO"), fighter(10))).isTrue();
        assertThat(FeatPrerequisites.meets(prerequisite("Aberrant Dragonmark", "ERLW"), fighter(10))).isTrue();
        assertThat(FeatPrerequisites.describe(prerequisite("Aberrant Dragonmark", "ERLW")))
                .isEqualTo("No other dragonmark");
    }

    @Test
    @DisplayName("A feat without a prerequisite is met by anyone and described as none")
    void none() {
        assertThat(FeatPrerequisites.unmet(prerequisite("Alert", "PHB"), fighter(8))).isEmpty();
        assertThat(FeatPrerequisites.describe(List.of())).isNull();
        assertThat(FeatPrerequisites.describe(prerequisite("Grappler", "PHB"))).isEqualTo("Strength 13");
        assertThat(FeatPrerequisites.describe(prerequisite("Ritual Caster", "PHB")))
                .isEqualTo("Intelligence or Wisdom 13");
        assertThat(FeatPrerequisites.describe(List.of(new PrerequisiteAlternative(null, null, false, false, null,
                List.of(new FeatRequirement("Squire of Solamnia", "DSOTDQ", null)), new LevelRequirement(4, null),
                null, null, null)))).isEqualTo("4th level, the Squire of Solamnia feat");
    }
}
