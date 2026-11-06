package com.tabletophelper.content.internal;

import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatEffects;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatLoaderTest {

    private final ObjectMapper objectMapper = JacksonDefaults.mapper();

    @Mock FeatRepository featRepository;

    private LoadContext load() {
        lenient().when(featRepository.saveAll(anyIterable()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.<Collection<Feat>>getArgument(0)));
        LoadContext context = new LoadContext(objectMapper);
        new FeatLoader(featRepository, objectMapper).load(context);
        return context;
    }

    @SuppressWarnings("unchecked")
    private List<Feat> firstLoad() {
        when(featRepository.findAll()).thenReturn(List.of());
        load();
        ArgumentCaptor<Collection<Feat>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(featRepository).saveAll(saved.capture());
        return List.copyOf(saved.getValue());
    }

    private static Feat named(List<Feat> feats, String name) {
        return feats.stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("A first load gives every feat an effects object, empty when the feat has no effect keys, Tough's from feat-effects.json, and repeatable to Elemental Adept only")
    void firstLoadGivesEveryFeatEffects() {
        List<Feat> feats = firstLoad();

        assertThat(feats).isNotEmpty().allSatisfy(f -> {
            assertThat(f.getEffects()).isNotNull();
            assertThat(f.getEffects().unreadable()).isNull();
        });
        assertThat(named(feats, "Great Weapon Master").getEffects().json().toString()).isEqualTo("{}");
        assertThat(named(feats, "Tough").getEffects().hpPerLevel()).isEqualTo(2);
        assertThat(feats).filteredOn(f -> f.getEffects().repeatable()).extracting(Feat::getName)
                .containsOnly("Elemental Adept");
        assertThat(named(feats, "Lucky").getEffects().resource())
                .isEqualTo(new FeatEffects.Resource("Luck Points", 3, "longRest"));
        assertThat(named(feats, "Lucky").getEffects().json().path("resource").toString())
                .isEqualTo("{\"name\":\"Luck Points\",\"maxUses\":3,\"resetOn\":\"longRest\"}");
    }

    @Test
    @DisplayName("Every feat's ability score increases and spells fit their records and keep the JSON written before typing")
    void increasesAndSpellsFitTheRecords() {
        List<Feat> feats = firstLoad();

        assertThat(feats).allSatisfy(f -> {
            if (f.getAbilityScoreIncrease() != null) assertThat(f.getAbilityScoreIncrease().unreadable()).isNull();
            if (f.getGrantsFeatures() != null) assertThat(f.getGrantsFeatures().unreadable()).isNull();
        });
        Feat resilient = named(feats, "Resilient");
        assertThat(resilient.getAbilityScoreIncrease().json().toString())
                .isEqualTo("[{\"choose\":{\"from\":[\"STR\",\"DEX\",\"CON\",\"INT\",\"WIS\",\"CHA\"],\"amount\":1}}]");
        assertThat(resilient.getAbilityScoreIncrease().entries().get(0).choose().amount()).isEqualTo(1);
        assertThat(named(feats, "Durable").getAbilityScoreIncrease().entries().get(0).fixed())
                .isEqualTo(Map.of("CON", 1));
        Feat feyTouched = named(feats, "Fey Touched");
        assertThat(feyTouched.getGrantsFeatures().options()).hasSize(1);
        assertThat(feyTouched.getGrantsFeatures().options().get(0).daily().get(1))
                .anySatisfy(spell -> assertThat(spell.spellName()).isEqualTo("misty step"))
                .anySatisfy(spell -> assertThat(spell.filter()).isEqualTo("level=1|school=E;D"));
    }

    @Test
    @DisplayName("Prerequisites load as alternatives of typed requirements: 62 feats, 68 alternatives, every 5etools key read")
    void prerequisitesLoadAsAlternatives() {
        List<Feat> feats = firstLoad();

        List<Feat> withPrerequisites = feats.stream().filter(f -> f.getPrerequisite() != null).toList();
        assertThat(withPrerequisites).hasSize(62).allSatisfy(f -> assertThat(f.getPrerequisite().unreadable()).isNull());
        assertThat(withPrerequisites.stream().mapToInt(f -> f.getPrerequisite().alternatives().size()).sum())
                .isEqualTo(68);
        assertThat(named(feats, "Grappler").getPrerequisite().json().toString())
                .isEqualTo("[{\"abilities\":[{\"ability\":\"STR\",\"minimum\":13}]}]");
        assertThat(named(feats, "Ritual Caster").getPrerequisite().json().toString())
                .isEqualTo("[{\"abilities\":[{\"ability\":\"INT\",\"minimum\":13},{\"ability\":\"WIS\",\"minimum\":13}]}]");
        assertThat(named(feats, "Strike of the Giants").getPrerequisite().json().toString())
                .isEqualTo("[{\"proficiencies\":[{\"kind\":\"WEAPON_GROUP\",\"name\":\"martial\"}]},"
                        + "{\"backgrounds\":[\"Giant Foundling\"]}]");
        assertThat(named(feats, "Ember of the Fire Giant").getPrerequisite().json().toString())
                .isEqualTo("[{\"feats\":[{\"name\":\"Strike of the Giants\",\"source\":\"BGG\","
                        + "\"display\":\"strike of the giants (fire strike)\"}],\"level\":{\"level\":4}}]");
        assertThat(named(feats, "Initiate of High Sorcery").getPrerequisite().json().get(0).toString())
                .isEqualTo("{\"level\":{\"level\":1,\"className\":\"Sorcerer\"},\"campaigns\":[\"Dragonlance\"]}");
        assertThat(named(feats, "Squat Nimbleness").getPrerequisite().json().toString())
                .isEqualTo("[{\"races\":[{\"name\":\"dwarf\"},{\"name\":\"small race\",\"display\":\"a Small race\"}]}]");
        assertThat(named(feats, "Eldritch Adept").getPrerequisite().json().toString())
                .isEqualTo("[{\"spellcastingFeature\":true}]");
        assertThat(named(feats, "War Caster").getPrerequisite().json().toString())
                .isEqualTo("[{\"spellcasting\":true}]");
        assertThat(named(feats, "Aberrant Dragonmark").getPrerequisite().json().toString())
                .isEqualTo("[{\"other\":\"No other dragonmark\"}]");
        assertThat(named(feats, "Alert").getPrerequisite()).isNull();
    }

    @Test
    @DisplayName("A prerequisite key the loader doesn't read fails the load, naming it (D155)")
    void unknownPrerequisiteKeyFails() throws Exception {
        FeatLoader loader = new FeatLoader(featRepository, objectMapper);
        var feat = objectMapper.readTree("{\"name\": \"Odd\", \"prerequisite\": [{\"psionics\": true}]}");
        assertThatThrownBy(() -> loader.extractPrerequisite(feat, Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown prerequisite psionics");
        var shape = objectMapper.readTree("{\"name\": \"Odd\", \"prerequisite\": [{\"ability\": [{\"str\": 13, \"dex\": 13}]}]}");
        assertThatThrownBy(() -> loader.extractPrerequisite(shape, Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("one ability");
        var feats = objectMapper.readTree("{\"name\": \"Odd\", \"prerequisite\": [{\"feat\": [\"no such feat|phb\"]}]}");
        assertThatThrownBy(() -> loader.extractPrerequisite(feats, Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("isn't in");
    }

    @Test
    @DisplayName("Loading over stored feats updates them in place and never deletes them, so their IDs stay (D169)")
    void loadingUpdatesInPlaceAndKeepsIds() {
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
        assertThat(gwm.getEffects().json().toString()).isEqualTo("{}");
        assertThat(tough.getId()).isEqualTo(toughId);
        assertThat(tough.getEffects().hpPerLevel()).isEqualTo(2);
        assertThat(tough.getDescription()).isNotEqualTo("Old text");
        LoadContext.TableCounts counts = context.counts().get(0);
        assertThat(counts.changed()).isEqualTo(2);
        assertThat(counts.added()).isEqualTo(counts.rows() - 2);
    }

    @Test
    @DisplayName("A stored feat whose JSON differs from the data only in spacing and key order is unchanged")
    void sameJsonInOtherTextIsUnchanged() {
        Feat lucky = named(firstLoad(), "Lucky");
        // As PostgreSQL's jsonb gives it back: shorter keys first
        FeatEffects storedEffects = FeatEffects.parse(
                "{\"resource\": {\"name\": \"Luck Points\", \"resetOn\": \"longRest\", \"maxUses\": 3}}");
        Feat stored = Feat.builder().id(UUID.randomUUID()).name(lucky.getName()).source(lucky.getSource())
                .prerequisite(lucky.getPrerequisite()).description(lucky.getDescription())
                .abilityScoreIncrease(lucky.getAbilityScoreIncrease()).grantsFeatures(lucky.getGrantsFeatures())
                .effects(storedEffects)
                .build();
        reset(featRepository);
        when(featRepository.findAll()).thenReturn(List.of(stored));

        LoadContext context = load();

        assertThat(context.counts().get(0).unchanged()).isEqualTo(1);
        assertThat(stored.getEffects()).isSameAs(storedEffects);
    }
}
