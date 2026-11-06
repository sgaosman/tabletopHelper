package com.tabletophelper.rules.combat;

import java.util.Objects;

/**
 * What a command charges a creature's turn (D174): its cost, whether it is an attack, whether it is a spell and of
 * what kind, for the bonus-action-spell rule (PHB p.202), and whether it happens on the creature's own turn.
 *
 * @param cost             what it costs
 * @param attack           an attack roll: with {@link ActionCost#ACTION} it opens or continues the Attack action;
 *                         with {@link ActionCost#BONUS_ACTION} it is an off-hand attack, which needs the Attack
 *                         action first (PHB p.195); with {@link ActionCost#REACTION} an opportunity attack
 * @param spell            the kind of spell it casts, or {@link SpellKind#NONE}
 * @param ownTurn          it happens on the creature's own turn
 * @param attacksPerAction the attacks one Attack action gives the creature, for an attack that opens one
 */
public record Charge(ActionCost cost, boolean attack, SpellKind spell, boolean ownTurn, int attacksPerAction) {

    /** Whether a spell is a cantrip, for the bonus-action-spell rule. */
    public enum SpellKind {
        NONE,
        CANTRIP,
        LEVELLED
    }

    public Charge {
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(spell, "spell");
        if (attacksPerAction < 1) {
            throw new IllegalArgumentException("An Attack action gives at least one attack: " + attacksPerAction);
        }
    }

    /** A cost that is neither an attack nor a spell, such as a monster's action or a pool's use. */
    public static Charge of(ActionCost cost, boolean ownTurn) {
        return new Charge(cost, false, SpellKind.NONE, ownTurn, 1);
    }

    /** An attack on the creature's own turn, opening the Attack action or continuing it. */
    public static Charge attack(int attacksPerAction) {
        return new Charge(ActionCost.ACTION, true, SpellKind.NONE, true, attacksPerAction);
    }

    /** An attack made with something used in place of one attack of the Attack action. */
    public static Charge replaceAttack(int attacksPerAction) {
        return new Charge(ActionCost.REPLACE_ATTACK, false, SpellKind.NONE, true, attacksPerAction);
    }

    /** An off-hand attack (two-weapon fighting, PHB p.195): a bonus action after the Attack action. */
    public static Charge offHandAttack() {
        return new Charge(ActionCost.BONUS_ACTION, true, SpellKind.NONE, true, 1);
    }

    /** An opportunity attack, or any attack made as a reaction (PHB p.195). */
    public static Charge reactionAttack(boolean ownTurn) {
        return new Charge(ActionCost.REACTION, true, SpellKind.NONE, ownTurn, 1);
    }

    /** Casting a spell whose casting time costs {@code cost}. */
    public static Charge spell(ActionCost cost, SpellKind kind, boolean ownTurn) {
        return new Charge(cost, false, kind, ownTurn, 1);
    }
}
