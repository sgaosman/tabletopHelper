package com.tabletophelper.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.reference.OptionalFeature;
import com.tabletophelper.reference.OptionalFeatureRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Pending Choices over HTTP on PostgreSQL (D159). Creating a character at level N owes the choices of levels 1 to N,
 * and levelling up those of the new level; apply-choices makes one by its ID and records it on the level that owes it;
 * a choice the character doesn't have is a 409 and an answer that doesn't fit a 400, each changing nothing; a choice
 * left for later stays pending while the character levels up and joins an encounter; levelling down removes the
 * level's own and reverses those made; and a Variant Human's feat at creation gets its pool and its initiative. The
 * DM sees the choices but can't make them. Aria is a Human Fighter with DEX 14 in the DM's campaign; the fighter
 * chooses between the Champion and the Eldritch Knight at 3rd level.
 */
class PendingChoicesIT extends IntegrationTest {

    @Autowired private SubclassRepository subclassRepository;
    @Autowired private FeatRepository featRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private OptionalFeatureRepository optionalFeatureRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private UUID humanId;
    private UUID variantHumanId;
    private UUID fighterId;
    private UUID sorcererId;
    private UUID championId;
    private UUID eldritchKnightId;
    private UUID luckyId;
    private UUID alertId;

    @BeforeEach
    void insertReferenceData() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asText());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        humanId = insertRace("Human");
        variantHumanId = raceRepository.save(Race.builder().name("Variant Human").source("PHB")
                .speed("{\"walk\":30}").raceChoices("{\"feats\": 1, \"skills\": [{\"any\": 1}]}").build()).getId();
        CharacterClass fighter = characterClassRepository.save(CharacterClass.builder().name("Fighter").source("PHB")
                .hitDice(10).subclassLevel(3).isSpellcaster(false).isPactMagic(false)
                .features("[{\"level\":1,\"name\":\"Fighting Style\",\"description\":\"Choose a style\"},"
                        + "{\"level\":1,\"name\":\"Second Wind\",\"description\":\"Heal\"},"
                        + "{\"level\":2,\"name\":\"Action Surge\",\"description\":\"Act again\"},"
                        + "{\"level\":4,\"name\":\"Ability Score Improvement\",\"description\":\"Improve\"}]")
                .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},"
                        + "{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]")
                .build());
        fighterId = fighter.getId();
        sorcererId = insertCaster("Sorcerer", 6, "CHA", false);
        championId = subclassRepository.save(Subclass.builder().name("Champion").source("PHB").characterClass(fighter)
                .features("[{\"level\":3,\"name\":\"Improved Critical\",\"description\":\"Crit on a 19\"}]")
                .build()).getId();
        eldritchKnightId = subclassRepository.save(Subclass.builder().name("Eldritch Knight").source("PHB")
                .characterClass(fighter)
                .features("[{\"level\":3,\"name\":\"Weapon Bond\",\"description\":\"Bonded weapons\"}]")
                .build()).getId();
        luckyId = featRepository.save(Feat.builder().name("Lucky").source("PHB").description("Luck points")
                .effects("{\"resource\": {\"name\": \"Luck Points\", \"maxUses\": 3, \"resetOn\": \"longRest\"}}")
                .build()).getId();
        alertId = featRepository.save(Feat.builder().name("Alert").source("PHB").description("Always on the lookout")
                .effects("{\"initiativeBonus\": 5}").build()).getId();
        for (String[] spell : new String[][]{{"Fire Bolt", "0"}, {"Minor Illusion", "0"}, {"Light", "0"},
                {"Mage Hand", "0"}, {"Prestidigitation", "0"}, {"Shield", "1"}, {"Magic Missile", "1"},
                {"Witch Bolt", "1"}, {"Sleep", "1"}, {"Misty Step", "2"}, {"Fireball", "3"}}) {
            spellRepository.save(Spell.builder().name(spell[0]).level(Integer.parseInt(spell[1])).school("Evocation")
                    .source("PHB").classes("[\"Sorcerer\",\"Wizard\"]").build());
        }
        optionalFeatureRepository.save(OptionalFeature.builder().name("Defense").featureType("FightingStyle")
                .source("PHB").description("+1 AC while you wear armour").build());
    }

    // ── Helpers ──────────────────────────────────────────────────

    private JsonNode create(Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Aria", "raceId", humanId, "classId", fighterId,
                "strength", 16, "dexterity", 14, "constitution", 12, "intelligence", 10, "wisdom", 10,
                "charisma", 16));
        body.put("campaignId", campaignId);
        body.putAll(details);
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()));
    }

    private ResponseEntity<String> apply(String characterId, Map<String, Object> answer) {
        return exchange(HttpMethod.POST, "/api/characters/" + characterId + "/apply-choices", answer,
                player.accessToken());
    }

    private JsonNode applied(String characterId, Map<String, Object> answer) {
        return expect(HttpStatus.OK, apply(characterId, answer));
    }

    private JsonNode get(String characterId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + characterId, null,
                player.accessToken()));
    }

    private JsonNode levelUp(String characterId, UUID classId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + characterId + "/level-up",
                Map.of("classId", classId), player.accessToken())).get("character");
    }

    private JsonNode levelDown(String characterId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + characterId + "/level-down", null,
                player.accessToken()));
    }

    private String error(ResponseEntity<String> response, HttpStatus status) {
        return expect(status, response).get("error").asText();
    }

    // Each pending choice as its level, type and count, as "4 ASI" or "3 SPELLS×3"
    private static List<String> described(JsonNode character) {
        List<String> choices = new ArrayList<>();
        character.get("pendingChoices").forEach(c -> choices.add(c.get("characterLevel").asInt() + " "
                + c.get("type").asText() + (c.get("count").asInt() > 1 ? "×" + c.get("count").asInt() : "")));
        return choices;
    }

    private static JsonNode choice(JsonNode character, String type, int characterLevel) {
        for (JsonNode choice : character.get("pendingChoices")) {
            if (choice.get("type").asText().equals(type) && choice.get("characterLevel").asInt() == characterLevel) {
                return choice;
            }
        }
        throw new AssertionError("No pending " + type + " at level " + characterLevel + ": "
                + character.get("pendingChoices"));
    }

    private static String id(JsonNode character, String type, int characterLevel) {
        return choice(character, type, characterLevel).get("id").asText();
    }

    private static List<String> names(JsonNode character, String source) {
        List<String> names = new ArrayList<>();
        character.get("spellsKnown").forEach(s -> {
            if (source.equals(s.path("source").asText())) names.add(s.get("name").asText());
        });
        return names;
    }

    private static List<String> features(JsonNode character) {
        List<String> names = new ArrayList<>();
        character.get("features").forEach(f -> names.add(f.get("name").asText()));
        return names;
    }

    private int version(String characterId) {
        Integer version = jdbcTemplate.queryForObject("SELECT version FROM player_characters WHERE id = ?",
                Integer.class, UUID.fromString(characterId));
        return version != null ? version : -1;
    }

    // ── Where choices come from ──────────────────────────────────

    @Test
    @DisplayName("Creating a Fighter 5 owes the choices of levels 1 to 5, and levelling up adds the new level's")
    void creationAndLevelUp() {
        JsonNode created = create(Map.of("level", 5));
        String aria = created.get("id").asText();

        assertThat(described(created)).containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS", "4 ASI");
        JsonNode subclass = choice(created, "SUBCLASS", 3);
        assertThat(subclass.get("source").asText()).isEqualTo("CREATION");
        assertThat(subclass.get("classId").asText()).isEqualTo(fighterId.toString());
        assertThat(subclass.get("options").toString()).isEqualTo("[\"Champion\",\"Eldritch Knight\"]");
        assertThat(choice(created, "FIGHTING_STYLE", 1).get("options").toString())
                .startsWith("[\"Archery\",\"Defense\",\"Dueling\"");

        // The player leaves every choice for later and levels up: Fighter 6 owes another ASI
        JsonNode sixth = levelUp(aria, fighterId);
        assertThat(described(sixth)).containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS", "4 ASI", "6 ASI");
        assertThat(choice(sixth, "ASI", 6).get("source").asText()).isEqualTo("LEVEL_UP");
        assertThat(choice(sixth, "ASI", 6).get("classLevel").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("A known caster created at 5th level learns its later levels' spells on the sheet, one a level with a swap")
    void knownCasterCreatedAboveFirstLevel() {
        JsonNode created = create(Map.of("classId", sorcererId, "level", 5, "spellsKnown", List.of(
                Map.of("name", "Fire Bolt", "level", 0, "source", "class:Sorcerer"),
                Map.of("name", "Minor Illusion", "level", 0, "source", "class:Sorcerer"),
                Map.of("name", "Light", "level", 0, "source", "class:Sorcerer"),
                Map.of("name", "Mage Hand", "level", 0, "source", "class:Sorcerer"),
                Map.of("name", "Shield", "level", 1, "source", "class:Sorcerer"),
                Map.of("name", "Magic Missile", "level", 1, "source", "class:Sorcerer"))));
        String aria = created.get("id").asText();
        // The wizard gives the 1st level's four cantrips and two spells; levels 2 to 5 each owe a spell
        assertThat(described(created)).containsExactly("2 SPELLS", "3 SPELLS", "4 ASI", "4 CANTRIPS", "4 SPELLS",
                "5 SPELLS");
        assertThat(choice(created, "SPELLS", 3).get("maxSpellLevel").asInt()).isEqualTo(2);
        assertThat(choice(created, "SPELLS", 5).get("maxSpellLevel").asInt()).isEqualTo(3);
        assertThat(choice(created, "SPELLS", 2).get("swap").asBoolean()).isTrue();

        // Level 2's spell can't be of 2nd level, which the 3rd level can learn
        assertThat(error(apply(aria, Map.of("choiceId", id(created, "SPELLS", 2), "spells", List.of("Misty Step"))),
                HttpStatus.BAD_REQUEST))
                .isEqualTo("A level 2 Sorcerer learns spells up to level 1, so can't take Misty Step (level 2)");
        applied(aria, Map.of("choiceId", id(created, "SPELLS", 2), "spells", List.of("Witch Bolt")));
        applied(aria, Map.of("choiceId", id(created, "SPELLS", 3), "spells", List.of("Misty Step"),
                "swap", Map.of("replace", "Shield", "with", "Sleep")));
        applied(aria, Map.of("choiceId", id(created, "CANTRIPS", 4), "spells", List.of("Prestidigitation")));
        JsonNode fifth = applied(aria, Map.of("choiceId", id(created, "SPELLS", 5), "spells", List.of("Fireball")));

        assertThat(names(fifth, "class:Sorcerer")).containsExactly("Fire Bolt", "Minor Illusion", "Light", "Mage Hand",
                "Magic Missile", "Witch Bolt", "Sleep", "Misty Step", "Prestidigitation", "Fireball");
        JsonNode third = fifth.get("levelHistory").get(2).get("choices").get("spells");
        assertThat(third.get("learned").toString()).isEqualTo("[\"Misty Step\"]");
        assertThat(third.get("replaced").get("name").asText()).isEqualTo("Shield");
        assertThat(third.get("replacement").asText()).isEqualTo("Sleep");
        assertThat(described(fifth)).containsExactly("4 ASI", "4 SPELLS");
    }

    // ── Making them ──────────────────────────────────────────────

    @Test
    @DisplayName("A subclass chosen on the sheet is recorded on its level, with its features, and brings the choices it gives")
    void subclassAndTheChoicesItBrings() {
        String aria = create(Map.of("level", 4)).get("id").asText();
        JsonNode owed = get(aria);

        JsonNode knight = applied(aria, Map.of("choiceId", id(owed, "SUBCLASS", 3), "subclassId", eldritchKnightId));

        assertThat(knight.get("subclass").asText()).isEqualTo("Eldritch Knight");
        assertThat(knight.get("levelHistory").get(2).get("choices").get("subclass").get("subclassName").asText())
                .isEqualTo("Eldritch Knight");
        assertThat(features(knight)).contains("Weapon Bond");
        assertThat(described(knight)).containsExactly("1 FIGHTING_STYLE", "3 CANTRIPS×2", "3 SPELLS×3", "4 ASI",
                "4 SPELLS");
        assertThat(knight.get("spellcasting").get(0).get("className").asText()).isEqualTo("Fighter");

        JsonNode style = applied(aria, Map.of("choiceId", id(knight, "FIGHTING_STYLE", 1), "fightingStyle", "Defense"));
        assertThat(features(style)).contains("Fighting Style: Defense");
        JsonNode improved = applied(aria, Map.of("choiceId", id(style, "ASI", 4), "asi", Map.of("type", "ability",
                "increases", List.of(Map.of("ability", "dexterity", "bonus", 2)))));
        assertThat(improved.get("dexterity").asInt()).isEqualTo(16);
        assertThat(improved.get("initiativeBonus").asInt()).isEqualTo(3);
        assertThat(improved.get("levelHistory").get(3).get("choices").get("asi").get("type").asText())
                .isEqualTo("ability");
    }

    @Test
    @DisplayName("A choice the character doesn't have, or one made already, is a 409; an answer that doesn't fit is a 400; neither changes anything")
    void refusals() {
        JsonNode created = create(Map.of("level", 4));
        String aria = created.get("id").asText();
        String asi = id(created, "ASI", 4);
        int version = version(aria);

        assertThat(error(apply(aria, Map.of("choiceId", UUID.randomUUID())), HttpStatus.CONFLICT))
                .startsWith("Aria has no pending choice ");
        assertThat(error(apply(aria, Map.of("choiceId", asi, "subclassId", championId)), HttpStatus.BAD_REQUEST))
                .isEqualTo("This ASI choice takes asi, not subclassId");
        assertThat(error(apply(aria, Map.of("choiceId", asi, "asi", Map.of("type", "ability", "increases",
                List.of(Map.of("ability", "strength", "bonus", 3))))), HttpStatus.BAD_REQUEST))
                .isEqualTo("An Ability Score Improvement is +2 to one ability score or +1 to two");
        assertThat(error(apply(aria, Map.of("choiceId", id(created, "SUBCLASS", 3),
                "subclassId", UUID.randomUUID())), HttpStatus.BAD_REQUEST)).isEqualTo("Subclass not found");
        assertThat(error(apply(aria, Map.of("choiceId", id(created, "FIGHTING_STYLE", 1),
                "fightingStyle", "Blessed Warrior")), HttpStatus.BAD_REQUEST))
                .startsWith("A Fighter chooses its Fighting Style from Archery, Defense, ");
        assertThat(apply(aria, Map.of("asi", Map.of("type", "ability"))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(version(aria)).isEqualTo(version);
        assertThat(described(get(aria))).containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS", "4 ASI");

        applied(aria, Map.of("choiceId", asi, "asi", Map.of("type", "ability",
                "increases", List.of(Map.of("ability", "strength", "bonus", 2)))));
        assertThat(error(apply(aria, Map.of("choiceId", asi, "asi", Map.of("type", "ability",
                "increases", List.of(Map.of("ability", "strength", "bonus", 2))))), HttpStatus.CONFLICT))
                .startsWith("Aria has no pending choice " + asi);
    }

    @Test
    @DisplayName("Choices left for later stay pending while the character joins an encounter; while it is under way they wait, a 409")
    void skippedChoicesAndEncounters() {
        String aria = create(Map.of("level", 4)).get("id").asText();
        String asi = id(get(aria), "ASI", 4);
        UUID encounterId = createEncounter(dm.accessToken(), campaignId);

        addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", aria, "displayName", "Aria"));
        startEncounter(dm.accessToken(), encounterId);

        assertThat(error(apply(aria, Map.of("choiceId", asi, "asi", Map.of("type", "ability",
                "increases", List.of(Map.of("ability", "strength", "bonus", 2))))), HttpStatus.CONFLICT))
                .isEqualTo("Aria is in an encounter that is under way, so can't make the choices its levels bring "
                        + "until it ends");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end", null,
                dm.accessToken()));
        assertThat(applied(aria, Map.of("choiceId", asi, "asi", Map.of("type", "ability",
                "increases", List.of(Map.of("ability", "strength", "bonus", 2))))).get("strength").asInt())
                .isEqualTo(18);
    }

    @Test
    @DisplayName("Levelling down removes the level's pending choices and reverses those made for it, a subclass among them")
    void levelDownRemovesAndReverses() {
        String aria = create(Map.of("level", 3)).get("id").asText();
        JsonNode owed = get(aria);
        JsonNode champion = applied(aria, Map.of("choiceId", id(owed, "SUBCLASS", 3), "subclassId", championId));
        assertThat(features(champion)).contains("Improved Critical");
        levelUp(aria, fighterId);

        JsonNode third = levelDown(aria);
        assertThat(described(third)).containsExactly("1 FIGHTING_STYLE");
        assertThat(third.get("subclass").asText()).isEqualTo("Champion");

        JsonNode second = levelDown(aria);
        assertThat(second.get("subclass").isNull()).isTrue();
        assertThat(second.get("multiclassEntries").get(0).path("subclassId").isTextual()).isFalse();
        assertThat(features(second)).doesNotContain("Improved Critical");
        assertThat(described(second)).containsExactly("1 FIGHTING_STYLE");

        // Regaining the level owes the subclass again
        assertThat(described(levelUp(aria, fighterId))).containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS");
    }

    @Test
    @DisplayName("A Variant Human's feat is a choice on the sheet; taken, Lucky gets its pool and Alert its initiative, and the ASI can't take Lucky again")
    void variantHumanFeat() {
        JsonNode created = create(Map.of("raceId", variantHumanId, "level", 4));
        String aria = created.get("id").asText();
        JsonNode feat = choice(created, "FEAT", 1);
        assertThat(feat.get("source").asText()).isEqualTo("RACE");
        assertThat(feat.has("options")).isFalse();

        JsonNode lucky = applied(aria, Map.of("choiceId", feat.get("id").asText(),
                "feat", Map.of("type", "feat", "featId", luckyId)));

        assertThat(lucky.get("resourcePools").toString()).contains("\"poolId\":\"feat:lucky\"");
        JsonNode pool = null;
        for (JsonNode p : lucky.get("resourcePools")) {
            if ("feat:lucky".equals(p.get("poolId").asText())) pool = p;
        }
        assertThat(pool).isNotNull();
        assertThat(pool.get("maxUses").asInt()).isEqualTo(3);
        assertThat(lucky.get("levelHistory").get(0).get("choices").get("feats").get(0).get("featName").asText())
                .isEqualTo("Lucky");
        assertThat(error(apply(aria, Map.of("choiceId", id(lucky, "ASI", 4), "asi", Map.of("type", "feat",
                "featId", luckyId))), HttpStatus.BAD_REQUEST))
                .isEqualTo("Aria already has Lucky, which can't be taken again");
        JsonNode alert = applied(aria, Map.of("choiceId", id(lucky, "ASI", 4), "asi", Map.of("type", "feat",
                "featId", alertId)));
        assertThat(alert.get("initiativeBonus").asInt()).isEqualTo(2 + 5);

        // The pool survives a long rest and a reload, as any pool does
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + aria + "/pools/spend",
                Map.of("poolId", "feat:lucky"), player.accessToken()));
        assertThat(get(aria).get("resourcePools").toString()).contains("\"poolId\":\"feat:lucky\"");
    }

    @Test
    @DisplayName("The DM sees a character's pending choices in the campaign's list, but can't make one")
    void theDmSeesButCantChoose() {
        String aria = create(Map.of("level", 4)).get("id").asText();
        String asi = id(get(aria), "ASI", 4);

        JsonNode list = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/campaign/" + campaignId,
                null, dm.accessToken()));
        assertThat(list.get(0).get("pendingChoices")).hasSize(3);
        var asDm = exchange(HttpMethod.POST, "/api/characters/" + aria + "/apply-choices", Map.of("choiceId", asi,
                "asi", Map.of("type", "ability", "increases", List.of(Map.of("ability", "strength", "bonus", 2)))),
                dm.accessToken());
        assertThat(asDm.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(aria).get("pendingChoices")).hasSize(3);
    }
}
