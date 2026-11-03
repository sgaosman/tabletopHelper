package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.shared.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manual Modifiers (D164): numbers entered by hand in place of the server's or added to them, such as a spell attack
 * bonus, a save DC, a magic weapon's bonus, a whole attack entered by hand or a forced critical hit. The DM may always
 * enter them, and a player while the campaign allows it, which it does until the DM turns it off. Each use is logged
 * with its value.
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
        return forSpell(caster.getSpellAttackBonus(), caster.getSpellSaveDc(), attackBonus, saveDc);
    }

    /**
     * The spell attack bonus and save DC entered for a spell, each against the numbers the spell would use: a class
     * spell's class's, or the caster's main ones (D164).
     */
    static List<Use> forSpell(Integer ownAttackBonus, Integer ownSaveDc, Integer attackBonus, Integer saveDc) {
        List<Use> uses = new ArrayList<>();
        if (attackBonus != null) {
            uses.add(new Use("uses spell attack bonus " + signed(attackBonus)
                    + (ownAttackBonus != null ? " instead of " + signed(ownAttackBonus) : "")));
        }
        if (saveDc != null) {
            uses.add(new Use("uses spell save DC " + saveDc + (ownSaveDc != null ? " instead of " + ownSaveDc : "")));
        }
        return uses;
    }

    /**
     * What a weapon attack adds by hand to the numbers the server works out (D164): a bonus to the attack roll and
     * damage of the attacker's choosing, such as a magic weapon's +1, which the sheet doesn't record, and a forced
     * critical hit. A bonus of 0 is none.
     */
    static List<Use> forWeaponAttack(Integer extraAttackBonus, Dice extraDamage, String extraDamageType,
                                     boolean forcedCritical) {
        List<Use> uses = new ArrayList<>();
        if (extraAttackBonus != null && extraAttackBonus != 0) {
            uses.add(new Use("adds " + signed(extraAttackBonus) + " to the attack roll"));
        }
        if (extraDamage != null) {
            uses.add(new Use("adds " + extraDamage + (extraDamageType != null ? " " + extraDamageType : "")
                    + " damage"));
        }
        if (forcedCritical) {
            uses.add(new Use("is a critical hit"));
        }
        return uses;
    }

    /**
     * An attack whose numbers were all entered by hand, as for a weapon the server doesn't know: the attack bonus
     * and damage together are one use, and a forced critical hit another.
     */
    static List<Use> forEnteredAttack(int attackBonus, Dice damage, String damageType, boolean forcedCritical) {
        List<Use> uses = new ArrayList<>();
        uses.add(new Use("uses attack bonus " + signed(attackBonus) + " and " + damage
                + (damageType != null ? " " + damageType : "") + " damage"));
        if (forcedCritical) {
            uses.add(new Use("is a critical hit"));
        }
        return uses;
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
