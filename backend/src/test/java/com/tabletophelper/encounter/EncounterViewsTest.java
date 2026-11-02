package com.tabletophelper.encounter;

import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.CombatLogResponse;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.EncounterResponse.ParticipantResponse;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.KnownSpell;
import com.tabletophelper.encounter.state.SpellSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EncounterViewsTest {

    private final UUID dmId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();

    private final UUID goblinId = UUID.randomUUID();
    private final UUID bugbearId = UUID.randomUUID();
    private final UUID gimliId = UUID.randomUUID();

    private static final List<ConditionEntry> PRONE = List.of(ConditionEntry.unsourced("prone", null, 1));

    private ParticipantResponse goblin(int hpCurrent) {
        return ParticipantResponse.builder()
                .id(goblinId)
                .participantType("MONSTER")
                .monsterId(UUID.randomUUID())
                .displayName("Goblin")
                .hpMax(7)
                .hpCurrent(hpCurrent)
                .hpTemp(2)
                .armourClass(15)
                .activeConditions(PRONE)
                .isVisibleToPlayers(true)
                .isAlive(hpCurrent > 0)
                .notes("Carries the key to the cellar")
                .spellsKnown(List.of(KnownSpell.monster(UUID.randomUUID(), "Shield", 1)))
                .spellSlotsCurrent(Map.of("1", new SpellSlot(2, 1)))
                .spellAttackBonus(4)
                .spellSaveDc(12)
                .spellcastingAbility("INT")
                .resourcePoolsCurrent(List.of(new ResourcePoolEntry("monster:legendary-resistance",
                        "Legendary Resistance", "MONSTER", "Goblin", 3, null, 3, "longRest", null, null, "FREE",
                        "shield", Map.of())))
                .build();
    }

    private ParticipantResponse bugbear(Boolean visible) {
        return ParticipantResponse.builder()
                .id(bugbearId)
                .participantType("MONSTER")
                .displayName("Bugbear")
                .hpMax(27)
                .hpCurrent(27)
                .isVisibleToPlayers(visible)
                .build();
    }

    private ParticipantResponse gimli() {
        return ParticipantResponse.builder()
                .id(gimliId)
                .participantType("PLAYER")
                .displayName("Gimli")
                .hpMax(30)
                .hpCurrent(12)
                .hpTemp(5)
                .isVisibleToPlayers(true)
                .controlledByUserId(playerId)
                .notes("Owes the innkeeper")
                .spellsKnown(List.of())
                .spellSlotsCurrent(Map.of())
                .spellAttackBonus(5)
                .spellSaveDc(13)
                .spellcastingAbility("WIS")
                .resourcePoolsCurrent(List.of())
                .build();
    }

    private EncounterResponse encounter(boolean showExactMonsterHp, ParticipantResponse... participants) {
        return EncounterResponse.builder()
                .id(UUID.randomUUID())
                .name("Goblin Ambush")
                .status("ACTIVE")
                .roundNumber(2)
                .participants(List.of(participants))
                .dmUserId(dmId)
                .showExactMonsterHp(showExactMonsterHp)
                .build();
    }

    private static ParticipantResponse find(EncounterResponse view, UUID id) {
        return view.getParticipants().stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Participant " + id + " is not in the view"));
    }

    @Nested
    @DisplayName("playerView")
    class PlayerView {

        @Test
        @DisplayName("Leaves out hidden participants and keeps those whose flag is true or null")
        void leavesOutHiddenParticipants() {
            var dmView = encounter(false, goblin(7), bugbear(false), gimli());
            var nullFlag = encounter(false, bugbear(null));

            assertEquals(List.of(goblinId, gimliId),
                    EncounterViews.playerView(dmView).getParticipants().stream().map(ParticipantResponse::getId).toList());
            assertEquals(1, EncounterViews.playerView(nullFlag).getParticipants().size());
        }

        @Test
        @DisplayName("Removes a monster's notes, spells, spell slots, spellcasting numbers, resource pools and bestiary entry")
        void removesMonsterDetails() {
            var goblin = find(EncounterViews.playerView(encounter(true, goblin(7))), goblinId);

            assertNull(goblin.getNotes());
            assertNull(goblin.getSpellsKnown());
            assertNull(goblin.getSpellSlotsCurrent());
            assertNull(goblin.getSpellAttackBonus());
            assertNull(goblin.getSpellSaveDc());
            assertNull(goblin.getSpellcastingAbility());
            assertNull(goblin.getResourcePoolsCurrent());
            assertNull(goblin.getMonsterId());
            // What the table can see stays
            assertEquals("Goblin", goblin.getDisplayName());
            assertEquals(15, goblin.getArmourClass());
            assertEquals(PRONE, goblin.getActiveConditions());
        }

        @Test
        @DisplayName("Replaces monster HP with a Health Status while the campaign hides exact HP")
        void replacesMonsterHpWithHealthStatus() {
            var goblin = find(EncounterViews.playerView(encounter(false, goblin(5))), goblinId);

            assertNull(goblin.getHpCurrent());
            assertNull(goblin.getHpMax());
            assertNull(goblin.getHpTemp());
            assertEquals("INJURED", goblin.getHealthStatus());
        }

        @Test
        @DisplayName("Keeps exact monster HP, with no Health Status, when the campaign shows it")
        void keepsExactHpWhenTheCampaignShowsIt() {
            var goblin = find(EncounterViews.playerView(encounter(true, goblin(5))), goblinId);

            assertEquals(5, goblin.getHpCurrent());
            assertEquals(7, goblin.getHpMax());
            assertEquals(2, goblin.getHpTemp());
            assertNull(goblin.getHealthStatus());
        }

        @Test
        @DisplayName("Leaves player characters exactly as the DM sees them")
        void leavesPlayerCharactersAlone() {
            var dmGimli = gimli();

            assertEquals(dmGimli, find(EncounterViews.playerView(encounter(false, dmGimli)), gimliId));
        }

        @Test
        @DisplayName("Returns a copy and leaves the DM View unchanged")
        void leavesTheDmViewUnchanged() {
            var dmView = encounter(false, goblin(5), bugbear(false));

            EncounterViews.playerView(dmView);

            assertEquals(2, dmView.getParticipants().size());
            assertEquals(5, find(dmView, goblinId).getHpCurrent());
            assertEquals("Carries the key to the cellar", find(dmView, goblinId).getNotes());
            assertNull(find(dmView, goblinId).getHealthStatus());
        }
    }

    @Nested
    @DisplayName("Health Status")
    class HealthStatusRules {

        @Test
        @DisplayName("Unharmed at full HP, Injured above half, Bloodied at half or less, Down at 0")
        void thresholds() {
            assertEquals(HealthStatus.UNHARMED, HealthStatus.of(7, 7));
            assertEquals(HealthStatus.INJURED, HealthStatus.of(6, 7));
            assertEquals(HealthStatus.INJURED, HealthStatus.of(4, 7));
            assertEquals(HealthStatus.BLOODIED, HealthStatus.of(3, 7));
            assertEquals(HealthStatus.BLOODIED, HealthStatus.of(5, 10));
            assertEquals(HealthStatus.BLOODIED, HealthStatus.of(1, 7));
            assertEquals(HealthStatus.DOWN, HealthStatus.of(0, 7));
        }

        @Test
        @DisplayName("Has no Health Status when HP is missing")
        void missingHp() {
            assertNull(HealthStatus.of(null, 7));
            assertNull(HealthStatus.of(7, null));
        }

        @Test
        @DisplayName("A dead monster in the Player View is Down")
        void deadMonsterIsDown() {
            assertEquals("DOWN", find(EncounterViews.playerView(encounter(false, goblin(0))), goblinId).getHealthStatus());
        }
    }

    @Nested
    @DisplayName("forUser")
    class ForUser {

        @Test
        @DisplayName("The campaign's DM gets the DM View itself")
        void dmGetsTheDmView() {
            var dmView = encounter(false, goblin(5), bugbear(false));

            assertSame(dmView, EncounterViews.forUser(dmView, dmId));
        }

        @Test
        @DisplayName("Anyone else gets the Player View")
        void playerGetsThePlayerView() {
            var view = EncounterViews.forUser(encounter(false, goblin(5), bugbear(false)), playerId);

            assertEquals(List.of(goblinId), view.getParticipants().stream().map(ParticipantResponse::getId).toList());
            assertEquals("INJURED", find(view, goblinId).getHealthStatus());
        }

        @Test
        @DisplayName("Without a known DM, everyone gets the Player View")
        void unknownDmMeansPlayerView() {
            var dmView = encounter(false, bugbear(false)).toBuilder().dmUserId(null).build();

            assertTrue(EncounterViews.forUser(dmView, dmId).getParticipants().isEmpty());
        }

        @Test
        @DisplayName("A spell result carries the caller's view of the encounter and keeps everything else")
        void spellResultCarriesTheCallersView() {
            var result = CastSpellResponse.builder()
                    .encounterState(encounter(false, goblin(5), bugbear(false)))
                    .spellName("Fire Bolt")
                    .resultSummary("Fire Bolt hits Goblin for 2 fire damage.")
                    .build();

            var forPlayer = EncounterViews.forUser(result, playerId);
            var forDm = EncounterViews.forUser(result, dmId);

            assertEquals(1, forPlayer.getEncounterState().getParticipants().size());
            assertEquals("Fire Bolt hits Goblin for 2 fire damage.", forPlayer.getResultSummary());
            assertEquals(2, forDm.getEncounterState().getParticipants().size());
            assertEquals(2, result.getEncounterState().getParticipants().size());
        }
    }

    @Nested
    @DisplayName("playerLog")
    class PlayerLog {

        private final Map<UUID, String> hidden = Map.of(bugbearId, "Bugbear");

        private CombatLogResponse entry(String description, UUID actorId, UUID targetId) {
            return CombatLogResponse.builder()
                    .id(UUID.randomUUID())
                    .roundNumber(1)
                    .actorId(actorId)
                    .targetId(targetId)
                    .actionType("ATTACK")
                    .description(description)
                    .turnParticipantName("Gimli")
                    .build();
        }

        private List<String> descriptions(List<CombatLogResponse> log) {
            return log.stream().map(CombatLogResponse::getDescription).toList();
        }

        @Test
        @DisplayName("Leaves out entries whose actor or target is hidden")
        void leavesOutEntriesAboutHiddenParticipants() {
            var log = List.of(
                    entry("Gimli attacks Goblin", gimliId, goblinId),
                    entry("Bugbear attacks Gimli", bugbearId, gimliId),
                    entry("Gimli attacks Bugbear", gimliId, bugbearId),
                    entry("Round 2 begins", null, null));

            assertEquals(List.of("Gimli attacks Goblin", "Round 2 begins"),
                    descriptions(EncounterViews.playerLog(log, hidden)));
        }

        @Test
        @DisplayName("Leaves out an entry whose Legendary Resistance target is hidden")
        void leavesOutEntriesWithAHiddenLegendaryResistanceTarget() {
            var log = List.of(entry("Hold Person", gimliId, null).toBuilder()
                    .legendaryResistanceEligible(true)
                    .lrTargetId(bugbearId)
                    .build());

            assertTrue(EncounterViews.playerLog(log, hidden).isEmpty());
        }

        @Test
        @DisplayName("Removes the Legendary Resistance fields from every entry")
        void removesLegendaryResistanceFields() {
            var log = List.of(entry("Goblin fails its save", gimliId, goblinId).toBuilder()
                    .legendaryResistanceEligible(true)
                    .lrTargetId(goblinId)
                    .lrDamageDealt(8)
                    .lrConditionsApplied("paralyzed")
                    .lrResolved(false)
                    .build());

            var playerEntry = EncounterViews.playerLog(log, hidden).getFirst();

            assertNull(playerEntry.getLegendaryResistanceEligible());
            assertNull(playerEntry.getLrTargetId());
            assertNull(playerEntry.getLrDamageDealt());
            assertNull(playerEntry.getLrConditionsApplied());
            assertNull(playerEntry.getLrResolved());
            assertEquals("Goblin fails its save", playerEntry.getDescription());
            assertEquals(8, log.getFirst().getLrDamageDealt());
        }

        @Test
        @DisplayName("Keeps an entry from a hidden participant's turn but drops that participant's name")
        void dropsAHiddenParticipantsTurnName() {
            var log = List.of(
                    entry("DM deals 4 damage to Gimli", null, gimliId).toBuilder().turnParticipantName("Bugbear").build(),
                    entry("Gimli attacks Goblin", gimliId, goblinId));

            var playerLog = EncounterViews.playerLog(log, hidden);

            assertEquals(2, playerLog.size());
            assertNull(playerLog.get(0).getTurnParticipantName());
            assertEquals("Gimli", playerLog.get(1).getTurnParticipantName());
        }

        @Test
        @DisplayName("With nothing hidden, every entry stays")
        void nothingHidden() {
            var log = List.of(entry("Gimli attacks Goblin", gimliId, goblinId), entry("Round 2 begins", null, null));

            assertEquals(2, EncounterViews.playerLog(log, Map.of()).size());
        }
    }

    @Nested
    @DisplayName("hiddenParticipants")
    class HiddenParticipants {

        @Test
        @DisplayName("Maps each participant the DM View hides to its name; a null flag counts as visible")
        void mapsHiddenParticipantsToTheirNames() {
            var dmView = encounter(false, gimli(), goblin(7).toBuilder().isVisibleToPlayers(null).build(), bugbear(false));

            assertEquals(Map.of(bugbearId, "Bugbear"), EncounterViews.hiddenParticipants(dmView));
        }

        @Test
        @DisplayName("Is empty when nobody is hidden")
        void emptyWhenNobodyIsHidden() {
            assertTrue(EncounterViews.hiddenParticipants(encounter(false, gimli(), bugbear(true))).isEmpty());
        }
    }
}
