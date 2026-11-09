package com.tabletophelper.encounter.internal;

import com.tabletophelper.content.internal.SummonSpiritsFile;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.rules.combat.SummonSpirits;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Tasha's spirits' templates from {@code data/content/summon-spirits.json} (TCE p.109–114, D182): every spirit and
 * variant at every slot level from its spell's to 9th makes templates the encounter can read, with a Multiattack of
 * half the slot level of the variant's attack, and the caster's numbers in place of the placeholders.
 */
class SpiritTemplatesTest {

    private final ObjectMapper objectMapper = JacksonDefaults.mapper();
    private final SummonSpirits spirits = SummonSpiritsFile.read(objectMapper);
    private final SpiritTemplates templates = new SpiritTemplates(spirits, objectMapper);

    @Test
    @DisplayName("Every Tasha's summoning spell has its spirit, with the variants the book gives")
    void everySpell() {
        List<String> spells = SummonRules.spells().stream()
                .filter(spell -> spell.kind() == SummonRules.Kind.SPIRIT).map(SummonRules.Spell::name).toList();
        assertEquals(4, spells.size());
        spells.forEach(spell -> assertTrue(spirits.forSpell(spell).isPresent(), spell));
        assertEquals(List.of("Air", "Land", "Water"), spirits.forSpell("Summon Beast").orElseThrow().variantNames());
        assertEquals(List.of("Fuming", "Mirthful", "Tricksy"),
                spirits.forSpell("Summon Fey").orElseThrow().variantNames());
        assertEquals(List.of("Fury", "Despair", "Fear"),
                spirits.forSpell("Summon Shadowspawn").orElseThrow().variantNames());
        assertEquals(List.of("Ghostly", "Putrid", "Skeletal"),
                spirits.forSpell("Summon Undead").orElseThrow().variantNames());
    }

    @Test
    @DisplayName("Every variant at every level reads, its Multiattack naming an action it has, half the level times")
    void everyVariantAtEveryLevel() {
        for (SummonSpirits.Spirit spirit : spirits.spirits()) {
            for (SummonSpirits.Variant variant : spirit.variants()) {
                for (int slot = spirit.baseLevel(); slot <= 9; slot++) {
                    String what = spirit.spell() + " " + variant.name() + " at " + slot;
                    MonsterTemplates made = templates.templates(spirit, variant, slot, 7, 15);
                    assertNull(made.unreadable(), what);
                    MonsterTemplates.Action multiattack = made.find("ACTION", "Multiattack").orElseThrow();
                    assertEquals(slot / 2, multiattack.multiattackComponents().size(), what);
                    String attack = SpiritTemplates.attack(spirit, variant);
                    MonsterTemplates.Action action = made.find("ACTION", attack).orElseThrow();
                    assertEquals(7, action.attackBonus(), what);
                    assertFalse(made.json().toString().contains("SPELL_"), what);
                    assertFalse(made.json().toString().contains("LEVEL"), what);
                }
            }
        }
    }

    @Test
    @DisplayName("Golden: a ghostly undead at 3rd hits for 1d8 + 6 and frightens on a failed DC 15 Wisdom save")
    void ghostly_thirdLevel() {
        SummonSpirits.Spirit undead = spirits.forSpell("Summon Undead").orElseThrow();
        MonsterTemplates made = templates.templates(undead, undead.variant("Ghostly").orElseThrow(), 3, 6, 15);
        MonsterTemplates.Action touch = made.find("ACTION", "Deathly Touch").orElseThrow();
        assertEquals("1d8 + 6", touch.effects().getFirst().damageDice());
        assertEquals(15, touch.effects().get(1).saveDc());
        assertEquals("WIS", touch.effects().get(1).saveAbility());
        assertEquals(30, SpiritTemplates.hitPoints(undead, undead.variant("Ghostly").orElseThrow(), 3));
        assertEquals(20, SpiritTemplates.hitPoints(undead, undead.variant("Skeletal").orElseThrow(), 3));
    }

    @Test
    @DisplayName("Golden: a shadow spirit at 5th has 65 HP, AC 16, and its Dreadful Scream once a day")
    void shadow_fifthLevel() {
        SummonSpirits.Spirit shadow = spirits.forSpell("Summon Shadowspawn").orElseThrow();
        SummonSpirits.Variant fury = shadow.variant("Fury").orElseThrow();
        assertEquals(65, SpiritTemplates.hitPoints(shadow, fury, 5));
        assertEquals(16, SpiritTemplates.armourClass(shadow, 5));
        MonsterTemplates made = templates.templates(shadow, fury, 5, 7, 15);
        MonsterTemplates.Action scream = made.find("ACTION", "Dreadful Scream (1/Day)").orElseThrow();
        assertEquals(15, scream.saveDc());
        assertTrue(LimitedUsage.of(scream).isPresent());
        assertEquals("1d12 + 8", made.find("ACTION", "Chilling Rend").orElseThrow().effects().getFirst()
                .damageDice());
    }

    @Test
    @DisplayName("Golden: a land beast at 4th has AC 15, 40 HP and two attacks")
    void bestialLand_fourthLevel() {
        SummonSpirits.Spirit beast = spirits.forSpell("Summon Beast").orElseThrow();
        SummonSpirits.Variant land = beast.variant("Land").orElseThrow();
        assertEquals(15, SpiritTemplates.armourClass(beast, 4));
        assertEquals(40, SpiritTemplates.hitPoints(beast, land, 4));
        assertEquals(List.of("Maul", "Maul"), templates.templates(beast, land, 4, 5, 13)
                .find("ACTION", "Multiattack").orElseThrow().multiattackComponents());
    }
}
