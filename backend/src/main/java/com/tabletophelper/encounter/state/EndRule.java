package com.tabletophelper.encounter.state;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * How a condition ends (D162). The turn processing ends conditions at the turn boundaries their rules name.
 * Whatever its type, a condition with a duration also ends once that many rounds have passed, at the start of
 * its caster's turn, and one with a Repeat Save ends when its creature succeeds on it at the end of one of its
 * turns.
 *
 * @param type          the rule
 * @param participantId whose turn the rule counts: the caster for AFTER_ROUNDS, the named creature for
 *                      START_OF_TURN and END_OF_TURN; null for the creature with the condition, and for
 *                      every other type
 */
public record EndRule(@Schema(requiredMode = REQUIRED) Type type, UUID participantId) {

    public static final EndRule MANUAL = new EndRule(Type.MANUAL, null);
    public static final EndRule CONCENTRATION = new EndRule(Type.CONCENTRATION, null);
    public static final EndRule HP_REGAINED = new EndRule(Type.HP_REGAINED, null);
    public static final EndRule REPEAT_SAVE = new EndRule(Type.REPEAT_SAVE, null);

    /** Ends after its duration, at the start of the caster's turn; null for the creature with the condition. */
    public static EndRule afterRounds(UUID caster) {
        return new EndRule(Type.AFTER_ROUNDS, caster);
    }

    /** Ends at the start of the creature's next turn. */
    public static EndRule startOfTurn(UUID creature) {
        return new EndRule(Type.START_OF_TURN, creature);
    }

    /** Ends at the end of the creature's next turn. */
    public static EndRule endOfTurn(UUID creature) {
        return new EndRule(Type.END_OF_TURN, creature);
    }

    public enum Type {

        /** After a number of rounds, at the start of its caster's turn (D162). */
        AFTER_ROUNDS,

        /** At the start of a named creature's turn. */
        START_OF_TURN,

        /** At the end of a named creature's turn. */
        END_OF_TURN,

        /** When the Concentration Instance that created it ends. */
        CONCENTRATION,

        /** On a successful Repeat Save. */
        REPEAT_SAVE,

        /** When the creature regains hit points: the unconsciousness that dropping to 0 HP brings (D161). */
        HP_REGAINED,

        /** Only when removed by hand, which includes every condition from before R5 (D162). */
        MANUAL
    }
}
