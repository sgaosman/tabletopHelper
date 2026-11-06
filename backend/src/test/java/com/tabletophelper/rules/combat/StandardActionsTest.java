package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.combat.StandardActions.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What grants a standard action as a bonus action (PHB p.96, MM p.166), and the refusals when nothing does. */
class StandardActionsTest {

    @ParameterizedTest(name = "{0} with [{1}] → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            DASH       | Cunning Action           | Cunning Action
            DISENGAGE  | Cunning Action           | Cunning Action
            HIDE       | cunning action           | Cunning Action
            DODGE      | Cunning Action           | -
            DASH       | Nimble Escape            | -
            DISENGAGE  | Nimble Escape            | Nimble Escape
            HIDE       | Nimble Escape            | Nimble Escape
            HELP       | Cunning Action;Nimble Escape | -
            DISENGAGE  | Sneak Attack             | -
            """)
    @DisplayName("Cunning Action grants Dash, Disengage and Hide; Nimble Escape Disengage and Hide")
    void grants(Action action, String features, String expected) {
        List<String> names = Arrays.asList(features.split(";"));
        assertEquals("-".equals(expected) ? Optional.empty() : Optional.of(expected),
                StandardActions.bonusActionGrant(action, names));
    }

    @Test
    @DisplayName("The refusal names what would grant it")
    void refusals() {
        assertEquals("Dash as a bonus action needs Cunning Action", StandardActions.bonusActionRefusal(Action.DASH));
        assertEquals("Hide as a bonus action needs Cunning Action or Nimble Escape",
                StandardActions.bonusActionRefusal(Action.HIDE));
        assertEquals("Use an Object can't be taken as a bonus action",
                StandardActions.bonusActionRefusal(Action.USE_OBJECT));
    }
}
