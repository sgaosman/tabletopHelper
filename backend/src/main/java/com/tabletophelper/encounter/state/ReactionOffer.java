package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A reaction a creature may take in answer to something that has just happened in the fight (D216), stored in
 * {@code encounters.reaction_offers} (V39). The server never waits for an answer: the triggering command has already
 * resolved, and the offer only lists what the creature could do now and how. It lasts until it is answered or
 * dismissed, its reactor spends its reaction, leaves or dies, the turn changes, or its command is undone or taken back.
 * A Counterspell offer lasts only while its spell is the newest command, as only that can be countered (D196).
 *
 * @param id               the offer's own ID, which an answer or a dismissal names
 * @param commandId        the combat command that triggered it (M14), or null outside a recorded command
 * @param reactorId        the creature that may react
 * @param reactorName      its name
 * @param trigger          what happened to it
 * @param triggerActorId   the creature that attacked, damaged or cast; null for the DM by hand
 * @param triggerActorName that creature's name, or "DM"
 * @param attackTotal      the attack roll's total, for an attack that hit
 * @param armourClass      the reactor's AC the attack was rolled against
 * @param critical         the hit was a critical hit, which no AC turns into a miss
 * @param damageTaken      the damage the reactor took, after its defences; null for none
 * @param damageTypes      the types of that damage
 * @param spellName        the spell cast, for {@link Trigger#SPELL_CAST}
 * @param spellLevel       the level it was cast at
 * @param options          what the reactor can do, each with how to do it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReactionOffer(
        @Schema(requiredMode = REQUIRED) UUID id,
        UUID commandId,
        @Schema(requiredMode = REQUIRED) UUID reactorId,
        @Schema(requiredMode = REQUIRED) String reactorName,
        @Schema(requiredMode = REQUIRED) Trigger trigger,
        UUID triggerActorId,
        String triggerActorName,
        Integer attackTotal,
        Integer armourClass,
        @Schema(requiredMode = REQUIRED) boolean critical,
        Integer damageTaken,
        List<String> damageTypes,
        String spellName,
        Integer spellLevel,
        @Schema(requiredMode = REQUIRED) List<ReactionOption> options
) {

    /** What the offer answers. */
    public enum Trigger {
        /** The reactor was hit by an attack (and may have taken damage from it). */
        ATTACK_HIT,
        /** The reactor took damage from something other than an attack roll. */
        DAMAGE,
        /** Another creature cast a spell. */
        SPELL_CAST
    }

    /** How an option is taken, which names the existing command that takes it. */
    public enum Kind {
        /** POST /combat/cast-spell with the option's spell, source, level, targets and option. */
        SPELL,
        /** POST /combat/use-pool with the option's pool and amount. */
        POOL,
        /** POST /combat/monster-action with the option's action name as a REACTION. */
        MONSTER_REACTION
    }

    /**
     * One reaction the reactor can take.
     *
     * @param kind        which command takes it
     * @param name        what it is called, such as "Shield" or "Absorb Elements (fire)"
     * @param spellName   for SPELL, the spell
     * @param spellSource for SPELL, what pays for it
     * @param slotLevels  for SPELL, the levels it can be cast at from that source, lowest first
     * @param castOption  for SPELL, the cast's option, such as Absorb Elements' damage type
     * @param targetIds   for SPELL, the cast's targets: the reactor for Shield and Absorb Elements, the attacker for
     *                    Hellish Rebuke, the caster for Counterspell
     * @param poolId      for POOL, the pool
     * @param amount      for POOL, the amount the feature reads, such as the damage Stone's Endurance reduces
     * @param actionName  for MONSTER_REACTION, the stat block's reaction
     * @param resolves    the app applies its result to the triggering event; false when the DM adjusts by hand
     * @param note        what taking it does here
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReactionOption(
            @Schema(requiredMode = REQUIRED) Kind kind,
            @Schema(requiredMode = REQUIRED) String name,
            String spellName,
            SpellSource spellSource,
            List<Integer> slotLevels,
            String castOption,
            List<UUID> targetIds,
            String poolId,
            Integer amount,
            String actionName,
            @Schema(requiredMode = REQUIRED) boolean resolves,
            String note
    ) {

        /** A spell cast in reaction. */
        public static ReactionOption spell(String name, String spellName, SpellSource source, List<Integer> levels,
                                   String castOption, List<UUID> targetIds, boolean resolves, String note) {
            return new ReactionOption(Kind.SPELL, name, spellName, source, List.copyOf(levels), castOption,
                    List.copyOf(targetIds), null, null, null, resolves, note);
        }

        /** A pool used as a reaction. */
        public static ReactionOption pool(String name, String poolId, Integer amount, boolean resolves, String note) {
            return new ReactionOption(Kind.POOL, name, null, null, null, null, null, poolId, amount, null, resolves, note);
        }

        /** A monster's stat-block reaction. */
        public static ReactionOption monsterReaction(String actionName, boolean resolves, String note) {
            return new ReactionOption(Kind.MONSTER_REACTION, actionName, null, null, null, null, null, null, null, actionName,
                    resolves, note);
        }
    }

    /**
     * The offer as the players see it: without the command's ID, as their log keeps none (M14), and with the creature
     * that triggered it named as they may know it.
     */
    public ReactionOffer forPlayers(boolean actorHidden) {
        if (!actorHidden) {
            return new ReactionOffer(id, null, reactorId, reactorName, trigger, triggerActorId, triggerActorName,
                    attackTotal, armourClass, critical, damageTaken, damageTypes, spellName, spellLevel, options);
        }
        // An option aimed at a hidden creature would be refused for a player (D157), so it goes too
        List<ReactionOption> kept = options.stream()
                .filter(option -> option.targetIds() == null || !option.targetIds().contains(triggerActorId))
                .toList();
        return new ReactionOffer(id, null, reactorId, reactorName, trigger, null, "A hidden creature", attackTotal,
                armourClass, critical, damageTaken, damageTypes, spellName, spellLevel, kept);
    }

    // ── The offers on an encounter ──────────────────────────────

    /** The offers without those the predicate picks. */
    public static List<ReactionOffer> without(List<ReactionOffer> offers, Predicate<ReactionOffer> removed) {
        List<ReactionOffer> kept = new ArrayList<>();
        for (ReactionOffer offer : offers != null ? offers : List.<ReactionOffer>of()) {
            if (offer != null && !removed.test(offer)) {
                kept.add(offer);
            }
        }
        return kept;
    }

    /** The offers without the reactor's: it has used its reaction. */
    public static List<ReactionOffer> withoutReactor(List<ReactionOffer> offers, UUID reactorId) {
        return without(offers, offer -> Objects.equals(offer.reactorId(), reactorId));
    }

    /** The offers without those the command triggered: it has been taken back. */
    public static List<ReactionOffer> withoutCommand(List<ReactionOffer> offers, UUID commandId) {
        return without(offers, offer -> commandId != null && commandId.equals(offer.commandId()));
    }
}
