package com.tabletophelper.encounter.dto;

import com.tabletophelper.encounter.state.SpellConsent;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Whoever controls a creature whose trait keeps spells off it "unless it wishes to be" affected, as a rakshasa's
 * Limited Magic Immunity (MM p.257, D198), sets which spells it lets through: NEVER, HELPFUL or ALWAYS.
 */
public record SpellConsentRequest(
        @NotNull UUID participantId,
        @NotNull SpellConsent consent
) {
}
