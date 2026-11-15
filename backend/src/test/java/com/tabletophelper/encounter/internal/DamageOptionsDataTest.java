package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped actions whose damage type the monster chooses when it uses them (D207), as generate_monster_actions.py
 * reads them from the stat blocks' "damage of a type chosen by ...", "of a type of its choice" and "(its choice)".
 */
class DamageOptionsDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final String[] LISTS = {"actions", "bonusActions", "reactions", "legendaryActions",
            "mythicActions", "lairActions"};
    private static final Set<String> DAMAGE_TYPES = Set.of("acid", "bludgeoning", "cold", "fire", "force",
            "lightning", "necrotic", "piercing", "poison", "psychic", "radiant", "slashing", "thunder");

    private static final List<JsonNode> definitions = new ArrayList<>();
    private final MonsterEffects monsterEffects = new MonsterEffects(new com.tabletophelper.rules.Rng() {
        @Override
        public int roll(int sides) {
            return 1;
        }
    });

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

    private static String name(JsonNode definition) {
        return definition.path("monsterName").asString() + " (" + definition.path("source").asString() + ")";
    }

    @Test
    @DisplayName("Twenty-two actions on twenty-one monsters let the monster choose the damage type, each with a part "
            + "of type \"option\", and no other action has one")
    void optionActions() {
        Map<String, List<String>> found = new TreeMap<>();
        List<String> wrong = new ArrayList<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    List<String> options = new ArrayList<>();
                    action.path("damageOptions").forEach(option -> options.add(option.asString()));
                    long optionParts = 0;
                    for (JsonNode part : action.path("effects")) {
                        optionParts += "option".equals(part.path("damageType").asString()) ? 1 : 0;
                    }
                    String what = name(definition) + ": " + action.path("name").asString();
                    if (!options.isEmpty()) {
                        found.put(what, options);
                    }
                    if (options.isEmpty() != (optionParts == 0) || !DAMAGE_TYPES.containsAll(options)) {
                        wrong.add(what);
                    }
                }
            }
        }
        assertEquals(List.of(), wrong);
        List<String> chromatic = List.of("acid", "cold", "fire", "lightning", "poison", "thunder");
        List<String> dragon = List.of("acid", "cold", "fire", "lightning", "poison");
        Map<String, List<String>> expected = new TreeMap<>();
        // The seven D207 set out to fix: the dracohydra's two, which were the DM's; the kobold's, reigar's, tortle's
        // and flesh colossus's, which dealt one type, "variable" or force; Mordakhesh's and the assassin's, none
        expected.put("Dracohydra (FTD): Bite", dragon);
        expected.put("Dracohydra (FTD): Prismatic Breath", dragon);
        expected.put("Flesh Colossus (BGG): Elemental Breath", List.of("acid", "cold", "fire", "lightning"));
        expected.put("Kobold Scale Sorcerer (MPMM): Chromatic Bolt", chromatic);
        expected.put("Mordakhesh (ERLW): Chromatic Orb", chromatic);
        expected.put("Reigar (BAM): Chromatic Bolt", List.of("cold", "fire", "lightning", "radiant"));
        expected.put("Tarkanan Assassin (ERLW): Chromatic Orb (1/Day)", chromatic);
        expected.put("Tortle Druid (MPMM): Nature's Wrath", List.of("cold", "fire", "lightning", "thunder"));
        // The type list with "(its choice)", which dealt the first type and left the choice to the DM's note
        expected.put("Crawling Claw (MM): Claw", List.of("bludgeoning", "slashing"));
        expected.put("Djinni (MM): Scimitar", List.of("lightning", "thunder"));
        expected.put("Eo Ashmajiir (MaBJoV): Twinned Orbs", chromatic);
        expected.put("Firemane Angel (GGR): Longsword", List.of("fire", "radiant"));
        expected.put("Heralds of Dust Exorcist (AATM): Grave Burst", List.of("necrotic", "radiant"));
        expected.put("High Fae Mage (MCV4EC): Elemental Strike",
                List.of("acid", "cold", "fire", "force", "lightning", "thunder"));
        expected.put("Mephistopheles (CoA): Ranseur +3", List.of("fire", "cold"));
        expected.put("Mephistopheles (MaBJoV): Ranseur, +3", List.of("fire", "cold"));
        expected.put("Nafas (QftIS): Storm Shamshir", List.of("lightning", "thunder"));
        expected.put("Prismari Apprentice (SCC): Elemental Strike", List.of("fire", "cold"));
        expected.put("Prismari Pledgemage (SCC): Elemental Strike", List.of("fire", "cold"));
        expected.put("Tomb Tapper (IDRotF): Sledgehammer", List.of("bludgeoning", "force"));
        expected.put("Windfall (VEoR): Chromatic Rapier", dragon);
        expected.put("Xan Moonblade (MaBJoV): Moonblade", List.of("cold", "fire"));
        assertEquals(expected, found);
    }

    @Test
    @DisplayName("The dracohydra's Bite is 1d10 + 5 of the type chosen, made once per head, and its Prismatic Breath a "
            + "DC 17 Dexterity save for half in a 60-foot cone; neither is the DM's any more, and no note says the "
            + "type is chosen by hand")
    void dracohydra() {
        JsonNode definition = definitions.stream()
                .filter(d -> "Dracohydra".equals(d.path("monsterName").asString())
                        && "FTD".equals(d.path("source").asString()))
                .findFirst().orElseThrow();
        MonsterTemplates templates = MonsterTemplates.of(definition);
        MonsterTemplates.Action bite = templates.find("ACTION", "Bite").orElseThrow();
        assertEquals("1d10 + 5", bite.effects().getFirst().damageDice());
        assertEquals("option", bite.effects().getFirst().damageType());
        assertNull(bite.automatable());
        assertNull(bite.dmNote());
        MonsterTemplates.Action breath = templates.find("ACTION", "Prismatic Breath").orElseThrow();
        assertEquals("SAVING_THROW", breath.deliveryMethod());
        assertEquals(17, breath.saveDc());
        assertEquals("DEX", breath.saveAbility());
        assertTrue(breath.halfOnSave());
        assertEquals("6d10", breath.effects().getFirst().damageDice());
        assertNull(breath.dmNote());
        MonsterEffects.Multiattack multiattack = monsterEffects.multiattack(
                templates.find("ACTION", "Multiattack").orElseThrow(), templates, MonsterEffects.Overrides.NONE, 3);
        assertEquals(List.of("Bite", "Bite", "Bite"),
                multiattack.components().stream().map(MonsterEffects.MultiattackComponent::name).toList());
        assertTrue(multiattack.components().stream().allMatch(c -> c.automated() && c.damageOptions().size() == 5));
        // Planned with a type chosen, the Bite deals it
        MonsterEffects.MonsterAction plan = monsterEffects.plan(bite.withDamageType("poison"), templates,
                List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        assertTrue(plan.automated(), plan::manualReason);
        assertEquals("poison", plan.steps().getFirst().effect().damage().getFirst().type());
    }

    @Test
    @DisplayName("The flesh colossus's force damage to the creatures in its chest is the DM's, not the breath's")
    void fleshColossus() {
        JsonNode definition = definitions.stream()
                .filter(d -> "Flesh Colossus".equals(d.path("monsterName").asString()))
                .findFirst().orElseThrow();
        MonsterTemplates.Action breath = MonsterTemplates.of(definition).find("ACTION", "Elemental Breath")
                .orElseThrow();
        assertEquals(1, breath.effects().size());
        assertEquals("option", breath.effects().getFirst().damageType());
        assertTrue(breath.dmNote().startsWith("Applied by hand: At the same time as the colossus releases this "
                + "exhalation, creatures inside the colossus's chest cavity take 40 (9d8) force damage"),
                breath.dmNote());
    }
}
