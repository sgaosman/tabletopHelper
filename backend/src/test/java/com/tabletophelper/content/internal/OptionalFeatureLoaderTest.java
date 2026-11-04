package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.OptionalFeature;
import com.tabletophelper.reference.OptionalFeatureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptionalFeatureLoaderTest {

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("A Fighting Style listed for the Fighter, Ranger and Paladin is loaded once, and no natural key repeats")
    void eachStyleOnce() throws Exception {
        OptionalFeatureRepository repository = mock(OptionalFeatureRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.saveAll(anyIterable()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.<Collection<OptionalFeature>>getArgument(0)));
        LoadContext context = new LoadContext(new ObjectMapper());

        new OptionalFeatureLoader(repository, new ObjectMapper()).load(context);

        ArgumentCaptor<Collection<OptionalFeature>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).filteredOn(f -> f.getName().equals("Dueling") && "PHB".equals(f.getSource()))
                .singleElement().extracting(OptionalFeature::getFeatureType).isEqualTo("FightingStyle");
        assertThat(saved.getValue()).extracting(f -> f.getName() + "|" + f.getSource()).doesNotHaveDuplicates();
        assertThat(context.counts().get(0).duplicates()).isZero();
    }
}
