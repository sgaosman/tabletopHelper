package com.tabletophelper.rules.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The initiative order (D163): highest initiative first, ties to the higher DEX score, then the DM's order. The
 * PHB leaves ties to the DM and players (p.189); higher DEX is a common convention the DM can reorder. The Lair
 * takes a turn on initiative 20, losing ties (MM p.11), and dead monsters' turns are skipped. A creature that follows
 * another, as a Tasha's spirit takes its turn immediately after its summoner's (TCE p.109, D182), comes right after
 * its leader, followers by their sort order; with its leader gone from the order, it takes its own place by its stored
 * initiative.
 */
public final class TurnOrder {

    /** What the Lair's turn is called in the order. */
    public static final String LAIR_ACTIONS = "Lair Actions";

    /** The initiative count the Lair acts on. */
    public static final int LAIR_INITIATIVE = 20;

    /**
     * A creature in the order. initiative is null until rolled, dexterity is the DEX score, and sortOrder is the
     * DM's order among ties, lowest first. followsId names the creature it takes its turn right after, or is null.
     */
    public record Combatant(UUID id, Integer initiative, int dexterity, int sortOrder, boolean monster, boolean dead,
                            UUID followsId) {
        public Combatant {
            Objects.requireNonNull(id, "id");
        }

        /** A creature that follows nobody. */
        public Combatant(UUID id, Integer initiative, int dexterity, int sortOrder, boolean monster, boolean dead) {
            this(id, initiative, dexterity, sortOrder, monster, dead, null);
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

    /** The turn that came before, and whether going back to it returns to the previous round. */
    public record PreviousTurn(int index, boolean previousRound) {}

    // Combatants without initiative come last
    private static final Comparator<Combatant> INITIATIVE_ORDER = Comparator
            .comparing(Combatant::initiative, Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
            .thenComparing(Comparator.comparingInt(Combatant::dexterity).reversed())
            .thenComparingInt(Combatant::sortOrder);

    private TurnOrder() {}

    /** Every combatant's turn in order, with the Lair's when withLair is set. */
    public static List<Turn> order(Collection<Combatant> combatants, boolean withLair) {
        Set<UUID> present = combatants.stream().map(Combatant::id).collect(Collectors.toSet());
        Map<UUID, List<Combatant>> followers = new LinkedHashMap<>();
        List<Combatant> leading = new ArrayList<>();
        for (Combatant combatant : combatants) {
            UUID leader = combatant.followsId();
            if (leader != null && !leader.equals(combatant.id()) && present.contains(leader)) {
                followers.computeIfAbsent(leader, id -> new ArrayList<>()).add(combatant);
            } else {
                leading.add(combatant);
            }
        }
        List<Turn> turns = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Combatant combatant : leading.stream().sorted(INITIATIVE_ORDER).toList()) {
            addWithFollowers(turns, combatant, followers, seen);
        }
        // Followers that only follow each other take their own places
        for (Combatant combatant : combatants.stream().filter(c -> !seen.contains(c.id())).sorted(INITIATIVE_ORDER)
                .toList()) {
            addWithFollowers(turns, combatant, followers, seen);
        }
        Set<UUID> following = followers.values().stream().flatMap(List::stream).map(Combatant::id)
                .collect(Collectors.toSet());
        if (withLair) {
            turns.add(lairPosition(turns, following), Turn.LAIR);
        }
        return List.copyOf(turns);
    }

    // The combatant's turn, then its followers' by the DM's order, which keeps the order they were made in, each with its own followers after it
    private static void addWithFollowers(List<Turn> turns, Combatant combatant, Map<UUID, List<Combatant>> followers,
                                         Set<UUID> seen) {
        if (!seen.add(combatant.id())) {
            return;
        }
        turns.add(new Turn(combatant));
        for (Combatant follower : followers.getOrDefault(combatant.id(), List.of()).stream()
                .sorted(Comparator.comparingInt(Combatant::sortOrder)).toList()) {
            addWithFollowers(turns, follower, followers, seen);
        }
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

    /**
     * The turn before current, the index of the turn now taken or -1 when there is none, passing over skipped
     * turns. Going back past the start of the order returns to the previous round. Empty when nobody in the
     * order can act.
     */
    public static Optional<PreviousTurn> previous(List<Turn> order, int current) {
        if (current < -1 || current >= order.size()) {
            throw new IllegalArgumentException("Turn " + current + " isn't in an order of " + order.size());
        }
        int from = current == -1 ? order.size() : current;
        for (int step = 1; step <= order.size(); step++) {
            int index = Math.floorMod(from - step, order.size());
            if (!order.get(index).isSkipped()) {
                return Optional.of(new PreviousTurn(index, from - step < 0));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether two combatants tie on both initiative and DEX, so only the DM's order puts one before the other
     * (D163). Combatants without initiative never tie, and nor does a follower, whose place is its leader's.
     */
    public static boolean tied(Combatant a, Combatant b) {
        return a.followsId() == null && b.followsId() == null && a.initiative() != null && a.initiative().equals(b.initiative()) && a.dexterity() == b.dexterity();
    }

    // Everyone on 20 or more goes before the Lair, which loses ties; a follower stays right after its leader
    private static int lairPosition(List<Turn> turns, Set<UUID> following) {
        for (int i = 0; i < turns.size(); i++) {
            if (following.contains(turns.get(i).combatant().id())) {
                continue;
            }
            Integer initiative = turns.get(i).combatant().initiative();
            if (initiative == null || initiative < LAIR_INITIATIVE) return i;
        }
        return turns.size();
    }
}
