package com.tabletophelper.content.internal;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
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
}
