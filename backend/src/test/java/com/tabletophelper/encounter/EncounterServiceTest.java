package com.tabletophelper.encounter;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EncounterServiceTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private EncounterParticipantRepository participantRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignMemberRepository campaignMemberRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private MonsterRepository monsterRepository;

    @InjectMocks private EncounterService encounterService;

    private UUID dmUserId;
    private UUID campaignId;
    private Campaign campaign;
    private User dmUser;

    @BeforeEach
    void setUp() {
        dmUserId = UUID.randomUUID();
        campaignId = UUID.randomUUID();

        dmUser = User.builder()
                .id(dmUserId)
                .username("dm_user")
                .email("dm@test.com")
                .passwordHash("hash")
                .displayName("DM User")
                .build();

        campaign = Campaign.builder()
                .id(campaignId)
                .name("Test Campaign")
                .dm(dmUser)
                .inviteCode("TESTCODE")
                .members(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("Create encounter sets status to PREPARING")
    void createEncounter_setsPreparingStatus() {
        EncounterCreateRequest request = new EncounterCreateRequest();
        request.setCampaignId(campaignId);
        request.setName("Goblin Ambush");
        request.setDescription("A surprise attack!");

        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> {
            Encounter enc = invocation.getArgument(0);
            enc.setId(UUID.randomUUID());
            return enc;
        });

        EncounterResponse response = encounterService.createEncounter(request, dmUserId);

        assertEquals("PREPARING", response.getStatus());
        assertEquals("Goblin Ambush", response.getName());
        verify(encounterRepository).save(any(Encounter.class));
    }

    @Test
    @DisplayName("Add monster participant auto-populates stats and names with quantity suffix")
    void addMonsterParticipant_autoPopulatesFromMonsterStats() {
        UUID encounterId = UUID.randomUUID();
        UUID monsterId = UUID.randomUUID();

        Monster goblin = Monster.builder()
                .id(monsterId)
                .name("Goblin")
                .hitPoints(7)
                .armourClass(15)
                .dexterity(14) // DEX mod +2
                .build();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.PREPARING)
                .participants(new ArrayList<>())
                .build();

        AddParticipantRequest request = new AddParticipantRequest();
        request.setParticipantType("MONSTER");
        request.setMonsterId(monsterId);
        request.setDisplayName("Goblin");
        request.setQuantity(3);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(monsterRepository.findById(monsterId)).thenReturn(Optional.of(goblin));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.addParticipant(encounterId, request, dmUserId);

        assertEquals(3, response.getParticipants().size());
        assertEquals("Goblin 1", response.getParticipants().get(0).getDisplayName());
        assertEquals("Goblin 2", response.getParticipants().get(1).getDisplayName());
        assertEquals("Goblin 3", response.getParticipants().get(2).getDisplayName());
        response.getParticipants().forEach(p -> {
            assertEquals(7, p.getHpMax());
            assertEquals(7, p.getHpCurrent());
            assertEquals(15, p.getArmourClass());
            assertEquals(2, p.getInitiativeModifier()); // (14-10)/2 = 2
        });
    }

    @Test
    @DisplayName("Add PC participant auto-populates from character sheet")
    void addPcParticipant_autoPopulatesFromCharacterSheet() {
        UUID encounterId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();

        User playerUser = User.builder()
                .id(UUID.randomUUID())
                .username("player1")
                .email("player1@test.com")
                .passwordHash("hash")
                .displayName("Player One")
                .build();

        PlayerCharacter thorin = PlayerCharacter.builder()
                .id(characterId)
                .name("Thorin")
                .user(playerUser)
                .strength(16).dexterity(14).constitution(14)
                .intelligence(10).wisdom(12).charisma(8)
                .hpMax(45).hpCurrent(45)
                .armourClass(18)
                .initiativeBonus(2)
                .build();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.PREPARING)
                .participants(new ArrayList<>())
                .build();

        AddParticipantRequest request = new AddParticipantRequest();
        request.setParticipantType("PLAYER");
        request.setCharacterId(characterId);
        request.setDisplayName("Thorin");

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(characterRepository.findById(characterId)).thenReturn(Optional.of(thorin));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.addParticipant(encounterId, request, dmUserId);

        assertEquals(1, response.getParticipants().size());
        EncounterResponse.ParticipantResponse participant = response.getParticipants().get(0);
        assertEquals("Thorin", participant.getDisplayName());
        assertEquals("PLAYER", participant.getParticipantType());
        assertEquals(45, participant.getHpMax());
        assertEquals(45, participant.getHpCurrent());
        assertEquals(18, participant.getArmourClass());
        assertEquals(2, participant.getInitiativeModifier());
    }

    @Test
    @DisplayName("Remove participant deletes the specific participant")
    void removeParticipant_removesSpecificParticipant() {
        UUID encounterId = UUID.randomUUID();
        UUID participantToRemoveId = UUID.randomUUID();
        UUID participantToKeepId = UUID.randomUUID();

        EncounterParticipant toRemove = EncounterParticipant.builder()
                .id(participantToRemoveId)
                .participantType(ParticipantType.MONSTER)
                .displayName("Goblin 2")
                .hpMax(7).hpCurrent(7).armourClass(15)
                .build();

        EncounterParticipant toKeep = EncounterParticipant.builder()
                .id(participantToKeepId)
                .participantType(ParticipantType.MONSTER)
                .displayName("Goblin 1")
                .hpMax(7).hpCurrent(7).armourClass(15)
                .build();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.PREPARING)
                .participants(new ArrayList<>(List.of(toRemove, toKeep)))
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.removeParticipant(encounterId, participantToRemoveId, dmUserId);

        assertEquals(1, response.getParticipants().size());
        assertEquals("Goblin 1", response.getParticipants().get(0).getDisplayName());
    }

    @Test
    @DisplayName("Rename participant updates display name only")
    void renameParticipant_updatesDisplayName() {
        UUID encounterId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID monsterId = UUID.randomUUID();

        Monster goblin = Monster.builder().id(monsterId).name("Goblin").build();

        EncounterParticipant participant = EncounterParticipant.builder()
                .id(participantId)
                .participantType(ParticipantType.MONSTER)
                .monster(goblin)
                .displayName("Goblin 1")
                .hpMax(7).hpCurrent(7).armourClass(15)
                .build();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.PREPARING)
                .participants(new ArrayList<>(List.of(participant)))
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.renameParticipant(encounterId, participantId, "Snaggle the Goblin", dmUserId);

        assertEquals("Snaggle the Goblin", response.getParticipants().get(0).getDisplayName());
        assertEquals(monsterId, response.getParticipants().get(0).getMonsterId());
    }

    @Test
    @DisplayName("Set initiatives in bulk updates all specified participants")
    void setInitiatives_bulkUpdate() {
        UUID encounterId = UUID.randomUUID();
        UUID fighterId = UUID.randomUUID();
        UUID wizardId = UUID.randomUUID();

        EncounterParticipant fighter = EncounterParticipant.builder()
                .id(fighterId)
                .participantType(ParticipantType.PLAYER)
                .displayName("Fighter")
                .hpMax(45).hpCurrent(45).armourClass(18)
                .build();

        EncounterParticipant wizard = EncounterParticipant.builder()
                .id(wizardId)
                .participantType(ParticipantType.PLAYER)
                .displayName("Wizard")
                .hpMax(20).hpCurrent(20).armourClass(12)
                .build();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.PREPARING)
                .participants(new ArrayList<>(List.of(fighter, wizard)))
                .build();

        SetInitiativeRequest fighterInit = new SetInitiativeRequest();
        fighterInit.setParticipantId(fighterId);
        fighterInit.setInitiative(18);

        SetInitiativeRequest wizardInit = new SetInitiativeRequest();
        wizardInit.setParticipantId(wizardId);
        wizardInit.setInitiative(12);

        BulkInitiativeRequest request = new BulkInitiativeRequest();
        request.setInitiatives(List.of(fighterInit, wizardInit));

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.setInitiatives(encounterId, request, dmUserId);

        // Participants should be sorted by initiative descending
        assertEquals(2, response.getParticipants().size());
        assertEquals("Fighter", response.getParticipants().get(0).getDisplayName());
        assertEquals(18, response.getParticipants().get(0).getInitiative());
        assertEquals("Wizard", response.getParticipants().get(1).getDisplayName());
        assertEquals(12, response.getParticipants().get(1).getInitiative());
    }

    @Test
    @DisplayName("Roll all initiatives assigns d20 + modifier to each participant")
    void rollAllInitiatives_assignsRandomValues() {
        UUID encounterId = UUID.randomUUID();

        EncounterParticipant fighter = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(ParticipantType.PLAYER)
                .displayName("Fighter")
                .initiativeModifier(3)
                .hpMax(45).hpCurrent(45).armourClass(18)
                .build();

        EncounterParticipant wizard = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(ParticipantType.PLAYER)
                .displayName("Wizard")
                .initiativeModifier(1)
                .hpMax(20).hpCurrent(20).armourClass(12)
                .build();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.PREPARING)
                .participants(new ArrayList<>(List.of(fighter, wizard)))
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.rollAllInitiatives(encounterId, dmUserId);

        response.getParticipants().forEach(p -> {
            assertNotNull(p.getInitiative(), "Initiative should be set for " + p.getDisplayName());
            // d20 (1-20) + modifier, so min is 1+modifier, max is 20+modifier
        });
    }

    @Test
    @DisplayName("Start encounter transitions PREPARING to ACTIVE with sorted participants")
    void startEncounter_transitionsToActive() {
        UUID encounterId = UUID.randomUUID();

        EncounterParticipant wizard = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(ParticipantType.PLAYER)
                .displayName("Wizard")
                .initiative(12)
                .sortOrder(1)
                .hpMax(20).hpCurrent(20).armourClass(12)
                .build();

        EncounterParticipant fighter = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(ParticipantType.PLAYER)
                .displayName("Fighter")
                .initiative(18)
                .sortOrder(0)
                .hpMax(45).hpCurrent(45).armourClass(18)
                .build();

        EncounterParticipant goblin = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(ParticipantType.MONSTER)
                .displayName("Goblin")
                .initiative(8)
                .sortOrder(2)
                .hpMax(7).hpCurrent(7).armourClass(15)
                .build();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.PREPARING)
                .participants(new ArrayList<>(List.of(wizard, fighter, goblin)))
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.findBySessionCode(anyString())).thenReturn(Optional.empty());
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.startEncounter(encounterId, dmUserId);

        assertEquals("ACTIVE", response.getStatus());
        assertEquals(1, response.getRoundNumber());
        // First participant (sort order 0, highest initiative) should be Fighter
        assertEquals("Fighter", response.getParticipants().get(0).getDisplayName());
        assertEquals(18, response.getParticipants().get(0).getInitiative());
    }

    @Test
    @DisplayName("Start encounter fails when participants lack initiative")
    void startEncounter_failsWithoutInitiative() {
        UUID encounterId = UUID.randomUUID();

        EncounterParticipant noInitiative = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(ParticipantType.PLAYER)
                .displayName("Fighter")
                .initiative(null)
                .hpMax(45).hpCurrent(45).armourClass(18)
                .build();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.PREPARING)
                .participants(new ArrayList<>(List.of(noInitiative)))
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> encounterService.startEncounter(encounterId, dmUserId));

        assertTrue(ex.getMessage().contains("initiative"));
    }

    @Test
    @DisplayName("Pause and resume encounter preserves state")
    void pauseAndResumeEncounter_preservesState() {
        UUID encounterId = UUID.randomUUID();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.ACTIVE)
                .currentTurnIndex(2)
                .roundNumber(3)
                .participants(new ArrayList<>())
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Pause
        EncounterResponse pauseResponse = encounterService.pauseEncounter(encounterId, dmUserId);
        assertEquals("PAUSED", pauseResponse.getStatus());
        assertEquals(2, pauseResponse.getCurrentTurnIndex());
        assertEquals(3, pauseResponse.getRoundNumber());

        // Resume
        EncounterResponse resumeResponse = encounterService.resumeEncounter(encounterId, dmUserId);
        assertEquals("ACTIVE", resumeResponse.getStatus());
        assertEquals(2, resumeResponse.getCurrentTurnIndex());
        assertEquals(3, resumeResponse.getRoundNumber());
    }

    @Test
    @DisplayName("End encounter sets status to COMPLETED")
    void endEncounter_setsCompleted() {
        UUID encounterId = UUID.randomUUID();

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.ACTIVE)
                .roundNumber(5)
                .participants(new ArrayList<>())
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.endEncounter(encounterId, dmUserId);

        assertEquals("COMPLETED", response.getStatus());
    }

    @Test
    @DisplayName("Session code lookup returns correct encounter (case-insensitive)")
    void getEncounterBySessionCode_returnsCorrectEncounter() {
        String sessionCode = "ABC12345";

        Encounter encounter = Encounter.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .name("Found Encounter")
                .status(EncounterStatus.ACTIVE)
                .sessionCode(sessionCode)
                .participants(new ArrayList<>())
                .build();

        when(encounterRepository.findBySessionCode("ABC12345")).thenReturn(Optional.of(encounter));

        EncounterResponse response = encounterService.getEncounterBySessionCode("abc12345");

        assertEquals("Found Encounter", response.getName());
        assertEquals(sessionCode, response.getSessionCode());
    }
}
