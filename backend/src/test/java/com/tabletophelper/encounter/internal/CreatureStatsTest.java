package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A participant's numbers from its sheet or stat block. From the old SpellResolverEngineTest, the
 * getSaveModifier nest: a PC without and with proficiency, a monster's ability modifier and listed save, and
 * an unknown ability. The old MonsterActionResolverEngine kept its own copy of the same rules, which had no
 * tests.
 */
class CreatureStatsTest {

    private CombatFixture f;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
    }

    @Test
    @DisplayName("A player character's save is its ability modifier without proficiency")
    void playerNoProficiency() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        fighter.getCharacter().setDexterity(14);
        fighter.getCharacter().setProficiencyBonus(3);

        assertEquals(2, f.stats.saveModifier(fighter, Ability.DEX));
    }

    @Test
    @DisplayName("A player character adds its proficiency bonus to a save it is proficient in")
    void playerWithProficiency() {
        EncounterParticipant sorcerer = f.player("Sorcerer", 30, 12);
        sorcerer.getCharacter().setConstitution(16);
        sorcerer.getCharacter().setProficiencyBonus(3);
        sorcerer.getCharacter().setSavingThrowProficiencies("[\"CON\",\"CHA\"]");

        assertEquals(6, f.stats.saveModifier(sorcerer, Ability.CON));
        assertEquals(0, f.stats.saveModifier(sorcerer, Ability.WIS));
    }

    @Test
    @DisplayName("Proficiencies listed by full name count too, and an odd score below 10 rounds down")
    void fullNamesAndRounding() {
        EncounterParticipant cleric = f.player("Cleric", 30, 12);
        cleric.getCharacter().setWisdom(9);
        cleric.getCharacter().setSavingThrowProficiencies("[\"Wisdom\"]");

        assertEquals(1, f.stats.saveModifier(cleric, Ability.WIS));
    }

    @Test
    @DisplayName("A monster without a listed save uses its ability modifier")
    void monsterBaseAbilityModifier() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        goblin.getMonster().setWisdom(8);

        assertEquals(-1, f.stats.saveModifier(goblin, Ability.WIS));
    }

    @Test
    @DisplayName("A monster's listed save counts, written as a number or as text such as \"+11\"")
    void monsterListedSave() {
        EncounterParticipant dragon = f.monster("Adult Red Dragon", 256, 19);
        dragon.getMonster().setCharisma(21);
        dragon.getMonster().setSavingThrows("{\"dex\": \"+7\", \"con\": 13, \"cha\": \"+11\"}");

        assertEquals(11, f.stats.saveModifier(dragon, Ability.CHA));
        assertEquals(13, f.stats.saveModifier(dragon, Ability.CON));
        assertEquals(7, f.stats.saveModifier(dragon, Ability.DEX));
        assertEquals(0, f.stats.saveModifier(dragon, Ability.STR));
    }

    @Test
    @DisplayName("An unknown ability is a modifier of 0")
    void nullAbility() {
        assertEquals(0, f.stats.saveModifier(f.monster("Goblin", 7, 15), null));
    }

    @Test
    @DisplayName("A monster's defences are the plain damage types in its stat block, in any case")
    void monsterDefences() {
        EncounterParticipant lich = f.monster("Lich", 135, 17);
        lich.getMonster().setDamageResistances("[\"cold\", \"Lightning\", "
                + "\"{\\\"resist\\\":[\\\"bludgeoning\\\"],\\\"cond\\\":true}\"]");
        lich.getMonster().setDamageImmunities("[\"poison\"]");
        lich.getMonster().setDamageVulnerabilities("[\"radiant\"]");

        assertEquals(Set.of(Defence.RESISTANCE), f.stats.defences(lich, "lightning"));
        assertEquals(Set.of(Defence.IMMUNITY), f.stats.defences(lich, "Poison"));
        assertEquals(Set.of(Defence.VULNERABILITY), f.stats.defences(lich, "radiant"));
        assertEquals(Set.of(), f.stats.defences(lich, "bludgeoning"));
        assertEquals(Set.of(), f.stats.defences(lich, null));
    }

    @Test
    @DisplayName("A player character's defences come from its sheet, which holds no vulnerabilities yet (D161)")
    void playerDefences() {
        EncounterParticipant tiefling = f.player("Tiefling", 30, 12);
        tiefling.getCharacter().setDamageResistances("[\"fire\"]");
        tiefling.getCharacter().setDamageImmunities("[\"poison\"]");

        assertEquals(Set.of(Defence.RESISTANCE), f.stats.defences(tiefling, "fire"));
        assertEquals(Set.of(Defence.IMMUNITY), f.stats.defences(tiefling, "poison"));
    }

    @Test
    @DisplayName("The cantrip level is a PC's level, or a monster's challenge rating rounded up and at least 1")
    void level() {
        EncounterParticipant wizard = f.player("Wizard", 30, 12);
        wizard.getCharacter().setLevel(7);
        EncounterParticipant lich = f.monster("Lich", 135, 17);
        lich.getMonster().setChallengeRating("21");
        EncounterParticipant imp = f.monster("Imp", 10, 13);
        imp.getMonster().setChallengeRating("1/4");
        EncounterParticipant rat = f.monster("Rat", 1, 10);
        rat.getMonster().setChallengeRating("0");

        assertEquals(7, f.stats.level(wizard));
        assertEquals(21, f.stats.level(lich));
        assertEquals(1, f.stats.level(imp));
        assertEquals(1, f.stats.level(rat));
    }

    @Test
    @DisplayName("Text in a jsonb column that doesn't parse is a 409 naming the data")
    void damagedJson() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        goblin.getMonster().setSavingThrows("{not json");

        ConflictException ex = assertThrows(ConflictException.class,
                () -> f.stats.saveModifier(goblin, Ability.DEX));

        assertEquals("Goblin's saving throws can't be read", ex.getMessage());
    }
}
