package com.tabletophelper.content.internal;

import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

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
}
