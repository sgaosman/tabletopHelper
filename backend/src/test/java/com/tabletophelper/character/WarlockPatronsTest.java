package com.tabletophelper.character;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.SubclassChosen;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Genie's and the Fathomless's features read from a warlock's sheet (TCE, D242): the Genie's kind and its
 * damage type, Elemental Gift's resistance from Warlock 6, and Grasping Tentacles from Fathomless 10.
 */
class WarlockPatronsTest {

    // A single-class warlock of the patron and level, its kind recorded on Warlock 1 when one is given
    private static PlayerCharacter warlock(String patron, int level, String kind) {
        PlayerCharacter pc = PlayerCharacter.builder().id(UUID.randomUUID()).name("Vex").level(level)
                .characterClass("Warlock").subclass(patron).proficiencyBonus(2).build();
        UUID classId = UUID.randomUUID();
        LevelChoices choices = LevelChoices.NONE.withSubclass(new SubclassChosen(classId, UUID.randomUUID(), patron));
        if (kind != null) choices = choices.withSubclassOption(kind);
        pc.setLevelHistory(new ArrayList<>(List.of(new LevelHistoryEntry(1, classId, "Warlock", 1, 8, null, null,
                choices, null, null))));
        return pc;
    }

    @Test
    @DisplayName("Each kind gives its damage type: bludgeoning (dao), thunder (djinni), fire (efreeti), cold (marid)")
    void kindTypes() {
        assertEquals(Optional.of("bludgeoning"), WarlockPatrons.kindDamageType("Dao"));
        assertEquals(Optional.of("thunder"), WarlockPatrons.kindDamageType("djinni"));
        assertEquals(Optional.of("fire"), WarlockPatrons.kindDamageType("Efreeti"));
        assertEquals(Optional.of("cold"), WarlockPatrons.kindDamageType("MARID"));
        assertEquals(Optional.empty(), WarlockPatrons.kindDamageType("Ifrit"));
    }

    @Test
    @DisplayName("Genie's Wrath's type is the recorded kind's; none until a kind is chosen or for another patron")
    void wrathType() {
        assertEquals(Optional.of("fire"), WarlockPatrons.geniesWrathType(warlock(WarlockPatrons.GENIE, 1, "Efreeti")));
        assertEquals(Optional.empty(), WarlockPatrons.geniesWrathType(warlock(WarlockPatrons.GENIE, 1, null)));
        assertEquals(Optional.empty(), WarlockPatrons.geniesWrathType(warlock("The Fiend", 1, "Efreeti")));
    }

    @Test
    @DisplayName("Elemental Gift's resistance comes at Warlock 6 and shows among the sheet's resistances, counted once")
    void elementalGiftResistance() {
        PlayerCharacter fifth = warlock(WarlockPatrons.GENIE, 5, "Marid");
        PlayerCharacter sixth = warlock(WarlockPatrons.GENIE, 6, "Marid");
        sixth.setDamageResistances(List.of("Cold"));

        assertEquals(Optional.empty(), WarlockPatrons.elementalGiftResistance(fifth));
        assertFalse(DmAdjustments.damageTypes(fifth, Defence.RESISTANCE).contains("cold"));
        assertEquals(Optional.of("cold"), WarlockPatrons.elementalGiftResistance(sixth));
        assertEquals(List.of("Cold"), DmAdjustments.damageTypes(sixth, Defence.RESISTANCE));
        assertEquals(Set.of(Defence.RESISTANCE), DmAdjustments.defences(sixth, "cold"));
        assertEquals(List.of("thunder"),
                DmAdjustments.damageTypes(warlock(WarlockPatrons.GENIE, 6, "Djinni"), Defence.RESISTANCE));
    }

    @Test
    @DisplayName("A multiclass Genie warlock counts its warlock levels only")
    void multiclass() {
        PlayerCharacter pc = warlock(WarlockPatrons.GENIE, 8, "Dao");
        pc.setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Warlock", null, WarlockPatrons.GENIE, 5),
                new ClassEntry(UUID.randomUUID(), "Fighter", null, null, 3)));

        assertEquals(5, WarlockPatrons.patronLevel(pc, WarlockPatrons.GENIE));
        assertEquals(Optional.empty(), WarlockPatrons.elementalGiftResistance(pc));
        assertEquals(Optional.of("bludgeoning"), WarlockPatrons.geniesWrathType(pc));
    }

    @Test
    @DisplayName("Grasping Tentacles is a Fathomless warlock's from 10th level, and bolsters Evard's black tentacles")
    void graspingTentacles() {
        assertFalse(WarlockPatrons.graspingTentacles(warlock(WarlockPatrons.FATHOMLESS, 9, null)));
        assertTrue(WarlockPatrons.graspingTentacles(warlock(WarlockPatrons.FATHOMLESS, 10, null)));
        assertFalse(WarlockPatrons.graspingTentacles(warlock(WarlockPatrons.GENIE, 10, "Dao")));
        assertTrue(WarlockPatrons.blackTentacles("evard's black tentacles"));
        assertFalse(WarlockPatrons.blackTentacles("Hunger of Hadar"));
    }
}
