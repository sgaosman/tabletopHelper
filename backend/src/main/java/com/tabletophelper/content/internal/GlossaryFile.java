package com.tabletophelper.content.internal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.Glossary;
import com.tabletophelper.reference.GlossaryCategory;
import com.tabletophelper.reference.dto.GlossaryEntry;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the Glossary (M19) from {@code data/content/glossary-terms.json}. Each term there names its category, its
 * aliases, a short summary written for new players and where its rules text is: an entry of a 5etools file
 * ({@code file}, {@code kind} and {@code name}), or a section of the quick reference ({@code bookref}, a path of
 * section names). A term with neither gives its book and page and needs a summary.
 * <p>
 * The file is read strictly, and any of these stops the start, naming the term: a pointer that finds nothing, an ID,
 * name or alias used twice, a "see also" naming no term, or a term with no summary and no rules text.
 */
public final class GlossaryFile {

    /** The file, on the classpath. */
    public static final String PATH = "data/content/glossary-terms.json";

    private static final String DATA_DIRECTORY = "data/5etools/";
    private static final String QUICK_REFERENCE = "generated/bookref-quick.json";
    private static final Map<String, String> ABILITIES = Map.of(
            "str", "Strength", "dex", "Dexterity", "con", "Constitution",
            "int", "Intelligence", "wis", "Wisdom", "cha", "Charisma");

    record TermsFile(List<Term> terms) {
    }

    record Term(String id, String name, GlossaryCategory category, List<String> aliases, From from, String book,
                Integer page, String summary, List<String> seeAlso) {
    }

    /** Where a term's rules text is: {@code file}, {@code kind} and {@code name}, or {@code bookref}. */
    record From(String file, String kind, String name, List<String> bookref) {
    }

    private GlossaryFile() {
    }

    /** Builds the Glossary from the bundled files. */
    public static Glossary read(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return read(objectMapper, in, file -> readData(objectMapper, file));
        } catch (IOException e) {
            throw new IllegalStateException(PATH + " could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the Glossary from a stream holding the terms file's JSON, reading the data files it points to through
     * {@code dataFiles}, by their path under {@code data/5etools}.
     */
    public static Glossary read(ObjectMapper objectMapper, InputStream terms, Function<String, JsonNode> dataFiles) {
        TermsFile file;
        try {
            file = objectMapper.readerFor(TermsFile.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(terms);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException(PATH + " could not be read: " + e.getMessage(), e);
        }
        if (file.terms() == null || file.terms().isEmpty()) {
            throw new IllegalStateException(PATH + " has no terms");
        }

        Map<String, JsonNode> files = new HashMap<>();
        Function<String, JsonNode> cachedFiles = name -> files.computeIfAbsent(name, dataFiles);
        List<GlossaryEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, String> spellings = new HashMap<>();
        for (Term term : file.terms()) {
            GlossaryEntry entry = entry(term, cachedFiles);
            if (!ids.add(entry.id())) {
                throw fault(entry.id(), "the ID is used twice");
            }
            for (String spelling : spellings(entry)) {
                String owner = spellings.putIfAbsent(spelling, entry.id());
                if (owner != null && !owner.equals(entry.id())) {
                    throw fault(entry.id(), "\"" + spelling + "\" is already a name or alias of " + owner);
                }
            }
            entries.add(entry);
        }
        for (GlossaryEntry entry : entries) {
            for (String other : entry.seeAlso()) {
                if (!ids.contains(other) || other.equals(entry.id())) {
                    throw fault(entry.id(), "\"see also\" names " + other + ", which isn't another term");
                }
            }
        }
        return new Glossary(entries);
    }

    private static GlossaryEntry entry(Term term, Function<String, JsonNode> files) {
        String id = term.id();
        if (id == null || !id.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw fault(String.valueOf(id), "the ID must be lower-case words joined by hyphens");
        }
        if (isBlank(term.name()) || term.category() == null) {
            throw fault(id, "a term needs a name and a category");
        }
        String summary = isBlank(term.summary()) ? null : term.summary().strip();
        String body = null;
        String source;
        From from = term.from();
        if (from == null) {
            if (isBlank(term.book())) {
                throw fault(id, "a term without \"from\" needs a book");
            }
            source = source(term.book(), term.page());
        } else if (from.bookref() != null) {
            if (from.file() != null || from.kind() != null || from.name() != null) {
                throw fault(id, "\"from\" takes either bookref or file, kind and name");
            }
            Located section = quickReferenceSection(files.apply(QUICK_REFERENCE), from.bookref())
                    .orElseThrow(() -> fault(id, "the quick reference has no section " + from.bookref()));
            body = flatten(section.node().path("entries"));
            source = source(section.source(), section.page());
        } else {
            if (isBlank(from.file()) || isBlank(from.kind())) {
                throw fault(id, "\"from\" needs a file and a kind, or a bookref");
            }
            String wanted = isBlank(from.name()) ? term.name() : from.name();
            JsonNode found = dataEntry(files.apply(from.file()), from.kind(), wanted)
                    .orElseThrow(() -> fault(id, from.file() + " has no " + from.kind() + " named " + wanted));
            body = flatten(found.path("entries"));
            source = source(found.path("source").asString(""), found.path("page").isNumber()
                    ? found.path("page").asInt() : null);
            if (summary == null && term.category() == GlossaryCategory.SKILL) {
                summary = skillSummary(id, found);
            }
        }
        if (summary == null && body == null) {
            throw fault(id, "a term needs a summary or rules text");
        }
        if (body != null && body.contains("{@")) {
            throw fault(id, "the rules text still has a 5etools tag");
        }
        List<String> aliases = term.aliases() == null ? List.of()
                : term.aliases().stream().map(GlossaryFile::spelling).toList();
        List<String> seeAlso = term.seeAlso() == null ? List.of() : List.copyOf(term.seeAlso());
        return new GlossaryEntry(id, term.name().strip(), term.category(), aliases, summary, body, source, seeAlso);
    }

    /** A skill's summary, from the ability the data gives it. */
    private static String skillSummary(String id, JsonNode skill) {
        String ability = ABILITIES.get(skill.path("ability").asString(""));
        if (ability == null) {
            throw fault(id, "the skill has no ability");
        }
        return "A " + ability + " skill. A check with it adds your " + ability
                + " modifier, plus your proficiency bonus if you're proficient in it.";
    }

    /** The entry of a kind, such as {@code condition}, with that name; the PHB's when several books have one. */
    private static Optional<JsonNode> dataEntry(JsonNode file, String kind, String name) {
        JsonNode found = null;
        for (JsonNode candidate : file.path(kind)) {
            if (!name.equalsIgnoreCase(candidate.path("name").asString(""))) continue;
            if (found == null || "PHB".equals(candidate.path("source").asString(""))) {
                found = candidate;
            }
        }
        return Optional.ofNullable(found);
    }

    private record Located(JsonNode node, String source, Integer page) {
    }

    /**
     * The quick reference section at a path of names: the first name is a chapter's section, and each name after it
     * a named entry inside the one before, through any unnamed entries between. The book and page are the nearest
     * the path gives.
     */
    private static Optional<Located> quickReferenceSection(JsonNode quickReference, List<String> path) {
        if (path.isEmpty()) return Optional.empty();
        for (JsonNode chapter : quickReference.path("data").path("bookref-quick")) {
            for (JsonNode section : chapter.path("entries")) {
                if (!path.getFirst().equals(section.path("name").asString(null))) continue;
                Located at = new Located(section, section.path("source").asString("PHB"), page(section, null));
                for (String name : path.subList(1, path.size())) {
                    JsonNode child = namedChild(at.node(), name);
                    if (child == null) {
                        at = null;
                        break;
                    }
                    at = new Located(child, child.path("source").asString(at.source()), page(child, at.page()));
                }
                if (at != null) return Optional.of(at);
            }
        }
        return Optional.empty();
    }

    private static Integer page(JsonNode node, Integer otherwise) {
        return node.path("page").isNumber() ? Integer.valueOf(node.path("page").asInt()) : otherwise;
    }

    private static JsonNode namedChild(JsonNode node, String name) {
        for (JsonNode child : node.path("entries")) {
            if (!child.isObject()) continue;
            if (child.has("name")) {
                if (name.equals(child.path("name").asString(""))) return child;
            } else {
                JsonNode found = namedChild(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 5etools entries as plain text, one paragraph per line: a named entry starts its paragraph with its name, list
     * items start with "- ", and table rows have their cells joined by " | ". Tags are removed by
     * {@link FiveEToolsMarkupParser}.
     */
    static String flatten(JsonNode entries) {
        List<String> lines = new ArrayList<>();
        flattenInto(entries, lines);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static void flattenInto(JsonNode entries, List<String> lines) {
        for (JsonNode entry : entries) {
            if (entry.isString()) {
                addLine(lines, FiveEToolsMarkupParser.parse(entry.asString()));
                continue;
            }
            if (!entry.isObject()) continue;
            String name = entry.path("name").asString("");
            switch (entry.path("type").asString("")) {
                case "list" -> {
                    for (JsonNode item : entry.path("items")) {
                        List<String> inner = new ArrayList<>();
                        if (item.isString()) {
                            addLine(inner, FiveEToolsMarkupParser.parse(item.asString()));
                        } else if (item.has("entry")) {
                            addLine(inner, FiveEToolsMarkupParser.parse(item.path("entry").asString("")));
                        } else {
                            flattenInto(item.path("entries"), inner);
                        }
                        String itemName = item.path("name").asString("");
                        if (!inner.isEmpty()) {
                            String first = itemName.isEmpty() ? inner.getFirst() : itemName + ". " + inner.getFirst();
                            lines.add("- " + first);
                            lines.addAll(inner.subList(1, inner.size()));
                        }
                    }
                }
                case "table" -> {
                    for (JsonNode row : entry.path("rows")) {
                        List<String> cells = new ArrayList<>();
                        for (JsonNode cell : row) {
                            cells.add(FiveEToolsMarkupParser.parse(cell.isString() ? cell.asString() : cell.toString()));
                        }
                        addLine(lines, String.join(" | ", cells));
                    }
                }
                default -> {
                    List<String> inner = new ArrayList<>();
                    if (entry.has("entry")) {
                        addLine(inner, FiveEToolsMarkupParser.parse(entry.path("entry").asString("")));
                    }
                    flattenInto(entry.path("entries"), inner);
                    if (!name.isEmpty()) {
                        if (inner.isEmpty()) inner.add(name + ".");
                        else inner.set(0, name + ". " + inner.getFirst());
                    }
                    lines.addAll(inner);
                }
            }
        }
    }

    private static void addLine(List<String> lines, String text) {
        if (text != null && !text.isBlank()) lines.add(text.strip());
    }

    /** The term's name and aliases in lower case, as the browser looks them up. */
    private static Set<String> spellings(GlossaryEntry entry) {
        Set<String> spellings = new LinkedHashSet<>();
        spellings.add(spelling(entry.name()));
        spellings.addAll(entry.aliases());
        return spellings;
    }

    private static String spelling(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String source(String book, Integer page) {
        String name = isBlank(book) ? "PHB" : book.strip();
        return page == null ? name : name + " p." + page;
    }

    private static JsonNode readData(ObjectMapper objectMapper, String file) {
        String path = DATA_DIRECTORY + file;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException(PATH + " points to " + path + ", which could not be read: "
                    + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static IllegalStateException fault(String id, String problem) {
        return new IllegalStateException(PATH + ", term " + id + ": " + problem);
    }
}
