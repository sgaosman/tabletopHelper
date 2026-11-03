package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A feat's limited uses, such as Lucky's luck points, as the level history records them in the feat's
 * {@link AppliedFeatEffects} (D168). The sheet keeps the uses themselves in a {@code feat:<feat>} resource pool that
 * CharacterStats builds from this record (D158), so they are spent, reset and taken into encounters as any other
 * pool; the deprecated {@code player_characters.feat_resources} column that held them is no longer mapped.
 *
 * @param currentUses the uses when the feat was taken, a record only: the pool holds the uses left
 * @param resetOn     the rest that brings them back, "shortRest" or "longRest"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeatResourceEntry(
        String featName,
        String name,
        @Schema(requiredMode = REQUIRED) int maxUses,
        @Schema(requiredMode = REQUIRED) int currentUses,
        String resetOn
) {
}
