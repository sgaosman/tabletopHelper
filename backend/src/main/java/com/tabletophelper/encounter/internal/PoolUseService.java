package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A participant's resource pool used at the table (D174, closing D127's open item): the DM may use anyone's, a player
 * only their own participant's (D028). The use costs what the pool's {@code spendActionType} says (D125): an action
 * or a bonus action needs the participant's turn, while a reaction or a free use may be taken on any turn; a use in
 * place of an attack takes one attack of the Attack action, opening it when none is going. A pool whose metadata says
 * {@code grantsAction}, Action Surge, adds an action, only on the participant's own turn and only once on the same
 * turn (PHB p.72). The spend follows the one rule the sheet uses too (ResourcePoolService.spend): an unlimited pool is
 * never refused, and Psionic Recovery gives back a die of the pool it names, refused when none is expended.
 * <p>
 * A pool whose metadata names an {@code effect} also does what its class feature does (M17, {@link FeatureEffects}):
 * Rage starts or ends a rage, Second Wind and Lay on Hands heal, and Ki's options take the Dodge, Dash or Disengage
 * action as a bonus action. The feature's refusals come before anything is charged, as the pool's own do.
 * <p>
 * Pools spent inside other commands, such as a monster's legendary actions or a recharge, are charged with their
 * command and never come through here.
 */
@Service
@RequiredArgsConstructor
public class PoolUseService {

    private final CombatAccess access;
    private final ActionEconomyKeeper economy;
    private final CombatLogWriter combatLog;
    private final FeatureEffects featureEffects;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;
    // A hydra whose last head dies dies with it (MM p.190, D206)
    private final EffectApplier effectApplier;

    // The Heads pool's options: a head dies (the default), or heads grow (D206)
    static final String LOSE = "lose";
    static final String GROW = "grow";

    /**
     * Uses the pool: everything is checked, and the use charged to the participant's turn economy, before the pool
     * changes.
     *
     * @throws ValidationException for a pool the participant hasn't, more than one use of Action Surge at once, or
     *                             an option or target its feature doesn't take (FeatureEffects)
     * @throws com.tabletophelper.shared.ConflictException when the pool hasn't the uses, the pool it recovers has
     *                                                     none to regain, its feature refuses, or a player's use is
     *                                                     over budget
     */
    @Transactional
    public EncounterResponse usePool(UUID encounterId, UsePoolRequest request, UUID userId) {
        int uses = request.uses() != null ? request.uses() : 1;
        // The cost is the participant's pool's, known only once the encounter is read, as a spell's casting time is
        Encounter encounter = access.beginFor(encounterId, request.participantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.participantId());
        ResourcePoolEntry pool = pool(participant, request.poolId());
        if (MonsterPools.HEADS_EFFECT.equals(FeatureEffects.effectOf(pool))) {
            heads(encounter, participant, pool, request, uses);
            return encounterService.toResponse(encounterRepository.save(encounter));
        }
        FeatureEffects.Plan plan = featureEffects.plan(encounter, participant, pool, request, userId, costOf(pool));
        ActionCost cost = plan.cost();
        boolean grantsAction = grantsAction(pool);
        // Action Surge is used on the participant's own turn, though it costs nothing from it
        access.requireTurnForActionCost(encounter, participant.getId(), userId,
                grantsAction ? ActionCost.ACTION : cost);
        if (grantsAction && uses != 1) {
            throw new ValidationException(pool.displayName() + " can be used only once at a time");
        }
        // Ending a rage spends nothing
        List<ResourcePoolEntry> spent = plan.spend() > 0
                ? ResourcePoolService.spend(participant.getResourcePoolsCurrent(), pool.poolId(), plan.spend(),
                        participant.getDisplayName())
                : participant.getResourcePoolsCurrent();

        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, participant.getId());
        String costNote = costNote(cost);
        String overReason = grantsAction
                ? ActionEconomy.actionSurgeCheck(economyOf(participant).budget(), ownTurn).orElse(null) : null;
        economy.charge(encounter, participant, charge(participant, cost, ownTurn), overReason, userId,
                new ActionEconomyKeeper.Command(CombatActionType.POOL_USE,
                        participant.getDisplayName() + "'s " + pool.displayName() + costNote));
        if (grantsAction) {
            participant.setTurnEconomy(economyOf(participant)
                    .withBudget(ActionEconomy.actionSurge(economyOf(participant).budget())));
        }
        participant.setResourcePoolsCurrent(spent);

        // Ending a rage spends nothing; so does Kensei's Shot, which is described as any other use
        String description = plan.spend() == 0 && FeatureEffects.END.equals(plan.option())
                ? participant.getDisplayName() + " ends their " + pool.displayName() + costNote
                : describe(participant, pool, plan.spend(), costNote, spent, grantsAction, plan.note());
        combatLog.write(encounter, CombatActionType.POOL_USE, description, participant,
                plan.target() != participant ? plan.target() : null);
        featureEffects.apply(encounter, participant, plan);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /*
     * A hydra's heads changed by the DM as they die or regrow (MM p.190, D206), costing nothing from the turn: "lose"
     * (the default) for heads that die, "grow" for heads that grow, which raise the pool's maximum when it has more
     * heads than ever. The trait's own counting (25 damage in a turn, two heads for each that died unless it took fire
     * damage, 10 hit points for each regrown) is the DM's. A hydra whose last head dies dies with it ("If all its heads
     * die, the hydra dies").
     */
    private void heads(Encounter encounter, EncounterParticipant hydra, ResourcePoolEntry pool,
                       UsePoolRequest request, int count) {
        String option = request.option() != null && !request.option().isBlank()
                ? request.option().trim().toLowerCase(java.util.Locale.ROOT) : LOSE;
        if (!LOSE.equals(option) && !GROW.equals(option)) {
            throw new ValidationException(pool.displayName() + " takes the option \"lose\" or \"grow\"");
        }
        if ((request.targetIds() != null && !request.targetIds().isEmpty()) || request.amounts() != null
                || request.beastId() != null || request.weapon() != null) {
            throw new ValidationException(pool.displayName() + " takes no target, amounts, beast or weapon");
        }
        if (hydra.getLifeState() == com.tabletophelper.rules.combat.LifeState.DEAD) {
            throw new com.tabletophelper.shared.ConflictException(hydra.getDisplayName() + " is dead");
        }
        int before = pool.currentUses();
        int after;
        if (GROW.equals(option)) {
            after = before + count;
        } else {
            if (count > before) {
                throw new com.tabletophelper.shared.ConflictException(hydra.getDisplayName() + " has only " + before
                        + " head" + (before == 1 ? "" : "s"));
            }
            after = before - count;
        }
        ResourcePoolEntry changed = new ResourcePoolEntry(pool.poolId(), pool.displayName(), pool.sourceType(),
                pool.sourceName(), Math.max(pool.maxUses(), after), pool.maxUsesFormula(), after, pool.resetOn(),
                pool.resetAmount(), pool.resetCheck(), pool.spendActionType(), pool.icon(), pool.metadata());
        hydra.setResourcePoolsCurrent(new java.util.ArrayList<>(hydra.getResourcePoolsCurrent().stream()
                .map(each -> each != null && pool.poolId().equals(each.poolId()) ? changed : each)
                .toList()));
        String heads = count == 1 ? "a head" : count + " heads";
        combatLog.write(encounter, CombatActionType.POOL_USE, hydra.getDisplayName()
                + (GROW.equals(option) ? " grows " + heads : " loses " + heads) + ": " + after + " head"
                + (after == 1 ? "" : "s") + " now" + (GROW.equals(option)
                ? " (the DM adds the hit points its trait gives for each head regrown)" : ""), hydra, null);
        if (after == 0) {
            effectApplier.destroy(encounter, hydra, "all its heads have died, and by its Multiple Heads trait it "
                    + "dies with them");
            effectApplier.endLinked(encounter);
        }
    }

    private static ResourcePoolEntry pool(EncounterParticipant participant, String poolId) {
        List<ResourcePoolEntry> pools = participant.getResourcePoolsCurrent();
        return (pools != null ? pools : List.<ResourcePoolEntry>of()).stream()
                .filter(pool -> pool != null && poolId.equals(pool.poolId()))
                .findFirst()
                .orElseThrow(() -> new ValidationException(participant.getDisplayName() + " has no pool " + poolId));
    }

    // The pool's spendActionType; a pool without one costs nothing from the turn
    static ActionCost costOf(ResourcePoolEntry pool) {
        String type = pool.spendActionType();
        if (type == null || type.isBlank()) {
            return ActionCost.FREE;
        }
        try {
            return ActionCost.valueOf(type.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The " + pool.poolId() + " pool has an unknown spendActionType " + type,
                    e);
        }
    }

    static boolean grantsAction(ResourcePoolEntry pool) {
        return pool.metadata() instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("grantsAction"));
    }

    // What the use charges: one attack of the Attack action for a use in place of one, otherwise the pool's cost
    private static Charge charge(EncounterParticipant participant, ActionCost cost, boolean ownTurn) {
        if (cost == ActionCost.REPLACE_ATTACK) {
            int attacksPerAction = TurnEconomies.attacksPerAction(participant);
            return ownTurn ? Charge.replaceAttack(attacksPerAction)
                    : new Charge(ActionCost.REPLACE_ATTACK, false, Charge.SpellKind.NONE, false, attacksPerAction);
        }
        return Charge.of(cost, ownTurn);
    }

    private static TurnEconomy economyOf(EncounterParticipant participant) {
        return participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
    }

    // How the log names a use's cost when it isn't an action, as the other commands' entries do (D174)
    static String costNote(ActionCost cost) {
        return switch (cost) {
            case BONUS_ACTION -> " (bonus action)";
            case REACTION -> " (reaction)";
            case REPLACE_ATTACK -> " (in place of an attack)";
            case ACTION, FREE, NONE -> "";
        };
    }

    // Such as "Vex uses Psionic Recovery (bonus action) and regains a use of Psionic Energy Dice (4/4); 0/1 left"
    private static String describe(EncounterParticipant participant, ResourcePoolEntry pool, int uses, String costNote,
                                   List<ResourcePoolEntry> spent, boolean grantsAction, String note) {
        StringBuilder text = new StringBuilder(participant.getDisplayName()).append(" uses ")
                .append(pool.displayName());
        if (uses > 1) {
            text.append(" ×").append(uses);
        }
        text.append(costNote).append(note);
        if (grantsAction) {
            text.append(" and takes an additional action");
        }
        if (pool.recovers() != null) {
            ResourcePoolEntry recovered = find(spent, pool.recovers());
            if (recovered != null) {
                text.append(" and regains ").append(uses == 1 ? "a use" : uses + " uses").append(" of ")
                        .append(recovered.displayName()).append(" (").append(remaining(recovered)).append(")");
            }
        }
        ResourcePoolEntry after = find(spent, pool.poolId());
        if (after != null) {
            text.append("; ").append(after.unlimited() ? "unlimited" : remaining(after) + " left");
        }
        return text.toString();
    }

    private static String remaining(ResourcePoolEntry pool) {
        return pool.unlimited() ? "unlimited" : pool.currentUses() + "/" + pool.maxUses();
    }

    private static ResourcePoolEntry find(List<ResourcePoolEntry> pools, String poolId) {
        return pools.stream().filter(pool -> pool != null && poolId.equals(pool.poolId())).findFirst().orElse(null);
    }
}
