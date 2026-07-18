package com.questkeeper.encounter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questkeeper.encounter.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class CombatService {

    private final EncounterRepository encounterRepository;
    private final CombatLogRepository combatLogRepository;
    private final EncounterService encounterService;
    private final ObjectMapper objectMapper;

    @Transactional
    public EncounterResponse rollAttack(UUID encounterId, AttackRollRequest request, UUID actorParticipantId, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDmOrController(encounter, userId, actorParticipantId);

        EncounterParticipant actor = actorParticipantId != null ? findParticipant(encounter, actorParticipantId) : null;
        EncounterParticipant target = findParticipant(encounter, request.getTargetId());

        if (!target.getIsAlive()) {
            throw new IllegalArgumentException("Target is already dead");
        }

        int roll1 = ThreadLocalRandom.current().nextInt(1, 21);
        int roll2 = ThreadLocalRandom.current().nextInt(1, 21);
        int attackRoll;
        String rollDesc;

        if (Boolean.TRUE.equals(request.getAdvantage())) {
            attackRoll = Math.max(roll1, roll2);
            rollDesc = "2d20(" + roll1 + "," + roll2 + ") advantage → " + attackRoll;
        } else if (Boolean.FALSE.equals(request.getAdvantage())) {
            attackRoll = Math.min(roll1, roll2);
            rollDesc = "2d20(" + roll1 + "," + roll2 + ") disadvantage → " + attackRoll;
        } else {
            attackRoll = roll1;
            rollDesc = "d20(" + attackRoll + ")";
        }

        int total = attackRoll + request.getAttackBonus();
        String actorName = actor != null ? actor.getDisplayName() : "DM";
        boolean isNat20 = attackRoll == 20;
        boolean isNat1 = attackRoll == 1;

        if (isNat1) {
            String desc = actorName + " attacks " + target.getDisplayName()
                    + ": " + rollDesc + " + " + request.getAttackBonus() + " = " + total
                    + " — Natural 1! Miss!";
            logAction(encounter, actor, target, CombatActionType.ATTACK, desc, attackRoll, total, null, null);
            return encounterService.toResponse(encounterRepository.save(encounter));
        }

        boolean hits = isNat20 || total >= target.getArmourClass();

        if (!hits) {
            String desc = actorName + " attacks " + target.getDisplayName()
                    + ": " + rollDesc + " + " + request.getAttackBonus() + " = " + total
                    + " vs AC " + target.getArmourClass() + " — Miss!";
            logAction(encounter, actor, target, CombatActionType.ATTACK, desc, attackRoll, total, null, null);
            return encounterService.toResponse(encounterRepository.save(encounter));
        }

        DiceRoller.RollResult damageRoll = isNat20
                ? DiceRoller.rollCritical(request.getDamageDice())
                : DiceRoller.roll(request.getDamageDice());

        String critLabel = isNat20 ? " CRITICAL HIT!" : "";
        String attackDesc = actorName + " attacks " + target.getDisplayName()
                + ": " + rollDesc + " + " + request.getAttackBonus() + " = " + total
                + " vs AC " + target.getArmourClass() + " — Hit!" + critLabel;
        logAction(encounter, actor, target, CombatActionType.ATTACK, attackDesc, attackRoll, total, null, null);

        int damage = damageRoll.total();
        int actualDamage = 0;

        int tempHp = target.getHpTemp() != null ? target.getHpTemp() : 0;
        int remainingDamage = damage;
        if (tempHp > 0) {
            if (remainingDamage <= tempHp) {
                target.setHpTemp(tempHp - remainingDamage);
                actualDamage = remainingDamage;
                remainingDamage = 0;
            } else {
                remainingDamage -= tempHp;
                actualDamage = tempHp;
                target.setHpTemp(0);
            }
        }

        if (remainingDamage > 0) {
            int newHp = Math.max(0, target.getHpCurrent() - remainingDamage);
            actualDamage += (target.getHpCurrent() - newHp);
            target.setHpCurrent(newHp);

            if (newHp == 0) {
                if (target.getParticipantType() == ParticipantType.PLAYER) {
                    target.setIsAlive(false);
                    target.setDeathSaveSuccesses(0);
                    target.setDeathSaveFailures(0);
                } else {
                    target.setIsAlive(false);
                }
            }
        }

        String damageDesc = actorName + " deals " + actualDamage
                + (isNat20 ? " critical" : "") + " damage to " + target.getDisplayName()
                + " (" + damageRoll.diceCount() + "d" + damageRoll.diceSides()
                + (damageRoll.modifier() > 0 ? "+" + damageRoll.modifier() : "")
                + " = " + damage + ")"
                + (request.getDamageType() != null ? " [" + request.getDamageType() + "]" : "");
        logAction(encounter, actor, target, CombatActionType.DAMAGE, damageDesc, null, null, actualDamage, null);

        if (target.getHpCurrent() == 0) {
            if (target.getParticipantType() == ParticipantType.PLAYER) {
                logAction(encounter, actor, target, CombatActionType.KILL,
                        target.getDisplayName() + " drops to 0 HP and is dying", null, null, null, null);
            } else {
                logAction(encounter, actor, target, CombatActionType.KILL,
                        target.getDisplayName() + " is killed", null, null, null, null);
            }
        }

        if (target.getConcentrationSpell() != null && actualDamage > 0 && target.getIsAlive()) {
            checkConcentration(encounter, target, actualDamage);
        }

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse applyDamage(UUID encounterId, DamageRequest request, UUID actorParticipantId, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDmOrController(encounter, userId, actorParticipantId);

        EncounterParticipant target = findParticipant(encounter, request.getTargetId());
        if (!target.getIsAlive()) {
            throw new IllegalArgumentException("Target is already dead");
        }

        EncounterParticipant actor = actorParticipantId != null ? findParticipant(encounter, actorParticipantId) : null;

        int amount = request.getAmount();
        int actualDamage = 0;

        int tempHp = target.getHpTemp() != null ? target.getHpTemp() : 0;
        if (tempHp > 0) {
            if (amount <= tempHp) {
                target.setHpTemp(tempHp - amount);
                actualDamage = amount;
                amount = 0;
            } else {
                amount -= tempHp;
                actualDamage = tempHp;
                target.setHpTemp(0);
            }
        }

        if (amount > 0) {
            int newHp = Math.max(0, target.getHpCurrent() - amount);
            actualDamage += (target.getHpCurrent() - newHp);
            target.setHpCurrent(newHp);

            if (newHp == 0) {
                if (target.getParticipantType() == ParticipantType.PLAYER) {
                    target.setIsAlive(false);
                    target.setDeathSaveSuccesses(0);
                    target.setDeathSaveFailures(0);
                    logAction(encounter, actor, target, CombatActionType.KILL,
                            target.getDisplayName() + " drops to 0 HP and is dying", null, null, actualDamage, null);
                } else {
                    target.setIsAlive(false);
                    logAction(encounter, actor, target, CombatActionType.KILL,
                            target.getDisplayName() + " is killed", null, null, actualDamage, null);
                }
            }
        }

        if (target.getIsAlive() || actualDamage > 0) {
            String damageDesc = (actor != null ? actor.getDisplayName() : "DM")
                    + " deals " + actualDamage + " damage to " + target.getDisplayName()
                    + (request.getDamageType() != null ? " (" + request.getDamageType() + ")" : "");
            logAction(encounter, actor, target, CombatActionType.DAMAGE, damageDesc, null, null, actualDamage, null);
        }

        if (target.getConcentrationSpell() != null && actualDamage > 0 && target.getIsAlive()) {
            checkConcentration(encounter, target, actualDamage);
        }

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse applyHealing(UUID encounterId, HealRequest request, UUID actorParticipantId, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDmOrController(encounter, userId, actorParticipantId);

        EncounterParticipant target = findParticipant(encounter, request.getTargetId());
        EncounterParticipant actor = actorParticipantId != null ? findParticipant(encounter, actorParticipantId) : null;

        boolean wasDown = !target.getIsAlive();
        int oldHp = target.getHpCurrent();
        int newHp = Math.min(target.getHpMax(), oldHp + request.getAmount());
        int actualHealing = newHp - oldHp;

        target.setHpCurrent(newHp);

        if (wasDown && newHp > 0) {
            target.setIsAlive(true);
            target.setDeathSaveSuccesses(0);
            target.setDeathSaveFailures(0);
            logAction(encounter, actor, target, CombatActionType.REVIVE,
                    target.getDisplayName() + " is revived with " + actualHealing + " HP", null, null, null, actualHealing);
        }

        String healDesc = (actor != null ? actor.getDisplayName() : "DM")
                + " heals " + target.getDisplayName() + " for " + actualHealing + " HP";
        logAction(encounter, actor, target, CombatActionType.HEAL, healDesc, null, null, null, actualHealing);

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse setHp(UUID encounterId, SetHpRequest request, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDm(encounter, userId);

        EncounterParticipant target = findParticipant(encounter, request.getTargetId());

        int newHp = Math.min(request.getHpCurrent(), target.getHpMax());
        newHp = Math.max(0, newHp);
        target.setHpCurrent(newHp);

        if (request.getHpTemp() != null) {
            target.setHpTemp(Math.max(0, request.getHpTemp()));
        }

        if (newHp == 0 && target.getIsAlive()) {
            if (target.getParticipantType() == ParticipantType.PLAYER) {
                target.setIsAlive(false);
                target.setDeathSaveSuccesses(0);
                target.setDeathSaveFailures(0);
            } else {
                target.setIsAlive(false);
            }
        } else if (newHp > 0 && !target.getIsAlive()) {
            target.setIsAlive(true);
            target.setDeathSaveSuccesses(0);
            target.setDeathSaveFailures(0);
        }

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse addCondition(UUID encounterId, ConditionRequest request, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDm(encounter, userId);

        EncounterParticipant target = findParticipant(encounter, request.getTargetId());
        List<ConditionEntry> conditions = parseConditionEntries(target);
        String condition = request.getCondition().toLowerCase().trim();

        if (conditions.stream().noneMatch(c -> c.name.equals(condition))) {
            conditions.add(new ConditionEntry(condition, request.getDuration(), encounter.getRoundNumber()));
            target.setActiveConditions(serializeConditionEntries(conditions));

            String desc = target.getDisplayName() + " gains condition: " + condition
                    + (request.getDuration() != null ? " (" + request.getDuration() + " rounds)" : "");
            logAction(encounter, null, target, CombatActionType.CONDITION_ADD, desc, null, null, null, null);
        }

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse removeCondition(UUID encounterId, ConditionRequest request, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDm(encounter, userId);

        EncounterParticipant target = findParticipant(encounter, request.getTargetId());
        List<ConditionEntry> conditions = parseConditionEntries(target);
        String condition = request.getCondition().toLowerCase().trim();

        boolean removed = conditions.removeIf(c -> c.name.equals(condition));
        if (removed) {
            target.setActiveConditions(conditions.isEmpty() ? null : serializeConditionEntries(conditions));

            logAction(encounter, null, target, CombatActionType.CONDITION_REMOVE,
                    target.getDisplayName() + " loses condition: " + condition, null, null, null, null);
        }

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse rollDeathSave(UUID encounterId, DeathSaveRequest request, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);

        EncounterParticipant participant = findParticipant(encounter, request.getParticipantId());

        if (participant.getIsAlive()) {
            throw new IllegalArgumentException("Participant is not dying");
        }
        if (participant.getParticipantType() != ParticipantType.PLAYER) {
            throw new IllegalArgumentException("Only player characters make death saving throws");
        }

        verifyDmOrController(encounter, userId, request.getParticipantId());

        int roll = ThreadLocalRandom.current().nextInt(1, 21);
        String resultDesc;

        if (roll == 20) {
            participant.setHpCurrent(1);
            participant.setIsAlive(true);
            participant.setDeathSaveSuccesses(0);
            participant.setDeathSaveFailures(0);
            resultDesc = participant.getDisplayName() + " rolls a natural 20 on death save — regains 1 HP!";
            logAction(encounter, null, participant, CombatActionType.REVIVE, resultDesc, roll, roll, null, null);
        } else if (roll == 1) {
            int failures = participant.getDeathSaveFailures() + 2;
            participant.setDeathSaveFailures(failures);
            if (failures >= 3) {
                resultDesc = participant.getDisplayName() + " rolls a natural 1 — 2 death save failures. " + participant.getDisplayName() + " has died.";
                logAction(encounter, null, participant, CombatActionType.KILL, resultDesc, roll, roll, null, null);
            } else {
                resultDesc = participant.getDisplayName() + " rolls a natural 1 — 2 death save failures (" + failures + "/3)";
                logAction(encounter, null, participant, CombatActionType.DEATH_SAVE, resultDesc, roll, roll, null, null);
            }
        } else if (roll >= 10) {
            int successes = participant.getDeathSaveSuccesses() + 1;
            participant.setDeathSaveSuccesses(successes);
            if (successes >= 3) {
                resultDesc = participant.getDisplayName() + " succeeds on death save (roll: " + roll + ") — stabilized!";
                logAction(encounter, null, participant, CombatActionType.STABILIZE, resultDesc, roll, roll, null, null);
            } else {
                resultDesc = participant.getDisplayName() + " succeeds on death save (roll: " + roll + ") — " + successes + "/3 successes";
                logAction(encounter, null, participant, CombatActionType.DEATH_SAVE, resultDesc, roll, roll, null, null);
            }
        } else {
            int failures = participant.getDeathSaveFailures() + 1;
            participant.setDeathSaveFailures(failures);
            if (failures >= 3) {
                resultDesc = participant.getDisplayName() + " fails death save (roll: " + roll + ") — " + participant.getDisplayName() + " has died.";
                logAction(encounter, null, participant, CombatActionType.KILL, resultDesc, roll, roll, null, null);
            } else {
                resultDesc = participant.getDisplayName() + " fails death save (roll: " + roll + ") — " + failures + "/3 failures";
                logAction(encounter, null, participant, CombatActionType.DEATH_SAVE, resultDesc, roll, roll, null, null);
            }
        }

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse setConcentration(UUID encounterId, ConcentrationRequest request, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDmOrController(encounter, userId, request.getParticipantId());

        EncounterParticipant participant = findParticipant(encounter, request.getParticipantId());

        if (participant.getConcentrationSpell() != null && request.getSpellName() != null) {
            logAction(encounter, null, participant, CombatActionType.CONCENTRATION_LOST,
                    participant.getDisplayName() + " loses concentration on " + participant.getConcentrationSpell(), null, null, null, null);
        }

        participant.setConcentrationSpell(request.getSpellName());

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse useSpellSlot(UUID encounterId, SpellSlotRequest request, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDmOrController(encounter, userId, request.getParticipantId());

        EncounterParticipant participant = findParticipant(encounter, request.getParticipantId());
        Map<String, Map<String, Integer>> slots = parseSpellSlots(participant);

        String level = String.valueOf(request.getSlotLevel());
        Map<String, Integer> slot = slots.get(level);
        if (slot == null || slot.getOrDefault("remaining", 0) <= 0) {
            throw new IllegalArgumentException("No level " + level + " spell slots remaining");
        }

        slot.put("remaining", slot.get("remaining") - 1);
        participant.setSpellSlotsCurrent(serializeSpellSlots(slots));

        logAction(encounter, null, participant, CombatActionType.SPELL_SLOT_USE,
                participant.getDisplayName() + " uses a level " + level + " spell slot ("
                        + slot.get("remaining") + "/" + slot.get("max") + " remaining)",
                null, null, null, null);

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse restoreSpellSlot(UUID encounterId, SpellSlotRequest request, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDm(encounter, userId);

        EncounterParticipant participant = findParticipant(encounter, request.getParticipantId());
        Map<String, Map<String, Integer>> slots = parseSpellSlots(participant);

        String level = String.valueOf(request.getSlotLevel());
        Map<String, Integer> slot = slots.get(level);
        if (slot == null) {
            throw new IllegalArgumentException("Participant has no level " + level + " spell slots");
        }

        int remaining = slot.getOrDefault("remaining", 0);
        int max = slot.getOrDefault("max", 0);
        if (remaining >= max) {
            throw new IllegalArgumentException("Level " + level + " spell slots already at maximum");
        }

        slot.put("remaining", remaining + 1);
        participant.setSpellSlotsCurrent(serializeSpellSlots(slots));

        logAction(encounter, null, participant, CombatActionType.SPELL_SLOT_RESTORE,
                participant.getDisplayName() + " restores a level " + level + " spell slot ("
                        + slot.get("remaining") + "/" + max + " remaining)",
                null, null, null, null);

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse advanceTurn(UUID encounterId, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDm(encounter, userId);

        List<EncounterParticipant> sorted = encounter.getParticipants().stream()
                .sorted(Comparator.comparing(p -> p.getSortOrder() != null ? p.getSortOrder() : Integer.MAX_VALUE))
                .toList();

        if (sorted.isEmpty()) return encounterService.toResponse(encounter);

        int currentIndex = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getIsCurrentTurn()) {
                currentIndex = i;
                sorted.get(i).setIsCurrentTurn(false);
                break;
            }
        }

        int nextIndex = (currentIndex + 1) % sorted.size();
        if (nextIndex <= currentIndex || currentIndex == -1) {
            encounter.setRoundNumber(encounter.getRoundNumber() + 1);
        }
        encounter.setCurrentTurnIndex(nextIndex);

        EncounterParticipant next = sorted.get(nextIndex);
        next.setIsCurrentTurn(true);

        expireConditions(encounter, next);

        logAction(encounter, null, next, CombatActionType.TURN_ADVANCE,
                "Turn passes to " + next.getDisplayName() + " (Round " + encounter.getRoundNumber() + ")",
                null, null, null, null);

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse previousTurn(UUID encounterId, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDm(encounter, userId);

        List<EncounterParticipant> sorted = encounter.getParticipants().stream()
                .sorted(Comparator.comparing(p -> p.getSortOrder() != null ? p.getSortOrder() : Integer.MAX_VALUE))
                .toList();

        if (sorted.isEmpty()) return encounterService.toResponse(encounter);

        int currentIndex = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getIsCurrentTurn()) {
                currentIndex = i;
                sorted.get(i).setIsCurrentTurn(false);
                break;
            }
        }

        int prevIndex;
        if (currentIndex <= 0) {
            prevIndex = sorted.size() - 1;
            int round = encounter.getRoundNumber();
            if (round > 1) {
                encounter.setRoundNumber(round - 1);
            }
        } else {
            prevIndex = currentIndex - 1;
        }
        encounter.setCurrentTurnIndex(prevIndex);

        EncounterParticipant prev = sorted.get(prevIndex);
        prev.setIsCurrentTurn(true);

        logAction(encounter, null, prev, CombatActionType.TURN_BACK,
                "Turn returns to " + prev.getDisplayName() + " (Round " + encounter.getRoundNumber() + ")",
                null, null, null, null);

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional(readOnly = true)
    public List<CombatLogResponse> getCombatLog(UUID encounterId, UUID userId) {
        return combatLogRepository.findByEncounterIdOrderByCreatedAtAsc(encounterId).stream()
                .map(log -> CombatLogResponse.builder()
                        .id(log.getId())
                        .roundNumber(log.getRoundNumber())
                        .actorId(log.getActorId())
                        .actorName(log.getActorName())
                        .targetId(log.getTargetId())
                        .targetName(log.getTargetName())
                        .actionType(log.getActionType().name())
                        .description(log.getDescription())
                        .rollValue(log.getRollValue())
                        .rollTotal(log.getRollTotal())
                        .damageDealt(log.getDamageDealt())
                        .healingDone(log.getHealingDone())
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList();
    }

    private void checkConcentration(Encounter encounter, EncounterParticipant participant, int damage) {
        int dc = Math.max(10, damage / 2);
        int conMod = 0;

        if (participant.getParticipantType() == ParticipantType.PLAYER && participant.getCharacter() != null) {
            conMod = (participant.getCharacter().getConstitution() - 10) / 2;
        } else if (participant.getParticipantType() == ParticipantType.MONSTER && participant.getMonster() != null) {
            conMod = (participant.getMonster().getConstitution() - 10) / 2;
        }

        int roll = ThreadLocalRandom.current().nextInt(1, 21);
        int total = roll + conMod;

        if (total >= dc) {
            logAction(encounter, null, participant, CombatActionType.CONCENTRATION_CHECK,
                    participant.getDisplayName() + " concentration check: " + roll + " + " + conMod + " = " + total + " vs DC " + dc + " — maintained " + participant.getConcentrationSpell(),
                    roll, total, null, null);
        } else {
            String spell = participant.getConcentrationSpell();
            participant.setConcentrationSpell(null);
            logAction(encounter, null, participant, CombatActionType.CONCENTRATION_LOST,
                    participant.getDisplayName() + " concentration check: " + roll + " + " + conMod + " = " + total + " vs DC " + dc + " — lost concentration on " + spell,
                    roll, total, null, null);
        }
    }

    private void logAction(Encounter encounter, EncounterParticipant actor, EncounterParticipant target,
                           CombatActionType actionType, String description,
                           Integer rollValue, Integer rollTotal, Integer damageDealt, Integer healingDone) {
        CombatLog log = CombatLog.builder()
                .encounter(encounter)
                .roundNumber(encounter.getRoundNumber())
                .actorId(actor != null ? actor.getId() : null)
                .actorName(actor != null ? actor.getDisplayName() : null)
                .targetId(target != null ? target.getId() : null)
                .targetName(target != null ? target.getDisplayName() : null)
                .actionType(actionType)
                .description(description)
                .rollValue(rollValue)
                .rollTotal(rollTotal)
                .damageDealt(damageDealt)
                .healingDone(healingDone)
                .build();
        combatLogRepository.save(log);
    }

    private Encounter loadActiveEncounter(UUID encounterId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new IllegalArgumentException("Encounter not found"));
        if (encounter.getStatus() != EncounterStatus.ACTIVE && encounter.getStatus() != EncounterStatus.PAUSED) {
            throw new IllegalArgumentException("Encounter is not active");
        }
        return encounter;
    }

    private EncounterParticipant findParticipant(Encounter encounter, UUID participantId) {
        return encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Participant not found"));
    }

    private void verifyDm(Encounter encounter, UUID userId) {
        if (!encounter.getCampaign().getDm().getId().equals(userId)) {
            throw new IllegalArgumentException("Only the DM can perform this action");
        }
    }

    private void verifyDmOrController(Encounter encounter, UUID userId, UUID participantId) {
        if (encounter.getCampaign().getDm().getId().equals(userId)) {
            return;
        }
        if (participantId != null) {
            EncounterParticipant actor = findParticipant(encounter, participantId);
            if (userId.equals(actor.getControlledByUserId())) {
                return;
            }
        }
        throw new IllegalArgumentException("You do not have permission to perform this action");
    }

    private void expireConditions(Encounter encounter, EncounterParticipant participant) {
        List<ConditionEntry> conditions = parseConditionEntries(participant);
        if (conditions.isEmpty()) return;

        List<ConditionEntry> expired = new ArrayList<>();
        List<ConditionEntry> remaining = new ArrayList<>();

        for (ConditionEntry c : conditions) {
            if (c.duration != null && (encounter.getRoundNumber() - c.appliedRound) >= c.duration) {
                expired.add(c);
            } else {
                remaining.add(c);
            }
        }

        if (!expired.isEmpty()) {
            participant.setActiveConditions(remaining.isEmpty() ? null : serializeConditionEntries(remaining));
            for (ConditionEntry c : expired) {
                logAction(encounter, null, participant, CombatActionType.CONDITION_REMOVE,
                        c.name + " expires on " + participant.getDisplayName(), null, null, null, null);
            }
        }
    }

    private List<ConditionEntry> parseConditionEntries(EncounterParticipant participant) {
        if (participant.getActiveConditions() == null) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(participant.getActiveConditions(),
                    new TypeReference<List<ConditionEntry>>() {}));
        } catch (JsonProcessingException e) {
            try {
                List<String> legacy = objectMapper.readValue(participant.getActiveConditions(),
                        new TypeReference<List<String>>() {});
                return new ArrayList<>(legacy.stream()
                        .map(name -> new ConditionEntry(name, null, 1))
                        .toList());
            } catch (JsonProcessingException e2) {
                return new ArrayList<>();
            }
        }
    }

    private String serializeConditionEntries(List<ConditionEntry> conditions) {
        try {
            return objectMapper.writeValueAsString(conditions);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private Map<String, Map<String, Integer>> parseSpellSlots(EncounterParticipant participant) {
        if (participant.getSpellSlotsCurrent() == null) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(participant.getSpellSlotsCurrent(),
                    new TypeReference<LinkedHashMap<String, Map<String, Integer>>>() {});
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private String serializeSpellSlots(Map<String, Map<String, Integer>> slots) {
        try {
            return objectMapper.writeValueAsString(slots);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    static class ConditionEntry {
        public String name;
        public Integer duration;
        public int appliedRound;

        public ConditionEntry() {}

        public ConditionEntry(String name, Integer duration, int appliedRound) {
            this.name = name;
            this.duration = duration;
            this.appliedRound = appliedRound;
        }
    }
}
