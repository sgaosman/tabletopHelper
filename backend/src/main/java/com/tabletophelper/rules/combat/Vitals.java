package com.tabletophelper.rules.combat;

import java.util.Objects;

/**
 * A creature's hit points and how close it is to death: what {@link DamageRules} works on. A player character
 * makes death saves at 0 HP; a monster dies there. A creature is Alive exactly when it has hit points left.
 */
public record Vitals(int hp, int maxHp, int tempHp, LifeState lifeState,
                     int deathSaveSuccesses, int deathSaveFailures, boolean playerCharacter) {

    public Vitals {
        Objects.requireNonNull(lifeState, "lifeState");
        if (hp < 0 || maxHp < 1 || tempHp < 0) {
            throw new IllegalArgumentException("Impossible hit points: " + hp + "/" + maxHp + ", " + tempHp + " temporary");
        }
        if (deathSaveSuccesses < 0 || deathSaveSuccesses > 3 || deathSaveFailures < 0 || deathSaveFailures > 3) {
            throw new IllegalArgumentException(
                    "Death saves run from 0 to 3: " + deathSaveSuccesses + " successes, " + deathSaveFailures + " failures");
        }
        if ((lifeState == LifeState.ALIVE) != (hp > 0)) {
            throw new IllegalArgumentException("A creature at " + hp + " HP can't be " + lifeState);
        }
        if (!playerCharacter && (lifeState == LifeState.DYING || lifeState == LifeState.STABLE)) {
            throw new IllegalArgumentException("Only a player character can be " + lifeState);
        }
    }
}
