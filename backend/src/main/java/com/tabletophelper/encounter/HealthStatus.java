package com.tabletophelper.encounter;

/** How a monster's HP appears to players when the DM hides exact numbers (D157). */
public enum HealthStatus {
    UNHARMED,
    INJURED,
    BLOODIED,
    DOWN;

    /**
     * Unharmed at full HP, Injured above half, Bloodied at half or less, Down at 0. Temporary HP
     * doesn't count. Null when either value is missing.
     */
    public static HealthStatus of(Integer hpCurrent, Integer hpMax) {
        if (hpCurrent == null || hpMax == null) {
            return null;
        }
        if (hpCurrent <= 0) {
            return DOWN;
        }
        if (hpCurrent >= hpMax) {
            return UNHARMED;
        }
        return hpCurrent * 2 > hpMax ? INJURED : BLOODIED;
    }
}
