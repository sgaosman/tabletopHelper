package com.tabletophelper.character;

import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.LevelUpResponse;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Levelling up and down, and the choices levels bring (D167): each level records its Hit Die Result and what it
 * gained, levelling down removes the last level and reverses only what it recorded, and CharacterStats works the
 * Derived Stats out again after every change. The default character has STR 16, DEX 14, CON 12, INT 16, WIS 10 and
 * CHA 13, so a Fighter has 11 hit points at 1st level and 7 more a level.
 * <p>
 * These took LevelUpDownRoundTripTest's cases, by its nested groups:
 * <ul>
 *   <li>Single class level up, single class level down, the round trips, ownership, level history integrity, the
 *       subclass threshold and the spellcaster level up and down: the groups of the same names here, now checking
 *       exact hit points and the recorded hit die result.</li>
 *   <li>Multiclass level up: "A multiclass dip's first level gains the die's fixed value" here, which now expects the
 *       d6's 4 rather than its maximum (PHB p.163).</li>
 *   <li>Multiclass level down: the case of the same name in "Multiclassing".</li>
 * </ul>
 */
class LevelProgressionTest {

    private final CharacterFixture f = new CharacterFixture();

    private PlayerCharacter fighter(int level) {
        return f.create(f.human, CharacterFixture.scores(), f.fighter, level);
    }

    @Nested
    @DisplayName("Single class level up")
    class SingleClassLevelUp {

        @Test
        @DisplayName("Fighter 1→2 gains 7 hit points, Action Surge and its pool, and keeps a +2 proficiency bonus")
        void fighterLevel1To2() {
            PlayerCharacter pc = fighter(1);
            assertThat(pc.getHpMax()).isEqualTo(11);

            LevelUpResponse response = f.levelUp(pc, f.fighter);

            assertThat(response.getCharacter().getLevel()).isEqualTo(2);
            assertThat(response.getCharacter().getHpMax()).isEqualTo(18);
            assertThat(response.getCharacter().getHpCurrent()).isEqualTo(18);
            assertThat(response.getCharacter().getProficiencyBonus()).isEqualTo(2);
            assertThat(response.getPendingChoices().getNewFeatures()).contains("Action Surge");
            assertThat(pc.getResourcePools()).extracting(ResourcePoolEntry::poolId)
                    .containsExactly("class:fighter-action-surge");
        }

        @Test
        @DisplayName("Fighter at level 4 shows ASI available")
        void fighterLevel4Asi() {
            LevelUpResponse response = f.levelUp(fighter(3), f.fighter);

            assertThat(response.getCharacter().getLevel()).isEqualTo(4);
            assertThat(response.getPendingChoices().isAsiAvailable()).isTrue();
        }

        @Test
        @DisplayName("Fighter level 4→5 gains proficiency bonus increase")
        void proficiencyBonusIncrease() {
            LevelUpResponse response = f.levelUp(fighter(4), f.fighter);

            assertThat(response.getCharacter().getLevel()).isEqualTo(5);
            assertThat(response.getCharacter().getProficiencyBonus()).isEqualTo(3);
        }

        @Test
        @DisplayName("Cannot level up past 20")
        void cannotExceedLevel20() {
            PlayerCharacter pc = fighter(20);

            assertThatThrownBy(() -> f.levelUp(pc, f.fighter))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("maximum level");
        }

        @Test
        @DisplayName("A higher hit point maximum raises current hit points by as much, so a hurt character stays as hurt (D167)")
        void hurtCharacterGainsTheDifference() {
            PlayerCharacter pc = fighter(3);
            pc.setHpCurrent(10);

            f.levelUp(pc, f.fighter);

            assertThat(pc.getHpMax()).isEqualTo(32);
            assertThat(pc.getHpCurrent()).isEqualTo(17);
        }

        @Test
        @DisplayName("Levelling up adds a Hit Die to the total and the remaining, and spends no slot back")
        void spentHitDiceAndSlotsStaySpent() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.wizard, 3);
            pc.setHitDice(Map.of("Wizard", new HitDicePool(6, 3, 1)));
            pc.setSpellSlots(Map.of("1", new SpellSlot(4, 2), "2", new SpellSlot(2, 2)));

            f.levelUp(pc, f.wizard);

            assertThat(pc.getHitDice()).isEqualTo(Map.of("Wizard", new HitDicePool(6, 4, 2)));
            assertThat(pc.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(4, 2), "2", new SpellSlot(3, 2)));
        }
    }

    @Nested
    @DisplayName("Single class level down")
    class SingleClassLevelDown {

        @Test
        @DisplayName("Fighter 2→1 reverts HP and features, and the pool Action Surge gave goes")
        void fighterLevel2To1() {
            PlayerCharacter pc = fighter(2);
            assertThat(pc.getResourcePools()).hasSize(1);

            CharacterResponse response = f.levelDown(pc);

            assertThat(response.getLevel()).isEqualTo(1);
            assertThat(response.getHpMax()).isEqualTo(11);
            assertThat(response.getLevelHistory()).hasSize(1);
            assertThat(response.getFeatures()).extracting(Feature::name).doesNotContain("Action Surge");
            assertThat(response.getResourcePools()).isEmpty();
        }

        @Test
        @DisplayName("Cannot level down from level 1")
        void cannotLevelDownFromLevel1() {
            PlayerCharacter pc = fighter(1);

            assertThatThrownBy(() -> f.levelDown(pc))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("minimum level");
        }

        @Test
        @DisplayName("Level down updates the hit dice")
        void levelDownUpdatesHitDice() {
            CharacterResponse response = f.levelDown(fighter(3));

            assertThat(response.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 2, 2)));
        }

        @Test
        @DisplayName("Level down caps current hit points at the new maximum and the dice spent at the new total")
        void levelDownCapsHitPointsAndSpentDice() {
            PlayerCharacter pc = fighter(3);
            pc.setHitDice(Map.of("Fighter", new HitDicePool(10, 3, 0)));

            f.levelDown(pc);

            assertThat(pc.getHpMax()).isEqualTo(18);
            assertThat(pc.getHpCurrent()).isEqualTo(18);
            assertThat(pc.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 2, 0)));
        }

        @Test
        @DisplayName("Level down takes away only the latest copy of a feature several levels gave")
        void levelDownKeepsEarlierCopies() {
            PlayerCharacter pc = fighter(6);
            assertThat(pc.getFeatures()).filteredOn(feature -> feature.name().equals("Ability Score Improvement"))
                    .hasSize(2);

            f.levelDown(pc);

            assertThat(pc.getFeatures()).filteredOn(feature -> feature.name().equals("Ability Score Improvement"))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("Level up/down round-trip")
    class RoundTrip {

        @Test
        @DisplayName("Level up then down restores original HP")
        void levelUpThenDownRestoresHp() {
            PlayerCharacter pc = fighter(3);

            f.levelUp(pc, f.fighter);
            assertThat(pc.getLevel()).isEqualTo(4);
            assertThat(pc.getHpMax()).isEqualTo(32);
            f.levelDown(pc);

            assertThat(pc.getLevel()).isEqualTo(3);
            assertThat(pc.getHpMax()).isEqualTo(25);
            assertThat(pc.getHpCurrent()).isEqualTo(25);
        }

        @Test
        @DisplayName("Level up then down restores proficiency bonus")
        void levelUpThenDownRestoresProfBonus() {
            PlayerCharacter pc = fighter(4);

            f.levelUp(pc, f.fighter);
            assertThat(pc.getProficiencyBonus()).isEqualTo(3);
            f.levelDown(pc);

            assertThat(pc.getProficiencyBonus()).isEqualTo(2);
        }

        @Test
        @DisplayName("Level up then down restores the hit dice")
        void levelUpThenDownRestoresHitDice() {
            PlayerCharacter pc = fighter(2);

            f.levelUp(pc, f.fighter);
            assertThat(pc.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 3, 3)));
            f.levelDown(pc);

            assertThat(pc.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 2, 2)));
        }

        @Test
        @DisplayName("Multiple level ups then downs restore original state")
        void multipleRoundTrips() {
            PlayerCharacter pc = fighter(1);

            f.levelUp(pc, f.fighter);
            f.levelUp(pc, f.fighter);
            f.levelUp(pc, f.fighter);
            assertThat(pc.getLevel()).isEqualTo(4);
            f.levelDown(pc);
            f.levelDown(pc);
            f.levelDown(pc);

            assertThat(pc.getLevel()).isEqualTo(1);
            assertThat(pc.getHpMax()).isEqualTo(11);
        }

        @Test
        @DisplayName("A multiclass dip and back leaves the whole sheet as it was")
        void dipAndBackIsLossless() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.wizard, 2);
            pc.setArmorProficiencies(List.of());
            pc.setWeaponProficiencies(List.of("Dagger"));
            pc.setToolProficiencies(List.of());
            PlayerCharacter before = snapshot(pc);

            f.levelUp(pc, f.fighter);
            assertThat(pc.getArmorProficiencies()).isNotEmpty();
            f.levelDown(pc);

            assertThat(snapshot(pc)).usingRecursiveComparison().isEqualTo(before);
        }

        // The sheet's state as levelling changes it
        private PlayerCharacter snapshot(PlayerCharacter pc) {
            return PlayerCharacter.builder()
                    .level(pc.getLevel()).hpMax(pc.getHpMax()).hpCurrent(pc.getHpCurrent())
                    .proficiencyBonus(pc.getProficiencyBonus()).initiativeBonus(pc.getInitiativeBonus())
                    .speed(pc.getSpeed()).strength(pc.getStrength()).constitution(pc.getConstitution())
                    .armorProficiencies(pc.getArmorProficiencies()).weaponProficiencies(pc.getWeaponProficiencies())
                    .toolProficiencies(pc.getToolProficiencies()).features(pc.getFeatures())
                    .multiclassEntries(pc.getMulticlassEntries()).hitDice(pc.getHitDice())
                    .spellSlots(pc.getSpellSlots()).spellSaveDc(pc.getSpellSaveDc())
                    .spellAttackBonus(pc.getSpellAttackBonus()).spellcastingAbility(pc.getSpellcastingAbility())
                    .levelHistory(pc.getLevelHistory()).resourcePools(pc.getResourcePools())
                    .build();
        }
    }

    @Nested
    @DisplayName("Ownership validation")
    class OwnershipValidation {

        @Test
        @DisplayName("Level up rejects non-owner")
        void levelUpRejectsNonOwner() {
            PlayerCharacter pc = fighter(1);

            assertThatThrownBy(() -> f.service.levelUp(pc.getId(), null, UUID.randomUUID()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("do not own");
        }

        @Test
        @DisplayName("Level down rejects non-owner")
        void levelDownRejectsNonOwner() {
            PlayerCharacter pc = fighter(2);

            assertThatThrownBy(() -> f.service.levelDown(pc.getId(), UUID.randomUUID()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("do not own");
        }
    }

    @Nested
    @DisplayName("Level history integrity")
    class LevelHistoryIntegrity {

        @Test
        @DisplayName("Level up appends the level with its hit die result and what it added to the maximum")
        void levelUpAppendsHistory() {
            PlayerCharacter pc = fighter(1);

            f.levelUp(pc, f.fighter);

            List<LevelHistoryEntry> history = pc.getLevelHistory();
            assertThat(history).hasSize(2);
            LevelHistoryEntry latest = history.get(1);
            assertThat(latest.characterLevel()).isEqualTo(2);
            assertThat(latest.classId()).isEqualTo(f.fighter.getId());
            assertThat(latest.className()).isEqualTo("Fighter");
            assertThat(latest.classLevel()).isEqualTo(2);
            assertThat(latest.hitDieResult()).isEqualTo(6);
            assertThat(latest.hpGained()).isEqualTo(7);
            assertThat(history.getFirst().hitDieResult()).isEqualTo(10);
            assertThat(history.getFirst().hpGained()).isEqualTo(11);
        }

        @Test
        @DisplayName("Level down removes last history entry")
        void levelDownRemovesHistory() {
            PlayerCharacter pc = fighter(3);

            f.levelDown(pc);

            assertThat(pc.getLevelHistory()).hasSize(2);
            assertThat(pc.getLevelHistory().getLast().characterLevel()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Spellcaster level up and down")
    class Spellcasters {

        @Test
        @DisplayName("Wizard level up recalculates spell slots and asks for prepared spells up to the class's highest level")
        void wizardLevelUpRecalculatesSlots() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.wizard, 1);

            LevelUpResponse toTwo = f.levelUp(pc, f.wizard);
            assertThat(toTwo.getCharacter().getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(3, 0)));
            assertThat(toTwo.getPendingChoices().isSpellSelectionNeeded()).isTrue();
            assertThat(toTwo.getPendingChoices().getSpellSelectionType()).isEqualTo("prepared");
            assertThat(toTwo.getPendingChoices().getMaxSpellLevel()).isEqualTo(1);

            assertThat(f.levelUp(pc, f.wizard).getPendingChoices().getMaxSpellLevel()).isEqualTo(2);
        }

        @Test
        @DisplayName("Wizard level 5 to 4: spell slots recalculated, no 3rd-level slots")
        void wizardLevelDownRecalculatesSlots() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.wizard, 5);
            assertThat(pc.getHpMax()).isEqualTo(27);

            CharacterResponse response = f.levelDown(pc);

            assertThat(response.getLevel()).isEqualTo(4);
            assertThat(pc.getHpMax()).isEqualTo(22);
            assertThat(pc.getSpellSlots()).containsOnlyKeys("1", "2");
            assertThat(pc.getHitDice()).isEqualTo(Map.of("Wizard", new HitDicePool(6, 4, 4)));
        }

        @Test
        @DisplayName("An Eldritch Knight is asked for the spells it knows, up to 1st level, as a Fighter 4")
        void eldritchKnightIsPromptedForSpells() {
            PlayerCharacter pc = fighter(3);
            pc.setMulticlassEntries(List.of(new ClassEntry(f.fighter.getId(), "Fighter", UUID.randomUUID(),
                    "Eldritch Knight", 3)));

            LevelUpResponse.PendingChoices choices = f.levelUp(pc, f.fighter).getPendingChoices();

            assertThat(choices.isSpellSelectionNeeded()).isTrue();
            assertThat(choices.getSpellSelectionType()).isEqualTo("known");
            assertThat(choices.getMaxSpellLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("A Paladin's 1st level brings no spells to choose")
        void paladinFirstLevelHasNoSpells() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);

            LevelUpResponse.PendingChoices choices = f.levelUp(pc, f.paladin).getPendingChoices();

            assertThat(choices.isSpellSelectionNeeded()).isFalse();
            assertThat(choices.getMaxSpellLevel()).isZero();
        }
    }

    @Nested
    @DisplayName("Multiclassing")
    class Multiclassing {

        @Test
        @DisplayName("A multiclass dip's first level gains the die's fixed value, spell slots appear, and Hit Dice of both kinds")
        void fighterAddsWizard() {
            PlayerCharacter pc = fighter(3);

            LevelUpResponse response = f.levelUp(pc, f.wizard);

            assertThat(response.getCharacter().getLevel()).isEqualTo(4);
            // The d6's fixed 4 + 1, since a new class's first level isn't character level 1 (PHB p.163)
            assertThat(pc.getHpMax()).isEqualTo(25 + 5);
            assertThat(pc.getLevelHistory().getLast().hitDieResult()).isEqualTo(4);
            assertThat(pc.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(2, 0)));
            assertThat(pc.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 3, 3))
                    .containsEntry("Wizard", new HitDicePool(6, 1, 1));
        }

        @Test
        @DisplayName("Multiclass level down: Fighter 3 / Wizard 1 removes Wizard, reverts to Fighter 3")
        void levelDownRemovesLastClass() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 3, f.wizard, 1);
            assertThat(pc.getHpMax()).isEqualTo(30);

            CharacterResponse response = f.levelDown(pc);

            assertThat(response.getLevel()).isEqualTo(3);
            assertThat(pc.getHpMax()).isEqualTo(25);
            assertThat(pc.getSpellSlots()).isNull();
            assertThat(pc.getSpellSaveDc()).isNull();
            assertThat(pc.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 3, 3)));
        }

        @Test
        @DisplayName("Prerequisites are checked against every class the character has, not only the first (PHB p.163)")
        void everyCurrentClassPrerequisites() {
            // CHA 12 misses the Paladin's CHA 13; the Fighter and the Rogue are met
            PlayerCharacter pc = f.create(f.human, new int[]{16, 14, 12, 10, 10, 12}, f.fighter, 2, f.paladin, 2);

            assertThatThrownBy(() -> f.levelUp(pc, f.rogue))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Character does not meet multiclass prerequisites for Paladin");
            assertThat(pc.getLevel()).isEqualTo(4);
        }

        @Test
        @DisplayName("A Fighter with STR and DEX below 13 can't multiclass, even into a class whose own prerequisites it meets")
        void currentClassPrerequisitesStopADip() {
            PlayerCharacter pc = f.create(f.human, new int[]{12, 12, 12, 16, 10, 10}, f.fighter, 2);

            assertThatThrownBy(() -> f.levelUp(pc, f.wizard))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Character does not meet multiclass prerequisites for Fighter");
        }

        @Test
        @DisplayName("Multiclassing in records the proficiencies it added, and levelling down removes only those")
        void multiclassProficienciesAreReversed() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.wizard, 2);
            pc.setWeaponProficiencies(List.of("Dagger", "Simple"));

            f.levelUp(pc, f.fighter);

            assertThat(pc.getArmorProficiencies()).containsExactly("Light", "Medium", "Shield");
            assertThat(pc.getWeaponProficiencies()).containsExactly("Dagger", "Simple", "Martial");
            assertThat(pc.getLevelHistory().getLast().proficienciesGained())
                    .isEqualTo(new ProficienciesGained(List.of("Light", "Medium", "Shield"), List.of("Martial"),
                            List.of()));

            f.levelDown(pc);

            assertThat(pc.getArmorProficiencies()).isEmpty();
            assertThat(pc.getWeaponProficiencies()).containsExactly("Dagger", "Simple");
        }
    }

    @Nested
    @DisplayName("Subclass threshold")
    class SubclassThreshold {

        @Test
        @DisplayName("Fighter leveling to level 3 flags subclass as available")
        void subclassAvailableAtThreshold() {
            LevelUpResponse response = f.levelUp(fighter(2), f.fighter);

            assertThat(response.getCharacter().getLevel()).isEqualTo(3);
            assertThat(response.getPendingChoices().isSubclassRequired()).isTrue();
        }
    }

    @Nested
    @DisplayName("Primal Champion")
    class PrimalChampion {

        @Test
        @DisplayName("Barbarian 20 adds 4 to STR and CON up to 24, recorded on the level, and levelling down takes them off")
        void primalChampion() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.barbarian, 19);
            // 12 + 1, then 18 levels of 7 + 1
            assertThat(pc.getHpMax()).isEqualTo(157);

            CharacterResponse response = f.levelUp(pc, f.barbarian).getCharacter();

            assertThat(pc.getStrength()).isEqualTo(20);
            assertThat(pc.getConstitution()).isEqualTo(16);
            assertThat(pc.getLevelHistory().getLast().abilityIncreases()).containsExactly(
                    new AbilityBonus("strength", 4), new AbilityBonus("constitution", 4));
            assertThat(response.getAbilityScoreMaximums().strength()).isEqualTo(24);
            assertThat(response.getAbilityScoreMaximums().constitution()).isEqualTo(24);
            // CON 16 counts for every level: 12 + 3, then 19 levels of 7 + 3
            assertThat(pc.getHpMax()).isEqualTo(205);

            CharacterResponse down = f.levelDown(pc);

            assertThat(pc.getStrength()).isEqualTo(16);
            assertThat(pc.getConstitution()).isEqualTo(12);
            assertThat(pc.getHpMax()).isEqualTo(157);
            assertThat(down.getAbilityScoreMaximums().strength()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("Ability Score Improvements")
    class AbilityScoreImprovements {

        @Test
        @DisplayName("+2 to CON raises the maximum for every level and current hit points with it; levelling down reverses both")
        void constitutionImprovement() {
            PlayerCharacter pc = fighter(4);
            assertThat(pc.getHpMax()).isEqualTo(32);

            f.improve(pc, "constitution", 2);

            assertThat(pc.getConstitution()).isEqualTo(14);
            assertThat(pc.getHpMax()).isEqualTo(36);
            assertThat(pc.getHpCurrent()).isEqualTo(36);
            assertThat(pc.getLevelHistory().get(3).choices().asi()).isEqualTo(new AbilityScoreImprovement("ability",
                    List.of(new AbilityBonus("constitution", 2)), null, null, null, null,
                    List.of(new AbilityBonus("constitution", 2))));

            f.levelDown(pc);

            assertThat(pc.getConstitution()).isEqualTo(12);
            assertThat(pc.getHpMax()).isEqualTo(25);
            assertThat(pc.getHpCurrent()).isEqualTo(25);
        }

        @Test
        @DisplayName("+1 to two different scores is an ASI too")
        void twoScores() {
            PlayerCharacter pc = fighter(4);

            f.improve(pc, "strength", 1, "DEX", 1);

            assertThat(pc.getStrength()).isEqualTo(17);
            assertThat(pc.getDexterity()).isEqualTo(15);
        }

        @Test
        @DisplayName("Anything but +2 to one score or +1 to two, or a score above its maximum, is a 400 and changes nothing")
        void invalidImprovementsAre400() {
            PlayerCharacter pc = fighter(4);
            pc.setStrength(19);

            assertThatThrownBy(() -> f.improve(pc, "strength", 1)).isInstanceOf(ValidationException.class)
                    .hasMessage("An Ability Score Improvement is +2 to one ability score or +1 to two");
            assertThatThrownBy(() -> f.improve(pc, "dexterity", 3)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> f.improve(pc, "dexterity", 1, "DEX", 1)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> f.improve(pc, "dexterity", 1, "wisdom", 1, "charisma", 1))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> f.improve(pc, "luck", 2)).isInstanceOf(ValidationException.class)
                    .hasMessage("'luck' is not an ability");
            assertThatThrownBy(() -> f.improve(pc, "strength", 2)).isInstanceOf(ValidationException.class)
                    .hasMessage("Strength is 19, and an Ability Score Improvement can't take it above 20");
            assertThat(pc.getStrength()).isEqualTo(19);
            assertThat(pc.getLevelHistory().get(3).choices().asi()).isNull();
        }

        @Test
        @DisplayName("An ASI no level owes is a 409")
        void notOwedIs409() {
            PlayerCharacter pc = fighter(3);

            assertThatThrownBy(() -> f.improve(pc, "strength", 2)).isInstanceOf(ConflictException.class)
                    .hasMessage("No Ability Score Improvement is owed");
        }

        @Test
        @DisplayName("An ASI goes on the latest level that owes one, so levelling down past that level reverses it")
        void asiGoesOnTheLevelThatGaveIt() {
            PlayerCharacter pc = fighter(6);

            f.improve(pc, "dexterity", 2);

            assertThat(pc.getLevelHistory().get(5).choices().asi()).isNotNull();
            assertThat(pc.getLevelHistory().get(3).choices().asi()).isNull();
            f.levelDown(pc);
            assertThat(pc.getDexterity()).isEqualTo(14);
            assertThat(pc.getInitiativeBonus()).isEqualTo(2);
        }

        @Test
        @DisplayName("An ASI recorded before R6, without the amount applied, is taken off at the amount requested")
        void legacyAsiFallsBackToRequested() {
            PlayerCharacter pc = fighter(4);
            List<LevelHistoryEntry> history = new ArrayList<>(pc.getLevelHistory());
            history.set(3, history.get(3).withChoices(LevelChoices.NONE.withAsi(new AbilityScoreImprovement("ability",
                    List.of(new AbilityBonus("wisdom", 2)), null, null, null, null, null))));
            pc.setLevelHistory(history);
            pc.setWisdom(12);

            f.levelDown(pc);

            assertThat(pc.getWisdom()).isEqualTo(10);
        }

        @Test
        @DisplayName("Tough taken as an ASI adds 2 for every level, and levelling down takes it away with its level")
        void toughForEveryLevel() {
            Feat tough = Feat.builder().id(UUID.randomUUID()).name("Tough").description("Hit points")
                    .effects("{\"hpPerLevel\": 2}").build();
            when(f.featRepository.findById(tough.getId())).thenReturn(Optional.of(tough));
            PlayerCharacter pc = fighter(4);
            ApplyChoicesRequest.AsiChoice asi = new ApplyChoicesRequest.AsiChoice();
            asi.setType("feat");
            asi.setFeatId(tough.getId());
            ApplyChoicesRequest request = new ApplyChoicesRequest();
            request.setAsi(asi);

            f.service.applyChoices(pc.getId(), request, f.userId);

            assertThat(pc.getHpMax()).isEqualTo(32 + 8);
            assertThat(pc.getHpCurrent()).isEqualTo(40);
            f.levelUp(pc, f.fighter);
            assertThat(pc.getHpMax()).isEqualTo(40 + 7 + 2);

            f.levelDown(pc);
            f.levelDown(pc);

            assertThat(pc.getHpMax()).isEqualTo(25);
            assertThat(pc.getFeatures()).extracting(Feature::name).doesNotContain("Tough");
        }
    }

    @Nested
    @DisplayName("Expertise")
    class Expertise {

        @Test
        @DisplayName("Expertise is recorded on the level that gives it, and levelling down removes the skills it added")
        void expertiseIsReversed() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.rogue, 5);
            pc.setSkillExpertises(List.of("Stealth"));
            LevelUpResponse response = f.levelUp(pc, f.rogue);
            assertThat(response.getPendingChoices().getExpertiseCount()).isEqualTo(2);
            ApplyChoicesRequest request = new ApplyChoicesRequest();
            request.setExpertiseSkills(List.of("Stealth", "Perception"));

            f.service.applyChoices(pc.getId(), request, f.userId);

            assertThat(pc.getSkillExpertises()).containsExactly("Stealth", "Perception");
            assertThat(pc.getLevelHistory().getLast().choices().expertise()).containsExactly("Perception");
            f.levelDown(pc);
            assertThat(pc.getSkillExpertises()).containsExactly("Stealth");
        }

        @Test
        @DisplayName("More skills than the level gives is a 400, and Expertise no level owes a 409")
        void invalidExpertise() {
            PlayerCharacter rogue = f.create(f.human, CharacterFixture.scores(), f.rogue, 6);
            ApplyChoicesRequest three = new ApplyChoicesRequest();
            three.setExpertiseSkills(List.of("Stealth", "Perception", "Athletics"));
            ApplyChoicesRequest two = new ApplyChoicesRequest();
            two.setExpertiseSkills(List.of("Stealth", "Perception"));

            assertThatThrownBy(() -> f.service.applyChoices(rogue.getId(), three, f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Level 6 Rogue gives Expertise in 2 skills, not 3");
            assertThatThrownBy(() -> f.service.applyChoices(fighter(2).getId(), two, f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("No Expertise is owed");
        }
    }
}
