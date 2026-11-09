package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.Glossary;
import com.tabletophelper.reference.GlossaryCategory;
import com.tabletophelper.reference.dto.GlossaryEntry;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Glossary built from data/content/glossary-terms.json and the 5etools files it points to (M19). */
class GlossaryFileTest {

    private static final ObjectMapper MAPPER = JacksonDefaults.mapper();

    private static final Function<String, JsonNode> DATA_FILES = file -> {
        try (InputStream in = new ClassPathResource("data/5etools/" + file).getInputStream()) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    };

    private static Glossary build(String termsJson) {
        return GlossaryFile.read(MAPPER, new ByteArrayInputStream(termsJson.getBytes(StandardCharsets.UTF_8)), DATA_FILES);
    }

    @Nested
    @DisplayName("The bundled glossary")
    class Bundled {

        private static Glossary glossary;
        private static Map<String, GlossaryEntry> byId;

        @BeforeAll
        static void read() {
            glossary = GlossaryFile.read(MAPPER);
            byId = glossary.entries().stream().collect(Collectors.toMap(GlossaryEntry::id, e -> e));
        }

        @Test
        @DisplayName("Every term's pointer finds its rules text, so the file builds, with 104 terms")
        void builds() {
            assertThat(glossary.entries()).hasSize(104);
        }

        @Test
        @DisplayName("All 15 conditions of PHB appendix A are there, each with its rules text from conditionsdiseases.json")
        void hasEveryCondition() {
            List<String> conditions = glossary.entries().stream()
                    .filter(e -> e.category() == GlossaryCategory.CONDITION).map(GlossaryEntry::name).toList();

            assertThat(conditions).containsExactlyInAnyOrder("Blinded", "Charmed", "Deafened", "Exhaustion",
                    "Frightened", "Grappled", "Incapacitated", "Invisible", "Paralyzed", "Petrified", "Poisoned",
                    "Prone", "Restrained", "Stunned", "Unconscious");
            assertThat(byId.get("prone").body()).contains("crawl");
            assertThat(byId.get("prone").source()).isEqualTo("PHB p.292");
        }

        @Test
        @DisplayName("Concentration, the 18 skills, 4 senses and 13 damage types are there")
        void hasTheOtherGroups() {
            assertThat(byId.get("concentration").body()).isNotBlank();
            assertThat(count(GlossaryCategory.SKILL)).isEqualTo(18);
            assertThat(count(GlossaryCategory.DAMAGE_TYPE)).isEqualTo(13);
            assertThat(glossary.entries().stream().filter(e -> e.category() == GlossaryCategory.SENSE)
                    .map(GlossaryEntry::id)).contains("blindsight", "darkvision", "tremorsense", "truesight");
        }

        @Test
        @DisplayName("Every entry has a summary or rules text, and no rules text keeps a 5etools tag")
        void noEntryIsEmptyOrTagged() {
            assertThat(glossary.entries()).allSatisfy(entry -> {
                assertThat(entry.summary() != null || entry.body() != null).as(entry.id()).isTrue();
                if (entry.body() != null) assertThat(entry.body()).as(entry.id()).doesNotContain("{@");
            });
        }

        @Test
        @DisplayName("No name or alias belongs to two entries, and every 'see also' names another entry")
        void spellingsAreUniqueAndLinksResolve() {
            Map<String, String> owners = new HashMap<>();
            for (GlossaryEntry entry : glossary.entries()) {
                Set<String> spellings = new java.util.HashSet<>(entry.aliases());
                spellings.add(entry.name().toLowerCase());
                for (String spelling : spellings) {
                    assertThat(owners.put(spelling, entry.id())).as(spelling).isNull();
                }
                assertThat(entry.seeAlso()).allSatisfy(id -> assertThat(byId).containsKey(id));
            }
        }

        @Test
        @DisplayName("A quick reference section gives its text and page: fire damage is PHB p.196")
        void quickReferenceSection() {
            GlossaryEntry fire = byId.get("fire");

            assertThat(fire.body()).contains("Red dragons breathe fire");
            assertThat(fire.source()).isEqualTo("PHB p.196");
            assertThat(fire.summary()).isNull();
        }

        @Test
        @DisplayName("A skill without a summary is described by its ability: Perception is a Wisdom skill")
        void skillSummary() {
            assertThat(byId.get("perception").summary()).startsWith("A Wisdom skill.");
            assertThat(byId.get("sleight-of-hand").summary()).startsWith("A Dexterity skill.");
        }

        @Test
        @DisplayName("A term the data has no entry for keeps its written summary and book, with no rules text")
        void writtenTerm() {
            GlossaryEntry slot = byId.get("spell-slot");

            assertThat(slot.body()).isNull();
            assertThat(slot.source()).isEqualTo("PHB p.201");
            assertThat(slot.aliases()).contains("spell slots");
        }

        @Test
        @DisplayName("Aliases are lower case, so DC 15 can find the Difficulty Class by 'dc'")
        void aliasesAreLowerCase() {
            assertThat(byId.get("difficulty-class").aliases()).containsExactly("dc");
            assertThat(byId.get("armor-class").aliases()).contains("ac", "armour class");
        }

        private long count(GlossaryCategory category) {
            return glossary.entries().stream().filter(e -> e.category() == category).count();
        }
    }

    @Nested
    @DisplayName("A faulty terms file stops the start, naming the term")
    class Faults {

        @Test
        @DisplayName("A pointer to an entry the data doesn't have")
        void missingEntry() {
            assertThatThrownBy(() -> build("""
                    {"terms": [{"id": "dazed", "name": "Dazed", "category": "CONDITION",
                      "from": {"file": "conditionsdiseases.json", "kind": "condition"}}]}"""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("term dazed")
                    .hasMessageContaining("no condition named Dazed");
        }

        @Test
        @DisplayName("A quick reference path that finds no section")
        void missingSection() {
            assertThatThrownBy(() -> build("""
                    {"terms": [{"id": "flanking", "name": "Flanking", "category": "COMBAT",
                      "from": {"bookref": ["Making an Attack", "Flanking"]}}]}"""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("term flanking");
        }

        @Test
        @DisplayName("An alias another term already has")
        void duplicateAlias() {
            assertThatThrownBy(() -> build("""
                    {"terms": [
                      {"id": "one", "name": "One", "category": "RULE", "book": "PHB", "summary": "A.", "aliases": ["thing"]},
                      {"id": "two", "name": "Two", "category": "RULE", "book": "PHB", "summary": "B.", "aliases": ["Thing"]}
                    ]}"""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("term two")
                    .hasMessageContaining("already a name or alias of one");
        }

        @Test
        @DisplayName("A 'see also' naming no term")
        void unknownSeeAlso() {
            assertThatThrownBy(() -> build("""
                    {"terms": [{"id": "one", "name": "One", "category": "RULE", "book": "PHB", "summary": "A.",
                      "seeAlso": ["nothing"]}]}"""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("names nothing");
        }

        @Test
        @DisplayName("A written term without a summary")
        void noText() {
            assertThatThrownBy(() -> build("""
                    {"terms": [{"id": "one", "name": "One", "category": "RULE", "book": "PHB"}]}"""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("needs a summary or rules text");
        }

        @Test
        @DisplayName("A property the file doesn't have")
        void unknownProperty() {
            assertThatThrownBy(() -> build("""
                    {"terms": [{"id": "one", "name": "One", "category": "RULE", "book": "PHB", "summary": "A.",
                      "definition": "B."}]}"""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be read");
        }
    }
}
