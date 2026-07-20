package com.tabletophelper.encounter;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EncounterService {

    private final EncounterRepository encounterRepository;
    private final EncounterParticipantRepository participantRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignMemberRepository campaignMemberRepository;
    private final CharacterRepository characterRepository;
    private final MonsterRepository monsterRepository;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Transactional
    public EncounterResponse createEncounter(EncounterCreateRequest request, UUID userId) {
        Campaign campaign = campaignRepository.findById(request.getCampaignId())
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
        verifyDmOwnership(campaign, userId);

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
        verifyMembership(encounter.getCampaign().getId(), userId);
        return toResponse(encounter);
    }

    @Transactional(readOnly = true)
    public List<EncounterResponse> getEncountersByCampaign(UUID campaignId, UUID userId) {
        verifyMembership(campaignId, userId);
        return encounterRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EncounterResponse addParticipant(UUID encounterId, AddParticipantRequest request, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        verifyDmOwnership(encounter.getCampaign(), userId);
        verifyStatus(encounter, EncounterStatus.PREPARING);

        ParticipantType type = ParticipantType.valueOf(request.getParticipantType());
        int quantity = request.getQuantity() != null ? request.getQuantity() : 1;

        if (type == ParticipantType.PLAYER) {
            addPlayerParticipant(encounter, request);
        } else {
            addMonsterParticipants(encounter, request, quantity);
        }

        return toResponse(encounterRepository.save(encounter));
    }

    private void addPlayerParticipant(Encounter encounter, AddParticipantRequest request) {
        PlayerCharacter character = characterRepository.findById(request.getCharacterId())
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));

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
                .spellSlotsCurrent(character.getSpellSlots())
                .spellAttackBonus(character.getSpellAttackBonus())
                .spellSaveDc(character.getSpellSaveDc())
                .spellcastingAbility(character.getSpellcastingAbility())
                .spellsKnown(character.getSpellsKnown())
                .build();

        encounter.getParticipants().add(participant);
    }

    private void addMonsterParticipants(Encounter encounter, AddParticipantRequest request, int quantity) {
        Monster monster = monsterRepository.findById(request.getMonsterId())
                .orElseThrow(() -> new IllegalArgumentException("Monster not found"));

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

            encounter.getParticipants().add(participant);
        }
    }

    @Transactional
    public EncounterResponse renameParticipant(UUID encounterId, UUID participantId, String newName, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        verifyDmOwnership(encounter.getCampaign(), userId);

        encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Participant not found"))
                .setDisplayName(newName.trim());

        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse removeParticipant(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        verifyDmOwnership(encounter.getCampaign(), userId);
        verifyStatus(encounter, EncounterStatus.PREPARING);

        encounter.getParticipants().removeIf(p -> p.getId().equals(participantId));
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse setInitiatives(UUID encounterId, BulkInitiativeRequest request, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        verifyDmOwnership(encounter.getCampaign(), userId);

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
        verifyDmOwnership(encounter.getCampaign(), userId);

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
        verifyDmOwnership(encounter.getCampaign(), userId);
        verifyStatus(encounter, EncounterStatus.PREPARING);

        boolean allHaveInitiative = encounter.getParticipants().stream()
                .allMatch(p -> p.getInitiative() != null);
        if (!allHaveInitiative) {
            throw new IllegalArgumentException("All participants must have initiative set before starting");
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
        verifyDmOwnership(encounter.getCampaign(), userId);
        encounter.setStatus(EncounterStatus.PAUSED);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse resumeEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        verifyDmOwnership(encounter.getCampaign(), userId);
        encounter.setStatus(EncounterStatus.ACTIVE);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse endEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        verifyDmOwnership(encounter.getCampaign(), userId);
        encounter.setStatus(EncounterStatus.COMPLETED);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public void deleteEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        verifyDmOwnership(encounter.getCampaign(), userId);
        verifyStatus(encounter, EncounterStatus.PREPARING);
        encounterRepository.delete(encounter);
    }

    @Transactional(readOnly = true)
    public EncounterResponse getEncounterBySessionCode(String sessionCode) {
        Encounter encounter = encounterRepository.findBySessionCode(sessionCode.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Invalid session code"));
        return toResponse(encounter);
    }

    private Encounter loadEncounter(UUID encounterId) {
        return encounterRepository.findById(encounterId)
                .orElseThrow(() -> new IllegalArgumentException("Encounter not found"));
    }

    private void verifyDmOwnership(Campaign campaign, UUID userId) {
        if (!campaign.getDm().getId().equals(userId)) {
            throw new IllegalArgumentException("Only the DM can perform this action");
        }
    }

    private void verifyMembership(UUID campaignId, UUID userId) {
        if (!campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, userId)) {
            throw new IllegalArgumentException("You are not a member of this campaign");
        }
    }

    private void verifyStatus(Encounter encounter, EncounterStatus expected) {
        if (encounter.getStatus() != expected) {
            throw new IllegalArgumentException("Encounter must be in " + expected + " status");
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
}
