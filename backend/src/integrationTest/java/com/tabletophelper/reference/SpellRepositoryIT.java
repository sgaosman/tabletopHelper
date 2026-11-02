package com.tabletophelper.reference;

import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpellRepositoryIT extends IntegrationTest {

    // ReferenceController passes one-element sentinel lists when a filter is empty
    private static final List<Integer> NO_LEVELS = List.of(-1);
    private static final List<String> NO_FILTER = List.of("");
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20, Sort.by("name"));

    @Autowired
    private SpellRepository spellRepository;

    @BeforeEach
    void insertSpells() {
        insertSpell("Fireball", 3, "Evocation", "PHB", "[\"Sorcerer\",\"Wizard\"]", false, false);
        insertSpell("Detect Magic", 1, "Divination", "PHB",
                "[\"Bard\",\"Cleric\",\"Druid\",\"Paladin\",\"Ranger\",\"Sorcerer\",\"Wizard\"]", true, true);
        insertSpell("Bless", 1, "Enchantment", "PHB", "[\"Cleric\",\"Paladin\",\"Cleric (Life)\"]", true, false);
        insertSpell("Faerie Fire", 1, "Evocation", "PHB",
                "[\"Bard\",\"Druid\",\"Cleric (Light)\",\"Warlock (Archfey)\"]", true, false);
        insertSpell("Find Familiar", 1, "Conjuration", "PHB", "[\"Wizard\"]", false, true);
        insertSpell("Silvery Barbs", 1, "Enchantment", "SCC", "[\"Bard\",\"Sorcerer\",\"Wizard\"]", false, false);
    }

    private void insertSpell(String name, int level, String school, String source, String classes,
                             boolean concentration, boolean ritual) {
        spellRepository.save(Spell.builder()
                .name(name).level(level).school(school).source(source).classes(classes)
                .concentration(concentration).ritual(ritual)
                .build());
    }

    private Page<Spell> search(List<String> classes, String concentration, String ritual) {
        return spellRepository.searchSpells(null, 0, NO_LEVELS, 0, NO_FILTER, 0, NO_FILTER,
                classes.size(), classes.isEmpty() ? NO_FILTER : classes, concentration, ritual, FIRST_PAGE);
    }

    @Test
    @DisplayName("The class filter matches any listed class in the JSONB classes array, subclass entries included")
    void classFilterReadsJsonbArray() {
        assertThat(search(List.of("Wizard"), null, null).getContent()).extracting(Spell::getName)
                .containsExactly("Detect Magic", "Find Familiar", "Fireball", "Silvery Barbs");
        assertThat(search(List.of("Cleric (Light)"), null, null).getContent()).extracting(Spell::getName)
                .containsExactly("Faerie Fire");
        assertThat(search(List.of("Paladin", "Warlock (Archfey)"), null, null).getContent()).extracting(Spell::getName)
                .containsExactly("Bless", "Detect Magic", "Faerie Fire");
    }

    @Test
    @DisplayName("The concentration and ritual filters cast their text values to booleans")
    void booleanFiltersCastText() {
        assertThat(search(List.of(), "true", null).getContent()).extracting(Spell::getName)
                .containsExactly("Bless", "Detect Magic", "Faerie Fire");
        assertThat(search(List.of(), "false", "true").getContent()).extracting(Spell::getName)
                .containsExactly("Find Familiar");
        assertThat(search(List.of(), "false", "false").getContent()).extracting(Spell::getName)
                .containsExactly("Fireball", "Silvery Barbs");
    }

    @Test
    @DisplayName("Name, level, school and source filters combine, each accepting several values")
    void levelSchoolAndSourceFiltersCombine() {
        var byName = spellRepository.searchSpells("FIRE", 1, List.of(1), 1, List.of("Evocation"), 1, List.of("PHB"),
                0, NO_FILTER, null, null, FIRST_PAGE);
        var byLists = spellRepository.searchSpells(null, 2, List.of(1, 3), 2, List.of("Evocation", "Enchantment"),
                1, List.of("PHB"), 0, NO_FILTER, null, null, FIRST_PAGE);

        assertThat(byName.getContent()).extracting(Spell::getName).containsExactly("Faerie Fire");
        assertThat(byLists.getContent()).extracting(Spell::getName).containsExactly("Bless", "Faerie Fire", "Fireball");
    }

    @Test
    @DisplayName("With no filters every spell comes back, with page counts")
    void noFiltersPagesThroughEverything() {
        var page = spellRepository.searchSpells(null, 0, NO_LEVELS, 0, NO_FILTER, 0, NO_FILTER,
                0, NO_FILTER, null, null, PageRequest.of(1, 4, Sort.by("name")));

        assertThat(page.getTotalElements()).isEqualTo(6);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Spell::getName).containsExactly("Fireball", "Silvery Barbs");
    }

    @Test
    @DisplayName("findDistinctClasses lists each base class once and skips subclass entries")
    void findDistinctClassesSkipsSubclassEntries() {
        assertThat(spellRepository.findDistinctClasses())
                .containsExactly("Bard", "Cleric", "Druid", "Paladin", "Ranger", "Sorcerer", "Wizard");
    }

    @Test
    @DisplayName("findDistinctSubclasses lists only the given class's subclass entries")
    void findDistinctSubclassesListsOneClass() {
        assertThat(spellRepository.findDistinctSubclasses("Cleric")).containsExactly("Cleric (Life)", "Cleric (Light)");
        assertThat(spellRepository.findDistinctSubclasses("Warlock")).containsExactly("Warlock (Archfey)");
        assertThat(spellRepository.findDistinctSubclasses("Wizard")).isEmpty();
    }
}
