package com.tabletophelper.encounter.state;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * How a condition ends (D162). Conditions get CONCENTRATION, HP_REGAINED or MANUAL for now; the turn-based
 * rules come with the turn processing of R5 workstream E, and until then a condition with a duration still
 * expires after that many rounds.
 */
public record EndRule(@Schema(requiredMode = REQUIRED) Type type) {

    public static final EndRule MANUAL = new EndRule(Type.MANUAL);
    public static final EndRule CONCENTRATION = new EndRule(Type.CONCENTRATION);
    public static final EndRule HP_REGAINED = new EndRule(Type.HP_REGAINED);

    public enum Type {

        /** After a number of rounds. */
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
