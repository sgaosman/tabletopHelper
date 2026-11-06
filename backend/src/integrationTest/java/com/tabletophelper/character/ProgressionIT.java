package com.tabletophelper.character;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.maintenance.MaintenanceReport;
import com.tabletophelper.maintenance.RecomputeCharactersJob;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.reference.PoolLevelTable;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Character progression end to end on PostgreSQL (D167): a Fighter 3 is created, dips into Wizard, takes a CON
 * improvement at Fighter 4 and Tough at Fighter 6, and levels back down to Fighter 3, with every Derived Stat checked
 * on the way and the sheet back as it began. Since R6 workstream F each level's choices are Pending Choices, made by
 * their IDs (D159). The recompute-characters job then reports and repairs a stored stat.
 */
class ProgressionIT extends IntegrationTest {

    @Autowired private FeatRepository featRepository;
    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;
    @Autowired private RecomputeCharactersJob recomputeCharactersJob;
    @Autowired private PlatformTransactionManager transactionManager;

    private RegisteredUser player;
    private UUID fighterId;
    private UUID wizardId;
    private UUID toughId;

    @BeforeEach
    void insertReferenceData() {
        player = register("aria");
        CharacterClass fighter = characterClassRepository.save(CharacterClass.builder().name("Fighter").source("PHB")
                .hitDice(10).subclassLevel(3).isSpellcaster(false).isPactMagic(false)
                .features("[{\"level\":1,\"name\":\"Second Wind\",\"description\":\"Heal\"},"
                        + "{\"level\":2,\"name\":\"Action Surge\",\"description\":\"Act again\"},"
                        + "{\"level\":4,\"name\":\"Ability Score Improvement\",\"description\":\"Improve\"},"
                        + "{\"level\":6,\"name\":\"Ability Score Improvement\",\"description\":\"Improve\"}]")
                .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},"
                        + "{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]")
                .multiclassProficiencies("{\"armor\":[\"Light\",\"Medium\",\"Shield\"],\"weapons\":[\"Simple\",\"Martial\"]}")
                .build());
        fighterId = fighter.getId();
        wizardId = characterClassRepository.save(CharacterClass.builder().name("Wizard").source("PHB").hitDice(6)
                .subclassLevel(2).isSpellcaster(true).isPactMagic(false).spellcastingAbility("INT")
                .features("[{\"level\":1,\"name\":\"Arcane Recovery\",\"description\":\"Recover slots\"}]")
                .multiclassRequirements("[{\"ability\":\"INT\",\"minimum\":13}]")
                .build()).getId();
        toughId = featRepository.save(Feat.builder().name("Tough").source("PHB").description("Hit points")
                .effects("{\"hpPerLevel\": 2}").build()).getId();
        ResourcePoolDefinition surge = poolDefinitionRepository.save(ResourcePoolDefinition.builder()
                .poolId("class:fighter-action-surge").displayName("Action Surge").sourceType("CLASS")
                .maxUsesFormula("1").resetOn("shortRest").build());
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(fighter).minLevel(2)
                .poolDefinition(surge).build());
    }

    private JsonNode get(String id) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + id, null, player.accessToken()));
    }

    private JsonNode levelUp(String id, UUID classId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + id + "/level-up",
                Map.of("classId", classId), player.accessToken()));
    }

    private JsonNode levelDown(String id) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + id + "/level-down", null,
                player.accessToken()));
    }

    private JsonNode applyChoices(String id, Map<String, Object> choices) {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + id + "/apply-choices", choices,
                player.accessToken()));
    }

    // The ID of the character's pending choice of the type at the character level
    private static String choiceId(JsonNode character, String type, int characterLevel) {
        for (JsonNode choice : character.get("pendingChoices")) {
            if (choice.get("type").asString().equals(type) && choice.get("characterLevel").asInt() == characterLevel) {
                return choice.get("id").asString();
            }
        }
        throw new AssertionError("No pending " + type + " at level " + characterLevel + ": "
                + character.get("pendingChoices"));
    }

    // The stats CharacterStats works out, and what levelling changes, in one comparable shape
    private static Map<String, Object> stats(JsonNode c) {
        List<Integer> hitDieResults = new ArrayList<>();
        c.get("levelHistory").forEach(entry -> hitDieResults.add(entry.get("hitDieResult").asInt()));
        List<String> pools = new ArrayList<>();
        c.get("resourcePools").forEach(pool -> pools.add(pool.get("poolId").asString()));
        List<String> features = new ArrayList<>();
        c.get("features").forEach(feature -> features.add(feature.get("name").asString()));
        return Map.ofEntries(
                Map.entry("level", c.get("level").asInt()),
                Map.entry("hp", c.get("hpCurrent").asInt() + "/" + c.get("hpMax").asInt()),
                Map.entry("proficiencyBonus", c.get("proficiencyBonus").asInt()),
                Map.entry("initiative", c.get("initiativeBonus").asInt()),
                Map.entry("speed", c.get("speed").asInt()),
                Map.entry("armourClass", c.get("armourClass").asInt()),
                Map.entry("constitution", c.get("constitution").asInt()),
                Map.entry("hitDice", c.get("hitDice").toString()),
                Map.entry("spellSlots", String.valueOf(c.get("spellSlots"))),
                Map.entry("spellSaveDc", String.valueOf(c.get("spellSaveDc"))),
                Map.entry("spellcasting", c.get("spellcasting").toString()),
                Map.entry("hitDieResults", hitDieResults),
                Map.entry("features", features),
                Map.entry("pools", pools),
                Map.entry("armor", String.valueOf(c.get("armorProficiencies"))));
    }

    @Test
    @DisplayName("A Fighter 3 dips into Wizard, takes CON at Fighter 4 and Tough at Fighter 6, and levels back to Fighter 3 exactly as it was")
    void progression() {
        JsonNode created = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Aria", "raceId", insertRace("Human"), "classId", fighterId, "level", 3,
                "strength", 16, "dexterity", 14, "constitution", 14, "intelligence", 14, "wisdom", 10,
                "charisma", 10), player.accessToken()));
        String id = created.get("id").asString();
        Map<String, Object> fighter3 = stats(created);

        // Fighter 3 with CON 14: 10+2, 6+2, 6+2
        assertThat(fighter3).containsEntry("hp", "28/28").containsEntry("proficiencyBonus", 2)
                .containsEntry("initiative", 2).containsEntry("speed", 30).containsEntry("armourClass", 12)
                .containsEntry("hitDieResults", List.of(10, 6, 6))
                .containsEntry("pools", List.of("class:fighter-action-surge"))
                .containsEntry("spellSlots", "null").containsEntry("spellSaveDc", "null");

        // The Wizard dip: the d6's fixed 4 + 2, Wizard slots and numbers, and Hit Dice of both kinds
        JsonNode dip = levelUp(id, wizardId);
        JsonNode wizard4 = dip.get("character");
        assertThat(wizard4.get("hpMax").asInt()).isEqualTo(34);
        assertThat(wizard4.get("levelHistory").get(3).get("hitDieResult").asInt()).isEqualTo(4);
        assertThat(wizard4.get("spellSlots").get("1").get("max").asInt()).isEqualTo(2);
        assertThat(wizard4.get("hitDice").get("Wizard").get("total").asInt()).isEqualTo(1);
        assertThat(wizard4.get("spellcasting").get(0).get("className").asString()).isEqualTo("Wizard");
        assertThat(wizard4.get("spellcasting").get(0).get("spellSaveDc").asInt()).isEqualTo(12);
        assertThat(wizard4.get("spellSaveDc").asInt()).isEqualTo(12);
        // The dip's 1st level owes three cantrips and six spellbook spells of 1st level
        JsonNode spellbook = wizard4.get("pendingChoices").get(wizard4.get("pendingChoices").size() - 1);
        assertThat(spellbook.get("type").asString()).isEqualTo("SPELLS");
        assertThat(spellbook.get("characterLevel").asInt()).isEqualTo(4);
        assertThat(spellbook.get("count").asInt()).isEqualTo(6);
        assertThat(spellbook.get("maxSpellLevel").asInt()).isEqualTo(1);

        // Fighter 4 brings an ASI; one past the rules is refused, and +2 CON counts for all five levels
        String asi = choiceId(levelUp(id, fighterId).get("character"), "ASI", 5);
        var tooHigh = exchange(HttpMethod.POST, "/api/characters/" + id + "/apply-choices", Map.of("choiceId", asi,
                "asi", Map.of("type", "ability", "increases", List.of(Map.of("ability", "strength", "bonus", 3)))),
                player.accessToken());
        assertThat(tooHigh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode improved = applyChoices(id, Map.of("choiceId", asi, "asi", Map.of("type", "ability",
                "increases", List.of(Map.of("ability", "constitution", "bonus", 2)))));
        assertThat(improved.get("constitution").asInt()).isEqualTo(16);
        // 13, 9, 9, then the Wizard's 4 + 3 and Fighter 4's 6 + 3
        assertThat(improved.get("hpMax").asInt()).isEqualTo(47);
        assertThat(improved.get("hpCurrent").asInt()).isEqualTo(47);
        assertThat(improved.get("proficiencyBonus").asInt()).isEqualTo(3);
        assertThat(improved.get("spellSaveDc").asInt()).isEqualTo(13);

        // Fighter 6 brings another, taken as Tough: 2 for each of the seven levels
        levelUp(id, fighterId);
        String sixth = choiceId(levelUp(id, fighterId).get("character"), "ASI", 7);
        JsonNode tough = applyChoices(id, Map.of("choiceId", sixth, "asi", Map.of("type", "feat", "featId", toughId)));
        assertThat(tough.get("hpMax").asInt()).isEqualTo(65 + 14);
        assertThat(tough.get("hpCurrent").asInt()).isEqualTo(79);
        assertThat(tough.get("levelHistory").get(6).get("choices").get("asi").get("featName").asString())
                .isEqualTo("Tough");

        // A taken ASI isn't owed again
        var again = exchange(HttpMethod.POST, "/api/characters/" + id + "/apply-choices", Map.of("choiceId", sixth,
                "asi", Map.of("type", "ability", "increases", List.of(Map.of("ability", "strength", "bonus", 2)))),
                player.accessToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Back down to Fighter 3: each level takes off only what it recorded, Tough with Fighter 6
        assertThat(levelDown(id).get("hpMax").asInt()).isEqualTo(56);
        levelDown(id);
        JsonNode fighter4AndWizard = levelDown(id);
        assertThat(fighter4AndWizard.get("constitution").asInt()).isEqualTo(14);
        assertThat(fighter4AndWizard.get("hpMax").asInt()).isEqualTo(34);
        JsonNode back = levelDown(id);

        assertThat(stats(back)).isEqualTo(fighter3);
        assertThat(stats(get(id))).isEqualTo(fighter3);
        assertThat(back.get("multiclassEntries")).hasSize(1);
        assertThat(back.get("spellcasting")).isEmpty();
    }

    @Test
    @DisplayName("recompute-characters reports a stored stat the rules disagree with, writes nothing on a dry run, and repairs it with apply")
    void recomputeCharacters() {
        UUID id = createCharacter(player.accessToken(), insertRace("Human"), fighterId);
        jdbcTemplate.update("UPDATE player_characters SET initiative_bonus = 0, hp_max = 30 WHERE id = ?", id);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        MaintenanceReport dryRun = transaction.execute(status -> recomputeCharactersJob.run(false));

        assertThat(dryRun.changes()).singleElement().asString()
                .contains("initiative: +0 → +2")
                .contains("hit points: 11/30 → 11/11");
        assertThat(jdbcTemplate.queryForObject("SELECT initiative_bonus FROM player_characters WHERE id = ?",
                Integer.class, id)).isZero();

        MaintenanceReport applied = transaction.execute(status -> recomputeCharactersJob.run(true));

        assertThat(applied.applied()).isTrue();
        assertThat(jdbcTemplate.queryForMap("SELECT initiative_bonus, hp_max, hp_current FROM player_characters "
                + "WHERE id = ?", id)).isEqualTo(Map.of("initiative_bonus", 2, "hp_max", 11, "hp_current", 11));
        assertThat(transaction.execute(status -> recomputeCharactersJob.run(false)).changes()).isEmpty();
    }

    @Test
    @DisplayName("A level-table pool (V27, D169): Rage follows the barbarian level, recompute-characters reports a stored copy the table disagrees with, and spending it at 20th is never refused")
    void levelTablePool() {
        CharacterClass barbarian = characterClassRepository.save(CharacterClass.builder().name("Barbarian")
                .source("PHB").hitDice(12).subclassLevel(3).isSpellcaster(false).isPactMagic(false)
                .features("[{\"level\":1,\"name\":\"Rage\",\"description\":\"Rage\"}]")
                .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13}]")
                .build());
        ResourcePoolDefinition rage = poolDefinitionRepository.save(ResourcePoolDefinition.builder()
                .poolId("class:barbarian-rage").displayName("Rage").sourceType("CLASS").maxUsesFormula("2")
                .resetOn("longRest").spendActionType("BONUS_ACTION")
                .levelTable(new PoolLevelTable("Barbarian", List.of(new PoolLevelTable.Row(3, "3", null, null, null),
                        new PoolLevelTable.Row(6, "4", null, null, null),
                        new PoolLevelTable.Row(20, "6", null, null, true))))
                .build());
        classFeaturePoolRepository.save(ClassFeaturePool.builder().characterClass(barbarian).minLevel(1)
                .poolDefinition(rage).build());
        // The JSONB column holds the table as typed, with no nulls
        assertThat(jdbcTemplate.queryForObject("SELECT level_table::text FROM resource_pool_definitions "
                + "WHERE pool_id = 'class:barbarian-rage'", String.class))
                .isEqualTo("{\"rows\": [{\"level\": 3, \"maxUses\": \"3\"}, {\"level\": 6, \"maxUses\": \"4\"}, "
                        + "{\"level\": 20, \"maxUses\": \"6\", \"unlimited\": true}], \"levelOf\": \"Barbarian\"}");
        assertThat(poolDefinitionRepository.findById("class:barbarian-rage").orElseThrow().getLevelTable())
                .isEqualTo(rage.getLevelTable());

        UUID race = insertRace("Human");
        JsonNode fifth = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Grog", "raceId", race, "classId", barbarian.getId(), "level", 5,
                "strength", 16, "dexterity", 14, "constitution", 14, "intelligence", 8, "wisdom", 10,
                "charisma", 10), player.accessToken()));
        String id = fifth.get("id").asString();
        JsonNode pool = fifth.get("resourcePools").get(0);
        assertThat(pool.get("poolId").asString()).isEqualTo("class:barbarian-rage");
        assertThat(pool.get("maxUses").asInt()).isEqualTo(3);
        assertThat(pool.get("currentUses").asInt()).isEqualTo(3);

        assertThat(levelUp(id, barbarian.getId()).get("character").get("resourcePools").get(0).get("maxUses").asInt()).isEqualTo(4);

        // A stored copy from before the table, as the local characters have, is what the job reports and repairs
        jdbcTemplate.update("UPDATE player_characters SET resource_pools = jsonb_set(jsonb_set(resource_pools, "
                + "'{0,maxUses}', '2'), '{0,currentUses}', '2') WHERE id = ?::uuid", id);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThat(transaction.execute(status -> recomputeCharactersJob.run(false)).changes()).singleElement()
                .asString().contains("pool class:barbarian-rage: 2/2 longRest → 4/4 longRest");
        transaction.execute(status -> recomputeCharactersJob.run(true));
        assertThat(get(id).get("resourcePools").get(0).get("maxUses").asInt()).isEqualTo(4);

        // At 20th the pool is unlimited: spends never run it down
        String twentieth = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", Map.of(
                "name", "Grog the Elder", "raceId", race, "classId", barbarian.getId(), "level", 20,
                "strength", 16, "dexterity", 14, "constitution", 14, "intelligence", 8, "wisdom", 10,
                "charisma", 10), player.accessToken())).get("id").asString();
        for (int i = 0; i < 8; i++) {
            expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + twentieth + "/pools/spend",
                    Map.of("poolId", "class:barbarian-rage"), player.accessToken()));
        }
        JsonNode unlimited = get(twentieth).get("resourcePools").get(0);
        assertThat(unlimited.get("metadata").get("unlimited").asBoolean()).isTrue();
        assertThat(unlimited.get("currentUses").asInt()).isEqualTo(6);
    }
}
