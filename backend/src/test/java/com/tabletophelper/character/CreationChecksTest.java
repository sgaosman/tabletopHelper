package com.tabletophelper.character;

import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Race;
import com.tabletophelper.rules.character.ProficiencyLists;
import com.tabletophelper.rules.combat.DamageRules;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a create request may give a character (D232): saving throws worked out from the first class (PHB p.164),
 * damage resistances only as the race gives them (D158, PHB p.34), and skill proficiencies only as the race, the
 * classes and the background give them, a background skill held already replaced by any other (PHB p.125, D226). The
 * reference data is shaped as the content loaders store it.
 */
class CreationChecksTest {

    private final ObjectMapper objectMapper = JacksonDefaults.mapper();

    private final CharacterClass fighter = CharacterClass.builder().id(UUID.randomUUID()).name("Fighter")
            .savingThrowProficiencies("[\"STR\",\"CON\"]")
            .skillChoices("{\"count\":2,\"from\":[\"Acrobatics\",\"Animal Handling\",\"Athletics\",\"History\","
                    + "\"Insight\",\"Intimidation\",\"Perception\",\"Survival\"]}")
            .build();
    private final CharacterClass wizard = CharacterClass.builder().id(UUID.randomUUID()).name("Wizard")
            .savingThrowProficiencies("[\"INT\",\"WIS\"]")
            .skillChoices("{\"count\":2,\"from\":[\"Arcana\",\"History\",\"Insight\",\"Investigation\",\"Medicine\","
                    + "\"Religion\"]}")
            .build();
    private final CharacterClass rogue = CharacterClass.builder().id(UUID.randomUUID()).name("Rogue")
            .savingThrowProficiencies("[\"DEX\",\"INT\"]")
            .skillChoices("{\"count\":4,\"from\":[\"Acrobatics\",\"Athletics\",\"Deception\",\"Insight\","
                    + "\"Intimidation\",\"Investigation\",\"Perception\",\"Performance\",\"Persuasion\","
                    + "\"Sleight Of Hand\",\"Stealth\"]}")
            .multiclassProficiencies("{\"armor\":[\"Light\"],\"skills\":{\"count\":1,\"from\":[\"Acrobatics\","
                    + "\"Athletics\",\"Deception\",\"Insight\",\"Intimidation\",\"Investigation\",\"Perception\","
                    + "\"Performance\",\"Persuasion\",\"Sleight Of Hand\",\"Stealth\"]}}")
            .build();

    private final Race human = Race.builder().id(UUID.randomUUID()).name("Human").resistances("[]").build();
    private final Race variantHuman = Race.builder().id(UUID.randomUUID()).name("Variant Human").resistances("[]")
            .raceChoices("{\"feats\":1,\"skills\":[{\"any\":1}]}").build();
    private final Race highElf = Race.builder().id(UUID.randomUUID()).name("High Elf").resistances("[]")
            .proficiencies("{\"skills\":[\"Perception\"]}").build();
    private final Race tiefling = Race.builder().id(UUID.randomUUID()).name("Tiefling").resistances("[\"fire\"]")
            .build();
    private final Race dragonborn = Race.builder().id(UUID.randomUUID()).name("Dragonborn").resistances("[]")
            .traits("[{\"name\":\"Draconic Ancestry\",\"description\":\"Choose a dragon\"},"
                    + "{\"name\":\"Breath Weapon\",\"description\":\"Exhale\"}]")
            .build();
    // A race whose resistance is the player's choice, as the races loader stores a "choose" resist
    private final Race chooser = Race.builder().id(UUID.randomUUID()).name("Chooser")
            .resistances("[\"choose: acid, cold\",\"condition immunity: disease\"]")
            .raceChoices("{\"resistances\":[{\"from\":[\"Acid\",\"Cold\"]}]}").build();

    private final Background soldier = Background.builder().id(UUID.randomUUID()).name("Soldier")
            .skillProficiencies("[\"Athletics\",\"Intimidation\"]").build();
    private final Background sage = Background.builder().id(UUID.randomUUID()).name("Sage")
            .skillProficiencies("[\"Arcana\",\"History\"]").build();
    private final Background wanderer = Background.builder().id(UUID.randomUUID()).name("Wanderer")
            .skillProficiencies("[{\"choose\":{\"from\":[\"Nature\",\"Survival\"],\"count\":1}},{\"any\":1}]").build();

    @Nested
    @DisplayName("Saving throws")
    class SavingThrows {

        @Test
        @DisplayName("Are the first class's two, whatever the request says (PHB p.164)")
        void firstClasss() {
            assertThat(CreationChecks.savingThrows(objectMapper, fighter)).containsExactly("STR", "CON");
            assertThat(CreationChecks.savingThrows(objectMapper, wizard)).containsExactly("INT", "WIS");
        }

        @Test
        @DisplayName("Are none without a class, and class data that can't be read is a 409 (D155)")
        void noneOrUnreadable() {
            assertThat(CreationChecks.savingThrows(objectMapper, null)).isEmpty();
            CharacterClass broken = CharacterClass.builder().name("Broken").savingThrowProficiencies("[STR").build();
            assertThatThrownBy(() -> CreationChecks.savingThrows(objectMapper, broken))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("The saving throws of the class Broken can't be read");
        }
    }

    @Nested
    @DisplayName("Damage resistances")
    class DamageResistances {

        @Test
        @DisplayName("A Tiefling keeps its fire resistance; any other is a 400 naming it (D158)")
        void tiefling() {
            assertThat(CreationChecks.damageResistances(objectMapper, tiefling, null, List.of("fire", "Fire")))
                    .containsExactly("fire");
            assertThatThrownBy(() -> CreationChecks.damageResistances(objectMapper, tiefling, null,
                    List.of("fire", "cold")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageStartingWith("Resistance to cold isn't one the Tiefling gives");
        }

        @Test
        @DisplayName("A Dragonborn resists its draconic ancestry's damage type only (PHB p.34, D208)")
        void dragonborn() {
            assertThat(CreationChecks.damageResistances(objectMapper, dragonborn, "Red", List.of("fire")))
                    .containsExactly("fire");
            assertThat(CreationChecks.damageResistances(objectMapper, dragonborn, "silver", List.of("cold")))
                    .containsExactly("cold");
            assertThatThrownBy(() -> CreationChecks.damageResistances(objectMapper, dragonborn, "Red",
                    List.of("cold")))
                    .isInstanceOf(ValidationException.class).hasMessageStartingWith("Resistance to cold isn't");
            assertThatThrownBy(() -> CreationChecks.damageResistances(objectMapper, dragonborn, null,
                    List.of("fire")))
                    .isInstanceOf(ValidationException.class).hasMessageStartingWith("Resistance to fire isn't");
            assertThatThrownBy(() -> CreationChecks.damageResistances(objectMapper, human, "Red", List.of("fire")))
                    .as("an ancestry counts only for a race with the trait")
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("A race's choice gives one of its types, not two; its condition immunities pass as the wizard "
                + "sends them")
        void raceChoice() {
            assertThat(CreationChecks.damageResistances(objectMapper, chooser, null,
                    List.of("Cold", "condition immunity: disease"))).containsExactly("Cold", "condition immunity: disease");
            assertThatThrownBy(() -> CreationChecks.damageResistances(objectMapper, chooser, null,
                    List.of("acid", "cold")))
                    .isInstanceOf(ValidationException.class).hasMessageStartingWith("Resistance to cold isn't");
        }

        @Test
        @DisplayName("All 13 damage types are refused for a Human, and none asked is none given")
        void everyType() {
            List<String> all = new ArrayList<>(DamageRules.DAMAGE_TYPES);
            assertThat(all).hasSize(13);
            assertThatThrownBy(() -> CreationChecks.damageResistances(objectMapper, human, null, all))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("isn't one the Human gives")
                    .hasMessageContaining("comes from the DM's adjustments");
            assertThat(CreationChecks.damageResistances(objectMapper, human, null, null)).isNull();
            assertThat(CreationChecks.damageResistances(objectMapper, human, null, List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Skill proficiencies")
    class SkillProficiencies {

        @Test
        @DisplayName("A Human Fighter soldier: two of the fighter's skills and the soldier's two")
        void fighterSoldier() {
            assertThat(CreationChecks.skillProficiencies(objectMapper, human, List.of(fighter), soldier,
                    List.of("Acrobatics", "Perception", "Athletics", "Intimidation")))
                    .containsExactly("Acrobatics", "Perception", "Athletics", "Intimidation");
        }

        @Test
        @DisplayName("A background skill a class gives already is replaced by any other (PHB p.125, D226); without "
                + "the overlap the extra skill is a 400")
        void backgroundReplacement() {
            assertThat(CreationChecks.skillProficiencies(objectMapper, human, List.of(fighter, wizard), soldier,
                    List.of("Athletics", "Survival", "Intimidation", "Stealth")))
                    .containsExactly("Athletics", "Survival", "Intimidation", "Stealth");
            assertThatThrownBy(() -> CreationChecks.skillProficiencies(objectMapper, human, List.of(fighter),
                    soldier, List.of("Acrobatics", "Perception", "Athletics", "Intimidation", "Stealth")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Proficiency in Stealth isn't one the race, classes and background give (4 skills in "
                            + "all)");
        }

        @Test
        @DisplayName("A High Elf wizard sage and a Variant Human with a skill of any kind")
        void raceSkills() {
            assertThat(CreationChecks.skillProficiencies(objectMapper, highElf, List.of(wizard), sage,
                    List.of("Perception", "Investigation", "Medicine", "Arcana", "History"))).hasSize(5);
            assertThat(CreationChecks.skillProficiencies(objectMapper, variantHuman, List.of(fighter), sage,
                    List.of("Stealth", "History", "Athletics", "Arcana", "Insight")))
                    .as("the race's skill of any kind takes Stealth")
                    .hasSize(5);
        }

        @Test
        @DisplayName("A later class gives its multiclassing skill only (PHB p.164); a skill off the first class's "
                + "list is a 400")
        void classLists() {
            assertThat(CreationChecks.skillProficiencies(objectMapper, human, List.of(fighter, rogue), null,
                    List.of("Athletics", "Survival", "sleight of hand")))
                    .containsExactly("Athletics", "Survival", "Sleight of Hand");
            assertThatThrownBy(() -> CreationChecks.skillProficiencies(objectMapper, human, List.of(fighter), null,
                    List.of("Athletics", "Arcana")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Proficiency in Arcana isn't one the race, classes and background give (2 skills in "
                            + "all)");
        }

        @Test
        @DisplayName("A background's choices and its skill of any kind")
        void backgroundChoices() {
            assertThat(CreationChecks.skillProficiencies(objectMapper, human, List.of(fighter), wanderer,
                    List.of("Athletics", "Perception", "Nature", "Deception"))).hasSize(4);
            assertThatThrownBy(() -> CreationChecks.skillProficiencies(objectMapper, human, List.of(fighter),
                    wanderer, List.of("Athletics", "Perception", "Nature", "Survival", "Deception")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("Proficiency in all 18 skills is a 400 naming those nothing gives")
        void everySkill() {
            assertThatThrownBy(() -> CreationChecks.skillProficiencies(objectMapper, human, List.of(rogue), soldier,
                    ProficiencyLists.SKILLS))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("(6 skills in all)");
            assertThat(CreationChecks.skillProficiencies(objectMapper, human, List.of(), null, null)).isNull();
        }
    }

    @Nested
    @DisplayName("Names stored once, in PHB casing (D232)")
    class Names {

        @Test
        @DisplayName("Expertise is stored in the skills' PHB casing, so \"Sleight Of Hand\" from a class list matches its skill")
        void expertiseCasing() {
            assertThat(CreationChecks.skillNames(List.of("Sleight Of Hand", "stealth", "Stealth")))
                    .containsExactly("Sleight of Hand", "Stealth");
            assertThat(CreationChecks.skillNames(null)).isNull();
        }

        @Test
        @DisplayName("A tool both the class and the background give, as a criminal rogue's thieves' tools, is kept once")
        void toolsOnce() {
            assertThat(CreationChecks.once(List.of("Thieves' tools", "Dice Set", "Thieves' Tools")))
                    .containsExactly("Thieves' tools", "Dice Set");
            assertThat(CreationChecks.once(null)).isNull();
        }
    }
}
