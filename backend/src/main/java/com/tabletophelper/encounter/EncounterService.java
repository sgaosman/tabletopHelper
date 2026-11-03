package com.tabletophelper.encounter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CombatSnapshot;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.CombatResultPreview;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterLifecycle.Transition;
import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.encounter.internal.EncounterTurns;
import com.tabletophelper.encounter.internal.MonsterPools;
import com.tabletophelper.encounter.internal.MonsterTemplates;
import com.tabletophelper.encounter.internal.StoredJson;
import com.tabletophelper.encounter.internal.TurnProcessor;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.TurnOrder;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EncounterService {

    private final EncounterRepository encounterRepository;
    private final EncounterParticipantRepository participantRepository;
    private final CampaignRepository campaignRepository;
    private final CharacterRepository characterRepository;
    private final MonsterRepository monsterRepository;
    private final ObjectMapper objectMapper;
    private final MonsterPools monsterPools;
    private final AccessPolicy accessPolicy;
    private final EncounterCommands encounterCommands;
    private final CombatLogReader combatLogReader;
    private final TurnProcessor turnProcessor;
    private final EncounterResults encounterResults;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    // An encounter under way, from which a player character leaving takes its changes (D166)
    private static final Set<EncounterStatus> LIVE = EnumSet.of(EncounterStatus.ACTIVE, EncounterStatus.PAUSED);

    @Transactional
    public EncounterResponse createEncounter(EncounterCreateRequest request, UUID userId) {
        Campaign campaign = campaignRepository.findById(request.getCampaignId())
                .orElseThrow(() -> new ValidationException("Campaign not found"));
        accessPolicy.requireDm(campaign.getId(), userId);

        Encounter encounter = Encounter.builder()
                .campaign(campaign)
                .name(request.getName())
                .description(request.getDescription())
                .build();

        encounter = encounterRepository.save(encounter);
        return toResponse(encounter);
    }

    @Transactional(readOnly = true)
    public EncounterResponse getEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireMember(encounter.getCampaign().getId(), userId);
        return toResponse(encounter);
    }

    @Transactional(readOnly = true)
    public List<EncounterResponse> getEncountersByCampaign(UUID campaignId, UUID userId) {
        accessPolicy.requireMember(campaignId, userId);
        return encounterRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EncounterResponse addParticipant(UUID encounterId, AddParticipantRequest request, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        verifyStatus(encounter, EncounterStatus.PREPARING);

        ParticipantType type = parseParticipantType(request.getParticipantType());
        int quantity = request.getQuantity() != null ? request.getQuantity() : 1;

        if (type == ParticipantType.PLAYER) {
            addPlayerParticipant(encounter, request);
        } else {
            addMonsterParticipants(encounter, request, quantity);
        }

        return toResponse(encounterRepository.save(encounter));
    }

    private void addPlayerParticipant(Encounter encounter, AddParticipantRequest request) {
        if (request.getCharacterId() == null) {
            throw new ValidationException("characterId is required for a player participant");
        }
        PlayerCharacter character = characterRepository.findById(request.getCharacterId())
                .orElseThrow(() -> new ValidationException("Character not found"));
        CombatSnapshot snapshot = CombatSnapshot.of(character);
        accessPolicy.requireOwnerIsMember(encounter.getCampaign().getId(), snapshot.ownerId());
        if (snapshot.dead()) {
            throw new ConflictException(snapshot.name() + " is dead, so can't join an encounter until the DM revives them");
        }

        EncounterParticipant participant = EncounterParticipant.builder()
                .encounter(encounter)
                .participantType(ParticipantType.PLAYER)
                .character(character)
                .displayName(snapshot.name())
                .build();
        joinWith(participant, snapshot);
        encounter.getParticipants().add(participant);
    }

    /**
     * Copies a player character into its participant through the character module's snapshot of its sheet (D166):
     * its hit points, temporary hit points, AC, initiative modifier, slots, spell numbers, spells and pools, with the
     * totals as what it joined with, from which Write-back works out what the encounter changes. The hit points and
     * life state must agree (D161): a character who arrives at 0 HP is stable and unconscious, since the sheet doesn't
     * say how they got there, so they make no death saves until hit.
     */
    private static void joinWith(EncounterParticipant participant, CombatSnapshot snapshot) {
        int hp = snapshot.totals().hpCurrent();
        participant.setInitiativeModifier(snapshot.initiativeBonus());
        participant.setHpMax(snapshot.hpMax());
        participant.setHpCurrent(hp);
        participant.setHpTemp(snapshot.totals().hpTemp());
        participant.setLifeState(hp > 0 ? LifeState.ALIVE : LifeState.STABLE);
        participant.setActiveConditions(hp > 0 ? null
                : new ArrayList<>(List.of(ConditionEntry.unconsciousAtZeroHp(1))));
        participant.setArmourClass(snapshot.armourClass());
        participant.setControlledByUserId(snapshot.ownerId());
        participant.setSpellSlotsCurrent(snapshot.spellSlots());
        participant.setSpellAttackBonus(snapshot.spellAttackBonus());
        participant.setSpellSaveDc(snapshot.spellSaveDc());
        participant.setSpellcastingAbility(snapshot.spellcastingAbility());
        participant.setSpellsKnown(snapshot.spellsKnown());
        participant.setResourcePoolsCurrent(snapshot.resourcePools());
        participant.setJoinedWith(snapshot.totals());
    }

    private void addMonsterParticipants(Encounter encounter, AddParticipantRequest request, int quantity) {
        if (request.getMonsterId() == null) {
            throw new ValidationException("monsterId is required for a monster participant");
        }
        Monster monster = monsterRepository.findById(request.getMonsterId())
                .orElseThrow(() -> new ValidationException("Monster not found"));

        int dexMod = Ability.modifier(monster.getDexterity());
        String baseName = request.getDisplayName();

        long existingCount = encounter.getParticipants().stream()
                .filter(p -> p.getDisplayName().startsWith(baseName))
                .count();

        for (int i = 0; i < quantity; i++) {
            String name = quantity == 1 && existingCount == 0
                    ? baseName
                    : baseName + " " + (existingCount + i + 1);

            EncounterParticipant participant = EncounterParticipant.builder()
                    .encounter(encounter)
                    .participantType(ParticipantType.MONSTER)
                    .monster(monster)
                    .displayName(name)
                    .initiativeModifier(dexMod)
                    .hpMax(monster.getHitPoints())
                    .hpCurrent(monster.getHitPoints())
                    .armourClass(monster.getArmourClass())
                    .build();

            // Its pools, innate uses included, and its spell slots and spells (D165)
            monsterPools.setUp(participant, monster);

            encounter.getParticipants().add(participant);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMonsterActionTemplates(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);

        EncounterParticipant participant = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found"));

        if (participant.getMonster() == null || participant.getMonster().getActionTemplates() == null) {
            return Map.of("error", "No action templates available for this monster");
        }
        JsonNode templatesNode = StoredJson.read(objectMapper, participant.getMonster().getActionTemplates(),
                participant.getMonster().getName() + "'s action templates");
        if (!templatesNode.isObject()) {
            return Map.of("error", "No action templates available for this monster");
        }
        Map<String, Object> templates = objectMapper.convertValue(templatesNode,
                new TypeReference<LinkedHashMap<String, Object>>() {});

        // Attach current resource pool states
        List<ResourcePoolEntry> pools = participant.getResourcePoolsCurrent() != null
                ? participant.getResourcePoolsCurrent() : List.of();
        Map<String, Object> poolStates = new LinkedHashMap<>();
        for (ResourcePoolEntry p : pools) {
            poolStates.put(p.poolId(), Map.of(
                    "currentUses", p.currentUses(),
                    "maxUses", p.maxUses(),
                    "displayName", p.displayName()
            ));
        }
        templates.put("resourcePools", poolStates);

        return templates;
    }

    @Transactional
    public EncounterResponse renameParticipant(UUID encounterId, UUID participantId, String newName, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);

        encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found"))
                .setDisplayName(newName.trim());

        return toResponse(encounterRepository.save(encounter));
    }

    // Hides a monster from the Player View or shows it again, in any status (D157)
    @Transactional
    public EncounterResponse setParticipantVisibility(UUID encounterId, UUID participantId, boolean visible,
                                                      UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);

        EncounterParticipant participant = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found"));
        if (participant.getParticipantType() != ParticipantType.MONSTER) {
            throw new ValidationException("Only monsters can be hidden from players");
        }
        participant.setIsVisibleToPlayers(visible);

        return toResponse(encounterRepository.save(encounter));
    }

    /**
     * Removes a participant while the encounter is prepared or fought. Mid-fight its concentration ends, it
     * leaves its lair, and if the turn is its own, the turn passes to the next creature in the order (D163). A player
     * character leaving an encounter under way takes what the encounter changed for it so far to its sheet then,
     * through Write-back (D166).
     */
    @Transactional
    public EncounterResponse removeParticipant(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        if (encounter.getStatus() != EncounterStatus.PREPARING && encounter.getStatus() != EncounterStatus.ACTIVE) {
            throw new ConflictException("Participants can be removed only while the encounter is prepared or active");
        }
        EncounterParticipant participant = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found"));

        if (LIVE.contains(encounter.getStatus())) {
            // Before the turn order reads the sheets, so the character's is read once its row is locked
            encounterResults.apply(encounter, List.of(participant));
            turnProcessor.leave(encounter, participant);
        }
        encounter.getParticipants().remove(participant);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse setInitiatives(UUID encounterId, BulkInitiativeRequest request, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);

        Map<UUID, Integer> initiativeMap = request.getInitiatives().stream()
                .collect(Collectors.toMap(SetInitiativeRequest::getParticipantId, SetInitiativeRequest::getInitiative));

        for (EncounterParticipant p : encounter.getParticipants()) {
            Integer init = initiativeMap.get(p.getId());
            if (init != null) {
                p.setInitiative(init);
            }
        }

        recomputeSortOrder(encounter);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse rollAllInitiatives(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);

        for (EncounterParticipant p : encounter.getParticipants()) {
            int mod = p.getInitiativeModifier() != null ? p.getInitiativeModifier() : 0;
            int roll = ThreadLocalRandom.current().nextInt(1, 21) + mod;
            p.setInitiative(roll);
        }

        recomputeSortOrder(encounter);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse startEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        EncounterLifecycle.require(encounter, Transition.START);

        boolean allHaveInitiative = encounter.getParticipants().stream()
                .allMatch(p -> p.getInitiative() != null);
        if (!allHaveInitiative) {
            throw new ConflictException("All participants must have initiative set before starting");
        }

        // Every player character was added while the encounter was prepared, and its sheet may have changed since, by
        // a rest or a DM Adjustment, so each joins again with its sheet as it stands now: what Write-back measures the
        // encounter's changes from (D166). The initiative the DM set stays.
        for (EncounterParticipant participant : encounter.getParticipants()) {
            if (participant.getParticipantType() == ParticipantType.PLAYER && participant.getCharacter() != null) {
                CombatSnapshot snapshot = CombatSnapshot.of(participant.getCharacter());
                if (snapshot.dead()) {
                    throw new ConflictException(snapshot.name() + " has died since joining, so the encounter can't "
                            + "start with them until the DM revives them or removes them from it");
                }
                joinWith(participant, snapshot);
            }
        }

        encounter.setStatus(EncounterStatus.ACTIVE);
        encounter.setSessionCode(generateSessionCode());
        encounter.setRoundNumber(1);
        turnProcessor.begin(encounter);

        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse pauseEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        EncounterLifecycle.apply(encounter, Transition.PAUSE);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse resumeEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        EncounterLifecycle.apply(encounter, Transition.RESUME);
        return toResponse(encounterRepository.save(encounter));
    }

    /**
     * Completes the encounter (D166). With its results applied, what the encounter changed for each player character
     * is written back to their sheets: the encounter's row is locked first, by the command, and then the characters'
     * in ID order. Without, no sheet changes, pool uses included. Completed is final, so the results apply once.
     *
     * @param applyResults the DM's choice, made once for the whole encounter
     */
    @Transactional
    public EncounterResponse endEncounter(UUID encounterId, boolean applyResults, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        EncounterLifecycle.apply(encounter, Transition.COMPLETE);
        encounter.setResultsApplied(applyResults);
        if (applyResults) {
            encounterResults.apply(encounter, encounter.getParticipants());
        }
        log.info("DM {} completed encounter {} {} its results applied", userId, encounterId,
                applyResults ? "with" : "without");
        return toResponse(encounterRepository.save(encounter));
    }

    /**
     * What completing the encounter with its results applied would write back to each player character's sheet as it
     * stands now, for the DM's End dialog (D166); changes nothing. Only an encounter that can be completed, Active or
     * Paused, has results to preview.
     */
    @Transactional(readOnly = true)
    public List<CombatResultPreview> previewResults(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);
        EncounterLifecycle.require(encounter, Transition.COMPLETE);
        return encounterResults.preview(encounter);
    }

    @Transactional
    public void deleteEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        verifyStatus(encounter, EncounterStatus.PREPARING);
        encounterRepository.delete(encounter);
    }

    @Transactional(readOnly = true)
    public EncounterResponse getEncounterBySessionCode(String sessionCode, UUID userId) {
        Encounter encounter = encounterRepository.findBySessionCode(sessionCode.toUpperCase())
                .orElseThrow(() -> new NotFoundException("Invalid session code"));
        accessPolicy.requireMember(encounter.getCampaign().getId(), userId);
        return toResponse(encounter);
    }

    private Encounter loadEncounter(UUID encounterId) {
        return encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
    }

    private void verifyStatus(Encounter encounter, EncounterStatus expected) {
        if (encounter.getStatus() != expected) {
            throw new ConflictException("Encounter must be in " + expected + " status");
        }
    }

    private ParticipantType parseParticipantType(String participantType) {
        try {
            return ParticipantType.valueOf(participantType);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid participant type: " + participantType);
        }
    }

    /**
     * The DM's order for a group of participants tied on initiative and DEX (D163): the IDs of the whole group,
     * in the order they should act. Their turns swap places among themselves and nothing else moves. Naming
     * participants who don't tie, or only some of a group, is a 400; a Paused or Completed encounter is a 409.
     */
    @Transactional
    public EncounterResponse reorderTie(UUID encounterId, TieOrderRequest request, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        if (encounter.getStatus() != EncounterStatus.PREPARING && encounter.getStatus() != EncounterStatus.ACTIVE) {
            throw new ConflictException("Ties can be reordered only while the encounter is prepared or active");
        }
        List<UUID> requested = request.participantIds();
        if (requested.size() < 2 || new HashSet<>(requested).size() != requested.size()) {
            throw new ValidationException("Name two or more tied participants, each once");
        }
        Map<UUID, TurnOrder.Combatant> combatants = encounter.getParticipants().stream()
                .collect(Collectors.toMap(EncounterParticipant::getId, EncounterTurns::combatant));
        List<TurnOrder.Combatant> group = requested.stream()
                .map(id -> Optional.ofNullable(combatants.get(id))
                        .orElseThrow(() -> new ValidationException("Participant not found")))
                .toList();
        TurnOrder.Combatant first = group.getFirst();
        if (!group.stream().allMatch(c -> TurnOrder.tied(first, c))) {
            throw new ValidationException("Only participants tied on both initiative and DEX can be reordered");
        }
        if (combatants.values().stream().filter(c -> TurnOrder.tied(first, c)).count() != group.size()) {
            throw new ValidationException("Name every participant in the tie");
        }

        // The order as it stands, with the group's turns in the order asked for, numbered from 0
        List<UUID> order = new ArrayList<>(TurnOrder.order(combatants.values(), false).stream()
                .map(turn -> turn.combatant().id())
                .toList());
        List<Integer> slots = requested.stream().map(order::indexOf).sorted().toList();
        for (int i = 0; i < slots.size(); i++) {
            order.set(slots.get(i), requested.get(i));
        }
        renumber(encounter, order);
        return toResponse(encounterRepository.save(encounter));
    }

    // The DM's order among ties follows the turn order once initiatives change (D163)
    private void recomputeSortOrder(Encounter encounter) {
        renumber(encounter, TurnOrder.order(encounter.getParticipants().stream()
                .map(EncounterTurns::combatant).toList(), false).stream()
                .map(turn -> turn.combatant().id())
                .toList());
    }

    private static void renumber(Encounter encounter, List<UUID> order) {
        for (EncounterParticipant participant : encounter.getParticipants()) {
            participant.setSortOrder(order.indexOf(participant.getId()));
        }
    }

    private String generateSessionCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        String code = sb.toString();
        if (encounterRepository.findBySessionCode(code).isPresent()) {
            return generateSessionCode();
        }
        return code;
    }

    /**
     * The DM View of an encounter, which EncounterViews narrows for players (D157). The turn order is
     * computed here, with the current turn's place in it and each tie the DM may reorder (D163).
     */
    public EncounterResponse toResponse(Encounter encounter) {
        List<TurnOrder.Turn> order = EncounterTurns.order(encounter);
        List<EncounterResponse.TurnResponse> turns = turnResponses(encounter, order);
        Map<UUID, Integer> places = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            if (!order.get(i).isLair()) {
                places.put(order.get(i).combatant().id(), i);
            }
        }
        int current = EncounterTurns.currentIndex(encounter, order);

        List<EncounterResponse.ParticipantResponse> participantResponses = encounter.getParticipants().stream()
                .sorted(Comparator.comparing(p -> places.get(p.getId())))
                .map(p -> EncounterResponse.ParticipantResponse.builder()
                        .id(p.getId())
                        .participantType(p.getParticipantType().name())
                        .characterId(p.getCharacter() != null ? p.getCharacter().getId() : null)
                        .characterClass(p.getCharacter() != null ? p.getCharacter().getCharacterClass() : null)
                        .monsterId(p.getMonster() != null ? p.getMonster().getId() : null)
                        .displayName(p.getDisplayName())
                        .initiative(p.getInitiative())
                        .initiativeModifier(p.getInitiativeModifier())
                        .sortOrder(places.get(p.getId()))
                        .hpMax(p.getHpMax())
                        .hpCurrent(p.getHpCurrent())
                        .hpTemp(p.getHpTemp())
                        .armourClass(p.getArmourClass())
                        .activeConditions(p.getActiveConditions())
                        .concentrationSpell(p.getConcentration() != null ? p.getConcentration().spell() : null)
                        .concentrationSlotLevel(p.getConcentration() != null ? p.getConcentration().slotLevel() : null)
                        .activeSpell(p.getActiveSpell())
                        .activeSpellSlotLevel(p.getActiveSpellSlotLevel())
                        .spellSlotsCurrent(p.getSpellSlotsCurrent())
                        .isVisibleToPlayers(p.getIsVisibleToPlayers())
                        .lifeState(p.getLifeState().name())
                        .isCurrentTurn(EncounterTurns.isCurrentTurn(encounter, p.getId()))
                        .deathSaveDue(Boolean.TRUE.equals(p.getDeathSaveDue()) && p.getLifeState() == LifeState.DYING)
                        .hasLairActions(p.getParticipantType() == ParticipantType.MONSTER ? hasLairActions(p) : null)
                        .controlledByUserId(p.getControlledByUserId())
                        .deathSaveSuccesses(p.getDeathSaveSuccesses())
                        .deathSaveFailures(p.getDeathSaveFailures())
                        .notes(p.getNotes())
                        .spellAttackBonus(p.getSpellAttackBonus())
                        .spellSaveDc(p.getSpellSaveDc())
                        .spellcastingAbility(p.getSpellcastingAbility())
                        .spellsKnown(p.getSpellsKnown())
                        .resourcePoolsCurrent(p.getResourcePoolsCurrent())
                        .actionUsed(p.getActionUsed())
                        .bonusActionUsed(p.getBonusActionUsed())
                        .reactionUsed(p.getReactionUsed())
                        .build())
                .toList();

        Campaign campaign = encounter.getCampaign();
        return EncounterResponse.builder()
                .id(encounter.getId())
                .campaignId(campaign.getId())
                .campaignName(campaign.getName())
                .name(encounter.getName())
                .description(encounter.getDescription())
                .status(encounter.getStatus().name())
                .currentTurnIndex(current >= 0 ? current : null)
                .roundNumber(encounter.getRoundNumber())
                .sessionCode(encounter.getSessionCode())
                .participants(participantResponses)
                .turnOrder(turns)
                .monstersInLair(encounter.getMonstersInLair() != null ? List.copyOf(encounter.getMonstersInLair()) : List.of())
                .createdAt(encounter.getCreatedAt())
                .dmUserId(campaign.getDm().getId())
                .showExactMonsterHp(Boolean.TRUE.equals(campaign.getShowExactMonsterHp()))
                .allowPlayerManualModifiers(!Boolean.FALSE.equals(campaign.getAllowPlayerManualModifiers()))
                .build();
    }

    // Each turn in order, with the number of the group it ties with, if any: its first turn's place
    private static List<EncounterResponse.TurnResponse> turnResponses(Encounter encounter, List<TurnOrder.Turn> order) {
        List<EncounterResponse.TurnResponse> turns = new ArrayList<>();
        Integer group = null;
        for (int i = 0; i < order.size(); i++) {
            TurnOrder.Turn turn = order.get(i);
            boolean tiedBefore = i > 0 && tied(order.get(i - 1), turn);
            boolean tiedAfter = i + 1 < order.size() && tied(turn, order.get(i + 1));
            group = tiedBefore ? group : tiedAfter ? Integer.valueOf(i) : null;
            turns.add(EncounterResponse.TurnResponse.builder()
                    .participantId(turn.isLair() ? null : turn.combatant().id())
                    .name(EncounterTurns.name(encounter, turn))
                    .initiative(turn.isLair() ? Integer.valueOf(TurnOrder.LAIR_INITIATIVE) : turn.combatant().initiative())
                    .lair(turn.isLair())
                    .skipped(turn.isSkipped())
                    .current(EncounterTurns.isCurrent(encounter, turn))
                    .tieGroup(group)
                    .build());
        }
        return turns;
    }

    private static boolean tied(TurnOrder.Turn a, TurnOrder.Turn b) {
        return !a.isLair() && !b.isLair() && TurnOrder.tied(a.combatant(), b.combatant());
    }

    // Whether the monster's action templates list any lair actions, so it can fight in its lair (D163)
    private boolean hasLairActions(EncounterParticipant participant) {
        Monster monster = participant.getMonster();
        return monster != null && MonsterTemplates.of(objectMapper, monster).hasLairActions();
    }

    /**
     * The encounter's combat log as the user may read it: all of it for the DM, and for players without the
     * entries about hidden participants or the Legendary Resistance fields (D157).
     */
    @Transactional(readOnly = true)
    public List<CombatLogResponse> getCombatLog(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireMember(encounter.getCampaign().getId(), userId);

        List<CombatLogResponse> entries = combatLogReader.dmLog(encounterId);
        if (userId.equals(encounter.getCampaign().getDm().getId())) {
            return entries;
        }
        Map<UUID, String> hiddenParticipants = encounter.getParticipants().stream()
                .filter(p -> Boolean.FALSE.equals(p.getIsVisibleToPlayers()))
                .collect(Collectors.toMap(EncounterParticipant::getId, EncounterParticipant::getDisplayName));
        return EncounterViews.playerLog(entries, hiddenParticipants);
    }
}
