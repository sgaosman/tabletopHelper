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
        assertEquals(8, spells.size());
        spells.forEach(spell -> assertTrue(spirits.forSpell(spell).isPresent(), spell));
        // Each spirit's stat block is read from the book its spell names (D185)
        spells.forEach(spell -> assertEquals(SummonRules.spell(spell).orElseThrow().source(),
                spirits.forSpell(spell).orElseThrow().source(), spell));
        assertEquals(List.of("Beholderkin", "Slaad", "Star Spawn"),
                spirits.forSpell("Summon Aberration").orElseThrow().variantNames());
        assertEquals(List.of("Clay", "Metal", "Stone"),
                spirits.forSpell("Summon Construct").orElseThrow().variantNames());
        assertEquals(List.of("Air", "Earth", "Fire", "Water"),
                spirits.forSpell("Summon Elemental").orElseThrow().variantNames());
        assertEquals(List.of("Reaper"), spirits.forSpell("Spirit of Death").orElseThrow().variantNames());
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
    @DisplayName("Golden: a fire elemental at 4th has AC 15, 50 HP, two fire Slams of 1d10 + 8 and immunity to fire; "
            + "the others slam with bludgeoning and resist by element (TCE p.111)")
    void elemental_fourthLevel() {
        SummonSpirits.Spirit elemental = spirits.forSpell("Summon Elemental").orElseThrow();
        SummonSpirits.Variant fire = elemental.variant("Fire").orElseThrow();
        assertEquals(15, SpiritTemplates.armourClass(elemental, 4));
        assertEquals(50, SpiritTemplates.hitPoints(elemental, fire, 4));
        MonsterTemplates made = templates.templates(elemental, fire, 4, 7, 15);
        assertEquals(List.of("Slam", "Slam"), made.find("ACTION", "Multiattack").orElseThrow()
                .multiattackComponents());
        MonsterTemplates.Part slam = made.find("ACTION", "Slam").orElseThrow().effects().getFirst();
        assertEquals("1d10 + 8", slam.damageDice());
        assertEquals("fire", slam.damageType());
        assertEquals(List.of("fire"), fire.damageImmunities());
        for (String name : List.of("Air", "Earth", "Water")) {
            SummonSpirits.Variant variant = elemental.variant(name).orElseThrow();
            assertEquals("bludgeoning", templates.templates(elemental, variant, 4, 7, 15).find("ACTION", "Slam")
                    .orElseThrow().effects().getFirst().damageType(), name);
            assertTrue(variant.damageImmunities().isEmpty(), name);
        }
        assertEquals(List.of("lightning", "thunder"), elemental.variant("Air").orElseThrow().damageResistances());
        assertEquals(List.of("piercing", "slashing"), elemental.variant("Earth").orElseThrow().damageResistances());
        assertEquals(List.of("acid"), elemental.variant("Water").orElseThrow().damageResistances());
        assertTrue(fire.damageResistances().isEmpty());
    }

    @Test
    @DisplayName("Golden: a clay construct at 6th has AC 19, 70 HP, three Slams and its Berserk Lashing reaction")
    void construct_sixthLevel() {
        SummonSpirits.Spirit construct = spirits.forSpell("Summon Construct").orElseThrow();
        SummonSpirits.Variant clay = construct.variant("Clay").orElseThrow();
        assertEquals(19, SpiritTemplates.armourClass(construct, 6));
        assertEquals(70, SpiritTemplates.hitPoints(construct, clay, 6));
        MonsterTemplates made = templates.templates(construct, clay, 6, 8, 16);
        assertEquals(3, made.find("ACTION", "Multiattack").orElseThrow().multiattackComponents().size());
        MonsterTemplates.Action lashing = made.find("REACTION", "Berserk Lashing").orElseThrow();
        assertEquals(8, lashing.attackBonus());
        assertEquals("1d8 + 10", lashing.effects().getFirst().damageDice());
        assertTrue(templates.templates(construct, construct.variant("Metal").orElseThrow(), 6, 8, 16)
                .reactions().isEmpty());
    }

    @Test
    @DisplayName("Golden: the aberrant spirits attack by kind: a beholderkin's Eye Ray is a ranged psychic spell attack")
    void aberration_kinds() {
        SummonSpirits.Spirit aberration = spirits.forSpell("Summon Aberration").orElseThrow();
        MonsterTemplates beholderkin = templates.templates(aberration,
                aberration.variant("Beholderkin").orElseThrow(), 5, 7, 15);
        MonsterTemplates.Action ray = beholderkin.find("ACTION", "Eye Ray").orElseThrow();
        assertEquals("RANGED_SPELL", ray.attackType());
        assertEquals("1d8 + 8", ray.effects().getFirst().damageDice());
        assertEquals("psychic", ray.effects().getFirst().damageType());
        assertEquals(60, SpiritTemplates.hitPoints(aberration, aberration.variant("Slaad").orElseThrow(), 6));
        MonsterTemplates slaad = templates.templates(aberration, aberration.variant("Slaad").orElseThrow(), 4, 7, 15);
        assertEquals("1d10 + 7", slaad.find("ACTION", "Claws").orElseThrow().effects().getFirst().damageDice());
        assertTrue(slaad.find("ACTION", "Eye Ray").isEmpty());
    }

    @Test
    @DisplayName("Golden: the reaper spirit has one form, taken without naming it, and scythes for necrotic damage")
    void reaper_oneForm() {
        SummonSpirits.Spirit reaper = spirits.forSpell("Spirit of Death").orElseThrow();
        assertEquals("BMT", reaper.source());
        assertFalse(reaper.hasChoice());
        SummonSpirits.Variant only = reaper.variant(null).orElseThrow();
        assertEquals("Reaper", only.name());
        assertEquals(50, SpiritTemplates.hitPoints(reaper, only, 5));
        MonsterTemplates made = templates.templates(reaper, only, 4, 6, 14);
        MonsterTemplates.Action scythe = made.find("ACTION", "Reaping Scythe").orElseThrow();
        assertEquals("1d8 + 7", scythe.effects().getFirst().damageDice());
        assertEquals("necrotic", scythe.effects().getFirst().damageType());
        assertTrue(made.find("BONUS", "Haunt Creature").orElseThrow().description().contains("DC 14 Wisdom"));
        // A spirit with several forms still needs one named
        assertTrue(spirits.forSpell("Summon Elemental").orElseThrow().variant(null).isEmpty());
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
