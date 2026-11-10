package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.Race;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.ShapeSpells;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the rules read from a creature in a spell's form (D192). Aria is a 1st-level character with WIS 16 and
 * proficiency in Wisdom saves (+5); the form is a brown bear (STR 19, INT 2, WIS 13, CHA 7, 34 HP, AC 11, a listed
 * +3 Wisdom save here). An ogre with its own +4 Wisdom save and immunity to being frightened is the monster target.
 */
class FormStatsTest {

    private CombatFixture f;
    private EncounterParticipant aria;
    private EncounterParticipant ogre;
    private Monster bear;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        aria = f.player("Aria", 30, 12);
        aria.getCharacter().setWisdom(16);
        aria.getCharacter().setSavingThrowProficiencies(List.of("INT", "WIS"));
        ogre = f.monster("Ogre", 59, 11);
        ogre.getMonster().setType("giant");
        ogre.getMonster().setSavingThrows("{\"wis\": \"+4\"}");
        ogre.getMonster().setConditionImmunities("[\"frightened\"]");
        bear = Monster.builder().id(UUID.randomUUID()).name("Brown Bear").type("beast").challengeRating("1")
                .source("MM").size("Large").hitPoints(34).armourClass(11).strength(19).dexterity(10).constitution(16)
                .intelligence(2).wisdom(13).charisma(7).savingThrows("{\"wis\": \"+3\"}").build();
    }

    // Gives the creature the spell's entry, then the form on it
    private void transform(EncounterParticipant creature, ShapeSpells.Rules rules) {
        ConditionEntry entry = ConditionEntry.fromEffect(rules.entry(), null, 1, rules.spell(), aria.getId(), null);
        List<ConditionEntry> conditions = new ArrayList<>(creature.getActiveConditions() != null
                ? creature.getActiveConditions() : List.of());
        conditions.add(entry);
        creature.setActiveConditions(conditions);
        f.effectApplier.startForm(creature, entry.id(), f.wildShapeEffects.spellForm(bear, creature, rules), false);
    }

    @Test
    @DisplayName("Polymorph: every score and save is the bear's, its own proficiency gone (PHB p.266)")
    void polymorphReplacesEverything() {
        transform(aria, ShapeSpells.POLYMORPH);

        assertThat(aria.getHpCurrent()).isEqualTo(34);
        assertThat(aria.getArmourClass()).isEqualTo(11);
        assertThat(f.stats.abilityScore(aria, Ability.STR)).isEqualTo(19);
        assertThat(f.stats.abilityScore(aria, Ability.WIS)).isEqualTo(13);
        assertThat(f.stats.abilityScore(aria, Ability.INT)).isEqualTo(2);
        assertThat(f.stats.saveModifier(aria, Ability.WIS)).isEqualTo(3);
        // INT isn't listed: the bear's INT 2 modifier, without Aria's proficiency
        assertThat(f.stats.saveModifier(aria, Ability.INT)).isEqualTo(-4);
        assertThat(CreatureStats.typeForSpells(aria)).isEqualTo("beast");
    }

    @Test
    @DisplayName("Shapechange keeps Aria's mental scores and proficiencies, with the higher save bonus (PHB p.274)")
    void shapechangeKeepsMind() {
        transform(aria, ShapeSpells.SHAPECHANGE);

        assertThat(f.stats.abilityScore(aria, Ability.STR)).isEqualTo(19);
        assertThat(f.stats.abilityScore(aria, Ability.WIS)).isEqualTo(16);
        assertThat(f.stats.abilityScore(aria, Ability.INT)).isEqualTo(10);
        // Her own +3 + 2 beats the bear's +3
        assertThat(f.stats.saveModifier(aria, Ability.WIS)).isEqualTo(5);
        assertThat(WildShapeForm.of(aria).orElseThrow().keepsOwn()).isTrue();
    }

    @Test
    @DisplayName("Animal Shapes keeps the mental scores but not the proficiencies (PHB p.212)")
    void animalShapesKeepsScores() {
        transform(aria, ShapeSpells.ANIMAL_SHAPES);

        assertThat(f.stats.abilityScore(aria, Ability.WIS)).isEqualTo(16);
        assertThat(f.stats.saveModifier(aria, Ability.WIS)).isEqualTo(3);
        assertThat(f.stats.saveModifier(aria, Ability.INT)).isZero();
    }

    @Test
    @DisplayName("A polymorphed monster saves and resists as the bear, losing its own save bonus and immunity")
    void polymorphedMonster() {
        assertThat(f.stats.saveModifier(ogre, Ability.WIS)).isEqualTo(4);
        assertThat(f.stats.immuneToCondition(ogre, "frightened")).isTrue();

        transform(ogre, ShapeSpells.POLYMORPH);

        assertThat(f.stats.saveModifier(ogre, Ability.WIS)).isEqualTo(3);
        assertThat(f.stats.immuneToCondition(ogre, "frightened")).isFalse();
        assertThat(f.stats.abilityScore(ogre, Ability.STR)).isEqualTo(19);
        assertThat(CreatureStats.creatureType(ogre)).isEqualTo("beast");
        assertThat(ogre.getHpMax()).isEqualTo(34);
        assertThat(WildShapeForm.of(ogre).orElseThrow().normalHpMax()).isEqualTo(59);
    }

    private static final String MAGIC_RESISTANCE = "[{\"name\": \"Magic Resistance\", \"description\": \"It has "
            + "advantage on saving throws against spells and other magical effects.\"}]";
    private static final String GNOME_CUNNING = "[{\"name\": \"Gnome Cunning\", \"description\": \"You have advantage "
            + "on all Intelligence, Wisdom, and Charisma saving throws against magic.\"}]";

    @Test
    @DisplayName("A polymorphed Magic-Resistant monster loses Magic Resistance; a form with it gives it (D191)")
    void formTraitsReplaceOwn() {
        ogre.getMonster().setTraits(MAGIC_RESISTANCE);
        assertThat(f.stats.spellSaveAdvantage(ogre, Ability.WIS)).isEqualTo("Magic Resistance");

        transform(ogre, ShapeSpells.POLYMORPH);
        assertThat(f.stats.spellSaveAdvantage(ogre, Ability.WIS)).isNull();

        bear.setTraits(MAGIC_RESISTANCE);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        transform(goblin, ShapeSpells.TRUE_POLYMORPH);
        assertThat(f.stats.spellSaveAdvantage(goblin, Ability.DEX)).isEqualTo("Magic Resistance");
    }

    @Test
    @DisplayName("A gnome keeps Gnome Cunning in Shapechange but not under Polymorph (PHB p.37, p.274, p.266)")
    void raceTraitsKeptOnlyWithFeatures() {
        aria.getCharacter().setRaceRef(Race.builder().name("Gnome").traits(GNOME_CUNNING).build());
        EncounterParticipant other = f.player("Bryn", 20, 12);
        other.getCharacter().setRaceRef(Race.builder().name("Gnome").traits(GNOME_CUNNING).build());

        transform(aria, ShapeSpells.SHAPECHANGE);
        transform(other, ShapeSpells.POLYMORPH);

        assertThat(f.stats.spellSaveAdvantage(aria, Ability.WIS)).isEqualTo("Gnome Cunning");
        assertThat(f.stats.spellSaveAdvantage(other, Ability.WIS)).isNull();
    }

    @Test
    @DisplayName("Falling unconscious ends a Wild Shape but not a Polymorph (PHB p.67, p.266)")
    void unconsciousKeepsPolymorph() {
        transform(ogre, ShapeSpells.POLYMORPH);

        f.effectApplier.addCondition(f.encounter, ogre, "unconscious", null);

        assertThat(WildShapeForm.of(ogre)).isPresent();
    }
}
