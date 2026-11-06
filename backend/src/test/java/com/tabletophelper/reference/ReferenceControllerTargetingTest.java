package com.tabletophelper.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.dto.SpellTargetingResponse;
import com.tabletophelper.rules.character.TestClassTables;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

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
                backgroundRepository, featRepository, optionalFeatureRepository, objectMapper,
                TestClassTables.load());
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
        SpellTargetingResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.maxTargets());
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
        SpellTargetingResponse body = response.getBody();
        assertNotNull(body);
        assertTrue(body.selfOnly());
        assertTrue(body.canTargetSelf());
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
        SpellTargetingResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(5, body.maxTargets()); // 3 base + 2 more from +2 levels above base
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
        SpellTargetingResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(-1, body.maxTargets());
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
    @DisplayName("An effect template that isn't JSON is a 409 that names the spell, not a 404 (D155)")
    void unreadableEffectTemplate() {
        Spell spell = new Spell();
        spell.setName("Broken Spell");
        spell.setEffectTemplate("{not json");
        when(spellRepository.findByNameIgnoreCase("Broken Spell")).thenReturn(Optional.of(spell));

        var thrown = assertThrows(ConflictException.class, () -> controller.getSpellTargeting("Broken Spell", 0));

        assertEquals("The targeting rules of Broken Spell can't be read", thrown.getMessage());
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
        SpellTargetingResponse body = response.getBody();
        assertNotNull(body);
        assertTrue(body.hasRepeatEffect());
    }

    @Test
    @DisplayName("The response has the same seven JSON fields as the map it replaced, with targetType defaulted")
    void responseJsonMatchesTheFormerMap() throws Exception {
        Spell spell = new Spell();
        spell.setName("Bless");
        spell.setEffectTemplate(
            "{\"spellLevel\":1,\"targetCount\":3,\"targetCountUpcastScaling\":{\"additionalTargetsPerLevel\":1}}");
        when(spellRepository.findByNameIgnoreCase("Bless")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("Bless", 2);

        var expected = objectMapper.readTree("""
                {"maxTargets": 4, "selfOnly": false, "canTargetSelf": false, "canTargetAllies": true,
                 "canTargetEnemies": true, "targetType": "SINGLE_TARGET", "hasRepeatEffect": false}
                """);
        assertEquals(expected, objectMapper.valueToTree(response.getBody()));
    }
}
