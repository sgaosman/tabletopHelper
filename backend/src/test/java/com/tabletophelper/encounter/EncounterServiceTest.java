package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.encounter.state.KnownSpell;
import com.tabletophelper.encounter.state.SpellSlot;
import com.tabletophelper.identity.User;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
    @Mock private com.tabletophelper.resourcepool.ResourcePoolService resourcePoolService;
    @Mock private MonsterSpellCastService monsterSpellCastService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private EncounterService encounterService;

    private UUID dmUserId;
    private UUID campaignId;
    private Campaign campaign;
    private User dmUser;

    @BeforeEach
    void setUp() {
        AccessPolicy accessPolicy = new AccessPolicy(campaignRepository, campaignMemberRepository);
        encounterService = new EncounterService(encounterRepository, participantRepository, campaignRepository,
                characterRepository, monsterRepository, new ObjectMapper(), resourcePoolService,
                monsterSpellCastService, accessPolicy,
                new EncounterCommands(encounterRepository, accessPolicy, eventPublisher));

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

        // The access policy looks the campaign up to find its DM
        lenient().when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
    }

    /** A command finds the encounter's campaign, then locks its row (D160); both find this encounter. */
    private void stubLock(Encounter encounter) {
        lenient().when(encounterRepository.findCampaignIdById(encounter.getId())).thenReturn(Optional.of(campaignId));
        lenient().when(encounterRepository.lockById(encounter.getId())).thenReturn(Optional.of(encounter));
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

        stubLock(encounter);
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

        stubLock(encounter);
        when(characterRepository.findById(characterId)).thenReturn(Optional.of(thorin));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerUser.getId())).thenReturn(true);
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
    @DisplayName("A PC joins with the sheet's {total, used} slots as {max, used}, pact slots kept as pact_N, and its spells typed")
    void addPcParticipant_copiesSlotsAndSpellsTyped() {
        UUID encounterId = UUID.randomUUID();
        User playerUser = User.builder().id(UUID.randomUUID()).username("player1").email("player1@test.com")
                .passwordHash("hash").build();
        PlayerCharacter elara = PlayerCharacter.builder()
                .id(UUID.randomUUID()).name("Elara").user(playerUser)
                .hpMax(30).hpCurrent(30).armourClass(12).initiativeBonus(1)
                // A used count above the total, which the sheet allows, is capped
                .spellSlots("{\"1\":{\"total\":4,\"used\":1},\"2\":{\"total\":2,\"used\":5},\"pact_2\":{\"total\":2,\"used\":0}}")
                // Spells stay untyped on the sheet until R6, so an unknown property is ignored
                .spellsKnown("[{\"name\":\"Shield\",\"level\":1,\"source\":\"class:Wizard\",\"prepared\":true},"
                        + "{\"name\":\"Eldritch Blast\",\"level\":0,\"source\":\"class:Warlock\",\"colour\":\"green\"}]")
                .build();
        Encounter encounter = Encounter.builder()
                .id(encounterId).campaign(campaign).name("Test Encounter")
                .status(EncounterStatus.PREPARING).participants(new ArrayList<>())
                .build();

        AddParticipantRequest request = new AddParticipantRequest();
        request.setParticipantType("PLAYER");
        request.setCharacterId(elara.getId());

        stubLock(encounter);
        when(characterRepository.findById(elara.getId())).thenReturn(Optional.of(elara));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerUser.getId())).thenReturn(true);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterResponse response = encounterService.addParticipant(encounterId, request, dmUserId);

        EncounterResponse.ParticipantResponse participant = response.getParticipants().get(0);
        assertEquals(Map.of(
                "1", new SpellSlot(4, 1),
                "2", new SpellSlot(2, 2),
                "pact_2", new SpellSlot(2, 0)), participant.getSpellSlotsCurrent());
        assertEquals(List.of(
                new KnownSpell(null, "Shield", 1, "class:Wizard", true, null, null, null, null),
                new KnownSpell(null, "Eldritch Blast", 0, "class:Warlock", null, null, null, null, null)),
                participant.getSpellsKnown());
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

        stubLock(encounter);
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

        stubLock(encounter);
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

        stubLock(encounter);
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

        stubLock(encounter);
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

        stubLock(encounter);
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

        stubLock(encounter);

        ConflictException ex = assertThrows(ConflictException.class,
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

        stubLock(encounter);
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

        stubLock(encounter);
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
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, dmUserId)).thenReturn(true);

        EncounterResponse response = encounterService.getEncounterBySessionCode("abc12345", dmUserId);

        assertEquals("Found Encounter", response.getName());
        assertEquals(sessionCode, response.getSessionCode());
    }

    @Test
    @DisplayName("Session code lookup refuses a user outside the campaign")
    void getEncounterBySessionCode_refusesNonMember() {
        UUID strangerId = UUID.randomUUID();
        Encounter encounter = Encounter.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .name("Found Encounter")
                .status(EncounterStatus.ACTIVE)
                .sessionCode("ABC12345")
                .participants(new ArrayList<>())
                .build();

        when(encounterRepository.findBySessionCode("ABC12345")).thenReturn(Optional.of(encounter));

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> encounterService.getEncounterBySessionCode("ABC12345", strangerId));

        assertEquals("You are not a member of this campaign", ex.getMessage());
    }

    @Test
    @DisplayName("An unknown session code is a NotFoundException")
    void getEncounterBySessionCode_unknownCodeIsNotFound() {
        when(encounterRepository.findBySessionCode("ZZZZ9999")).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> encounterService.getEncounterBySessionCode("zzzz9999", dmUserId));

        assertEquals("Invalid session code", ex.getMessage());
    }

    @Test
    @DisplayName("A character whose owner has not joined the campaign cannot be added")
    void addPcParticipant_refusesCharacterOfNonMember() {
        UUID encounterId = UUID.randomUUID();
        User outsider = User.builder().id(UUID.randomUUID()).username("outsider").build();
        PlayerCharacter stray = PlayerCharacter.builder()
                .id(UUID.randomUUID()).name("Stray").user(outsider)
                .hpMax(10).hpCurrent(10).armourClass(12).initiativeBonus(0)
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
        request.setCharacterId(stray.getId());
        request.setDisplayName("Stray");

        stubLock(encounter);
        when(characterRepository.findById(stray.getId())).thenReturn(Optional.of(stray));

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> encounterService.addParticipant(encounterId, request, dmUserId));

        assertEquals("This character's owner is not a member of this campaign", ex.getMessage());
        assertTrue(encounter.getParticipants().isEmpty());
        verify(encounterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Monster action templates are for the DM only")
    void getMonsterActionTemplates_refusesPlayer() {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.ACTIVE)
                .participants(new ArrayList<>())
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> encounterService.getMonsterActionTemplates(encounterId, UUID.randomUUID(), UUID.randomUUID()));

        assertEquals("Only the DM can perform this action", ex.getMessage());
    }

    @Test
    @DisplayName("An unknown encounter is a NotFoundException")
    void getEncounter_unknownIsNotFound() {
        UUID encounterId = UUID.randomUUID();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> encounterService.getEncounter(encounterId, dmUserId));

        assertEquals("Encounter not found", ex.getMessage());
    }

    // ── Hiding participants and choosing a view (D157) ──────────

    private Encounter activeEncounterWith(EncounterParticipant... participants) {
        Encounter encounter = Encounter.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.ACTIVE)
                .participants(new ArrayList<>(List.of(participants)))
                .build();
        lenient().when(encounterRepository.findById(encounter.getId())).thenReturn(Optional.of(encounter));
        stubLock(encounter);
        lenient().when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return encounter;
    }

    private static EncounterParticipant participant(String name, ParticipantType type) {
        return EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(type)
                .displayName(name)
                .hpMax(10)
                .hpCurrent(10)
                .build();
    }

    @Test
    @DisplayName("The DM can hide a monster from players and show it again")
    void setParticipantVisibility_dmHidesAndShowsMonster() {
        EncounterParticipant bugbear = participant("Bugbear", ParticipantType.MONSTER);
        Encounter encounter = activeEncounterWith(bugbear);

        EncounterResponse hidden = encounterService.setParticipantVisibility(encounter.getId(), bugbear.getId(), false, dmUserId);
        assertFalse(bugbear.getIsVisibleToPlayers());
        assertFalse(hidden.getParticipants().get(0).getIsVisibleToPlayers());

        encounterService.setParticipantVisibility(encounter.getId(), bugbear.getId(), true, dmUserId);
        assertTrue(bugbear.getIsVisibleToPlayers());
    }

    @Test
    @DisplayName("A player character cannot be hidden")
    void setParticipantVisibility_refusesPlayerCharacter() {
        EncounterParticipant gimli = participant("Gimli", ParticipantType.PLAYER);
        Encounter encounter = activeEncounterWith(gimli);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> encounterService.setParticipantVisibility(encounter.getId(), gimli.getId(), false, dmUserId));

        assertEquals("Only monsters can be hidden from players", ex.getMessage());
        assertTrue(gimli.getIsVisibleToPlayers());
        verify(encounterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Only the DM can hide a participant")
    void setParticipantVisibility_refusesPlayer() {
        EncounterParticipant bugbear = participant("Bugbear", ParticipantType.MONSTER);
        Encounter encounter = activeEncounterWith(bugbear);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> encounterService.setParticipantVisibility(encounter.getId(), bugbear.getId(), false, UUID.randomUUID()));

        assertEquals("Only the DM can perform this action", ex.getMessage());
        assertTrue(bugbear.getIsVisibleToPlayers());
    }

    @Test
    @DisplayName("Hiding an unknown participant is a NotFoundException")
    void setParticipantVisibility_unknownParticipantIsNotFound() {
        Encounter encounter = activeEncounterWith(participant("Bugbear", ParticipantType.MONSTER));

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> encounterService.setParticipantVisibility(encounter.getId(), UUID.randomUUID(), false, dmUserId));

        assertEquals("Participant not found", ex.getMessage());
    }

    @Test
    @DisplayName("The response carries the campaign's DM and HP setting, which choose the view")
    void toResponse_carriesTheViewContext() {
        campaign.setShowExactMonsterHp(true);
        Encounter encounter = activeEncounterWith(participant("Bugbear", ParticipantType.MONSTER));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, dmUserId)).thenReturn(true);

        EncounterResponse response = encounterService.getEncounter(encounter.getId(), dmUserId);

        assertEquals(dmUserId, response.getDmUserId());
        assertTrue(response.isShowExactMonsterHp());
    }

    // ── Lifecycle and locking (D160, D166) ──────────────────────

    private Encounter encounterIn(EncounterStatus status, EncounterParticipant... participants) {
        Encounter encounter = Encounter.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .name("Test Encounter")
                .status(status)
                .participants(new ArrayList<>(List.of(participants)))
                .build();
        stubLock(encounter);
        lenient().when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return encounter;
    }

    @Test
    @DisplayName("Completing an encounter syncs its pools back once; completing it again is a 409 that syncs nothing")
    void endEncounter_syncsPoolsBackOnce() {
        PlayerCharacter thorin = PlayerCharacter.builder().id(UUID.randomUUID()).name("Thorin").build();
        EncounterParticipant participant = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(ParticipantType.PLAYER)
                .character(thorin)
                .displayName("Thorin")
                .hpMax(45).hpCurrent(45).armourClass(18)
                .build();
        Encounter encounter = encounterIn(EncounterStatus.ACTIVE, participant);

        encounterService.endEncounter(encounter.getId(), dmUserId);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> encounterService.endEncounter(encounter.getId(), dmUserId));

        assertEquals("The encounter is already completed", ex.getMessage());
        assertEquals(EncounterStatus.COMPLETED, encounter.getStatus());
        verify(resourcePoolService, times(1)).syncBackToCharacter(participant, thorin);
    }

    @Test
    @DisplayName("A Paused encounter can be completed without resuming it")
    void endEncounter_completesPausedEncounter() {
        Encounter encounter = encounterIn(EncounterStatus.PAUSED);

        EncounterResponse response = encounterService.endEncounter(encounter.getId(), dmUserId);

        assertEquals("COMPLETED", response.getStatus());
    }

    @Test
    @DisplayName("A Completed encounter is final: resuming, pausing or starting it again is a 409")
    void completedEncounterIsFinal() {
        UUID encounterId = encounterIn(EncounterStatus.COMPLETED).getId();

        ConflictException resume = assertThrows(ConflictException.class,
                () -> encounterService.resumeEncounter(encounterId, dmUserId));
        ConflictException pause = assertThrows(ConflictException.class,
                () -> encounterService.pauseEncounter(encounterId, dmUserId));
        ConflictException start = assertThrows(ConflictException.class,
                () -> encounterService.startEncounter(encounterId, dmUserId));

        assertEquals("Cannot resume an encounter that is completed", resume.getMessage());
        assertEquals("Cannot pause an encounter that is completed", pause.getMessage());
        assertEquals("Cannot start an encounter that is completed", start.getMessage());
        verify(encounterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Resume continues only a Paused encounter: it never starts a Preparing one")
    void resumeEncounter_onlyContinuesPausedEncounter() {
        Encounter preparing = encounterIn(EncounterStatus.PREPARING);
        Encounter active = encounterIn(EncounterStatus.ACTIVE);

        ConflictException fromPreparing = assertThrows(ConflictException.class,
                () -> encounterService.resumeEncounter(preparing.getId(), dmUserId));
        ConflictException fromActive = assertThrows(ConflictException.class,
                () -> encounterService.resumeEncounter(active.getId(), dmUserId));

        assertEquals("Cannot resume an encounter that is still being prepared", fromPreparing.getMessage());
        assertEquals("The encounter is already active", fromActive.getMessage());
        assertEquals(EncounterStatus.PREPARING, preparing.getStatus());
        assertNull(preparing.getSessionCode());
    }

    @Test
    @DisplayName("Only an Active encounter can be paused")
    void pauseEncounter_onlyPausesActiveEncounter() {
        Encounter preparing = encounterIn(EncounterStatus.PREPARING);
        Encounter paused = encounterIn(EncounterStatus.PAUSED);

        ConflictException fromPreparing = assertThrows(ConflictException.class,
                () -> encounterService.pauseEncounter(preparing.getId(), dmUserId));
        ConflictException fromPaused = assertThrows(ConflictException.class,
                () -> encounterService.pauseEncounter(paused.getId(), dmUserId));

        assertEquals("Cannot pause an encounter that is still being prepared", fromPreparing.getMessage());
        assertEquals("The encounter is already paused", fromPaused.getMessage());
        assertEquals(EncounterStatus.PREPARING, preparing.getStatus());
    }

    @Test
    @DisplayName("Starting an encounter that is already running is a 409, checked before its initiatives")
    void startEncounter_onlyStartsPreparingEncounter() {
        Encounter encounter = encounterIn(EncounterStatus.ACTIVE, participant("Goblin", ParticipantType.MONSTER));
        encounter.setSessionCode("ABC12345");

        ConflictException ex = assertThrows(ConflictException.class,
                () -> encounterService.startEncounter(encounter.getId(), dmUserId));

        assertEquals("The encounter is already active", ex.getMessage());
        assertEquals("ABC12345", encounter.getSessionCode());
    }

    @Test
    @DisplayName("Access is checked by campaign ID before the row is locked, so a player locks nothing")
    void command_checksAccessBeforeLocking() {
        Encounter encounter = encounterIn(EncounterStatus.ACTIVE);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> encounterService.pauseEncounter(encounter.getId(), UUID.randomUUID()));

        assertEquals("Only the DM can perform this action", ex.getMessage());
        verify(encounterRepository, never()).lockById(any());
        verifyNoInteractions(eventPublisher);
        assertEquals(EncounterStatus.ACTIVE, encounter.getStatus());
    }

    @Test
    @DisplayName("A command on an unknown encounter is a NotFoundException and locks nothing")
    void command_unknownEncounterIsNotFound() {
        UUID unknownId = UUID.randomUUID();

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> encounterService.pauseEncounter(unknownId, dmUserId));

        assertEquals("Encounter not found", ex.getMessage());
        verify(encounterRepository, never()).lockById(any());
    }

    @Test
    @DisplayName("A command limits its lock wait, locks the encounter, then announces the change to the broadcaster")
    void command_locksThenAnnouncesTheChange() {
        Encounter encounter = encounterIn(EncounterStatus.ACTIVE);

        encounterService.pauseEncounter(encounter.getId(), dmUserId);

        InOrder inOrder = inOrder(encounterRepository, eventPublisher);
        inOrder.verify(encounterRepository).findCampaignIdById(encounter.getId());
        inOrder.verify(encounterRepository).limitLockWaits();
        inOrder.verify(encounterRepository).lockById(encounter.getId());
        inOrder.verify(eventPublisher).publishEvent(new EncounterChanged(encounter.getId()));
    }
}
