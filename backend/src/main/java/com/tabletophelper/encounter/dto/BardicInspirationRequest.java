package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * What the holder of a Bardic Inspiration die does with it (PHB p.53, M17). An attack adds it through the attack's own
 * useInspiration; this is for saves and ability checks.
 *
 * @param participantId the creature holding the die
 * @param use           what it does with the die
 */
public record BardicInspirationRequest(
        @NotNull UUID participantId,
        @NotNull Use use
) {

    /** What the holder does with its die. */
    @Schema(name = "InspirationUse", enumAsRef = true)
    public enum Use {

        /**
         * Keep it for its next failed saving throw the server rolls: the die is rolled and added then, which turns the
         * failure into a success when it makes up the difference.
         */
        ON_FAILED_SAVE,

        /** Keep it for an attack roll, or to roll by hand: no longer rolled on a failed save. */
        KEEP,

        /** Roll it now and add it to an ability check or saving throw the DM is resolving; the die is spent. */
        ROLL
    }
}
