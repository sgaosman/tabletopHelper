package com.tabletophelper.content.internal;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.rules.character.ClassTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CharacterClassLoaderIT extends IntegrationTest {

    @Autowired private CharacterClassLoader characterClassLoader;
    @Autowired private SubclassRepository subclassRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ClassTables classTables;

    // Runs the loader in a transaction, as ContentLoading does
    private void load() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                characterClassLoader.load(new LoadContext(objectMapper));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("After loading, the 13 classes carry the spellcaster and pact magic flags the every-class slot table assumes")
    void seededClassesCarryCasterFlags() {
        load();

        assertThat(characterClassRepository.findAll())
                .extracting(CharacterClass::getName, CharacterClass::getSource,
                        CharacterClass::getIsSpellcaster, CharacterClass::getIsPactMagic)
                .containsExactlyInAnyOrder(
                        tuple("Artificer", "TCE", true, false),
                        tuple("Barbarian", "PHB", false, false),
                        tuple("Bard", "PHB", true, false),
                        tuple("Cleric", "PHB", true, false),
                        tuple("Druid", "PHB", true, false),
                        tuple("Fighter", "PHB", false, false),
                        tuple("Monk", "PHB", false, false),
                        tuple("Paladin", "PHB", true, false),
                        tuple("Ranger", "PHB", true, false),
                        tuple("Rogue", "PHB", false, false),
                        tuple("Sorcerer", "PHB", true, false),
                        tuple("Warlock", "PHB", true, true),
                        tuple("Wizard", "PHB", true, false));
    }

    @Test
    @DisplayName("After loading, each class's primary ability reads as the PHB's class table gives it: a Fighter's STR or DEX, a Paladin's STR and CHA")
    void primaryAbilitiesReadAsThePhbGivesThem() {
        load();

        assertThat(characterClassRepository.findAll())
                .extracting(CharacterClass::getName, CharacterClass::getPrimaryAbility)
                .containsExactlyInAnyOrder(
                        tuple("Artificer", "INT"),
                        tuple("Barbarian", "STR"),
                        tuple("Bard", "CHA"),
                        tuple("Cleric", "WIS"),
                        tuple("Druid", "WIS"),
                        tuple("Fighter", "STR or DEX"),
                        tuple("Monk", "DEX and WIS"),
                        tuple("Paladin", "STR and CHA"),
                        tuple("Ranger", "DEX and WIS"),
                        tuple("Rogue", "DEX"),
                        tuple("Sorcerer", "CHA"),
                        tuple("Warlock", "CHA"),
                        tuple("Wizard", "INT"));
    }

    @Test
    @DisplayName("Loading classes already loaded corrects a primary ability older code read wrongly, such as the Fighter's OR")
    void loadingAgainCorrectsThePrimaryAbility() {
        load();
        CharacterClass fighter = characterClassRepository.findByNameAndSource("Fighter", "PHB").orElseThrow();
        fighter.setPrimaryAbility("OR");
        characterClassRepository.save(fighter);

        load();

        assertThat(characterClassRepository.findByNameAndSource("Fighter", "PHB").orElseThrow().getPrimaryAbility())
                .isEqualTo("STR or DEX");
        assertThat(characterClassRepository.findByNameAndSource("Paladin", "PHB").orElseThrow().getPrimaryAbility())
                .isEqualTo("STR and CHA");
    }

    @Test
    @DisplayName("After loading, the third-caster subclasses sit under the non-caster Fighter and Rogue")
    void thirdCasterSubclassesBelongToNonCasters() {
        load();

        assertThat(subclassRepository.findAll())
                .filteredOn(subclass -> subclass.getName().equals("Eldritch Knight")
                        || subclass.getName().equals("Arcane Trickster"))
                .extracting(Subclass::getName, subclass -> subclass.getCharacterClass().getName(),
                        subclass -> subclass.getCharacterClass().getIsSpellcaster())
                .containsExactlyInAnyOrder(
                        tuple("Eldritch Knight", "Fighter", false),
                        tuple("Arcane Trickster", "Rogue", false));
    }

    @Test
    @DisplayName("After loading, only the Draconic Bloodline has effects, its hpPerClassLevel and Draconic Resilience's 13 + DEX from subclass-effects.json")
    void subclassEffectsComeFromTheOverlay() {
        load();

        assertThat(subclassRepository.findAll()).filteredOn(subclass -> subclass.getEffects() != null)
                .extracting(Subclass::getName, subclass -> subclass.getCharacterClass().getName(),
                        subclass -> objectMapper.readTree(subclass.getEffects()))
                .containsExactly(tuple("Draconic Bloodline", "Sorcerer", objectMapper.readTree(
                        "{\"hpPerClassLevel\": 1, \"unarmouredAcBase\": 13, \"unarmouredAcDex\": 1}")));
    }

    @Test
    @DisplayName("After loading, each class's subclass level and slot progression are the class tables'")
    void subclassLevelsAndSlotsComeFromTheClassTables() throws Exception {
        load();

        assertThat(characterClassRepository.findAll())
                .extracting(CharacterClass::getName, CharacterClass::getSubclassLevel)
                .containsExactlyInAnyOrder(
                        tuple("Artificer", 3), tuple("Barbarian", 3), tuple("Bard", 3), tuple("Cleric", 1),
                        tuple("Druid", 2), tuple("Fighter", 3), tuple("Monk", 3), tuple("Paladin", 3),
                        tuple("Ranger", 3), tuple("Rogue", 3), tuple("Sorcerer", 1), tuple("Warlock", 1),
                        tuple("Wizard", 2));
        CharacterClass paladin = characterClassRepository.findByNameAndSource("Paladin", "PHB").orElseThrow();
        CharacterClass warlock = characterClassRepository.findByNameAndSource("Warlock", "PHB").orElseThrow();
        assertThat(objectMapper.readTree(paladin.getSpellSlotProgression()))
                .isEqualTo(objectMapper.valueToTree(classTables.slotProgression("half")));
        assertThat(objectMapper.readTree(warlock.getSpellSlotProgression()).path("20").toString())
                .isEqualTo("{\"5\":4}");
        assertThat(characterClassRepository.findByNameAndSource("Fighter", "PHB").orElseThrow()
                .getSpellSlotProgression()).isNull();
    }

    @Test
    @DisplayName("After loading, subclasses carry the bonus proficiencies of subclass-proficiencies.json: a Life "
            + "Domain's heavy armour, a War Domain's heavy armour and martial weapons, a Nature Domain's heavy armour "
            + "and its choice of skill, a Knowledge Domain's choices, and none for a Light Domain")
    void subclassProficienciesComeFromTheOverlay() {
        load();

        assertThat(subclassRepository.findAll()).filteredOn(subclass -> subclass.getAdditionalProficiencies() != null)
                .hasSize(41);
        assertThat(proficienciesOf("Life Domain")).isEqualTo(objectMapper.readTree("{\"armor\": [\"heavy\"]}"));
        assertThat(proficienciesOf("War Domain")).isEqualTo(objectMapper.readTree(
                "{\"armor\": [\"heavy\"], \"weapons\": [\"martial\"]}"));
        assertThat(proficienciesOf("Tempest Domain")).isEqualTo(objectMapper.readTree(
                "{\"armor\": [\"heavy\"], \"weapons\": [\"martial\"]}"));
        assertThat(proficienciesOf("Nature Domain")).isEqualTo(objectMapper.readTree("{\"armor\": [\"heavy\"], "
                + "\"choices\": [{\"count\": 1, \"skills\": [\"Animal Handling\", \"Nature\", \"Survival\"]}], "
                + "\"cantrips\": [{\"count\": 1, \"spellList\": \"Druid\"}]}"));
        assertThat(proficienciesOf("Knowledge Domain").path("choices").get(0).path("languages")).hasSize(16);
        assertThat(proficienciesOf("College of Valor")).isEqualTo(objectMapper.readTree(
                "{\"armor\": [\"medium\", \"shields\"], \"weapons\": [\"martial\"]}"));
        assertThat(cleric("Light Domain").getAdditionalProficiencies()).isNull();
    }

    @Test
    @DisplayName("After loading, the Draconic Bloodline, Storm Sorcery and Circle of the Shepherd learn Draconic, "
            + "Primordial and Sylvan (PHB p.102, XGE p.51, p.31); the Artificer subclasses' tools and the Path of the "
            + "Giant's Giant are conditional grants with a choice otherwise (TCE p.14-18, BGG p.11); and a kensei owes "
            + "kensei weapons on Monk 3, 6, 11 and 17 (XGE p.34)")
    void conditionalGrantsLanguagesAndKenseiWeapons() {
        load();

        assertThat(proficienciesOf("Draconic Bloodline")).isEqualTo(objectMapper.readTree(
                "{\"languages\": [\"Draconic\"]}"));
        assertThat(proficienciesOf("Storm Sorcery")).isEqualTo(objectMapper.readTree(
                "{\"languages\": [\"Primordial\"]}"));
        assertThat(proficienciesOf("Circle of the Shepherd")).isEqualTo(objectMapper.readTree(
                "{\"languages\": [\"Sylvan\"]}"));
        var alchemist = proficienciesOf("Alchemist");
        assertThat(alchemist.path("tools").isMissingNode()).isTrue();
        assertThat(alchemist.path("conditional").get(0).path("tools").toString()).isEqualTo("[\"alchemist's supplies\"]");
        assertThat(alchemist.path("conditional").get(0).path("otherwise").path("tools")).hasSize(17);
        assertThat(proficienciesOf("Armorer").path("armor").toString()).isEqualTo("[\"heavy\"]");
        assertThat(proficienciesOf("Battle Smith").path("weapons").toString()).isEqualTo("[\"martial\"]");
        assertThat(proficienciesOf("Artillerist").path("conditional").get(0).path("tools").toString())
                .isEqualTo("[\"woodcarver's tools\"]");
        assertThat(proficienciesOf("Path of the Giant").path("conditional").get(0).path("otherwise")
                .path("languages")).hasSize(16);
        var kensei = proficienciesOf("Way of the Kensei").path("choices");
        assertThat(kensei).hasSize(6);
        assertThat(kensei.get(1).path("weapons")).hasSize(21);
        assertThat(kensei.get(2).path("weapons")).hasSize(7);
        assertThat(kensei.get(5).path("level").asInt()).isEqualTo(17);
        assertThat(kensei.get(5).path("weapons")).hasSize(28);
    }

    @Test
    @DisplayName("After loading, the Samurai and Gloom Stalker gain Wisdom saves, or Intelligence or Charisma, at 7th "
            + "level (XGE p.31, p.41), the Purple Dragon Knight Persuasion with Expertise at 7th (SCAG p.128), the Scout "
            + "Expertise in Nature and Survival (XGE p.47), and the Arcane Archer, Path of the Giant and Circle of the "
            + "Land a cantrip of the player's choice (XGE p.28, BGG p.11, PHB p.68)")
    void laterGrantsExpertiseAndCantrips() {
        load();

        assertThat(proficienciesOf("Samurai").path("conditional").get(0)).isEqualTo(objectMapper.readTree(
                "{\"saves\": [\"WIS\"], \"level\": 7, \"otherwise\": {\"count\": 1, \"saves\": [\"INT\", \"CHA\"]}}"));
        assertThat(proficienciesOf("Gloom Stalker").path("conditional").get(0).path("level").asInt()).isEqualTo(7);
        var envoy = proficienciesOf("Purple Dragon Knight (Banneret)").path("conditional").get(0);
        assertThat(envoy.path("skills").toString()).isEqualTo("[\"Persuasion\"]");
        assertThat(envoy.path("expertise").asBoolean()).isTrue();
        assertThat(proficienciesOf("Scout").path("expertise").toString()).isEqualTo("[\"Nature\",\"Survival\"]");
        assertThat(proficienciesOf("Arcane Archer").path("cantrips").get(0).path("spells")).hasSize(2);
        assertThat(proficienciesOf("Path of the Giant").path("cantrips").get(0).path("spells")).hasSize(2);
        assertThat(proficienciesOf("Circle of the Land").path("cantrips").get(0).path("spellList").asString())
                .isEqualTo("Druid");
    }

    @Test
    @DisplayName("After loading, a Life Domain's only 1st-level feature is the one named after the subclass, so no "
            + "character has a feature named Disciple of Life, and its text holds Bonus Proficiency's and Disciple of "
            + "Life's, each led by its name (PHB p.60)")
    void lifeDomainHoldsDiscipleOfLifeInsideItsFirstFeature() {
        load();

        var features = objectMapper.readTree(cleric("Life Domain").getFeatures());
        var firstLevel = new java.util.ArrayList<String>();
        var description = new StringBuilder();
        features.forEach(feature -> {
            if (feature.path("level").asInt() == 1) {
                firstLevel.add(feature.path("name").asString());
                description.append(feature.path("description").asString());
            }
        });
        assertThat(firstLevel).containsExactly("Life Domain");
        assertThat(description.toString())
                .startsWith("The Life domain focuses on the vibrant positive energy")
                .contains("\nBonus Proficiency. When you choose this domain at 1st level, you gain proficiency with "
                        + "heavy armor.")
                .endsWith("\nDisciple of Life. Also starting at 1st level, your healing spells are more effective. "
                        + "Whenever you use a spell of 1st level or higher to restore hit points to a creature, the "
                        + "creature regains additional hit points equal to 2 + the spell's level.");
    }

    @Test
    @DisplayName("After loading, class features nested inside another reach its text too: the Monk's Ki holds Flurry "
            + "of Blows, Patient Defense and Step of the Wind (PHB p.78), and its feature list doesn't gain them")
    void kiHoldsItsNestedFeatures() {
        load();

        CharacterClass monk = characterClassRepository.findAll().stream()
                .filter(found -> found.getName().equals("Monk"))
                .findFirst()
                .orElseThrow();
        var names = new java.util.ArrayList<String>();
        var ki = new StringBuilder();
        objectMapper.readTree(monk.getFeatures()).forEach(feature -> {
            names.add(feature.path("name").asString());
            if (feature.path("name").asString().equals("Ki")) ki.append(feature.path("description").asString());
        });
        assertThat(names).doesNotContain("Flurry of Blows", "Patient Defense", "Step of the Wind");
        assertThat(ki.toString()).contains("\nFlurry of Blows. ", "\nPatient Defense. ", "\nStep of the Wind. ");
    }

    private Subclass cleric(String name) {
        return subclassRepository.findAll().stream()
                .filter(subclass -> subclass.getName().equals(name)
                        && subclass.getCharacterClass().getName().equals("Cleric"))
                .findFirst()
                .orElseThrow();
    }

    private tools.jackson.databind.JsonNode proficienciesOf(String name) {
        Subclass subclass = subclassRepository.findAll().stream()
                .filter(found -> found.getName().equals(name))
                .findFirst()
                .orElseThrow();
        return objectMapper.readTree(subclass.getAdditionalProficiencies());
    }
}
