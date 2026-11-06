package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.tabletophelper.rules.character.FeatPrerequisites.PrerequisiteAlternative;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * A feat's prerequisite, the {@code feats.prerequisite} column (D159, D168): a list of alternatives, any one of which
 * suffices, each naming every requirement it needs (rules/character/FeatPrerequisites). FeatLoader writes it from the
 * 5etools data. The column's JSON is kept whole beside the records, and it is what is stored and what the API returns.
 *
 * @param alternatives the ways to meet it; empty for a feat without a prerequisite
 * @param json         the column's JSON as stored
 * @param unreadable   why the JSON doesn't fit the records, or null; the records are then empty, and
 *                     {@link #readable} stops a command that needs them with a 409 (D155)
 */
public record FeatPrerequisite(List<PrerequisiteAlternative> alternatives, JsonNode json, String unreadable) {

    private static final Logger log = LoggerFactory.getLogger(FeatPrerequisite.class);

    private static final ObjectMapper READER = JacksonDefaults.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final TypeReference<List<PrerequisiteAlternative>> ALTERNATIVES = new TypeReference<>() {
    };

    public FeatPrerequisite {
        alternatives = alternatives == null ? List.of() : alternatives.stream().filter(Objects::nonNull).toList();
    }

    /** The prerequisite of this JSON, which it keeps as it is: an array of alternatives. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FeatPrerequisite of(JsonNode json) {
        if (json == null || !json.isArray()) {
            return new FeatPrerequisite(null, json, json == null || json.isNull() ? null
                    : "a prerequisite is a list of alternatives");
        }
        try {
            return new FeatPrerequisite(READER.convertValue(json, ALTERNATIVES), json, null);
        } catch (IllegalArgumentException | JacksonException e) {
            return new FeatPrerequisite(null, json, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** The prerequisite of these alternatives, its JSON written as the records serialize. */
    public static FeatPrerequisite of(List<PrerequisiteAlternative> alternatives, ObjectMapper writer) {
        return of(writer.valueToTree(alternatives));
    }

    /** The JSON as stored and as the API returns it. */
    @JsonValue
    @Override
    public JsonNode json() {
        return json;
    }

    /** The prerequisite of the column's text, for data the caller knows to be JSON, such as a test's; null for null. */
    public static FeatPrerequisite parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return of(READER.readTree(json));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not JSON: " + json, e);
        }
    }

    /**
     * The alternatives of a feat's prerequisite, none for a feat without one. One that doesn't fit the records stops
     * the command with a 409 naming the feat (D155), and the cause is logged.
     */
    public static List<PrerequisiteAlternative> readable(Feat feat) {
        FeatPrerequisite prerequisite = feat.getPrerequisite();
        if (prerequisite == null) return List.of();
        if (prerequisite.unreadable() != null) {
            log.error("The prerequisite of the feat {} doesn't fit the records: {}", feat.getName(),
                    prerequisite.unreadable());
            throw new ConflictException("The prerequisite of the feat " + feat.getName() + " can't be read");
        }
        return prerequisite.alternatives();
    }
}
