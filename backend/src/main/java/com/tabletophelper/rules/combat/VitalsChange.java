package com.tabletophelper.rules.combat;

/**
 * What one hit point event leaves: the new vitals, and what the EffectApplier does alongside them.
 *
 * @param droppedToZero               the creature went from above 0 HP to 0
 * @param died                        the creature is now dead and wasn't before
 * @param massiveDamage               it died outright from massive damage
 * @param concentrationEnds           it fell unconscious or died, so its concentration ends
 * @param addUnconsciousAndProne      a PC dropped to 0: it falls unconscious and prone
 * @param removeUnconsciousFromDowned a PC regained hit points: the unconscious condition that dropping to 0
 *                                    added ends, and it stays prone
 */
public record VitalsChange(Vitals vitals, boolean droppedToZero, boolean died, boolean massiveDamage,
                           boolean concentrationEnds, boolean addUnconsciousAndProne,
                           boolean removeUnconsciousFromDowned) {

    /** New vitals, or the same ones, with nothing else to do. */
    static VitalsChange only(Vitals vitals) {
        return new VitalsChange(vitals, false, false, false, false, false, false);
    }
}
