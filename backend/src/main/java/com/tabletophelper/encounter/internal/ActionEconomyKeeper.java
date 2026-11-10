package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterViews;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.TurnBudget;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Charges a command to its actor's turn economy (D174). A command is charged after everything else about it has
 * been checked and before anything rolls or is spent, inside the encounter's lock (D160). A player's command over
 * budget is refused with a 409 that says why; the DM's never is: it applies, and for a creature on the players' side
 * the combat log says it went over the action economy. The DM's monsters are tracked the same way, but going over
 * with them isn't logged. A reaction by a creature an Ongoing Modifier keeps from taking reactions, as Shocking
 * Grasp's target (PHB p.275), is over budget the same way (D199).
 */
@Component
@RequiredArgsConstructor
public class ActionEconomyKeeper {

    private final CombatLogWriter combatLog;

    /**
     * What the log calls a charged command when it goes over, such as "Rogue's attack (bonus action)", and the type
     * of that entry.
     */
    public record Command(CombatActionType type, String subject) {
    }

    /** Charges the command, or refuses a player's over budget; see the class. */
    public void charge(Encounter encounter, EncounterParticipant actor, Charge charge, UUID userId, Command command) {
        charge(encounter, actor, charge, null, userId, command);
    }

    /**
     * As {@link #charge(Encounter, EncounterParticipant, Charge, UUID, Command)}, with a reason the command goes
     * over whatever the budget says, such as a spell whose casting time is longer than a turn; null for none.
     */
    public void charge(Encounter encounter, EncounterParticipant actor, Charge charge, String overReason,
                       UUID userId, Command command) {
        TurnBudget budget = budget(actor);
        Optional<String> reason = ActionEconomy.check(budget, charge, conditionNames(actor));
        // A spell that takes reactions away, as Shocking Grasp (PHB p.275) or Tasha's Mind Whip (TCE p.115) (D199)
        if (reason.isEmpty() && charge.cost() == ActionCost.REACTION) {
            reason = ActiveModifiers.on(actor).stream()
                    .filter(found -> found.modifier().kind() == OngoingModifier.Kind.NO_REACTIONS)
                    .findFirst()
                    .map(found -> "It can't take reactions (" + ActiveModifiers.label(found.effect()) + ")");
        }
        if (overReason != null) {
            reason = Optional.of(reason.map(other -> overReason + "; " + lowerFirst(other)).orElse(overReason));
        }
        if (reason.isPresent()) {
            if (!CombatAccess.isDm(encounter, userId)) {
                throw new ConflictException(reason.get());
            }
            if (!EncounterViews.numbersHidden(actor)) {
                combatLog.write(encounter, command.type(), command.subject() + " is over the action economy: "
                        + lowerFirst(reason.get()), actor, null);
            }
        }
        TurnEconomy economy = actor.getTurnEconomy() != null ? actor.getTurnEconomy() : TurnEconomy.fresh();
        actor.setTurnEconomy(economy.withBudget(ActionEconomy.spend(budget, charge)));
    }

    private static TurnBudget budget(EncounterParticipant actor) {
        TurnEconomy economy = actor.getTurnEconomy();
        return (economy != null ? economy : TurnEconomy.fresh()).budget();
    }

    private static List<String> conditionNames(EncounterParticipant actor) {
        List<ConditionEntry> conditions = actor.getActiveConditions();
        return conditions == null ? List.of() : conditions.stream().map(ConditionEntry::name).toList();
    }

    // The rules' reasons start "It ...", which reads on after a colon as "it ..."; a name keeps its capital
    private static String lowerFirst(String text) {
        return text.startsWith("It ") ? "it " + text.substring(3) : text;
    }
}
