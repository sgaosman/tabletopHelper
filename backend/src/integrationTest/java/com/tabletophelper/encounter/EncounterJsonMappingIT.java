package com.tabletophelper.encounter;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.state.CommandSnapshot;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.EncounterTurnState;
import com.tabletophelper.encounter.state.LogPatch;
import com.tabletophelper.encounter.state.ParticipantCombatState;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.internal.UserRepository;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.rules.combat.LifeState;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The typed encounter JSON columns (D168): each record is written to PostgreSQL in the shape V15 and V16
 * normalises rows to, and reads back equal.
 */
class EncounterJsonMappingIT extends IntegrationTest {

    @Autowired private EncounterRepository encounterRepository;
    @Autowired private EncounterParticipantRepository participantRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MonsterRepository monsterRepository;
    @Autowired private CombatCommandRepository combatCommandRepository;

    private Campaign campaign;

    @BeforeEach
    void insertCampaign() {
        User dm = userRepository.save(User.builder().username("dm").email("dm@example.com").passwordHash("hash").build());
        campaign = campaignRepository.save(Campaign.builder().name("Lost Mine").dm(dm).inviteCode("ABCD2345").build());
    }

    /** Saves an encounter with one participant built by the given builder, and returns the participant's ID. */
    private UUID saveParticipant(EncounterParticipant.EncounterParticipantBuilder participant, List<UUID> monstersInLair) {
        Encounter encounter = Encounter.builder().campaign(campaign).name("Ambush").monstersInLair(monstersInLair).build();
        encounter.getParticipants().add(participant.encounter(encounter)
                .participantType(ParticipantType.MONSTER).displayName("Lich").hpMax(135).hpCurrent(135).armourClass(17)
                .build());
        return encounterRepository.save(encounter).getParticipants().get(0).getId();
    }

    private EncounterParticipant reload(UUID participantId) {
        return participantRepository.findById(participantId).orElseThrow();
    }

    private String column(String sql, UUID participantId) {
        return jdbcTemplate.queryForObject(sql, String.class, participantId);
    }

    @Test
    @DisplayName("Conditions round-trip, with or without a source, each with its ID and end rule")
    void conditionsRoundTrip() {
        ConcentrationInstance causeFear = ConcentrationInstance.start("Cause Fear", 1, 3, 10);
        List<ConditionEntry> conditions = List.of(
                ConditionEntry.fromEffect("frightened", 2, 3, "Cause Fear", UUID.randomUUID(), causeFear),
                ConditionEntry.manual("prone", null, 1),
                ConditionEntry.unconsciousAtZeroHp(2));

        UUID id = saveParticipant(EncounterParticipant.builder().activeConditions(conditions), null);

        assertThat(reload(id).getActiveConditions()).isEqualTo(conditions);
        assertThat(column("SELECT active_conditions -> 0 -> 'endRule' ->> 'type' FROM encounter_participants "
                + "WHERE id = ?", id)).isEqualTo("CONCENTRATION");
        assertThat(column("SELECT active_conditions -> 0 ->> 'concentrationInstanceId' FROM encounter_participants "
                + "WHERE id = ?", id)).isEqualTo(causeFear.id().toString());
        assertThat(column("SELECT active_conditions -> 2 -> 'endRule' ->> 'type' FROM encounter_participants "
                + "WHERE id = ?", id)).isEqualTo("HP_REGAINED");
        // Every field is written, null or not, as the server always has
        assertThat(column("SELECT active_conditions -> 1 ->> 'name' FROM encounter_participants WHERE id = ?", id))
                .isEqualTo("prone");
        assertThat(column("SELECT jsonb_typeof(active_conditions -> 1 -> 'sourceParticipantId') "
                + "FROM encounter_participants WHERE id = ?", id)).isEqualTo("null");
    }

    @Test
    @DisplayName("A Concentration Instance and the life state round-trip (D161, D162)")
    void concentrationAndLifeStateRoundTrip() {
        ConcentrationInstance haste = ConcentrationInstance.start("Haste", 3, 2, 10);

        UUID id = saveParticipant(EncounterParticipant.builder().concentration(haste), null);
        assertThat(column("SELECT life_state FROM encounter_participants WHERE id = ?", id)).isEqualTo("ALIVE");
        jdbcTemplate.update("UPDATE encounter_participants SET hp_current = 0, life_state = 'DEAD' WHERE id = ?", id);

        EncounterParticipant reloaded = reload(id);
        assertThat(reloaded.getConcentration()).isEqualTo(haste);
        assertThat(reloaded.getLifeState()).isEqualTo(LifeState.DEAD);
        assertThat(column("SELECT concentration ->> 'spell' FROM encounter_participants WHERE id = ?", id))
                .isEqualTo("Haste");
    }

    @Test
    @DisplayName("The database refuses a life state that isn't one of the four")
    void lifeStateIsChecked() {
        UUID id = saveParticipant(EncounterParticipant.builder(), null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE encounter_participants SET life_state = 'UNDEAD' WHERE id = ?", id))
                .hasMessageContaining("encounter_participants_life_state_check");
    }

    @Test
    @DisplayName("Spell slots round-trip as {max, used}, keyed by level and pact_N")
    void spellSlotsRoundTrip() {
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        slots.put("1", new SpellSlot(4, 1));
        slots.put("9", new SpellSlot(1, 0));
        slots.put("pact_5", new SpellSlot(2, 2));

        UUID id = saveParticipant(EncounterParticipant.builder().spellSlotsCurrent(slots), null);

        assertThat(reload(id).getSpellSlotsCurrent()).isEqualTo(slots);
        assertThat(column("SELECT spell_slots_current ->> 'pact_5' FROM encounter_participants WHERE id = ?", id))
                .isEqualTo("{\"max\": 2, \"used\": 2}");
    }

    @Test
    @DisplayName("Known spells round-trip, and a field an entry doesn't have stays out of the column")
    void knownSpellsRoundTrip() {
        List<KnownSpell> spells = List.of(
                KnownSpell.monster(UUID.randomUUID(), "Fireball", 3),
                new KnownSpell(null, "Shield", 1, "class:Wizard", true, null, null, null, null),
                new KnownSpell(null, "Hellish Rebuke", 1, "race:Tiefling", null, null, null, 1, 3));

        UUID id = saveParticipant(EncounterParticipant.builder().spellsKnown(spells), null);

        assertThat(reload(id).getSpellsKnown()).isEqualTo(spells);
        assertThat(column("SELECT spells_known ->> 1 FROM encounter_participants WHERE id = ?", id))
                .isEqualTo("{\"name\": \"Shield\", \"level\": 1, \"source\": \"class:Wizard\", \"prepared\": true}");
    }

    @Test
    @DisplayName("Resource pools round-trip, metadata included")
    void resourcePoolsRoundTrip() {
        List<ResourcePoolEntry> pools = List.of(
                new ResourcePoolEntry("monster:legendary-resistance", "Legendary Resistance", "MONSTER", "Lich",
                        3, null, 2, "longRest", null, null, "FREE", "shield", Map.of()),
                new ResourcePoolEntry("class:fighter-superiority-dice", "Superiority Dice", "CLASS", null,
                        4, "4", 4, "shortRest", null, null, "FREE", "swords", Map.of("dieFaces", 8)));

        UUID id = saveParticipant(EncounterParticipant.builder().resourcePoolsCurrent(pools), null);

        assertThat(reload(id).getResourcePoolsCurrent()).isEqualTo(pools);
    }

    @Test
    @DisplayName("What a player character joined with round-trips, keyed as slots and pools are, and an encounter's results choice with it (D166)")
    void joinedWithRoundTrips() {
        Map<String, Integer> slotsUsed = new LinkedHashMap<>();
        slotsUsed.put("1", 2);
        slotsUsed.put("pact_3", 0);
        CombatTotals joined = new CombatTotals(38, 5, slotsUsed, Map.of("class:barbarian-rage", 2));

        UUID id = saveParticipant(EncounterParticipant.builder().joinedWith(joined), null);
        UUID encounterId = jdbcTemplate.queryForObject(
                "SELECT encounter_id FROM encounter_participants WHERE id = ?", UUID.class, id);
        Encounter encounter = encounterRepository.findById(encounterId).orElseThrow();
        assertThat(encounter.getResultsApplied()).isNull();
        encounter.setResultsApplied(false);
        encounterRepository.save(encounter);

        assertThat(reload(id).getJoinedWith()).isEqualTo(joined);
        assertThat(column("SELECT joined_with::text FROM encounter_participants WHERE id = ?", id))
                .isEqualTo("{\"hpTemp\": 5, \"hpCurrent\": 38, \"slotsUsed\": {\"1\": 2, \"pact_3\": 0}, "
                        + "\"poolsCurrentUses\": {\"class:barbarian-rage\": 2}}");
        assertThat(jdbcTemplate.queryForObject("SELECT results_applied FROM encounters WHERE id = ?", Boolean.class,
                encounterId)).isFalse();
        // A monster joins with nothing to write back
        assertThat(column("SELECT joined_with::text FROM encounter_participants WHERE id = ?",
                saveParticipant(EncounterParticipant.builder(), null))).isNull();
    }

    @Test
    @DisplayName("A turn economy round-trips, a participant starts with a fresh one, the column's default is the fresh record, and Action Surge is written only once used (D174, V30)")
    void turnEconomyRoundTrips() {
        TurnEconomy spent = new TurnEconomy(2, 1, true, 1, true, true, true, false, true, true, false, false);

        UUID id = saveParticipant(EncounterParticipant.builder().turnEconomy(spent), null);

        assertThat(reload(id).getTurnEconomy()).isEqualTo(spent);
        assertThat(column("SELECT turn_economy::text FROM encounter_participants WHERE id = ?", id))
                .isEqualTo("{\"dashed\": true, \"actions\": 2, \"disengaged\": false, \"actionsUsed\": 1, "
                        + "\"attacksLeft\": 1, \"reactionUsed\": true, \"otherSpellCast\": true, "
                        + "\"bonusActionUsed\": true, \"attackActionTaken\": true, \"bonusActionSpellCast\": false, "
                        + "\"objectInteractionUsed\": true}");

        UUID fresh = saveParticipant(EncounterParticipant.builder(), null);
        assertThat(reload(fresh).getTurnEconomy()).isEqualTo(TurnEconomy.fresh());
        jdbcTemplate.update("UPDATE encounter_participants SET turn_economy = DEFAULT WHERE id = ?", id);
        assertThat(reload(id).getTurnEconomy()).isEqualTo(TurnEconomy.fresh());
        assertThat(column("SELECT turn_economy::text FROM encounter_participants WHERE id = ?", id))
                .isEqualTo(column("SELECT turn_economy::text FROM encounter_participants WHERE id = ?", fresh));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE encounter_participants SET turn_economy = NULL WHERE id = ?", id))
                .hasMessageContaining("turn_economy");

        // Action Surge used is written only when true, so a record stored before it existed reads as false (M13 C)
        TurnEconomy surged = new TurnEconomy(2, 0, false, 0, false, false, false, false, false, false, false, true);
        UUID surgedId = saveParticipant(EncounterParticipant.builder().turnEconomy(surged), null);
        assertThat(reload(surgedId).getTurnEconomy()).isEqualTo(surged);
        assertThat(column("SELECT turn_economy ->> 'actionSurgeUsed' FROM encounter_participants WHERE id = ?",
                surgedId)).isEqualTo("true");
        assertThat(reload(fresh).getTurnEconomy().actionSurgeUsed()).isFalse();
    }

    @Test
    @DisplayName("An encounter's monsters in the lair round-trip as IDs")
    void monstersInLairRoundTrip() {
        List<UUID> monstersInLair = List.of(UUID.randomUUID(), UUID.randomUUID());

        UUID id = saveParticipant(EncounterParticipant.builder(), monstersInLair);

        UUID encounterId = jdbcTemplate.queryForObject(
                "SELECT encounter_id FROM encounter_participants WHERE id = ?", UUID.class, id);
        assertThat(encounterRepository.findById(encounterId).orElseThrow().getMonstersInLair())
                .isEqualTo(monstersInLair);
    }

    @Test
    @DisplayName("Combat changes the typed state in place, and the API returns it as JSON rather than strings")
    void combatChangesTypedStateThroughTheApi() {
        RegisteredUser dm = register("dungeonmaster");
        UUID campaignId = UUID.fromString(createCampaign(dm.accessToken()).get("id").asString());
        UUID encounterId = createEncounter(dm.accessToken(), campaignId);
        Monster mage = monsterRepository.save(Monster.builder()
                .name("Mage").type("Humanoid").challengeRating("6").source("MM")
                .hitPoints(40).armourClass(12).dexterity(14)
                .build());
        String mageId = addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", mage.getId(), "displayName", "Mage"))
                .get("participants").get(0).get("id").asString();
        EncounterParticipant participant = reload(UUID.fromString(mageId));
        participant.setSpellSlotsCurrent(Map.of("1", new SpellSlot(4, 0), "3", new SpellSlot(3, 1)));
        participantRepository.save(participant);
        startEncounter(dm.accessToken(), encounterId);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/spell-slot/use",
                Map.of("participantId", mageId, "slotLevel", 3), dm.accessToken()));
        JsonNode state = expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/encounters/" + encounterId + "/combat/condition/add",
                Map.of("targetId", mageId, "condition", "Prone", "duration", 2), dm.accessToken()));

        JsonNode mageState = state.get("participants").get(0);
        assertThat(mageState.get("spellSlotsCurrent").get("3").get("used").asInt()).isEqualTo(2);
        assertThat(mageState.get("activeConditions").get(0).get("name").asString()).isEqualTo("prone");
        assertThat(reload(UUID.fromString(mageId)).getSpellSlotsCurrent())
                .isEqualTo(Map.of("1", new SpellSlot(4, 0), "3", new SpellSlot(3, 2)));
        assertThat(reload(UUID.fromString(mageId)).getActiveConditions())
                .singleElement()
                .satisfies(condition -> {
                    assertThat(condition.name()).isEqualTo("prone");
                    assertThat(condition.duration()).isEqualTo(2);
                    assertThat(condition.appliedRound()).isEqualTo(1);
                    // Its two rounds count on the mage's own turns (D162)
                    assertThat(condition.endRule()).isEqualTo(EndRule.afterRounds(null));
                    assertThat(condition.id()).isNotNull();
                });
    }

    @Test
    @DisplayName("A property the records don't have is ignored on load, as the API ignores it")
    void unknownPropertiesAreIgnored() {
        UUID id = saveParticipant(EncounterParticipant.builder(), null);
        jdbcTemplate.update("""
                UPDATE encounter_participants
                SET spells_known = '[{"name": "Shield", "level": 1, "colour": "blue"}]',
                    resource_pools_current = '[{"poolId": "class:monk-ki", "displayName": "Ki Points",
                        "sourceType": "CLASS", "maxUses": 5, "currentUses": 3, "note": "from an older version"}]'
                WHERE id = ?""", id);

        EncounterParticipant participant = reload(id);

        assertThat(participant.getSpellsKnown())
                .containsExactly(new KnownSpell(null, "Shield", 1, null, null, null, null, null, null));
        assertThat(participant.getResourcePoolsCurrent()).singleElement()
                .satisfies(pool -> assertThat(pool.currentUses()).isEqualTo(3));
    }

    @Test
    @DisplayName("A combat command's before-state round-trips: participants, the encounter's turn and log patches (M14)")
    void commandSnapshotRoundTrips() {
        UUID encounterId = encounterRepository.save(Encounter.builder().campaign(campaign).name("Ambush").build()).getId();
        ConcentrationInstance web = ConcentrationInstance.start("Web", 2, 1, 600);
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        slots.put("2", new SpellSlot(3, 1));
        slots.put("pact_3", new SpellSlot(2, 0));
        ParticipantCombatState lich = new ParticipantCombatState(UUID.randomUUID(), 0, 5, LifeState.DYING, 1, 2, true,
                List.of(ConditionEntry.fromEffect("restrained", null, 1, "Web", UUID.randomUUID(), web),
                        ConditionEntry.unconsciousAtZeroHp(1)),
                web, "Web", 2, slots,
                List.of(new ResourcePoolEntry("monster:legendary-resistance", "Legendary Resistance", "MONSTER", "Lich",
                        3, null, 2, "longRest", null, null, "FREE", "shield", Map.of())),
                new TurnEconomy(1, 1, true, 0, true, false, true, false, true, false, true, true), "Tremor");
        CommandSnapshot snapshot = new CommandSnapshot(List.of(lich),
                new EncounterTurnState(3, null, true, List.of(UUID.randomUUID())),
                List.of(new LogPatch(UUID.randomUUID(), false)));

        UUID id = combatCommandRepository.save(CombatCommand.builder().encounterId(encounterId).sequence(1L)
                .kind("SPELL_CAST").summary("Lich casts Web").beforeState(snapshot).build()).getId();

        assertThat(combatCommandRepository.findById(id).orElseThrow().getBeforeState()).isEqualTo(snapshot);
        assertThat(jdbcTemplate.queryForObject("SELECT before_state -> 'encounter' ->> 'lairTurn' FROM combat_commands "
                + "WHERE id = ?", String.class, id)).isEqualTo("true");
    }
}
