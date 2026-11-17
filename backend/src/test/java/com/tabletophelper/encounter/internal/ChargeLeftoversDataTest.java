package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D241's leftovers as generate_monster_actions.py now reads them (D243): charges that hang on no attack become traits
 * the DM uses, the orcs' spears of Gruumsh, Vajra Safahr's Blackstaff and Sephek's sword are versatile with nothing
 * left for the DM to settle, the minotaur infiltrator's charge grows while enlarged, and the yochlol's and the animated
 * breath's damage types follow their forms.
 */
class ChargeLeftoversDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";

    private static final List<JsonNode> definitions = new ArrayList<>();

    @BeforeAll
    static void load() throws IOException {
        ObjectMapper mapper = JacksonDefaults.builder().build();
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            mapper.readTree(in).forEach(definitions::add);
        }
    }

    @AfterAll
    static void release() {
        definitions.clear();
    }

    private static MonsterTemplates templates(String monster, String source) {
        for (JsonNode definition : definitions) {
            if (monster.equals(definition.path("monsterName").asString(""))
                    && source.equals(definition.path("source").asString(""))) {
                return MonsterTemplates.of(definition);
            }
        }
        throw new AssertionError(monster + " has no definition");
    }

    private static MonsterTemplates.Action action(String monster, String source, String name) {
        return templates(monster, source).find("ACTION", name).orElseThrow();
    }

    private static List<String> dice(MonsterTemplates.Action action) {
        return action.effects().stream().filter(part -> "DAMAGE".equals(part.effectType()))
                .map(MonsterTemplates.Part::damageDice).toList();
    }

    private static List<String> types(MonsterTemplates.Action action) {
        return action.effects().stream().filter(part -> "DAMAGE".equals(part.effectType()))
                .map(MonsterTemplates.Part::damageType).toList();
    }

    @Test
    @DisplayName("Five charge traits on five monsters are saving throws the DM uses: the giffs' Headfirst Charge, the "
            + "equinal's and the ferrumach's Skewering Charge")
    void traitCharges() {
        TreeMap<String, String> usable = new TreeMap<>();
        for (JsonNode definition : definitions) {
            for (MonsterTemplates.Action trait : MonsterTemplates.of(definition).list("TRAIT")) {
                usable.put(definition.path("monsterName").asString() + " (" + definition.path("source").asString()
                        + "): " + trait.name(), trait.deliveryMethod() + " DC " + trait.saveDc() + " "
                        + trait.saveAbility() + " " + dice(trait) + " " + trait.maxTargetSize());
            }
        }
        assertEquals(5, usable.size(), usable::toString);
        assertEquals("SAVING_THROW DC 14 STR [2d6] Large", usable.get("Giff (MPMM): Headfirst Charge"));
        assertEquals("SAVING_THROW DC 14 STR [2d6] Large", usable.get("Giff (MTF): Headfirst Charge"));
        assertEquals("SAVING_THROW DC 16 STR [2d6] Large", usable.get("Giff Shock Trooper (BAM): Headfirst Charge"));
        assertEquals("SAVING_THROW DC 17 STR [4d6] null", usable.get("Equinal Guardinal (MPP): Headfirst Charge"));
        assertEquals("SAVING_THROW DC 16 STR [3d6] Large", usable.get("Ferrumach Rilmani (MPP): Skewering Charge"));

        MonsterTemplates.Action skewer = templates("Ferrumach Rilmani", "MPP").list("TRAIT").getFirst();
        MonsterTemplates.Part grapple = skewer.effects().getFirst();
        assertEquals("grappled", grapple.condition());
        assertEquals(18, grapple.escapeDc());
        assertFalse(skewer.halfOnSave());
        MonsterTemplates.Action headfirst = templates("Equinal Guardinal", "MPP").list("TRAIT").getFirst();
        assertEquals("prone", headfirst.effects().get(1).condition());
        assertTrue(headfirst.dmNote().startsWith("Used when the equinal moves at least 30 feet in a straight line"));
    }

    @Test
    @DisplayName("The orcs' spears keep Gruumsh's Fury with two hands: the war chief's 1d8 + 4 and 1d8, 2d8 + 4 as its "
            + "stat block says (MM), and the hand-written note of it goes")
    void gruumshSpears() {
        MonsterTemplates.Action chief = action("Orc War Chief", "MM", "Spear");
        assertTrue(chief.isVersatile());
        assertEquals(List.of("1d6 + 4", "1d8"), dice(chief.withGrip(false)));
        assertEquals(List.of("1d8 + 4", "1d8"), dice(chief.withGrip(true)));
        assertNull(chief.dmNote());
        MonsterTemplates.Action eye = action("Orc Eye of Gruumsh", "MM", "Spear");
        assertEquals(List.of("1d8 + 3", "1d8"), dice(eye.withGrip(true)));
        assertNull(eye.dmNote());
    }

    @Test
    @DisplayName("Vajra's Blackstaff and Sephek's Ice Longsword are versatile, with no choice left to the DM")
    void versatileNotes() {
        MonsterTemplates.Action staff = action("Vajra Safahr", "WDH", "Blackstaff");
        assertEquals("1d8 + 2 bludgeoning", MonsterEffects.twoHandedDamageOf(staff));
        assertFalse(staff.dmNote().contains("settles the choice"));
        assertTrue(staff.dmNote().contains("expend 1 of the staff's charges"));
        MonsterTemplates.Action sword = action("Sephek Kaltro", "IDRotF", "Ice Longsword");
        assertEquals("1d10 + 3 slashing", MonsterEffects.twoHandedDamageOf(sword));
        assertEquals(List.of("1d10 + 3", "2d4"), dice(sword.withGrip(true)));
        assertNull(sword.dmNote());
    }

    @Test
    @DisplayName("The minotaur infiltrator's charge deals 2d8 while enlarged and 1d8 otherwise (BMT)")
    void enlargedCharge() {
        MonsterTemplates.Action gore = action("Minotaur Infiltrator", "BMT", "Gore");
        assertEquals(List.of("1d8 + 4", "2d8 + 4", "1d8", "1d8"),
                dice(gore.withCharge(true).withForm("enlarged")));
        assertEquals(List.of("1d8 + 4", "1d8"), dice(gore.withCharge(true).withForm("usual")));
        assertEquals(List.of("1d8 + 4", "2d8 + 4"), dice(gore.withCharge(false).withForm("enlarged")));
        assertFalse(gore.charge().note().contains("enlarged"));
    }

    @Test
    @DisplayName("The yochlol bites for piercing in spider form (MM), and the animated breath's 2d10 is its form's type "
            + "(FTD)")
    void formTypes() {
        MonsterTemplates.Action slam = action("Yochlol", "MM", "Slam (Bite in Spider Form)");
        assertEquals(List.of("spider form"), slam.forms());
        assertEquals(List.of("bludgeoning", "piercing", "poison"), types(slam.withForm("spider form")));
        assertEquals(List.of("bludgeoning", "poison"), types(slam.withForm("usual")));
        MonsterTemplates.Action breath = action("Animated Breath", "FTD", "Slam");
        assertEquals(List.of("acid form", "cold form", "fire form", "lightning form", "poison form"), breath.forms());
        assertEquals(List.of("bludgeoning", "fire"), types(breath.withForm("fire form")));
        assertFalse(types(breath).contains("variable"));
        assertNull(breath.dmNote());
    }
}
