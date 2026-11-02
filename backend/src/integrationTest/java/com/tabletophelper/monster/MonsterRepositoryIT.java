package com.tabletophelper.monster;

import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonsterRepositoryIT extends IntegrationTest {

    // MonsterService passes a one-element sentinel list when a filter is empty
    private static final List<String> NO_FILTER = List.of("");

    @Autowired
    private MonsterRepository monsterRepository;

    private void insertMonster(String name, String type, String cr, String source) {
        monsterRepository.save(Monster.builder().name(name).type(type).challengeRating(cr).source(source).build());
    }

    private void insertBestiary() {
        insertMonster("Goblin", "Humanoid", "1/4", "MM");
        insertMonster("Bugbear", "Humanoid", "1", "MM");
        insertMonster("Owlbear", "Monstrosity", "3", "MM");
        insertMonster("Young Red Dragon", "Dragon", "10", "MM");
        insertMonster("Gnoll Pack Lord", "Humanoid", "2", "VGM");
    }

    private void insertGoblinoids() {
        insertMonster("Hobgoblin", "Humanoid", "1/2", "MM");
        insertMonster("Goblin Boss", "Humanoid", "1", "MM");
        insertMonster("Goblyn", "Humanoid", "1/4", "HB");
        insertMonster("Goblin", "Humanoid", "1/4", "MM");
        insertMonster("Bugbear", "Humanoid", "1", "MM");
    }

    @Test
    @DisplayName("searchMonsters with no filters returns every monster, sorted by name, with page counts")
    void searchMonstersWithoutFiltersPages() {
        insertBestiary();

        var page = monsterRepository.searchMonsters(null, 0, NO_FILTER, 0, NO_FILTER, 0, NO_FILTER,
                PageRequest.of(0, 2, Sort.by("name")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Monster::getName).containsExactly("Bugbear", "Gnoll Pack Lord");
    }

    @Test
    @DisplayName("searchMonsters combines type, CR and source filters, and compares types in lower case")
    void searchMonstersCombinesFilters() {
        insertBestiary();

        var page = monsterRepository.searchMonsters(null,
                1, List.of("humanoid"), 2, List.of("1/4", "1"), 1, List.of("MM"),
                PageRequest.of(0, 20, Sort.by("name")));

        assertThat(page.getContent()).extracting(Monster::getName).containsExactly("Bugbear", "Goblin");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("searchMonsters matches a name substring whatever its case")
    void searchMonstersMatchesNameSubstring() {
        insertBestiary();

        var page = monsterRepository.searchMonsters("BEAR", 0, NO_FILTER, 0, NO_FILTER, 0, NO_FILTER,
                PageRequest.of(0, 20, Sort.by("name")));

        assertThat(page.getContent()).extracting(Monster::getName).containsExactly("Bugbear", "Owlbear");
    }

    @Test
    @DisplayName("fuzzySearchByName ranks prefix matches, then substring matches, then trigram matches, and honours the limit")
    void fuzzySearchRanksMatches() {
        insertGoblinoids();

        assertThat(monsterRepository.fuzzySearchByName("goblin", 10)).extracting(Monster::getName)
                .containsExactly("Goblin", "Goblin Boss", "Hobgoblin", "Goblyn");
        assertThat(monsterRepository.fuzzySearchByName("goblin", 2)).extracting(Monster::getName)
                .containsExactly("Goblin", "Goblin Boss");
    }

    @Test
    @DisplayName("fuzzySearchByName still finds the Goblin when the search has a typo")
    void fuzzySearchToleratesTypo() {
        insertGoblinoids();

        var names = monsterRepository.fuzzySearchByName("goblim", 10).stream().map(Monster::getName).toList();

        assertThat(names).startsWith("Goblin").doesNotContain("Bugbear");
    }
}
