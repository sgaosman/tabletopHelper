package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.character.dto.CharacterCreateRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.CharacterUpdateRequest;
import com.tabletophelper.character.dto.ShortRestRequest;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.identity.User;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Race;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Creating, updating, resting, deleting and reading characters. These were CharacterServiceBusinessTest's cases
 * 15.1 to 15.22, kept with their numbers, and its creation validation and access cases, run on the R6 derivation:
 * <ul>
 *   <li>15.1 to 15.9, creation, now also check the stats the server works out instead of taking them from the
 *       request: armour class 10 + DEX, initiative, speed from the race, and the Hill Dwarf's extra hit point a
 *       level in 15.9.</li>
 *   <li>15.10 works the spell save DC out through CharacterStats; 15.11 checks the constraints the PUT still has,
 *       now that the level isn't one of its fields.</li>
 *   <li>15.12 to 15.20, rests, and 15.21 and 15.22, deletion, are unchanged: workstream D moves rests to the
 *       server's own rules.</li>
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
    }

    @Nested
    @DisplayName("Character Update")
    class CharacterUpdate {

        @Test
        @DisplayName("15.10 Update ability scores recalculates spell save DC and spell attack bonus")
        void updateAbilityScoresRecalculates() {
            PlayerCharacter pc = f.create(f.human, new int[]{10, 10, 10, 16, 10, 10}, f.wizard, 1);
            assertThat(pc.getSpellSaveDc()).isEqualTo(13);

            CharacterUpdateRequest req = new CharacterUpdateRequest();
            req.setIntelligence(18);
            CharacterResponse response = f.service.updateCharacter(pc.getId(), req, f.userId);

            assertThat(response.getIntelligence()).isEqualTo(18);
            assertThat(response.getSpellSaveDc()).isEqualTo(14);
            assertThat(response.getSpellAttackBonus()).isEqualTo(6);
        }

        @Test
        @DisplayName("15.11 @Valid constraints reject invalid values (HP -5, STR 31)")
        void updateValidConstraintsRejectInvalid() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            CharacterUpdateRequest req = new CharacterUpdateRequest();
            req.setHpCurrent(-5);
            req.setStrength(31);

            Set<String> violatedPaths = new HashSet<>();
            for (ConstraintViolation<CharacterUpdateRequest> v : validator.validate(req)) {
                violatedPaths.add(v.getPropertyPath().toString());
            }
            assertThat(violatedPaths).contains("hpCurrent", "strength");
        }

        @Test
        @DisplayName("The PUT changes only what is spent: slots' used counts, remaining Hit Dice and pools' uses, each within its maximum")
        void putChangesOnlyWhatIsSpent() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 2, f.wizard, 1);
            CharacterUpdateRequest req = new CharacterUpdateRequest();
            req.setSpellSlots(Map.of("1", new SpellSlot(9, 1), "9", new SpellSlot(4, 0)));
            req.setHitDice(Map.of("Fighter", new HitDicePool(12, 20, 0), "Wizard", new HitDicePool(6, 1, 7)));
            req.setResourcePools(List.of(new ResourcePoolEntry("class:fighter-action-surge", "Action Surge",
                    "CLASS", null, 5, "5", -2, "shortRest", null, null, null, null, Map.of()),
                    ResourcePoolEntry.innate("Wish", 3, "RACE", "Genie")));
            req.setHpCurrent(99);

            CharacterResponse response = f.service.updateCharacter(pc.getId(), req, f.userId);

            assertThat(response.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(2, 1)));
            assertThat(response.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 2, 0))
                    .containsEntry("Wizard", new HitDicePool(6, 1, 1));
            assertThat(response.getResourcePools()).singleElement().satisfies(surge -> {
                assertThat(surge.poolId()).isEqualTo("class:fighter-action-surge");
                assertThat(surge.maxUses()).isEqualTo(1);
                assertThat(surge.currentUses()).isZero();
            });
            assertThat(response.getHpCurrent()).isEqualTo(response.getHpMax());
        }

        @Test
        @DisplayName("Joining a campaign through the PUT needs the owner to be a member")
        void campaignNeedsMembership() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);
            Campaign campaign = Campaign.builder().id(UUID.randomUUID()).name("Lost Mine")
                    .dm(User.builder().id(UUID.randomUUID()).build()).inviteCode("ABCD2345").build();
            when(f.campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
            CharacterUpdateRequest req = new CharacterUpdateRequest();
            req.setCampaignId(campaign.getId());

            assertThatThrownBy(() -> f.service.updateCharacter(pc.getId(), req, f.userId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("You are not a member of this campaign");
        }
    }

    // ========================================================================
    // Rests, unchanged until workstream D
    // ========================================================================

    private PlayerCharacter restingCharacter(String className, int level, int hpMax, int hpCurrent, int constitution,
                                             Map<String, HitDicePool> hitDice, Map<String, SpellSlot> spellSlots,
                                             List<FeatResourceEntry> featResources) {
        PlayerCharacter pc = PlayerCharacter.builder()
                .user(f.user).name("RestTestChar")
                .characterClass(className).level(level)
                .classRef(f.fighter)
                .strength(10).dexterity(10).constitution(constitution)
                .intelligence(10).wisdom(10).charisma(10)
                .hpMax(hpMax).hpCurrent(hpCurrent)
                .armourClass(10)
                .proficiencyBonus(CharacterService.proficiencyBonusForLevel(level))
                .speed(30)
                .hitDice(hitDice)
                .spellSlots(spellSlots)
                .featResources(featResources)
                .isActive(true)
                .build();
        return f.characterRepository.save(pc);
    }

    @Nested
    @DisplayName("Short Rest")
    class ShortRest {

        @Test
        @DisplayName("15.12 Spending 1 hit die heals avg die + CON mod, decreases remaining")
        void shortRestSpend1HitDie() {
            PlayerCharacter pc = restingCharacter("Fighter", 5, 45, 20, 14,
                    Map.of("Fighter", new HitDicePool(10, 5, 3)), null, null);
            ShortRestRequest req = new ShortRestRequest();
            req.setHitDiceToSpend(Map.of("Fighter", 1));

            CharacterResponse response = f.service.shortRest(pc.getId(), req, f.userId);

            assertThat(response.getHpCurrent()).isEqualTo(28);
            assertThat(response.getHitDice().get("Fighter").remaining()).isEqualTo(2);
        }

        @Test
        @DisplayName("15.13 Spending multiple hit dice from multiclass uses correct die sizes")
        void shortRestMultipleHitDiceMulticlass() {
            Map<String, HitDicePool> hitDice = new LinkedHashMap<>();
            hitDice.put("Fighter", new HitDicePool(10, 3, 2));
            hitDice.put("Wizard", new HitDicePool(6, 2, 1));
            PlayerCharacter pc = restingCharacter("Fighter / Wizard", 5, 35, 15, 12, hitDice, null, null);
            ShortRestRequest req = new ShortRestRequest();
            Map<String, Integer> spend = new LinkedHashMap<>();
            spend.put("Fighter", 1);
            spend.put("Wizard", 1);
            req.setHitDiceToSpend(spend);

            CharacterResponse response = f.service.shortRest(pc.getId(), req, f.userId);

            // (6+1) + (4+1)
            assertThat(response.getHpCurrent()).isEqualTo(27);
            assertThat(response.getHitDice().get("Fighter").remaining()).isEqualTo(1);
            assertThat(response.getHitDice().get("Wizard").remaining()).isEqualTo(0);
        }

        @Test
        @DisplayName("15.14 Cannot spend more hit dice than remaining")
        void shortRestCannotSpendMoreThanRemaining() {
            PlayerCharacter pc = restingCharacter("Fighter", 3, 30, 20, 14,
                    Map.of("Fighter", new HitDicePool(10, 3, 0)), null, null);
            ShortRestRequest req = new ShortRestRequest();
            req.setHitDiceToSpend(Map.of("Fighter", 1));

            assertThatThrownBy(() -> f.service.shortRest(pc.getId(), req, f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Not enough hit dice remaining");
        }

        @Test
        @DisplayName("15.15 Warlock pact slots reset on short rest, regular slots unchanged")
        void shortRestWarlockPactSlotsReset() {
            PlayerCharacter pc = restingCharacter("Warlock / Wizard", 5, 30, 30, 10,
                    Map.of("Warlock", new HitDicePool(8, 3, 3), "Wizard", new HitDicePool(6, 2, 2)),
                    Map.of("1", new SpellSlot(3, 1), "pact_3", new SpellSlot(2, 2)), null);

            CharacterResponse response = f.service.shortRest(pc.getId(), new ShortRestRequest(), f.userId);

            assertThat(response.getSpellSlots().get("pact_3")).isEqualTo(new SpellSlot(2, 0));
            assertThat(response.getSpellSlots().get("1")).isEqualTo(new SpellSlot(3, 1));
        }

        @Test
        @DisplayName("15.16 Feat resources with shortRestReset restored on short rest")
        void shortRestFeatResourcesRestored() {
            PlayerCharacter pc = restingCharacter("Fighter", 3, 30, 25, 14,
                    Map.of("Fighter", new HitDicePool(10, 3, 3)), null, List.of(
                            new FeatResourceEntry("Battle Master", "Superiority Dice", 4, 1, "shortRest"),
                            new FeatResourceEntry("Lucky", "Luck Points", 3, 0, "longRest")));

            CharacterResponse response = f.service.shortRest(pc.getId(), new ShortRestRequest(), f.userId);

            assertThat(response.getFeatResources()).extracting(FeatResourceEntry::name, FeatResourceEntry::currentUses)
                    .containsExactly(tuple("Superiority Dice", 4), tuple("Luck Points", 0));
        }
    }

    @Nested
    @DisplayName("Long Rest")
    class LongRest {

        @Test
        @DisplayName("15.17 Long rest restores full HP")
        void longRestFullHpRestored() {
            PlayerCharacter pc = restingCharacter("Fighter", 5, 45, 20, 14,
                    Map.of("Fighter", new HitDicePool(10, 5, 5)), null, null);

            CharacterResponse response = f.service.longRest(pc.getId(), f.userId);

            assertThat(response.getHpCurrent()).isEqualTo(45);
            assertThat(response.getHpMax()).isEqualTo(45);
        }

        @Test
        @DisplayName("15.18 Long rest restores all spell slots (regular and pact)")
        void longRestAllSpellSlotsRestored() {
            PlayerCharacter pc = restingCharacter("Wizard / Warlock", 7, 40, 25, 10,
                    Map.of("Wizard", new HitDicePool(6, 5, 3), "Warlock", new HitDicePool(8, 2, 2)),
                    Map.of("1", new SpellSlot(4, 4), "2", new SpellSlot(3, 3), "pact_3", new SpellSlot(2, 2)), null);

            CharacterResponse response = f.service.longRest(pc.getId(), f.userId);

            assertThat(response.getSpellSlots()).isEqualTo(Map.of(
                    "1", new SpellSlot(4, 0), "2", new SpellSlot(3, 0), "pact_3", new SpellSlot(2, 0)));
        }

        @Test
        @DisplayName("15.19 Long rest recovers half hit dice rounded down, minimum 1")
        void longRestHitDiceRecovery() {
            PlayerCharacter pc = restingCharacter("Fighter", 7, 60, 50, 14,
                    Map.of("Fighter", new HitDicePool(10, 7, 0)), null, null);

            CharacterResponse response = f.service.longRest(pc.getId(), f.userId);

            assertThat(response.getHitDice().get("Fighter")).isEqualTo(new HitDicePool(10, 7, 3));
        }

        @Test
        @DisplayName("15.20 Long rest restores all feat resources (both short and long rest)")
        void longRestAllFeatResourcesRestored() {
            PlayerCharacter pc = restingCharacter("Fighter", 5, 40, 30, 14,
                    Map.of("Fighter", new HitDicePool(10, 5, 2)), null, List.of(
                            new FeatResourceEntry("Lucky", "Luck Points", 3, 0, "longRest"),
                            new FeatResourceEntry("Battle Master", "Superiority Dice", 4, 2, "shortRest")));

            CharacterResponse response = f.service.longRest(pc.getId(), f.userId);

            assertThat(response.getFeatResources()).extracting(FeatResourceEntry::currentUses).containsExactly(3, 4);
        }

        @Test
        @DisplayName("A long rest resets the innate uses the sheet keeps as pools (D165)")
        void longRestResetsInnatePools() {
            PlayerCharacter pc = restingCharacter("Fighter", 5, 40, 30, 14,
                    Map.of("Fighter", new HitDicePool(10, 5, 5)), null, null);
            pc.setResourcePools(List.of(ResourcePoolEntry.innate("Faerie Fire", 1, "RACE", "Fairy")
                    .withCurrentUses(0)));

            CharacterResponse response = f.service.longRest(pc.getId(), f.userId);

            assertThat(response.getResourcePools()).singleElement()
                    .extracting(ResourcePoolEntry::currentUses).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Character Deletion")
    class CharacterDeletion {

        @Test
        @DisplayName("15.21 Soft delete sets isActive=false")
        void softDeleteSetsInactiveFalse() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.fighter, 5);
            when(f.participantRepository.existsByCharacter_IdAndEncounter_StatusIn(eq(pc.getId()), any()))
                    .thenReturn(false);

            f.service.deleteCharacter(pc.getId(), f.userId);

            assertThat(pc.getIsActive()).isFalse();
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
