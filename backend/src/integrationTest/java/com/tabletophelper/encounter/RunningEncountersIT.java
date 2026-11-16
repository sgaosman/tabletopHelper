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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/encounters/running, the DM dashboard's list of encounters under way (D236): the Active and Paused
 * encounters in the campaigns the caller runs, Active first, never another DM's and never a Preparing or Completed one.
 */
class RunningEncountersIT extends IntegrationTest {

    private static final String RUNNING = "/api/encounters/running";

    @Autowired
    private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser otherDm;
    private RegisteredUser player;
    private UUID campaignId;
    private Monster goblin;

    @BeforeEach
    void setUpTables() {
        dm = register("dungeonmaster");
        otherDm = register("otherdm");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        goblin = monsterRepository.save(Monster.builder()
                .name("Goblin").type("Humanoid").challengeRating("1/4").source("MM")
                .hitPoints(7).armourClass(15).dexterity(14)
                .build());
    }

    private UUID encounter(String token, UUID campaign, String name) {
        JsonNode created = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/encounters",
                Map.of("campaignId", campaign, "name", name), token));
        UUID id = UUID.fromString(created.get("id").asString());
        addParticipant(token, id, Map.of("participantType", "MONSTER", "monsterId", goblin.getId(),
                "displayName", "Goblin"));
        return id;
    }

    private void command(String token, UUID encounterId, String command) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/" + command, null, token));
    }

    private JsonNode running(String token) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, RUNNING, null, token));
    }

    private static List<String> names(JsonNode list) {
        return list.valueStream().map(e -> e.get("name").asString()).toList();
    }

    @Test
    @DisplayName("The DM sees their Active and Paused encounters, Active first though the Paused one changed later, "
            + "and neither the Preparing nor the Completed one")
    void dmSeesOwnActiveAndPausedEncounters() {
        encounter(dm.accessToken(), campaignId, "Still Preparing");
        UUID active = encounter(dm.accessToken(), campaignId, "Goblin Ambush");
        UUID paused = encounter(dm.accessToken(), campaignId, "Bridge Toll");
        UUID completed = encounter(dm.accessToken(), campaignId, "Old Fight");
        startEncounter(dm.accessToken(), active);
        startEncounter(dm.accessToken(), completed);
        command(dm.accessToken(), completed, "end");
        startEncounter(dm.accessToken(), paused);
        command(dm.accessToken(), paused, "pause");

        JsonNode list = running(dm.accessToken());

        assertThat(names(list)).containsExactly("Goblin Ambush", "Bridge Toll");
        JsonNode first = list.get(0);
        assertThat(first.get("id").asString()).isEqualTo(active.toString());
        assertThat(first.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(first.get("roundNumber").asInt()).isEqualTo(1);
        assertThat(first.get("campaignId").asString()).isEqualTo(campaignId.toString());
        assertThat(first.get("campaignName").asString()).isEqualTo("Lost Mine");
        assertThat(first.get("participantCount").asLong()).isEqualTo(1);
        assertThat(first.get("updatedAt").isNull()).isFalse();
        assertThat(first.has("participants")).isFalse();
        assertThat(list.get(1).get("id").asString()).isEqualTo(paused.toString());
        assertThat(list.get(1).get("status").asString()).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("Another DM's running encounter is left out, and each DM sees only their own")
    void otherDmsEncountersAreLeftOut() {
        UUID mine = encounter(dm.accessToken(), campaignId, "Goblin Ambush");
        startEncounter(dm.accessToken(), mine);
        UUID otherCampaign = UUID.fromString(createCampaign(otherDm.accessToken()).get("id").asString());
        UUID theirs = encounter(otherDm.accessToken(), otherCampaign, "Dragon Lair");
        startEncounter(otherDm.accessToken(), theirs);

        assertThat(names(running(dm.accessToken()))).containsExactly("Goblin Ambush");
        assertThat(names(running(otherDm.accessToken()))).containsExactly("Dragon Lair");
    }

    @Test
    @DisplayName("A player of the campaign gets an empty list, though its encounter is Active")
    void playerGetsEmptyList() {
        UUID active = encounter(dm.accessToken(), campaignId, "Goblin Ambush");
        startEncounter(dm.accessToken(), active);

        assertThat(running(player.accessToken())).isEmpty();
    }

    @Test
    @DisplayName("Without a token the list is a 401")
    void anonymousIsUnauthorised() {
        assertThat(exchange(HttpMethod.GET, RUNNING, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
