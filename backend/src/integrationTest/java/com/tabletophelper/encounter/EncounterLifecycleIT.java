package com.tabletophelper.encounter;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The encounter lifecycle through the full stack (D166): Preparing → Active ⇄ Paused → Completed. Combat
 * needs an Active encounter, and start, pause, resume and end refuse any other move with a 409. A table
 * of a DM and a 7 HP goblin.
 */
class EncounterLifecycleIT extends IntegrationTest {

    @Autowired
    private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private UUID encounterId;
    private String goblinId;

    @BeforeEach
    void setUpEncounter() {
        dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        Monster goblin = monsterRepository.save(Monster.builder()
                .name("Goblin").type("Humanoid").challengeRating("1/4").source("MM")
                .hitPoints(7).armourClass(15).dexterity(14)
                .build());
        JsonNode state = addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", goblin.getId(), "displayName", "Goblin"));
        goblinId = state.get("participants").get(0).get("id").asString();
    }

    @Test
    @DisplayName("Combat in a Paused encounter gets 409 and changes nothing; once resumed, it applies")
    void combatInPausedEncounterIsRefused() {
        startEncounter(dm.accessToken(), encounterId);
        expect(HttpStatus.OK, command("pause"));

        var damage = combat("damage", Map.of("targetId", goblinId, "amount", 3));
        var condition = combat("condition/add", Map.of("targetId", goblinId, "condition", "prone"));
        var nextTurn = combat("turn/next", null);

        assertThat(List.of(damage, condition, nextTurn))
                .allSatisfy(refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(error(damage)).isEqualTo("The encounter is paused until the DM resumes it");
        JsonNode paused = encounter();
        assertThat(goblin(paused).get("hpCurrent").asInt()).isEqualTo(7);
        assertThat(goblin(paused).get("activeConditions").isNull()).isTrue();
        assertThat(paused.get("roundNumber").asInt()).isEqualTo(1);

        expect(HttpStatus.OK, command("resume"));
        expect(HttpStatus.OK, combat("damage", Map.of("targetId", goblinId, "amount", 3)));
        assertThat(goblin(encounter()).get("hpCurrent").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("A Paused encounter can be completed; then combat gets 409 and Completed is final")
    void completedEncounterIsFinal() {
        startEncounter(dm.accessToken(), encounterId);
        expect(HttpStatus.OK, command("pause"));
        assertThat(expect(HttpStatus.OK, command("end")).get("status").asString()).isEqualTo("COMPLETED");

        var damage = combat("damage", Map.of("targetId", goblinId, "amount", 3));
        var resume = command("resume");
        var endAgain = command("end");

        assertThat(damage.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(damage)).isEqualTo("The encounter has ended");
        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(resume)).isEqualTo("Cannot resume an encounter that is completed");
        assertThat(endAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(endAgain)).isEqualTo("The encounter is already completed");
        assertThat(encounter().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(goblin(encounter()).get("hpCurrent").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("An illegal transition gets 409 with the reason, and the status stays as it was")
    void illegalTransitionsAreRefused() {
        var pause = command("pause");
        var resume = command("resume");
        var end = command("end");

        assertThat(pause.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(pause)).isEqualTo("Cannot pause an encounter that is still being prepared");
        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(resume)).isEqualTo("Cannot resume an encounter that is still being prepared");
        assertThat(end.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(end)).isEqualTo("Cannot complete an encounter that is still being prepared");
        assertThat(encounter().get("status").asString()).isEqualTo("PREPARING");
        assertThat(encounter().get("sessionCode").isNull()).isTrue();

        String sessionCode = startEncounter(dm.accessToken(), encounterId).get("sessionCode").asString();
        var startAgain = command("start");
        var resumeActive = command("resume");

        assertThat(startAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(startAgain)).isEqualTo("The encounter is already active");
        assertThat(resumeActive.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(resumeActive)).isEqualTo("The encounter is already active");
        assertThat(encounter().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(encounter().get("sessionCode").asString()).isEqualTo(sessionCode);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ResponseEntity<String> command(String name) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/" + name, null, dm.accessToken());
    }

    private ResponseEntity<String> combat(String action, Object body) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/" + action, body,
                dm.accessToken());
    }

    private JsonNode encounter() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode goblin(JsonNode encounter) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("id").asString().equals(goblinId)) {
                return participant;
            }
        }
        throw new AssertionError("The goblin is not in the encounter");
    }

    private String error(ResponseEntity<String> response) {
        return json(response).get("error").asString();
    }
}
