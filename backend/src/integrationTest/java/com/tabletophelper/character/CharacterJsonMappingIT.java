package com.tabletophelper.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.internal.UserRepository;
import com.tabletophelper.reference.Race;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The typed player character JSON columns (D168): each record is written to PostgreSQL in the shape V19 normalises
 * rows to and reads back equal, the deprecated columns are no longer written, and the API takes and returns the
 * records as JSON rather than JSON inside strings.
 */
class CharacterJsonMappingIT extends IntegrationTest {

    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private Race human;

    @BeforeEach
    void insertOwner() {
        owner = userRepository.save(User.builder().username("aria").email("aria@example.com").passwordHash("hash").build());
        human = raceRepository.findById(insertRace("Human")).orElseThrow();
    }

    private PlayerCharacter.PlayerCharacterBuilder character() {
        return PlayerCharacter.builder().user(owner).raceRef(human).name("Aria")
                .strength(16).dexterity(14).constitution(12).intelligence(10).wisdom(10).charisma(10)
                .hpMax(12).hpCurrent(12).armourClass(12);
    }

    private PlayerCharacter reload(UUID characterId) {
        return characterRepository.findById(characterId).orElseThrow();
    }

    private String column(String sql, UUID characterId) {
        return jdbcTemplate.queryForObject(sql, String.class, characterId);
    }

    @Test
    @DisplayName("Every typed column round-trips")
    void everyColumnRoundTrips() {
        UUID fighterId = UUID.randomUUID();
        UUID championId = UUID.randomUUID();
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        slots.put("1", new SpellSlot(4, 1));
        slots.put("pact_2", new SpellSlot(1, 0));
        AppliedFeatEffects tough = new AppliedFeatEffects(Map.of("con", 1), null, null, null, null, null, null, null,
                null, null, null, 2, null, null, new FeatResourceEntry("Tough", "Grit", 1, 1, "longRest"),
                List.of(new KnownSpell(null, "Shield", 1, "feat:Tough", null, null, null, 1, null)), "Tough");
        List<LevelHistoryEntry> history = List.of(
                new LevelHistoryEntry(1, fighterId, "Fighter", 1, 12,
                        List.of(new Feature("Second Wind", "Heal on a bonus action", "Fighter")), LevelChoices.NONE),
                new LevelHistoryEntry(4, fighterId, "Fighter", 4, 7, List.of(), new LevelChoices(
                        new AbilityScoreImprovement("feat", null, "Tough", UUID.randomUUID(), null, tough))));

        PlayerCharacter saved = characterRepository.save(character()
                .savingThrowProficiencies(List.of("STR", "CON"))
                .skillProficiencies(List.of("Athletics", "Perception"))
                .skillExpertises(List.of("Athletics"))
                .armorProficiencies(List.of("Light", "Medium", "Heavy", "Shields"))
                .weaponProficiencies(List.of("simple", "martial"))
                .toolProficiencies(List.of("Playing card set"))
                .languageProficiencies(List.of("Common", "Dwarvish"))
                .damageResistances(List.of("fire"))
                .damageImmunities(List.of("poison"))
                .conditionImmunities(List.of("charmed"))
                .features(List.of(new Feature("Second Wind", "Heal on a bonus action", "Fighter")))
                .spellsKnown(List.of(new KnownSpell(null, "Shield", 1, "class:Wizard", true, null, null, null, null)))
                .spellSlots(slots)
                .equipment(List.of(new InventoryItem("Longsword", 1, "Family heirloom", UUID.randomUUID(), true, false),
                        new InventoryItem("Rope, hempen (50 feet)", null, null, null, null, null)))
                .currency(new Currency(1, 2, 3, 4, 5))
                .racialAbilityBonuses(List.of(new AbilityBonus("STR", 2), new AbilityBonus(null, 1)))
                .multiclassEntries(List.of(new ClassEntry(fighterId, "Fighter", championId, "Champion", 4)))
                .hitDice(Map.of("Fighter", new HitDicePool(10, 4, 2)))
                .levelHistory(history)
                .featResources(List.of(new FeatResourceEntry("Lucky", "Luck Points", 3, 2, "longRest")))
                .resourcePools(List.of(new ResourcePoolEntry("class:fighter-second-wind", "Second Wind", "CLASS",
                        "Fighter", 1, "1", 0, "shortRest", null, null, "BONUS_ACTION", "heart", Map.of())))
                .build());

        PlayerCharacter reloaded = reload(saved.getId());

        assertThat(reloaded.getSavingThrowProficiencies()).isEqualTo(saved.getSavingThrowProficiencies());
        assertThat(reloaded.getSkillProficiencies()).isEqualTo(saved.getSkillProficiencies());
        assertThat(reloaded.getSkillExpertises()).isEqualTo(saved.getSkillExpertises());
        assertThat(reloaded.getArmorProficiencies()).isEqualTo(saved.getArmorProficiencies());
        assertThat(reloaded.getWeaponProficiencies()).isEqualTo(saved.getWeaponProficiencies());
        assertThat(reloaded.getToolProficiencies()).isEqualTo(saved.getToolProficiencies());
        assertThat(reloaded.getLanguageProficiencies()).isEqualTo(saved.getLanguageProficiencies());
        assertThat(reloaded.getDamageResistances()).isEqualTo(saved.getDamageResistances());
        assertThat(reloaded.getDamageImmunities()).isEqualTo(saved.getDamageImmunities());
        assertThat(reloaded.getConditionImmunities()).isEqualTo(saved.getConditionImmunities());
        assertThat(reloaded.getFeatures()).isEqualTo(saved.getFeatures());
        assertThat(reloaded.getSpellsKnown()).isEqualTo(saved.getSpellsKnown());
        assertThat(reloaded.getSpellSlots()).isEqualTo(slots);
        assertThat(reloaded.getEquipment()).isEqualTo(saved.getEquipment());
        assertThat(reloaded.getCurrency()).isEqualTo(new Currency(1, 2, 3, 4, 5));
        assertThat(reloaded.getRacialAbilityBonuses()).isEqualTo(saved.getRacialAbilityBonuses());
        assertThat(reloaded.getMulticlassEntries()).isEqualTo(saved.getMulticlassEntries());
        assertThat(reloaded.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 4, 2)));
        assertThat(reloaded.getLevelHistory()).isEqualTo(history);
        assertThat(reloaded.getFeatResources()).isEqualTo(saved.getFeatResources());
        assertThat(reloaded.getResourcePools()).isEqualTo(saved.getResourcePools());

        // The shapes V19 normalises rows to: slots {max, used}, Hit Dice by class name, and fields an entry
        // doesn't have left out
        UUID id = saved.getId();
        assertThat(column("SELECT spell_slots ->> 'pact_2' FROM player_characters WHERE id = ?", id))
                .isEqualTo("{\"max\": 1, \"used\": 0}");
        assertThat(column("SELECT hit_dice ->> 'Fighter' FROM player_characters WHERE id = ?", id))
                .isEqualTo("{\"total\": 4, \"dieSize\": 10, \"remaining\": 2}");
        assertThat(column("SELECT equipment ->> 1 FROM player_characters WHERE id = ?", id))
                .isEqualTo("{\"name\": \"Rope, hempen (50 feet)\"}");
        assertThat(column("SELECT level_history -> 0 ->> 'choices' FROM player_characters WHERE id = ?", id))
                .isEqualTo("{}");
        assertThat(column("SELECT level_history -> 1 -> 'choices' -> 'asi' -> 'appliedEffects' ->> 'hpPerLevel' "
                + "FROM player_characters WHERE id = ?", id)).isEqualTo("2");
        assertThat(column("SELECT (level_history -> 1 -> 'choices' -> 'asi' -> 'appliedEffects' -> 'speedBonus' "
                + "IS NULL)::text FROM player_characters WHERE id = ?", id)).isEqualTo("true");
    }

    @Test
    @DisplayName("A property the records don't have is ignored on load, as the API ignores it")
    void unknownPropertiesAreIgnored() {
        UUID id = characterRepository.save(character().build()).getId();
        jdbcTemplate.update("""
                UPDATE player_characters
                SET features = '[{"name": "Darkvision", "description": "See in the dark", "source": "Race", "uses": 2}]',
                    equipment = '[{"name": "Rope", "weight": 10}]',
                    currency = '{"cp": 1, "sp": 0, "ep": 0, "gp": 7, "pp": 0, "gems": 3}',
                    spells_known = '[{"name": "Shield", "level": 1, "colour": "blue"}]',
                    level_history = '[{"characterLevel": 1, "className": "Fighter", "classLevel": 1, "hpGained": 12,
                        "featuresGained": [], "hitDieResult": 10,
                        "choices": {"asi": {"type": "ability", "increases": [{"ability": "strength", "bonus": 2}]},
                                    "expertise": ["Stealth"]}}]'
                WHERE id = ?""", id);

        PlayerCharacter character = reload(id);

        assertThat(character.getFeatures()).containsExactly(new Feature("Darkvision", "See in the dark", "Race"));
        assertThat(character.getEquipment()).containsExactly(new InventoryItem("Rope", null, null, null, null, null));
        assertThat(character.getCurrency()).isEqualTo(new Currency(1, 0, 0, 7, 0));
        assertThat(character.getSpellsKnown())
                .containsExactly(new KnownSpell(null, "Shield", 1, null, null, null, null, null, null));
        assertThat(character.getLevelHistory()).singleElement().satisfies(entry -> {
            assertThat(entry.hpGained()).isEqualTo(12);
            assertThat(entry.choices().asi().increases()).containsExactly(new AbilityBonus("strength", 2));
        });
    }

    @Test
    @DisplayName("The deprecated hit dice, prepared, attuned and equipped columns are no longer written")
    void deprecatedColumnsAreLeftAlone() {
        UUID id = characterRepository.save(character().hitDice(Map.of("Fighter", new HitDicePool(10, 1, 1))).build())
                .getId();
        jdbcTemplate.update("""
                UPDATE player_characters
                SET hit_dice_map = '{"Fighter": {"total": 1, "remaining": 1, "faces": 10}}',
                    hit_dice_total = '1d10', hit_dice_remaining = '1d10', prepared_spells = '["Shield"]',
                    attuned_items = '["Cloak of Protection"]', equipped_items = '["Shield"]'
                WHERE id = ?""", id);

        PlayerCharacter character = reload(id);
        character.setHitDice(Map.of("Fighter", new HitDicePool(10, 2, 2)));
        characterRepository.save(character);

        assertThat(column("SELECT hit_dice ->> 'Fighter' FROM player_characters WHERE id = ?", id))
                .contains("\"total\": 2");
        assertThat(column("SELECT hit_dice_map::text FROM player_characters WHERE id = ?", id))
                .isEqualTo("{\"Fighter\": {\"faces\": 10, \"total\": 1, \"remaining\": 1}}");
        assertThat(column("SELECT hit_dice_total || ' ' || hit_dice_remaining FROM player_characters WHERE id = ?", id))
                .isEqualTo("1d10 1d10");
        assertThat(column("SELECT prepared_spells::text || attuned_items::text || equipped_items::text "
                + "FROM player_characters WHERE id = ?", id))
                .isEqualTo("[\"Shield\"][\"Cloak of Protection\"][\"Shield\"]");
    }

    @Test
    @DisplayName("The API takes and returns the records as JSON, and a JSON field sent as a string gets 400")
    void apiCarriesJson() {
        RegisteredUser player = register("bram");
        UUID classId = insertClass("Fighter", 10);
        Map<String, Object> create = new LinkedHashMap<>(Map.of(
                "name", "Bram", "raceId", insertRace("Elf"), "classId", classId,
                "strength", 16, "dexterity", 14, "constitution", 12,
                "intelligence", 10, "wisdom", 10, "charisma", 10));
        create.put("skillProficiencies", List.of("Athletics", "Perception"));
        create.put("racialAbilityBonuses", List.of(Map.of("ability", "DEX", "bonus", 2)));
        create.put("features", List.of(Map.of("name", "Fey Ancestry", "description", "Advantage against charm",
                "source", "Race")));
        create.put("hitDice", Map.of("Fighter", Map.of("dieSize", 10, "total", 1, "remaining", 1)));

        JsonNode created = expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", create,
                player.accessToken()));
        String id = created.get("id").asText();

        assertThat(created.get("skillProficiencies").isArray()).isTrue();
        assertThat(created.get("skillProficiencies").get(1).asText()).isEqualTo("Perception");
        assertThat(created.get("racialAbilityBonuses").get(0).get("bonus").asInt()).isEqualTo(2);
        assertThat(created.get("features").get(0).get("source").asText()).isEqualTo("Race");
        assertThat(created.get("hitDice").get("Fighter").get("dieSize").asInt()).isEqualTo(10);
        assertThat(created.get("currency").get("gp").asInt()).isZero();
        assertThat(created.get("multiclassEntries").get(0).get("classId").asText()).isEqualTo(classId.toString());
        assertThat(created.get("levelHistory").get(0).get("choices").isObject()).isTrue();
        assertThat(created.has("hitDiceMap")).isFalse();
        assertThat(created.has("hitDiceTotal")).isFalse();

        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + id, Map.of(
                "spellSlots", Map.of("1", Map.of("max", 2, "used", 1)),
                "currency", Map.of("cp", 0, "sp", 5, "ep", 0, "gp", 12, "pp", 0),
                "equipment", List.of(Map.of("name", "Longsword", "quantity", 1, "equipped", true))),
                player.accessToken()));
        JsonNode read = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + id, null,
                player.accessToken()));

        assertThat(read.get("spellSlots").get("1").get("used").asInt()).isEqualTo(1);
        assertThat(read.get("currency").get("gp").asInt()).isEqualTo(12);
        assertThat(read.get("equipment").get(0).get("equipped").asBoolean()).isTrue();
        assertThat(column("SELECT spell_slots::text FROM player_characters WHERE id = ?", UUID.fromString(id)))
                .isEqualTo("{\"1\": {\"max\": 2, \"used\": 1}}");

        var asString = exchange(HttpMethod.PUT, "/api/characters/" + id,
                Map.of("skillProficiencies", "[\"Stealth\"]"), player.accessToken());
        assertThat(asString.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("A character joins an encounter with copies of its typed slots and spells")
    void joiningAnEncounterCopiesTheSheet() {
        RegisteredUser dm = register("dungeonmaster");
        RegisteredUser player = register("bram");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asText()), player.accessToken()));
        UUID characterId = createCharacter(player.accessToken(), insertRace("Tiefling"), insertClass("Warlock", 8));
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + characterId, Map.of(
                "spellSlots", Map.of("pact_1", Map.of("max", 1, "used", 1)),
                "spellsKnown", List.of(Map.of("name", "Hellish Rebuke", "level", 1, "source", "race:Tiefling",
                        "usesPerLongRest", 1))), player.accessToken()));
        UUID encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asText()));

        JsonNode participant = addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", characterId, "displayName", "Bram"))
                .get("participants").get(0);

        assertThat(participant.get("spellSlotsCurrent").get("pact_1").get("used").asInt()).isEqualTo(1);
        assertThat(participant.get("spellsKnown").get(0).get("name").asText()).isEqualTo("Hellish Rebuke");
        assertThat(participant.get("spellsKnown").get(0).get("usesPerLongRest").asInt()).isEqualTo(1);
    }
}
