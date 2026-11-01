package com.tabletophelper.seeder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatSeederTest {

    @Mock FeatRepository featRepository;

    private FeatSeeder seeder() {
        return new FeatSeeder(featRepository, new ObjectMapper());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("First seed gives every feat an effects object, empty when the feat has no effect keys")
    void firstSeedGivesEveryFeatEffects() throws Exception {
        when(featRepository.findAllByOrderByNameAsc()).thenReturn(List.of());

        seeder().seed();

        ArgumentCaptor<List<Feat>> saved = ArgumentCaptor.forClass(List.class);
        verify(featRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).isNotEmpty().allSatisfy(f -> assertThat(f.getEffects()).isNotNull());
        Feat gwm = saved.getValue().stream()
                .filter(f -> f.getName().equals("Great Weapon Master")).findFirst().orElseThrow();
        assertThat(gwm.getEffects()).isEqualTo("{}");
    }

    @Test
    @DisplayName("Backfilling effects updates feats in place and never deletes them, so IDs stay stable (D169)")
    void backfillKeepsIds() throws Exception {
        UUID gwmId = UUID.randomUUID();
        UUID toughId = UUID.randomUUID();
        Feat gwm = Feat.builder().id(gwmId).name("Great Weapon Master").source("PHB").build();
        Feat tough = Feat.builder().id(toughId).name("Tough").source("PHB").build();
        when(featRepository.findAllByOrderByNameAsc()).thenReturn(new ArrayList<>(List.of(gwm, tough)));

        seeder().seed();

        verify(featRepository, never()).deleteAll();
        verify(featRepository, never()).deleteAll(anyList());
        verify(featRepository).saveAll(List.of(gwm, tough));
        assertThat(gwm.getId()).isEqualTo(gwmId);
        assertThat(gwm.getEffects()).isEqualTo("{}");
        assertThat(tough.getId()).isEqualTo(toughId);
        assertThat(tough.getEffects()).contains("\"hpPerLevel\":2");
    }

    @Test
    @DisplayName("Feats that already have effects are left alone")
    void skipsWhenAlreadySeeded() throws Exception {
        Feat lucky = Feat.builder().id(UUID.randomUUID()).name("Lucky").source("PHB").effects("{}").build();
        when(featRepository.findAllByOrderByNameAsc()).thenReturn(List.of(lucky));

        seeder().seed();

        verify(featRepository, never()).saveAll(anyList());
    }
}
