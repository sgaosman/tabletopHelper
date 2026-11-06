package com.tabletophelper.content.internal;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceRepository;
import com.tabletophelper.reference.SpellRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The effects overlays (V28): race-effects.json and subclass-effects.json give rows the effects the 5etools data
 * doesn't, by natural key, and a file that names an unknown effect or a row the data doesn't have fails the load.
 */
class EffectsOverlayTest {

    private static final String FILE = "data/content/test-effects.json";
    private final ObjectMapper objectMapper = JacksonDefaults.mapper();

    @TempDir
    Path dir;

    private EffectsOverlay overlay(String json) throws IOException {
        Path file = dir.resolve(FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        LoadContext context = new LoadContext(objectMapper, path -> dir.resolve(path).toUri().toString());
        return EffectsOverlay.read(context, objectMapper, FILE, Set.of("hpPerLevel"));
    }

    @Test
    @DisplayName("A row's effects are found by its natural key; a row the file doesn't name has none")
    void effectsByNaturalKey() throws IOException {
        EffectsOverlay effects = overlay("{\"Hill Dwarf|PHB\": {\"hpPerLevel\": 1}}");

        assertThat(effects.effectsOf("Hill Dwarf|PHB")).isEqualTo("{\"hpPerLevel\":1}");
        assertThat(effects.effectsOf("Mountain Dwarf|PHB")).isNull();
        effects.requireEveryRowFound();
    }

    @Test
    @DisplayName("A row the data doesn't have, as after a rename, fails the load, naming the file and the row")
    void aRowTheDataLacksFails() throws IOException {
        EffectsOverlay effects = overlay("{\"Hill Dwarf|PHB\": {\"hpPerLevel\": 1}, \"Dwarf (Hill)|PHB\": {\"hpPerLevel\": 1}}");
        effects.effectsOf("Hill Dwarf|PHB");

        assertThatThrownBy(effects::requireEveryRowFound).isInstanceOf(IllegalStateException.class)
                .hasMessage(FILE + " names rows the data doesn't have: [Dwarf (Hill)|PHB]");
    }

    @Test
    @DisplayName("An effect the rules don't read, a value that isn't a whole number, or an empty row fails the load")
    void badEffectsFail() {
        assertThatThrownBy(() -> overlay("{\"Hill Dwarf|PHB\": {\"hpPerClassLevel\": 1}}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hpPerClassLevel, which isn't one of");
        assertThatThrownBy(() -> overlay("{\"Hill Dwarf|PHB\": {\"hpPerLevel\": 1.5}}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("is not a whole number");
        assertThatThrownBy(() -> overlay("{\"Hill Dwarf|PHB\": {}}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has no effects object");
        assertThatThrownBy(() -> overlay("[]"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("is not an object of natural keys");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("The races loader gives the Hill Dwarf its hpPerLevel from race-effects.json, and no other race any effects")
    void raceLoaderAppliesTheOverlay() throws Exception {
        RaceRepository races = mock(RaceRepository.class);
        SpellRepository spells = mock(SpellRepository.class);
        when(races.findAll()).thenReturn(List.of());
        when(spells.findAll()).thenReturn(List.of());
        when(races.saveAll(anyIterable()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.<Collection<Race>>getArgument(0)));

        new RaceLoader(races, spells, objectMapper).load(new LoadContext(objectMapper));

        ArgumentCaptor<Collection<Race>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(races).saveAll(saved.capture());
        assertThat(saved.getValue()).filteredOn(race -> race.getEffects() != null)
                .extracting(race -> race.getName() + "|" + race.getSource() + " " + race.getEffects())
                .containsExactly("Hill Dwarf|PHB {\"hpPerLevel\":1}");
    }
}
