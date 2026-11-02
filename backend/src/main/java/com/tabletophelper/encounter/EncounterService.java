package com.tabletophelper.encounter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
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
    private final ResourcePoolService resourcePoolService;
    private final MonsterSpellCastService monsterSpellCastService;
    private final AccessPolicy accessPolicy;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

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
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);
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
        accessPolicy.requireOwnerIsMember(encounter.getCampaign().getId(), character.getUser().getId());

        EncounterParticipant participant = EncounterParticipant.builder()
                .encounter(encounter)
                .participantType(ParticipantType.PLAYER)
                .character(character)
                .displayName(character.getName())
                .initiativeModifier(character.getInitiativeBonus())
                .hpMax(character.getHpMax())
                .hpCurrent(character.getHpCurrent())
                .armourClass(character.getArmourClass())
                .controlledByUserId(character.getUser().getId())
                .spellSlotsCurrent(convertSpellSlotsFormat(character.getSpellSlots()))
                .spellAttackBonus(character.getSpellAttackBonus())
                .spellSaveDc(character.getSpellSaveDc())
                .spellcastingAbility(character.getSpellcastingAbility())
                .spellsKnown(character.getSpellsKnown())
                .build();

        resourcePoolService.copyToParticipant(participant, character);
        encounter.getParticipants().add(participant);
    }

    private void addMonsterParticipants(Encounter encounter, AddParticipantRequest request, int quantity) {
        if (request.getMonsterId() == null) {
            throw new ValidationException("monsterId is required for a monster participant");
        }
        Monster monster = monsterRepository.findById(request.getMonsterId())
                .orElseThrow(() -> new ValidationException("Monster not found"));

        int dexMod = (monster.getDexterity() - 10) / 2;
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

            resourcePoolService.createForMonster(participant, monster);

            // M12: Populate monster spellcasting data from action templates
            if (monster.getActionTemplates() != null && !monster.getActionTemplates().isBlank()) {
                try {
                    monsterSpellCastService.populateMonsterSpellcasting(participant,
                            monster.getActionTemplates());
                    monsterSpellCastService.populateInnateSpellPools(participant,
                            monster.getActionTemplates());
                } catch (Exception e) {
                    log.warn("Failed to populate monster spellcasting for {}: {}",
                            participant.getDisplayName(), e.getMessage());
                }
            }

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
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> templates = objectMapper.readValue(
                    participant.getMonster().getActionTemplates(), Map.class);

            // Attach current resource pool states
            List<ResourcePoolEntry> pools = resourcePoolService.parsePools(
                    participant.getResourcePoolsCurrent());
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
        } catch (Exception e) {
            return Map.of("error", "Failed to parse action templates: " + e.getMessage());
        }
    }

    @Transactional
    public EncounterResponse renameParticipant(UUID encounterId, UUID participantId, String newName, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);

        encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found"))
                .setDisplayName(newName.trim());

        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse removeParticipant(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);
        verifyStatus(encounter, EncounterStatus.PREPARING);

        encounter.getParticipants().removeIf(p -> p.getId().equals(participantId));
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse setInitiatives(UUID encounterId, BulkInitiativeRequest request, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);

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
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);

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
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);
        verifyStatus(encounter, EncounterStatus.PREPARING);

        boolean allHaveInitiative = encounter.getParticipants().stream()
                .allMatch(p -> p.getInitiative() != null);
        if (!allHaveInitiative) {
            throw new ConflictException("All participants must have initiative set before starting");
        }

        encounter.setStatus(EncounterStatus.ACTIVE);
        encounter.setSessionCode(generateSessionCode());
        encounter.setCurrentTurnIndex(0);
        encounter.setRoundNumber(1);

        encounter.getParticipants().forEach(p -> p.setIsCurrentTurn(false));
        encounter.getParticipants().stream()
                .filter(p -> p.getSortOrder() != null && p.getSortOrder() == 0)
                .findFirst()
                .ifPresent(p -> p.setIsCurrentTurn(true));

        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse pauseEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);
        encounter.setStatus(EncounterStatus.PAUSED);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse resumeEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);
        encounter.setStatus(EncounterStatus.ACTIVE);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse endEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);
        encounter.setStatus(EncounterStatus.COMPLETED);

        // Sync resource pool currentUses back to character sheets
        for (EncounterParticipant p : encounter.getParticipants()) {
            if (p.getParticipantType() == ParticipantType.PLAYER && p.getCharacter() != null) {
                resourcePoolService.syncBackToCharacter(p, p.getCharacter());
            }
        }

        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public void deleteEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        accessPolicy.requireDm(encounter.getCampaign().getId(), userId);
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

    private void recomputeSortOrder(Encounter encounter) {
        List<EncounterParticipant> sorted = encounter.getParticipants().stream()
                .sorted(Comparator.comparing(
                        (EncounterParticipant p) -> p.getInitiative() != null ? p.getInitiative() : 0
                ).reversed())
                .toList();

        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setSortOrder(i);
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

    EncounterResponse toResponse(Encounter encounter) {
        List<EncounterResponse.ParticipantResponse> participantResponses = encounter.getParticipants().stream()
                .sorted(Comparator.comparing(p -> p.getSortOrder() != null ? p.getSortOrder() : Integer.MAX_VALUE))
                .map(p -> EncounterResponse.ParticipantResponse.builder()
                        .id(p.getId())
                        .participantType(p.getParticipantType().name())
                        .characterId(p.getCharacter() != null ? p.getCharacter().getId() : null)
                        .monsterId(p.getMonster() != null ? p.getMonster().getId() : null)
                        .displayName(p.getDisplayName())
                        .initiative(p.getInitiative())
                        .initiativeModifier(p.getInitiativeModifier())
                        .sortOrder(p.getSortOrder())
                        .hpMax(p.getHpMax())
                        .hpCurrent(p.getHpCurrent())
                        .hpTemp(p.getHpTemp())
                        .armourClass(p.getArmourClass())
                        .activeConditions(p.getActiveConditions())
                        .concentrationSpell(p.getConcentrationSpell())
                        .concentrationSlotLevel(p.getConcentrationSlotLevel())
                        .activeSpell(p.getActiveSpell())
                        .activeSpellSlotLevel(p.getActiveSpellSlotLevel())
                        .spellSlotsCurrent(p.getSpellSlotsCurrent())
                        .isVisibleToPlayers(p.getIsVisibleToPlayers())
                        .isAlive(p.getIsAlive())
                        .isCurrentTurn(p.getIsCurrentTurn())
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

        return EncounterResponse.builder()
                .id(encounter.getId())
                .campaignId(encounter.getCampaign().getId())
                .campaignName(encounter.getCampaign().getName())
                .name(encounter.getName())
                .description(encounter.getDescription())
                .status(encounter.getStatus().name())
                .currentTurnIndex(encounter.getCurrentTurnIndex())
                .roundNumber(encounter.getRoundNumber())
                .sessionCode(encounter.getSessionCode())
                .participants(participantResponses)
                .createdAt(encounter.getCreatedAt())
                .build();
    }

    private String convertSpellSlotsFormat(String characterSpellSlots) {
        if (characterSpellSlots == null) return null;
        try {
            Map<String, Map<String, Integer>> charSlots = objectMapper.readValue(characterSpellSlots,
                    new TypeReference<LinkedHashMap<String, Map<String, Integer>>>() {});
            Map<String, Map<String, Integer>> encounterSlots = new LinkedHashMap<>();
            for (var entry : charSlots.entrySet()) {
                Map<String, Integer> slot = entry.getValue();
                int total = slot.getOrDefault("total", slot.getOrDefault("max", 0));
                int used = slot.getOrDefault("used", 0);
                int remaining = slot.containsKey("remaining") ? slot.get("remaining") : total - used;
                int max = slot.containsKey("max") ? slot.get("max") : total;
                encounterSlots.put(entry.getKey(), Map.of("remaining", remaining, "max", max));
            }
            return objectMapper.writeValueAsString(encounterSlots);
        } catch (JsonProcessingException e) {
            log.warn("Failed to convert spell slots format: {}", e.getMessage());
            return characterSpellSlots;
        }
    }
}
