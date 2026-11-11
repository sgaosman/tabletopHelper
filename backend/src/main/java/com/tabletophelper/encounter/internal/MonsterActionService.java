package com.tabletophelper.encounter.internal;

import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.dto.MultiattackOption;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monster actions, legendary actions and lair actions, all the DM's (M12); monsters cast spells through
 * SpellcastingService, as player characters do (D165). Legendary actions spend the monster's pool and can't
 * be used on its own turn, a recharge action needs its pool recharged, and a lair action happens only on the
 * Lair's turn and can't repeat two rounds running (D136, D163). A multiattack takes a target for each of its
 * attacks, or sends them all at one creature (D165). Each target's result is logged on its own entry, and a failed
 * save by a creature with Legendary Resistance left is offered for undoing (D163). An action from the monster's
 * actions costs its action, a Multiattack included; a bonus action costs its bonus action, and a reaction its
 * reaction, which may be taken on any creature's turn, the Lair's included (D138, D174). Legendary and lair actions
 * are outside the action economy. The DM is never refused for the economy, and a monster going over it isn't logged.
 * A reaction's entries are logged as REACTION_TAKEN. A d6 recharge applies to bonus actions and reactions as it does
 * to actions. Uses limited to so many a day or to one until a rest (MM p.11) are counted for actions, bonus actions,
 * reactions and legendary actions alike ({@link LimitedUsage}): each use spends the action's pool, and one with no
 * uses left is a 409, as a recharge action not yet recharged is. Rests don't happen during an encounter, so those
 * pools never refill in one.
 * <p>
 * Bonus actions and reactions resolve as actions do: those with effects the server can read automatically, the rest
 * logged for the DM (D137). Nothing prompts for a reaction, so one that changes a result already resolved, such as
 * Parry raising AC against a hit or Uncanny Dodge halving its damage, is logged with its data's note, and the DM
 * adjusts the result by hand.
 * <p>
 * A druid in Wild Shape takes its beast's actions, bonus actions and reactions the same way (PHB p.67): its player may,
 * on its turn unless the action is a reaction, and the DM for it, each charged to its turn economy as a player's are.
 * <p>
 * Summoned creatures (D182): a familiar can't attack (PHB p.240), except a Pact of the Chain warlock's, which may make
 * one attack with its reaction when the warlock forgoes one of its own attacks of the Attack action (PHB p.107); and a
 * controlled mount only Dashes, Disengages or Dodges (PHB p.198), so its actions are refused for players.
 */
@Service
@RequiredArgsConstructor
public class MonsterActionService {

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final MonsterEffects monsterEffects;
    private final Resolver resolver;
    private final CreatureStats stats;
    private final EffectApplier effectApplier;
    private final ActionEconomyKeeper economy;
    private final ResourcePoolService resourcePoolService;
    private final ObjectMapper objectMapper;
    private final MonsterRepository monsterRepository;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;
    private final CombatLogWriter combatLog;

    @Transactional
    public MonsterActionResponse monsterAction(UUID encounterId, MonsterActionRequest request, UUID userId) {
        // A monster is the DM's; a druid in Wild Shape is its player's too, on its turn unless the action is a reaction.
        // A Pact of the Chain familiar's attack takes its reaction, on its warlock's turn (PHB p.107)
        boolean pact = Boolean.TRUE.equals(request.pactOfTheChain());
        ActionCost sourceCost = pact ? ActionCost.REACTION : costOf(request.actionSource());
        Encounter encounter = access.beginForCost(encounterId, request.monsterParticipantId(), userId,
                sourceCost != null ? sourceCost : ActionCost.FREE);
        EncounterParticipant monster = monsterParticipant(encounter, request.monsterParticipantId());
        Monster monsterEntity = actingForm(monster, request.actionSource());
        MonsterTemplates templates = templatesOf(monster, monsterEntity);
        if (templates == null) {
            throw new ValidationException("Monster has no action templates");
        }
        MonsterTemplates.Action action = templates.find(request.actionSource(), request.actionName())
                .orElseThrow(() -> new ValidationException("Action '" + request.actionName()
                        + "' not found in monster's action templates (source: " + request.actionSource() + ")"));
        String actionName = MonsterEffects.nameOf(action);
        EncounterParticipant warlock = pact
                ? pactWarlock(encounter, monster, request, action, actionName) : null;
        requireSummonMayAct(encounter, monster, action, actionName, userId, pact);
        // A creature in Gaseous Form can't attack (PHB p.242, D199)
        String barred = ActiveModifiers.barredFromAttacking(monster);
        if (barred != null && (action.multiattack() || "ATTACK_ROLL".equalsIgnoreCase(action.deliveryMethod()))) {
            throw new ConflictException(monster.getDisplayName() + " can't attack while " + barred + " lasts");
        }
        MonsterEffects.Overrides overrides = new MonsterEffects.Overrides(request.overrideAttackBonus(),
                request.overrideSaveDC(), request.advantage());

        // A multiattack's attacks each take their own targets, or all go to the one target named
        MonsterEffects.Multiattack multiattack = action.multiattack()
                ? monsterEffects.multiattack(action, templates, overrides) : null;
        List<List<UUID>> componentTargetIds = request.componentTargetIds() != null
                ? componentTargets(actionName, multiattack, request) : null;
        List<UUID> targetIds = request.targetParticipantIds() != null ? request.targetParticipantIds() : List.of();
        if (componentTargetIds == null && multiattack != null && multiattack.readable() && targetIds.size() > 1) {
            throw new ValidationException(actionName + " makes its attacks one target at a time: choose a target "
                    + "for each attack");
        }
        List<EncounterParticipant> targets = CombatAccess.targets(encounter,
                componentTargetIds != null ? distinct(componentTargetIds) : targetIds, userId);
        CombatAccess.requireNotDead(targets);
        CombatAccess.requireReachable(monster, targets);
        requireTiming(encounter, monster, request.actionSource());

        if ("LEGENDARY".equals(request.actionSource())) {
            int cost = action.legendaryCost();
            if (!resourcePoolService.spendPool(monster, "monster:legendary-actions", cost)) {
                throw new ConflictException("Not enough legendary actions remaining (need " + cost + ")");
            }
        }
        ActionCost cost = pact ? ActionCost.REACTION : costOf(request.actionSource());
        ResourcePoolEntry rechargePool = MonsterPools.rechargePool(action, monsterEntity.getName(),
                cost != null ? cost.name() : ActionCost.ACTION.name());
        if (rechargePool != null) {
            startPoolIfMissing(monster, rechargePool);
            if (poolUses(monster, rechargePool.poolId()) <= 0) {
                throw new ConflictException("Recharge action '" + actionName + "' is not yet recharged");
            }
        }
        ResourcePoolEntry limitedPool = LimitedUsage.pool(action, monsterEntity.getName(),
                cost != null ? cost.name() : ActionCost.FREE.name()).orElse(null);
        if (limitedPool != null) {
            startPoolIfMissing(monster, limitedPool);
            if (poolUses(monster, limitedPool.poolId()) <= 0) {
                LimitedUsage limit = LimitedUsage.of(action).orElseThrow();
                throw new ConflictException(monster.getDisplayName() + " has no uses of " + actionName + " left: "
                        + (limit.uses() == 1 ? "its use comes" : "its " + limit.uses() + " uses come") + " back "
                        + limit.comesBack() + ", and rests don't happen during an encounter");
            }
        }
        CombatActionType logType = switch (request.actionSource()) {
            case "LEGENDARY" -> CombatActionType.LEGENDARY_ACTION;
            case "LAIR" -> CombatActionType.LAIR_ACTION;
            case "REACTION" -> CombatActionType.REACTION_TAKEN;
            default -> CombatActionType.MONSTER_ACTION;
        };
        String costNote = pact ? " (reaction, Pact of the Chain)" : costNote(cost);
        if (warlock != null) {
            // The warlock forgoes one of its own attacks of the Attack action (PHB p.107)
            economy.charge(encounter, warlock, Charge.replaceAttack(TurnEconomies.attacksPerAction(warlock)), userId,
                    new ActionEconomyKeeper.Command(CombatActionType.ATTACK, warlock.getDisplayName()
                            + "'s attack forgone for " + monster.getDisplayName() + " (Pact of the Chain)"));
            effectApplier.markAttackedOrDamaged(warlock);
        }
        if (cost != null) {
            economy.charge(encounter, monster,
                    Charge.of(cost, EncounterTurns.isCurrentTurn(encounter, monster.getId())), userId,
                    new ActionEconomyKeeper.Command(logType, monster.getDisplayName() + "'s " + actionName + costNote));
        }
        if ("LAIR".equals(request.actionSource())) {
            if (actionName.equals(monster.getLastLairActionUsed())) {
                throw new ConflictException("Lair action '" + actionName + "' cannot be used two rounds in a row");
            }
            monster.setLastLairActionUsed(actionName);
        }

        MonsterEffects.MonsterAction plan = componentTargetIds != null
                ? monsterEffects.multiattack(multiattack, componentTargetIds)
                : monsterEffects.plan(action, templates, targetIds, overrides);

        // The entry for the action as a whole names no target, so it can't name a hidden one (D157)
        String lead = monster.getDisplayName() + " uses " + actionName + costNote;
        String notes = String.join(" ", plan.notes());
        String description = plan.automated()
                ? lead + "." + (notes.isEmpty() ? "" : " " + notes)
                : lead + " — " + plan.manualReason();
        effectApplier.summary(encounter, new EffectApplier.Use(monster, monster.getDisplayName(), logType,
                "uses " + actionName + costNote, "on", monster.getDisplayName(), null), description);

        List<EffectApplier.Applied> applied = new ArrayList<>();
        // What the riders came to that the hit's entry doesn't show, such as a target too big to grapple (D202)
        List<String> riderNotes = new ArrayList<>();
        if (plan.automated()) {
            Map<UUID, EncounterParticipant> byId = targets.stream()
                    .collect(Collectors.toMap(EncounterParticipant::getId, Function.identity(), (a, b) -> a));
            for (MonsterEffects.Step step : plan.steps()) {
                // A target an earlier step killed is past attacking, and a step with none left rolls nothing
                List<EncounterParticipant> stepTargets = step.targetIds().stream()
                        .map(byId::get)
                        .filter(target -> target.getLifeState() != LifeState.DEAD)
                        .toList();
                if (stepTargets.isEmpty()) {
                    continue;
                }
                String label = step.label().equals(actionName) ? actionName : actionName + " (" + step.label() + ")";
                EffectApplier.Use use = new EffectApplier.Use(monster, monster.getDisplayName(), logType,
                        "uses " + label + costNote, "on", monster.getDisplayName(), null);
                // Ray of Enfeeblement halves a melee weapon attack's damage, taken as using Strength (PHB p.271, D197)
                boolean halved = Enfeeblement.strengthAttack(step.effect()) && Enfeeblement.weakened(monster);
                if (!step.riders().none() && step.effect().delivery() instanceof Effect.Attack) {
                    applied.addAll(attackWithRiders(encounter, use, logType, monster, step, stepTargets, halved,
                            riderNotes));
                    continue;
                }
                Effect effect = halved ? Enfeeblement.noted(step.effect()) : step.effect();
                List<Resolver.TargetOutcome> outcomes = resolver.resolve(effect, CreatureStats.side(monster),
                        stats.targets(stepTargets, effect, monster));
                List<RiderNote> sizeNotes = new ArrayList<>();
                if (!step.riders().maxTargetSize().isEmpty()) {
                    outcomes = withoutTooBig(step, stepTargets, outcomes, sizeNotes);
                }
                applied.addAll(effectApplier.apply(encounter, use, effect,
                        halved ? Enfeeblement.halved(outcomes) : outcomes));
                riderNotes.addAll(logged(encounter, logType, monster, sizeNotes));
            }
        }

        if (rechargePool != null) {
            resourcePoolService.spendPool(monster, rechargePool.poolId(), 1);
        }
        if (limitedPool != null) {
            resourcePoolService.spendPool(monster, limitedPool.poolId(), 1);
        }

        List<MonsterActionResponse.TargetResult> results = applied.stream().map(MonsterActionService::result).toList();
        String fullDescription = applied.isEmpty() ? description
                : description + " " + applied.stream().map(EffectApplier.Applied::description)
                .collect(Collectors.joining(" "));
        if (!riderNotes.isEmpty()) {
            fullDescription = fullDescription + " " + String.join(" ", riderNotes);
        }
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

    // A note the log writes about one target, after the hit's own entry (D202)
    private record RiderNote(String text, EncounterParticipant target) {
    }

    /**
     * An attack whose riders depend on each target (D202): each target gets the Effect {@link MonsterRiders} works out
     * for it, every attack is rolled before any is applied, as a step's attacks always are, and what the riders came
     * to is logged after each hit. Its targets' attacks are rolled one by one, as the Resolver rolls them anyway.
     */
    private List<EffectApplier.Applied> attackWithRiders(Encounter encounter, EffectApplier.Use use,
                                                         CombatActionType logType, EncounterParticipant monster,
                                                         MonsterEffects.Step step,
                                                         List<EncounterParticipant> stepTargets, boolean halved,
                                                         List<String> riderNotes) {
        List<Effect> effects = new ArrayList<>();
        List<Resolver.TargetOutcome> outcomes = new ArrayList<>();
        List<RiderNote> notes = new ArrayList<>();
        for (EncounterParticipant target : stepTargets) {
            MonsterRiders.Against against = MonsterRiders.against(step.effect(), step.riders(), monster, target,
                    stats.target(target, step.effect(), monster));
            Effect effect = halved ? Enfeeblement.noted(against.effect()) : against.effect();
            effects.add(effect);
            outcomes.addAll(resolver.resolve(effect, CreatureStats.side(monster),
                    List.of(stats.target(target, effect, monster))));
            against.notes().forEach(note -> notes.add(new RiderNote(note, target)));
        }
        List<EffectApplier.Applied> applied = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            List<Resolver.TargetOutcome> one = List.of(outcomes.get(i));
            applied.addAll(effectApplier.apply(encounter, use, effects.get(i), halved ? Enfeeblement.halved(one) : one));
        }
        riderNotes.addAll(logged(encounter, logType, monster, notes));
        return applied;
    }

    // The outcomes without the conditions each target is too big for, as a save action's (D202)
    private static List<Resolver.TargetOutcome> withoutTooBig(MonsterEffects.Step step,
                                                              List<EncounterParticipant> stepTargets,
                                                              List<Resolver.TargetOutcome> outcomes,
                                                              List<RiderNote> notes) {
        Map<UUID, EncounterParticipant> byId = stepTargets.stream()
                .collect(Collectors.toMap(EncounterParticipant::getId, Function.identity(), (a, b) -> a));
        List<Resolver.TargetOutcome> kept = new ArrayList<>();
        for (Resolver.TargetOutcome outcome : outcomes) {
            EncounterParticipant target = byId.get(outcome.targetId());
            List<String> said = new ArrayList<>();
            List<String> tooBig = target != null
                    ? MonsterRiders.tooBig(step.effect().name(), step.riders(), target, said) : List.of();
            said.forEach(note -> notes.add(new RiderNote(note, target)));
            kept.add(tooBig.isEmpty() ? outcome : outcome.withConditions(outcome.conditions().stream()
                    .filter(condition -> !tooBig.contains(condition)).toList()));
        }
        return kept;
    }

    // Writes each note on its own entry about its target, and gives back their texts
    private List<String> logged(Encounter encounter, CombatActionType logType, EncounterParticipant monster,
                                List<RiderNote> notes) {
        for (RiderNote note : notes) {
            combatLog.write(encounter, logType, note.text(), monster, note.target());
        }
        return notes.stream().map(RiderNote::text).toList();
    }

    /**
     * Each multiattack among a monster's actions, with the attacks it makes, for a target picker per attack (D165).
     * The DM's alone, as the monster's templates are (D157); it changes nothing, so it needs no lock and any status
     * will do.
     */
    @Transactional(readOnly = true)
    public List<MultiattackOption> multiattackOptions(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        UUID campaignId = encounter.getCampaign().getId();
        // A druid in Wild Shape's beast attacks are its player's to read too (PHB p.67), and a summoned creature's its
        // controller's (D182)
        Optional<EncounterParticipant> druid = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId) && p.getMonster() == null
                        && WildShapeForm.of(p).isPresent())
                .findFirst();
        Monster monsterEntity;
        MonsterTemplates templates;
        if (druid.isPresent()) {
            accessPolicy.requireControls(campaignId, druid.get().getControlledByUserId(), userId);
            monsterEntity = beastOf(druid.get());
            templates = monsterEntity != null ? MonsterTemplates.of(monsterEntity) : null;
        } else {
            EncounterParticipant participant = CombatAccess.participant(encounter, participantId);
            if (participant.getSummon() != null && participant.getControlledByUserId() != null) {
                accessPolicy.requireControls(campaignId, participant.getControlledByUserId(), userId);
            } else {
                accessPolicy.requireDm(campaignId, userId);
            }
            // A polymorphed monster attacks with its form's actions (PHB p.266, D192)
            monsterEntity = WildShapeForm.of(participant).isPresent() ? beastOf(participant)
                    : participant.getMonster();
            templates = templatesOf(participant, monsterEntity);
        }
        if (templates == null) {
            return List.of();
        }
        return templates.actions().stream()
                .filter(MonsterTemplates.Action::multiattack)
                .map(action -> option(monsterEffects.multiattack(action, templates, MonsterEffects.Overrides.NONE)))
                .toList();
    }

    private static MultiattackOption option(MonsterEffects.Multiattack multiattack) {
        return new MultiattackOption(multiattack.name(), multiattack.description(),
                multiattack.components().stream()
                        .map(component -> new MultiattackOption.ComponentOption(component.name(), component.automated(),
                                component.manualReason(), component.maxTargets()))
                        .toList(),
                multiattack.unreadable());
    }

    /**
     * The targets of each of a multiattack's attacks, refused with a 400 unless the action is a multiattack the server
     * can read, there is one list per attack, no attack takes more targets than it can, and at least one attack has a
     * target. A creature named twice for one attack counts once; an attack the DM resolves takes none.
     */
    private static List<List<UUID>> componentTargets(String actionName, MonsterEffects.Multiattack multiattack,
                                                     MonsterActionRequest request) {
        if (multiattack == null) {
            throw new ValidationException("Only a multiattack takes a target for each attack, and " + actionName
                    + " isn't one");
        }
        if (!multiattack.readable()) {
            throw new ValidationException(multiattack.unreadable() + ", so its attacks can't take targets one by one");
        }
        if (request.targetParticipantIds() != null && !request.targetParticipantIds().isEmpty()) {
            throw new ValidationException("Give " + actionName + "'s targets attack by attack or for the whole "
                    + "action, not both");
        }
        List<MonsterEffects.MultiattackComponent> components = multiattack.components();
        List<List<UUID>> given = request.componentTargetIds();
        if (given.size() != components.size()) {
            throw new ValidationException(actionName + " makes " + components.size() + " attack"
                    + (components.size() != 1 ? "s" : "") + ", but targets were given for " + given.size());
        }
        List<List<UUID>> targets = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            MonsterEffects.MultiattackComponent component = components.get(i);
            List<UUID> ids = given.get(i) != null ? List.copyOf(new LinkedHashSet<>(given.get(i))) : List.of();
            if (!component.automated()) {
                ids = List.of();
            } else if (component.maxTargets() != null && ids.size() > component.maxTargets()) {
                throw new ValidationException(component.name() + " can target " + component.maxTargets()
                        + " creature" + (component.maxTargets() != 1 ? "s" : "") + ", but " + ids.size()
                        + " were chosen");
            }
            targets.add(ids);
        }
        if (targets.stream().allMatch(List::isEmpty)) {
            throw new ValidationException("Choose a target for at least one of " + actionName + "'s attacks");
        }
        return targets;
    }

    // Every creature the attacks name, in the order first named
    private static List<UUID> distinct(List<List<UUID>> componentTargetIds) {
        Set<UUID> ids = new LinkedHashSet<>();
        componentTargetIds.forEach(ids::addAll);
        return List.copyOf(ids);
    }

    /**
     * What an action of this source costs from the monster's turn (D174): an action from its actions, its bonus action
     * from its bonus actions, and its reaction from its reactions; null for legendary and lair actions, which are
     * outside the economy (D163).
     */
    private static ActionCost costOf(String source) {
        return switch (source != null ? source : "") {
            case "LEGENDARY", "LAIR" -> null;
            case "BONUS" -> ActionCost.BONUS_ACTION;
            case "REACTION" -> ActionCost.REACTION;
            default -> ActionCost.ACTION;
        };
    }

    // How the log names a cost other than an action, as attacks and spells do (D174)
    private static String costNote(ActionCost cost) {
        if (cost == ActionCost.BONUS_ACTION) {
            return " (bonus action)";
        }
        return cost == ActionCost.REACTION ? " (reaction)" : "";
    }

    /**
     * When the action may be used (D163), refused with a 409: a legendary action on any turn but its monster's
     * own, the Lair's included, and a lair action only on the Lair's turn, by a living monster fighting in its
     * lair. Bonus actions and reactions have no timing of their own here: the economy decides, and the DM goes over
     * it rather than being refused (D174).
     */
    private static void requireTiming(Encounter encounter, EncounterParticipant monster, String source) {
        if ("LEGENDARY".equals(source) && EncounterTurns.isCurrentTurn(encounter, monster.getId())) {
            throw new ConflictException(monster.getDisplayName()
                    + " can't use legendary actions on its own turn, only on other creatures' turns");
        }
        if ("LAIR".equals(source)) {
            if (!EncounterTurns.isLairTurn(encounter)) {
                throw new ConflictException("Lair actions happen only on the Lair's turn, at initiative 20");
            }
            if (!EncounterTurns.inLair(encounter, monster.getId()) || monster.getLifeState() == LifeState.DEAD) {
                throw new ConflictException(monster.getDisplayName() + " isn't fighting in its lair");
            }
        }
    }

    /**
     * The stat block whose actions the participant takes: a monster's own, or for a druid in Wild Shape the beast's
     * (PHB p.67), which has no legendary or lair actions to take. Any other participant has none, a 400.
     */
    private Monster actingForm(EncounterParticipant participant, String source) {
        Optional<WildShapeForm> form = WildShapeForm.of(participant);
        if (form.isEmpty() && participant.getMonster() != null) {
            return participant.getMonster();
        }
        if (form.isEmpty()) {
            throw new ValidationException(participant.getDisplayName() + " has no monster actions: only a monster, or "
                    + "a druid in Wild Shape, takes them");
        }
        // A creature in another form takes the form's actions, never its legendary or lair actions (PHB p.67, p.274,
        // D192)
        if (("LEGENDARY".equals(source) || "LAIR".equals(source)) && form.get().wildShape()) {
            throw new ValidationException("A druid in Wild Shape takes the beast's actions, bonus actions and "
                    + "reactions, not legendary or lair actions");
        }
        if ("LEGENDARY".equals(source) || "LAIR".equals(source)) {
            throw new ValidationException(participant.getDisplayName() + " is in " + form.get().beastName()
                    + " form and takes its actions, bonus actions and reactions, not legendary or lair actions");
        }
        return beastOf(participant);
    }

    /**
     * The templates the participant acts from: its own, made for it at the cast as a Tasha's spirit's are (TCE p.109,
     * D182), or else its stat block's; null when it has neither.
     */
    private static MonsterTemplates templatesOf(EncounterParticipant participant, Monster form) {
        if (participant.getActionTemplates() != null && participant.getActionTemplates().json() != null
                && WildShapeForm.of(participant).isEmpty()) {
            return participant.getActionTemplates();
        }
        return form != null && form.getActionTemplates() != null ? MonsterTemplates.of(form) : null;
    }

    /**
     * What a summoned creature may do (D182): a familiar can't attack (PHB p.240), so a player's attack with one is a
     * 409 and the DM's is logged as the DM's call; and a creature that acts only on its caster's command, as Animate
     * Dead's undead (PHB p.212) and a tiny servant (XGE p.168) do, acts for the caster's player only while a command
     * from the caster's bonus action holds, until its next turn ends. Uncommanded, it only defends itself, which the DM
     * runs.
     */
    private void requireSummonMayAct(Encounter encounter, EncounterParticipant creature,
                                     MonsterTemplates.Action action, String actionName, UUID userId, boolean pact) {
        SummonLink link = creature.getSummon();
        if (link == null) {
            return;
        }
        boolean dm = CombatAccess.isDm(encounter, userId);
        // A controlled mount only Dashes, Disengages or Dodges, standard actions it takes as any creature does
        ControlledMount.requireMountAction(encounter, creature, "use " + actionName, userId, combatLog);
        boolean attack = action.multiattack() || "ATTACK_ROLL".equalsIgnoreCase(action.deliveryMethod());
        // A Pact of the Chain familiar's one attack is allowed (PHB p.107), checked by pactWarlock
        if (link.cannotAttack() && attack && !pact) {
            if (!dm) {
                throw new ConflictException(creature.getDisplayName() + " is a familiar, and a familiar can't attack");
            }
            combatLog.write(encounter, CombatActionType.MONSTER_ACTION, "The DM lets " + creature.getDisplayName()
                    + " use " + actionName + ", though a familiar can't attack", creature, null);
        }
        if (link.needsCommand() && link.commandedRound() == null && !dm) {
            throw new ConflictException(creature.getDisplayName() + " has no command from its caster, so it only "
                    + "defends itself; its caster commands it with a bonus action");
        }
    }

    /**
     * The warlock whose familiar attacks through Pact of the Chain (PHB p.107): the familiar's caster, with the Pact of
     * the Chain feature, on whose turn the familiar makes one attack from its actions, with its reaction, at one
     * creature. The warlock's forgone attack and the familiar's reaction are charged as any others, so a player is
     * refused when either is spent and the DM's is logged.
     *
     * @throws ValidationException for a creature that isn't a familiar from Find Familiar, a caster without Pact of the
     *                             Chain, or an action that isn't one attack from its actions at one creature
     * @throws ConflictException   off the warlock's turn, for a player, or when the warlock has left the encounter
     */
    private EncounterParticipant pactWarlock(Encounter encounter, EncounterParticipant familiar,
                                             MonsterActionRequest request, MonsterTemplates.Action action,
                                             String actionName) {
        SummonLink link = familiar.getSummon();
        if (link == null || link.kind() != SummonRules.Kind.FAMILIAR) {
            throw new ValidationException("Pact of the Chain lets a warlock's familiar from Find Familiar attack, and "
                    + familiar.getDisplayName() + " isn't one");
        }
        EncounterParticipant warlock = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(familiar.getSummonedByParticipantId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException(familiar.getDisplayName()
                        + "'s warlock isn't in the encounter"));
        if (!FeatureEffects.hasFeature(warlock.getCharacter(), SummonRules.PACT_OF_THE_CHAIN)) {
            throw new ValidationException(warlock.getDisplayName() + " doesn't have Pact of the Chain, so "
                    + familiar.getDisplayName() + " can't attack");
        }
        if (!"ACTION".equals(request.actionSource()) || action.multiattack()
                || !"ATTACK_ROLL".equalsIgnoreCase(action.deliveryMethod())) {
            throw new ValidationException("With Pact of the Chain a familiar makes one attack of its own, and "
                    + actionName + " isn't one");
        }
        if (request.componentTargetIds() != null || request.targetParticipantIds() == null
                || request.targetParticipantIds().size() != 1) {
            throw new ValidationException("A familiar's Pact of the Chain attack names one target");
        }
        if (!EncounterTurns.isCurrentTurn(encounter, warlock.getId())) {
            throw new ConflictException("A familiar attacks through Pact of the Chain when " + warlock.getDisplayName()
                    + " takes the Attack action on its own turn");
        }
        return warlock;
    }

    // The beast a druid in Wild Shape has become
    private Monster beastOf(EncounterParticipant druid) {
        WildShapeForm form = WildShapeForm.of(druid).orElseThrow();
        return monsterRepository.findById(form.monsterId())
                .orElseThrow(() -> new ConflictException(form.beastName() + "'s stat block is gone"));
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
                outcome.outcome() == Outcome.SAVE_SUCCESS || outcome.hitSave() != null && outcome.hitSave().success(),
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

    // A monster that joined before its recharge pools covered bonus actions and reactions, or before limited uses were
    // counted, starts the pool full, as the ability is at the start of an encounter
    private static void startPoolIfMissing(EncounterParticipant monster, ResourcePoolEntry pool) {
        List<ResourcePoolEntry> pools = monster.getResourcePoolsCurrent() != null
                ? monster.getResourcePoolsCurrent() : List.of();
        if (pools.stream().noneMatch(existing -> existing != null && pool.poolId().equals(existing.poolId()))) {
            List<ResourcePoolEntry> started = new ArrayList<>(pools);
            started.add(pool);
            monster.setResourcePoolsCurrent(started);
        }
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
