package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.shared.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manual Modifiers (D164): numbers entered by hand in place of the server's, such as a spell attack bonus, a save
 * DC or a forced critical hit. The DM may always enter them, and a player while the campaign allows it, which it
 * does until the DM turns it off. Each use is logged with its value.
 */
@Component
@RequiredArgsConstructor
public class ManualModifiers {

    private final CombatLogWriter combatLog;

    /**
     * One number entered by hand, as the log words it after the action it changes, such as "uses spell attack
     * bonus +9 instead of +5" or "is a critical hit".
     */
    public record Use(String change) {
    }

    /** The spell attack bonus and save DC entered for a spell, each against the caster's own. */
    static List<Use> forSpell(EncounterParticipant caster, Integer attackBonus, Integer saveDc) {
        List<Use> uses = new ArrayList<>();
        if (attackBonus != null) {
            uses.add(new Use("uses spell attack bonus " + signed(attackBonus)
                    + (caster.getSpellAttackBonus() != null ? " instead of " + signed(caster.getSpellAttackBonus()) : "")));
        }
        if (saveDc != null) {
            uses.add(new Use("uses spell save DC " + saveDc
                    + (caster.getSpellSaveDc() != null ? " instead of " + caster.getSpellSaveDc() : "")));
        }
        return uses;
    }

    /** A forced critical hit, when the attack asks for one. */
    static List<Use> forAttack(boolean forcedCritical) {
        return forcedCritical ? List.of(new Use("is a critical hit")) : List.of();
    }

    /** Whether a user may enter Manual Modifiers: the DM always, a player unless the campaign has turned them off. */
    static boolean allowed(boolean dm, Boolean campaignAllowsPlayers) {
        return dm || !Boolean.FALSE.equals(campaignAllowsPlayers);
    }

    /** Refuses a player's Manual Modifiers with a 403 while the campaign doesn't allow them; nothing has changed yet. */
    public void require(Encounter encounter, UUID userId, List<Use> uses) {
        if (!uses.isEmpty() && !allowed(CombatAccess.isDm(encounter, userId),
                encounter.getCampaign().getAllowPlayerManualModifiers())) {
            throw new ForbiddenException("This campaign doesn't allow players to enter Manual Modifiers");
        }
    }

    /**
     * Logs each use under the action's type, naming whose action it changed and whether the DM or a player
     * entered it, never a target, so it can't name a hidden one (D157).
     *
     * @param subject the action changed, such as "Wizard's Fire Bolt" or "Rogue's attack"
     */
    public void log(Encounter encounter, CombatActionType type, EncounterParticipant actor, String subject, UUID userId,
                    List<Use> uses) {
        String enteredBy = CombatAccess.isDm(encounter, userId) ? "DM" : "player";
        for (Use use : uses) {
            combatLog.write(encounter, type, "Manual Modifier (" + enteredBy + "): " + subject + " " + use.change() + ".",
                    actor, null);
        }
    }

    private static String signed(int value) {
        return value < 0 ? "-" + Math.abs(value) : "+" + value;
    }
}
