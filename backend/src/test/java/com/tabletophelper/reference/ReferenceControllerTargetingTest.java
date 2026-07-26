package com.tabletophelper.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReferenceControllerTargetingTest {

    @Mock private SpellRepository spellRepository;
    @Mock private RaceRepository raceRepository;
    @Mock private CharacterClassRepository characterClassRepository;
    @Mock private SubclassRepository subclassRepository;
    @Mock private BackgroundRepository backgroundRepository;
    @Mock private FeatRepository featRepository;
    @Mock private OptionalFeatureRepository optionalFeatureRepository;
    @Mock private ConditionRepository conditionRepository;
    @Mock private ItemRepository itemRepository;

    private ReferenceController controller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new ReferenceController(spellRepository, conditionRepository, itemRepository,
                raceRepository, characterClassRepository, subclassRepository,
                backgroundRepository, featRepository, optionalFeatureRepository, objectMapper);
    }

    @Test
    @DisplayName("Basic single-target spell returns maxTargets=1")
    void basicSingleTarget() {
        Spell spell = new Spell();
        spell.setName("Magic Missile");
        spell.setEffectTemplate("{\"spellLevel\":1,\"targetCount\":1,\"targetType\":\"SINGLE_TARGET\"}");
        when(spellRepository.findByNameIgnoreCase("Magic Missile")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("Magic Missile", 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("maxTargets"));
    }

    @Test
    @DisplayName("Self-only spell returns selfOnly=true")
    void selfOnlySpell() {
        Spell spell = new Spell();
        spell.setName("Shield");
        spell.setEffectTemplate("{\"spellLevel\":1,\"selfOnly\":true}");
        when(spellRepository.findByNameIgnoreCase("Shield")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("Shield", 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("selfOnly"));
        assertEquals(true, body.get("canTargetSelf"));
    }

    @Test
    @DisplayName("Upcast scaling increases maxTargets")
    void upcastScaling() {
        Spell spell = new Spell();
        spell.setName("Bless");
        spell.setEffectTemplate(
            "{\"spellLevel\":1,\"targetCount\":3,\"targetCountUpcastScaling\":{\"additionalTargetsPerLevel\":1},\"targetType\":\"SINGLE_TARGET\"}");
        when(spellRepository.findByNameIgnoreCase("Bless")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("Bless", 3);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(5, body.get("maxTargets")); // 3 base + 2 more from +2 levels above base
    }

    @Test
    @DisplayName("No targetCount (area spell) returns maxTargets=-1")
    void noTargetCount() {
        Spell spell = new Spell();
        spell.setName("Fireball");
        spell.setEffectTemplate("{\"spellLevel\":3,\"targetType\":\"AREA\"}");
        when(spellRepository.findByNameIgnoreCase("Fireball")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("Fireball", 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(-1, body.get("maxTargets"));
    }

    @Test
    @DisplayName("Spell not found returns 404")
    void spellNotFound() {
        when(spellRepository.findByNameIgnoreCase("NotASpell")).thenReturn(Optional.empty());

        var response = controller.getSpellTargeting("NotASpell", 0);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("Null effectTemplate returns 404")
    void nullEffectTemplate() {
        Spell spell = new Spell();
        spell.setName("SomeSpell");
        spell.setEffectTemplate(null);
        when(spellRepository.findByNameIgnoreCase("SomeSpell")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("SomeSpell", 0);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("Repeat effect flag is detected")
    void repeatEffectFlag() {
        Spell spell = new Spell();
        spell.setName("Call Lightning");
        spell.setEffectTemplate("{\"spellLevel\":3,\"targetCount\":1,\"repeatEffect\":{\"actionType\":\"ACTION\"}}");
        when(spellRepository.findByNameIgnoreCase("Call Lightning")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("Call Lightning", 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("hasRepeatEffect"));
    }
}
