package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.dto.CharacterCreateRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.character.state.SubclassChosen;
import com.tabletophelper.identity.User;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatAbilityIncrease;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Creating, deleting and reading characters. These were CharacterServiceBusinessTest's cases 15.1 to 15.22, kept with
 * their numbers, and its creation validation and access cases, run on the R6 derivation:
 * <ul>
 *   <li>15.1 to 15.9, creation, now also check the stats the server works out instead of taking them from the
 *       request: armour class 10 + DEX, initiative, speed from the race, and the Hill Dwarf's extra hit point a
 *       level in 15.9.</li>
 *   <li>15.10 changed ability scores through the PUT, which workstream D removed (D158): here an Intelligence
 *       increase from an Ability Score Improvement works the spell save DC and attack bonus out again.</li>
 *   <li>15.11, the PUT's constraints, and its other cases moved to CharacterSheetServiceTest with the commands that
 *       replaced it.</li>
 *   <li>15.12 to 15.20, rests, moved to RestServiceTest, with the server's own rest rules.</li>
 *   <li>15.21 and 15.22, deletion, are unchanged, and deletion, levelling and the choices a level brings now wait
 *       while the character is in an encounter (D166).</li>
 * </ul>
 */
class CharacterServiceTest {

    private final CharacterFixture f = new CharacterFixture();

    private final UUID otherUserId = UUID.randomUUID();
    private final Race elf = Race.builder().id(UUID.randomUUID()).name("Elf").speed("{\"walk\": 30}").build();
    private final Background soldier = Background.builder().id(UUID.randomUUID()).name("Soldier").build();

    @BeforeEach
    void knowTheReferenceData() {
        lenient().when(f.raceRepository.findById(elf.getId())).thenReturn(Optional.of(elf));
        lenient().when(f.backgroundRepository.findById(soldier.getId())).thenReturn(Optional.of(soldier));
    }

    private CharacterCreateRequest request(String name, CharacterClass characterClass, Race race) {
        CharacterCreateRequest req = new CharacterCreateRequest();
        req.setName(name);
        req.setClassId(characterClass.getId());
        req.setRaceId(race != null ? race.getId() : null);
        req.setStrength(16);
        req.setDexterity(14);
        req.setConstitution(14);
        req.setIntelligence(10);
        req.setWisdom(10);
        req.setCharisma(10);
        req.setLevel(1);
        return req;
    }

    private CharacterResponse create(CharacterCreateRequest request) {
        return f.service.createCharacter(request, f.userId);
    }

    @Nested
    @DisplayName("Character Creation")
    class CharacterCreation {

        @Test
        @DisplayName("15.1 Create single-class non-caster (Fighter): HP = d10 max + CON mod, proficiency 2, hit dice 1d10, no spell slots")
        void createFighterSingleClassNonCaster() {
            CharacterResponse response = create(request("TestFighter", f.fighter, f.human));

            assertThat(response.getHpMax()).isEqualTo(12);
            assertThat(response.getHpCurrent()).isEqualTo(12);
            assertThat(response.getProficiencyBonus()).isEqualTo(2);
            assertThat(response.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 1, 1)));
            assertThat(response.getSpellSlots()).isNull();
            assertThat(response.getSpellcastingAbility()).isNull();
            assertThat(response.getSpellcasting()).isEmpty();
            // Worked out by the server: 10 + DEX, the DEX modifier, and the race's walking speed
            assertThat(response.getArmourClass()).isEqualTo(12);
            assertThat(response.getInitiativeBonus()).isEqualTo(2);
            assertThat(response.getSpeed()).isEqualTo(30);
        }

        @Test
        @DisplayName("15.2 Create single-class full caster (Wizard): HP with d6, spell slots calculated, spellcasting ability = INT")
        void createWizardSingleClassFullCaster() {
            CharacterCreateRequest req = request("TestWizard", f.wizard, elf);
            req.setConstitution(12);
            req.setIntelligence(16);

            CharacterResponse response = create(req);

            assertThat(response.getHpMax()).isEqualTo(7);
            assertThat(response.getSpellcastingAbility()).isEqualTo("INT");
            assertThat(response.getSpellSaveDc()).isEqualTo(13);
            assertThat(response.getSpellAttackBonus()).isEqualTo(5);
            assertThat(response.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(2, 0)));
            assertThat(response.getSpellcasting()).singleElement().satisfies(wizard -> {
                assertThat(wizard.spellSaveDc()).isEqualTo(13);
                assertThat(wizard.cantripsKnown()).isEqualTo(3);
                assertThat(wizard.preparedSpells()).isEqualTo(4);
            });
        }

        @Test
        @DisplayName("15.3 Create single-class half caster (Paladin): spell slots at level 2")
        void createPaladinSingleClassHalfCaster() {
            CharacterCreateRequest req = request("TestPaladin", f.paladin, f.human);
            req.setLevel(2);
            req.setCharisma(16);

            CharacterResponse response = create(req);

            assertThat(response.getSpellcastingAbility()).isEqualTo("CHA");
            assertThat(response.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(2, 0)));
        }

        @Test
        @DisplayName("15.4 Create single-class pact caster (Warlock): pact slots only, no regular slots")
        void createWarlockSingleClassPactCaster() {
            CharacterCreateRequest req = request("TestWarlock", f.warlock, f.human);
            req.setCharisma(16);

            CharacterResponse response = create(req);

            assertThat(response.getSpellcastingAbility()).isEqualTo("CHA");
            assertThat(response.getSpellSlots()).isEqualTo(Map.of("pact_1", new SpellSlot(1, 0)));
            assertThat(response.getSpellcasting().getFirst().pactMagic()).isTrue();
        }

        @Test
        @DisplayName("15.5 Create multiclass at creation (Fighter 3 / Wizard 2): combined HP, spell slots, hit dice map")
        void createMulticlassFighterWizard() {
            CharacterCreateRequest req = request("TestMulti", f.fighter, f.human);
            req.setLevel(5);
            req.setIntelligence(13);
            req.setMulticlassClassEntries(List.of(
                    new ClassEntry(f.fighter.getId(), "Fighter", null, null, 3),
                    new ClassEntry(f.wizard.getId(), "Wizard", null, null, 2)));

            CharacterResponse response = create(req);

            // Fighter 10+2, 6+2, 6+2; Wizard 4+2, 4+2
            assertThat(response.getHpMax()).isEqualTo(40);
            assertThat(response.getLevelHistory()).extracting(LevelHistoryEntry::hpGained)
                    .containsExactly(12, 8, 8, 6, 6);
            assertThat(response.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(3, 0)));
            assertThat(response.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 3, 3))
                    .containsEntry("Wizard", new HitDicePool(6, 2, 2));
            assertThat(response.getMulticlassEntries()).hasSize(2);
            assertThat(response.getCharacterClass()).isEqualTo("Fighter / Wizard");
        }

        @Test
        @DisplayName("15.6 Ability scores saved correctly with standard array")
        void abilityScoresSavedCorrectly() {
            CharacterCreateRequest req = request("TestScores", f.fighter, f.human);
            req.setStrength(15);
            req.setDexterity(14);
            req.setConstitution(13);
            req.setIntelligence(12);
            req.setWisdom(10);
            req.setCharisma(8);
            req.setAbilityScoreMethod("Standard Array");

            CharacterResponse response = create(req);

            assertThat(response.getStrength()).isEqualTo(15);
            assertThat(response.getDexterity()).isEqualTo(14);
            assertThat(response.getConstitution()).isEqualTo(13);
            assertThat(response.getIntelligence()).isEqualTo(12);
            assertThat(response.getWisdom()).isEqualTo(10);
            assertThat(response.getCharisma()).isEqualTo(8);
            assertThat(response.getAbilityScoreMethod()).isEqualTo("Standard Array");
        }

        @Test
        @DisplayName("15.7 Race proficiencies merged with class proficiencies")
        void raceProficienciesMergedWithClass() {
            // The wizard merges these; the service stores what the request gives
            CharacterCreateRequest req = request("TestMerge", f.fighter, elf);
            req.setSkillProficiencies(List.of("Perception", "Athletics"));
            req.setSavingThrowProficiencies(List.of("STR", "CON"));
            req.setArmorProficiencies(List.of("Light", "Medium", "Heavy", "Shields"));
            req.setWeaponProficiencies(List.of("Simple", "Martial", "Longsword", "Shortsword", "Shortbow", "Longbow"));

            CharacterResponse response = create(req);

            assertThat(response.getSkillProficiencies()).containsOnlyOnce("Perception");
            assertThat(response.getSavingThrowProficiencies()).contains("STR", "CON");
            assertThat(response.getArmorProficiencies()).contains("Light", "Medium", "Heavy", "Shields");
            assertThat(response.getWeaponProficiencies()).contains("Simple", "Martial");
        }

        @Test
        @DisplayName("15.8 Background proficiencies merged with deduplication")
        void backgroundProficienciesMergedWithDeduplication() {
            CharacterCreateRequest req = request("TestBgMerge", f.fighter, f.human);
            req.setBackgroundId(soldier.getId());
            req.setSkillProficiencies(List.of("Athletics", "Intimidation"));
            req.setToolProficiencies(List.of("Playing card set", "Vehicles (land)"));
            req.setLanguageProficiencies(List.of("Common"));

            CharacterResponse response = create(req);

            assertThat(response.getSkillProficiencies()).containsOnlyOnce("Athletics").contains("Intimidation");
            assertThat(response.getToolProficiencies()).contains("Playing card set");
            assertThat(response.getLanguageProficiencies()).contains("Common");
            assertThat(response.getBackgroundName()).isEqualTo("Soldier");
        }

        @Test
        @DisplayName("15.9 Tasha's ability score reassignment records racial bonuses; a Hill Dwarf has 1 more hit point a level")
        void tashasAbilityScoreReassignment() {
            CharacterCreateRequest req = request("TestTasha", f.fighter, f.hillDwarf);
            req.setStrength(16);
            req.setConstitution(14);
            req.setWisdom(11);
            req.setRacialAbilityBonuses(List.of(new AbilityBonus("STR", 2), new AbilityBonus("WIS", 1)));

            CharacterResponse response = create(req);

            assertThat(response.getRacialAbilityBonuses())
                    .containsExactly(new AbilityBonus("STR", 2), new AbilityBonus("WIS", 1));
            assertThat(response.getStrength()).isEqualTo(16);
            assertThat(response.getConstitution()).isEqualTo(14);
            assertThat(response.getWisdom()).isEqualTo(11);
            assertThat(response.getHpMax()).isEqualTo(12 + 1);
            assertThat(response.getSpeed()).isEqualTo(25);
        }

        @Test
        @DisplayName("A Fairy barbarian's chosen ability gives the main spell numbers its race spells use (D164)")
        void chosenSpellcastingAbility() {
            CharacterCreateRequest req = request("Fae", f.barbarian, f.human);
            req.setWisdom(14);
            req.setSpellcastingAbility("Wisdom");

            CharacterResponse response = create(req);

            assertThat(response.getSpellcastingAbility()).isEqualTo("WIS");
            assertThat(response.getSpellSaveDc()).isEqualTo(12);
            assertThat(response.getSpellAttackBonus()).isEqualTo(4);
            assertThat(response.getSpellcasting()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The stored spellcasting ability (D159)")
    class StoredSpellcastingAbility {

        private final Feat feyTouched = Feat.builder().id(UUID.randomUUID()).name("Fey Touched")
                .description("Fey magic")
                .abilityScoreIncrease(FeatAbilityIncrease.parse("[{\"choose\": {\"from\": [\"int\", \"wis\", \"cha\"], \"amount\": 1}}]"))
                .build();

        private PlayerCharacter created(CharacterCreateRequest request) {
            return f.stored(create(request).getId());
        }

        // Fey Touched taken for the latest level that owes an Ability Score Improvement, choosing the ability
        private void takeFeyTouched(PlayerCharacter pc, String ability) {
            UUID mistyStep = UUID.randomUUID();
            when(f.spellRepository.findById(mistyStep)).thenReturn(Optional.of(
                    Spell.builder().id(mistyStep).name("Misty Step").level(2).build()));
            when(f.featRepository.findById(feyTouched.getId())).thenReturn(Optional.of(feyTouched));
            ApplyChoicesRequest.AsiChoice asi = new ApplyChoicesRequest.AsiChoice();
            asi.setType("feat");
            asi.setFeatId(feyTouched.getId());
            asi.setFeatAbility(ability);
            asi.setSpellIds(List.of(mistyStep));
            f.choose(pc, f.pending(pc, PendingChoice.Type.ASI), request -> request.setAsi(asi));
        }

        @Test
        @DisplayName("A Wizard stores INT, recorded on its 1st level, a Cleric WIS, and a Fighter none")
        void eachClasssOwn() {
            PlayerCharacter wizard = created(request("Pip", f.wizard, f.human));
            assertThat(wizard.getSpellcastingAbility()).isEqualTo("INT");
            assertThat(wizard.getLevelHistory().getFirst().choices().setsSpellcastingAbility()).isTrue();
            assertThat(created(request("Hild", f.cleric, f.human)).getSpellcastingAbility()).isEqualTo("WIS");
            assertThat(created(request("Bram", f.fighter, f.human)).getSpellcastingAbility()).isNull();
        }

        @Test
        @DisplayName("A Fighter 3 / Wizard 1 stores the Wizard's INT, which levelling down out of the Wizard clears")
        void dipsAbilityGoesWithIt() {
            CharacterCreateRequest req = request("Kel", f.fighter, f.human);
            req.setLevel(4);
            req.setIntelligence(13);
            req.setMulticlassClassEntries(List.of(new ClassEntry(f.fighter.getId(), null, null, null, 3),
                    new ClassEntry(f.wizard.getId(), null, null, null, 1)));
            PlayerCharacter pc = created(req);
            assertThat(pc.getSpellcastingAbility()).isEqualTo("INT");

            f.levelDown(pc);

            assertThat(pc.getSpellcastingAbility()).isNull();
            assertThat(pc.getSpellSaveDc()).isNull();
        }

        @Test
        @DisplayName("An Eldritch Knight 3 created with its subclass stores INT, which levelling down past the subclass clears")
        void eldritchKnightAtCreation() {
            CharacterCreateRequest req = request("Kel", f.fighter, f.human);
            req.setLevel(3);
            req.setSubclassId(f.eldritchKnight.getId());

            PlayerCharacter pc = created(req);

            assertThat(pc.getSpellcastingAbility()).isEqualTo("INT");
            assertThat(pc.getLevelHistory().get(2).choices().setsSpellcastingAbility()).isTrue();
            f.levelDown(pc);
            assertThat(pc.getSpellcastingAbility()).isNull();
        }

        @Test
        @DisplayName("A Fighter 3 stores none until its Eldritch Knight subclass is chosen, which stores INT")
        void eldritchKnightChosenLater() {
            CharacterCreateRequest req = request("Kel", f.fighter, f.human);
            req.setLevel(3);
            PlayerCharacter pc = created(req);
            assertThat(pc.getSpellcastingAbility()).isNull();

            f.choose(pc, f.pending(pc, PendingChoice.Type.SUBCLASS),
                    request -> request.setSubclassId(f.eldritchKnight.getId()));

            assertThat(pc.getSpellcastingAbility()).isEqualTo("INT");
            assertThat(pc.getLevelHistory().get(2).choices().subclass())
                    .isEqualTo(new SubclassChosen(f.fighter.getId(), f.eldritchKnight.getId(), "Eldritch Knight"));
            assertThat(pc.getLevelHistory().get(2).choices().setsSpellcastingAbility()).isTrue();
        }

        @Test
        @DisplayName("A Champion stores none, and no level records storing one")
        void championStoresNone() {
            CharacterCreateRequest req = request("Bram", f.fighter, f.human);
            req.setLevel(3);
            req.setSubclassId(f.champion.getId());

            PlayerCharacter pc = created(req);

            assertThat(pc.getSpellcastingAbility()).isNull();
            assertThat(pc.getLevelHistory().get(2).choices().setsSpellcastingAbility()).isNull();
        }

        @Test
        @DisplayName("The race's chosen ability wins over the class's, and an Eldritch Knight chosen later doesn't replace it")
        void raceChoiceWins() {
            CharacterCreateRequest wizard = request("Fae", f.wizard, f.human);
            wizard.setSpellcastingAbility("Charisma");
            assertThat(created(wizard).getSpellcastingAbility()).isEqualTo("CHA");

            CharacterCreateRequest fighter = request("Fae", f.fighter, f.human);
            fighter.setLevel(3);
            fighter.setSpellcastingAbility("WIS");
            PlayerCharacter pc = created(fighter);
            f.choose(pc, f.pending(pc, PendingChoice.Type.SUBCLASS),
                    request -> request.setSubclassId(f.eldritchKnight.getId()));

            assertThat(pc.getSpellcastingAbility()).isEqualTo("WIS");
            assertThat(pc.getLevelHistory().get(2).choices().setsSpellcastingAbility()).isNull();
            f.levelDown(pc);
            assertThat(pc.getSpellcastingAbility()).isEqualTo("WIS");
        }

        @Test
        @DisplayName("A later feat choosing WIS leaves an Eldritch Knight's INT, before and after levelling down past it")
        void laterFeatLeavesTheClasssAbility() {
            CharacterCreateRequest req = request("Kel", f.fighter, f.human);
            req.setLevel(4);
            req.setSubclassId(f.eldritchKnight.getId());
            PlayerCharacter pc = created(req);

            takeFeyTouched(pc, "WIS");

            assertThat(pc.getSpellcastingAbility()).isEqualTo("INT");
            assertThat(pc.getLevelHistory().get(3).choices().asi().appliedEffects().setsSpellcastingAbility())
                    .isFalse();
            assertThat(f.characterStats.mainSpellcastingAbility(pc)).isEqualTo("INT");
            f.levelDown(pc);
            assertThat(pc.getSpellcastingAbility()).isEqualTo("INT");
        }
    }

    @Nested
    @DisplayName("Character Creation Validation")
    class CharacterCreationValidation {

        @Test
        @DisplayName("Creating a character without a race is rejected (D152)")
        void createWithoutRaceRejected() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            CharacterCreateRequest req = request("NoRace", f.fighter, null);

            Set<String> violatedPaths = new HashSet<>();
            for (ConstraintViolation<CharacterCreateRequest> v : validator.validate(req)) {
                violatedPaths.add(v.getPropertyPath().toString());
            }
            assertThat(violatedPaths).contains("raceId");
        }

        @Test
        @DisplayName("A character with a race passes the race check")
        void createWithRaceAccepted() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            CharacterCreateRequest req = request("WithRace", f.fighter, f.human);

            assertThat(validator.validate(req))
                    .noneMatch(v -> v.getPropertyPath().toString().equals("raceId"));
        }

        @Test
        @DisplayName("A multiclass character's class levels must add up to its level, and a spellcasting ability be an ability")
        void inconsistentRequestsAre400() {
            CharacterCreateRequest levels = request("Bram", f.fighter, f.human);
            levels.setLevel(4);
            levels.setMulticlassClassEntries(List.of(new ClassEntry(f.fighter.getId(), null, null, null, 3),
                    new ClassEntry(f.wizard.getId(), null, null, null, 2)));
            CharacterCreateRequest ability = request("Bram", f.fighter, f.human);
            ability.setSpellcastingAbility("Luck");

            assertThatThrownBy(() -> create(levels)).isInstanceOf(ValidationException.class)
                    .hasMessage("The class levels add up to 5, but the character is level 4");
            assertThatThrownBy(() -> create(ability)).isInstanceOf(ValidationException.class)
                    .hasMessage("'Luck' is not an ability");
            verify(f.characterRepository, never()).save(any());
        }

        @Test
        @DisplayName("A multiclass character's subclass that doesn't exist is a 400, not dropped")
        void unknownSubclassIs400() {
            UUID missing = UUID.randomUUID();
            CharacterCreateRequest req = request("Bram", f.fighter, f.human);
            req.setLevel(5);
            req.setMulticlassClassEntries(List.of(new ClassEntry(f.fighter.getId(), null, missing, null, 3),
                    new ClassEntry(f.wizard.getId(), null, null, null, 2)));

            assertThatThrownBy(() -> create(req)).isInstanceOf(ValidationException.class)
                    .hasMessage("Subclass not found: " + missing);
            verify(f.characterRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Ability scores and spellcasting")
    class AbilityScoresAndSpellcasting {

        @Test
        @DisplayName("15.10 An Intelligence increase works the spell save DC and spell attack bonus out again")
        void intelligenceIncreaseRecalculatesSpellNumbers() {
            PlayerCharacter pc = f.create(f.human, new int[]{10, 10, 10, 16, 10, 10}, f.wizard, 4);
            assertThat(pc.getSpellSaveDc()).isEqualTo(13);

            CharacterResponse response = f.improve(pc, "intelligence", 2);

            assertThat(response.getIntelligence()).isEqualTo(18);
            assertThat(response.getSpellSaveDc()).isEqualTo(14);
            assertThat(response.getSpellAttackBonus()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("Encounters under way (D166)")
    class EncountersUnderWay {

        @Test
        @DisplayName("Levelling up, levelling down and the choices a level brings wait while the character is in an Active or Paused encounter")
        void levellingWaits() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 4);
            when(f.liveEncounters.includes(pc.getId())).thenReturn(true);

            assertThatThrownBy(() -> f.levelUp(pc, f.fighter))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria is in an encounter that is under way, so can't level up until it ends");
            assertThatThrownBy(() -> f.levelDown(pc))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria is in an encounter that is under way, so can't level down until it ends");
            assertThatThrownBy(() -> f.improve(pc, "strength", 2))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria is in an encounter that is under way, so can't make the choices its levels "
                            + "bring until it ends");
            assertThat(pc.getLevel()).isEqualTo(4);
            assertThat(pc.getStrength()).isEqualTo(16);
        }
    }

    @Nested
    @DisplayName("Character Deletion")
    class CharacterDeletion {

        @Test
        @DisplayName("15.21 Soft delete sets isActive=false")
        void softDeleteSetsInactiveFalse() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 5);

            f.service.deleteCharacter(pc.getId(), f.userId);

            assertThat(pc.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("A character in an encounter that hasn't been completed, a Preparing one included, can't be deleted")
        void cannotDeleteDuringAnEncounter() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 5);
            when(f.liveEncounters.includesUnfinished(pc.getId())).thenReturn(true);

            assertThatThrownBy(() -> f.service.deleteCharacter(pc.getId(), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot delete a character that is in an active encounter");
            assertThat(pc.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("15.22 Non-owner cannot delete character")
        void nonOwnerCannotDelete() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);
            clearInvocations(f.characterRepository);

            assertThatThrownBy(() -> f.service.deleteCharacter(pc.getId(), otherUserId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("You do not own this character");
            assertThat(pc.getIsActive()).isTrue();
            verify(f.characterRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Access (D155)")
    class Access {

        private final UUID dmUserId = UUID.randomUUID();
        private final Campaign campaign = Campaign.builder().id(UUID.randomUUID()).name("Lost Mine")
                .dm(User.builder().id(dmUserId).username("dm").email("dm@test.com").passwordHash("hash")
                        .displayName("DM").build())
                .inviteCode("ABCD2345").build();

        @BeforeEach
        void setUpCampaign() {
            lenient().when(f.campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        }

        private PlayerCharacter characterInCampaign() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);
            pc.setCampaign(campaign);
            return pc;
        }

        @Test
        @DisplayName("Another player in the same campaign cannot read the character")
        void otherPlayerCannotReadCharacter() {
            PlayerCharacter pc = characterInCampaign();

            assertThatThrownBy(() -> f.service.getCharacter(pc.getId(), otherUserId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("You do not have access to this character");
        }

        @Test
        @DisplayName("The campaign's DM can read a player's character")
        void campaignDmCanReadCharacter() {
            PlayerCharacter pc = characterInCampaign();

            assertThat(f.service.getCharacter(pc.getId(), dmUserId).getName()).isEqualTo("Aria");
        }

        @Test
        @DisplayName("An unknown character is a NotFoundException")
        void unknownCharacterIsNotFound() {
            assertThatThrownBy(() -> f.service.getCharacter(UUID.randomUUID(), f.userId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Character not found");
        }

        @Test
        @DisplayName("Only the DM can list the campaign's characters")
        void playerCannotListCampaignCharacters() {
            assertThatThrownBy(() -> f.service.getCharactersInCampaign(campaign.getId(), f.userId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Only the DM can perform this action");
            verify(f.characterRepository, never()).findByCampaignIdAndIsActiveTrue(any());
        }

        @Test
        @DisplayName("A character cannot be created in a campaign its owner has not joined")
        void cannotCreateCharacterInCampaignNotJoined() {
            CharacterCreateRequest request = new CharacterCreateRequest();
            request.setName("Aria");
            request.setCampaignId(campaign.getId());
            request.setRaceId(f.human.getId());

            assertThatThrownBy(() -> f.service.createCharacter(request, f.userId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("You are not a member of this campaign");
            verify(f.characterRepository, never()).save(any());
        }
    }
}
