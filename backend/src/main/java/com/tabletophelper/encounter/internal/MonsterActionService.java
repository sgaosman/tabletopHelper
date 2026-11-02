package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.MonsterSpellCastService;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.dto.MonsterSpellRequest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monster actions, legendary actions, lair actions and monster spells, all the DM's (M12). Legendary actions
 * spend the monster's pool, a recharge action needs its pool recharged, and a lair action can't repeat two
 * rounds running (D136). Each target's result is logged on its own entry, and a failed save by a creature with
 * Legendary Resistance left is offered for undoing (D163).
 */
@Service
@RequiredArgsConstructor
public class MonsterActionService {

    private final CombatAccess access;
    private final MonsterEffects monsterEffects;
    private final Resolver resolver;
    private final CreatureStats stats;
    private final EffectApplier effectApplier;
    private final MonsterSpellCastService monsterSpellCastService;
    private final ResourcePoolService resourcePoolService;
    private final ObjectMapper objectMapper;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public MonsterActionResponse monsterAction(UUID encounterId, MonsterActionRequest request, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        EncounterParticipant monster = monsterParticipant(encounter, request.monsterParticipantId());
        Monster monsterEntity = monster.getMonster();
        if (monsterEntity == null || monsterEntity.getActionTemplates() == null) {
            throw new ValidationException("Monster has no action templates");
        }
        JsonNode templates = StoredJson.read(objectMapper, monsterEntity.getActionTemplates(),
                monsterEntity.getName() + "'s action templates");
        JsonNode action = findAction(templates, request.actionName(), request.actionSource());
        if (action == null) {
            throw new ValidationException("Action '" + request.actionName()
                    + "' not found in monster's action templates (source: " + request.actionSource() + ")");
        }
        String actionName = action.path("name").asText("Unknown Action");
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.targetParticipantIds(), userId);
        CombatAccess.requireNotDead(targets);

        if ("LEGENDARY".equals(request.actionSource())) {
            int cost = action.path("cost").asInt(1);
            if (!resourcePoolService.spendPool(monster, "monster:legendary-actions", cost)) {
                throw new ConflictException("Not enough legendary actions remaining (need " + cost + ")");
            }
        }
        String rechargePool = rechargePoolId(action);
        if (rechargePool != null && poolUses(monster, rechargePool) <= 0) {
            throw new ConflictException("Recharge action '" + actionName + "' is not yet recharged");
        }
        if ("LAIR".equals(request.actionSource())) {
            if (actionName.equals(monster.getLastLairActionUsed())) {
                throw new ConflictException("Lair action '" + actionName + "' cannot be used two rounds in a row");
            }
            monster.setLastLairActionUsed(actionName);
        }

        MonsterEffects.MonsterAction plan = monsterEffects.plan(action, templates, request.targetParticipantIds(),
                request.overrideAttackBonus(), request.overrideSaveDC(), request.advantage());
        CombatActionType logType = switch (request.actionSource()) {
            case "LEGENDARY" -> CombatActionType.LEGENDARY_ACTION;
            case "LAIR" -> CombatActionType.LAIR_ACTION;
            default -> CombatActionType.MONSTER_ACTION;
        };

        // The entry for the action as a whole names no target, so it can't name a hidden one (D157)
        String lead = monster.getDisplayName() + " uses " + actionName;
        String notes = String.join(" ", plan.notes());
        String description = plan.automated()
                ? lead + "." + (notes.isEmpty() ? "" : " " + notes)
                : lead + " — " + plan.manualReason();
        effectApplier.summary(encounter, new EffectApplier.Use(monster, monster.getDisplayName(), logType,
                "uses " + actionName, "on", monster.getDisplayName(), null), description);

        List<EffectApplier.Applied> applied = new ArrayList<>();
        if (plan.automated()) {
            Map<UUID, EncounterParticipant> byId = targets.stream()
                    .collect(Collectors.toMap(EncounterParticipant::getId, Function.identity(), (a, b) -> a));
            for (MonsterEffects.Step step : plan.steps()) {
                // A target an earlier step killed is past attacking
                List<EncounterParticipant> stepTargets = step.targetIds().stream()
                        .map(byId::get)
                        .filter(target -> target.getLifeState() != LifeState.DEAD)
                        .toList();
                String label = step.label().equals(actionName) ? actionName : actionName + " (" + step.label() + ")";
                EffectApplier.Use use = new EffectApplier.Use(monster, monster.getDisplayName(), logType,
                        "uses " + label, "on", monster.getDisplayName(), null);
                applied.addAll(effectApplier.apply(encounter, use, step.effect(),
                        resolver.resolve(step.effect(), stats.targets(stepTargets, step.effect()))));
            }
        }

        if (rechargePool != null) {
            resourcePoolService.spendPool(monster, rechargePool, 1);
        }

        List<MonsterActionResponse.TargetResult> results = applied.stream().map(MonsterActionService::result).toList();
        String fullDescription = applied.isEmpty() ? description
                : description + " " + applied.stream().map(EffectApplier.Applied::description)
                .collect(Collectors.joining(" "));
        return new MonsterActionResponse(
                plan.automated(),
                fullDescription,
                applied.stream().mapToInt(EffectApplier.Applied::damageTaken).sum(),
                applied.stream().mapToInt(EffectApplier.Applied::healed).sum(),
                results,
                !plan.automated(),
                plan.manualReason(),
                applied.stream().flatMap(a -> a.conditionNames().stream()).distinct().toList(),
                results.stream().anyMatch(MonsterActionResponse.TargetResult::legendaryResistanceAvailable),
                results.stream().mapToInt(MonsterActionResponse.TargetResult::legendaryResistanceRemaining).max().orElse(0),
                encounterService.toResponse(encounterRepository.save(encounter)));
    }

    @Transactional
    public CastSpellResponse monsterSpell(UUID encounterId, MonsterSpellRequest request, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        EncounterParticipant monster = monsterParticipant(encounter, request.monsterParticipantId());

        // Every caster gets its spells, but an innate caster gets no spell slots: its daily uses live in
        // resource pools (D165)
        if (monster.getSpellsKnown() == null
                && monster.getMonster() != null && monster.getMonster().getActionTemplates() != null) {
            monsterSpellCastService.populateMonsterSpellcasting(monster, monster.getMonster().getActionTemplates());
            monsterSpellCastService.populateInnateSpellPools(monster, monster.getMonster().getActionTemplates());
        }
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.targetParticipantIds(), userId);

        SpellCaster.CastResult result = monsterSpellCastService.castMonsterSpell(encounter, monster,
                request.spellName(), request.slotLevel(), targets, request.overrideAttackBonus(),
                request.overrideSaveDC(), request.advantage());

        return CastSpellResponse.builder()
                .encounterState(encounterService.toResponse(encounterRepository.save(encounter)))
                .spellName(request.spellName())
                .slotLevelUsed(request.slotLevel())
                .autoResolved(result.autoResolved())
                .resultSummary(result.summary())
                .targets(result.targets())
                .manualResolutionReason(result.manualReason())
                .build();
    }

    private static EncounterParticipant monsterParticipant(Encounter encounter, UUID participantId) {
        return encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Monster participant not found"));
    }

    private static MonsterActionResponse.TargetResult result(EffectApplier.Applied applied) {
        Resolver.TargetOutcome outcome = applied.outcome();
        return new MonsterActionResponse.TargetResult(
                applied.target().getId(),
                applied.target().getDisplayName(),
                applied.damageTaken(),
                applied.healed(),
                outcome.outcome() == Outcome.SAVE_SUCCESS,
                applied.conditionNames(),
                switch (outcome.outcome()) {
                    case SAVE_SUCCESS -> "SAVED";
                    case SAVE_FAIL -> "FAILED_SAVE";
                    case AUTOMATIC -> "HIT";
                    default -> outcome.outcome().name();
                },
                outcome.d20(),
                outcome.total(),
                applied.damageType(),
                applied.legendaryResistanceOffered(),
                applied.legendaryResistanceRemaining());
    }

    // The action of that name in the source's list: actions, legendaryActions or lairActions
    private static JsonNode findAction(JsonNode templates, String actionName, String source) {
        String list = switch (source) {
            case "LEGENDARY" -> "legendaryActions";
            case "LAIR" -> "lairActions";
            default -> "actions";
        };
        for (JsonNode action : templates.path(list)) {
            if (action.path("name").asText("").equals(actionName)) {
                return action;
            }
        }
        return null;
    }

    // The pool a recharge action spends, "monster:recharge:<name in kebab case>", or null for an action
    // without a d6 recharge
    private static String rechargePoolId(JsonNode action) {
        String recharge = action.path("recharge").asText("");
        if (!recharge.matches("^[4-6]-[5-6]$") && !recharge.matches("^[4-6]$")) {
            return null;
        }
        String kebab = action.path("name").asText("").toLowerCase()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        return "monster:recharge:" + kebab;
    }

    private static int poolUses(EncounterParticipant participant, String poolId) {
        if (participant.getResourcePoolsCurrent() == null) {
            return 0;
        }
        return participant.getResourcePoolsCurrent().stream()
                .filter(pool -> pool.poolId().equals(poolId))
                .mapToInt(pool -> pool.currentUses())
                .findFirst()
                .orElse(0);
    }
}
