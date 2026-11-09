package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Exhaustion;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * The actions anyone can take in combat besides attacking and casting (PHB p.192–193, D174), and what they change.
 * <ul>
 *   <li>Dodge: until the start of the dodger's next turn, an attack roll against it has disadvantage if the
 *       attacker can see it, and it makes Dexterity saving throws with advantage. It loses the benefit while it is
 *       incapacitated or its speed drops to 0. The encounter records it as the {@link #DODGING} entry among the
 *       creature's conditions, which is not one of the conditions of appendix A: nothing makes a creature immune to
 *       it, and only these rules read it.</li>
 *   <li>Dash, Disengage and Hide can be taken as a bonus action with the Rogue's Cunning Action (PHB p.96), which
 *       some stat blocks also have; Disengage and Hide with a monster's Nimble Escape (the goblin's, MM p.166).</li>
 * </ul>
 */
public final class StandardActions {

    /** The condition entry a Dodge leaves on its creature until the start of its next turn. */
    public static final String DODGING = "dodging";

    /** The feature or trait that grants Dash, Disengage and Hide as a bonus action. */
    public static final String CUNNING_ACTION = "Cunning Action";

    /** The monster trait that grants Disengage and Hide as a bonus action. */
    public static final String NIMBLE_ESCAPE = "Nimble Escape";

    /** The standard actions of PHB p.192–193 other than Attack and Cast a Spell. */
    public enum Action {
        DASH("Dash"),
        DISENGAGE("Disengage"),
        DODGE("Dodge"),
        HELP("Help"),
        HIDE("Hide"),
        READY("Ready"),
        SEARCH("Search"),
        USE_OBJECT("Use an Object");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        /** The action as the PHB names it, such as "Use an Object". */
        public String label() {
            return label;
        }
    }

    private StandardActions() {}

    /**
     * Whether a creature's Dodge protects it now: it has the {@link #DODGING} entry and is neither incapacitated nor
     * held at speed 0 by being grappled or restrained, or by exhaustion level 5 or more (PHB p.192, p.291). A speed
     * brought to 0 any other way isn't known here, so the DM ends the Dodge by hand.
     *
     * @param conditions the names of the creature's conditions
     */
    public static boolean dodging(Collection<String> conditions) {
        if (conditions == null || !Conditions.has(conditions, DODGING)) {
            return false;
        }
        return !ActionEconomy.incapacitated(conditions)
                && !Conditions.has(conditions, Conditions.GRAPPLED)
                && !Conditions.has(conditions, Conditions.RESTRAINED)
                && !Exhaustion.speedZero(Exhaustion.level(conditions));
    }

    /**
     * What lets a creature take the action as a bonus action, from the names of its features or traits: Cunning
     * Action for Dash, Disengage or Hide, and Nimble Escape for Disengage or Hide. Empty when nothing does.
     */
    public static Optional<String> bonusActionGrant(Action action, Collection<String> featureNames) {
        boolean cunning = false;
        boolean nimble = false;
        for (String name : featureNames) {
            String feature = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            cunning |= feature.equals("cunning action");
            nimble |= feature.equals("nimble escape");
        }
        return switch (action) {
            case DASH -> cunning ? Optional.of(CUNNING_ACTION) : Optional.empty();
            case DISENGAGE, HIDE -> cunning ? Optional.of(CUNNING_ACTION)
                    : nimble ? Optional.of(NIMBLE_ESCAPE) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** Why the action can't be taken as a bonus action, for a creature that has nothing granting it. */
    public static String bonusActionRefusal(Action action) {
        return switch (action) {
            case DASH -> "Dash as a bonus action needs Cunning Action";
            case DISENGAGE, HIDE -> action.label() + " as a bonus action needs Cunning Action or Nimble Escape";
            default -> action.label() + " can't be taken as a bonus action";
        };
    }
}
