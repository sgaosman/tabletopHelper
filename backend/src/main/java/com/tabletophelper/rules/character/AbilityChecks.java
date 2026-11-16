package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;

import java.util.Set;

/**
 * The parts of an ability check's bonus a character's sheet gives beyond the modifier (D230): proficiency, Expertise's
 * double proficiency, and the half proficiency two class features add to checks made without proficiency.
 * <ul>
 *   <li>Jack of All Trades, from Bard 2: half the proficiency bonus, rounded down, to any ability check that doesn't
 *       already include the proficiency bonus (PHB p.54).</li>
 *   <li>Remarkable Athlete, from Champion 7: half the proficiency bonus, rounded up, to any Strength, Dexterity or
 *       Constitution check that doesn't already use it (PHB p.72).</li>
 * </ul>
 * The two don't stack: a check without proficiency takes the higher. Initiative is a Dexterity check (PHB p.189), so
 * both can apply to it, and a passive check is a check's total (PHB p.175), so Jack of All Trades applies to a passive
 * Perception without proficiency; Remarkable Athlete never does, Perception being a Wisdom check.
 */
public final class AbilityChecks {

    /** The Bard level that brings Jack of All Trades (PHB p.54). */
    public static final int JACK_OF_ALL_TRADES_LEVEL = 2;
    /** The Champion level that brings Remarkable Athlete (PHB p.72). */
    public static final int REMARKABLE_ATHLETE_LEVEL = 7;
    /** A passive check's base (PHB p.175). */
    public static final int PASSIVE_BASE = 10;

    private static final Set<Ability> ATHLETIC = Set.of(Ability.STR, Ability.DEX, Ability.CON);

    private AbilityChecks() {}

    /** Jack of All Trades' bonus: half the proficiency bonus rounded down from Bard 2, otherwise 0 (PHB p.54). */
    public static int jackOfAllTrades(int bardLevel, int proficiencyBonus) {
        return bardLevel >= JACK_OF_ALL_TRADES_LEVEL ? proficiencyBonus / 2 : 0;
    }

    /** Remarkable Athlete's bonus: half the proficiency bonus rounded up from Champion 7, otherwise 0 (PHB p.72). */
    public static int remarkableAthlete(int championLevel, int proficiencyBonus) {
        return championLevel >= REMARKABLE_ATHLETE_LEVEL ? (proficiencyBonus + 1) / 2 : 0;
    }

    /**
     * What a check of the ability made without proficiency gains from Jack of All Trades and Remarkable Athlete, the
     * higher of the two that apply; 0 for a check that already includes the proficiency bonus.
     */
    public static int halfProficiency(Ability ability, boolean proficient, int bardLevel, int championLevel,
                                      int proficiencyBonus) {
        if (proficient) {
            return 0;
        }
        int athlete = ATHLETIC.contains(ability) ? remarkableAthlete(championLevel, proficiencyBonus) : 0;
        return Math.max(jackOfAllTrades(bardLevel, proficiencyBonus), athlete);
    }

    /**
     * A check's bonus beyond the ability modifier: double the proficiency bonus with Expertise, the bonus with
     * proficiency, or else the half proficiency given.
     */
    public static int skillBonus(boolean proficient, boolean expertise, int proficiencyBonus, int halfProficiency) {
        if (expertise) return 2 * proficiencyBonus;
        if (proficient) return proficiencyBonus;
        return halfProficiency;
    }

    /**
     * A passive check's score: 10 + the check's modifier (ability modifier and skill bonus) + the passive bonuses, such
     * as Observant's +5 (PHB p.175, p.168).
     */
    public static int passive(int abilityModifier, int skillBonus, int passiveBonus) {
        return PASSIVE_BASE + abilityModifier + skillBonus + passiveBonus;
    }
}
