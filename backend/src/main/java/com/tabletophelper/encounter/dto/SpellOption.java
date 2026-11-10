package com.tabletophelper.encounter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.rules.combat.CastingTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A spell a participant can cast, with each Spell Source that can pay for it (D164, D165). A spell whose
 * sources are all spent is still listed, with no levels left on any of them.
 *
 * @param name    the spell's name, as the spells table has it
 * @param level           its level, 0 for a cantrip
 * @param castingTime     what casting it costs from a turn (D174): an action, a bonus action, a reaction, or LONGER
 *                        for a minute or more, which a player can't cast in combat
 * @param reactionTrigger for a reaction spell, what it is cast in response to, as its casting time says ("which you
 *                        take when ..."); null for any other spell
 * @param sources         what can pay for it, in the order SLOT, PACT_SLOT, INNATE, MYSTIC_ARCANUM, NONE
 * @param unavailableReason why the caster can't cast it at all now, such as armour worn without proficiency (PHB
 *                        p.144), which casting refuses for the DM too; null when nothing stops it
 * @param metamagic       the Metamagic options the caster knows, each with its cost and whether it can change this
 *                        spell (PHB p.102, M18); left out for a caster who knows none
 * @param castOptions     the options a cast of the spell chooses between, such as "enlarge" and "reduce" (D184); left
 *                        out for a spell without them
 * @param duplicatesUpTo  the highest level of spell a cast of it may duplicate, naming that spell as its option, as
 *                        Wish's 8th (PHB p.288, D190); left out for a spell that duplicates none
 */
public record SpellOption(
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) int level,
        @Schema(requiredMode = REQUIRED) CastingTime castingTime,
        String reactionTrigger,
        @Schema(requiredMode = REQUIRED) List<SourceOption> sources,
        String unavailableReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<MetamagicOption> metamagic,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> castOptions,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer duplicatesUpTo
) {

    /** An option with no Metamagic. */
    public SpellOption(String name, int level, CastingTime castingTime, String reactionTrigger,
                       List<SourceOption> sources, String unavailableReason) {
        this(name, level, castingTime, reactionTrigger, sources, unavailableReason, null, null, null);
    }

    /** An option with no choice of its own to make. */
    public SpellOption(String name, int level, CastingTime castingTime, String reactionTrigger,
                       List<SourceOption> sources, String unavailableReason, List<MetamagicOption> metamagic) {
        this(name, level, castingTime, reactionTrigger, sources, unavailableReason, metamagic, null, null);
    }

    /** The option with the reason the caster can't cast it. */
    public SpellOption unavailable(String reason) {
        return new SpellOption(name, level, castingTime, reactionTrigger, sources, reason, metamagic, castOptions,
                duplicatesUpTo);
    }

    /** The option with the Metamagic the caster knows. */
    public SpellOption withMetamagic(List<MetamagicOption> options) {
        return new SpellOption(name, level, castingTime, reactionTrigger, sources, unavailableReason, options,
                castOptions, duplicatesUpTo);
    }

    /** The option with the choices a cast of the spell makes (D184); none leaves them out. */
    public SpellOption withCastOptions(List<String> choices) {
        return new SpellOption(name, level, castingTime, reactionTrigger, sources, unavailableReason, metamagic,
                choices == null || choices.isEmpty() ? null : List.copyOf(choices), duplicatesUpTo);
    }

    /** The option with the highest level of spell a cast of it may duplicate (D190); null for none. */
    public SpellOption withDuplicatesUpTo(Integer maxLevel) {
        return new SpellOption(name, level, castingTime, reactionTrigger, sources, unavailableReason, metamagic,
                castOptions, maxLevel);
    }

    /**
     * One Spell Source for the spell.
     *
     * @param levels   the levels it can be cast at with this source now, lowest first: each slot level with a
     *                 slot left for SLOT, the pact slots' level while one is left for PACT_SLOT, and the spell's
     *                 own level for INNATE and MYSTIC_ARCANUM while a use is left and for NONE. Empty when the source
     *                 is spent.
     * @param usesLeft the pact slots, innate uses or Mystic Arcanum use left; null for SLOT and NONE
     * @param usesMax  the pact slots, innate uses or Mystic Arcanum use there are; null for SLOT and NONE
     */
    public record SourceOption(
            @Schema(requiredMode = REQUIRED) SpellSource source,
            @Schema(requiredMode = REQUIRED) List<Integer> levels,
            Integer usesLeft,
            Integer usesMax
    ) {
    }
}
