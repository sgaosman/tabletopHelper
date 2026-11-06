package com.tabletophelper.encounter.dto;

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
 * @param sources         what can pay for it, in the order SLOT, PACT_SLOT, INNATE, NONE
 */
public record SpellOption(
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) int level,
        @Schema(requiredMode = REQUIRED) CastingTime castingTime,
        String reactionTrigger,
        @Schema(requiredMode = REQUIRED) List<SourceOption> sources
) {

    /**
     * One Spell Source for the spell.
     *
     * @param levels   the levels it can be cast at with this source now, lowest first: each slot level with a
     *                 slot left for SLOT, the pact slots' level while one is left for PACT_SLOT, and the spell's
     *                 own level for INNATE while a use is left and for NONE. Empty when the source is spent.
     * @param usesLeft the pact slots or innate uses left; null for SLOT and NONE
     * @param usesMax  the pact slots or innate uses there are; null for SLOT and NONE
     */
    public record SourceOption(
            @Schema(requiredMode = REQUIRED) SpellSource source,
            @Schema(requiredMode = REQUIRED) List<Integer> levels,
            Integer usesLeft,
            Integer usesMax
    ) {
    }
}
