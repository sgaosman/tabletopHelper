package com.tabletophelper.rules.combat;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A spell's casting time as it costs a turn (PHB p.202, D174): an action, a bonus action, a reaction, or longer
 * than a turn. It reads {@code spells.casting_time} ("1 action", "1 bonus action", "1 reaction, which you take
 * when ...", "1 minute", "10 minutes", "8 hours") and the spell effect templates' {@code castingTime}
 * ({@code ACTION}, {@code BONUS_ACTION}, {@code REACTION}, {@code 1_MINUTE}, {@code 10_MINUTES}, {@code 1_HOUR}).
 */
public enum CastingTime {

    ACTION,
    BONUS_ACTION,
    REACTION,

    /** A minute or more: more than one turn's action. */
    LONGER;

    // "1 minute", "10 minutes", "8 hours", "24 hours", and the templates' "1_MINUTE", "10_MINUTES", "1_HOUR"
    private static final Pattern LONGER_TEXT = Pattern.compile("\\d+[ _](round|minute|hour|day)s?");

    /**
     * The casting time the text names, ignoring case and surrounding spaces. A reaction's trigger after the comma
     * is ignored.
     *
     * @throws IllegalArgumentException for a missing casting time or one it can't read
     */
    public static CastingTime of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A spell with no casting time");
        }
        String time = text.trim().toLowerCase(Locale.ROOT);
        if (time.equals("1 action") || time.equals("action")) {
            return ACTION;
        }
        if (time.equals("1 bonus action") || time.equals("bonus_action")) {
            return BONUS_ACTION;
        }
        if (time.equals("1 reaction") || time.startsWith("1 reaction,") || time.equals("reaction")) {
            return REACTION;
        }
        if (LONGER_TEXT.matcher(time).matches()) {
            return LONGER;
        }
        throw new IllegalArgumentException("An unknown casting time: " + text);
    }

    /** What casting it costs from a turn; nothing for a casting time longer than a turn. */
    public Optional<ActionCost> cost() {
        return switch (this) {
            case ACTION -> Optional.of(ActionCost.ACTION);
            case BONUS_ACTION -> Optional.of(ActionCost.BONUS_ACTION);
            case REACTION -> Optional.of(ActionCost.REACTION);
            case LONGER -> Optional.empty();
        };
    }
}
