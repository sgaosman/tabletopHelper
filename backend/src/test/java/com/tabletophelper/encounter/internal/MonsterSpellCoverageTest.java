package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The monster spell coverage sweep (D191): every spell in every seeded monster's spellcasting, read as an encounter
 * reads it, resolves to a spell in the spells data and to that spell's effect template. Each monster is one the
 * bestiary loads (not a _copy), with the first action template of its name and source, as MonsterActionLoader stores
 * them; its spells are read by {@link MonsterPools#spells(Monster)} over a spells table built from the same
 * spells-*.json files SpellLoader seeds it from, so each name is looked up exactly as in an encounter. A spell's
 * effect template is the spell's own, written by SpellEffectLoader from the definition of the same name, case aside.
 * <p>
 * The number of names that don't resolve may not rise above the baseline, and the walk must reach a minimum number
 * of monsters and spells, so empty data can't pass. Each run writes the report to
 * {@code build/reports/monster-spell-coverage.txt}: the counts, each name that doesn't resolve, and how the names as
 * listed, before {@link MonsterSpellbook#canonicalName} cleans them, would have failed an exact lookup.
 * <p>
 * And the other way round (D193): every monster the bestiary loads whose stat block has a spellcasting block with
 * spell lists ({@code spells}, {@code will} or {@code daily}) has a definition whose spellbook gives it at least one
 * spell it can cast, one the spells data lists. The definitions for the 126 that had none are written by
 * {@code backend/scripts/generate_monster_spellcasting.py} from the stat blocks.
 */
class MonsterSpellCoverageTest {

    private static final String BESTIARY = "classpath:data/5etools/bestiary/bestiary-*.json";
    private static final String SPELLS = "classpath:data/5etools/spells/spells-*.json";
    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final String SPELL_EFFECTS = "data/spell-effects/spell-effect-definitions.json";
    private static final Path REPORT = Path.of("build", "reports", "monster-spell-coverage.txt");

    // The baseline after the D191 sweep: no monster spell fails to resolve to a spell or to its template
    private static final int UNRESOLVED_SPELLS_BASELINE = 0;
    private static final int UNRESOLVED_TEMPLATES_BASELINE = 0;
    // The data had 765 monsters with spells, listing 6407 spells, when the sweep was written
    private static final int MINIMUM_MONSTERS = 700;
    private static final int MINIMUM_SPELL_ENTRIES = 6000;
    // The baseline after D193: every stat-block spellcaster can cast; there were 126 that couldn't before
    private static final int SPELLCASTERS_WITHOUT_SPELLS_BASELINE = 0;
    // The bestiary had 891 loaded monsters with spell lists in their stat blocks when D193 was written
    private static final int MINIMUM_STAT_BLOCK_SPELLCASTERS = 850;

    private final ObjectMapper objectMapper = JacksonDefaults.mapper();

    @Test
    @DisplayName("Every monster spell resolves to a spell and its effect template, no worse than the baseline")
    void everyMonsterSpellResolves() throws IOException {
        Map<String, Spell> spellsByName = spells();
        Set<String> templates = templates();
        Set<String> loadedMonsters = loadedMonsters();

        // The spells table as the encounter reads it; every spell in it has a casting time, so a spell read
        // without one is one the table doesn't list
        SpellRepository spellRepository = mock(SpellRepository.class);
        when(spellRepository.findByNameIgnoreCase(anyString())).thenAnswer(call -> Optional.ofNullable(
                spellsByName.get(call.<String>getArgument(0).toLowerCase(Locale.ROOT))));
        MonsterPools monsterPools = new MonsterPools(mock(MonsterPoolTriggerRepository.class), spellRepository,
                objectMapper);

        int monsters = 0;
        int entries = 0;
        Map<String, Integer> listedNames = new TreeMap<>();
        Map<String, Integer> unresolvedSpells = new TreeMap<>();
        Map<String, Integer> unresolvedTemplates = new TreeMap<>();
        Set<String> canonicalNames = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode definition : read(new ClassPathResource(DEFINITIONS))) {
            String key = key(definition.path("monsterName").asString(""), definition.path("source").asString(""));
            if (!loadedMonsters.contains(key) || !seen.add(key)) {
                continue;
            }
            MonsterTemplates actionTemplates = MonsterTemplates.of(definition);
            MonsterSpellbook book = MonsterSpellbook.read(actionTemplates);
            if (book.spells().isEmpty()) {
                continue;
            }
            monsters++;
            book.spells().forEach(entry -> listedNames.merge(entry.name(), 1, Integer::sum));

            Monster monster = Monster.builder()
                    .name(definition.path("monsterName").asString(""))
                    .source(definition.path("source").asString(""))
                    .actionTemplates(actionTemplates)
                    .build();
            List<SpellSources.Known> known = monsterPools.spells(monster);
            assertEquals(book.spells().size(), known.size(), monster.getName());
            for (SpellSources.Known spell : known) {
                entries++;
                canonicalNames.add(spell.name().toLowerCase(Locale.ROOT));
                if (spell.castingTime() == null) {
                    unresolvedSpells.merge(spell.name(), 1, Integer::sum);
                } else if (!templates.contains(spell.name().toLowerCase(Locale.ROOT))) {
                    unresolvedTemplates.merge(spell.name(), 1, Integer::sum);
                }
            }
        }

        // The names as listed, looked up as they are, case aside: how each that fails would fail; and those in
        // another case than the spells data's, which the lookup ignores
        Map<String, Map<String, Integer>> rawFailures = new TreeMap<>();
        int otherCase = 0;
        for (var listed : listedNames.entrySet()) {
            String name = listed.getKey();
            Spell spell = spellsByName.get(name.trim().toLowerCase(Locale.ROOT));
            if (spell == null) {
                rawFailures.computeIfAbsent(category(name, spellsByName), category -> new TreeMap<>())
                        .put(name, listed.getValue());
            } else if (!spell.getName().equals(name.trim())) {
                otherCase++;
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("# Monster spell coverage (D191): seeded monsters' spells against the spells data and templates");
        lines.add("monsters with spells: " + monsters);
        lines.add("spell entries: " + entries);
        lines.add("distinct names as listed: " + listedNames.size());
        lines.add("distinct names after canonicalName: " + canonicalNames.size());
        lines.add("entries not in the spells data: " + total(unresolvedSpells) + " (" + unresolvedSpells.size()
                + " distinct)");
        lines.add("entries whose spell has no effect template: " + total(unresolvedTemplates) + " ("
                + unresolvedTemplates.size() + " distinct)");
        unresolvedSpells.forEach((name, count) -> lines.add("no spell | " + name + " | " + count));
        unresolvedTemplates.forEach((name, count) -> lines.add("no template | " + name + " | " + count));
        lines.add("# The names as listed: " + otherCase + " distinct are in another case than the spells data's, which"
                + " the lookup ignores; those a lookup, case aside, would miss without canonicalName, by why");
        rawFailures.forEach((category, names) -> {
            lines.add(category + ": " + names.size() + " distinct, " + total(names) + " entries");
            names.forEach((name, count) -> lines.add("  " + name + " | " + count));
        });
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, lines, StandardCharsets.UTF_8);
        System.out.println("Monster spell coverage: " + monsters + " monsters, " + entries + " entries, "
                + total(unresolvedSpells) + " without a spell, " + total(unresolvedTemplates)
                + " without a template; written to " + REPORT.toAbsolutePath());

        assertTrue(monsters >= MINIMUM_MONSTERS, "only " + monsters + " monsters with spells were walked");
        assertTrue(entries >= MINIMUM_SPELL_ENTRIES, "only " + entries + " spell entries were walked");
        assertTrue(total(unresolvedSpells) <= UNRESOLVED_SPELLS_BASELINE,
                "monster spells not in the spells data: " + unresolvedSpells);
        assertTrue(total(unresolvedTemplates) <= UNRESOLVED_TEMPLATES_BASELINE,
                "monster spells without an effect template: " + unresolvedTemplates);
    }

    @Test
    @DisplayName("Every bestiary monster with spell lists in its stat block can cast at least one spell")
    void everyStatBlockSpellcasterCasts() throws IOException {
        Map<String, Spell> spellsByName = spells();
        SpellRepository spellRepository = mock(SpellRepository.class);
        when(spellRepository.findByNameIgnoreCase(anyString())).thenAnswer(call -> Optional.ofNullable(
                spellsByName.get(call.<String>getArgument(0).toLowerCase(Locale.ROOT))));
        MonsterPools monsterPools = new MonsterPools(mock(MonsterPoolTriggerRepository.class), spellRepository,
                objectMapper);

        // The first definition of each name and source, as MonsterActionLoader stores them
        Map<String, JsonNode> definitions = new HashMap<>();
        for (JsonNode definition : read(new ClassPathResource(DEFINITIONS))) {
            definitions.putIfAbsent(key(definition.path("monsterName").asString(""),
                    definition.path("source").asString("")), definition);
        }

        int spellcasters = 0;
        List<String> withoutSpells = new ArrayList<>();
        for (JsonNode monster : loadedStatBlocks().values()) {
            if (!hasSpellLists(monster.path("spellcasting"))) {
                continue;
            }
            spellcasters++;
            String name = monster.path("name").asString("");
            String source = monster.path("source").asString("");
            JsonNode definition = definitions.get(key(name, source));
            long castable = definition == null ? 0 : monsterPools.spells(Monster.builder().name(name).source(source)
                            .actionTemplates(MonsterTemplates.of(definition)).build()).stream()
                    .filter(spell -> spell.castingTime() != null)
                    .count();
            if (castable == 0) {
                withoutSpells.add(name + " (" + source + ")");
            }
        }
        System.out.println("Stat-block spellcasters: " + spellcasters + ", " + withoutSpells.size()
                + " without a castable spell");

        assertTrue(spellcasters >= MINIMUM_STAT_BLOCK_SPELLCASTERS, "only " + spellcasters + " spellcasters walked");
        assertTrue(withoutSpells.size() <= SPELLCASTERS_WITHOUT_SPELLS_BASELINE,
                "stat-block spellcasters without a castable spell: " + withoutSpells);
    }

    // Whether any of a stat block's spellcasting blocks lists spells to cast
    private static boolean hasSpellLists(JsonNode blocks) {
        for (JsonNode block : blocks) {
            for (String list : List.of("spells", "will", "daily")) {
                if (!block.path(list).isMissingNode() && !block.path(list).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    // Why a name as listed misses an exact lookup: the part canonicalName removes, or that it isn't in the data
    private static String category(String listed, Map<String, Spell> spellsByName) {
        String canonical = MonsterSpellbook.canonicalName(listed);
        if (!spellsByName.containsKey(canonical.toLowerCase(Locale.ROOT))) {
            return "not in the spells data";
        }
        String trimmed = listed.trim();
        if (trimmed.endsWith(")")) {
            return "a note in brackets";
        }
        if (!canonical.equalsIgnoreCase(trimmed.replaceAll("[*†‡¹²³⁰-⁹]+$", "")
                .trim())) {
            return "apostrophes or spacing";
        }
        return "a footnote marker";
    }

    // Each spell in the spells data by its name in lower case, as SpellLoader seeds them: _copy spells aside, and
    // the first of a name and source
    private Map<String, Spell> spells() throws IOException {
        Map<String, Spell> spells = new HashMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(SPELLS)) {
            if (resource.getFilename() != null && resource.getFilename().contains("fluff")) {
                continue;
            }
            for (JsonNode spell : read(resource).path("spell")) {
                if (spell.has("_copy")) {
                    continue;
                }
                String name = spell.path("name").asString("");
                spells.putIfAbsent(name.toLowerCase(Locale.ROOT), Spell.builder()
                        .id(UUID.randomUUID())
                        .name(name)
                        .level(spell.path("level").asInt(0))
                        .castingTime("1 action")
                        .build());
            }
        }
        assertFalse(spells.isEmpty());
        return spells;
    }

    // The spell names with an effect definition, in lower case, as SpellEffectLoader matches them
    private Set<String> templates() throws IOException {
        Set<String> names = new HashSet<>();
        for (JsonNode definition : read(new ClassPathResource(SPELL_EFFECTS))) {
            if (definition.path("spellName").isString()) {
                names.add(definition.path("spellName").asString().toLowerCase(Locale.ROOT));
            }
        }
        assertFalse(names.isEmpty());
        return names;
    }

    // The name and source of each monster the bestiary loads, as MonsterLoader reads it: _copy monsters, fluff and
    // index files aside
    private Set<String> loadedMonsters() throws IOException {
        return new LinkedHashSet<>(loadedStatBlocks().keySet());
    }

    // The stat block of each monster the bestiary loads, by name and source, the first of each
    private Map<String, JsonNode> loadedStatBlocks() throws IOException {
        Map<String, JsonNode> monsters = new LinkedHashMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(BESTIARY)) {
            String file = resource.getFilename() != null ? resource.getFilename() : "";
            if (file.contains("fluff") || file.contains("index")) {
                continue;
            }
            for (JsonNode monster : read(resource).path("monster")) {
                if (!monster.has("_copy")) {
                    monsters.putIfAbsent(key(monster.path("name").asString(""),
                            monster.path("source").asString("")), monster);
                }
            }
        }
        assertFalse(monsters.isEmpty());
        return monsters;
    }

    private static String key(String name, String source) {
        return name.trim().toLowerCase(Locale.ROOT) + "|" + source.trim().toLowerCase(Locale.ROOT);
    }

    private static int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private JsonNode read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readTree(in);
        }
    }
}
