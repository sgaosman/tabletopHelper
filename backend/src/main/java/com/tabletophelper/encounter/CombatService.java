package com.tabletophelper.encounter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.encounter.dto.*;
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
    private final SpellResolverEngine spellResolverEngine;

    @Transactional
    public EncounterResponse rollAttack(UUID encounterId, AttackRollRequest request, UUID actorParticipantId, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDmOrControllerOnTurn(encounter, userId, actorParticipantId);

        EncounterParticipant actor = actorParticipantId != null ? findParticipant(encounter, actorParticipantId) : null;
        EncounterParticipant target = findParticipant(encounter, request.getTargetId());

        boolean targetDowned = !target.getIsAlive() && target.getParticipantType() == ParticipantType.PLAYER
                && target.getDeathSaveFailures() < 3;
        if (!target.getIsAlive() && !targetDowned) {
            throw new IllegalArgumentException("Target is already dead");
        }

        String actorName = actor != null ? actor.getDisplayName() : "DM";
        boolean forceCrit = Boolean.TRUE.equals(request.getForceCrit());

        if (targetDowned) {
            boolean isRanged = Boolean.TRUE.equals(request.getIsRanged());
            boolean isCrit = forceCrit || !isRanged;
            DiceRoller.RollResult damageRoll = isCrit
                    ? DiceRoller.rollCritical(request.getDamageDice())
                    : DiceRoller.roll(request.getDamageDice());
            int damage = damageRoll.total();

            if (damage >= target.getHpMax()) {
                target.setDeathSaveFailures(3);
                String desc = actorName + " attacks " + target.getDisplayName()
                        + " (unconscious, auto-hit" + (isCrit ? ", critical" : "") + "): "
                        + damage + " damage exceeds max HP (" + target.getHpMax() + ") — instant death!";
                logAction(encounter, actor, target, CombatActionType.KILL, desc, null, null, damage, null);
                return encounterService.toResponse(encounterRepository.save(encounter));
            }

            int failsAdded = isCrit ? 2 : 1;
            int newFails = Math.min(3, target.getDeathSaveFailures() + failsAdded);
            target.setDeathSaveFailures(newFails);

            String desc = actorName + " attacks " + target.getDisplayName()
                    + " (unconscious, auto-hit" + (isCrit ? ", critical" : "") + "): "
                    + damage + " damage — " + failsAdded + " death save failure" + (failsAdded > 1 ? "s" : "") + "!";

            if (newFails >= 3) {
                logAction(encounter, actor, target, CombatActionType.KILL, desc + " " + target.getDisplayName() + " dies!", null, null, damage, null);
            } else {
                logAction(encounter, actor, target, CombatActionType.DAMAGE, desc
                        + " (" + newFails + "/3 failures)", null, null, damage, null);
            }
            return encounterService.toResponse(encounterRepository.save(encounter));
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
        boolean isNat20 = attackRoll == 20;
        boolean isCrit = isNat20 || forceCrit;
        boolean isNat1 = attackRoll == 1;

        if (isNat1 && !forceCrit) {
            String desc = actorName + " attacks " + target.getDisplayName()
                    + ": " + rollDesc + " + " + request.getAttackBonus() + " = " + total
                    + " — Natural 1! Miss!";
            logAction(encounter, actor, target, CombatActionType.ATTACK, desc, attackRoll, total, null, null);
            return encounterService.toResponse(encounterRepository.save(encounter));
        }

        boolean hits = isCrit || total >= target.getArmourClass();

        if (!hits) {
            String desc = actorName + " attacks " + target.getDisplayName()
                    + ": " + rollDesc + " + " + request.getAttackBonus() + " = " + total
                    + " vs AC " + target.getArmourClass() + " — Miss!";
            logAction(encounter, actor, target, CombatActionType.ATTACK, desc, attackRoll, total, null, null);
            return encounterService.toResponse(encounterRepository.save(encounter));
        }

        DiceRoller.RollResult damageRoll = isCrit
                ? DiceRoller.rollCritical(request.getDamageDice())
                : DiceRoller.roll(request.getDamageDice());

        String critLabel = isCrit ? " CRITICAL HIT!" : "";
        String attackDesc = actorName + " attacks " + target.getDisplayName()
                + ": " + rollDesc + " + " + request.getAttackBonus() + " = " + total
                + " vs AC " + target.getArmourClass() + " — Hit!" + critLabel;
        logAction(encounter, actor, target, CombatActionType.ATTACK, attackDesc, attackRoll, total, null, null);

        int damage = damageRoll.total();
        DamageResult result = applyDamageToTarget(encounter, target, damage);

        String damageDesc = actorName + " deals " + result.actualDamage()
                + (isNat20 ? " critical" : "") + " damage to " + target.getDisplayName()
                + " (" + damageRoll.diceCount() + "d" + damageRoll.diceSides()
                + (damageRoll.modifier() > 0 ? "+" + damageRoll.modifier() : "")
                + " = " + damage + ")"
                + (request.getDamageType() != null ? " [" + request.getDamageType() + "]" : "");
        logAction(encounter, actor, target, CombatActionType.DAMAGE, damageDesc, null, null, result.actualDamage(), null);

        if (result.droppedToZero()) {
            if (target.getParticipantType() == ParticipantType.PLAYER) {
                logAction(encounter, actor, target, CombatActionType.KILL,
                        target.getDisplayName() + " drops to 0 HP and is dying", null, null, null, null);
            } else {
                logAction(encounter, actor, target, CombatActionType.KILL,
                        target.getDisplayName() + " is killed", null, null, null, null);
            }
        }

        if (target.getConcentrationSpell() != null && result.actualDamage() > 0 && target.getIsAlive()) {
            checkConcentration(encounter, target, result.actualDamage());
        }

        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse applyDamage(UUID encounterId, DamageRequest request, UUID actorParticipantId, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDmOrController(encounter, userId, actorParticipantId);

        EncounterParticipant target = findParticipant(encounter, request.getTargetId());
        boolean targetDowned = !target.getIsAlive() && target.getParticipantType() == ParticipantType.PLAYER
                && target.getDeathSaveFailures() < 3;
        if (!target.getIsAlive() && !targetDowned) {
            throw new IllegalArgumentException("Target is already dead");
        }

        EncounterParticipant actor = actorParticipantId != null ? findParticipant(encounter, actorParticipantId) : null;
        String actorDesc = actor != null ? actor.getDisplayName() : "DM";

        if (targetDowned) {
            int amount = request.getAmount();
            if (amount >= target.getHpMax()) {
                target.setDeathSaveFailures(3);
                String desc = actorDesc + " deals " + amount + " damage to " + target.getDisplayName()
                        + " (unconscious) — exceeds max HP (" + target.getHpMax() + "), instant death!";
                logAction(encounter, actor, target, CombatActionType.KILL, desc, null, null, amount, null);
            } else {
                int newFails = Math.min(3, target.getDeathSaveFailures() + 1);
                target.setDeathSaveFailures(newFails);
                String desc = actorDesc + " deals " + amount + " damage to " + target.getDisplayName()
                        + " (unconscious) — 1 death save failure! (" + newFails + "/3)";
                if (newFails >= 3) {
                    logAction(encounter, actor, target, CombatActionType.KILL,
                            desc + " " + target.getDisplayName() + " dies!", null, null, amount, null);
                } else {
                    logAction(encounter, actor, target, CombatActionType.DAMAGE, desc, null, null, amount, null);
                }
            }
            return encounterService.toResponse(encounterRepository.save(encounter));
        }

        DamageResult result = applyDamageToTarget(encounter, target, request.getAmount());

        if (result.droppedToZero()) {
            if (target.getParticipantType() == ParticipantType.PLAYER) {
                logAction(encounter, actor, target, CombatActionType.KILL,
                        target.getDisplayName() + " drops to 0 HP and is dying", null, null, result.actualDamage(), null);
            } else {
                logAction(encounter, actor, target, CombatActionType.KILL,
                        target.getDisplayName() + " is killed", null, null, result.actualDamage(), null);
            }
        }

        if (target.getIsAlive() || result.actualDamage() > 0) {
            String damageDesc = actorDesc + " deals " + result.actualDamage() + " damage to " + target.getDisplayName()
                    + (request.getDamageType() != null ? " (" + request.getDamageType() + ")" : "");
            logAction(encounter, actor, target, CombatActionType.DAMAGE, damageDesc, null, null, result.actualDamage(), null);
        }

        if (target.getConcentrationSpell() != null && result.actualDamage() > 0 && target.getIsAlive()) {
            checkConcentration(encounter, target, result.actualDamage());
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
        boolean wasDead = wasDown && target.getDeathSaveFailures() >= 3;
        int oldHp = target.getHpCurrent();
        int newHp = Math.min(target.getHpMax(), oldHp + request.getAmount());
        int actualHealing = newHp - oldHp;

        target.setHpCurrent(newHp);

        if (wasDown && newHp > 0) {
            target.setIsAlive(true);
            target.setDeathSaveSuccesses(0);
            target.setDeathSaveFailures(0);

            List<ConditionEntry> conditions = parseConditionEntries(target);
            conditions.removeIf(c -> c.name.equals("unconscious"));
            if (conditions.stream().noneMatch(c -> c.name.equals("prone"))) {
                conditions.add(new ConditionEntry("prone", null, encounter.getRoundNumber()));
            }
            target.setActiveConditions(serializeConditionEntries(conditions));

            String reviveDesc = wasDead
                    ? target.getDisplayName() + " is resurrected with " + actualHealing + " HP (prone)"
                    : target.getDisplayName() + " is revived with " + actualHealing + " HP (prone)";
            logAction(encounter, actor, target, CombatActionType.REVIVE, reviveDesc, null, null, null, actualHealing);
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
            dropConcentrationOnZeroHp(encounter, target);
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
        verifyDmOrTargetOwner(encounter, userId, request.getTargetId());

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
        verifyDmOrTargetOwner(encounter, userId, request.getTargetId());

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
            dropConcentrationCascade(encounter, participant);
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
        if (slot == null) {
            slot = slots.get("pact_" + level);
            if (slot != null) {
                level = "pact_" + level;
            }
        }
        if (slot == null || slot.getOrDefault("remaining", 0) <= 0) {
            throw new IllegalArgumentException("No level " + request.getSlotLevel() + " spell slots remaining");
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
            slot = slots.get("pact_" + level);
            if (slot != null) {
                level = "pact_" + level;
            }
        }
        if (slot == null) {
            throw new IllegalArgumentException("Participant has no level " + request.getSlotLevel() + " spell slots");
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
    public CastSpellResponse castSpell(UUID encounterId, CastSpellRequest request, UUID actorParticipantId, UUID userId) {
        Encounter encounter = loadActiveEncounter(encounterId);
        verifyDmOrControllerOnTurn(encounter, userId, actorParticipantId);

        EncounterParticipant caster = findParticipant(encounter, actorParticipantId);

        if (request.getSlotLevel() > 0) {
            Map<String, Map<String, Integer>> slots = parseSpellSlots(caster);
            String slotKey = Boolean.TRUE.equals(request.getUsePactSlot())
                    ? "pact_" + request.getSlotLevel()
                    : String.valueOf(request.getSlotLevel());
            Map<String, Integer> slot = slots.get(slotKey);
            if (slot == null || slot.getOrDefault("remaining", 0) <= 0) {
                throw new IllegalArgumentException("No level " + request.getSlotLevel() + " spell slots remaining");
            }
            slot.put("remaining", slot.get("remaining") - 1);
            caster.setSpellSlotsCurrent(serializeSpellSlots(slots));

            logAction(encounter, null, caster, CombatActionType.SPELL_SLOT_USE,
                    caster.getDisplayName() + " uses a level " + request.getSlotLevel() + " spell slot ("
                            + slot.get("remaining") + "/" + slot.get("max") + " remaining)",
                    null, null, null, null);
        }

        int spellAttackBonus = request.getOverrideSpellAttackBonus() != null
                ? request.getOverrideSpellAttackBonus()
                : (caster.getSpellAttackBonus() != null ? caster.getSpellAttackBonus() : 0);
        int spellSaveDC = request.getOverrideSpellSaveDC() != null
                ? request.getOverrideSpellSaveDC()
                : (caster.getSpellSaveDc() != null ? caster.getSpellSaveDc() : 10);

        SpellResolverEngine.SpellCastResult result = spellResolverEngine.resolveSpell(
                encounter, caster, request.getSpellName(), request.getSlotLevel(),
                request.getTargetIds(), spellAttackBonus, spellSaveDC, request.getAdvantage());

        List<CastSpellResponse.TargetOutcome> outcomes = new ArrayList<>();

        for (SpellResolverEngine.TargetResult tr : result.targetResults()) {
            if (tr.damage() > 0) {
                EncounterParticipant target = findParticipant(encounter, tr.targetId());
                DamageResult dmgResult = applyDamageToTarget(encounter, target, tr.damage());
                if (target.getConcentrationSpell() != null && dmgResult.actualDamage() > 0 && target.getIsAlive()) {
                    checkConcentration(encounter, target, dmgResult.actualDamage());
                }
            }
            if (tr.healing() > 0) {
                EncounterParticipant target = findParticipant(encounter, tr.targetId());
                int newHp = Math.min(target.getHpMax(), target.getHpCurrent() + tr.healing());
                target.setHpCurrent(newHp);
                if (!target.getIsAlive() && newHp > 0) {
                    target.setIsAlive(true);
                    target.setDeathSaveSuccesses(0);
                    target.setDeathSaveFailures(0);
                }
            }
            if (!tr.conditionsApplied().isEmpty()) {
                EncounterParticipant target = findParticipant(encounter, tr.targetId());
                List<ConditionEntry> conditions = parseConditionEntries(target);
                for (String condName : tr.conditionsApplied()) {
                    if (conditions.stream().noneMatch(c -> c.name.equalsIgnoreCase(condName))) {
                        conditions.add(new ConditionEntry(condName, result.durationRounds(),
                                encounter.getRoundNumber(), request.getSpellName(),
                                caster.getId(), result.concentrationSet()));
                    }
                }
                target.setActiveConditions(serializeConditionEntries(conditions));
            }

            outcomes.add(CastSpellResponse.TargetOutcome.builder()
                    .targetId(tr.targetId())
                    .targetName(tr.targetName())
                    .outcome(tr.attackOutcome())
                    .damage(tr.damage() > 0 ? tr.damage() : null)
                    .healing(tr.healing() > 0 ? tr.healing() : null)
                    .conditionsApplied(tr.conditionsApplied())
                    .attackRoll("hit".equals(tr.attackOutcome()) || "miss".equals(tr.attackOutcome())
                            || "critical".equals(tr.attackOutcome()) ? tr.rollTotal() : null)
                    .saveRoll("saved".equals(tr.attackOutcome()) || "failed_save".equals(tr.attackOutcome())
                            ? tr.rollTotal() : null)
                    .build());
        }

        if (result.concentrationSet()) {
            if (caster.getConcentrationSpell() != null) {
                dropConcentrationCascade(encounter, caster);
            }
            caster.setConcentrationSpell(result.concentrationSpellName());
        }

        logAction(encounter, caster, null, CombatActionType.SPELL_CAST,
                result.description(), null, null,
                result.totalDamage() > 0 ? result.totalDamage() : null,
                result.totalHealing() > 0 ? result.totalHealing() : null);

        EncounterResponse encounterResponse = encounterService.toResponse(encounterRepository.save(encounter));

        return CastSpellResponse.builder()
                .encounterState(encounterResponse)
                .spellName(request.getSpellName())
                .slotLevelUsed(request.getSlotLevel())
                .autoResolved(result.resolved())
                .resultSummary(result.description())
                .targets(outcomes)
                .manualResolutionReason(result.requiresManualResolution() ? result.manualResolutionReason() : null)
                .build();
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
                        .turnParticipantName(log.getTurnParticipantName())
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList();
    }

    private record DamageResult(int actualDamage, boolean droppedToZero) {}

    private DamageResult applyDamageToTarget(Encounter encounter, EncounterParticipant target, int damage) {
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

        boolean droppedToZero = false;
        if (remainingDamage > 0) {
            int newHp = Math.max(0, target.getHpCurrent() - remainingDamage);
            actualDamage += (target.getHpCurrent() - newHp);
            target.setHpCurrent(newHp);

            if (newHp == 0) {
                droppedToZero = true;
                dropConcentrationOnZeroHp(encounter, target);
                if (target.getParticipantType() == ParticipantType.PLAYER) {
                    target.setIsAlive(false);
                    target.setDeathSaveSuccesses(0);
                    target.setDeathSaveFailures(0);
                } else {
                    target.setIsAlive(false);
                }
            }
        }

        return new DamageResult(actualDamage, droppedToZero);
    }

    private void checkConcentration(Encounter encounter, EncounterParticipant participant, int damage) {
        int dc = Math.max(10, damage / 2);
        int conMod = 0;
        int saveBonus = 0;

        if (participant.getParticipantType() == ParticipantType.PLAYER && participant.getCharacter() != null) {
            var character = participant.getCharacter();
            conMod = (character.getConstitution() - 10) / 2;
            if (hasConSaveProficiency(character)) {
                saveBonus = character.getProficiencyBonus() != null ? character.getProficiencyBonus() : 2;
            }
        } else if (participant.getParticipantType() == ParticipantType.MONSTER && participant.getMonster() != null) {
            conMod = (participant.getMonster().getConstitution() - 10) / 2;
        }

        int roll = ThreadLocalRandom.current().nextInt(1, 21);
        int total = roll + conMod + saveBonus;

        String bonusStr = roll + " + " + conMod;
        if (saveBonus > 0) bonusStr += " + " + saveBonus + " (prof)";
        if (total >= dc) {
            logAction(encounter, null, participant, CombatActionType.CONCENTRATION_CHECK,
                    participant.getDisplayName() + " concentration check: " + bonusStr + " = " + total + " vs DC " + dc + " — maintained " + participant.getConcentrationSpell(),
                    roll, total, null, null);
        } else {
            String spell = participant.getConcentrationSpell();
            dropConcentrationCascade(encounter, participant);
            logAction(encounter, null, participant, CombatActionType.CONCENTRATION_CHECK,
                    participant.getDisplayName() + " concentration check: " + bonusStr + " = " + total + " vs DC " + dc + " — lost concentration on " + spell,
                    roll, total, null, null);
        }
    }

    private boolean hasConSaveProficiency(com.tabletophelper.character.PlayerCharacter character) {
        String savesJson = character.getSavingThrowProficiencies();
        if (savesJson == null || savesJson.isBlank()) return false;
        try {
            List<String> saves = objectMapper.readValue(savesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return saves.stream().anyMatch(s -> "CON".equalsIgnoreCase(s) || "Constitution".equalsIgnoreCase(s));
        } catch (Exception e) {
            return false;
        }
    }

    private void dropConcentrationOnZeroHp(Encounter encounter, EncounterParticipant participant) {
        if (participant.getConcentrationSpell() != null) {
            dropConcentrationCascade(encounter, participant);
        }
    }

    private void dropConcentrationCascade(Encounter encounter, EncounterParticipant concentrator) {
        String spell = concentrator.getConcentrationSpell();
        if (spell == null) return;

        UUID casterId = concentrator.getId();
        concentrator.setConcentrationSpell(null);

        logAction(encounter, null, concentrator, CombatActionType.CONCENTRATION_LOST,
                concentrator.getDisplayName() + " loses concentration on " + spell,
                null, null, null, null);

        for (EncounterParticipant p : encounter.getParticipants()) {
            List<ConditionEntry> conditions = parseConditionEntries(p);
            List<ConditionEntry> toRemove = conditions.stream()
                    .filter(c -> c.sourceRequiresConcentration != null
                            && c.sourceRequiresConcentration
                            && spell.equals(c.sourceSpellName)
                            && casterId.equals(c.sourceParticipantId))
                    .toList();

            if (!toRemove.isEmpty()) {
                conditions.removeAll(toRemove);
                p.setActiveConditions(conditions.isEmpty() ? null : serializeConditionEntries(conditions));
                for (ConditionEntry removed : toRemove) {
                    logAction(encounter, null, p, CombatActionType.CONDITION_REMOVE,
                            removed.name + " ends on " + p.getDisplayName() + " (" + spell + " concentration lost)",
                            null, null, null, null);
                }
            }
        }
    }

    private void logAction(Encounter encounter, EncounterParticipant actor, EncounterParticipant target,
                           CombatActionType actionType, String description,
                           Integer rollValue, Integer rollTotal, Integer damageDealt, Integer healingDone) {
        String turnName = null;
        List<EncounterParticipant> sorted = encounter.getParticipants().stream()
                .sorted((a, b) -> (a.getSortOrder() != null ? a.getSortOrder() : 999) - (b.getSortOrder() != null ? b.getSortOrder() : 999))
                .toList();
        int turnIdx = encounter.getCurrentTurnIndex() != null ? encounter.getCurrentTurnIndex() : 0;
        if (!sorted.isEmpty() && turnIdx < sorted.size()) {
            turnName = sorted.get(turnIdx).getDisplayName();
        }

        CombatLog log = CombatLog.builder()
                .encounter(encounter)
                .roundNumber(encounter.getRoundNumber())
                .actorId(actor != null ? actor.getId() : null)
                .actorName(actor != null ? actor.getDisplayName() : null)
                .targetId(target != null ? target.getId() : null)
                .targetName(target != null ? target.getDisplayName() : null)
                .turnParticipantName(turnName)
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

    private void verifyDmOrControllerOnTurn(Encounter encounter, UUID userId, UUID participantId) {
        if (encounter.getCampaign().getDm().getId().equals(userId)) {
            return;
        }
        if (participantId != null) {
            EncounterParticipant actor = findParticipant(encounter, participantId);
            if (userId.equals(actor.getControlledByUserId())) {
                List<EncounterParticipant> sorted = encounter.getParticipants().stream()
                        .sorted((a, b) -> (a.getSortOrder() != null ? a.getSortOrder() : 999) - (b.getSortOrder() != null ? b.getSortOrder() : 999))
                        .toList();
                int turnIdx = encounter.getCurrentTurnIndex() != null ? encounter.getCurrentTurnIndex() : 0;
                if (turnIdx < sorted.size() && sorted.get(turnIdx).getId().equals(participantId)) {
                    return;
                }
                throw new IllegalArgumentException("You can only attack on your turn");
            }
        }
        throw new IllegalArgumentException("You do not have permission to perform this action");
    }

    private void verifyDmOrTargetOwner(Encounter encounter, UUID userId, UUID targetId) {
        if (encounter.getCampaign().getDm().getId().equals(userId)) {
            return;
        }
        if (targetId != null) {
            EncounterParticipant target = findParticipant(encounter, targetId);
            if (userId.equals(target.getControlledByUserId())) {
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
        public String sourceSpellName;
        public UUID sourceParticipantId;
        public Boolean sourceRequiresConcentration;

        public ConditionEntry() {}

        public ConditionEntry(String name, Integer duration, int appliedRound) {
            this.name = name;
            this.duration = duration;
            this.appliedRound = appliedRound;
        }

        public ConditionEntry(String name, Integer duration, int appliedRound,
                              String sourceSpellName, UUID sourceParticipantId, Boolean sourceRequiresConcentration) {
            this.name = name;
            this.duration = duration;
            this.appliedRound = appliedRound;
            this.sourceSpellName = sourceSpellName;
            this.sourceParticipantId = sourceParticipantId;
            this.sourceRequiresConcentration = sourceRequiresConcentration;
        }
    }
}
