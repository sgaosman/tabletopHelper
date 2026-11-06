package com.tabletophelper.character;

import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Removing a DM Adjustment (D158): a FEAT grant stops counting before its effects are reversed, so a spellcasting
 * ability it set passes to a feat still applied (D159), and removing one already removed is a 409.
 */
class DmAdjustmentServiceTest {

    private final CharacterCommands commands = mock(CharacterCommands.class);
    private final FeatEffectResolver featEffectResolver = mock(FeatEffectResolver.class);
    private final DmAdjustmentService service = new DmAdjustmentService(commands, mock(FeatRepository.class),
            featEffectResolver);

    private final UUID characterId = UUID.randomUUID();
    private final UUID dmId = UUID.randomUUID();

    @Test
    @DisplayName("Removing a FEAT grant marks it removed before reversing its effects, and removing it again is a 409 (ConflictException)")
    void featGrantRemovedBeforeReversal() {
        AppliedFeatEffects effects = new AppliedFeatEffects(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "Fey Touched", "WIS", true);
        DmAdjustment grant = new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.FEAT, "Fey Touched", null, null,
                UUID.randomUUID(), effects, "A boon", dmId, Instant.now(), null, null);
        PlayerCharacter character = PlayerCharacter.builder().id(characterId).name("Aria")
                .dmAdjustments(new ArrayList<>(List.of(grant))).build();
        when(commands.beginAsDm(characterId, dmId)).thenReturn(character);
        when(commands.finish(character)).thenReturn(mock(CharacterResponse.class));
        List<Boolean> activeWhenReversed = new ArrayList<>();
        doAnswer(inv -> {
            PlayerCharacter reversing = inv.getArgument(0);
            activeWhenReversed.add(reversing.getDmAdjustments().getFirst().active());
            return null;
        }).when(featEffectResolver).reverseFeatEffects(eq(character), any());

        service.remove(characterId, grant.id(), dmId);

        assertThat(activeWhenReversed).containsExactly(false);
        verify(featEffectResolver).reverseFeatEffects(character, effects);
        DmAdjustment removed = character.getDmAdjustments().getFirst();
        assertThat(removed.removedBy()).isEqualTo(dmId);
        assertThat(removed.removedAt()).isNotNull();

        assertThatThrownBy(() -> service.remove(characterId, grant.id(), dmId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("That DM Adjustment has already been removed");
        verify(featEffectResolver, times(1)).reverseFeatEffects(any(), any());
    }
}
