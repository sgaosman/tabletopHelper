package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.shared.JacksonDefaults;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The spell data D225 corrects, with the templates as the game data ships them: Chromatic Orb's and Conjure
 * Barrage's damage type chosen as a cast option, Magic Weapon's and Elemental Weapon's bonus stopping at +3, and
 * Etherealness's three creatures per slot level above 7th. The expected numbers are typed out from the PHB.
 */
class SpellDataFixesTest {

    private static final String DEFINITIONS = "/data/spell-effects/spell-effect-definitions.json";
    private static final List<String> SPELLS = List.of("Chromatic Orb", "Conjure Barrage", "Magic Weapon",
            "Elemental Weapon", "Etherealness");
    private static final JsonNode DEFINITIONS_READ = read();

    private CombatFixture f;
    private EncounterParticipant wizard;

    private static JsonNode read() {
        try (InputStream in = SpellDataFixesTest.class.getResourceAsStream(DEFINITIONS)) {
            return JacksonDefaults.mapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
        for (JsonNode definition : DEFINITIONS_READ) {
            if (SPELLS.contains(definition.path("spellName").asString())) {
                f.spell(definition.path("spellName").asString(), definition.path("spellLevel").asInt(),
                        definition.toString());
            }
        }
    }

    private List<Effect.DamagePart> damage(String spell, int slotLevel, String option) {
        return f.spellEffects.forCast(spell, slotLevel, wizard, "INT", 5, 13, null, option).effect().damage();
    }

    @Test
    @DisplayName("Chromatic Orb asks for acid, cold, fire, lightning, poison or thunder, and its 3d8, 1d8 more per slot "
            + "level above 1st, is of the type chosen (PHB p.221)")
    void chromaticOrb() {
        assertEquals(List.of("acid", "cold", "fire", "lightning", "poison", "thunder"),
                f.spellEffects.castOptions("Chromatic Orb"));
        assertEquals("fire", f.spellEffects.option("Chromatic Orb", "Fire"));
        assertThrows(ValidationException.class, () -> f.spellEffects.option("Chromatic Orb", null));
        assertThrows(ValidationException.class, () -> f.spellEffects.option("Chromatic Orb", "radiant"));
        assertEquals(List.of(new Effect.DamagePart(new Dice(3, 8, 0), "thunder")),
                damage("Chromatic Orb", 1, "thunder"));
        assertEquals(List.of(new Effect.DamagePart(new Dice(5, 8, 0), "fire")), damage("Chromatic Orb", 3, "fire"));
    }

    @Test
    @DisplayName("Conjure Barrage asks for the ammunition's or weapon's piercing, bludgeoning or slashing, as Conjure "
            + "Volley does, and its 3d8 is of that type (PHB p.225)")
    void conjureBarrage() {
        assertEquals(List.of("piercing", "bludgeoning", "slashing"), f.spellEffects.castOptions("Conjure Barrage"));
        assertThrows(ValidationException.class, () -> f.spellEffects.option("Conjure Barrage", null));
        assertThrows(ValidationException.class, () -> f.spellEffects.option("Conjure Barrage", "fire"));
        assertEquals(List.of(new Effect.DamagePart(new Dice(3, 8, 0), "slashing")),
                damage("Conjure Barrage", 3, "slashing"));
    }

    @Test
    @DisplayName("Magic Weapon's attack and damage bonus is +1 at 2nd and 3rd level, +2 at 4th and 5th, and +3 at 6th "
            + "and above, never +4 (PHB p.257)")
    void magicWeapon() {
        int[][] expected = {{2, 1}, {3, 1}, {4, 2}, {5, 2}, {6, 3}, {7, 3}, {8, 3}, {9, 3}};
        for (int[] row : expected) {
            List<OngoingModifier> modifiers = f.spellEffects.modifiers("Magic Weapon", row[0], null, 3, null)
                    .modifiers();
            assertEquals(2, modifiers.size());
            for (OngoingModifier modifier : modifiers) {
                assertEquals(row[1], modifier.flat(), "slot " + row[0] + ", " + modifier.kind());
            }
        }
    }

    @Test
    @DisplayName("Elemental Weapon is +1 and 1d4 at 3rd and 4th level, +2 and 2d4 at 5th and 6th, and +3 and 3d4 at "
            + "7th and above (PHB p.237)")
    void elementalWeapon() {
        Object[][] expected = {{3, 1, "1d4"}, {4, 1, "1d4"}, {5, 2, "2d4"}, {6, 2, "2d4"}, {7, 3, "3d4"},
                {8, 3, "3d4"}, {9, 3, "3d4"}};
        for (Object[] row : expected) {
            int slot = (int) row[0];
            List<OngoingModifier> modifiers = f.spellEffects.modifiers("Elemental Weapon", slot, "cold", 3, null)
                    .modifiers();
            OngoingModifier attack = modifiers.stream().filter(m -> m.kind() == OngoingModifier.Kind.ATTACK_ROLL)
                    .findFirst().orElseThrow();
            OngoingModifier damage = modifiers.stream().filter(m -> m.kind() == OngoingModifier.Kind.HIT_DAMAGE)
                    .findFirst().orElseThrow();
            assertEquals(row[1], attack.flat(), "slot " + slot);
            assertEquals(row[2], damage.dice(), "slot " + slot);
            assertEquals(List.of("cold"), damage.damageTypes(), "slot " + slot);
        }
    }

    @Test
    @DisplayName("Etherealness affects the caster alone at 7th level, and up to three willing creatures for each slot "
            + "level above 7th: 3 at 8th and 6 at 9th (PHB p.238)")
    void etherealness() {
        assertEquals(OptionalInt.of(1), f.spellEffects.maxTargets("Etherealness", 7));
        assertEquals(OptionalInt.of(3), f.spellEffects.maxTargets("Etherealness", 8));
        assertEquals(OptionalInt.of(6), f.spellEffects.maxTargets("Etherealness", 9));
    }
}
