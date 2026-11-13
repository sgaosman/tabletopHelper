package com.tabletophelper.encounter;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CombatSnapshot;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.CombatResultPreview;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterLifecycle.Transition;
import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.encounter.internal.AbilityChecks;
import com.tabletophelper.encounter.internal.ActiveModifiers;
import com.tabletophelper.encounter.internal.CreatureStats;
import com.tabletophelper.encounter.internal.ChannelDivinityEffects;
import com.tabletophelper.encounter.internal.CommandRecorder;
import com.tabletophelper.encounter.internal.EffectApplier;
import com.tabletophelper.encounter.internal.EncounterTurns;
import com.tabletophelper.encounter.internal.Fears;
import com.tabletophelper.encounter.internal.FeatureEffects;
import com.tabletophelper.encounter.internal.MetamagicCasting;
import com.tabletophelper.encounter.internal.MonsterPools;
import com.tabletophelper.encounter.internal.SpellEffects;
import com.tabletophelper.encounter.internal.Summoner;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.encounter.internal.TurnEconomies;
import com.tabletophelper.encounter.internal.TurnProcessor;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.RepeatSave;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.rules.combat.AttackFeatures;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.ChannelDivinity;
import com.tabletophelper.rules.combat.KenseiFeatures;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SpellTrigger;
import com.tabletophelper.rules.combat.RollModifiers;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.rules.combat.TurnOrder;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.reference.dto.RepeatCost;
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
    private final CommandRecorder commandRecorder;
    private final SpellEffects spellEffects;
    private final Summoner summoner;
    private final EffectApplier effectApplier;
    private final CreatureStats creatureStats;

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
        // Heads are a hydra's, chosen as it joins from the bestiary (D206)
        if (request.getHeads() != null && (type == ParticipantType.PLAYER
                || type == ParticipantType.COMPANION && request.getSummonerParticipantId() != null)) {
            throw new ValidationException("heads is only for a monster whose multiattack makes an attack for each of "
                    + "its heads, as a hydra's");
        }

        if (type == ParticipantType.PLAYER) {
            addPlayerParticipant(encounter, request);
        } else if (type == ParticipantType.COMPANION && request.getSummonerParticipantId() != null) {
            // A summoning cast before the fight, as the companion command places it (D182)
            summoner.companion(encounter, new CompanionSummonRequest(request.getSummonerParticipantId(),
                    request.getSpellName() != null ? request.getSpellName() : "", request.getSlotLevel(),
                    request.getOptionId(), request.getMonsterId() != null
                            ? List.of(new SummonCreature(request.getMonsterId(), quantity)) : List.of(),
                    request.getVariant(), request.getInitiativeMode(), request.getCreatureType(),
                    request.getConcentrationLinked()), request.getDisplayName());
        } else {
            addMonsterParticipants(encounter, request, quantity, type);
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
        participant.setExhaustionLevel(snapshot.totals().exhaustion());
    }

    /**
     * Joins the participant again at the start, keeping any change the DM made to its exhaustion while the encounter
     * was prepared (D183): the level is the sheet's plus that change, and what it joined with is the sheet's, so
     * Write-back takes the change to the sheet. At level 4 or more its hit points are kept within the halved maximum,
     * and at level 6 it starts dead (PHB p.291).
     */
    private static void joinAgainAtStart(EncounterParticipant participant, CombatSnapshot snapshot) {
        int before = Exhaustion.clamp(participant.getExhaustionLevel());
        int joined = participant.getJoinedWith() != null ? participant.getJoinedWith().exhaustion() : before;
        joinWith(participant, snapshot);
        int level = Exhaustion.clamp(snapshot.totals().exhaustion() + before - joined);
        participant.setExhaustionLevel(level);
        if (Exhaustion.dies(level)) {
            participant.setHpCurrent(0);
            participant.setHpTemp(0);
            participant.setLifeState(LifeState.DEAD);
            participant.setActiveConditions(null);
        } else if (participant.getHpCurrent() > CreatureStats.hpMax(participant)) {
            participant.setHpCurrent(CreatureStats.hpMax(participant));
        }
    }

    /**
     * Monsters from the bestiary, or companions: creatures on the party's side with a monster's statistics, controlled
     * by the player the request names or else by the DM (D182). A companion nobody summoned is set up as a monster is.
     */
    private void addMonsterParticipants(Encounter encounter, AddParticipantRequest request, int quantity,
                                        ParticipantType type) {
        if (request.getMonsterId() == null) {
            throw new ValidationException("monsterId is required for a monster participant");
        }
        if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
            throw new ValidationException("displayName is required for a monster participant");
        }
        if (type == ParticipantType.COMPANION && request.getControlledByUserId() != null) {
            accessPolicy.requireOwnerIsMember(encounter.getCampaign().getId(), request.getControlledByUserId());
        }
        Monster monster = monsterRepository.findById(request.getMonsterId())
                .orElseThrow(() -> new ValidationException("Monster not found"));

        // A stat block without a Dexterity score counts as 10, as everywhere else that reads one
        int dexMod = Ability.modifier(monster.getDexterity() != null ? monster.getDexterity() : 10);
        String baseName = request.getDisplayName().trim();

        long existingCount = encounter.getParticipants().stream()
                .filter(p -> p.getDisplayName().startsWith(baseName))
                .count();

        for (int i = 0; i < quantity; i++) {
            String name = quantity == 1 && existingCount == 0
                    ? baseName
                    : baseName + " " + (existingCount + i + 1);

            EncounterParticipant participant = EncounterParticipant.builder()
                    .encounter(encounter)
                    .participantType(type)
                    .monster(monster)
                    .displayName(name)
                    .initiativeModifier(dexMod)
                    .hpMax(monster.getHitPoints())
                    .hpCurrent(monster.getHitPoints())
                    .armourClass(monster.getArmourClass())
                    .controlledByUserId(type == ParticipantType.COMPANION ? request.getControlledByUserId() : null)
                    .build();

            // Its pools, innate uses included, and its spell slots and spells (D165)
            monsterPools.setUp(participant, monster);
            // A hydra's heads as the DM chose them, the stat block's count otherwise (MM p.190, D206)
            if (request.getHeads() != null) {
                monsterPools.chooseHeads(participant, monster, request.getHeads());
            }

            encounter.getParticipants().add(participant);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMonsterActionTemplates(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = loadEncounter(encounterId);
        UUID campaignId = encounter.getCampaign().getId();
        // A druid in Wild Shape takes its beast's actions, which its player reads too (PHB p.67); a monster's are the
        // DM's alone (D157)
        Optional<EncounterParticipant> druid = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId) && p.getMonster() == null
                        && WildShapeForm.of(p).isPresent())
                .findFirst();
        // A summoned creature's are its controller's to read too, as they act for it (D182)
        Optional<EncounterParticipant> summoned = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId) && p.getSummon() != null
                        && p.getControlledByUserId() != null)
                .findFirst();
        if (druid.isPresent()) {
            accessPolicy.requireControls(campaignId, druid.get().getControlledByUserId(), userId);
        } else if (summoned.isPresent()) {
            accessPolicy.requireControls(campaignId, summoned.get().getControlledByUserId(), userId);
        } else {
            accessPolicy.requireDm(campaignId, userId);
        }

        EncounterParticipant participant = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found"));

        // A creature in another form, a druid's Wild Shape or a polymorphed monster, takes the form's actions (D192)
        Monster monster = WildShapeForm.of(participant).isPresent()
                ? monsterRepository.findById(WildShapeForm.of(participant).orElseThrow().monsterId()).orElse(null)
                : participant.getMonster();
        // A Tasha's spirit's own templates, made for it at the cast, come before its monster row's (D182)
        MonsterTemplates typed = participant.getActionTemplates() != null ? participant.getActionTemplates()
                : monster != null ? monster.getActionTemplates() : null;
        if (typed == null) {
            return Map.of("error", "No action templates available for this monster");
        }
        JsonNode templatesNode = typed.json();
        if (templatesNode == null || !templatesNode.isObject()) {
            return Map.of("error", "No action templates available for this monster");
        }
        Map<String, Object> templates = objectMapper.convertValue(templatesNode,
                new TypeReference<LinkedHashMap<String, Object>>() {});
        markLimitedUses(templates, typed);

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

    // Each action, bonus action, reaction and legendary action with limited uses, such as "(1/Day)", gains
    // limitedUses: the pool it spends and that pool's full count, for a monster that hasn't used it yet
    private static void markLimitedUses(Map<String, Object> templates, MonsterTemplates typed) {
        Map<String, String> sources = Map.of("actions", "ACTION", "bonusActions", "BONUS", "reactions", "REACTION",
                "legendaryActions", "LEGENDARY");
        sources.forEach((key, source) -> {
            if (!(templates.get(key) instanceof List<?> list)) {
                return;
            }
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> raw && raw.get("name") instanceof String name) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> action = (Map<String, Object>) raw;
                    typed.find(source, name).flatMap(MonsterPools::limitOf).ifPresent(limit ->
                            action.put("limitedUses", Map.of("poolId", limit.poolId(), "maxUses", limit.maxUses())));
                }
            }
        });
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
        if (participant.getParticipantType() == ParticipantType.PLAYER) {
            throw new ValidationException("Only monsters and companions can be hidden from players");
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
                .filter(p -> p.getId().equals(participantId) && !EncounterTurns.dismissed(p))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found"));

        // A summoned creature is dismissed instead: nothing is written back, and undo can still bring it back (D182)
        if (participant.getSummon() != null) {
            if (LIVE.contains(encounter.getStatus())) {
                commandRecorder.record(encounter, userId);
            }
            effectApplier.dismiss(encounter, participant, "dismissed by the DM");
            return toResponse(encounterRepository.save(encounter));
        }
        // Its summoned creatures go with it (D182)
        effectApplier.dismissSummonsOf(encounter, participant, participant.getDisplayName() + " leaves the encounter");

        if (LIVE.contains(encounter.getStatus())) {
            // Before the turn order reads the sheets, so the character's is read once its row is locked
            encounterResults.apply(encounter, List.of(participant));
            turnProcessor.leave(encounter, participant);
            // Its sheet has taken the encounter's changes, so nothing before this can be undone (M14)
            commandRecorder.barrier(encounter, userId, "PARTICIPANT_REMOVED",
                    participant.getDisplayName() + " leaves the encounter");
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

        followCasters(encounter);
        recomputeSortOrder(encounter);
        return toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse rollAllInitiatives(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);

        // A summoned group rolls once, with its first creature's modifier (PHB p.189, p.225; D182). Initiative is a
        // Dexterity check (PHB p.189), so exhaustion gives it disadvantage (PHB p.291, D183), and the Ongoing
        // Modifiers on ability checks and initiative add their dice, as Guidance's d4 (PHB p.248) and Gift of Alacrity's
        // d8 (XGE p.157) do; a one-shot one, as Guidance's, is spent (D184); and their advantage or disadvantage on
        // Dexterity checks or initiative counts, as Enhance Ability's Cat's Grace (PHB p.237) and Jim's Glowing Coin's
        // distraction (Acquisitions Incorporated p.76) (D199)
        Map<UUID, Integer> groupRolls = new HashMap<>();
        Set<UUID> spent = new HashSet<>();
        for (EncounterParticipant p : EncounterTurns.present(encounter)) {
            int mod = p.getInitiativeModifier() != null ? p.getInitiativeModifier() : 0;
            List<AttackRules.Source> sources = AbilityChecks.sources(p, Ability.DEX, OngoingModifier.INITIATIVE_CHECK);
            List<RollModifiers.Bonus> bonuses = OngoingModifiers.checkBonuses(null, ActiveModifiers.labelledOn(p));
            UUID group = p.getSummon() != null ? p.getSummon().groupId() : null;
            int roll;
            if (group != null && groupRolls.containsKey(group)) {
                roll = groupRolls.get(group);
            } else {
                roll = AbilityChecks.roll(EncounterService::initiativeD20, sources).d20() + mod
                        + initiativeBonus(bonuses);
                spent.addAll(EffectApplier.oneShotsOf(bonuses));
                if (group != null) {
                    groupRolls.put(group, roll);
                }
            }
            p.setInitiative(roll);
        }
        effectApplier.spendModifiers(encounter, spent);

        followCasters(encounter);
        recomputeSortOrder(encounter);
        return toResponse(encounterRepository.save(encounter));
    }

    // What the Ongoing Modifiers add to an initiative roll, each die rolled (D184)
    private static int initiativeBonus(List<RollModifiers.Bonus> bonuses) {
        int total = 0;
        for (RollModifiers.Bonus bonus : bonuses) {
            int rolled = bonus.dice().modifier();
            for (int i = 0; i < bonus.dice().count(); i++) {
                rolled += ThreadLocalRandom.current().nextInt(1, bonus.dice().sides() + 1);
            }
            total += bonus.sign() * rolled;
        }
        return total;
    }

    // One d20 for initiative
    private static int initiativeD20() {
        return ThreadLocalRandom.current().nextInt(1, 21);
    }

    @Transactional
    public EncounterResponse startEncounter(UUID encounterId, UUID userId) {
        Encounter encounter = encounterCommands.beginAsDm(encounterId, userId);
        EncounterLifecycle.require(encounter, Transition.START);

        followCasters(encounter);
        boolean allHaveInitiative = EncounterTurns.present(encounter).stream()
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
                joinAgainAtStart(participant, snapshot);
                // Aid recorded as already active raises the hit points it joins with again, as it did its maximum
                // (PHB p.211, D184)
                int aid = ActiveModifiers.modifiersOn(participant).stream()
                        .filter(modifier -> modifier.kind() == OngoingModifier.Kind.HP_MAX)
                        .mapToInt(OngoingModifier::flat).sum();
                if (aid > 0 && participant.getLifeState() == LifeState.ALIVE) {
                    participant.setHpCurrent(Math.min(CreatureStats.hpMax(participant),
                            participant.getHpCurrent() + aid));
                }
            }
        }

        encounter.setStatus(EncounterStatus.ACTIVE);
        encounter.setSessionCode(generateSessionCode());
        encounter.setRoundNumber(1);
        turnProcessor.begin(encounter);
        // Undo never goes back past the start (M14)
        commandRecorder.barrier(encounter, userId, "ENCOUNTER_START", "The encounter starts");

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
        // Completed is final, so nothing can be undone: the commands keep only whether they were undone (M14)
        commandRecorder.completed(encounter);
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
        Map<UUID, TurnOrder.Combatant> combatants = EncounterTurns.present(encounter).stream()
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
        renumber(encounter, TurnOrder.order(EncounterTurns.present(encounter).stream()
                .map(EncounterTurns::combatant).toList(), false).stream()
                .map(turn -> turn.combatant().id())
                .toList());
    }

    // A summoned creature that follows its caster shares the caster's initiative count (TCE p.109, D182)
    private static void followCasters(Encounter encounter) {
        Map<UUID, EncounterParticipant> byId = new HashMap<>();
        encounter.getParticipants().forEach(p -> byId.put(p.getId(), p));
        for (EncounterParticipant p : EncounterTurns.present(encounter)) {
            SummonLink link = p.getSummon();
            EncounterParticipant caster = p.getSummonedByParticipantId() != null
                    ? byId.get(p.getSummonedByParticipantId()) : null;
            if (link != null && link.followsCaster() && caster != null && caster.getInitiative() != null) {
                p.setInitiative(caster.getInitiative());
            }
        }
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
        // A summoned creature that has disappeared is in neither view, its turn included while it keeps it (D182)
        Set<UUID> dismissed = encounter.getParticipants().stream().filter(EncounterTurns::dismissed)
                .map(EncounterParticipant::getId).collect(Collectors.toSet());
        List<TurnOrder.Turn> order = EncounterTurns.order(encounter).stream()
                .filter(turn -> turn.isLair() || !dismissed.contains(turn.combatant().id()))
                .toList();
        List<EncounterResponse.TurnResponse> turns = turnResponses(encounter, order);
        Map<UUID, Integer> places = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            if (!order.get(i).isLair()) {
                places.put(order.get(i).combatant().id(), i);
            }
        }
        int current = EncounterTurns.currentIndex(encounter, order);

        List<EncounterResponse.ParticipantResponse> participantResponses = EncounterTurns.present(encounter).stream()
                .sorted(Comparator.comparing(p -> places.getOrDefault(p.getId(), Integer.MAX_VALUE)))
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
                        .hpMax(CreatureStats.hpMax(p))
                        .hpMaxBase(p.getHpMax())
                        .hpCurrent(p.getHpCurrent())
                        .hpTemp(p.getHpTemp())
                        .armourClass(CreatureStats.armourClass(p))
                        .activeConditions(withActionChecks(p.getActiveConditions()))
                        .fears(fearResponses(p))
                        .exhaustionLevel(Exhaustion.clamp(p.getExhaustionLevel()))
                        .concentrationSpell(p.getConcentration() != null ? p.getConcentration().spell() : null)
                        .concentrationSlotLevel(p.getConcentration() != null ? p.getConcentration().slotLevel() : null)
                        .activeEffects(effectResponses(encounter, p))
                        .spellModifiers(modifierChips(encounter, p))
                        .spellSlotsCurrent(p.getSpellSlotsCurrent())
                        .isVisibleToPlayers(p.getIsVisibleToPlayers())
                        .lifeState(p.getLifeState().name())
                        .isCurrentTurn(EncounterTurns.isCurrentTurn(encounter, p.getId()))
                        .deathSaveDue(Boolean.TRUE.equals(p.getDeathSaveDue()) && p.getLifeState() == LifeState.DYING)
                        .hasLairActions(p.getParticipantType() == ParticipantType.MONSTER ? hasLairActions(p) : null)
                        .controlledByUserId(p.getControlledByUserId())
                        .spellConsentTrait(creatureStats.spellConsentTrait(p))
                        .spellConsent(creatureStats.spellConsentTrait(p) != null ? p.getSpellConsent().name() : null)
                        .deathSaveSuccesses(p.getDeathSaveSuccesses())
                        .deathSaveFailures(p.getDeathSaveFailures())
                        .notes(p.getNotes())
                        .spellAttackBonus(p.getSpellAttackBonus())
                        .spellSaveDc(p.getSpellSaveDc())
                        .spellcastingAbility(p.getSpellcastingAbility())
                        .spellsKnown(p.getSpellsKnown())
                        .resourcePoolsCurrent(p.getResourcePoolsCurrent())
                        .wildShape(WildShapeForm.of(p).filter(WildShapeForm::wildShape).orElse(null))
                        .form(WildShapeForm.of(p).orElse(null))
                        .formName(WildShapeForm.of(p).map(WildShapeForm::beastName).orElse(null))
                        .turnEconomy(p.getTurnEconomy())
                        .attacksPerAction(TurnEconomies.attacksPerAction(p))
                        .speed(TurnEconomies.speed(p, objectMapper))
                        .fightingStyles(fightingStyles(p))
                        .channelDivinityOptions(p.getCharacter() != null ? ChannelDivinityEffects.available(p).stream()
                                .map(ChannelDivinity.Option::id).toList() : null)
                        .metamagicOptions(p.getCharacter() != null ? MetamagicCasting.known(p.getCharacter()) : null)
                        .pactOfTheChain(FeatureEffects.hasFeature(p.getCharacter(), SummonRules.PACT_OF_THE_CHAIN)
                                ? Boolean.TRUE : null)
                        .kenseiLevel(kenseiLevel(p))
                        .summonedByParticipantId(p.getSummonedByParticipantId())
                        .summon(p.getSummon())
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
                .turnStamp(EncounterTurns.stamp(encounter))
                .sessionCode(encounter.getSessionCode())
                .participants(participantResponses)
                .turnOrder(turns)
                .monstersInLair(encounter.getMonstersInLair() != null ? List.copyOf(encounter.getMonstersInLair()) : List.of())
                .createdAt(encounter.getCreatedAt())
                .dmUserId(campaign.getDm().getId())
                .showExactMonsterHp(Boolean.TRUE.equals(campaign.getShowExactMonsterHp()))
                .allowPlayerManualModifiers(!Boolean.FALSE.equals(campaign.getAllowPlayerManualModifiers()))
                .undo(commandRecorder.undoInfo(encounter).orElse(null))
                .build();
    }

    // The participant's Spell Effects, each with how it is used from its spell's data; an effect whose template can't
    // be read is shown with nothing to do with it, so the state still loads (M15)
    private List<EncounterResponse.SpellEffectResponse> effectResponses(Encounter encounter,
                                                                        EncounterParticipant participant) {
        return EffectApplier.effects(participant).stream().map(effect -> {
            SpellEffects.EffectUse how;
            try {
                how = spellEffects.effectUse(effect.spell());
            } catch (ConflictException e) {
                how = SpellEffects.EffectUse.NONE;
            }
            return EncounterResponse.SpellEffectResponse.builder()
                    .id(effect.id())
                    .spell(effect.spell())
                    .slotLevel(effect.slotLevel())
                    .concentration(effect.concentrationInstanceId() != null)
                    .startedRound(effect.startedRound())
                    .durationRounds(effect.durationRounds())
                    .usesLeft(effect.usesLeft())
                    .directedCost(RepeatCost.of(how.directedCost()))
                    .directedResolves(how.directedResolves())
                    .maxUsesEachTurn(how.maxUsesEachTurn())
                    .triggers(how.triggers())
                    .repeatableTriggers(repeatableTriggers(effect.spell(), how.triggers()))
                    .summon(effect.summon())
                    .affectedIds(effect.affected())
                    .option(effect.option())
                    .modifiers(effect.modifiers().stream().map(OngoingModifier::describe).toList())
                    .retargetable(retargetable(effect.spell()))
                    .delayed(effect.delayed())
                    .damageLeft(effect.damageLeft())
                    .retaliation(retaliationText(effect.retaliation()))
                    .tallies(effect.countedSave() != null ? effect.countedSave().tallies() : List.of())
                    .tallyRule(effect.countedSave() != null ? effect.countedSave().ability() + " "
                            + effect.countedSave().successes() + "/" + effect.countedSave().failures() : null)
                    .controlledIds(effect.controlled().stream().map(SpellEffectEntry.Controlled::creature).toList())
                    .detonation(effect.detonation() == null ? null : EncounterResponse.DetonationResponse.builder()
                            .dice(effect.detonation().dice()).type(effect.detonation().type())
                            .ability(effect.detonation().ability()).dc(effect.detonation().dc())
                            .halfOnSave(effect.detonation().halfOnSave()).grows(effect.detonation().grows()).build())
                    .risingIds(effect.rising())
                    .useOptions(useOptions(effect.spell()))
                    .usableByIds(usedByTarget(effect.spell()) ? List.copyOf(effect.affected()) : List.of())
                    .summonedParticipantIds(effect.summon() == null ? List.of()
                            : EffectApplier.summonedBy(encounter, effect.id()).stream()
                                    .sorted(Comparator.comparing(p -> p.getSortOrder() != null ? p.getSortOrder()
                                            : Integer.MAX_VALUE))
                                    .map(EncounterParticipant::getId).toList())
                    .build();
        }).toList();
    }

    /*
     * The participant's conditions, each one its creature ends with its action by an ability check rather than a save
     * saying so on its Repeat Save, so its button can name the check (D200): Wrathful Smite's Wisdom check (PHB p.289),
     * Ensnaring Strike's Strength check (PHB p.237) and Phantasmal Force's Intelligence (Investigation) check (PHB
     * p.264), as the action-save rolls them (D199). Read from the spell's template, so a condition stored before keeps
     * its check too; the stored conditions are not changed.
     */
    // Each frightened entry's source and whether it is in sight (PHB p.290, D201); null for none
    private static List<EncounterResponse.FearResponse> fearResponses(EncounterParticipant participant) {
        List<Fears.Status> fears = Fears.of(participant);
        if (fears.isEmpty()) {
            return null;
        }
        return fears.stream().map(fear -> EncounterResponse.FearResponse.builder()
                .conditionId(fear.condition().id())
                .sourceId(fear.source() != null ? fear.source().getId() : null)
                .sourceName(fear.label())
                .inSight(fear.inSight())
                .sight(fear.sight().words())
                .markedOutOfSight(fear.condition().sourceMarkedOutOfSight())
                .build()).toList();
    }

    private List<ConditionEntry> withActionChecks(List<ConditionEntry> conditions) {
        if (conditions == null) {
            return null;
        }
        return conditions.stream().map(condition -> {
            RepeatSave save = condition.repeatSave();
            if (save == null || !save.madeByAction() || condition.sourceSpellName() == null) {
                return condition;
            }
            SpellTemplate.ActionSave action;
            try {
                action = spellEffects.actionSave(condition.sourceSpellName());
            } catch (ConflictException e) {
                return condition;
            }
            if (action == null || !condition.name().equalsIgnoreCase(action.condition())
                    || (!action.check() && action.skill() == null)) {
                return condition;
            }
            return condition.withRepeatSave(save.asCheck(action.skill()));
        }).toList();
    }

    // The triggers whose use may count several moves at once, as Spike Growth's ENTER (D200); none when its template
    // can't be read
    private List<SpellTrigger> repeatableTriggers(String spell, List<SpellTrigger> triggers) {
        try {
            return triggers.stream().filter(when -> spellEffects.triggerRules(spell, when).repeatable()).toList();
        } catch (ConflictException e) {
            return List.of();
        }
    }

    // Whether the creature the spell touched uses its effect, as Dragon's Breath's (D200); false when its template
    // can't be read
    private boolean usedByTarget(String spell) {
        try {
            return spellEffects.usedByTarget(spell);
        } catch (ConflictException e) {
            return false;
        }
    }

    // Whether the spell's effect can move to a new creature (Hunter's Mark, Hex); false when its template can't be read
    private boolean retargetable(String spell) {
        try {
            return spellEffects.retargetable(spell);
        } catch (ConflictException e) {
            return false;
        }
    }

    // The options each use of the spell's effect chooses between, as Eyebite's (D188); none when its template can't be
    // read
    private List<String> useOptions(String spell) {
        try {
            return spellEffects.optionEachUse(spell) ? spellEffects.castOptions(spell) : List.of();
        } catch (ConflictException e) {
            return List.of();
        }
    }

    /**
     * The spells whose Ongoing Modifiers are on the participant (D184), one chip each, such as "Bless" or "Faerie
     * Fire": each Spell Effect in the encounter that affects it, with its caster, what it does and whether it hinders.
     */
    private static List<EncounterResponse.SpellModifierResponse> modifierChips(Encounter encounter,
                                                                              EncounterParticipant participant) {
        List<EncounterResponse.SpellModifierResponse> chips = new ArrayList<>();
        for (EncounterParticipant caster : encounter.getParticipants()) {
            if (Boolean.TRUE.equals(caster.getDismissed())) {
                continue;
            }
            for (SpellEffectEntry effect : EffectApplier.effects(caster)) {
                if (effect.modifiers().isEmpty() || !effect.affected().contains(participant.getId())) {
                    continue;
                }
                chips.add(EncounterResponse.SpellModifierResponse.builder()
                        .effectId(effect.id())
                        .casterId(caster.getId())
                        .spell(effect.spell())
                        .label(ActiveModifiers.label(effect))
                        .modifiers(effect.modifiers().stream().map(OngoingModifier::describe).toList())
                        .hinders(effect.modifiers().stream().anyMatch(EncounterService::hinders))
                        .build());
            }
        }
        return chips;
    }

    // Whether a modifier works against the creature it is on, such as Bane's -1d4 or Hunter's Mark's extra damage
    private static boolean hinders(OngoingModifier modifier) {
        if (modifier.scope() != OngoingModifier.Scope.AFFECTED) {
            return true;
        }
        return switch (modifier.kind()) {
            case ATTACK_ROLL, SAVE, CHECK, INITIATIVE, HIT_DAMAGE -> modifier.sign() < 0 || modifier.flat() < 0;
            case ATTACK_MODE, SAVE_MODE -> modifier.mode() == RollMode.DISADVANTAGE;
            case ATTACKED_MODE -> modifier.mode() == RollMode.ADVANTAGE;
            case AC, SPEED -> modifier.flat() < 0;
            case CHECK_MODE -> modifier.mode() == RollMode.DISADVANTAGE;
            case REVEALED, SPEED_HALVED, NO_HEALING, SPEED_MAX, HALF_STRENGTH_DAMAGE, NO_REACTIONS -> true;
            default -> false;
        };
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

    // A Way of the Kensei monk's Monk level (XGE p.34); null for anyone else
    private static Integer kenseiLevel(EncounterParticipant participant) {
        int level = FeatureEffects.subclassLevel(participant.getCharacter(), KenseiFeatures.MONK,
                KenseiFeatures.SUBCLASS);
        return level > 0 ? Math.min(20, level) : null;
    }

    // Whether the monster's action templates list any lair actions, so it can fight in its lair (D163)
    // A player character's PHB Fighting Styles by name, from its features (M17); null for a monster
    private static List<String> fightingStyles(EncounterParticipant participant) {
        PlayerCharacter character = participant.getCharacter();
        if (character == null) {
            return null;
        }
        List<String> names = character.getFeatures() == null ? List.of() : character.getFeatures().stream()
                .filter(feature -> feature != null && feature.name() != null)
                .map(Feature::name)
                .toList();
        return AttackFeatures.FightingStyle.of(names).stream().map(AttackFeatures.FightingStyle::styleName).toList();
    }

    private boolean hasLairActions(EncounterParticipant participant) {
        Monster monster = participant.getMonster();
        return monster != null && MonsterTemplates.of(monster).hasLairActions();
    }

    /**
     * The encounter's combat log as the user may read it: all of it for the DM, and for players without the
     * entries about hidden participants, the Legendary Resistance fields, or the changes in the DM's edits of
     * monsters' turn economies (D157, D174).
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
        Set<UUID> monsters = encounter.getParticipants().stream()
                .filter(EncounterViews::numbersHidden)
                .map(EncounterParticipant::getId)
                .collect(Collectors.toSet());
        return EncounterViews.playerLog(entries, hiddenParticipants, monsters);
    }

    /*
     * How a Spell Effect's retaliation reads (D185, D195): its damage, as "2d8 fire", or the save an attacker makes and
     * what a failure gives, as "CON save or blinded (fiend, undead)"; null for none.
     */
    private static String retaliationText(SpellEffectEntry.Retaliation retaliation) {
        if (retaliation == null) {
            return null;
        }
        if (retaliation.dice() != null) {
            return retaliation.dice() + " " + retaliation.type();
        }
        return retaliation.ability() + " save or " + String.join(" and ", retaliation.conditions())
                + (retaliation.attackerTypes().isEmpty() ? "" : " (" + String.join(", ", retaliation.attackerTypes())
                + ")");
    }
}
