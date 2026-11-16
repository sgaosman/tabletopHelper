package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.RunningEncounterSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EncounterControllerTest {

    @Test
    @DisplayName("GET /running answers 200 with the caller's running encounters, taking the user from the token (D236)")
    void getRunningEncounters_returnsTheCallersSummaries() {
        var service = mock(EncounterService.class);
        var controller = new EncounterController(service);
        var userId = UUID.randomUUID();
        var summary = new RunningEncounterSummary(UUID.randomUUID(), "Goblin Ambush", EncounterStatus.PAUSED, 2,
                UUID.randomUUID(), "Lost Mine", 3, Instant.now());
        when(service.getRunningEncounters(userId)).thenReturn(List.of(summary));

        var response = controller.getRunningEncounters(new UsernamePasswordAuthenticationToken(userId, null, List.of()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(summary), response.getBody());
    }
}
