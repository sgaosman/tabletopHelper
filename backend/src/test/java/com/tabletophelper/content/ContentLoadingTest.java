package com.tabletophelper.content;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.content.internal.ContentLoader;
import com.tabletophelper.content.internal.ContentVersion;
import com.tabletophelper.content.internal.ContentVersionRepository;
import com.tabletophelper.content.internal.LoadContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one catch per loader in {@link ContentLoading} (D155): a loader that fails rolls back and keeps its old hash,
 * the loaders that depend on it are skipped, {@code fail-on-error} stops the load instead, and each loader's skip
 * rules are counted in its outcome. The loaders are stand-ins under the real names and dependencies.
 */
class ContentLoadingTest {

    private static final Map<String, List<String>> DEPENDENCIES = Map.of(
            "spell-effects", List.of("spells"), "races", List.of("spells"),
            "resource-pools", List.of("classes"), "monster-actions", List.of("monsters"));

    private final ContentVersionRepository versions = mock(ContentVersionRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final List<String> ran = new ArrayList<>();

    ContentLoadingTest() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(versions.findAll()).thenReturn(List.of());
    }

    private record StandIn(String name, List<String> dependsOn, Consumer<LoadContext> body) implements ContentLoader {
        @Override
        public List<String> files() {
            return List.of("data/content/feat-effects.json");
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public void load(LoadContext context) {
            body.accept(context);
        }
    }

    // Every loader of ContentLoading.ORDER, each recording that it ran; the given ones do what their body says
    private ContentLoading loading(boolean failOnError, Map<String, Consumer<LoadContext>> bodies) {
        List<ContentLoader> loaders = new ArrayList<>();
        for (String name : ContentLoading.ORDER) {
            Consumer<LoadContext> body = bodies.getOrDefault(name, context -> { });
            loaders.add(new StandIn(name, DEPENDENCIES.getOrDefault(name, List.of()), context -> {
                ran.add(name);
                body.accept(context);
            }));
        }
        return new ContentLoading(loaders, versions, mock(EntityManager.class), JacksonDefaults.mapper(),
                transactionManager, failOnError);
    }

    private static Map<String, ContentLoading.Status> statuses(List<ContentLoading.Outcome> outcomes) {
        Map<String, ContentLoading.Status> statuses = new LinkedHashMap<>();
        outcomes.forEach(outcome -> statuses.put(outcome.loader(), outcome.status()));
        return statuses;
    }

    private static Consumer<LoadContext> failing() {
        return context -> LoadContext.reading("spells-phb.json: spell Fireball|PHB", () -> {
            throw new IllegalArgumentException("no level");
        });
    }

    @Test
    @DisplayName("A loader that fails is rolled back and stores no hash; the loaders that depend on it are skipped, and the rest load")
    void aFailureSkipsItsDependents() {
        List<ContentLoading.Outcome> outcomes = loading(false, Map.of("spells", failing())).load();

        assertThat(statuses(outcomes)).containsEntry("spells", ContentLoading.Status.FAILED)
                .containsEntry("spell-effects", ContentLoading.Status.SKIPPED)
                .containsEntry("races", ContentLoading.Status.SKIPPED)
                .containsEntry("conditions", ContentLoading.Status.LOADED)
                .containsEntry("monster-actions", ContentLoading.Status.LOADED);
        assertThat(ran).doesNotContain("spell-effects", "races");
        assertThat(outcomes.get(1).detail()).isEqualTo("spells-phb.json: spell Fireball|PHB: no level");
        verify(transactionManager).rollback(any());
        verify(transactionManager, times(9)).commit(any());
        verify(versions, times(9)).save(any(ContentVersion.class));
    }

    @Test
    @DisplayName("With fail-on-error the first loader that fails stops the load, after its rollback, and no later loader runs")
    void failOnErrorThrows() {
        ContentLoading loading = loading(true, Map.of("items", failing()));

        assertThatThrownBy(loading::load).isInstanceOf(ContentLoading.ContentLoadFailedException.class)
                .hasMessageContaining("items failed").hasMessageContaining("spell Fireball|PHB: no level");

        assertThat(ran).containsExactly("conditions", "spells", "spell-effects", "items");
        verify(transactionManager).rollback(any());
        verify(versions, times(3)).save(any(ContentVersion.class));
    }

    @Test
    @DisplayName("load(false) logs a failure and carries on even where fail-on-error is set, as preview-content runs it")
    void loadWithoutFailOnError() {
        List<ContentLoading.Outcome> outcomes = loading(true, Map.of("monsters", failing())).load(false);

        assertThat(statuses(outcomes)).containsEntry("monsters", ContentLoading.Status.FAILED)
                .containsEntry("monster-actions", ContentLoading.Status.SKIPPED);
    }

    @Test
    @DisplayName("Each loader's outcome counts its skip rules by name, and its summary line lists them")
    void skipRulesAreCounted() {
        List<ContentLoading.Outcome> outcomes = loading(false, Map.of("races", context -> {
            context.skip("_copy subraces");
            context.skip("subraces named ?");
            context.skip("_copy subraces");
        })).load();

        ContentLoading.Outcome races = outcomes.get(ContentLoading.ORDER.indexOf("races"));
        assertThat(races.skipped()).containsExactly(Map.entry("_copy subraces", 2), Map.entry("subraces named ?", 1));
        assertThat(races.toString()).endsWith("; skipped: _copy subraces 2, subraces named ? 1");
        assertThat(outcomes.get(0).skipped()).isEmpty();
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    @DisplayName("The prod profile sets content.fail-on-error, and the default profile leaves it off")
    void prodProfileFailsOnError() {
        assertThat(yaml("application-prod.yml").getProperty("content.fail-on-error")).isEqualTo("true");
        assertThat(yaml("application.yml").getProperty("content.fail-on-error")).isNull();
    }

    private static Properties yaml(String file) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(file));
        return factory.getObject();
    }
}
