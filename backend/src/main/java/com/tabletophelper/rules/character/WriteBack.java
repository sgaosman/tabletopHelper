package com.tabletophelper.rules.character;

import com.tabletophelper.rules.combat.LifeState;

/**
 * Write-back (D166): what an encounter changed for a player character, applied to its sheet as the sheet stands when
 * the encounter completes or the character leaves it, rather than the participant's last totals copied over. So the
 * changes of two encounters the character is in add up, and what the sheet gained meanwhile, such as a heal, stays.
 * <ul>
 *   <li>Hit points, a spell slot's used count and a pool's current uses each get the participant's change since it
 *       joined, kept between 0 and the sheet's limit.</li>
 *   <li>Temporary hit points are set to what the participant had left.</li>
 *   <li>A character who died is dead, at 0 hit points and none temporary, until the DM revives it. One still Dying or
 *       Stable is written back alive at 0 hit points. A character its sheet already marks dead stays at 0, since no
 *       encounter can heal it.</li>
 * </ul>
 * Conditions aren't written back, and nothing in combat spends Hit Dice.
 */
public final class WriteBack {

    /** The sheet's hit points after Write-back, and whether the character is dead. */
    public record Outcome(CurrentHitPoints.Totals hitPoints, boolean dead) {
    }

    private WriteBack() {
    }

    /**
     * A total after the change since the participant joined: the sheet's, plus what the participant gained or lost,
     * kept between 0 and the limit.
     *
     * @param sheet  the sheet's total as it stands, such as its hit points or a slot's used count
     * @param limit  the most the sheet allows: the hit point maximum, or the slot's or pool's maximum
     * @param joined the participant's total when it joined
     * @param ended  the participant's total when the encounter ended or it left
     */
    public static int total(int sheet, int limit, int joined, int ended) {
        long changed = (long) sheet + ended - joined;
        return (int) Math.max(0, Math.min(Math.max(0, limit), changed));
    }

    /**
     * The sheet's hit points after Write-back.
     *
     * @param sheet         the sheet's hit points as they stand
     * @param sheetDead     whether the sheet already marks the character dead
     * @param joined        the hit points the participant joined with
     * @param ended         the hit points it ended with
     * @param temporaryLeft the temporary hit points it ended with
     * @param lifeState     how it ended
     */
    public static Outcome hitPoints(CurrentHitPoints.Totals sheet, boolean sheetDead, int joined, int ended,
                                    int temporaryLeft, LifeState lifeState) {
        int maximum = sheet.maximum();
        if (sheetDead || lifeState == LifeState.DEAD) {
            return new Outcome(new CurrentHitPoints.Totals(0, maximum, 0), true);
        }
        int temporary = Math.max(0, temporaryLeft);
        if (lifeState == LifeState.DYING || lifeState == LifeState.STABLE) {
            return new Outcome(new CurrentHitPoints.Totals(0, maximum, temporary), false);
        }
        return new Outcome(new CurrentHitPoints.Totals(total(sheet.current(), maximum, joined, ended), maximum,
                temporary), false);
    }
}
