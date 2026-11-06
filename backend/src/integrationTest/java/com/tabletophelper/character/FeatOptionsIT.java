package com.tabletophelper.character;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatEffects;
import com.tabletophelper.reference.FeatPrerequisite;
import com.tabletophelper.reference.FeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feat prerequisites over HTTP on PostgreSQL (D158, D159): {@code GET /api/characters/{id}/feat-options} for the
 * character's owner and the DM of its campaign, a player's feat refused with what it misses, and a DM's grant that
 * goes through anyway. Aria is a Human Fighter 4 with STR 16 and CHA 10 in the DM's campaign.
 */
class FeatOptionsIT extends IntegrationTest {

    @Autowired private FeatRepository featRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private String aria;
    private UUID warCaster;
    private UUID leader;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID raceId = insertRace("Human");
        UUID fighterId = insertClass("Fighter", 10);
        Map<String, Object> body = new LinkedHashMap<>(Map.of("raceId", raceId, "strength", 16, "dexterity", 14,
                "constitution", 15, "intelligence", 10, "wisdom", 10, "charisma", 10));
        body.putAll(Map.of("name", "Aria", "classId", fighterId, "level", 4, "campaignId", campaignId));
        aria = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()))
                .get("id").asString();

        feat("Grappler", "[{\"abilities\": [{\"ability\": \"STR\", \"minimum\": 13}]}]", "{}");
        leader = feat("Inspiring Leader", "[{\"abilities\": [{\"ability\": \"CHA\", \"minimum\": 13}]}]", "{}");
        warCaster = feat("War Caster", "[{\"spellcasting\": true}]", "{}");
        feat("Lucky", null, "{}");
        feat("Elemental Adept", "[{\"spellcasting\": true}]", "{\"repeatable\": true}");
    }

    @Test
    @DisplayName("The owner and the campaign's DM read the feat options; anyone else gets 403, and an unknown character 404")
    void access() {
        expect(HttpStatus.OK, options(player));
        expect(HttpStatus.OK, options(dm));
        RegisteredUser stranger = register("bram");
        assertThat(json(options(stranger)).get("error").asString()).isEqualTo("You do not have access to this character");
        assertThat(options(stranger).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var unknown = exchange(HttpMethod.GET, "/api/characters/" + UUID.randomUUID() + "/feat-options", null,
                player.accessToken());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Each feat says whether the character meets its prerequisite, what it misses, the prerequisite in words, whether it is taken and whether it can be taken again")
    void answers() {
        JsonNode options = expect(HttpStatus.OK, options(player));

        assertThat(names(options)).containsExactly("Elemental Adept", "Grappler", "Inspiring Leader", "Lucky", "War Caster");
        JsonNode grappler = option(options, "Grappler");
        assertThat(grappler.get("eligible").asBoolean()).isTrue();
        assertThat(grappler.get("unmet")).isEmpty();
        assertThat(grappler.get("prerequisite").asString()).isEqualTo("Strength 13");
        assertThat(grappler.get("taken").asBoolean()).isFalse();
        assertThat(grappler.get("repeatable").asBoolean()).isFalse();
        assertThat(grappler.get("source").asString()).isEqualTo("PHB");
        JsonNode inspiring = option(options, "Inspiring Leader");
        assertThat(inspiring.get("eligible").asBoolean()).isFalse();
        assertThat(inspiring.get("unmet").get(0).asString()).isEqualTo("Charisma 13 (you have 10)");
        assertThat(option(options, "Lucky").get("prerequisite").isNull()).isTrue();
        assertThat(option(options, "Elemental Adept").get("repeatable").asBoolean()).isTrue();
        assertThat(option(options, "War Caster").get("unmet").get(0).asString())
                .isEqualTo("The ability to cast at least one spell");
    }

    @Test
    @DisplayName("A player's feat whose prerequisite the character doesn't meet is a 400 naming it; the DM's grant of one goes through and is then shown taken, still unmet")
    void playerRefusedDmGrants() {
        JsonNode sheet = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + aria, null,
                player.accessToken()));
        String asi = StreamSupport.stream(sheet.get("pendingChoices").spliterator(), false)
                .filter(c -> "ASI".equals(c.get("type").asString())).findFirst().orElseThrow().get("id").asString();
        var refused = exchange(HttpMethod.POST, "/api/characters/" + aria + "/apply-choices",
                Map.of("choiceId", asi, "asi", Map.of("type", "feat", "featId", leader)), player.accessToken());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(refused).get("error").asString())
                .isEqualTo("Aria doesn't meet the prerequisite of Inspiring Leader: Charisma 13 (you have 10)");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + aria + "/adjustments",
                Map.of("kind", "FEAT", "feat", Map.of("featId", warCaster), "reason", "A boon"), dm.accessToken()));

        JsonNode warCasterOption = option(expect(HttpStatus.OK, options(dm)), "War Caster");
        assertThat(warCasterOption.get("taken").asBoolean()).isTrue();
        assertThat(warCasterOption.get("eligible").asBoolean()).isFalse();
        assertThat(warCasterOption.get("unmet")).hasSize(1);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private UUID feat(String name, String prerequisite, String effects) {
        return featRepository.save(Feat.builder().name(name).source("PHB").description(name)
                .prerequisite(FeatPrerequisite.parse(prerequisite)).effects(FeatEffects.parse(effects)).build())
                .getId();
    }

    private ResponseEntity<String> options(RegisteredUser user) {
        return exchange(HttpMethod.GET, "/api/characters/" + aria + "/feat-options", null, user.accessToken());
    }

    private static JsonNode option(JsonNode options, String name) {
        for (JsonNode option : options) {
            if (name.equals(option.get("name").asString())) return option;
        }
        throw new AssertionError("No option " + name + " in " + options);
    }

    private static List<String> names(JsonNode options) {
        return StreamSupport.stream(options.spliterator(), false).map(o -> o.get("name").asString()).toList();
    }
}
