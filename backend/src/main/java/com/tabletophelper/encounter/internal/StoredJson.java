package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.tabletophelper.shared.ConflictException;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the text of a jsonb column that is still a string, such as a spell's effect template or a monster's
 * saving throws, as a tree that callers walk without further exceptions. PostgreSQL only stores valid JSON in
 * a jsonb column, so text that doesn't parse means a damaged row: the command stops with a 409 that names the
 * data, and the cause is logged (D155).
 */
@Slf4j
public final class StoredJson {

    private StoredJson() {
    }

    /**
     * The JSON as a tree, or a missing node for null or blank text.
     *
     * @param what names the data for the message, such as "Fireball's effect template"
     */
    public static JsonNode read(ObjectMapper objectMapper, String json, String what) {
        if (json == null || json.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("{} is not valid JSON", what, e);
            throw new ConflictException(what + " can't be read");
        }
    }
}
