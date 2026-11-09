package com.tabletophelper.reference;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /api/reference/glossary serves the Glossary built at startup (M19). */
class GlossaryEndpointIT extends IntegrationTest {

    @Autowired private Glossary glossary;

    @Test
    @DisplayName("The endpoint serves every glossary entry, which the browser may keep for an hour")
    void servesTheGlossary() {
        RegisteredUser user = register("glossary");

        ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/reference/glossary", null,
                user.accessToken());
        JsonNode body = expect(HttpStatus.OK, response);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=3600, private");
        assertThat(body).isEqualTo(objectMapper.valueToTree(glossary.entries()));
        JsonNode prone = null;
        for (JsonNode entry : body) {
            if ("prone".equals(entry.path("id").asString())) prone = entry;
        }
        assertThat(prone).isNotNull();
        assertThat(prone.path("name").asString()).isEqualTo("Prone");
        assertThat(prone.path("category").asString()).isEqualTo("CONDITION");
        assertThat(prone.path("summary").asString()).isNotBlank();
        assertThat(prone.path("body").asString()).contains("crawl");
        assertThat(prone.path("source").asString()).isEqualTo("PHB p.292");
        assertThat(prone.path("aliases").isArray()).isTrue();
        assertThat(prone.path("seeAlso").isArray()).isTrue();
    }

    @Test
    @DisplayName("Without a token the glossary is a 401, as every reference endpoint is")
    void needsAToken() {
        assertThat(exchange(HttpMethod.GET, "/api/reference/glossary", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
