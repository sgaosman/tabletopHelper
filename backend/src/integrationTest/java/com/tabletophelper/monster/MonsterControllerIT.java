package com.tabletophelper.monster;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The bestiary is the DM's: only a user who is the DM of a campaign reads it (D235). */
class MonsterControllerIT extends IntegrationTest {

    @Autowired
    private MonsterRepository monsterRepository;

    private List<String> paths;

    @BeforeEach
    void saveAGoblin() {
        Monster goblin = monsterRepository.save(Monster.builder()
                .name("Goblin").type("Humanoid").challengeRating("1/4").source("MM")
                .hitPoints(7).armourClass(15).dexterity(14)
                .build());
        paths = List.of("/api/monsters", "/api/monsters/search?name=Gob", "/api/monsters/" + goblin.getId(),
                "/api/monsters/filters/sources", "/api/monsters/filters/types",
                "/api/monsters/filters/challenge-ratings");
    }

    @Test
    @DisplayName("A campaign's DM reads every bestiary endpoint")
    void dmReadsTheBestiary() {
        String dm = register("dungeonmaster").accessToken();
        createCampaign(dm);

        paths.forEach(path -> expect(HttpStatus.OK, exchange(HttpMethod.GET, path, null, dm)));
        JsonNode goblin = expect(HttpStatus.OK, exchange(HttpMethod.GET, paths.get(2), null, dm));
        assertThat(goblin.get("armourClass").asInt()).isEqualTo(15);
    }

    @Test
    @DisplayName("A player in a campaign, and a user in none, get a 403 from every bestiary endpoint")
    void othersAreRefused() {
        String dm = register("dungeonmaster").accessToken();
        JsonNode campaign = createCampaign(dm);
        String player = register("aria").accessToken();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player));
        String loner = register("loner").accessToken();

        for (String token : List.of(player, loner)) {
            paths.forEach(path -> {
                JsonNode refusal = expect(HttpStatus.FORBIDDEN, exchange(HttpMethod.GET, path, null, token));
                assertThat(refusal.get("error").asString()).isEqualTo("Only a campaign's DM can read the bestiary");
            });
        }
    }
}
