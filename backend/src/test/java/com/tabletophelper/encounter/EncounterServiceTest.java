package com.tabletophelper.encounter;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CombatResults;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.WriteBackService;
import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.encounter.internal.CombatLogWriter;
import com.tabletophelper.encounter.internal.CreatureStats;
import com.tabletophelper.encounter.internal.EffectApplier;
import com.tabletophelper.encounter.internal.TurnProcessor;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.identity.User;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;
import java.util.stream.Stream;

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
    @Mock private com.tabletophelper.encounter.internal.MonsterPools monsterPools;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CombatLogRepository combatLogRepository;
    @Mock private WriteBackService writeBackService;

    private EncounterService encounterService;

    private UUID dmUserId;
    private UUID campaignId;
    private Campaign campaign;
    private User dmUser;

    @BeforeEach
    void setUp() {
        AccessPolicy accessPolicy = new AccessPolicy(campaignRepository, campaignMemberRepository);
        ObjectMapper objectMapper = JacksonDefaults.mapper();
        CombatLogWriter combatLog = new CombatLogWriter(combatLogRepository);
        TurnProcessor turnProcessor = new TurnProcessor(new EffectApplier(new Resolver(Rng.random()),
                new CreatureStats(objectMapper), combatLog, resourcePoolService), combatLog);
        encounterService = new EncounterService(encounterRepository, participantRepository, campaignRepository,
                characterRepository, monsterRepository, objectMapper, monsterPools, accessPolicy,
                new EncounterCommands(encounterRepository, accessPolicy, eventPublisher),
                new CombatLogReader(combatLogRepository), turnProcessor,
                new EncounterResults(eventPublisher, writeBackService));

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

    /** The encounter as saving returns it: its new participants get IDs, as persisting them would. */
    private static Encounter saved(org.mockito.invocation.InvocationOnMock invocation) {
        Encounter encounter = invocation.getArgument(0);
        encounter.getParticipants().stream().filter(p -> p.getId() == null).forEach(p -> p.setId(UUID.randomUUID()));
        return encounter;
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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

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
    @DisplayName("A monster with an odd Dexterity below 10 rounds its initiative modifier down")
    void addMonsterParticipant_oddDexterityBelowTenRoundsDown() {
        UUID encounterId = UUID.randomUUID();
        UUID monsterId = UUID.randomUUID();

        Monster aboleth = Monster.builder()
                .id(monsterId)
                .name("Aboleth")
                .hitPoints(135)
                .armourClass(17)
                .dexterity(9) // DEX mod -1
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
        request.setDisplayName("Aboleth");
        request.setQuantity(1);

        stubLock(encounter);
        when(monsterRepository.findById(monsterId)).thenReturn(Optional.of(aboleth));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

        EncounterResponse response = encounterService.addParticipant(encounterId, request, dmUserId);

        assertEquals(-1, response.getParticipants().get(0).getInitiativeModifier());
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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

        EncounterResponse response = encounterService.addParticipant(encounterId, request, dmUserId);

        assertEquals(1, response.getParticipants().size());
        EncounterResponse.ParticipantResponse participant = response.getParticipants().get(0);
        assertEquals("Thorin", participant.getDisplayName());
        assertEquals("PLAYER", participant.getParticipantType());
        assertEquals(45, participant.getHpMax());
        assertEquals(45, participant.getHpCurrent());
        assertEquals(18, participant.getArmourClass());
        assertEquals(2, participant.getInitiativeModifier());
        assertEquals("ALIVE", participant.getLifeState());
    }

    @Test
    @DisplayName("A PC who joins at 0 HP is Stable and unconscious, so their hit points and life state agree (D161)")
    void addPcParticipant_atZeroHpIsStable() {
        UUID encounterId = UUID.randomUUID();
        User playerUser = User.builder().id(UUID.randomUUID()).username("player1").email("player1@test.com")
                .passwordHash("hash").build();
        PlayerCharacter thorin = PlayerCharacter.builder()
                .id(UUID.randomUUID()).name("Thorin").user(playerUser)
                .strength(16).dexterity(14).constitution(14).intelligence(10).wisdom(12).charisma(8)
                .hpMax(45).hpCurrent(0).armourClass(18)
                .build();
        Encounter encounter = Encounter.builder()
                .id(encounterId).campaign(campaign).name("Test Encounter")
                .status(EncounterStatus.PREPARING).participants(new ArrayList<>())
                .build();
        AddParticipantRequest request = new AddParticipantRequest();
        request.setParticipantType("PLAYER");
        request.setCharacterId(thorin.getId());
        request.setDisplayName("Thorin");
        stubLock(encounter);
        when(characterRepository.findById(thorin.getId())).thenReturn(Optional.of(thorin));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerUser.getId())).thenReturn(true);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

        EncounterResponse.ParticipantResponse participant =
                encounterService.addParticipant(encounterId, request, dmUserId).getParticipants().get(0);

        assertEquals(0, participant.getHpCurrent());
        assertEquals("STABLE", participant.getLifeState());
        assertEquals(List.of("unconscious"),
                participant.getActiveConditions().stream().map(c -> c.name()).toList());
        assertEquals(com.tabletophelper.encounter.state.EndRule.HP_REGAINED,
                participant.getActiveConditions().get(0).endRule());
    }

    @Test
    @DisplayName("A PC joins with its sheet's temporary hit points, and keeps the totals it joined with for Write-back (D166)")
    void addPcParticipant_keepsWhatItJoinedWith() {
        User playerUser = User.builder().id(UUID.randomUUID()).username("player1").build();
        PlayerCharacter thorin = PlayerCharacter.builder()
                .id(UUID.randomUUID()).name("Thorin").user(playerUser)
                .hpMax(45).hpCurrent(40).hpTemp(5).armourClass(18)
                .spellSlots(Map.of("1", new SpellSlot(2, 1)))
                .resourcePools(List.of(rage(1)))
                .build();
        Encounter encounter = encounterIn(EncounterStatus.PREPARING);
        AddParticipantRequest request = new AddParticipantRequest();
        request.setParticipantType("PLAYER");
        request.setCharacterId(thorin.getId());
        when(characterRepository.findById(thorin.getId())).thenReturn(Optional.of(thorin));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerUser.getId())).thenReturn(true);

        encounterService.addParticipant(encounter.getId(), request, dmUserId);

        EncounterParticipant participant = encounter.getParticipants().getFirst();
        assertEquals(40, participant.getHpCurrent());
        assertEquals(5, participant.getHpTemp());
        assertEquals(new CombatTotals(40, 5, Map.of("1", 1), Map.of("class:barbarian-rage", 1)),
                participant.getJoinedWith());
        assertEquals(List.of(rage(1)), participant.getResourcePoolsCurrent());
    }

    @Test
    @DisplayName("A dead character can't join an encounter until the DM revives them (D166)")
    void addPcParticipant_refusesTheDead() {
        User playerUser = User.builder().id(UUID.randomUUID()).username("player1").build();
        PlayerCharacter thorin = PlayerCharacter.builder()
                .id(UUID.randomUUID()).name("Thorin").user(playerUser)
                .hpMax(45).hpCurrent(0).armourClass(18).dead(true)
                .build();
        Encounter encounter = encounterIn(EncounterStatus.PREPARING);
        AddParticipantRequest request = new AddParticipantRequest();
        request.setParticipantType("PLAYER");
        request.setCharacterId(thorin.getId());
        when(characterRepository.findById(thorin.getId())).thenReturn(Optional.of(thorin));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerUser.getId())).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> encounterService.addParticipant(encounter.getId(), request, dmUserId));

        assertEquals("Thorin is dead, so can't join an encounter until the DM revives them", ex.getMessage());
        assertTrue(encounter.getParticipants().isEmpty());
    }

    @Test
    @DisplayName("Starting an encounter copies each PC again from its sheet as it stands, keeping the initiative the DM set (D166)")
    void startEncounter_copiesPlayerCharactersAgain() {
        EncounterParticipant fighter = fighting("Fighter", ParticipantType.PLAYER, 18, 10, 0);
        EncounterParticipant goblin = fighting("Goblin", ParticipantType.MONSTER, 12, 14, 1);
        Encounter encounter = encounterIn(EncounterStatus.PREPARING, fighter, goblin);
        when(encounterRepository.findBySessionCode(anyString())).thenReturn(Optional.empty());
        // Hurt and rested on the sheet since it was added
        PlayerCharacter sheet = fighter.getCharacter();
        sheet.setHpCurrent(22);
        sheet.setHpTemp(3);
        sheet.setInitiativeBonus(4);
        sheet.setSpellSlots(Map.of("1", new SpellSlot(2, 1)));

        encounterService.startEncounter(encounter.getId(), dmUserId);

        assertEquals(22, fighter.getHpCurrent());
        assertEquals(3, fighter.getHpTemp());
        assertEquals(4, fighter.getInitiativeModifier());
        assertEquals(18, fighter.getInitiative());
        assertEquals(new CombatTotals(22, 3, Map.of("1", 1), Map.of()), fighter.getJoinedWith());
        assertEquals(Map.of("1", new SpellSlot(2, 1)), fighter.getSpellSlotsCurrent());
        assertNull(goblin.getJoinedWith());

        PlayerCharacter dead = PlayerCharacter.builder().id(UUID.randomUUID()).name("Brom")
                .user(User.builder().id(UUID.randomUUID()).build()).hpMax(20).hpCurrent(0).dead(true).build();
        Encounter another = encounterIn(EncounterStatus.PREPARING, EncounterParticipant.builder()
                .id(UUID.randomUUID()).participantType(ParticipantType.PLAYER).character(dead).displayName("Brom")
                .initiative(9).hpMax(20).hpCurrent(20).armourClass(12).build());
        ConflictException ex = assertThrows(ConflictException.class,
                () -> encounterService.startEncounter(another.getId(), dmUserId));
        assertEquals("Brom has died since joining, so the encounter can't start with them until the DM revives them "
                + "or removes them from it", ex.getMessage());
        assertEquals(EncounterStatus.PREPARING, another.getStatus());
    }

    @Test
    @DisplayName("The response shows the participant's life state, and its Concentration Instance's spell and slot")
    void toResponse_showsLifeStateAndConcentration() {
        EncounterParticipant wizard = participant("Wizard", ParticipantType.PLAYER);
        wizard.setConcentration(com.tabletophelper.encounter.state.ConcentrationInstance.start("Haste", 3, 2, 10));
        EncounterParticipant goblin = participant("Goblin", ParticipantType.MONSTER);
        goblin.setHpCurrent(0);
        goblin.setLifeState(com.tabletophelper.rules.combat.LifeState.DEAD);
        Encounter encounter = activeEncounterWith(wizard, goblin);
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, dmUserId)).thenReturn(true);

        EncounterResponse response = encounterService.getEncounter(encounter.getId(), dmUserId);

        EncounterResponse.ParticipantResponse wizardView = response.getParticipants().stream()
                .filter(p -> p.getDisplayName().equals("Wizard")).findFirst().orElseThrow();
        EncounterResponse.ParticipantResponse goblinView = response.getParticipants().stream()
                .filter(p -> p.getDisplayName().equals("Goblin")).findFirst().orElseThrow();
        assertEquals("Haste", wizardView.getConcentrationSpell());
        assertEquals(3, wizardView.getConcentrationSlotLevel());
        assertEquals("ALIVE", wizardView.getLifeState());
        assertNull(goblinView.getConcentrationSpell());
        assertEquals("DEAD", goblinView.getLifeState());
    }

    // ── The combat log (D157), from CombatServiceTest ──────────

    @Test
    @DisplayName("Reading the combat log requires campaign membership")
    void combatLogRequiresMembership() {
        Encounter encounter = activeEncounterWith(participant("Goblin", ParticipantType.MONSTER));
        UUID strangerId = UUID.randomUUID();

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> encounterService.getCombatLog(encounter.getId(), strangerId));

        assertEquals("You are not a member of this campaign", ex.getMessage());
        verify(combatLogRepository, never()).findByEncounterIdOrderByCreatedAtAsc(any());
    }

    @Test
    @DisplayName("A player's combat log leaves out entries about hidden monsters; the DM's keeps them (D157)")
    void combatLogLeavesOutHiddenMonstersForPlayers() {
        UUID playerUserId = UUID.randomUUID();
        EncounterParticipant rogue = participant("Rogue", ParticipantType.PLAYER);
        EncounterParticipant goblin = participant("Goblin", ParticipantType.MONSTER);
        EncounterParticipant bugbear = participant("Bugbear", ParticipantType.MONSTER);
        bugbear.setIsVisibleToPlayers(false);
        Encounter encounter = activeEncounterWith(rogue, goblin, bugbear);
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, playerUserId)).thenReturn(true);
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, dmUserId)).thenReturn(true);
        when(combatLogRepository.findByEncounterIdOrderByCreatedAtAsc(encounter.getId())).thenReturn(List.of(
                logEntry(encounter, "Rogue hits Goblin", rogue, goblin),
                logEntry(encounter, "Bugbear hits Rogue", bugbear, rogue)));

        var playerLog = encounterService.getCombatLog(encounter.getId(), playerUserId);
        var dmLog = encounterService.getCombatLog(encounter.getId(), dmUserId);

        assertEquals(List.of("Rogue hits Goblin"), playerLog.stream().map(CombatLogResponse::getDescription).toList());
        assertEquals(2, dmLog.size());
    }

    private static CombatLog logEntry(Encounter encounter, String description, EncounterParticipant actor,
                                      EncounterParticipant target) {
        return CombatLog.builder()
                .id(UUID.randomUUID())
                .encounter(encounter)
                .roundNumber(1)
                .actorId(actor.getId())
                .actorName(actor.getDisplayName())
                .targetId(target.getId())
                .targetName(target.getDisplayName())
                .actionType(CombatActionType.ATTACK)
                .description(description)
                .build();
    }

    @Test
    @DisplayName("A PC joins with copies of the sheet's {max, used} slots, pact slots kept as pact_N, and its spells")
    void addPcParticipant_copiesSlotsAndSpellsTyped() {
        UUID encounterId = UUID.randomUUID();
        User playerUser = User.builder().id(UUID.randomUUID()).username("player1").email("player1@test.com")
                .passwordHash("hash").build();
        PlayerCharacter elara = PlayerCharacter.builder()
                .id(UUID.randomUUID()).name("Elara").user(playerUser)
                .hpMax(30).hpCurrent(30).armourClass(12).initiativeBonus(1)
                // A used count above the maximum, which the sheet allows, is capped
                .spellSlots(Map.of("1", new SpellSlot(4, 1), "2", new SpellSlot(2, 5), "pact_2", new SpellSlot(2, 0)))
                .spellsKnown(List.of(
                        new KnownSpell(null, "Shield", 1, "class:Wizard", true, null, null, null, null),
                        new KnownSpell(null, "Eldritch Blast", 0, "class:Warlock", null, null, null, null, null)))
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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

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
    @DisplayName("Pause and resume encounter preserves state, the current turn included")
    void pauseAndResumeEncounter_preservesState() {
        UUID encounterId = UUID.randomUUID();
        List<EncounterParticipant> participants = new ArrayList<>();
        for (String name : List.of("Fighter", "Wizard", "Goblin")) {
            participants.add(EncounterParticipant.builder()
                    .id(UUID.randomUUID())
                    .participantType(ParticipantType.PLAYER)
                    .displayName(name)
                    .initiative(20 - participants.size())
                    .sortOrder(participants.size())
                    .hpMax(20).hpCurrent(20).armourClass(12)
                    .build());
        }

        Encounter encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.ACTIVE)
                .currentTurnParticipantId(participants.get(2).getId())
                .roundNumber(3)
                .participants(participants)
                .build();

        stubLock(encounter);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

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
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);

        EncounterResponse response = encounterService.endEncounter(encounterId, true, dmUserId);

        assertEquals("COMPLETED", response.getStatus());
        assertEquals(Boolean.TRUE, encounter.getResultsApplied());
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
        lenient().when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);
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
        lenient().when(encounterRepository.save(any(Encounter.class))).thenAnswer(EncounterServiceTest::saved);
        return encounter;
    }

    @Test
    @DisplayName("Completing an encounter publishes its player characters' results once; completing it again is a 409 that publishes nothing")
    void endEncounter_publishesResultsOnce() {
        EncounterParticipant thorin = joined("Thorin", 45, 30);
        thorin.setHpTemp(4);
        thorin.setResourcePoolsCurrent(List.of(rage(1)));
        EncounterParticipant goblin = participant("Goblin", ParticipantType.MONSTER);
        Encounter encounter = encounterIn(EncounterStatus.ACTIVE, goblin, thorin);

        encounterService.endEncounter(encounter.getId(), true, dmUserId);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> encounterService.endEncounter(encounter.getId(), true, dmUserId));

        assertEquals("The encounter is already completed", ex.getMessage());
        assertEquals(EncounterStatus.COMPLETED, encounter.getStatus());
        List<CombatResults> published = publishedResults();
        assertEquals(1, published.size());
        assertEquals(encounter.getId(), published.getFirst().encounterId());
        assertEquals(List.of(new CombatResults.CharacterResult(thorin.getCharacter().getId(),
                        new CombatTotals(45, 0, Map.of(), Map.of("class:barbarian-rage", 2)),
                        new CombatTotals(30, 4, Map.of(), Map.of("class:barbarian-rage", 1)),
                        LifeState.ALIVE)),
                published.getFirst().characters());
    }

    @Test
    @DisplayName("Completing an encounter without its results publishes nothing, and records the DM's choice")
    void endEncounter_declinedPublishesNothing() {
        Encounter encounter = encounterIn(EncounterStatus.PAUSED, joined("Thorin", 45, 0));

        encounterService.endEncounter(encounter.getId(), false, dmUserId);

        assertEquals(EncounterStatus.COMPLETED, encounter.getStatus());
        assertEquals(Boolean.FALSE, encounter.getResultsApplied());
        assertEquals(List.of(), publishedResults());
    }

    @Test
    @DisplayName("Removing a player character mid-fight publishes its results then; removing one while preparing publishes none")
    void removeParticipant_publishesResultsMidFight() {
        EncounterParticipant thorin = joined("Thorin", 45, 20);
        EncounterParticipant brom = joined("Brom", 30, 30);
        Encounter active = encounterIn(EncounterStatus.ACTIVE, thorin, brom);
        EncounterParticipant cael = joined("Cael", 12, 12);
        Encounter preparing = encounterIn(EncounterStatus.PREPARING, cael);

        encounterService.removeParticipant(active.getId(), thorin.getId(), dmUserId);
        encounterService.removeParticipant(preparing.getId(), cael.getId(), dmUserId);

        List<CombatResults> published = publishedResults();
        assertEquals(1, published.size());
        assertEquals(List.of(thorin.getCharacter().getId()), published.getFirst().characters().stream()
                .map(CombatResults.CharacterResult::characterId).toList());
        assertEquals(20, published.getFirst().characters().getFirst().endedWith().hpCurrent());
        assertEquals(List.of("Brom"), active.getParticipants().stream().map(EncounterParticipant::getDisplayName).toList());
    }

    @Test
    @DisplayName("The End dialog's preview is the DM's alone, for an encounter that can be completed, and publishes nothing")
    void previewResults_isForTheDmOfAnEncounterUnderWay() {
        EncounterParticipant thorin = joined("Thorin", 45, 20);
        Encounter active = encounterIn(EncounterStatus.ACTIVE, thorin);
        Encounter preparing = encounterIn(EncounterStatus.PREPARING);
        when(encounterRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(encounterRepository.findById(preparing.getId())).thenReturn(Optional.of(preparing));
        when(writeBackService.preview(any(CombatResults.class))).thenReturn(List.of());

        encounterService.previewResults(active.getId(), dmUserId);
        assertThrows(ForbiddenException.class, () -> encounterService.previewResults(active.getId(), UUID.randomUUID()));
        ConflictException early = assertThrows(ConflictException.class,
                () -> encounterService.previewResults(preparing.getId(), dmUserId));

        assertEquals("Cannot complete an encounter that is still being prepared", early.getMessage());
        verify(writeBackService).preview(argThat(results -> results.characters().size() == 1
                && results.characters().getFirst().characterId().equals(thorin.getCharacter().getId())));
        assertEquals(List.of(), publishedResults());
    }

    // A player character's participant that joined with these hit points and two uses of Rage, and has these now
    private static EncounterParticipant joined(String name, int joinedHp, int hpNow) {
        PlayerCharacter character = PlayerCharacter.builder().id(UUID.randomUUID()).name(name)
                .user(User.builder().id(UUID.randomUUID()).username(name.toLowerCase()).build())
                .hpMax(joinedHp).hpCurrent(joinedHp).armourClass(15).dexterity(12)
                .build();
        return EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(ParticipantType.PLAYER)
                .character(character)
                .displayName(name)
                .initiative(10)
                .hpMax(joinedHp).hpCurrent(hpNow).armourClass(15)
                .lifeState(hpNow > 0 ? LifeState.ALIVE : LifeState.DYING)
                .resourcePoolsCurrent(List.of(rage(2)))
                .joinedWith(new CombatTotals(joinedHp, 0, Map.of(), Map.of("class:barbarian-rage", 2)))
                .build();
    }

    private static ResourcePoolEntry rage(int currentUses) {
        return new ResourcePoolEntry("class:barbarian-rage", "Rage", "CLASS", null, 2, "2", currentUses, "longRest",
                null, null, "BONUS_ACTION", "flame", Map.of());
    }

    // The Write-back results the service published, beside the EncounterChanged events EncounterCommands publishes
    private List<CombatResults> publishedResults() {
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(0)).publishEvent(events.capture());
        return events.getAllValues().stream()
                .filter(CombatResults.class::isInstance)
                .map(CombatResults.class::cast)
                .toList();
    }

    @Test
    @DisplayName("A Paused encounter can be completed without resuming it")
    void endEncounter_completesPausedEncounter() {
        Encounter encounter = encounterIn(EncounterStatus.PAUSED);

        EncounterResponse response = encounterService.endEncounter(encounter.getId(), true, dmUserId);

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

    // ── The turn order (D163) ────────────────────────────────────

    private static EncounterParticipant fighting(String name, ParticipantType type, Integer initiative, int dexterity,
                                                 int sortOrder) {
        EncounterParticipant.EncounterParticipantBuilder participant = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .participantType(type)
                .displayName(name)
                .initiative(initiative)
                .sortOrder(sortOrder)
                .hpMax(30).hpCurrent(30).armourClass(14);
        if (type == ParticipantType.MONSTER) {
            participant.monster(Monster.builder().id(UUID.randomUUID()).name(name).dexterity(dexterity).build());
        } else {
            // A sheet that joins with the participant's hit points and AC, as starting an encounter copies it again
            participant.character(PlayerCharacter.builder().id(UUID.randomUUID()).name(name).dexterity(dexterity)
                    .user(User.builder().id(UUID.randomUUID()).username(name.toLowerCase()).build())
                    .hpMax(30).hpCurrent(30).armourClass(14)
                    .build());
        }
        return participant.build();
    }

    private static List<String> turnNames(EncounterResponse response) {
        return response.getTurnOrder().stream().map(EncounterResponse.TurnResponse::getName).toList();
    }

    @Test
    @DisplayName("The response lists the computed turn order with the Lair's turn, the current turn's place and the ties")
    void toResponse_computesTheTurnOrder() {
        EncounterParticipant rogue = fighting("Rogue", ParticipantType.PLAYER, 5, 16, 0);
        EncounterParticipant brom = fighting("Brom", ParticipantType.PLAYER, 15, 14, 1);
        EncounterParticipant aria = fighting("Aria", ParticipantType.PLAYER, 15, 14, 2);
        EncounterParticipant ogre = fighting("Ogre", ParticipantType.MONSTER, 22, 8, 3);
        EncounterParticipant dragon = fighting("Dragon", ParticipantType.MONSTER, 10, 10, 4);
        dragon.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [], "lairActions": [{"name": "Tremor"}]}"""));
        rogue.setHpCurrent(0);
        rogue.setLifeState(com.tabletophelper.rules.combat.LifeState.DYING);
        rogue.setDeathSaveDue(true);
        Encounter encounter = activeEncounterWith(rogue, brom, aria, ogre, dragon);
        encounter.setMonstersInLair(new ArrayList<>(List.of(dragon.getId())));
        encounter.setCurrentTurnParticipantId(aria.getId());
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, dmUserId)).thenReturn(true);

        EncounterResponse response = encounterService.getEncounter(encounter.getId(), dmUserId);

        assertEquals(List.of("Ogre", "Lair Actions", "Brom", "Aria", "Dragon", "Rogue"), turnNames(response));
        assertEquals(List.of("Ogre", "Brom", "Aria", "Dragon", "Rogue"),
                response.getParticipants().stream().map(EncounterResponse.ParticipantResponse::getDisplayName).toList());
        assertEquals(List.of(0, 2, 3, 4, 5),
                response.getParticipants().stream().map(EncounterResponse.ParticipantResponse::getSortOrder).toList());
        assertEquals(3, response.getCurrentTurnIndex());
        assertTrue(response.getTurnOrder().get(3).isCurrent());
        assertEquals(20, response.getTurnOrder().get(1).getInitiative());
        assertEquals(Arrays.asList(null, null, 2, 2, null, null),
                response.getTurnOrder().stream().map(EncounterResponse.TurnResponse::getTieGroup).toList());
        EncounterResponse.ParticipantResponse ariaView = response.getParticipants().get(2);
        assertTrue(ariaView.getIsCurrentTurn());
        assertNull(ariaView.getHasLairActions());
        assertTrue(response.getParticipants().get(3).getHasLairActions());
        assertFalse(response.getParticipants().get(0).getHasLairActions());
        assertTrue(response.getParticipants().get(4).getDeathSaveDue());
        assertEquals(List.of(dragon.getId()), response.getMonstersInLair());
    }

    @Test
    @DisplayName("Starting an encounter gives the first turn in the order, which starts")
    void startEncounter_givesTheFirstTurn() {
        EncounterParticipant goblin = fighting("Goblin", ParticipantType.MONSTER, 12, 14, 0);
        EncounterParticipant fighter = fighting("Fighter", ParticipantType.PLAYER, 18, 10, 1);
        fighter.setTurnEconomy(new TurnEconomy(1, 1, true, 1, true, true, true, false, false, true, false, false));
        Encounter encounter = encounterIn(EncounterStatus.PREPARING, goblin, fighter);
        when(encounterRepository.findBySessionCode(anyString())).thenReturn(Optional.empty());

        EncounterResponse response = encounterService.startEncounter(encounter.getId(), dmUserId);

        assertEquals(fighter.getId(), encounter.getCurrentTurnParticipantId());
        assertFalse(encounter.getLairTurn());
        assertEquals(TurnEconomy.fresh(), fighter.getTurnEconomy());
        assertEquals(0, response.getCurrentTurnIndex());
        assertEquals("Fighter", response.getTurnOrder().getFirst().getName());
    }

    @Test
    @DisplayName("Before the encounter starts there is no current turn")
    void toResponse_noTurnBeforeTheStart() {
        Encounter encounter = encounterIn(EncounterStatus.PREPARING,
                fighting("Goblin", ParticipantType.MONSTER, null, 14, 0));
        lenient().when(encounterRepository.findById(encounter.getId())).thenReturn(Optional.of(encounter));
        when(campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, dmUserId)).thenReturn(true);

        EncounterResponse response = encounterService.getEncounter(encounter.getId(), dmUserId);

        assertNull(response.getCurrentTurnIndex());
        assertFalse(response.getParticipants().getFirst().getIsCurrentTurn());
    }

    @Test
    @DisplayName("The DM reorders a tie: its turns swap places among themselves and nothing else moves")
    void reorderTie_swapsTheTiedTurns() {
        EncounterParticipant ogre = fighting("Ogre", ParticipantType.MONSTER, 20, 8, 0);
        EncounterParticipant aria = fighting("Aria", ParticipantType.PLAYER, 15, 14, 1);
        EncounterParticipant brom = fighting("Brom", ParticipantType.PLAYER, 15, 14, 2);
        EncounterParticipant cael = fighting("Cael", ParticipantType.PLAYER, 15, 14, 3);
        EncounterParticipant goblin = fighting("Goblin", ParticipantType.MONSTER, 15, 16, 4);
        Encounter encounter = encounterIn(EncounterStatus.ACTIVE, ogre, aria, brom, cael, goblin);
        encounter.setCurrentTurnParticipantId(aria.getId());

        EncounterResponse response = encounterService.reorderTie(encounter.getId(),
                new TieOrderRequest(List.of(cael.getId(), aria.getId(), brom.getId())), dmUserId);

        assertEquals(List.of("Ogre", "Goblin", "Cael", "Aria", "Brom"), turnNames(response));
        assertEquals(3, response.getCurrentTurnIndex(), "The turn stays with Aria");
        assertEquals(List.of(0, 3, 4, 2, 1),
                Stream.of(ogre, aria, brom, cael, goblin).map(EncounterParticipant::getSortOrder).toList());
    }

    @Test
    @DisplayName("Reordering anything but a whole tie on initiative and DEX is a 400; a Paused encounter is a 409")
    void reorderTie_refusals() {
        EncounterParticipant aria = fighting("Aria", ParticipantType.PLAYER, 15, 14, 0);
        EncounterParticipant brom = fighting("Brom", ParticipantType.PLAYER, 15, 14, 1);
        EncounterParticipant cael = fighting("Cael", ParticipantType.PLAYER, 15, 14, 2);
        EncounterParticipant goblin = fighting("Goblin", ParticipantType.MONSTER, 15, 16, 3);
        Encounter encounter = encounterIn(EncounterStatus.ACTIVE, aria, brom, cael, goblin);

        ValidationException dexterity = assertThrows(ValidationException.class, () -> encounterService.reorderTie(
                encounter.getId(), new TieOrderRequest(List.of(goblin.getId(), aria.getId())), dmUserId));
        ValidationException part = assertThrows(ValidationException.class, () -> encounterService.reorderTie(
                encounter.getId(), new TieOrderRequest(List.of(brom.getId(), aria.getId())), dmUserId));
        ValidationException twice = assertThrows(ValidationException.class, () -> encounterService.reorderTie(
                encounter.getId(), new TieOrderRequest(List.of(aria.getId(), aria.getId())), dmUserId));
        assertThrows(ValidationException.class, () -> encounterService.reorderTie(
                encounter.getId(), new TieOrderRequest(List.of(aria.getId(), UUID.randomUUID())), dmUserId));
        encounter.setStatus(EncounterStatus.PAUSED);
        assertThrows(ConflictException.class, () -> encounterService.reorderTie(encounter.getId(),
                new TieOrderRequest(List.of(cael.getId(), brom.getId(), aria.getId())), dmUserId));

        assertEquals("Only participants tied on both initiative and DEX can be reordered", dexterity.getMessage());
        assertEquals("Name every participant in the tie", part.getMessage());
        assertEquals("Name two or more tied participants, each once", twice.getMessage());
        assertEquals(List.of(0, 1, 2, 3),
                Stream.of(aria, brom, cael, goblin).map(EncounterParticipant::getSortOrder).toList());
    }

    @Test
    @DisplayName("Setting initiatives numbers the DM's order by the turn order, ties to the higher DEX")
    void setInitiatives_numbersTheOrder() {
        EncounterParticipant aria = fighting("Aria", ParticipantType.PLAYER, null, 12, 0);
        EncounterParticipant goblin = fighting("Goblin", ParticipantType.MONSTER, null, 14, 1);
        EncounterParticipant ogre = fighting("Ogre", ParticipantType.MONSTER, null, 8, 2);
        Encounter encounter = encounterIn(EncounterStatus.PREPARING, aria, goblin, ogre);
        BulkInitiativeRequest request = new BulkInitiativeRequest();
        request.setInitiatives(List.of(initiative(aria, 15), initiative(goblin, 15), initiative(ogre, 18)));

        encounterService.setInitiatives(encounter.getId(), request, dmUserId);

        assertEquals(List.of(2, 1, 0), Stream.of(aria, goblin, ogre).map(EncounterParticipant::getSortOrder).toList());
    }

    private static SetInitiativeRequest initiative(EncounterParticipant participant, int value) {
        SetInitiativeRequest request = new SetInitiativeRequest();
        request.setParticipantId(participant.getId());
        request.setInitiative(value);
        return request;
    }

    @Test
    @DisplayName("Removing the participant whose turn it is mid-fight passes the turn to the next in the order")
    void removeParticipant_passesTheTurnOn() {
        EncounterParticipant fighter = fighting("Fighter", ParticipantType.PLAYER, 18, 10, 0);
        EncounterParticipant goblin = fighting("Goblin", ParticipantType.MONSTER, 12, 14, 1);
        EncounterParticipant ogre = fighting("Ogre", ParticipantType.MONSTER, 8, 8, 2);
        Encounter encounter = encounterIn(EncounterStatus.ACTIVE, fighter, goblin, ogre);
        encounter.setCurrentTurnParticipantId(goblin.getId());

        EncounterResponse response = encounterService.removeParticipant(encounter.getId(), goblin.getId(), dmUserId);

        assertEquals(ogre.getId(), encounter.getCurrentTurnParticipantId());
        assertEquals(List.of("Fighter", "Ogre"), turnNames(response));
        assertEquals(1, response.getCurrentTurnIndex());
    }

    @Test
    @DisplayName("Participants can't be removed from a Paused or Completed encounter")
    void removeParticipant_refusedWhilePaused() {
        EncounterParticipant goblin = fighting("Goblin", ParticipantType.MONSTER, 12, 14, 0);
        Encounter paused = encounterIn(EncounterStatus.PAUSED, goblin);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> encounterService.removeParticipant(paused.getId(), goblin.getId(), dmUserId));

        assertEquals("Participants can be removed only while the encounter is prepared or active", ex.getMessage());
        assertEquals(1, paused.getParticipants().size());
    }
}
