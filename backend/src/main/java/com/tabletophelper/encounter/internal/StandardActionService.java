package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.ObjectInteractionRequest;
import com.tabletophelper.encounter.dto.StandardActionCost;
import com.tabletophelper.encounter.dto.StandardActionRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollModifiers;
import com.tabletophelper.rules.combat.StandardActions;
import com.tabletophelper.rules.combat.StandardActions.Action;
import com.tabletophelper.rules.combat.TurnBudget;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The standard actions other than Attack and Cast a Spell (PHB p.192–193), and object interactions (p.190), on the
 * participant's turn (D174): the DM for anyone, a player for their own participant (D028). Each is charged to the
 * turn economy as the other commands are (ActionEconomyKeeper), then logged.
 * <ul>
 *   <li>An action costs the action, or the bonus action where Cunning Action (Dash, Disengage, Hide) or Nimble Escape
 *       (Disengage, Hide) grants it. A player asking for a bonus action without such a feature gets a 409 saying
 *       so; the DM is never refused, and a player character's is logged as over the action economy.</li>
 *   <li>Dash and Disengage are shown in the economy; Dodge leaves a {@link StandardActions#DODGING} entry among the
 *       dodger's conditions, ending at the start of its next turn, which the rules read for attacks against it and
 *       its Dexterity saves; Help names its creature, whose advantage is declared on the roll it helps; Hide rolls
 *       Dexterity (Stealth) for the DM to judge; Ready logs its trigger, the response being a later reaction; Search
 *       and Use an Object are logged.</li>
 *   <li>The first object interaction each turn is free; a player's second gets a 409, since it needs the Use an
 *       Object action, and the DM's is logged as over the action economy.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardActionService {

    private static final String STEALTH = "Stealth";

    private final CombatAccess access;
    private final ActionEconomyKeeper economy;
    private final Resolver resolver;
    private final CreatureStats stats;
    private final CombatLogWriter combatLog;
    private final EffectApplier effectApplier;
    private final ObjectMapper objectMapper;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Takes the action: it is checked and charged before anything is rolled or changed.
     *
     * @throws ValidationException for Help without a creature, a creature named by any other action, or Ready without
     *                             its trigger
     * @throws ConflictException   when a player's action is over budget or asks for a bonus action nothing grants
     */
    @Transactional
    public EncounterResponse standardAction(UUID encounterId, StandardActionRequest request, UUID userId) {
        Action action = request.action();
        ActionCost cost = (request.cost() != null ? request.cost() : StandardActionCost.ACTION).actionCost();
        Encounter encounter = access.beginOnTurnForAction(encounterId, request.participantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.participantId());
        EncounterParticipant target = target(encounter, request, userId);
        String note = request.note() != null && !request.note().isBlank() ? request.note().trim() : null;
        if (action == Action.READY && note == null) {
            throw new ValidationException("Ready needs its trigger in the note");
        }

        // A controlled mount only Dashes, Disengages or Dodges (PHB p.198)
        if (action != Action.DASH && action != Action.DISENGAGE && action != Action.DODGE) {
            ControlledMount.requireMountAction(encounter, participant, "take the " + action.label() + " action", userId,
                    combatLog);
        }

        String overReason = null;
        String grant = null;
        if (cost == ActionCost.BONUS_ACTION) {
            Optional<String> granted = StandardActions.bonusActionGrant(action, featureNames(participant));
            grant = granted.orElse(null);
            overReason = granted.isPresent() ? null : StandardActions.bonusActionRefusal(action);
        }
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, participant.getId());
        // The log names the cost, as an attack's or a spell's entry does
        String costNote = cost == ActionCost.BONUS_ACTION
                ? " (bonus action" + (grant != null ? ", " + grant : "") + ")" : " (action)";
        economy.charge(encounter, participant, Charge.of(cost, ownTurn), overReason, userId,
                new ActionEconomyKeeper.Command(CombatActionType.STANDARD_ACTION,
                        participant.getDisplayName() + "'s " + action.label() + costNote));

        String name = participant.getDisplayName();
        String taken = name + " takes the " + action.label() + " action" + costNote;
        switch (action) {
            case DASH -> {
                participant.setTurnEconomy(economyOf(participant).withBudget(ActionEconomy.dash(budget(participant))));
                log(encounter, taken + ", gaining extra movement equal to its speed this turn.", participant, null);
            }
            case DISENGAGE -> {
                participant.setTurnEconomy(economyOf(participant)
                        .withBudget(ActionEconomy.disengage(budget(participant))));
                log(encounter, taken + ": its movement doesn't provoke opportunity attacks for the rest of the turn.",
                        participant, null);
            }
            case DODGE -> {
                effectApplier.dodge(encounter, participant, Action.DODGE.label());
                log(encounter, taken + " until the start of its next turn: attacks against it have disadvantage and "
                        + "it has advantage on Dexterity saves.", participant, null);
            }
            case HELP -> log(encounter, taken + " for " + target.getDisplayName()
                    + suffix(note) + ": the advantage it gives is declared on the roll it helps.", participant, target);
            case HIDE -> hide(encounter, participant, taken);
            case READY -> log(encounter, taken + ", waiting for: " + note
                    + ". Its response will take its reaction.", participant, null);
            case SEARCH, USE_OBJECT -> log(encounter, taken + (note != null ? ": " + note : "") + ".", participant,
                    null);
        }
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * An object interaction: the first each turn is free (PHB p.190). Another needs the Use an Object action, so a
     * player's is refused with a 409 saying so, as is an incapacitated player's, and the DM's is applied and logged as
     * over the action economy.
     */
    @Transactional
    public EncounterResponse objectInteraction(UUID encounterId, ObjectInteractionRequest request, UUID userId) {
        Encounter encounter = access.beginOnTurnForAction(encounterId, request.participantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.participantId());
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, participant.getId());
        String overReason = ActionEconomy.objectInteractionCheck(budget(participant), conditionNames(participant))
                .orElse(null);
        economy.charge(encounter, participant, Charge.of(ActionCost.FREE, ownTurn), overReason, userId,
                new ActionEconomyKeeper.Command(CombatActionType.OBJECT_INTERACTION,
                        participant.getDisplayName() + "'s object interaction"));
        participant.setTurnEconomy(economyOf(participant)
                .withBudget(ActionEconomy.objectInteraction(budget(participant))));
        log(encounter, CombatActionType.OBJECT_INTERACTION, participant.getDisplayName() + " interacts with an object: "
                + request.description().trim(), participant, null);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    private static List<String> conditionNames(EncounterParticipant participant) {
        List<ConditionEntry> conditions = participant.getActiveConditions();
        return conditions == null ? List.of() : conditions.stream().map(ConditionEntry::name).toList();
    }

    // The creature Help names, which it must; no other action names one
    private static EncounterParticipant target(Encounter encounter, StandardActionRequest request, UUID userId) {
        if (request.action() != Action.HELP) {
            if (request.targetId() != null) {
                throw new ValidationException("Only Help names a creature");
            }
            return null;
        }
        if (request.targetId() == null) {
            throw new ValidationException("Help names the creature it helps or distracts");
        }
        return CombatAccess.target(encounter, request.targetId(), userId);
    }

    // Dexterity (Stealth) from the sheet or the stat block, for the DM to judge against whoever is looking. Armour
    // that hinders stealth, or armour worn without proficiency, gives the check disadvantage (PHB p.144-145), as does
    // any exhaustion, which gives every ability check disadvantage (PHB p.291, D183)
    private void hide(Encounter encounter, EncounterParticipant participant, String taken) {
        int modifier = stats.skillModifier(participant, STEALTH, Ability.DEX);
        String dice;
        int d20;
        int exhaustion = Exhaustion.clamp(participant.getExhaustionLevel());
        List<String> reasons = new ArrayList<>();
        if (stats.armour(participant).stealthDisadvantage()) {
            reasons.add("armour");
        }
        if (Exhaustion.abilityCheckDisadvantage(exhaustion)) {
            reasons.add(Exhaustion.conditionName(exhaustion));
        }
        if (!reasons.isEmpty()) {
            int first = resolver.d20();
            int second = resolver.d20();
            d20 = Math.min(first, second);
            dice = "2d20(" + first + ", " + second + ") with disadvantage from " + String.join(" and ", reasons)
                    + " → " + d20;
        } else {
            d20 = resolver.d20();
            dice = "d20(" + d20 + ")";
        }
        // The Ongoing Modifiers on the check, as Guidance's d4 (PHB p.248) or Pass without Trace's +10 (PHB p.264); a
        // one-shot one is spent (D184)
        List<RollModifiers.Bonus> bonuses = OngoingModifiers.checkBonuses(STEALTH,
                ActiveModifiers.labelledOn(participant));
        List<RollModifiers.RolledBonus> rolled = resolver.rollBonuses(bonuses);
        int total = d20 + modifier + Resolver.sum(rolled);
        String added = rolled.stream().map(bonus -> " " + bonus.describe()).collect(Collectors.joining());
        String description = taken + ": Dexterity (Stealth) " + dice + signed(modifier) + added + " = " + total
                + "; the DM decides whether it is hidden.";
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.STANDARD_ACTION,
                description), participant, null).rollValue(d20).rollTotal(total));
        effectApplier.spendModifiers(encounter, EffectApplier.oneShotsOf(bonuses));
    }

    /**
     * The names of what the participant has that could grant a bonus action: a player character's features, and a
     * monster's traits and bonus actions from its stat block and its action templates.
     */
    private List<String> featureNames(EncounterParticipant participant) {
        List<String> names = new ArrayList<>();
        PlayerCharacter character = participant.getCharacter();
        if (character != null && character.getFeatures() != null) {
            character.getFeatures().stream().filter(Objects::nonNull).map(Feature::name).forEach(names::add);
        }
        Monster monster = participant.getMonster();
        if (monster != null) {
            try {
                for (JsonNode trait : StoredJson.read(objectMapper, monster.getTraits(), monster.getName() + "'s traits")) {
                    names.add(trait.path("name").asString(""));
                }
                MonsterTemplates templates = MonsterTemplates.of(monster);
                templates.bonusActions().forEach(entry -> names.add(entry.name()));
                templates.traits().forEach(entry -> names.add(entry.name()));
            } catch (ConflictException e) {
                // Unreadable data grants nothing; the DM can still take the bonus action
                log.warn("{}'s traits or templates can't be read for a bonus action", monster.getName());
            }
        }
        return names;
    }

    private void log(Encounter encounter, String description, EncounterParticipant actor, EncounterParticipant target) {
        log(encounter, CombatActionType.STANDARD_ACTION, description, actor, target);
    }

    private void log(Encounter encounter, CombatActionType type, String description, EncounterParticipant actor,
                     EncounterParticipant target) {
        combatLog.write(encounter, type, description, actor, target);
    }

    private static TurnBudget budget(EncounterParticipant participant) {
        return economyOf(participant).budget();
    }

    private static TurnEconomy economyOf(EncounterParticipant participant) {
        return participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
    }

    private static String suffix(String note) {
        return note != null ? " (" + note + ")" : "";
    }

    private static String signed(int value) {
        return value >= 0 ? " + " + value : " - " + (-value);
    }
}
