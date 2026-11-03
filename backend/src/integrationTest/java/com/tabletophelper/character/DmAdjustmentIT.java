package com.tabletophelper.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DM Adjustments over HTTP on PostgreSQL (D158, D161): only the DM of the character's campaign adds or removes one,
 * each takes the character's row lock and answers with the character as the server then works it out, and a removed
 * one stays as the record. Aria is a Human Fighter 3 with DEX 14 and CON 15 (AC 12, 28 hit points) in the DM's
 * campaign; Bram is a Human Wizard 3 with INT 16 and CON 14 (20 hit points, spell save DC 13, spell attack +5).
 */
@ExtendWith(OutputCaptureExtension.class)
class DmAdjustmentIT extends IntegrationTest {

    @Autowired private FeatRepository featRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID raceId;
    private UUID fighterId;
    private UUID wizardId;
    private String aria;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        raceId = insertRace("Human");
        fighterId = insertClass("Fighter", 10);
        wizardId = insertCaster("Wizard", 6, "INT", false);
        aria = create(Map.of("name", "Aria", "classId", fighterId, "level", 3, "campaignId", campaignId));
    }

    @Test
    @DisplayName("The DM adds an adjustment, which the player sees with its reason, and removes it, which keeps it as the record; removing it again is a 409")
    void theDmAddsAndRemoves(CapturedOutput output) {
        JsonNode added = adjust(aria, Map.of("kind", "STAT", "target", "ARMOUR_CLASS", "amount", 1,
                "reason", "Ring of Protection"));

        assertThat(added.get("armourClass").asInt()).isEqualTo(13);
        JsonNode ring = added.get("dmAdjustments").get(0);
        assertThat(ring.get("kind").asText()).isEqualTo("STAT");
        assertThat(ring.get("target").asText()).isEqualTo("ARMOUR_CLASS");
        assertThat(ring.get("amount").asInt()).isEqualTo(1);
        assertThat(ring.get("reason").asText()).isEqualTo("Ring of Protection");
        assertThat(ring.get("addedBy").asText()).isEqualTo(dm.id().toString());
        assertThat(ring.get("addedAt").isTextual()).isTrue();
        assertThat(ring.has("removedAt")).isFalse();
        // The player sees it on their sheet, reason and all
        JsonNode sheet = get(aria);
        assertThat(sheet.get("armourClass").asInt()).isEqualTo(13);
        assertThat(sheet.get("dmAdjustments").get(0).get("reason").asText()).isEqualTo("Ring of Protection");
        // Every use is logged by ID, never with the reason
        String ringId = ring.get("id").asText();
        assertThat(output.getOut()).contains("DM " + dm.id() + " added DM Adjustment " + ringId + " to character "
                + aria + ": STAT ARMOUR_CLASS +1");

        JsonNode removed = expect(HttpStatus.OK, remove(aria, ringId, dm));

        assertThat(removed.get("armourClass").asInt()).isEqualTo(12);
        JsonNode record = removed.get("dmAdjustments").get(0);
        assertThat(record.get("reason").asText()).isEqualTo("Ring of Protection");
        assertThat(record.get("removedBy").asText()).isEqualTo(dm.id().toString());
        assertThat(record.get("removedAt").isTextual()).isTrue();
        assertThat(output.getOut()).contains("DM " + dm.id() + " removed DM Adjustment " + ringId)
                .doesNotContain("Ring of Protection");

        assertThat(error(remove(aria, ringId, dm), HttpStatus.CONFLICT))
                .isEqualTo("That DM Adjustment has already been removed");
        assertThat(error(remove(aria, UUID.randomUUID().toString(), dm), HttpStatus.NOT_FOUND))
                .isEqualTo("DM Adjustment not found");
        assertThat(get(aria).get("dmAdjustments")).hasSize(1);
    }

    @Test
    @DisplayName("Only the DM of the character's campaign adjusts it: its owner and a stranger get 403, an unknown character 404, and a character in no campaign has no DM")
    void onlyTheCampaignsDm() {
        RegisteredUser stranger = register("bram");
        Map<String, Object> ring = Map.of("kind", "STAT", "target", "ARMOUR_CLASS", "amount", 1,
                "reason", "Ring of Protection");
        String ringId = adjust(aria, ring).get("dmAdjustments").get(0).get("id").asText();
        int version = version(aria);

        for (RegisteredUser notTheDm : List.of(player, stranger)) {
            assertThat(error(exchange(HttpMethod.POST, "/api/characters/" + aria + "/adjustments", ring,
                    notTheDm.accessToken()), HttpStatus.FORBIDDEN))
                    .isEqualTo("Only the DM of this character's campaign can adjust it");
            assertThat(error(remove(aria, ringId, notTheDm), HttpStatus.FORBIDDEN))
                    .isEqualTo("Only the DM of this character's campaign can adjust it");
        }
        assertThat(error(exchange(HttpMethod.POST, "/api/characters/" + UUID.randomUUID() + "/adjustments", ring,
                dm.accessToken()), HttpStatus.NOT_FOUND)).isEqualTo("Character not found");

        String loner = create(Map.of("name", "Loner", "classId", fighterId, "level", 1));
        for (RegisteredUser anyone : List.of(dm, player)) {
            assertThat(error(exchange(HttpMethod.POST, "/api/characters/" + loner + "/adjustments", ring,
                    anyone.accessToken()), HttpStatus.FORBIDDEN))
                    .isEqualTo("This character isn't in a campaign, so it has no DM to adjust it");
        }

        assertThat(version(aria)).isEqualTo(version);
        assertThat(get(aria).get("armourClass").asInt()).isEqualTo(13);
    }

    @Test
    @DisplayName("An owner who moves their character into a campaign of their own, so is its DM, still can't adjust or revive it: 403, changing nothing")
    void theOwnerIsNeverTheirOwnDm() {
        Map<String, Object> ring = Map.of("kind", "STAT", "target", "ARMOUR_CLASS", "amount", 1,
                "reason", "Ring of Protection");
        String ringId = adjust(aria, ring).get("dmAdjustments").get(0).get("id").asText();
        // Creating a campaign makes the player its DM
        UUID ownCampaign = UUID.fromString(createCampaign(player.accessToken()).get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + aria + "/campaign",
                Map.of("campaignId", ownCampaign), player.accessToken()));
        int version = version(aria);

        String refusal = "You can't use DM actions on your own character";
        assertThat(error(exchange(HttpMethod.POST, "/api/characters/" + aria + "/adjustments", ring,
                player.accessToken()), HttpStatus.FORBIDDEN)).isEqualTo(refusal);
        assertThat(error(remove(aria, ringId, player), HttpStatus.FORBIDDEN)).isEqualTo(refusal);
        // Refused before the revival asks whether the character is dead
        assertThat(error(exchange(HttpMethod.POST, "/api/characters/" + aria + "/revive", null,
                player.accessToken()), HttpStatus.FORBIDDEN)).isEqualTo(refusal);

        assertThat(version(aria)).isEqualTo(version);
        JsonNode sheet = get(aria);
        assertThat(sheet.get("armourClass").asInt()).isEqualTo(13);
        assertThat(sheet.get("dmAdjustments")).hasSize(1);
        assertThat(sheet.get("dmAdjustments").get(0).has("removedAt")).isFalse();
    }

    @Test
    @DisplayName("STAT adjustments reach the Derived Stats: ability scores first, then hit points, which follow the maximum, initiative, speed and every spell number")
    void statAdjustments() {
        String bram = create(Map.of("name", "Bram", "classId", wizardId, "level", 3, "campaignId", campaignId,
                "constitution", 14, "intelligence", 16));
        JsonNode wizard = get(bram);
        assertThat(wizard.get("hpMax").asInt()).isEqualTo(20);
        assertThat(wizard.get("spellcasting").get(0).get("spellSaveDc").asInt()).isEqualTo(13);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + bram + "/hp",
                Map.of("kind", "DAMAGE", "amount", 6), player.accessToken()));

        // CON 14 to 16 adds 1 a level, and current hit points rise with the maximum: 14 to 17 of 23
        JsonNode amulet = adjust(bram, stat("CON", 2, "Amulet of Health"));
        assertThat(amulet.get("constitution").asInt()).isEqualTo(16);
        assertThat(amulet.get("baseAbilityScores").get("constitution").asInt()).isEqualTo(14);
        assertThat(amulet.get("hpMax").asInt()).isEqualTo(23);
        assertThat(amulet.get("hpCurrent").asInt()).isEqualTo(17);

        // A lower maximum caps current hit points, and lifting it again raises them by as much
        JsonNode cursed = adjust(bram, stat("HP_MAX", -10, "Curse of the hag"));
        assertThat(cursed.get("hpMax").asInt()).isEqualTo(13);
        assertThat(cursed.get("hpCurrent").asInt()).isEqualTo(13);
        JsonNode lifted = expect(HttpStatus.OK, remove(bram, cursed.get("dmAdjustments").get(1).get("id").asText(), dm));
        assertThat(lifted.get("hpMax").asInt()).isEqualTo(23);
        assertThat(lifted.get("hpCurrent").asInt()).isEqualTo(23);

        // DEX adjustments reach initiative through the modifier; initiative and speed ones add directly
        assertThat(adjust(bram, stat("DEX", 2, "Gloves of the cat")).get("initiativeBonus").asInt()).isEqualTo(3);
        assertThat(adjust(bram, stat("INITIATIVE", 1, "Weapon of Warning")).get("initiativeBonus").asInt())
                .isEqualTo(4);
        assertThat(adjust(bram, stat("SPEED", 10, "Boots of Striding")).get("speed").asInt()).isEqualTo(40);

        // Spell adjustments apply to the class's numbers and to the main ones
        adjust(bram, stat("SPELL_SAVE_DC", 1, "Rod of the Pact Keeper"));
        JsonNode rod = adjust(bram, stat("SPELL_ATTACK_BONUS", 2, "Wand of the War Mage"));
        assertThat(rod.get("spellcasting").get(0).get("spellSaveDc").asInt()).isEqualTo(14);
        assertThat(rod.get("spellcasting").get(0).get("spellAttackBonus").asInt()).isEqualTo(7);
        assertThat(rod.get("spellSaveDc").asInt()).isEqualTo(14);
        assertThat(rod.get("spellAttackBonus").asInt()).isEqualTo(7);

        // An ability adjustment may pass the maximum of 20, but no score goes above 30
        JsonNode giant = adjust(bram, stat("STR", 12, "Belt of Giant Strength"));
        assertThat(giant.get("strength").asInt()).isEqualTo(28);
        assertThat(adjust(bram, stat("STR", 8, "Potion of Giant Strength")).get("strength").asInt()).isEqualTo(30);
        assertThat(get(bram).get("baseAbilityScores").get("strength").asInt()).isEqualTo(16);
        assertThat(get(bram).get("abilityScoreMaximums").get("strength").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("A DEFENCE adjustment reaches the character's participant in a live encounter at once, and one it already has counts once")
    void defencesInLiveEncounters() {
        String tiefling = create(Map.of("name", "Zariel", "classId", fighterId, "level", 3, "campaignId", campaignId,
                "damageResistances", List.of("fire")));
        UUID encounterId = createEncounter(dm.accessToken(), campaignId);
        for (String characterId : List.of(aria, tiefling)) {
            addParticipant(dm.accessToken(), encounterId,
                    Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", characterId));
        }
        startEncounter(dm.accessToken(), encounterId);

        // Allowed while the encounter is under way, and applied to the next hit
        JsonNode resistant = adjust(aria, defence("RESISTANCE", "Fire", "Ring of Fire Resistance"));
        assertThat(resistant.get("inLiveEncounter").asBoolean()).isTrue();
        assertThat(resistant.get("damageResistances").toString()).isEqualTo("[\"fire\"]");
        assertThat(damage(encounterId, aria, 10, "fire")).isEqualTo(28 - 5);
        assertThat(log(encounterId)).anySatisfy(line -> assertThat(line).contains("halved by resistance"));

        String vulnerability = adjust(aria, defence("VULNERABILITY", "cold", "Curse of the frost giant"))
                .get("dmAdjustments").get(1).get("id").asText();
        assertThat(get(aria).get("damageVulnerabilities").toString()).isEqualTo("[\"cold\"]");
        assertThat(damage(encounterId, aria, 4, "cold")).isEqualTo(23 - 8);

        expect(HttpStatus.OK, remove(aria, resistant.get("dmAdjustments").get(0).get("id").asText(), dm));
        expect(HttpStatus.OK, remove(aria, vulnerability, dm));
        assertThat(damage(encounterId, aria, 10, "fire")).isEqualTo(15 - 10);
        assertThat(damage(encounterId, aria, 4, "cold")).isEqualTo(5 - 4);

        // The racial resistance and the DM's count once, and the racial one stays when the DM's goes
        JsonNode twice = adjust(tiefling, defence("RESISTANCE", "fire", "Ring of Fire Resistance"));
        assertThat(twice.get("damageResistances").toString()).isEqualTo("[\"fire\"]");
        assertThat(damage(encounterId, tiefling, 12, "fire")).isEqualTo(28 - 6);
        JsonNode racialOnly = expect(HttpStatus.OK,
                remove(tiefling, twice.get("dmAdjustments").get(0).get("id").asText(), dm));
        assertThat(racialOnly.get("damageResistances").toString()).isEqualTo("[\"fire\"]");
        assertThat(damage(encounterId, tiefling, 12, "fire")).isEqualTo(22 - 6);
    }

    @Test
    @DisplayName("A FEAT grant applies the feat, its pool and its ability increase, refuses a feat the character has, and removing it puts the sheet back as it was")
    void featGrants() {
        UUID lucky = feat("Lucky", "You have inexplicable luck.", null,
                "{\"resource\": {\"name\": \"Luck Points\", \"maxUses\": 3, \"resetOn\": \"longRest\"}}");
        UUID resilient = feat("Resilient", "Choose one ability score.",
                "[{\"choose\": {\"from\": [\"STR\", \"DEX\", \"CON\", \"INT\", \"WIS\", \"CHA\"], \"amount\": 1}}]",
                "{\"savingThrowProficiencies\": [{\"choose\": {\"from\": [\"str\", \"dex\", \"con\", \"int\", "
                        + "\"wis\", \"cha\"]}}]}");
        UUID mobile = feat("Mobile", "You are exceptionally speedy and agile.", null, "{\"speedBonus\": 10}");
        Map<String, Object> before = sheet(get(aria));

        JsonNode luck = adjust(aria, grant(Map.of("featId", lucky), "Blessing of Tymora"));
        JsonNode luckPool = pool(luck, "feat:lucky");
        assertThat(luckPool.get("currentUses").asInt()).isEqualTo(3);
        assertThat(luckPool.get("maxUses").asInt()).isEqualTo(3);
        assertThat(luck.get("dmAdjustments").get(0).get("target").asText()).isEqualTo("Lucky");
        assertThat(luck.get("dmAdjustments").get(0).get("featId").asText()).isEqualTo(lucky.toString());
        assertThat(featureNames(luck)).contains("Lucky");
        // The pool is the player's to spend, as any other
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + aria + "/pools/spend",
                Map.of("poolId", "feat:lucky"), player.accessToken()));
        assertThat(error(exchange(HttpMethod.POST, "/api/characters/" + aria + "/adjustments",
                grant(Map.of("featId", lucky), "Another blessing"), dm.accessToken()), HttpStatus.CONFLICT))
                .isEqualTo("Aria already has Lucky, which can't be taken again");

        // A half-feat raises the ability chosen, which carries the hit points with it: CON 15 to 16 is 1 a level
        JsonNode hardy = adjust(aria, grant(Map.of("featId", resilient, "featAbility", "CON",
                "savingThrowChoice", "CON"), "Boon of the mountain"));
        assertThat(hardy.get("constitution").asInt()).isEqualTo(16);
        assertThat(hardy.get("baseAbilityScores").get("constitution").asInt()).isEqualTo(16);
        assertThat(hardy.get("hpMax").asInt()).isEqualTo(31);
        assertThat(hardy.get("savingThrowProficiencies").toString()).contains("CON");
        assertThat(hardy.get("dmAdjustments").get(1).get("appliedEffects").get("abilityIncreases").get("con").asInt())
                .isEqualTo(1);
        assertThat(adjust(aria, grant(Map.of("featId", mobile), "Boots of the wind")).get("speed").asInt())
                .isEqualTo(40);

        for (int i = 0; i < 3; i++) {
            String id = get(aria).get("dmAdjustments").get(i).get("id").asText();
            expect(HttpStatus.OK, remove(aria, id, dm));
        }

        JsonNode after = get(aria);
        assertThat(sheet(after)).isEqualTo(before);
        assertThat(after.get("resourcePools").toString()).doesNotContain("feat:lucky");
        assertThat(after.get("dmAdjustments")).hasSize(3)
                .allSatisfy(adjustment -> assertThat(adjustment.has("removedAt")).isTrue());
    }

    @Test
    @DisplayName("A feat that can be taken more than once can be granted again, and removing one grant leaves the other")
    void repeatableFeats() {
        UUID adept = feat("Elemental Adept", "Spells you cast ignore resistance to damage of the chosen type. You can "
                + "select this feat multiple times. Each time you do so, you must choose a different damage type.",
                null, "{}");

        adjust(aria, grant(Map.of("featId", adept), "Gift of the fire primordial"));
        JsonNode twice = adjust(aria, grant(Map.of("featId", adept), "Gift of the frost primordial"));
        assertThat(featureNames(twice)).filteredOn("Elemental Adept"::equals).hasSize(2);

        JsonNode once = expect(HttpStatus.OK, remove(aria, twice.get("dmAdjustments").get(1).get("id").asText(), dm));
        assertThat(featureNames(once)).filteredOn("Elemental Adept"::equals).hasSize(1);
    }

    @Test
    @DisplayName("A request its kind doesn't fit is a 400 and changes nothing")
    void requestsThatDontFitAre400() {
        int version = version(aria);
        Map<String, Map<String, Object>> refusals = new LinkedHashMap<>();
        refusals.put("A STAT adjustment needs an amount other than 0", stat("ARMOUR_CLASS", 0, "Nothing"));
        refusals.put("A STAT adjustment's target is one of ARMOUR_CLASS, HP_MAX, SPEED, INITIATIVE, SPELL_SAVE_DC, "
                + "SPELL_ATTACK_BONUS, STR, DEX, CON, INT, WIS, CHA", stat("SAVING_THROWS", 1, "Cloak of Protection"));
        refusals.put("A STAT adjustment has no defence", Map.of("kind", "STAT", "target", "ARMOUR_CLASS", "amount", 1,
                "defence", "RESISTANCE", "reason", "Mixed up"));
        refusals.put("A DEFENCE adjustment's target is a damage type: acid, bludgeoning, cold, fire, force, lightning, "
                + "necrotic, piercing, poison, psychic, radiant, slashing, thunder",
                defence("RESISTANCE", "nonmagical attacks", "Stoneskin"));
        refusals.put("A DEFENCE adjustment needs its defence: RESISTANCE, IMMUNITY or VULNERABILITY",
                Map.of("kind", "DEFENCE", "target", "fire", "reason", "Nothing"));
        refusals.put("A FEAT adjustment needs the feat's ID", Map.of("kind", "FEAT", "reason", "Nothing"));
        refusals.put("Feat not found", grant(Map.of("featId", UUID.randomUUID()), "Nothing"));

        refusals.forEach((message, request) -> assertThat(error(exchange(HttpMethod.POST,
                "/api/characters/" + aria + "/adjustments", request, dm.accessToken()), HttpStatus.BAD_REQUEST))
                .as(message).isEqualTo(message));
        var noReason = exchange(HttpMethod.POST, "/api/characters/" + aria + "/adjustments",
                Map.of("kind", "STAT", "target", "ARMOUR_CLASS", "amount", 1, "reason", " "), dm.accessToken());
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(version(aria)).isEqualTo(version);
        assertThat(get(aria).get("dmAdjustments")).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String create(Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("raceId", raceId,
                "strength", 16, "dexterity", 14, "constitution", 15, "intelligence", 10, "wisdom", 10,
                "charisma", 10));
        body.putAll(details);
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()))
                .get("id").asText();
    }

    private UUID feat(String name, String description, String abilityScoreIncrease, String effects) {
        return featRepository.save(Feat.builder().name(name).source("PHB").description(description)
                .abilityScoreIncrease(abilityScoreIncrease).effects(effects).build()).getId();
    }

    private static Map<String, Object> stat(String target, int amount, String reason) {
        return Map.of("kind", "STAT", "target", target, "amount", amount, "reason", reason);
    }

    private static Map<String, Object> defence(String defence, String damageType, String reason) {
        return Map.of("kind", "DEFENCE", "defence", defence, "target", damageType, "reason", reason);
    }

    private static Map<String, Object> grant(Map<String, Object> feat, String reason) {
        return Map.of("kind", "FEAT", "feat", feat, "reason", reason);
    }

    private JsonNode adjust(String characterId, Map<String, Object> request) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + characterId + "/adjustments",
                request, dm.accessToken()));
    }

    private ResponseEntity<String> remove(String characterId, String adjustmentId, RegisteredUser user) {
        return exchange(HttpMethod.DELETE, "/api/characters/" + characterId + "/adjustments/" + adjustmentId, null,
                user.accessToken());
    }

    private JsonNode get(String characterId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + characterId, null,
                player.accessToken()));
    }

    private String error(ResponseEntity<String> response, HttpStatus status) {
        return expect(status, response).get("error").asText();
    }

    private int version(String characterId) {
        Integer version = jdbcTemplate.queryForObject("SELECT version FROM player_characters WHERE id = ?",
                Integer.class, UUID.fromString(characterId));
        return version != null ? version : -1;
    }

    // The DM's damage to the character's participant, and the hit points it has left
    private int damage(UUID encounterId, String characterId, int amount, String damageType) {
        JsonNode encounter = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
        String participantId = null;
        for (JsonNode participant : encounter.get("participants")) {
            if (characterId.equals(participant.path("characterId").asText())) {
                participantId = participant.get("id").asText();
            }
        }
        JsonNode after = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/damage", Map.of("targetId", participantId, "amount", amount, "damageType", damageType),
                dm.accessToken()));
        for (JsonNode participant : after.get("participants")) {
            if (participantId.equals(participant.get("id").asText())) {
                return participant.get("hpCurrent").asInt();
            }
        }
        throw new AssertionError("No participant for " + characterId);
    }

    private List<String> log(UUID encounterId) {
        List<String> lines = new ArrayList<>();
        expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken())).forEach(entry -> lines.add(entry.get("description").asText()));
        return lines;
    }

    private static JsonNode pool(JsonNode character, String poolId) {
        for (JsonNode pool : character.get("resourcePools")) {
            if (poolId.equals(pool.get("poolId").asText())) {
                return pool;
            }
        }
        throw new AssertionError("No pool " + poolId + " in " + character.get("resourcePools"));
    }

    private static List<String> featureNames(JsonNode character) {
        List<String> names = new ArrayList<>();
        character.get("features").forEach(feature -> names.add(feature.get("name").asText()));
        return names;
    }

    // Everything a feat grant changes, in one comparable shape; a list taken back to empty counts as none
    private static Map<String, Object> sheet(JsonNode c) {
        return Map.ofEntries(
                Map.entry("hp", c.get("hpCurrent").asInt() + "/" + c.get("hpMax").asInt()),
                Map.entry("constitution", c.get("constitution").asInt()),
                Map.entry("baseAbilityScores", c.get("baseAbilityScores").toString()),
                Map.entry("initiative", c.get("initiativeBonus").asInt()),
                Map.entry("speed", c.get("speed").asInt()),
                Map.entry("armourClass", c.get("armourClass").asInt()),
                Map.entry("savingThrows", items(c.get("savingThrowProficiencies"))),
                Map.entry("features", featureNames(c)),
                Map.entry("pools", items(c.get("resourcePools"))),
                Map.entry("spellsKnown", items(c.get("spellsKnown"))));
    }

    private static List<String> items(JsonNode list) {
        List<String> items = new ArrayList<>();
        list.forEach(item -> items.add(item.toString()));
        return items;
    }
}
