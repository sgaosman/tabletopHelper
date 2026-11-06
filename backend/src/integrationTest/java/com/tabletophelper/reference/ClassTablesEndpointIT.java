package com.tabletophelper.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.dto.ClassTablesResponse;
import com.tabletophelper.rules.character.ClassTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /api/reference/class-tables serves the class tables the server's rules use (D167, D169). */
class ClassTablesEndpointIT extends IntegrationTest {

    @Autowired private ClassTables classTables;

    @Test
    @DisplayName("The endpoint serves the tables read from class-tables.json, which the browser may keep for an hour")
    void servesTheTables() {
        RegisteredUser user = register("tables");

        ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/reference/class-tables", null,
                user.accessToken());
        JsonNode body = expect(HttpStatus.OK, response);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=3600, private");
        assertThat(body).isEqualTo(objectMapper.valueToTree(ClassTablesResponse.of(classTables)));
        assertThat(body.path("spellSlots").path("full").path(20).toString()).isEqualTo("[4,3,3,3,3,2,2,1,1]");
        assertThat(body.path("spellSlots").path("pact").path(5).toString()).isEqualTo("[0,0,2]");
        assertThat(body.path("classes").path("Wizard").path("spellcasting").path("cantripsKnown").path(1).asInt())
                .isEqualTo(3);
        assertThat(body.path("classes").path("Wizard").path("spellbook").path("firstLevel").asInt()).isEqualTo(6);
        assertThat(body.path("classes").path("Bard").path("spellcasting").path("spellsKnown").path(10).asInt())
                .isEqualTo(14);
        assertThat(body.path("classes").path("Rogue").path("expertise").path("6").asInt()).isEqualTo(2);
        assertThat(body.path("subclasses").path("Eldritch Knight").path("spellcasting").path("casterType").asText())
                .isEqualTo("third");
        assertThat(body.path("proficiencyBonus").path(17).asInt()).isEqualTo(6);
        assertThat(body.path("classes")).hasSize(13);
    }

    @Test
    @DisplayName("Without a token the class tables are a 401, as every reference endpoint is")
    void needsAToken() {
        assertThat(exchange(HttpMethod.GET, "/api/reference/class-tables", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
