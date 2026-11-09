package com.tabletophelper.content.internal;

import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The text the class loader gives a feature (D169): features nested inside another by the 5etools references
 * {@code refSubclassFeature} and {@code refClassFeature} add their own paragraphs, each led by its name, as the PHB prints
 * the Life Domain's Bonus Proficiency and Disciple of Life (p.60).
 */
class CharacterClassLoaderTest {

    private final ObjectMapper objectMapper = JacksonDefaults.mapper();
    private final CharacterClassLoader loader = new CharacterClassLoader(null, null, objectMapper, null);

    private JsonNode json(String text) {
        return objectMapper.readTree(text);
    }

    // The Life Domain as the 5etools cleric file gives it: a parent feature whose entries end with two references,
    // the second with its source left blank for the Player's Handbook
    private static final String SUBCLASS_FEATURES = """
            [
              {"name": "Life Domain", "source": "PHB", "className": "Cleric", "classSource": "PHB",
               "subclassShortName": "Life", "subclassSource": "PHB", "level": 1,
               "entries": ["The Life domain focuses on the vibrant positive energy.",
                           {"type": "refSubclassFeature", "subclassFeature": "Bonus Proficiency|Cleric||Life||1"},
                           {"type": "refSubclassFeature", "subclassFeature": "Disciple of Life|Cleric|PHB|Life|PHB|1"}]},
              {"name": "Bonus Proficiency", "source": "PHB", "className": "Cleric", "classSource": "PHB",
               "subclassShortName": "Life", "subclassSource": "PHB", "level": 1, "header": 1,
               "entries": ["When you choose this domain at 1st level, you gain proficiency with heavy armor."]},
              {"name": "Disciple of Life", "source": "PHB", "className": "Cleric", "classSource": "PHB",
               "subclassShortName": "Life", "subclassSource": "PHB", "level": 1, "header": 1,
               "entries": ["Also starting at 1st level, your healing spells are more effective.",
                           "Whenever you use a spell of 1st level or higher, the creature regains {@dice 2} more."]},
              {"name": "Hex Warrior", "source": "XGE", "className": "Warlock", "classSource": "PHB",
               "subclassShortName": "Hexblade", "subclassSource": "XGE", "level": 1,
               "entries": ["You acquire the training necessary to effectively arm yourself for battle."]}
            ]""";

    @Test
    @DisplayName("A subclass feature's nested features follow its own text, each led by its name, paragraph by "
            + "paragraph; a blank source reads as the Player's Handbook")
    void nestedSubclassFeatures() {
        var nested = CharacterClassLoader.NestedFeatures.of(null, json(SUBCLASS_FEATURES));

        String text = loader.flattenEntries(json(SUBCLASS_FEATURES).get(0).get("entries"), nested, 0);

        assertThat(text).isEqualTo("""
                The Life domain focuses on the vibrant positive energy.
                Bonus Proficiency. When you choose this domain at 1st level, you gain proficiency with heavy armor.
                Disciple of Life. Also starting at 1st level, your healing spells are more effective.
                Whenever you use a spell of 1st level or higher, the creature regains 2 more.""");
    }

    @Test
    @DisplayName("A reference to a subclass of another book finds its feature through the subclass's source")
    void otherBookSubclass() {
        var nested = CharacterClassLoader.NestedFeatures.of(null, json(SUBCLASS_FEATURES));
        JsonNode entries = json("""
                [{"type": "refSubclassFeature", "subclassFeature": "Hex Warrior|Warlock||Hexblade|XGE|1"}]""");

        assertThat(loader.flattenEntries(entries, nested, 0))
                .isEqualTo("Hex Warrior. You acquire the training necessary to effectively arm yourself for battle.");
    }

    @Test
    @DisplayName("A class feature's nested class features are added the same way, as the Monk's Ki holds Flurry of "
            + "Blows (PHB p.78); a reference the file can't find and an optional feature's are left out")
    void nestedClassFeatures() {
        JsonNode classFeatures = json("""
                [
                  {"name": "Ki", "source": "PHB", "className": "Monk", "classSource": "PHB", "level": 2,
                   "entries": ["Your training allows you to harness the mystic energy of ki.",
                               {"type": "refClassFeature", "classFeature": "Flurry of Blows|Monk||2"},
                               {"type": "refClassFeature", "classFeature": "Missing Feature|Monk||2"},
                               {"type": "refOptionalfeature", "optionalfeature": "Archery"}]},
                  {"name": "Flurry of Blows", "source": "PHB", "className": "Monk", "classSource": "PHB", "level": 2,
                   "header": 1, "entries": ["Immediately after you take the Attack action on your turn."]}
                ]""");
        var nested = CharacterClassLoader.NestedFeatures.of(classFeatures, null);

        assertThat(loader.flattenEntries(classFeatures.get(0).get("entries"), nested, 0)).isEqualTo("""
                Your training allows you to harness the mystic energy of ki.
                Flurry of Blows. Immediately after you take the Attack action on your turn.""");
    }

    @Test
    @DisplayName("A feature that refers back to itself stops after a few levels instead of looping")
    void selfReference() {
        JsonNode features = json("""
                [{"name": "Loop", "source": "PHB", "className": "Cleric", "classSource": "PHB",
                  "subclassShortName": "Life", "subclassSource": "PHB", "level": 1,
                  "entries": ["Text.", {"type": "refSubclassFeature", "subclassFeature": "Loop|Cleric||Life||1"}]}]""");
        var nested = CharacterClassLoader.NestedFeatures.of(null, features);

        String text = loader.flattenEntries(features.get(0).get("entries"), nested, 0);

        assertThat(text).startsWith("Text.\nLoop. Text.").endsWith("Loop. Text.");
    }

    @Test
    @DisplayName("The bonus proficiencies file keeps a subclass's fixed grants and writes out its choices' lists: a "
            + "Knowledge Domain's two languages and two skills with Expertise (PHB p.59), a Bladesinger's one-handed "
            + "melee weapon beside its light armour (TCE p.76)")
    void subclassProficiencyChoices() {
        var rows = loader.subclassProficiencies(new LoadContext(objectMapper));

        JsonNode knowledge = json(rows.get("Cleric|PHB|Knowledge Domain|PHB"));
        assertThat(knowledge.path("choices")).hasSize(2);
        assertThat(knowledge.path("choices").get(0).path("count").asInt()).isEqualTo(2);
        assertThat(knowledge.path("choices").get(0).path("languages")).hasSize(16);
        assertThat(knowledge.path("choices").get(1)).isEqualTo(json(
                "{\"count\": 2, \"skills\": [\"Arcana\", \"History\", \"Nature\", \"Religion\"], \"expertise\": true}"));
        JsonNode bladesinging = json(rows.get("Wizard|PHB|Bladesinging|TCE"));
        assertThat(bladesinging.path("armor")).isEqualTo(json("[\"light\"]"));
        assertThat(bladesinging.path("choices").get(0).path("weapons")).hasSize(20);
        assertThat(json(rows.get("Bard|PHB|College of Lore|PHB")).path("choices").get(0).path("skills")).hasSize(18);
        assertThat(json(rows.get("Fighter|PHB|Battle Master|PHB")).path("choices").get(0).path("tools")).hasSize(17);
    }

    @Test
    @DisplayName("A choice without a count, offering nothing, naming an unknown list or giving Expertise to languages "
            + "is refused")
    void badSubclassProficiencyChoices() {
        assertThatThrownBy(() -> loader.choices("Key", json("[{\"skills\": [\"Arcana\"]}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith("Key's choice 1 has no count of 1 or more");
        assertThatThrownBy(() -> loader.choices("Key", json("[{\"count\": 1}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith("Key's choice 1 offers no options");
        assertThatThrownBy(() -> loader.choices("Key", json("[{\"count\": 1, \"tools\": [\"*instruments\"]}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("*instruments isn't one of the lists");
        assertThatThrownBy(() -> loader.choices("Key", json(
                "[{\"count\": 1, \"languages\": [\"Elvish\"], \"expertise\": true}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("expertise is true or false, and only for a choice of skills");
        assertThatThrownBy(() -> loader.choices("Key", json("[{\"count\": 1, \"feats\": [\"Alert\"]}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has feats, which isn't one of");
        assertThatThrownBy(() -> loader.choices("Key", json(
                "[{\"count\": 1, \"skills\": [\"Arcana\"], \"kensei\": true}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("kensei is true or false, and only for a choice of weapons");
        assertThatThrownBy(() -> loader.choices("Key", json("[{\"count\": 1, \"skills\": [\"Arcana\"], \"level\": 21}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's choice 1's level is a class level from 1 to 20");
    }

    @Test
    @DisplayName("The file's conditional grants keep their one proficiency and write out the choice otherwise: an "
            + "Alchemist's alchemist's supplies or another artisan's tool (TCE p.14), a Path of the Giant's Giant or "
            + "another language (BGG p.11); kensei weapons are owed at Monk 3, 6, 11 and 17 (XGE p.34); and the "
            + "Draconic Bloodline, Storm Sorcery and Circle of the Shepherd learn their languages")
    void conditionalGrantsAndLaterChoices() {
        var rows = loader.subclassProficiencies(new LoadContext(objectMapper));

        JsonNode alchemist = json(rows.get("Artificer|TCE|Alchemist|TCE"));
        assertThat(alchemist.path("tools").isMissingNode()).isTrue();
        assertThat(alchemist.path("conditional").get(0).path("tools")).isEqualTo(json("[\"alchemist's supplies\"]"));
        assertThat(alchemist.path("conditional").get(0).path("otherwise").path("tools")).hasSize(17);
        JsonNode armorer = json(rows.get("Artificer|TCE|Armorer|TCE"));
        assertThat(armorer.path("armor")).isEqualTo(json("[\"heavy\"]"));
        assertThat(armorer.path("conditional").get(0).path("tools")).isEqualTo(json("[\"smith's tools\"]"));
        assertThat(json(rows.get("Artificer|TCE|Artillerist|TCE")).path("conditional").get(0).path("tools"))
                .isEqualTo(json("[\"woodcarver's tools\"]"));
        assertThat(json(rows.get("Artificer|TCE|Battle Smith|TCE")).path("conditional").get(0).path("tools"))
                .isEqualTo(json("[\"smith's tools\"]"));
        JsonNode giant = json(rows.get("Barbarian|PHB|Path of the Giant|BGG"));
        assertThat(giant.path("conditional").get(0).path("languages")).isEqualTo(json("[\"Giant\"]"));
        assertThat(giant.path("conditional").get(0).path("otherwise").path("languages")).hasSize(16);

        JsonNode kensei = json(rows.get("Monk|PHB|Way of the Kensei|XGE")).path("choices");
        assertThat(kensei).hasSize(6);
        assertThat(kensei.get(0).path("kensei").isMissingNode()).isTrue();
        assertThat(kensei.get(1).path("weapons")).hasSize(21);
        assertThat(kensei.get(1).path("kensei").asBoolean()).isTrue();
        assertThat(kensei.get(2).path("weapons")).hasSize(7);
        assertThat(kensei.get(2).path("level").isMissingNode()).isTrue();
        assertThat(kensei.get(3).path("level").asInt()).isEqualTo(6);
        assertThat(kensei.get(3).path("weapons")).hasSize(28);
        assertThat(kensei.get(4).path("level").asInt()).isEqualTo(11);
        assertThat(kensei.get(5).path("level").asInt()).isEqualTo(17);

        assertThat(json(rows.get("Sorcerer|PHB|Draconic Bloodline|PHB")).path("languages"))
                .isEqualTo(json("[\"Draconic\"]"));
        assertThat(json(rows.get("Sorcerer|PHB|Storm Sorcery|XGE")).path("languages"))
                .isEqualTo(json("[\"Primordial\"]"));
        assertThat(json(rows.get("Druid|PHB|Circle of the Shepherd|XGE")).path("languages"))
                .isEqualTo(json("[\"Sylvan\"]"));
    }

    @Test
    @DisplayName("A conditional grant of more than one proficiency, of a list, or without a choice otherwise, or whose "
            + "choice otherwise has a level, is refused")
    void badConditionalGrants() {
        assertThatThrownBy(() -> loader.conditionals("Key", json(
                "[{\"tools\": [\"smith's tools\", \"tinker's tools\"], \"otherwise\": {\"count\": 1, "
                        + "\"tools\": [\"*artisan's tools\"]}}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's conditional grant 1 grants one proficiency by name");
        assertThatThrownBy(() -> loader.conditionals("Key", json(
                "[{\"tools\": [\"*artisan's tools\"], \"otherwise\": {\"count\": 1, \"tools\": [\"Smith's Tools\"]}}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith("grants one proficiency by name");
        assertThatThrownBy(() -> loader.conditionals("Key", json(
                "[{\"tools\": [\"smith's tools\"], \"languages\": [\"Giant\"], \"otherwise\": {\"count\": 1, "
                        + "\"tools\": [\"*artisan's tools\"]}}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith("grants one proficiency by name");
        assertThatThrownBy(() -> loader.conditionals("Key", json("[{\"tools\": [\"smith's tools\"]}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's conditional grant 1 has no choice otherwise");
        assertThatThrownBy(() -> loader.conditionals("Key", json(
                "[{\"tools\": [\"smith's tools\"], \"otherwise\": {\"count\": 1, \"level\": 6, "
                        + "\"tools\": [\"*artisan's tools\"]}}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has level, which isn't one of");
    }

    @Test
    @DisplayName("The file's later grants, saving throws, Expertise and cantrips: a Samurai's and a Gloom Stalker's "
            + "Wisdom saves or Intelligence or Charisma at 7th level (XGE p.31, p.41), a Purple Dragon Knight's "
            + "Persuasion with Expertise at 7th (SCAG p.128), a Scout's Expertise (XGE p.47), and the cantrips of the "
            + "Arcane Archer (XGE p.28), Path of the Giant (BGG p.11), Nature Domain (PHB p.61) and Circle of the "
            + "Land (PHB p.68)")
    void laterGrantsExpertiseAndCantrips() {
        var rows = loader.subclassProficiencies(new LoadContext(objectMapper));

        for (String key : new String[]{"Fighter|PHB|Samurai|XGE", "Ranger|PHB|Gloom Stalker|XGE"}) {
            JsonNode conditional = json(rows.get(key)).path("conditional").get(0);
            assertThat(conditional).isEqualTo(json(
                    "{\"saves\": [\"WIS\"], \"level\": 7, \"otherwise\": {\"count\": 1, \"saves\": [\"INT\", \"CHA\"]}}"));
        }
        JsonNode envoy = json(rows.get("Fighter|PHB|Purple Dragon Knight (Banneret)|SCAG")).path("conditional").get(0);
        assertThat(envoy.path("skills")).isEqualTo(json("[\"Persuasion\"]"));
        assertThat(envoy.path("expertise").asBoolean()).isTrue();
        assertThat(envoy.path("level").asInt()).isEqualTo(7);
        assertThat(envoy.path("otherwise").path("skills")).hasSize(4);
        JsonNode scout = json(rows.get("Rogue|PHB|Scout|XGE"));
        assertThat(scout.path("expertise")).isEqualTo(json("[\"Nature\", \"Survival\"]"));

        assertThat(json(rows.get("Fighter|PHB|Arcane Archer|XGE")).path("cantrips")).isEqualTo(json(
                "[{\"count\": 1, \"spells\": [\"Prestidigitation\", \"Druidcraft\"]}]"));
        assertThat(json(rows.get("Barbarian|PHB|Path of the Giant|BGG")).path("cantrips")).isEqualTo(json(
                "[{\"count\": 1, \"spells\": [\"Druidcraft\", \"Thaumaturgy\"]}]"));
        assertThat(json(rows.get("Cleric|PHB|Nature Domain|PHB")).path("cantrips")).isEqualTo(json(
                "[{\"count\": 1, \"spellList\": \"Druid\"}]"));
        assertThat(json(rows.get("Druid|PHB|Circle of the Land|PHB")).path("cantrips")).isEqualTo(json(
                "[{\"count\": 1, \"spellList\": \"Druid\"}]"));        // Improved Minor Illusion: Minor Illusion, or another wizard cantrip when it is known (PHB p.118)
        assertThat(json(rows.get("Wizard|PHB|School of Illusion|PHB")).path("cantrips")).isEqualTo(json(
                "[{\"count\": 1, \"grant\": \"Minor Illusion\", \"spellList\": \"Wizard\"}]"));
    }

    @Test
    @DisplayName("A saving throw not abbreviated as the class data has it, Expertise in a skill the row doesn't grant "
            + "or on a conditional language, a conditional level past 20, and a cantrip choice without a count or "
            + "with both spells and a spell list are refused")
    void badLaterGrantsExpertiseAndCantrips() {
        assertThatThrownBy(() -> loader.conditionals("Key", json(
                "[{\"saves\": [\"Wisdom\"], \"otherwise\": {\"count\": 1, \"saves\": [\"INT\"]}}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("names Wisdom, which isn't one of");
        assertThatThrownBy(() -> loader.choices("Key", json("[{\"count\": 1, \"saves\": [\"int\"]}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("names int, which isn't one of");
        assertThatThrownBy(() -> loader.expertise("Key", json("[\"Stealth\"]"), json("[\"Nature\"]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's expertise names Stealth, which isn't among the row's skills");
        assertThatThrownBy(() -> loader.conditionals("Key", json(
                "[{\"languages\": [\"Giant\"], \"expertise\": true, \"otherwise\": {\"count\": 1, "
                        + "\"languages\": [\"*languages\"]}}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("expertise is true or false, and only for a skill");
        assertThatThrownBy(() -> loader.conditionals("Key", json(
                "[{\"saves\": [\"WIS\"], \"level\": 21, \"otherwise\": {\"count\": 1, \"saves\": [\"INT\"]}}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's conditional grant 1's level is a class level from 1 to 20");
        assertThatThrownBy(() -> loader.cantrips("Key", json("[{\"spells\": [\"Druidcraft\"]}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's cantrip choice 1 has no count of 1 or more");
        assertThatThrownBy(() -> loader.cantrips("Key", json(
                "[{\"count\": 1, \"spells\": [\"Druidcraft\"], \"spellList\": \"Druid\"}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's cantrip choice 1 names either its spells or its spellList");
        assertThatThrownBy(() -> loader.cantrips("Key", json("[{\"count\": 1}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's cantrip choice 1 names either its spells or its spellList");
        assertThatThrownBy(() -> loader.cantrips("Key", json("[{\"count\": 1, \"spellList\": \"Druid\", "
                + "\"level\": 3}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has level, which isn't count");
    }

    @Test
    @DisplayName("A cantrip granted unless known needs one name, a count of 1 and a spell list for the choice owed "
            + "otherwise")
    void badGrantedCantrip() {
        String message = "Key's cantrip choice 1's grant names one cantrip, with a count of 1 and a spellList for the "
                + "choice owed when it is known";
        assertThatThrownBy(() -> loader.cantrips("Key", json(
                "[{\"count\": 2, \"grant\": \"Minor Illusion\", \"spellList\": \"Wizard\"}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith(message);
        assertThatThrownBy(() -> loader.cantrips("Key", json(
                "[{\"count\": 1, \"grant\": \" \", \"spellList\": \"Wizard\"}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith(message);
        assertThatThrownBy(() -> loader.cantrips("Key", json(
                "[{\"count\": 1, \"grant\": \"Minor Illusion\", \"spells\": [\"Light\"]}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith(message);
    }
}
