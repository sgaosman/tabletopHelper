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
                .hasMessageEndingWith("Key's cantrip choice 1 names either its spells, or its spellList or school, or a grant");
        assertThatThrownBy(() -> loader.cantrips("Key", json("[{\"count\": 1}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's cantrip choice 1 names either its spells, or its spellList or school, or a grant");
        assertThatThrownBy(() -> loader.cantrips("Key", json("[{\"count\": 1, \"spellList\": \"Druid\", "
                + "\"level\": 3}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has level, which isn't count");
    }

    @Test
    @DisplayName("A cantrip granted unless known needs one name and a count of 1, and names no spells or school")
    void badGrantedCantrip() {
        String message = "Key's cantrip choice 1's grant names one cantrip, with a count of 1 and, for a choice owed "
                + "when it is known, a spellList";
        assertThatThrownBy(() -> loader.cantrips("Key", json(
                "[{\"count\": 2, \"grant\": \"Minor Illusion\", \"spellList\": \"Wizard\"}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith(message);
        assertThatThrownBy(() -> loader.cantrips("Key", json(
                "[{\"count\": 1, \"grant\": \" \", \"spellList\": \"Wizard\"}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith(message);
        assertThatThrownBy(() -> loader.cantrips("Key", json(
                "[{\"count\": 1, \"grant\": \"Minor Illusion\", \"spells\": [\"Light\"]}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith(message);
        assertThatThrownBy(() -> loader.cantrips("Key", json(
                "[{\"count\": 1, \"grant\": \"Light\", \"school\": \"Evocation\"}]")))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith(message);
        assertThatThrownBy(() -> loader.cantrips("Key", json("[{\"count\": 1, \"school\": \" \"}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("Key's cantrip choice 1's spellList and school are names");
    }

    @Test
    @DisplayName("D231: a cantrip granted without a fallback, as the Light Domain's light (PHB p.60), and a choice by "
            + "school from any list, as the Death Domain's necromancy cantrip (DMG p.96), are taken")
    void grantsWithoutFallbackAndSchoolChoices() {
        assertThat(loader.cantrips("Key", json("[{\"count\": 1, \"grant\": \"Light\"}]")))
                .isEqualTo(json("[{\"count\": 1, \"grant\": \"Light\"}]"));
        assertThat(loader.cantrips("Key", json("[{\"count\": 1, \"school\": \"Necromancy\"}]")))
                .isEqualTo(json("[{\"count\": 1, \"school\": \"Necromancy\"}]"));
    }

    @Test
    @DisplayName("D231: the file grants the fixed subclass cantrips (Light, Grave, Celestial, Undying, Way of Shadow, "
            + "Spores, Stars, Swarmkeeper, Drakewarden, Spirits, Aberrant Mind), the Arcana Domain's two wizard cantrips "
            + "(SCAG p.125), the Death Domain's necromancy cantrip (DMG p.96) and the Order Domain's Intimidation or "
            + "Persuasion (TCE p.31)")
    void d231SubclassCantripsAndOrderDomain() {
        var rows = loader.subclassProficiencies(new LoadContext(objectMapper));
        java.util.Map<String, String> grants = new java.util.LinkedHashMap<>();
        grants.put("Cleric|PHB|Light Domain|PHB", "Light");
        grants.put("Cleric|PHB|Grave Domain|XGE", "Spare the Dying");
        grants.put("Warlock|PHB|The Undying|SCAG", "Spare the Dying");
        grants.put("Monk|PHB|Way of Shadow|PHB", "Minor Illusion");
        grants.put("Druid|PHB|Circle of Spores|TCE", "Chill Touch");
        grants.put("Druid|PHB|Circle of Stars|TCE", "Guidance");
        grants.put("Ranger|PHB|Swarmkeeper|TCE", "Mage Hand");
        grants.put("Ranger|PHB|Drakewarden|FTD", "Thaumaturgy");
        grants.put("Bard|PHB|College of Spirits|VRGR", "Guidance");
        grants.put("Sorcerer|PHB|Aberrant Mind|TCE", "Mind Sliver");
        grants.forEach((key, cantrip) -> assertThat(json(rows.get(key)).path("cantrips"))
                .as(key).isEqualTo(json("[{\"count\": 1, \"grant\": \"" + cantrip + "\"}]")));
        assertThat(json(rows.get("Warlock|PHB|The Celestial|XGE")).path("cantrips")).isEqualTo(json(
                "[{\"count\": 1, \"grant\": \"Sacred Flame\"}, {\"count\": 1, \"grant\": \"Light\"}]"));
        assertThat(json(rows.get("Cleric|PHB|Arcana Domain|SCAG")).path("cantrips")).isEqualTo(json(
                "[{\"count\": 2, \"spellList\": \"Wizard\"}]"));
        assertThat(json(rows.get("Cleric|PHB|Death Domain|DMG")).path("cantrips")).isEqualTo(json(
                "[{\"count\": 1, \"school\": \"Necromancy\"}]"));
        assertThat(json(rows.get("Cleric|PHB|Order Domain|TCE")).path("choices")).isEqualTo(json(
                "[{\"count\": 1, \"skills\": [\"Intimidation\", \"Persuasion\"]}]"));
        assertThat(json(rows.get("Cleric|PHB|Order Domain|TCE")).path("armor")).isEqualTo(json("[\"heavy\"]"));
    }

    // The Circle of the Land's spells as the 5etools druid file gives them, two terrains of eight, each with the
    // druid cantrip choice and its circle spells
    private static final String LAND = """
            {"name": "Circle of the Land", "additionalSpells": [
              {"name": "Arctic", "known": {"1": {"_": [{"choose": "level=0|class=Druid"}]}},
               "prepared": {"3": ["hold person", "spike growth"], "5": ["sleet storm", "slow"]}},
              {"name": "Underdark", "known": {"1": {"_": [{"choose": "level=0|class=Druid"}]}},
               "prepared": {"3": ["spider climb", "web"], "5": ["gaseous form", "stinking cloud"]}}]}""";

    @Test
    @DisplayName("D231: each Circle of the Land terrain keeps its own circle spells as an option, and none is every "
            + "member's always-prepared list, where the Underdark's used to overwrite the rest (PHB p.68-69)")
    void landTerrainsAreOptions() throws Exception {
        JsonNode land = json(LAND);

        assertThat(loader.parseAlwaysPreparedSpells(land)).isNull();
        assertThat(json(loader.parseGrantedSpells(land, "Druid"))).isEqualTo(json("""
                {"options": [
                  {"name": "Arctic", "prepared": {"3": ["hold person", "spike growth"], "5": ["sleet storm", "slow"]}},
                  {"name": "Underdark",
                   "prepared": {"3": ["spider climb", "web"], "5": ["gaseous form", "stinking cloud"]}}]}"""));
    }

    @Test
    @DisplayName("D231: a subclass's known spells by class level leave out its cantrips and choices and lose their "
            + "|source suffix: a Gloom Stalker's (XGE p.42), Aberrant Mind's (TCE p.66), and a Divine Soul's affinity "
            + "spells as options (XGE p.50); a prepared caster's, as the Circle of the Moon's alter self, are none")
    void knownSpellsAndOptions() throws Exception {
        assertThat(json(loader.parseGrantedSpells(json("""
                {"additionalSpells": [{"known": {"3": ["disguise self"], "5": ["rope trick"]}}]}"""), "Ranger")))
                .isEqualTo(json("{\"known\": {\"3\": [\"disguise self\"], \"5\": [\"rope trick\"]}}"));
        assertThat(json(loader.parseGrantedSpells(json("""
                {"additionalSpells": [{"known": {"1": ["mind sliver|tce#c", "arms of Hadar"],
                  "7": ["Evard's black tentacles", "summon aberration|TCE"]}}]}"""), "Sorcerer")))
                .isEqualTo(json("{\"known\": {\"1\": [\"arms of Hadar\"], "
                        + "\"7\": [\"Evard's black tentacles\", \"summon aberration\"]}}"));
        assertThat(json(loader.parseGrantedSpells(json("""
                {"additionalSpells": [
                  {"name": "Good", "known": {"1": ["cure wounds"]}, "expanded": {"1": [{"all": "level=1|class=Cleric"}]}},
                  {"name": "Evil", "known": {"1": ["inflict wounds"]}}]}"""), "Sorcerer")))
                .isEqualTo(json("{\"options\": [{\"name\": \"Good\", \"known\": {\"1\": [\"cure wounds\"]}}, "
                        + "{\"name\": \"Evil\", \"known\": {\"1\": [\"inflict wounds\"]}}]}"));
        assertThat(loader.parseGrantedSpells(json("{\"additionalSpells\": [{\"known\": {\"14\": [\"alter self\"]}}]}"),
                "Druid")).isNull();
        // A single named list is every member's, as Lunar Sorcery's
        assertThat(json(loader.parseGrantedSpells(json(
                "{\"additionalSpells\": [{\"name\": \"Full Moon\", \"known\": {\"1\": [\"shield\"]}}]}"), "Sorcerer")))
                .isEqualTo(json("{\"known\": {\"1\": [\"shield\"]}}"));
    }

    @Test
    @DisplayName("D239: each of the Genie's kinds is an option with its expanded spells, the genie spells and its own "
            + "(TCE p.73), and the Fathomless teaches Evard's black tentacles at Warlock 10 once a long rest (TCE p.73)")
    void genieKindsAndGraspingTentacles() throws Exception {
        assertThat(json(loader.parseGrantedSpells(json("""
                {"additionalSpells": [
                  {"name": "Dao", "expanded": {"9": ["wish"], "s1": ["detect evil and good", "sanctuary"],
                   "s4": ["summon elemental|tce"]}},
                  {"name": "Djinni", "expanded": {"9": ["wish"], "s1": ["detect evil and good", "thunderwave"]}}]}"""),
                "Warlock"))).isEqualTo(json("""
                {"options": [
                  {"name": "Dao", "expanded": ["wish", "detect evil and good", "sanctuary", "summon elemental"]},
                  {"name": "Djinni", "expanded": ["wish", "detect evil and good", "thunderwave"]}]}"""));
        assertThat(json(loader.parseGrantedSpells(json("""
                {"additionalSpells": [{"expanded": {"s1": ["create or destroy water", "thunderwave"]},
                  "known": {"10": {"daily": {"1": ["Evard's black tentacles"]}}}}]}"""), "Warlock")))
                .isEqualTo(json("{\"daily\": {\"10\": [\"Evard's black tentacles\"]}}"));
    }

    @Test
    @DisplayName("D231: a spell's |source suffix and #c mark are dropped, so the Fathomless's \"summon elemental|tce\" "
            + "(TCE p.72) and PSA Zeal's \"fire shield|\" match their spells")
    void spellNamesLoseTheirSourceSuffix() {
        assertThat(CharacterClassLoader.spellName("summon elemental|tce")).isEqualTo("summon elemental");
        assertThat(CharacterClassLoader.spellName("fire shield|")).isEqualTo("fire shield");
        assertThat(CharacterClassLoader.spellName("mind sliver|tce#c")).isEqualTo("mind sliver");
        assertThat(CharacterClassLoader.spellName("Nystul's magic aura")).isEqualTo("Nystul's magic aura");
    }
}
