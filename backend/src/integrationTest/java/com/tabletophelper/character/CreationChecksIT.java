package com.tabletophelper.character;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.BackgroundRepository;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creation checks what the request gives the character over HTTP on PostgreSQL (D232, the open item of the security
 * review D221). A crafted request is a 400 that saves nothing: resistance to all 13 damage types, a 9th-level spell
 * at Wizard 1, Expertise beyond the levels'; and saving throws asked for are ignored for the first class's (PHB
 * p.164). The requests the creation wizard sends (CharacterCreateWizard.requests.test.tsx: a Fighter 1 soldier, a
 * Wizard 1 sage with its spellbook, a Fighter 3 / Wizard 2 with a replaced background skill; and a Dragonborn, a
 * Tiefling and a Rogue with Expertise) are created as before.
 */
class CreationChecksIT extends IntegrationTest {

    @Autowired private BackgroundRepository backgroundRepository;
    @Autowired private SpellRepository spellRepository;

    private RegisteredUser player;
    private UUID humanId;
    private UUID tieflingId;
    private UUID dragonbornId;
    private UUID fighterId;
    private UUID wizardId;
    private UUID rogueId;
    private UUID soldierId;
    private UUID sageId;
    private UUID criminalId;

    @BeforeEach
    void insertReferenceData() {
        player = register("aria");
        humanId = raceRepository.save(Race.builder().name("Human").source("PHB").speed("{\"walk\":30}")
                .resistances("[]").proficiencies("{\"languages\":[\"Common\"]}")
                .raceChoices("{\"languages\":[{\"anyStandard\":1}]}").build()).getId();
        tieflingId = raceRepository.save(Race.builder().name("Tiefling").source("PHB").speed("{\"walk\":30}")
                .resistances("[\"fire\"]")
                .additionalSpells("{\"fixedSpells\":[{\"name\":\"Thaumaturgy\",\"level\":0,\"atWill\":true},"
                        + "{\"name\":\"Hellish Rebuke\",\"level\":1,\"unlocksAtLevel\":3,\"usesPerLongRest\":1}]}")
                .build()).getId();
        dragonbornId = raceRepository.save(Race.builder().name("Dragonborn").source("PHB").speed("{\"walk\":30}")
                .resistances("[]").traits("[{\"name\":\"Draconic Ancestry\",\"description\":\"A dragon\"}]")
                .build()).getId();
        fighterId = characterClassRepository.save(CharacterClass.builder().name("Fighter").source("PHB").hitDice(10)
                .subclassLevel(3).isSpellcaster(false).isPactMagic(false)
                .savingThrowProficiencies("[\"STR\",\"CON\"]")
                .skillChoices("{\"count\":2,\"from\":[\"Acrobatics\",\"Animal Handling\",\"Athletics\",\"History\","
                        + "\"Insight\",\"Intimidation\",\"Perception\",\"Survival\"]}")
                .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},"
                        + "{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]")
                .multiclassProficiencies("{\"armor\":[\"Light\",\"Medium\",\"Shield\"]}")
                .build()).getId();
        wizardId = characterClassRepository.save(CharacterClass.builder().name("Wizard").source("PHB").hitDice(6)
                .subclassLevel(2).isSpellcaster(true).isPreparedCaster(true).isPactMagic(false)
                .spellcastingAbility("INT").savingThrowProficiencies("[\"INT\",\"WIS\"]")
                .skillChoices("{\"count\":2,\"from\":[\"Arcana\",\"History\",\"Insight\",\"Investigation\","
                        + "\"Medicine\",\"Religion\"]}")
                .multiclassRequirements("[{\"ability\":\"INT\",\"minimum\":13}]")
                .build()).getId();
        rogueId = characterClassRepository.save(CharacterClass.builder().name("Rogue").source("PHB").hitDice(8)
                .subclassLevel(3).isSpellcaster(false).isPactMagic(false)
                .savingThrowProficiencies("[\"DEX\",\"INT\"]")
                .skillChoices("{\"count\":4,\"from\":[\"Acrobatics\",\"Athletics\",\"Deception\",\"Insight\","
                        + "\"Intimidation\",\"Investigation\",\"Perception\",\"Performance\",\"Persuasion\","
                        + "\"Sleight Of Hand\",\"Stealth\"]}")
                .build()).getId();
        soldierId = backgroundRepository.save(Background.builder().name("Soldier").source("PHB")
                .skillProficiencies("[\"Athletics\",\"Intimidation\"]").build()).getId();
        sageId = backgroundRepository.save(Background.builder().name("Sage").source("PHB")
                .skillProficiencies("[\"Arcana\",\"History\"]").build()).getId();
        criminalId = backgroundRepository.save(Background.builder().name("Criminal").source("PHB")
                .skillProficiencies("[\"Deception\",\"Stealth\"]").build()).getId();
        for (String name : List.of("Fire Bolt", "Mage Hand", "Prestidigitation")) {
            insertSpell(name, 0, "[\"Wizard\"]");
        }
        for (String name : List.of("Burning Hands", "Detect Magic", "Mage Armor", "Magic Missile", "Shield",
                "Sleep")) {
            insertSpell(name, 1, "[\"Wizard\"]");
        }
        insertSpell("Wish", 9, "[\"Sorcerer\",\"Wizard\"]");
        insertSpell("Thaumaturgy", 0, "[\"Cleric\"]");
        insertSpell("Hellish Rebuke", 1, "[\"Warlock\"]");
    }

    private void insertSpell(String name, int level, String classes) {
        spellRepository.save(Spell.builder().name(name).level(level).school("Evocation").source("PHB")
                .classes(classes).build());
    }

    private Map<String, Object> request(UUID raceId, UUID classId, int level) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "Aria", "raceId", raceId, "classId", classId,
                "level", level, "strength", 15, "dexterity", 14, "constitution", 13, "intelligence", 16,
                "wisdom", 12, "charisma", 10));
        return body;
    }

    private static Map<String, Object> classSpell(String name, int level, String className) {
        return Map.of("name", name, "level", level, "source", "class:" + className);
    }

    private int characterCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM player_characters", Integer.class);
        return count != null ? count : 0;
    }

    private JsonNode refused(Map<String, Object> body) {
        int before = characterCount();
        JsonNode error = expect(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/characters", body,
                player.accessToken()));
        assertThat(characterCount()).as("a refused request saves nothing").isEqualTo(before);
        return error;
    }

    private JsonNode created(Map<String, Object> body) {
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    @Test
    @DisplayName("A crafted request is refused with a 400 naming what it can't have, and saves nothing")
    void craftedRequests() {
        Map<String, Object> resistant = request(humanId, fighterId, 1);
        resistant.put("damageResistances", List.of("acid", "bludgeoning", "cold", "fire", "force", "lightning",
                "necrotic", "piercing", "poison", "psychic", "radiant", "slashing", "thunder"));
        assertThat(refused(resistant).get("error").asString())
                .startsWith("Resistance to acid, bludgeoning, cold, fire, force, lightning, necrotic, piercing, "
                        + "poison, psychic, radiant, slashing, thunder isn't one the Human gives");

        Map<String, Object> wish = request(humanId, wizardId, 1);
        wish.put("spellsKnown", List.of(classSpell("Wish", 0, "Wizard")));
        assertThat(refused(wish).get("error").asString())
                .isEqualTo("A level 1 Wizard casts spells up to level 1, so can't take Wish (level 9)");

        Map<String, Object> skilled = request(humanId, fighterId, 1);
        skilled.put("skillProficiencies", List.of("Athletics", "Perception", "Stealth", "Arcana"));
        assertThat(refused(skilled).get("error").asString())
                .isEqualTo("Proficiency in Stealth, Arcana isn't one the race, classes and background give (2 skills "
                        + "in all)");

        Map<String, Object> expert = request(humanId, rogueId, 1);
        expert.put("skillProficiencies", List.of("Stealth", "Perception", "Acrobatics"));
        expert.put("skillExpertises", List.of("Stealth", "Perception", "Acrobatics"));
        assertThat(refused(expert).get("error").asString())
                .isEqualTo("A character created at level 1 with these classes has Expertise in 2 skills, not 3");

        Map<String, Object> raceSpell = request(humanId, fighterId, 1);
        raceSpell.put("spellsKnown", List.of(Map.of("name", "Wish", "level", 9, "source", "race:Human",
                "atWill", true)));
        assertThat(refused(raceSpell).get("error").asString())
                .isEqualTo("Wish isn't one of the Human's spells at level 1");
    }

    @Test
    @DisplayName("Every saving throw asked for gives only the first class's two (PHB p.164)")
    void savingThrowsDerived() {
        Map<String, Object> body = request(humanId, fighterId, 1);
        body.put("savingThrowProficiencies", List.of("STR", "DEX", "CON", "INT", "WIS", "CHA"));
        assertThat(strings(created(body).get("savingThrowProficiencies"))).containsExactly("STR", "CON");
    }

    @Test
    @DisplayName("The wizard's requests are created as before: a Fighter soldier, a Wizard sage with its spellbook, a "
            + "Fighter 3 / Wizard 2, a Dragonborn, a Tiefling and a Rogue with Expertise")
    void wizardRequests() {
        Map<String, Object> fighter = request(humanId, fighterId, 1);
        fighter.putAll(Map.of("backgroundId", soldierId, "savingThrowProficiencies", List.of("STR", "CON"),
                "skillProficiencies", List.of("Acrobatics", "Perception", "Athletics", "Intimidation")));
        JsonNode soldier = created(fighter);
        assertThat(strings(soldier.get("skillProficiencies")))
                .containsExactly("Acrobatics", "Perception", "Athletics", "Intimidation");

        Map<String, Object> wizard = request(humanId, wizardId, 1);
        wizard.putAll(Map.of("backgroundId", sageId, "savingThrowProficiencies", List.of("INT", "WIS"),
                "skillProficiencies", List.of("Investigation", "Medicine", "Arcana", "History"),
                "spellsKnown", List.of(classSpell("Fire Bolt", 0, "Wizard"), classSpell("Mage Hand", 0, "Wizard"),
                        classSpell("Prestidigitation", 0, "Wizard"), classSpell("Burning Hands", 1, "Wizard"),
                        classSpell("Detect Magic", 1, "Wizard"), classSpell("Mage Armor", 1, "Wizard"),
                        classSpell("Magic Missile", 1, "Wizard"), classSpell("Shield", 1, "Wizard"),
                        classSpell("Sleep", 1, "Wizard"))));
        JsonNode sage = created(wizard);
        assertThat(sage.get("spellsKnown")).hasSize(9);
        assertThat(sage.get("pendingChoices").toString()).doesNotContain("\"SPELLS\"", "\"CANTRIPS\"");

        Map<String, Object> multiclass = request(humanId, fighterId, 5);
        multiclass.putAll(Map.of("backgroundId", soldierId, "savingThrowProficiencies", List.of("STR", "CON"),
                "skillProficiencies", List.of("Athletics", "Survival", "Intimidation", "Stealth"),
                "multiclassClassEntries", List.of(Map.of("classId", fighterId, "level", 3),
                        Map.of("classId", wizardId, "level", 2))));
        JsonNode eldric = created(multiclass);
        assertThat(strings(eldric.get("savingThrowProficiencies"))).containsExactly("STR", "CON");
        assertThat(strings(eldric.get("skillProficiencies"))).contains("Stealth");

        Map<String, Object> dragonborn = request(dragonbornId, fighterId, 1);
        dragonborn.putAll(Map.of("draconicAncestry", "Red", "damageResistances", List.of("fire")));
        assertThat(strings(created(dragonborn).get("damageResistances"))).containsExactly("fire");

        Map<String, Object> tiefling = request(tieflingId, fighterId, 1);
        tiefling.putAll(Map.of("damageResistances", List.of("fire"), "spellsKnown", List.of(
                Map.of("name", "Thaumaturgy", "level", 0, "source", "race:Tiefling", "atWill", true))));
        JsonNode tief = created(tiefling);
        assertThat(strings(tief.get("damageResistances"))).containsExactly("fire");
        assertThat(tief.get("spellsKnown").get(0).get("atWill").asBoolean()).isTrue();

        Map<String, Object> rogue = request(humanId, rogueId, 1);
        rogue.putAll(Map.of("backgroundId", criminalId,
                "skillProficiencies", List.of("Acrobatics", "Perception", "Sleight of Hand", "Investigation",
                        "Deception", "Stealth"),
                "skillExpertises", List.of("Stealth", "Sleight of Hand")));
        JsonNode expert = created(rogue);
        assertThat(strings(expert.get("skillExpertises"))).containsExactly("Stealth", "Sleight of Hand");
        assertThat(expert.get("pendingChoices").toString()).doesNotContain("EXPERTISE");
    }

    @Test
    @DisplayName("A Rogue's Expertise may take thieves' tools, at creation and at Rogue 6, with proficiency in them; "
            + "without it the request is a 400 (PHB p.96)")
    void thievesToolsExpertise() {
        Map<String, Object> lacking = request(humanId, rogueId, 1);
        lacking.putAll(Map.of("skillProficiencies", List.of("Stealth", "Perception"),
                "skillExpertises", List.of("Stealth", "Thieves' Tools")));
        assertThat(refused(lacking).get("error").asString())
                .isEqualTo("Aria isn't proficient with thieves' tools, so can't have Expertise with them");

        Map<String, Object> first = request(humanId, rogueId, 1);
        first.putAll(Map.of("skillProficiencies", List.of("Stealth", "Perception"),
                "toolProficiencies", List.of("thieves' tools"),
                "skillExpertises", List.of("Stealth", "thieves' tools")));
        JsonNode rogue1 = created(first);
        assertThat(strings(rogue1.get("skillExpertises"))).containsExactly("Stealth", "Thieves' Tools");
        assertThat(strings(rogue1.get("levelHistory").get(0).get("choices").get("expertise")))
                .containsExactly("Thieves' Tools", "Stealth");
        assertThat(rogue1.get("pendingChoices").toString()).doesNotContain("EXPERTISE");

        Map<String, Object> body = request(humanId, rogueId, 6);
        body.putAll(Map.of("skillProficiencies", List.of("Stealth", "Perception", "Acrobatics"),
                "toolProficiencies", List.of("thieves' tools"),
                "skillExpertises", List.of("Stealth", "Perception")));
        JsonNode rogue = created(body);

        JsonNode sixth = null;
        for (JsonNode choice : rogue.get("pendingChoices")) {
            if ("EXPERTISE".equals(choice.get("type").asString())) sixth = choice;
        }
        assertThat(sixth).isNotNull();
        assertThat(sixth.get("characterLevel").asInt()).isEqualTo(6);
        String id = rogue.get("id").asString();
        JsonNode chosen = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + id + "/apply-choices",
                Map.of("choiceId", sixth.get("id").asString(),
                        "expertiseSkills", List.of("Thieves' Tools", "Acrobatics")),
                player.accessToken()));
        assertThat(strings(chosen.get("skillExpertises")))
                .containsExactly("Stealth", "Perception", "Thieves' Tools", "Acrobatics");
        assertThat(strings(chosen.get("levelHistory").get(5).get("choices").get("expertise")))
                .containsExactly("Thieves' Tools", "Acrobatics");
    }
}
