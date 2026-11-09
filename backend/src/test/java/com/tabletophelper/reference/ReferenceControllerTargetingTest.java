package com.tabletophelper.reference;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.dto.RepeatCost;
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

import java.util.List;
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
        objectMapper = JacksonDefaults.mapper();
        controller = new ReferenceController(spellRepository, conditionRepository, itemRepository,
                raceRepository, characterClassRepository, subclassRepository,
                backgroundRepository, featRepository, optionalFeatureRepository, objectMapper,
                TestClassTables.load(), new Glossary(List.of()));
    }

    @Test
    @DisplayName("Basic single-target spell returns maxTargets=1")
    void basicSingleTarget() {
        Spell spell = new Spell();
        spell.setName("Magic Missile");
        spell.setEffectTemplate(SpellTemplate.parse("{\"spellLevel\":1,\"targetCount\":1,\"targetType\":\"SINGLE_TARGET\"}"));
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
        spell.setEffectTemplate(SpellTemplate.parse("{\"spellLevel\":1,\"selfOnly\":true}"));
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
        spell.setEffectTemplate(SpellTemplate.parse(
            "{\"spellLevel\":1,\"targetCount\":3,\"targetCountUpcastScaling\":{\"additionalTargetsPerLevel\":1},\"targetType\":\"SINGLE_TARGET\"}"));
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
        spell.setEffectTemplate(SpellTemplate.parse("{\"spellLevel\":3,\"targetType\":\"AREA\"}"));
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
    @DisplayName("An effect template that doesn't fit the template records is a 409 that names the spell, not a 404 (D155)")
    void unreadableEffectTemplate() {
        Spell spell = new Spell();
        spell.setName("Broken Spell");
        spell.setEffectTemplate(SpellTemplate.parse("{\"spellLevel\": {\"not\": \"a number\"}}"));
        when(spellRepository.findByNameIgnoreCase("Broken Spell")).thenReturn(Optional.of(spell));

        var thrown = assertThrows(ConflictException.class, () -> controller.getSpellTargeting("Broken Spell", 0));

        assertEquals("The targeting rules of Broken Spell can't be read", thrown.getMessage());
    }

    @Test
    @DisplayName("Repeat effect flag is detected")
    void repeatEffectFlag() {
        Spell spell = new Spell();
        spell.setName("Call Lightning");
        spell.setEffectTemplate(SpellTemplate.parse("{\"spellLevel\":3,\"targetCount\":1,\"repeatEffect\":{\"actionType\":\"ACTION\"}}"));
        when(spellRepository.findByNameIgnoreCase("Call Lightning")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("Call Lightning", 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        SpellTargetingResponse body = response.getBody();
        assertNotNull(body);
        assertTrue(body.hasRepeatEffect());
        assertNull(body.repeatActionType());
    }

    @Test
    @DisplayName("A repeat effect's cost comes from the template's repeatActionType (D174)")
    void repeatEffectCost() {
        Spell spell = new Spell();
        spell.setName("Spiritual Weapon");
        spell.setEffectTemplate(SpellTemplate.parse(
            "{\"spellLevel\":2,\"targetCount\":1,\"repeatEffect\":{},\"repeatActionType\":\"BONUS_ACTION\"}"));
        when(spellRepository.findByNameIgnoreCase("Spiritual Weapon")).thenReturn(Optional.of(spell));

        SpellTargetingResponse body = controller.getSpellTargeting("Spiritual Weapon", 2).getBody();

        assertNotNull(body);
        assertTrue(body.hasRepeatEffect());
        assertEquals(RepeatCost.BONUS_ACTION, body.repeatActionType());
    }

    @Test
    @DisplayName("A spell without a repeat effect has no repeat cost, whatever its template says")
    void noRepeatEffectNoCost() {
        Spell spell = new Spell();
        spell.setName("Bless");
        spell.setEffectTemplate(SpellTemplate.parse("{\"spellLevel\":1,\"repeatActionType\":\"ACTION\"}"));
        when(spellRepository.findByNameIgnoreCase("Bless")).thenReturn(Optional.of(spell));

        SpellTargetingResponse body = controller.getSpellTargeting("Bless", 1).getBody();

        assertNotNull(body);
        assertNull(body.repeatActionType());
    }

    @Test
    @DisplayName("The response has the seven JSON fields of the map it replaced, with targetType defaulted, and the repeat cost")
    void responseJsonMatchesTheFormerMap() throws Exception {
        Spell spell = new Spell();
        spell.setName("Bless");
        spell.setEffectTemplate(SpellTemplate.parse(
            "{\"spellLevel\":1,\"targetCount\":3,\"targetCountUpcastScaling\":{\"additionalTargetsPerLevel\":1}}"));
        when(spellRepository.findByNameIgnoreCase("Bless")).thenReturn(Optional.of(spell));

        var response = controller.getSpellTargeting("Bless", 2);

        var expected = objectMapper.readTree("""
                {"maxTargets": 4, "selfOnly": false, "canTargetSelf": false, "canTargetAllies": true,
                 "canTargetEnemies": true, "targetType": "SINGLE_TARGET", "hasRepeatEffect": false,
                 "repeatActionType": null}
                """);
        assertEquals(expected, objectMapper.valueToTree(response.getBody()));
    }
}
