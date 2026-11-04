package com.tabletophelper.content.internal;

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
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock FeatRepository featRepository;

    private LoadContext load() throws Exception {
        lenient().when(featRepository.saveAll(anyIterable()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.<Collection<Feat>>getArgument(0)));
        LoadContext context = new LoadContext(objectMapper);
        new FeatLoader(featRepository, objectMapper).load(context);
        return context;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("A first load gives every feat an effects object, empty when the feat has no effect keys, and Tough's from feat-effects.json")
    void firstLoadGivesEveryFeatEffects() throws Exception {
        when(featRepository.findAll()).thenReturn(List.of());

        load();

        ArgumentCaptor<Collection<Feat>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(featRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).isNotEmpty().allSatisfy(f -> assertThat(f.getEffects()).isNotNull());
        Feat gwm = saved.getValue().stream()
                .filter(f -> f.getName().equals("Great Weapon Master")).findFirst().orElseThrow();
        assertThat(gwm.getEffects()).isEqualTo("{}");
        Feat tough = saved.getValue().stream().filter(f -> f.getName().equals("Tough")).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(tough.getEffects()).path("hpPerLevel").asInt()).isEqualTo(2);
        Feat lucky = saved.getValue().stream().filter(f -> f.getName().equals("Lucky")).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(lucky.getEffects()).path("resource").toString())
                .isEqualTo("{\"name\":\"Luck Points\",\"maxUses\":3,\"resetOn\":\"longRest\"}");
    }

    @Test
    @DisplayName("Loading over stored feats updates them in place and never deletes them, so their IDs stay (D169)")
    void loadingUpdatesInPlaceAndKeepsIds() throws Exception {
        UUID gwmId = UUID.randomUUID();
        UUID toughId = UUID.randomUUID();
        Feat gwm = Feat.builder().id(gwmId).name("Great Weapon Master").source("PHB").build();
        Feat tough = Feat.builder().id(toughId).name("Tough").source("PHB").description("Old text").build();
        when(featRepository.findAll()).thenReturn(List.of(gwm, tough));

        LoadContext context = load();

        verify(featRepository, never()).deleteAll();
        verify(featRepository, never()).deleteAll(anyIterable());
        verify(featRepository, never()).delete(any());
        assertThat(gwm.getId()).isEqualTo(gwmId);
        assertThat(gwm.getEffects()).isEqualTo("{}");
        assertThat(tough.getId()).isEqualTo(toughId);
        assertThat(tough.getEffects()).contains("\"hpPerLevel\":2");
        assertThat(tough.getDescription()).isNotEqualTo("Old text");
        LoadContext.TableCounts counts = context.counts().get(0);
        assertThat(counts.changed()).isEqualTo(2);
        assertThat(counts.added()).isEqualTo(counts.rows() - 2);
    }

    @Test
    @DisplayName("A stored feat whose JSON differs from the data only in spacing and key order is unchanged")
    void sameJsonInOtherTextIsUnchanged() throws Exception {
        when(featRepository.findAll()).thenReturn(List.of());
        load();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Feat>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(featRepository).saveAll(saved.capture());
        Feat lucky = saved.getValue().stream().filter(f -> f.getName().equals("Lucky")).findFirst().orElseThrow();
        // As PostgreSQL's jsonb gives it back: spaces after separators, shorter keys first
        Feat stored = Feat.builder().id(UUID.randomUUID()).name(lucky.getName()).source(lucky.getSource())
                .prerequisite(lucky.getPrerequisite()).description(lucky.getDescription())
                .abilityScoreIncrease(lucky.getAbilityScoreIncrease()).grantsFeatures(lucky.getGrantsFeatures())
                .effects("{\"resource\": {\"name\": \"Luck Points\", \"resetOn\": \"longRest\", \"maxUses\": 3}}")
                .build();
        reset(featRepository);
        when(featRepository.findAll()).thenReturn(List.of(stored));

        LoadContext context = load();

        assertThat(context.counts().get(0).unchanged()).isEqualTo(1);
        assertThat(stored.getEffects())
                .isEqualTo("{\"resource\": {\"name\": \"Luck Points\", \"resetOn\": \"longRest\", \"maxUses\": 3}}");
    }
}
