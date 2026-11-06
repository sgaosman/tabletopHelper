package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.OptionalFeature;
import com.tabletophelper.reference.OptionalFeatureRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The upsert by natural key every loader uses (D169), over mocked repositories. */
class LoadContextTest {

    private final LoadContext context = new LoadContext(new ObjectMapper());

    private static OptionalFeature feature(UUID id, String name, String description) {
        return OptionalFeature.builder().id(id).name(name).source("PHB").featureType("FightingStyle")
                .description(description).build();
    }

    private static <T> void savesWhatItIsGiven(org.springframework.data.jpa.repository.JpaRepository<T, ?> repository) {
        when(repository.saveAll(anyIterable()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.<Collection<T>>getArgument(0)));
    }

    @Test
    @DisplayName("A stored row keeps its ID and creation time and takes the data's columns; a new row is saved; a row the data lacks is kept")
    void updatesInPlaceInsertsAndKeeps() {
        OptionalFeatureRepository repository = mock(OptionalFeatureRepository.class);
        UUID archeryId = UUID.randomUUID();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        OptionalFeature archery = feature(archeryId, "Archery", "Old text");
        archery.setCreatedAt(created);
        OptionalFeature retired = feature(UUID.randomUUID(), "Retired Style", "Gone from the data");
        when(repository.findAll()).thenReturn(List.of(archery, retired));
        savesWhatItIsGiven(repository);

        Map<String, OptionalFeature> rows = context.upsert("optional_features", repository,
                List.of(feature(null, "Archery", "+2 to ranged attack rolls"), feature(null, "Defense", "+1 AC"),
                        feature(null, "Defense", "A second copy")),
                f -> LoadContext.nameAndSource(f.getName(), f.getSource()));

        assertThat(archery.getId()).isEqualTo(archeryId);
        assertThat(archery.getCreatedAt()).isEqualTo(created);
        assertThat(archery.getDescription()).isEqualTo("+2 to ranged attack rolls");
        assertThat(rows.get("Archery|PHB")).isSameAs(archery);
        assertThat(rows.get("Defense|PHB").getDescription()).isEqualTo("+1 AC");
        assertThat(rows).doesNotContainKey("Retired Style|PHB");
        assertThat(retired.getDescription()).isEqualTo("Gone from the data");
        verify(repository, never()).deleteAll();
        assertThat(context.counts()).containsExactly(
                new LoadContext.TableCounts("optional_features", 1, 1, 0, 1, 1));
    }

    @Test
    @DisplayName("JSON that differs only in spacing and key order is unchanged, and a column another loader owns is left alone")
    void sameJsonAndOwnedColumns() {
        SpellRepository repository = mock(SpellRepository.class);
        Spell stored = Spell.builder().id(UUID.randomUUID()).name("Fireball").source("PHB").level(3)
                .classes("[\"Sorcerer\", \"Wizard\"]").effectTemplate(SpellTemplate.parse("{\"spellName\": \"Fireball\"}")).build();
        when(repository.findAll()).thenReturn(List.of(stored));

        context.upsert("spells", repository, List.of(Spell.builder().name("Fireball").source("PHB").level(3)
                .classes("[\"Sorcerer\",\"Wizard\"]").build()),
                s -> LoadContext.nameAndSource(s.getName(), s.getSource()), "effectTemplate");

        assertThat(stored.getClasses()).isEqualTo("[\"Sorcerer\", \"Wizard\"]");
        assertThat(stored.getEffectTemplate()).isEqualTo(SpellTemplate.parse("{\"spellName\": \"Fireball\"}"));
        assertThat(context.counts().get(0).unchanged()).isEqualTo(1);
        verify(repository, never()).saveAll(anyIterable());
    }

    @Test
    @DisplayName("A related row is compared by its ID, so a subclass under the same class is unchanged")
    void relationsComparedById() {
        SubclassRepository repository = mock(SubclassRepository.class);
        UUID fighterId = UUID.randomUUID();
        CharacterClass loaded = CharacterClass.builder().id(fighterId).name("Fighter").source("PHB").build();
        CharacterClass other = CharacterClass.builder().id(fighterId).name("Fighter").source("PHB").build();
        Subclass stored = Subclass.builder().id(UUID.randomUUID()).name("Champion").source("PHB")
                .characterClass(loaded).features("[]").build();
        when(repository.findAll()).thenReturn(List.of(stored));

        context.upsert("subclasses", repository, List.of(Subclass.builder().name("Champion").source("PHB")
                .characterClass(other).features("[]").build()), CharacterClassLoader::subclassKey);

        assertThat(stored.getCharacterClass()).isSameAs(loaded);
        assertThat(context.counts().get(0).unchanged()).isEqualTo(1);
    }

    // ── Skip rules and failures (D155) ───────────────────────────

    @Test
    @DisplayName("Each skip rule is counted by its name, in the order the rules were first used")
    void skipRulesCountedByName() {
        context.skip("_copy subraces");
        context.skip("SKIP_FILES");
        context.skip("_copy subraces");

        assertThat(context.skips()).containsExactly(Map.entry("_copy subraces", 2), Map.entry("SKIP_FILES", 1));
        assertThat(new LoadContext(new ObjectMapper()).skips()).isEmpty();
    }

    @Test
    @DisplayName("A row that can't be read fails with the row named before the cause, and a row that can is returned")
    void readingNamesTheRow() {
        assertThat(LoadContext.reading("races.json: race Elf|PHB", () -> "Elf")).isEqualTo("Elf");

        assertThatThrownBy(() -> LoadContext.reading("races.json: race Elf|PHB", () -> {
            throw new java.io.IOException("unexpected end of input");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("races.json: race Elf|PHB: unexpected end of input")
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("A file without its array of rows, a missing file and a pattern that matches nothing each fail, naming the file")
    void missingFilesAndArraysFail() throws Exception {
        var root = new ObjectMapper().readTree("{\"race\": {}}");

        assertThatThrownBy(() -> LoadContext.array(root, "race", "races.json"))
                .isInstanceOf(IllegalStateException.class).hasMessage("races.json has no \"race\" array");
        assertThatThrownBy(() -> context.read("data/content/no-such-file.json"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("data/content/no-such-file.json");
        assertThatThrownBy(() -> context.files("data/content/no-such-*.json"))
                .isInstanceOf(IllegalStateException.class).hasMessage("No file matches data/content/no-such-*.json");
        assertThat(context.read("data/content/feat-effects.json").isObject()).isTrue();
    }
}
