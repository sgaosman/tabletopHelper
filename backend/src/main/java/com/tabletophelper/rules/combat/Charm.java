package com.tabletophelper.rules.combat;

/**
 * The charmed condition (PHB p.290, D224): "A charmed creature can't attack the charmer or target the charmer with
 * harmful abilities or magical effects." The charmer is the creature the condition records as its source. A charm with
 * no recorded source limits nothing the app can check, and the charmer's advantage on ability checks to interact
 * socially with the creature is the DM's.
 */
public final class Charm {

    /** The condition's name as participants store it. */
    public static final String CHARMED = "charmed";

    private Charm() {}

    /** Whether the condition's name is charmed. */
    public static boolean isCharmed(String condition) {
        return condition != null && condition.trim().equalsIgnoreCase(CHARMED);
    }

    /**
     * Why the charmed creature can't do what it is trying to its charmer, such as "Aria is charmed by Vampire, so it
     * can't attack Vampire or target it with harmful abilities or magical effects (PHB p.290)".
     *
     * @param charmed the charmed creature's name
     * @param charmer the charmer's name
     */
    public static String refusal(String charmed, String charmer) {
        return charmed + " is charmed by " + charmer + ", so it can't attack " + charmer
                + " or target it with harmful abilities or magical effects (PHB p.290)";
    }
}
