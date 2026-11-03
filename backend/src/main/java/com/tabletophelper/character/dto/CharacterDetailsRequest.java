package com.tabletophelper.character.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * The details a character's owner sets, which no rule reads (D158). A field left out, or null, keeps its value, and an
 * empty text clears it; the name can't be cleared. The race and background are fixed at creation, classes change only
 * by levelling, and everything with a rules effect is the server's to work out or a DM Adjustment's.
 */
public record CharacterDetailsRequest(
        @Size(max = 200) String name,
        @Min(0) Integer experiencePoints,
        @Size(max = 50) String alignment,
        String personalityTraits,
        String ideals,
        String bonds,
        String flaws,
        String notes,
        @Size(max = 500) String portraitUrl
) {
}
