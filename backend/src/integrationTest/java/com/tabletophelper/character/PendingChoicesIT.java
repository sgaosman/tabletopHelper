package com.tabletophelper.character;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.maintenance.BackfillPendingChoicesJob;
import com.tabletophelper.maintenance.MaintenanceReport;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatEffects;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired private BackfillPendingChoicesJob backfillPendingChoicesJob;
    @Autowired private PlatformTransactionManager transactionManager;

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
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
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
                .effects(FeatEffects.parse("{\"resource\": {\"name\": \"Luck Points\", \"maxUses\": 3, \"resetOn\": \"longRest\"}}"))
                .build()).getId();
        alertId = featRepository.save(Feat.builder().name("Alert").source("PHB").description("Always on the lookout")
                .effects(FeatEffects.parse("{\"initiativeBonus\": 5}")).build()).getId();
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
        return expect(status, response).get("error").asString();
    }

    // Each pending choice as its level, type and count, as "4 ASI" or "3 SPELLS×3"
    private static List<String> described(JsonNode character) {
        List<String> choices = new ArrayList<>();
        character.get("pendingChoices").forEach(c -> choices.add(c.get("characterLevel").asInt() + " "
                + c.get("type").asString() + (c.get("count").asInt() > 1 ? "×" + c.get("count").asInt() : "")));
        return choices;
    }

    private static JsonNode choice(JsonNode character, String type, int characterLevel) {
        for (JsonNode choice : character.get("pendingChoices")) {
            if (choice.get("type").asString().equals(type) && choice.get("characterLevel").asInt() == characterLevel) {
                return choice;
            }
        }
        throw new AssertionError("No pending " + type + " at level " + characterLevel + ": "
                + character.get("pendingChoices"));
    }

    private static String id(JsonNode character, String type, int characterLevel) {
        return choice(character, type, characterLevel).get("id").asString();
    }

    private static List<String> names(JsonNode character, String source) {
        List<String> names = new ArrayList<>();
        character.get("spellsKnown").forEach(s -> {
            if (source.equals(s.path("source").asString())) names.add(s.get("name").asString());
        });
        return names;
    }

    private static List<String> features(JsonNode character) {
        List<String> names = new ArrayList<>();
        character.get("features").forEach(f -> names.add(f.get("name").asString()));
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
        String aria = created.get("id").asString();

        assertThat(described(created)).containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS", "4 ASI");
        JsonNode subclass = choice(created, "SUBCLASS", 3);
        assertThat(subclass.get("source").asString()).isEqualTo("CREATION");
        assertThat(subclass.get("classId").asString()).isEqualTo(fighterId.toString());
        assertThat(subclass.get("options").toString()).isEqualTo("[\"Champion\",\"Eldritch Knight\"]");
        assertThat(choice(created, "FIGHTING_STYLE", 1).get("options").toString())
                .startsWith("[\"Archery\",\"Defense\",\"Dueling\"");

        // The player leaves every choice for later and levels up: Fighter 6 owes another ASI
        JsonNode sixth = levelUp(aria, fighterId);
        assertThat(described(sixth)).containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS", "4 ASI", "6 ASI");
        assertThat(choice(sixth, "ASI", 6).get("source").asString()).isEqualTo("LEVEL_UP");
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
        String aria = created.get("id").asString();
        // The wizard gives the 1st level's four cantrips and two spells; levels 2 to 5 each owe a spell, and the 3rd
        // two Metamagic options (PHB p.101-102)
        assertThat(described(created)).containsExactly("2 SPELLS", "3 METAMAGIC×2", "3 SPELLS", "4 ASI", "4 CANTRIPS",
                "4 SPELLS", "5 SPELLS");
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
        assertThat(third.get("replaced").get("name").asString()).isEqualTo("Shield");
        assertThat(third.get("replacement").asString()).isEqualTo("Sleep");
        assertThat(described(fifth)).containsExactly("3 METAMAGIC×2", "4 ASI", "4 SPELLS");
    }

    // ── Making them ──────────────────────────────────────────────

    @Test
    @DisplayName("A subclass chosen on the sheet is recorded on its level, with its features, and brings the choices it gives")
    void subclassAndTheChoicesItBrings() {
        String aria = create(Map.of("level", 4)).get("id").asString();
        JsonNode owed = get(aria);

        JsonNode knight = applied(aria, Map.of("choiceId", id(owed, "SUBCLASS", 3), "subclassId", eldritchKnightId));

        assertThat(knight.get("subclass").asString()).isEqualTo("Eldritch Knight");
        assertThat(knight.get("levelHistory").get(2).get("choices").get("subclass").get("subclassName").asString())
                .isEqualTo("Eldritch Knight");
        assertThat(features(knight)).contains("Weapon Bond");
        assertThat(described(knight)).containsExactly("1 FIGHTING_STYLE", "3 CANTRIPS×2", "3 SPELLS×3", "4 ASI",
                "4 SPELLS");
        assertThat(knight.get("spellcasting").get(0).get("className").asString()).isEqualTo("Fighter");

        JsonNode style = applied(aria, Map.of("choiceId", id(knight, "FIGHTING_STYLE", 1), "fightingStyle", "Defense"));
        assertThat(features(style)).contains("Fighting Style: Defense");
        JsonNode improved = applied(aria, Map.of("choiceId", id(style, "ASI", 4), "asi", Map.of("type", "ability",
                "increases", List.of(Map.of("ability", "dexterity", "bonus", 2)))));
        assertThat(improved.get("dexterity").asInt()).isEqualTo(16);
        assertThat(improved.get("initiativeBonus").asInt()).isEqualTo(3);
        assertThat(improved.get("levelHistory").get(3).get("choices").get("asi").get("type").asString())
                .isEqualTo("ability");
    }

    @Test
    @DisplayName("A choice the character doesn't have, or one made already, is a 409; an answer that doesn't fit is a 400; neither changes anything")
    void refusals() {
        JsonNode created = create(Map.of("level", 4));
        String aria = created.get("id").asString();
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
        String aria = create(Map.of("level", 4)).get("id").asString();
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
        String aria = create(Map.of("level", 3)).get("id").asString();
        JsonNode owed = get(aria);
        JsonNode champion = applied(aria, Map.of("choiceId", id(owed, "SUBCLASS", 3), "subclassId", championId));
        assertThat(features(champion)).contains("Improved Critical");
        levelUp(aria, fighterId);

        JsonNode third = levelDown(aria);
        assertThat(described(third)).containsExactly("1 FIGHTING_STYLE");
        assertThat(third.get("subclass").asString()).isEqualTo("Champion");

        JsonNode second = levelDown(aria);
        assertThat(second.get("subclass").isNull()).isTrue();
        assertThat(second.get("multiclassEntries").get(0).path("subclassId").isString()).isFalse();
        assertThat(features(second)).doesNotContain("Improved Critical");
        assertThat(described(second)).containsExactly("1 FIGHTING_STYLE");

        // Regaining the level owes the subclass again
        assertThat(described(levelUp(aria, fighterId))).containsExactly("1 FIGHTING_STYLE", "3 SUBCLASS");
    }

    @Test
    @DisplayName("A Variant Human's feat is a choice on the sheet; taken, Lucky gets its pool and Alert its initiative, and the ASI can't take Lucky again")
    void variantHumanFeat() {
        JsonNode created = create(Map.of("raceId", variantHumanId, "level", 4));
        String aria = created.get("id").asString();
        JsonNode feat = choice(created, "FEAT", 1);
        assertThat(feat.get("source").asString()).isEqualTo("RACE");
        assertThat(feat.has("options")).isFalse();

        JsonNode lucky = applied(aria, Map.of("choiceId", feat.get("id").asString(),
                "feat", Map.of("type", "feat", "featId", luckyId)));

        assertThat(lucky.get("resourcePools").toString()).contains("\"poolId\":\"feat:lucky\"");
        JsonNode pool = null;
        for (JsonNode p : lucky.get("resourcePools")) {
            if ("feat:lucky".equals(p.get("poolId").asString())) pool = p;
        }
        assertThat(pool).isNotNull();
        assertThat(pool.get("maxUses").asInt()).isEqualTo(3);
        assertThat(lucky.get("levelHistory").get(0).get("choices").get("feats").get(0).get("featName").asString())
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
        String aria = create(Map.of("level", 4)).get("id").asString();
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
    @Test
    @DisplayName("A Battle Master's artisan's tools (PHB p.73) are a Pending Choice the subclass brings, stored with "
            + "its options, made by name, recorded on Fighter 3 and taken away by levelling down; a tool not offered "
            + "is a 400")
    void subclassBonusProficiencyChoice() {
        CharacterClass fighter = characterClassRepository.findById(fighterId).orElseThrow();
        UUID battleMasterId = subclassRepository.save(Subclass.builder().name("Battle Master").source("PHB")
                .characterClass(fighter)
                .features("[{\"level\":3,\"name\":\"Student of War\",\"description\":\"Artisan's tools\"}]")
                .additionalProficiencies("{\"choices\":[{\"count\":1,\"tools\":[\"Smith's Tools\","
                        + "\"Tinker's Tools\"]}]}")
                .build()).getId();
        JsonNode created = create(Map.of("level", 3, "toolProficiencies", List.of("Dice Set")));
        String aria = created.get("id").asString();

        JsonNode chosen = applied(aria, Map.of("choiceId", id(created, "SUBCLASS", 3), "subclassId", battleMasterId));
        JsonNode tools = choice(chosen, "PROFICIENCY", 3);
        assertThat(tools.get("classLevel").asInt()).isEqualTo(3);
        assertThat(tools.get("proficiencyOptions").toString())
                .isEqualTo("{\"tools\":[\"Smith's Tools\",\"Tinker's Tools\"]}");
        assertThat(get(aria).get("pendingChoices").toString()).contains("\"proficiencyOptions\"");

        assertThat(error(apply(aria, Map.of("choiceId", tools.get("id").asString(), "proficiencies",
                List.of("Cook's Utensils"))), HttpStatus.BAD_REQUEST))
                .isEqualTo("Choose from Smith's Tools, Tinker's Tools, not Cook's Utensils");
        JsonNode made = applied(aria, Map.of("choiceId", tools.get("id").asString(), "proficiencies",
                List.of("smith's tools")));

        assertThat(made.get("toolProficiencies").toString()).isEqualTo("[\"Dice Set\",\"Smith's Tools\"]");
        JsonNode third = made.get("levelHistory").get(2);
        assertThat(third.get("proficienciesGained").toString()).isEqualTo("{\"tools\":[\"Smith's Tools\"]}");
        assertThat(third.get("choices").get("proficiencies").toString()).isEqualTo("[\"Smith's Tools\"]");
        assertThat(described(made)).doesNotContain("3 PROFICIENCY");

        JsonNode second = levelDown(aria);
        assertThat(second.get("toolProficiencies").toString()).isEqualTo("[\"Dice Set\"]");
        assertThat(second.path("subclass").isMissingNode() || second.path("subclass").isNull()).isTrue();
    }

    // ── Conditional grants and kensei weapons ────────────────────

    // A Barbarian with the Path of the Giant: Giant, or another language when the character knows it (BGG p.11)
    private UUID insertGiantBarbarian() {
        CharacterClass barbarian = characterClassRepository.save(CharacterClass.builder().name("Barbarian")
                .source("PHB").hitDice(12).subclassLevel(3).isSpellcaster(false).isPactMagic(false).build());
        subclassRepository.save(Subclass.builder().name("Path of the Giant").source("BGG").characterClass(barbarian)
                .features("[{\"level\":3,\"name\":\"Giant Power\",\"description\":\"Giant, and a cantrip\"}]")
                .additionalProficiencies("{\"conditional\":[{\"languages\":[\"Giant\"],\"otherwise\":{\"count\":1,"
                        + "\"languages\":[\"Common\",\"Elvish\",\"Giant\",\"Sylvan\"]}}]}")
                .build());
        return barbarian.getId();
    }

    // A Monk with the Way of the Kensei, its weapon lists shortened (XGE p.34)
    private UUID[] insertKenseiMonk() {
        CharacterClass monk = characterClassRepository.save(CharacterClass.builder().name("Monk").source("PHB")
                .hitDice(8).subclassLevel(3).isSpellcaster(false).isPactMagic(false)
                .features("[{\"level\":4,\"name\":\"Ability Score Improvement\",\"description\":\"Improve\"}]")
                .build());
        String later = "\"count\":1,\"weapons\":[\"Shortsword\",\"Longsword\",\"Shortbow\",\"Longbow\"],"
                + "\"kensei\":true}";
        UUID kensei = subclassRepository.save(Subclass.builder().name("Way of the Kensei").source("XGE")
                .characterClass(monk)
                .features("[{\"level\":3,\"name\":\"Path of the Kensei\",\"description\":\"Kensei weapons\"}]")
                .additionalProficiencies("{\"choices\":["
                        + "{\"count\":1,\"tools\":[\"Calligrapher's Supplies\",\"Painter's Supplies\"]},"
                        + "{\"count\":1,\"weapons\":[\"Shortsword\",\"Longsword\"],\"kensei\":true},"
                        + "{\"count\":1,\"weapons\":[\"Shortbow\",\"Longbow\"],\"kensei\":true},"
                        + "{\"level\":6," + later + ",{\"level\":11," + later + ",{\"level\":17," + later + "]}")
                .build()).getId();
        return new UUID[]{monk.getId(), kensei};
    }

    private static String kenseiChoiceId(JsonNode character, int characterLevel, String option) {
        for (JsonNode choice : character.get("pendingChoices")) {
            if (choice.path("kenseiWeapons").asBoolean(false) && choice.get("characterLevel").asInt() == characterLevel
                    && choice.get("proficiencyOptions").get("weapons").toString().contains("\"" + option + "\"")) {
                return choice.get("id").asString();
            }
        }
        throw new AssertionError("No kensei choice at level " + characterLevel + " offering " + option);
    }

    @Test
    @DisplayName("A Path of the Giant barbarian learns Giant when it doesn't know it, and owes one other language "
            + "on Barbarian 3 when it does (BGG p.11), which it makes by name")
    void conditionalGrant() {
        UUID barbarianId = insertGiantBarbarian();
        UUID giantId = subclassRepository.findByCharacterClassIdOrderByNameAsc(barbarianId).getFirst().getId();

        JsonNode lacking = create(Map.of("classId", barbarianId, "level", 3, "subclassId", giantId,
                "languageProficiencies", List.of("Common")));
        assertThat(lacking.get("languageProficiencies").toString()).isEqualTo("[\"Common\",\"Giant\"]");
        assertThat(lacking.get("levelHistory").get(2).get("proficienciesGained").toString())
                .isEqualTo("{\"languages\":[\"Giant\"]}");
        assertThat(described(lacking)).doesNotContain("3 PROFICIENCY");

        JsonNode knowing = create(Map.of("classId", barbarianId, "level", 3, "subclassId", giantId,
                "languageProficiencies", List.of("Common", "Giant")));
        String id = knowing.get("id").asString();
        JsonNode other = choice(knowing, "PROFICIENCY", 3);
        assertThat(other.get("proficiencyOptions").toString())
                .isEqualTo("{\"languages\":[\"Common\",\"Elvish\",\"Giant\",\"Sylvan\"]}");
        assertThat(error(apply(id, Map.of("choiceId", other.get("id").asString(), "proficiencies",
                List.of("Giant"))), HttpStatus.BAD_REQUEST)).isEqualTo("Aria already has Giant");
        JsonNode made = applied(id, Map.of("choiceId", other.get("id").asString(), "proficiencies",
                List.of("Sylvan")));
        assertThat(made.get("languageProficiencies").toString()).isEqualTo("[\"Common\",\"Giant\",\"Sylvan\"]");
        assertThat(made.get("levelHistory").get(2).get("choices").get("proficiencies").toString())
                .isEqualTo("[\"Sylvan\"]");
    }

    @Test
    @DisplayName("A kensei chooses a melee and a ranged kensei weapon on Monk 3 and another on Monk 6 (XGE p.34), "
            + "gaining proficiency only with those it lacked; the sheet lists them, and levelling down takes the "
            + "Monk 6 weapon away")
    void kenseiWeapons() {
        UUID[] ids = insertKenseiMonk();
        JsonNode created = create(Map.of("classId", ids[0], "level", 3, "subclassId", ids[1],
                "weaponProficiencies", List.of("Simple", "Shortswords")));
        String aria = created.get("id").asString();
        assertThat(described(created)).containsSubsequence("3 PROFICIENCY", "3 PROFICIENCY", "3 PROFICIENCY");
        assertThat(created.path("kenseiWeapons")).isEmpty();

        applied(aria, Map.of("choiceId", kenseiChoiceId(created, 3, "Shortsword"), "proficiencies",
                List.of("Shortsword")));
        JsonNode both = applied(aria, Map.of("choiceId", kenseiChoiceId(created, 3, "Longbow"), "proficiencies",
                List.of("Longbow")));
        assertThat(both.get("kenseiWeapons").toString()).isEqualTo("[\"Shortsword\",\"Longbow\"]");
        assertThat(both.get("weaponProficiencies").toString()).isEqualTo("[\"Simple\",\"Shortswords\",\"Longbow\"]");
        JsonNode third = both.get("levelHistory").get(2);
        assertThat(third.get("choices").get("kenseiWeapons").toString()).isEqualTo("[\"Shortsword\",\"Longbow\"]");
        assertThat(third.get("proficienciesGained").toString()).isEqualTo("{\"weapons\":[\"Longbow\"]}");

        levelUp(aria, ids[0]);
        levelUp(aria, ids[0]);
        JsonNode sixth = levelUp(aria, ids[0]);
        String weapon = kenseiChoiceId(sixth, 6, "Longsword");
        assertThat(error(apply(aria, Map.of("choiceId", weapon, "proficiencies", List.of("Longbow"))),
                HttpStatus.BAD_REQUEST)).isEqualTo("Aria already has the kensei weapon Longbow");
        JsonNode made = applied(aria, Map.of("choiceId", weapon, "proficiencies", List.of("Longsword")));
        assertThat(made.get("kenseiWeapons").toString()).isEqualTo("[\"Shortsword\",\"Longbow\",\"Longsword\"]");

        JsonNode fifth = levelDown(aria);
        assertThat(fifth.get("kenseiWeapons").toString()).isEqualTo("[\"Shortsword\",\"Longbow\"]");
        assertThat(fifth.get("weaponProficiencies").toString()).doesNotContain("Longsword");
    }

    @Test
    @DisplayName("backfill-pending-choices owes a kensei who made its tool choice before kensei weapons were owed its "
            + "weapons on Monk 3 and 6, and a Giant barbarian who knew Giant its other language; a second run adds "
            + "nothing")
    void backfillOwesTheNewChoices() {
        UUID[] ids = insertKenseiMonk();
        JsonNode monk = create(Map.of("classId", ids[0], "level", 6, "subclassId", ids[1]));
        String kensei = monk.get("id").asString();
        String tool = null;
        for (JsonNode choice : monk.get("pendingChoices")) {
            if (choice.get("type").asString().equals("PROFICIENCY") && choice.path("kenseiWeapons").isMissingNode()) {
                tool = choice.get("id").asString();
            }
        }
        applied(kensei, Map.of("choiceId", tool, "proficiencies", List.of("Painter's Supplies")));
        UUID barbarianId = insertGiantBarbarian();
        UUID giantId = subclassRepository.findByCharacterClassIdOrderByNameAsc(barbarianId).getFirst().getId();
        String giant = create(Map.of("classId", barbarianId, "level", 3, "subclassId", giantId,
                "languageProficiencies", List.of("Common", "Giant"))).get("id").asString();
        jdbcTemplate.update("UPDATE player_characters SET pending_choices = '[]'::jsonb");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        MaintenanceReport applied = transaction.execute(status -> backfillPendingChoicesJob.run(true));

        assertThat(applied.changes()).hasSize(2);
        assertThat(get(kensei).get("pendingChoices")).filteredOn(c -> c.get("type").asString().equals("PROFICIENCY"))
                .extracting(c -> c.get("characterLevel").asInt() + " " + c.path("kenseiWeapons").asBoolean(false))
                .containsExactly("3 true", "3 true", "6 true");
        assertThat(described(get(giant))).containsExactly("3 PROFICIENCY");
        assertThat(transaction.execute(status -> backfillPendingChoicesJob.run(false)).changes()).isEmpty();
    }
}
