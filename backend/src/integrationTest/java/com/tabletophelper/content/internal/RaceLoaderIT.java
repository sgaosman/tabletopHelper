package com.tabletophelper.content.internal;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.Race;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The race loader's fixed spells (D231), with the spells loaded first as the content load orders them: a levelled
 * spell a race knows is cast once between rests unless the data says at will, and those cast once between short or
 * long rests are kept with that reset.
 */
class RaceLoaderIT extends IntegrationTest {

    @Autowired private SpellLoader spellLoader;
    @Autowired private RaceLoader raceLoader;
    @Autowired private PlatformTransactionManager transactionManager;

    private void load() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                spellLoader.load(new LoadContext(objectMapper));
                raceLoader.load(new LoadContext(objectMapper));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // The race's fixed spells: name, level, at will, uses and reset
    private List<Object[]> fixedSpells(String name, String source) {
        Race race = raceRepository.findAll().stream()
                .filter(r -> r.getName().equals(name) && r.getSource().equals(source))
                .findFirst().orElseThrow(() -> new AssertionError("No race " + name + "|" + source));
        List<Object[]> spells = new ArrayList<>();
        for (JsonNode spell : objectMapper.readTree(race.getAdditionalSpells()).path("fixedSpells")) {
            spells.add(new Object[]{spell.path("name").asString(), spell.path("level").asInt(),
                    spell.path("atWill").asBoolean(), spell.path("usesPerLongRest").asInt(0),
                    spell.path("usesResetOn").asString(null)});
        }
        return spells;
    }

    @Test
    @DisplayName("A race's known levelled spells are cast once a long rest unless the data says at will: a Hexblood's "
            + "hex and disguise self (VRGR p.18), the Marks' and the EEPC genasi's, and the DMG deep gnome's, whose "
            + "nondetection is at will (DMG p.282)")
    void knownSpellsOnceALongRest() {
        load();

        assertThat(fixedSpells("Hexblood", "VRGR")).extracting(s -> tuple(s))
                .containsExactlyInAnyOrder(tuple("Disguise Self", 1, false, 1, null), tuple("Hex", 1, false, 1, null));
        assertThat(fixedSpells("Air Genasi", "EEPC")).extracting(s -> tuple(s))
                .containsExactly(tuple("Levitate", 2, false, 1, null));
        assertThat(fixedSpells("Mark of Passage Human", "ERLW")).extracting(s -> tuple(s))
                .containsExactly(tuple("Misty Step", 2, false, 1, null));
        assertThat(fixedSpells("Mark of Making Human", "ERLW")).extracting(s -> tuple(s))
                .containsExactlyInAnyOrder(tuple("Mending", 0, true, 0, null), tuple("Magic Weapon", 2, false, 1, null));
        assertThat(fixedSpells("Gnome (Deep)", "DMG")).extracting(s -> tuple(s)).containsExactlyInAnyOrder(
                tuple("Nondetection", 3, true, 0, null), tuple("Blindness/deafness", 2, false, 1, null),
                tuple("Blur", 2, false, 1, null), tuple("Disguise Self", 1, false, 1, null));
    }

    @Test
    @DisplayName("D237: a Mark of Warding casts arcane lock from 3rd level, once a long rest, as Wards and Seals says "
            + "(ERLW), though the data lists only alarm and mage armor")
    void markOfWardingArcaneLock() {
        load();

        assertThat(fixedSpells("Mark of Warding Dwarf", "ERLW")).extracting(s -> tuple(s)).containsExactlyInAnyOrder(
                tuple("Alarm", 1, false, 1, null), tuple("Mage Armor", 1, false, 1, null),
                tuple("Arcane Lock", 2, false, 1, null));
        Race race = raceRepository.findAll().stream()
                .filter(r -> r.getName().equals("Mark of Warding Dwarf") && r.getSource().equals("ERLW"))
                .findFirst().orElseThrow();
        JsonNode arcaneLock = null;
        for (JsonNode spell : objectMapper.readTree(race.getAdditionalSpells()).path("fixedSpells")) {
            if (spell.path("name").asString().equals("Arcane Lock")) arcaneLock = spell;
        }
        assertThat(arcaneLock).isNotNull();
        assertThat(arcaneLock.path("unlocksAtLevel").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("Spells cast once between short or long rests are kept with that reset: the VGM firbolg's (VGM p.107), "
            + "the DMG eladrin's misty step (DMG p.286), the Mark of Handling's (ERLW p.42), and the Mark of "
            + "Scribing's comprehend languages beside its magic mouth once a long rest (ERLW p.47)")
    void shortRestSpells() {
        load();

        assertThat(fixedSpells("Firbolg", "VGM")).extracting(s -> tuple(s)).containsExactlyInAnyOrder(
                tuple("Detect Magic", 1, false, 1, "shortRest"), tuple("Disguise Self", 1, false, 1, "shortRest"));
        assertThat(fixedSpells("Eladrin Elf", "DMG")).extracting(s -> tuple(s))
                .containsExactly(tuple("Misty Step", 2, false, 1, "shortRest"));
        assertThat(fixedSpells("Mark of Handling Human", "ERLW")).extracting(s -> tuple(s)).containsExactlyInAnyOrder(
                tuple("Animal Friendship", 1, false, 1, "shortRest"), tuple("Speak With Animals", 1, false, 1, "shortRest"));
        assertThat(fixedSpells("Mark of Scribing Gnome", "ERLW")).extracting(s -> tuple(s)).contains(
                tuple("Comprehend Languages", 1, false, 1, "shortRest"), tuple("Magic Mouth", 2, false, 1, null));
    }
}
