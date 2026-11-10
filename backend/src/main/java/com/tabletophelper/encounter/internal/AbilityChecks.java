package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.RollMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;

/**
 * The d20 of an ability check the engine rolls (D199), with the advantage and disadvantage the creature has on it:
 * any exhaustion gives disadvantage on every ability check (PHB p.291, D183), and its Ongoing Modifiers' CHECK_MODEs
 * give theirs, as Enhance Ability's advantage on one ability's checks (PHB p.237) or Heat Metal's disadvantage while
 * the creature keeps hold of the hot object (PHB p.250). Any advantage and any disadvantage cancel (PHB p.173). The
 * checks are initiative, Hide's Dexterity (Stealth), the spellcasting ability checks of Counterspell and Dispel Magic,
 * contests and escape checks.
 */
public final class AbilityChecks {

    private AbilityChecks() {}

    /**
     * A check's d20.
     *
     * @param d20  the one that counts
     * @param dice how the log shows it, such as "d20(12)" or "2d20(12, 5) with advantage from Enhance Ability → 12"
     */
    public record Roll(int d20, String dice) {
    }

    /**
     * The named sources of advantage and disadvantage on the creature's check of the ability, and of the skill when it
     * has one, as {@link OngoingModifiers#checkSources} reads them, with its exhaustion's disadvantage first.
     *
     * @param ability the check's ability; null when it isn't known, as a spellcasting check with no ability given
     * @param skill   the skill, or {@link com.tabletophelper.rules.combat.OngoingModifier#INITIATIVE_CHECK}; null for
     *                none
     */
    public static List<AttackRules.Source> sources(EncounterParticipant creature, Ability ability, String skill) {
        List<AttackRules.Source> sources = new ArrayList<>();
        int exhaustion = Exhaustion.clamp(creature.getExhaustionLevel());
        if (Exhaustion.abilityCheckDisadvantage(exhaustion)) {
            sources.add(new AttackRules.Source(RollMode.DISADVANTAGE, Exhaustion.conditionName(exhaustion)));
        }
        sources.addAll(OngoingModifiers.checkSources(ability != null ? ability.name() : null, skill,
                ActiveModifiers.labelledOn(creature)));
        return sources;
    }

    /** The d20 rolled with these sources: one die, or two keeping the higher or the lower. */
    public static Roll roll(IntSupplier d20, List<AttackRules.Source> sources) {
        RollMode mode = RollMode.combine(sources.stream().map(AttackRules.Source::mode).toList());
        int first = d20.getAsInt();
        if (mode == RollMode.NORMAL) {
            String cancelled = sources.isEmpty() ? "" : " (" + AttackRules.describe(sources) + ", which cancel)";
            return new Roll(first, "d20(" + first + ")" + cancelled);
        }
        int second = d20.getAsInt();
        int kept = mode == RollMode.ADVANTAGE ? Math.max(first, second) : Math.min(first, second);
        List<String> reasons = sources.stream().filter(source -> source.mode() == mode)
                .map(AttackRules.Source::reason).toList();
        return new Roll(kept, "2d20(" + first + ", " + second + ") with " + mode.name().toLowerCase(Locale.ROOT)
                + " from " + String.join(" and ", reasons) + " → " + kept);
    }
}
