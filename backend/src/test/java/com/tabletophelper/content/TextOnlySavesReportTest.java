package com.tabletophelper.content;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The text-only saves report (D165): every monster action whose stat-block text calls for a saving throw, "DC N
 * Ability saving throw", while its template in monster-action-definitions.json records no save at all, so combat
 * applies its effects without one. It only reports; none of them is applied, and the data is corrected by hand from
 * the list. Each action's text is its 5etools bestiary entry, found by the monster's name and source and the action's
 * name, ignoring case, a parenthetical such as "(Recharge 5-6)" and a plural s; failing that, its template's own
 * description. An action with a DM note leaves its save to the DM on purpose, as the swallowing bites do, so it isn't
 * listed. Lair actions are read from their templates' descriptions, as the bestiary keeps them elsewhere.
 * <p>
 * Every run of the unit tests writes the report to {@code build/reports/text-only-saves.txt}, one action per line, and
 * prints the count; {@code ./gradlew test --tests '*TextOnlySavesReportTest'} writes it alone.
 */
class TextOnlySavesReportTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final String BESTIARY = "classpath:data/5etools/bestiary/bestiary-*.json";
    private static final Path REPORT = Path.of("build", "reports", "text-only-saves.txt");

    // Each template list and the bestiary key its stat-block text is under; lair actions have none
    private static final Map<String, String> LISTS = new LinkedHashMap<>();

    static {
        LISTS.put("actions", "action");
        LISTS.put("bonusActions", "bonus");
        LISTS.put("reactions", "reaction");
        LISTS.put("legendaryActions", "legendary");
        LISTS.put("mythicActions", "mythic");
        LISTS.put("traits", "trait");
        LISTS.put("lairActions", null);
    }

    private static final Pattern SAVE = Pattern.compile(
            "DC\\s*(\\d+)\\s*(Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma)\\s+saving throw",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DC_TAG = Pattern.compile("\\{@dc (\\d+)}");
    private static final Pattern TAG = Pattern.compile("\\{@\\w+ ([^}|]*)[^}]*}");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\(.*?\\)");

    private final ObjectMapper objectMapper = JacksonDefaults.mapper();

    /** One action whose text has a save its template doesn't record. */
    record TextOnlySave(String monster, String source, String list, String action, int dc, String ability,
                        String textFrom) {

        String line() {
            return monster + " (" + source + ") | " + list + " | " + action + " | DC " + dc + " " + ability
                    + " | text from " + textFrom;
        }
    }

    @Test
    @DisplayName("Writes the actions whose stat-block text has a save their templates don't record")
    void writesTheReport() throws IOException {
        Map<String, JsonNode> bestiary = bestiary();
        List<TextOnlySave> saves = new ArrayList<>();
        int actions = 0;
        int withoutText = 0;
        for (JsonNode definition : read(new ClassPathResource(DEFINITIONS))) {
            String monster = definition.path("monsterName").asString();
            String source = definition.path("source").asString();
            JsonNode statBlock = bestiary.get(monster.toLowerCase() + "|" + source.toLowerCase());
            for (var list : LISTS.entrySet()) {
                Map<String, JsonNode> entries = entries(statBlock, list.getValue());
                for (JsonNode action : definition.path(list.getKey())) {
                    actions++;
                    JsonNode entry = entries.get(key(action.path("name").asString()));
                    String text;
                    String textFrom;
                    if (entry != null) {
                        text = plain(flatten(entry.path("entries")));
                        textFrom = "bestiary";
                    } else if (action.path("description").isString()) {
                        text = plain(action.path("description").asString());
                        textFrom = "template";
                    } else {
                        withoutText++;
                        continue;
                    }
                    Matcher save = SAVE.matcher(text);
                    if (save.find() && !recordsASave(action) && !action.path("dmNote").isString()) {
                        saves.add(new TextOnlySave(monster, source, list.getKey(), action.path("name").asString(),
                                Integer.parseInt(save.group(1)), capitalised(save.group(2)), textFrom));
                    }
                }
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("# Monster actions whose stat-block text calls for a saving throw that their templates don't record");
        lines.add("# " + saves.size() + " of " + actions + " actions; " + withoutText
                + " actions had no text to read. Reported only: none is applied (D165).");
        lines.add("# monster (source) | list | action | the first save in its text | where the text came from");
        saves.forEach(save -> lines.add(save.line()));
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, lines, StandardCharsets.UTF_8);
        System.out.println("Text-only saves: " + saves.size() + " of " + actions + " actions, written to "
                + REPORT.toAbsolutePath());

        assertFalse(saves.isEmpty());
        assertTrue(saves.stream().noneMatch(save -> save.action().equals("Bite")
                && List.of("Kraken", "Froghemoth", "Froghemoth Elder", "Slarkrethel").contains(save.monster())));
        assertEquals(saves.size() + 3, Files.readAllLines(REPORT, StandardCharsets.UTF_8).size());
    }

    // Whether the template records a save anywhere: on the action, its effects, its rays or its options
    private static boolean recordsASave(JsonNode action) {
        if (hasSave(action) || action.has("secondarySaveDC") || action.has("additionalSaveDC")
                || action.has("onHitSave")) {
            return true;
        }
        for (String part : List.of("effects", "rays", "options", "subOptions")) {
            for (JsonNode node : action.path(part)) {
                if (hasSave(node)) {
                    return true;
                }
            }
        }
        return false;
    }

    // A swallowing bite records the swallower's own save to keep what it swallowed down on its gullet (D204)
    private static boolean hasSave(JsonNode node) {
        return node.path("saveDC").isNumber() || node.path("saveAbility").isString()
                || node.path("swallow").path("regurgitateDC").isNumber();
    }

    // Every bestiary monster by lower-case "name|source", the first of any that share one
    private Map<String, JsonNode> bestiary() throws IOException {
        Map<String, JsonNode> monsters = new HashMap<>();
        for (Resource file : new PathMatchingResourcePatternResolver().getResources(BESTIARY)) {
            for (JsonNode monster : read(file).path("monster")) {
                monsters.putIfAbsent(monster.path("name").asString().toLowerCase() + "|"
                        + monster.path("source").asString().toLowerCase(), monster);
            }
        }
        return monsters;
    }

    // A stat block's entries under the key, by their name's key; none for a monster or key without them
    private static Map<String, JsonNode> entries(JsonNode statBlock, String bestiaryKey) {
        Map<String, JsonNode> entries = new HashMap<>();
        if (statBlock == null || bestiaryKey == null) {
            return entries;
        }
        for (JsonNode entry : statBlock.path(bestiaryKey)) {
            if (entry.path("name").isString()) {
                entries.putIfAbsent(key(entry.path("name").asString()), entry);
            }
        }
        return entries;
    }

    // An action's name for matching: plain text, lower case, without a parenthetical or a plural s
    private static String key(String name) {
        String key = PARENTHETICAL.matcher(plain(name).toLowerCase()).replaceAll("").trim();
        return key.endsWith("s") ? key.substring(0, key.length() - 1) : key;
    }

    // 5etools text with its tags as plain words, "{@dc 13}" as "DC 13"
    private static String plain(String text) {
        return TAG.matcher(DC_TAG.matcher(text).replaceAll("DC $1")).replaceAll("$1");
    }

    // The text of an entry and the entries, items and entry nested in it
    private static String flatten(JsonNode node) {
        if (node.isString()) {
            return node.asString();
        }
        List<String> parts = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(child -> parts.add(flatten(child)));
        } else if (node.isObject()) {
            for (String key : List.of("name", "entries", "items", "entry")) {
                Optional.ofNullable(node.get(key)).ifPresent(child -> parts.add(flatten(child)));
            }
        }
        return String.join(" ", parts);
    }

    private static String capitalised(String ability) {
        return ability.substring(0, 1).toUpperCase() + ability.substring(1).toLowerCase();
    }

    private JsonNode read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readTree(in);
        }
    }
}
