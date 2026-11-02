package com.tabletophelper.rules.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The initiative order (D163): highest initiative first, ties to the higher DEX score, then the DM's order. The
 * PHB leaves ties to the DM and players (p.189); higher DEX is a common convention the DM can reorder. The Lair
 * takes a turn on initiative 20, losing ties (MM p.11), and dead monsters' turns are skipped.
 */
public final class TurnOrder {

    /** What the Lair's turn is called in the order. */
    public static final String LAIR_ACTIONS = "Lair Actions";

    /** The initiative count the Lair acts on. */
    public static final int LAIR_INITIATIVE = 20;

    /**
     * A creature in the order. initiative is null until rolled, dexterity is the DEX score, and sortOrder is the
     * DM's order among ties, lowest first.
     */
    public record Combatant(UUID id, Integer initiative, int dexterity, int sortOrder, boolean monster, boolean dead) {
        public Combatant {
            Objects.requireNonNull(id, "id");
        }
    }

    /** One turn in the order: a combatant's, or the Lair's, which has no combatant. */
    public record Turn(Combatant combatant) {

        /** The Lair's turn. */
        public static final Turn LAIR = new Turn(null);

        public boolean isLair() {
            return combatant == null;
        }

        /** Dead monsters' turns are skipped. A PC keeps its turn, dying or dead (D163). */
        public boolean isSkipped() {
            return combatant != null && combatant.monster() && combatant.dead();
        }
    }

    /** The turn that comes next, and whether reaching it starts a new round. */
    public record NextTurn(int index, boolean newRound) {}

    // Combatants without initiative come last
    private static final Comparator<Combatant> INITIATIVE_ORDER = Comparator
            .comparing(Combatant::initiative, Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
            .thenComparing(Comparator.comparingInt(Combatant::dexterity).reversed())
            .thenComparingInt(Combatant::sortOrder);

    private TurnOrder() {}

    /** Every combatant's turn in order, with the Lair's when withLair is set. */
    public static List<Turn> order(Collection<Combatant> combatants, boolean withLair) {
        List<Turn> turns = new ArrayList<>(combatants.stream().sorted(INITIATIVE_ORDER).map(Turn::new).toList());
        if (withLair) {
            turns.add(lairPosition(turns), Turn.LAIR);
        }
        return List.copyOf(turns);
    }

    /**
     * The turn after current, the index of the turn that just ended or -1 before the first, passing over skipped
     * turns. Going past the end of the order starts a new round. Empty when nobody in the order can act.
     */
    public static Optional<NextTurn> next(List<Turn> order, int current) {
        if (current < -1 || current >= order.size()) {
            throw new IllegalArgumentException("Turn " + current + " isn't in an order of " + order.size());
        }
        for (int step = 1; step <= order.size(); step++) {
            int index = (current + step) % order.size();
            if (!order.get(index).isSkipped()) {
                return Optional.of(new NextTurn(index, current + step >= order.size()));
            }
        }
        return Optional.empty();
    }

    // Everyone on 20 or more goes before the Lair, which loses ties
    private static int lairPosition(List<Turn> turns) {
        for (int i = 0; i < turns.size(); i++) {
            Integer initiative = turns.get(i).combatant().initiative();
            if (initiative == null || initiative < LAIR_INITIATIVE) return i;
        }
        return turns.size();
    }
}
