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
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped charges, versatile weapons and form-dependent damage (D239), as generate_monster_actions.py reads them
 * from the stat blocks: a charge trait ("Charge", "Pounce", "Trampling Charge") or a hit's "If the X moved at least N
 * feet straight toward the target immediately before the hit", "or N (dice) damage if used with two hands", and "1
 * piercing damage in raven form, or 4 (1d4 + 2) piercing damage in hybrid form".
 */
class ChargeGripFormDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final String[] LISTS = {"actions", "bonusActions", "reactions", "legendaryActions",
            "mythicActions", "lairActions"};

    private static final List<JsonNode> definitions = new ArrayList<>();

    @BeforeAll
    static void load() throws IOException {
        ObjectMapper mapper = JacksonDefaults.builder().build();
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            mapper.readTree(in).forEach(definitions::add);
        }
    }

    // The definitions are large; the class lets them go once done, as the other data tests do
    @AfterAll
    static void release() {
        definitions.clear();
    }

    private static MonsterTemplates.Action action(String monster, String source, String name) {
        for (JsonNode definition : definitions) {
            if (monster.equals(definition.path("monsterName").asString(""))
                    && source.equals(definition.path("source").asString(""))) {
                return MonsterTemplates.of(definition).find("ACTION", name).orElseThrow();
            }
        }
        throw new AssertionError(monster + " has no definition");
    }

    @Test
    @DisplayName("Eighty-one attacks on eighty-one monsters carry a charge, and every part that waits for a charge is "
            + "on one of them")
    void charges() {
        Map<String, Integer> feet = new TreeMap<>();
        List<String> orphans = new ArrayList<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    String what = definition.path("monsterName").asString() + " ("
                            + definition.path("source").asString() + "): " + action.path("name").asString();
                    boolean gated = false;
                    for (JsonNode part : action.path("effects")) {
                        gated |= part.path("onlyIf").path("attackerCharged").asBoolean(false);
                    }
                    if (action.has("charge")) {
                        feet.put(what, action.path("charge").path("feet").asInt());
                        assertTrue(gated, what + " has a charge but nothing waits for it");
                    } else if (gated) {
                        orphans.add(what);
                    }
                }
            }
        }
        assertEquals(81, feet.size(), feet::toString);
        assertEquals(List.of(), orphans);
        assertEquals(30, feet.get("Centaur (MM): Pike"));
        assertEquals(10, feet.get("Minotaur (MM): Gore"));
    }

    @Test
    @DisplayName("The aurochs's hand-built 2d8 and prone, once dealt on every hit, wait for its charge (MPMM)")
    void aurochs() {
        MonsterTemplates.Action gore = action("Aurochs", "MPMM", "Gore");
        assertEquals(List.of("2d8 + 5"), gore.withCharge(false).effects().stream()
                .map(MonsterTemplates.Part::damageDice).toList());
        assertEquals(java.util.Arrays.asList("2d8 + 5", "2d8", null), gore.withCharge(true).effects().stream()
                .map(MonsterTemplates.Part::damageDice).toList());
        MonsterTemplates.Part prone = gore.withCharge(true).effects().get(2);
        assertEquals("prone", prone.condition());
        assertEquals(15, prone.saveDc());
        assertEquals("STR", prone.saveAbility());
    }

    @Test
    @DisplayName("The centaur's Charge trait adds 3d6 piercing to its Pike only (MM p.38), the lion's Pounce a prone "
            + "save to its Claw with the bite left to the DM (MM p.331)")
    void traits() {
        MonsterTemplates.Action pike = action("Centaur", "MM", "Pike");
        assertEquals(List.of("1d10 + 4", "3d6"), pike.withCharge(true).effects().stream()
                .map(MonsterTemplates.Part::damageDice).toList());
        assertFalse(action("Centaur", "MM", "Hoof").hasCharge());
        MonsterTemplates.Action claw = action("Lion", "MM", "Claw");
        assertEquals("Pounce", claw.charge().trait());
        assertTrue(claw.charge().note().contains("bite attack against it as a bonus action"));
        assertEquals(13, claw.withCharge(true).effects().get(1).saveDc());
    }

    @Test
    @DisplayName("Versatile attacks, the hand-built and the generated, read as such: the hobgoblin's longsword 1d10 + 1 "
            + "(MM p.186), the knight's greatsword none; two hundred and sixty-three in all")
    void versatile() {
        MonsterTemplates.Action longsword = action("Hobgoblin", "MM", "Longsword");
        assertEquals("1d10 + 1 slashing", MonsterEffects.twoHandedDamageOf(longsword));
        assertNull(MonsterEffects.twoHandedDamageOf(action("Knight", "MM", "Greatsword")));
        assertEquals("2d10 + 4 slashing", MonsterEffects.twoHandedDamageOf(action("Izek Strazni", "CoS",
                "Battleaxe")));
        int count = 0;
        for (JsonNode definition : definitions) {
            MonsterTemplates templates = MonsterTemplates.of(definition);
            for (MonsterTemplates.Action action : templates.actions()) {
                count += action.isVersatile() ? 1 : 0;
            }
            for (String list : new String[]{"BONUS", "REACTION", "LEGENDARY"}) {
                for (MonsterTemplates.Action action : templates.list(list)) {
                    count += action.isVersatile() ? 1 : 0;
                }
            }
        }
        assertEquals(263, count);
    }

    @Test
    @DisplayName("The wereraven's beak deals 1 in raven form and 1d4 + 2 otherwise (VRGR), the oni's glaive 1d10 + 4 in "
            + "Small or Medium form (MM p.239)")
    void forms() {
        MonsterTemplates.Action beak = action("Wereraven", "VRGR", "Beak (Raven or Hybrid Form Only)");
        assertEquals(List.of("raven form"), beak.forms());
        assertEquals(List.of("1d4 + 2", "1"), beak.withForm("raven form").effects().stream()
                .map(MonsterTemplates.Part::damageDice).toList());
        assertEquals(List.of("1d4 + 2"), beak.withForm("usual").effects().stream()
                .map(MonsterTemplates.Part::damageDice).toList());
        assertEquals(List.of("small or medium form"), action("Oni", "MM", "Glaive").forms());
        assertEquals(List.of("enlarged"), action("Duergar", "MM", "War Pick").forms());
    }
}
