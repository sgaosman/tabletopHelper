package com.tabletophelper.rules.combat;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The frightened condition (PHB p.290, D201): "A frightened creature has disadvantage on ability checks and attack rolls
 * while the source of its fear is within line of sight", and it can't willingly move closer to the source. There is no
 * map, so line of sight is worked out from what the encounter knows: the source counts as in sight unless
 * <ul>
 *   <li>the DM has marked it out of sight on the condition;</li>
 *   <li>the frightened creature is blinded and has no blindsight (PHB p.290, MM p.8);</li>
 *   <li>the source is no longer there to see: dead, gone from the encounter, banished or on another plane;</li>
 *   <li>the source is invisible and the frightened creature can't see invisible creatures, having no blindsight, no
 *       truesight and no See Invisibility while it can see (PHB p.291, MM p.8-9).</li>
 * </ul>
 * A fear with no recorded source, as one the DM added without naming it, counts as in sight unless the creature is
 * blinded or the DM has marked it out of sight. A fear whose source is an illusion the spell creates, rather than its
 * caster (Phantasmal Killer PHB p.265, Weird PHB p.288, Illusory Dragon XGE p.157), doesn't depend on the caster being
 * seen: only blindness and the DM's mark take it out of sight. Moving closer to the source is the DM's.
 */
public final class Fear {

    /** The condition's name as participants store it. */
    public static final String FRIGHTENED = "frightened";

    /** The spells whose fear is of an illusion the spell creates, not of its caster (D201). */
    private static final Set<String> ILLUSORY_SOURCES = Set.of("phantasmal killer", "weird", "illusory dragon");

    private Fear() {}

    /** Why the source of a fear is out of sight, or that it is in sight. */
    public enum Sight {
        IN_SIGHT("in sight"),
        MARKED_OUT_OF_SIGHT("marked out of sight by the DM"),
        BLINDED("the creature is blinded"),
        SOURCE_GONE("the source isn't there to see"),
        SOURCE_INVISIBLE("the source is invisible");

        private final String words;

        Sight(String words) {
            this.words = words;
        }

        /** The words the session pages and the log use, such as "the source is invisible". */
        public String words() {
            return words;
        }

        public boolean inSight() {
            return this == IN_SIGHT;
        }
    }

    /**
     * What the frightened creature knows of its fear's source.
     *
     * @param side    the source's side of a roll, its conditions and senses, with invisibility left out when it can't
     *                benefit from it (Faerie Fire, PHB p.239); null when no source is recorded or the source is an
     *                illusion
     * @param present the source is still there to be seen: in the encounter, not dead, not dismissed, not banished
     */
    public record Source(AttackRules.Side side, boolean present) {

        /** A fear with no creature to see, as one with no recorded source or an illusion's. */
        public static final Source NONE = new Source(null, true);
    }

    /** Whether the condition's name is frightened. */
    public static boolean isFrightened(String condition) {
        return condition != null && condition.trim().equalsIgnoreCase(FRIGHTENED);
    }

    /** Whether the spell's fear is of an illusion it creates rather than of its caster (D201). */
    public static boolean illusorySource(String spell) {
        return spell != null && ILLUSORY_SOURCES.contains(spell.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the source of the frightened creature's fear is within its line of sight, and why not when it isn't.
     *
     * @param frightened the frightened creature's side: its conditions and senses
     * @param source     its fear's source, {@link Source#NONE} for none recorded or an illusion
     * @param markedOut  the DM has marked the source out of sight on the condition
     */
    public static Sight sight(AttackRules.Side frightened, Source source, boolean markedOut) {
        Objects.requireNonNull(frightened, "frightened");
        source = source != null ? source : Source.NONE;
        if (markedOut) {
            return Sight.MARKED_OUT_OF_SIGHT;
        }
        if (frightened.has(Conditions.BLINDED) && !frightened.senses().blindsight()) {
            return Sight.BLINDED;
        }
        if (!source.present()) {
            return Sight.SOURCE_GONE;
        }
        if (source.side() != null && source.side().has(Conditions.INVISIBLE) && !frightened.perceivesInvisible()) {
            return Sight.SOURCE_INVISIBLE;
        }
        return Sight.IN_SIGHT;
    }
}
