package com.tabletophelper.character.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * The details a character's owner sets, which no rule reads (D158). A field left out, or null, keeps its value, and an
 * empty text clears it; the name can't be cleared. The race and background are fixed at creation, classes change only
 * by levelling, and everything with a rules effect is the server's to work out or a DM Adjustment's. Each text has a
 * generous limit, so no request stores an unbounded one (D221); the notes are the sheet's journal and have the most.
 */
public record CharacterDetailsRequest(
        @Size(max = 200) String name,
        @Min(0) @Max(10_000_000) Integer experiencePoints,
        @Size(max = 50) String alignment,
        @Size(max = 10_000) String personalityTraits,
        @Size(max = 10_000) String ideals,
        @Size(max = 10_000) String bonds,
        @Size(max = 10_000) String flaws,
        @Size(max = 50_000) String notes,
        @Size(max = 500) String portraitUrl
) {
}
