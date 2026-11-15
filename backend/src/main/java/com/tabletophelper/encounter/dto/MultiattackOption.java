package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A monster's multiattack and the attacks it makes, each of which takes its own targets (D165). One the stat block
 * doesn't let the server read has no components and a manualReason: the DM resolves it by hand.
 *
 * @param name         the action's name, such as "Multiattack"
 * @param description  the stat block's wording, which says when the attacks are alternatives
 * @param components   its attacks in order, as componentTargetIds lists their targets; empty when it can't be read
 * @param manualReason why the DM resolves it by hand, or null
 */
public record MultiattackOption(
        @Schema(requiredMode = REQUIRED) String name,
        String description,
        @Schema(requiredMode = REQUIRED) List<ComponentOption> components,
        String manualReason
) {

    /**
     * One attack of a multiattack.
     *
     * @param name         the action it makes, such as "Claw"
     * @param automated    whether the server resolves it; otherwise it takes no targets and the DM resolves it
     * @param manualReason why the DM resolves it, or null
     * @param maxTargets   how many creatures it can take, one for most attacks; null for any number
     * @param damageOptions the damage types the monster chooses from for this attack, one of which
     *                      componentDamageTypes gives when it is made; empty for none (D207)
     */
    public record ComponentOption(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) boolean automated,
            String manualReason,
            Integer maxTargets,
            @Schema(requiredMode = REQUIRED) List<String> damageOptions
    ) {

        public ComponentOption {
            damageOptions = damageOptions != null ? List.copyOf(damageOptions) : List.of();
        }

        /** An attack without damage options. */
        public ComponentOption(String name, boolean automated, String manualReason, Integer maxTargets) {
            this(name, automated, manualReason, maxTargets, List.of());
        }
    }
}
