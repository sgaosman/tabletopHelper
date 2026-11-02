package com.tabletophelper.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec the frontend generates its API types from (D171): the committed copy must match the live
 * one, and a property is nullable unless its DTO marks it required.
 */
class OpenApiSpecIT extends IntegrationTest {

    // Gradle passes the absolute path; the fallback resolves from backend/, where tests run
    private static final Path COMMITTED_SPEC = Path.of(System.getProperty(
            "openapi.committed-spec", "../frontend/src/api/generated/openapi.json"));

    private static final int MAX_DIFFERENCES = 20;

    private JsonNode liveSpec() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/v3/api-docs", null, null));
    }

    private JsonNode campaignResponseSchema() {
        return liveSpec().path("components").path("schemas").path("CampaignResponse");
    }

    @Test
    @DisplayName("The committed frontend spec matches the live one; after an API change, run npm run api:spec")
    void committedSpecMatchesLiveSpec() throws IOException {
        assertThat(COMMITTED_SPEC).as("committed spec").exists();
        JsonNode committed = objectMapper.readTree(Files.readString(COMMITTED_SPEC));

        List<String> differences = differences(liveSpec(), committed, "", new ArrayList<>());

        assertThat(differences)
                .as("%s is out of date: start the dev backend, then run npm run api:spec in frontend/ and "
                        + "commit src/api/generated", COMMITTED_SPEC.normalize())
                .isEmpty();
    }

    @Test
    @DisplayName("The spec is OpenAPI 3.0 with a relative server URL, so it is the same wherever it is served")
    void specIsOpenApi30WithRelativeServer() {
        JsonNode spec = liveSpec();

        assertThat(spec.path("openapi").asText()).startsWith("3.0.");
        assertThat(spec.path("servers").path(0).path("url").asText()).isEqualTo("/");
    }

    @Test
    @DisplayName("A field marked required is listed in required and not nullable")
    void requiredFieldIsNotNullable() {
        JsonNode schema = campaignResponseSchema();

        assertThat(schema.path("required")).extracting(JsonNode::asText).contains("inviteCode");
        assertThat(schema.path("properties").path("inviteCode").has("nullable")).isFalse();
    }

    @Test
    @DisplayName("A field not marked required is nullable, because Jackson writes nulls")
    void optionalFieldIsNullable() {
        JsonNode schema = campaignResponseSchema();

        assertThat(schema.path("required")).extracting(JsonNode::asText).doesNotContain("description");
        assertThat(schema.path("properties").path("description").path("nullable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("No schema lists a nullable property as required")
    void noRequiredPropertyIsNullable() {
        List<String> requiredAndNullable = new ArrayList<>();
        liveSpec().path("components").path("schemas").properties().forEach(schema -> {
            JsonNode properties = schema.getValue().path("properties");
            schema.getValue().path("required").forEach(name -> {
                if (properties.path(name.asText()).path("nullable").asBoolean()) {
                    requiredAndNullable.add(schema.getKey() + "." + name.asText());
                }
            });
        });

        assertThat(requiredAndNullable).isEmpty();
    }

    /**
     * JSON Pointers to where the committed spec differs from the live one, at most {@link #MAX_DIFFERENCES}.
     * Numbers compare by value, because the frontend script writes 1.0 as 1.
     */
    private static List<String> differences(JsonNode live, JsonNode committed, String path, List<String> found) {
        if (found.size() >= MAX_DIFFERENCES) {
            return found;
        }
        if (live.isNumber() && committed.isNumber()) {
            if (live.decimalValue().compareTo(committed.decimalValue()) != 0) {
                found.add(path);
            }
        } else if (live.isObject() && committed.isObject()) {
            Set<String> names = new TreeSet<>();
            live.fieldNames().forEachRemaining(names::add);
            committed.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String child = path + "/" + name.replace("~", "~0").replace("/", "~1");
                if (!committed.has(name)) {
                    found.add(child + " (missing from the committed spec)");
                } else if (!live.has(name)) {
                    found.add(child + " (no longer in the live spec)");
                } else {
                    differences(live.get(name), committed.get(name), child, found);
                }
            }
        } else if (live.isArray() && committed.isArray() && live.size() == committed.size()) {
            for (int i = 0; i < live.size(); i++) {
                differences(live.get(i), committed.get(i), path + "/" + i, found);
            }
        } else if (!live.equals(committed)) {
            found.add(path);
        }
        return found;
    }
}
