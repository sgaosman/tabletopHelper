package com.tabletophelper.rules.character;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lists a subclass's bonus proficiencies of the player's choice are chosen from, typed from the PHB. */
class ProficiencyListsTest {

    @Test
    @DisplayName("The PHB has 18 skills (p.174), 16 standard and exotic languages (p.123), 17 artisan's tools and four "
            + "gaming sets (p.154)")
    void listSizes() {
        assertThat(ProficiencyLists.SKILLS).hasSize(18).doesNotHaveDuplicates();
        assertThat(ProficiencyLists.LANGUAGES).hasSize(16).doesNotHaveDuplicates().contains("Deep Speech");
        assertThat(ProficiencyLists.ARTISANS_TOOLS).hasSize(17).doesNotHaveDuplicates();
        assertThat(ProficiencyLists.GAMING_SETS).hasSize(4);
    }

    @Test
    @DisplayName("A list name is written out in place, other names are kept, and each name appears once whatever its "
            + "case")
    void expands() {
        assertThat(ProficiencyLists.expand(List.of("Draconic", "*languages")))
                .hasSize(16).startsWith("Draconic", "Common");
        assertThat(ProficiencyLists.expand(List.of("Arcana", "arcana", " Nature "))).containsExactly("Arcana", "Nature");
        assertThat(ProficiencyLists.expand(List.of("*Gaming Sets"))).isEqualTo(ProficiencyLists.GAMING_SETS);
    }

    @Test
    @DisplayName("An unknown list name is refused, naming the lists")
    void unknownList() {
        assertThatThrownBy(() -> ProficiencyLists.expand(List.of("*musical instruments")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("*musical instruments isn't one of the lists [*artisan's tools,");
    }

    @Test
    @DisplayName("One-handed melee weapons leave out the two-handed ones and the lance (PHB p.147, p.149), and each "
            + "knows its category")
    void oneHandedMelee() {
        List<String> weapons = ProficiencyLists.expand(List.of("*one-handed melee weapons"));

        assertThat(weapons).hasSize(20).contains("Quarterstaff", "Spear", "Battleaxe", "Whip")
                .doesNotContain("Greatclub", "Greatsword", "Glaive", "Halberd", "Lance", "Maul", "Pike", "Greataxe");
        assertThat(ProficiencyLists.weaponCategory("dagger")).contains("simple");
        assertThat(ProficiencyLists.weaponCategory("Rapier")).contains("martial");
        assertThat(ProficiencyLists.weaponCategory("Longbow")).contains("martial");
        assertThat(ProficiencyLists.weaponCategory("light crossbow")).contains("simple");
        assertThat(ProficiencyLists.weaponCategory("Musket")).isEmpty();
    }

    @Test
    @DisplayName("The PHB's weapon table has 10 simple melee, 4 simple ranged, 18 martial melee and 5 martial ranged "
            + "weapons (p.149)")
    void weaponTable() {
        assertThat(ProficiencyLists.SIMPLE_MELEE).hasSize(10).doesNotHaveDuplicates();
        assertThat(ProficiencyLists.SIMPLE_RANGED).hasSize(4).doesNotHaveDuplicates();
        assertThat(ProficiencyLists.MARTIAL_MELEE).hasSize(18).doesNotHaveDuplicates();
        assertThat(ProficiencyLists.MARTIAL_RANGED).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Kensei weapons are simple or martial weapons without the heavy and special properties, and the "
            + "longbow (XGE p.34): 10 simple and 11 martial melee, 4 simple ranged, and the blowgun, hand crossbow and "
            + "longbow")
    void kenseiWeapons() {
        assertThat(ProficiencyLists.KENSEI_MELEE).hasSize(21)
                .contains("Greatclub", "Javelin", "Longsword", "Rapier", "Shortsword", "Whip", "Warhammer")
                .doesNotContain("Glaive", "Greataxe", "Greatsword", "Halberd", "Lance", "Maul", "Pike");
        assertThat(ProficiencyLists.KENSEI_RANGED).containsExactly("Light Crossbow", "Dart", "Shortbow", "Sling",
                "Blowgun", "Hand Crossbow", "Longbow");
        assertThat(ProficiencyLists.expand(List.of("*kensei weapons"))).hasSize(28);
        assertThat(ProficiencyLists.expand(List.of("*kensei melee weapons")))
                .isEqualTo(ProficiencyLists.KENSEI_MELEE);

        assertThat(ProficiencyLists.isKenseiWeapon("longbow")).isTrue();
        assertThat(ProficiencyLists.isKenseiWeapon(" Hand Crossbow ")).isTrue();
        assertThat(ProficiencyLists.isKenseiWeapon("Heavy Crossbow")).as("heavy").isFalse();
        assertThat(ProficiencyLists.isKenseiWeapon("Net")).as("special").isFalse();
        assertThat(ProficiencyLists.isKenseiWeapon("Lance")).as("special").isFalse();
        assertThat(ProficiencyLists.isKenseiWeapon("Greatsword")).as("heavy").isFalse();
        assertThat(ProficiencyLists.isKenseiWeapon("Musket")).as("not a PHB weapon").isFalse();
        assertThat(ProficiencyLists.isKenseiWeapon(null)).isFalse();
    }
}
