package com.tabletophelper.seeder;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CharacterClassSeederIT extends IntegrationTest {

    @Autowired private CharacterClassSeeder characterClassSeeder;
    @Autowired private SubclassRepository subclassRepository;

    @Test
    @DisplayName("After seeding, the 13 classes carry the spellcaster and pact magic flags the every-class slot table assumes")
    void seededClassesCarryCasterFlags() throws Exception {
        characterClassSeeder.seed();

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
    @DisplayName("After seeding, the third-caster subclasses sit under the non-caster Fighter and Rogue")
    void thirdCasterSubclassesBelongToNonCasters() throws Exception {
        characterClassSeeder.seed();

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
