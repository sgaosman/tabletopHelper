package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.PoolLevelTable;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import com.tabletophelper.reference.SubclassRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResourcePoolLoader checks every formula and level table before it writes anything (D169), reading a
 * resource-pools.json written for each test in place of the bundled one.
 */
class ResourcePoolLoaderTest {

    @TempDir
    Path dir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResourcePoolDefinitionRepository definitions = mock(ResourcePoolDefinitionRepository.class);
    private final List<ResourcePoolDefinition> saved = new ArrayList<>();

    private ResourcePoolLoader loader() {
        when(definitions.saveAll(anyIterable())).thenAnswer(invocation -> {
            invocation.<Iterable<ResourcePoolDefinition>>getArgument(0).forEach(saved::add);
            return new ArrayList<>(saved);
        });
        ClassFeaturePoolRepository mappings = mock(ClassFeaturePoolRepository.class);
        when(mappings.saveAll(anyIterable())).thenAnswer(invocation -> List.of());
        MonsterPoolTriggerRepository triggers = mock(MonsterPoolTriggerRepository.class);
        when(triggers.saveAll(anyIterable())).thenAnswer(invocation -> List.of());
        CharacterClassRepository classes = mock(CharacterClassRepository.class);
        when(classes.findAll()).thenReturn(List.of(
                CharacterClass.builder().id(UUID.randomUUID()).name("Paladin").source("PHB").build(),
                CharacterClass.builder().id(UUID.randomUUID()).name("Bard").source("PHB").build()));
        return new ResourcePoolLoader(definitions, mappings, triggers, classes, mock(SubclassRepository.class),
                objectMapper);
    }

    // A file of one definition, with the given JSON fields after the required ones
    private LoadContext contextWith(String fields) throws Exception {
        Files.writeString(dir.resolve("resource-pools.json"), """
                {"definitions": [{"poolId": "class:test", "displayName": "Test", "sourceType": "CLASS",
                  "resetOn": "longRest", %s}],
                 "classPools": [], "monsterTriggers": []}
                """.formatted(fields));
        return new LoadContext(objectMapper, path -> dir.resolve("resource-pools.json").toUri().toString());
    }

    @Test
    @DisplayName("Valid formulas and a level table load, the table typed as the column holds it")
    void validDefinitionLoads() throws Exception {
        loader().load(contextWith("""
                "maxUsesFormula": "max(0, 1+charismaModifier) + 5*paladinLevel", "resetAmount": "floor(maxUses/2)",
                "levelTable": {"levelOf": "Bard", "rows": [{"level": 5, "resetOn": "shortRest", "die": "d8"},
                  {"level": 20, "maxUses": "6", "unlimited": true}]}"""));

        assertThat(saved).singleElement().satisfies(def -> assertThat(def.getLevelTable()).isEqualTo(
                new PoolLevelTable("Bard", List.of(new PoolLevelTable.Row(5, null, "shortRest", "d8", null),
                        new PoolLevelTable.Row(20, "6", null, null, true)))));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "an unknown name|\"maxUsesFormula\": \"5*paladinLvl\"|paladinLvl",
            "bad syntax|\"maxUsesFormula\": \"max(1, charismaModifier\"|max(1, charismaModifier",
            "no maximum|\"icon\": \"zap\"|maxUsesFormula",
            "a bad reset amount|\"maxUsesFormula\": \"1\", \"resetAmount\": \"half\"|half",
            "a bad recharge check|\"maxUsesFormula\": \"1\", \"resetCheck\": \"5\"|resetCheck",
            "a level table of a class not loaded|\"maxUsesFormula\": \"1\", \"levelTable\": {\"levelOf\": \"Psion\", \"rows\": [{\"level\": 1}]}|Psion",
            "a bad formula in a level table|\"maxUsesFormula\": \"1\", \"levelTable\": {\"levelOf\": \"Bard\", \"rows\": [{\"level\": 5, \"maxUses\": \"bardLevel +\"}]}|level 5 maxUses",
            "a rest no reset knows|\"maxUsesFormula\": \"1\", \"levelTable\": {\"levelOf\": \"Bard\", \"rows\": [{\"level\": 5, \"resetOn\": \"sunset\"}]}|sunset"})
    @DisplayName("A definition the pools couldn't use fails the loader, naming the pool and the problem, before anything is written")
    void invalidDefinitionFails(String problem, String fields, String named) throws Exception {
        ResourcePoolLoader loader = loader();
        LoadContext context = contextWith(fields);

        assertThatThrownBy(() -> loader.load(context))
                .hasMessageContaining("class:test").hasMessageContaining(named);
        verify(definitions, never()).saveAll(anyIterable());
    }
}
