package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.*;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.reference.*;
import com.tabletophelper.user.User;
import com.tabletophelper.user.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CharacterServiceBusinessTest {

    @Mock CharacterRepository characterRepository;
    @Mock UserRepository userRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignMemberRepository campaignMemberRepository;
    @Mock RaceRepository raceRepository;
    @Mock CharacterClassRepository characterClassRepository;
    @Mock SubclassRepository subclassRepository;
    @Mock BackgroundRepository backgroundRepository;
    @Mock EncounterParticipantRepository encounterParticipantRepository;
    @Mock FeatRepository featRepository;
    @Mock SpellRepository spellRepository;

    ObjectMapper objectMapper = new ObjectMapper();
    CharacterMapper characterMapper;
    CharacterJsonHelper jsonHelper;
    FeatEffectResolver featEffectResolver;
    CharacterService service;

    UUID userId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    User user;
    User otherUser;

    // Class IDs
    UUID fighterId = UUID.randomUUID();
    UUID wizardId = UUID.randomUUID();
    UUID paladinId = UUID.randomUUID();
    UUID warlockId = UUID.randomUUID();

    // Race IDs
    UUID humanId = UUID.randomUUID();
    UUID elfId = UUID.randomUUID();
    UUID dwarfId = UUID.randomUUID();

    // Background ID
    UUID soldierBgId = UUID.randomUUID();

    // Class entities
    CharacterClass fighter;
    CharacterClass wizard;
    CharacterClass paladin;
    CharacterClass warlock;

    // Race entities
    Race human;
    Race elf;
    Race dwarf;

    // Background entity
    Background soldierBg;

    @BeforeEach
    void setUp() {
        characterMapper = new CharacterMapper(objectMapper, subclassRepository);
        jsonHelper = new CharacterJsonHelper(objectMapper);
        featEffectResolver = new FeatEffectResolver(objectMapper, spellRepository);
        service = new CharacterService(
                characterRepository, userRepository, campaignRepository,
                campaignMemberRepository, raceRepository, characterClassRepository,
                subclassRepository, backgroundRepository, encounterParticipantRepository,
                featRepository, featEffectResolver, characterMapper, jsonHelper, objectMapper);

        user = User.builder().id(userId).username("testuser").email("test@test.com")
                .passwordHash("hash").displayName("Test User").build();
        otherUser = User.builder().id(otherUserId).username("otheruser").email("other@test.com")
                .passwordHash("hash").displayName("Other User").build();

        fighter = CharacterClass.builder()
                .id(fighterId).name("Fighter").hitDice(10).subclassLevel(3)
                .isSpellcaster(false).isPactMagic(false)
                .savingThrowProficiencies("[\"STR\",\"CON\"]")
                .armorProficiencies("[\"Light\",\"Medium\",\"Heavy\",\"Shields\"]")
                .weaponProficiencies("[\"Simple\",\"Martial\"]")
                .features("[{\"level\":1,\"name\":\"Fighting Style\",\"description\":\"Choose a style\"},{\"level\":1,\"name\":\"Second Wind\",\"description\":\"Heal on bonus action\"},{\"level\":2,\"name\":\"Action Surge\",\"description\":\"Extra action\"}]")
                .build();

        wizard = CharacterClass.builder()
                .id(wizardId).name("Wizard").hitDice(6).subclassLevel(2)
                .isSpellcaster(true).isPactMagic(false).spellcastingAbility("INT")
                .savingThrowProficiencies("[\"INT\",\"WIS\"]")
                .features("[{\"level\":1,\"name\":\"Arcane Recovery\",\"description\":\"Recover spell slots\"},{\"level\":2,\"name\":\"Arcane Tradition\",\"description\":\"Choose a tradition\"}]")
                .build();

        paladin = CharacterClass.builder()
                .id(paladinId).name("Paladin").hitDice(10).subclassLevel(3)
                .isSpellcaster(true).isPactMagic(false).spellcastingAbility("CHA")
                .savingThrowProficiencies("[\"WIS\",\"CHA\"]")
                .features("[{\"level\":1,\"name\":\"Divine Sense\",\"description\":\"Sense celestials\"},{\"level\":1,\"name\":\"Lay on Hands\",\"description\":\"Healing pool\"},{\"level\":2,\"name\":\"Divine Smite\",\"description\":\"Smite on hit\"}]")
                .build();

        warlock = CharacterClass.builder()
                .id(warlockId).name("Warlock").hitDice(8).subclassLevel(1)
                .isSpellcaster(true).isPactMagic(true).spellcastingAbility("CHA")
                .savingThrowProficiencies("[\"WIS\",\"CHA\"]")
                .features("[{\"level\":1,\"name\":\"Pact Magic\",\"description\":\"Cast spells using pact slots\"},{\"level\":1,\"name\":\"Otherworldly Patron\",\"description\":\"Choose a patron\"}]")
                .build();

        human = Race.builder()
                .id(humanId).name("Human").size("Medium").speed("30")
                .abilityScoreBonuses("{\"STR\":1,\"DEX\":1,\"CON\":1,\"INT\":1,\"WIS\":1,\"CHA\":1}")
                .build();

        elf = Race.builder()
                .id(elfId).name("Elf").size("Medium").speed("30")
                .abilityScoreBonuses("{\"DEX\":2}")
                .proficiencies("{\"skills\":[\"Perception\"],\"weapons\":[\"Longsword\",\"Shortsword\",\"Shortbow\",\"Longbow\"]}")
                .build();

        dwarf = Race.builder()
                .id(dwarfId).name("Hill Dwarf").size("Medium").speed("25")
                .abilityScoreBonuses("{\"CON\":2,\"WIS\":1}")
                .build();

        soldierBg = Background.builder()
                .id(soldierBgId).name("Soldier")
                .build();
    }

    private void mockSaveReturnsArg() {
        when(characterRepository.save(any(PlayerCharacter.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CharacterCreateRequest baseCreateRequest(String name, UUID classId, UUID raceId) {
        CharacterCreateRequest req = new CharacterCreateRequest();
        req.setName(name);
        req.setClassId(classId);
        req.setRaceId(raceId);
        req.setStrength(16);
        req.setDexterity(14);
        req.setConstitution(14);
        req.setIntelligence(10);
        req.setWisdom(10);
        req.setCharisma(10);
        return req;
    }

    // ========================================================================
    // Character Creation (9 tests)
    // ========================================================================

    @Nested
    @DisplayName("Character Creation")
    class CharacterCreation {

        @Test
        @DisplayName("15.1 Create single-class non-caster (Fighter): HP = d10 max + CON mod, proficiency 2, hit dice 1d10, no spell slots")
        void createFighterSingleClassNonCaster() {
            // Given: Human Fighter, level 1, STR 16, DEX 14, CON 14
            CharacterCreateRequest req = baseCreateRequest("TestFighter", fighterId, humanId);
            req.setLevel(1);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(humanId)).thenReturn(Optional.of(human));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then: HP max = 10 + 2 = 12, proficiency = 2, hit dice = 1d10, no spell slots
            assertThat(response.getHpMax()).isEqualTo(12);
            assertThat(response.getHpCurrent()).isEqualTo(12);
            assertThat(response.getProficiencyBonus()).isEqualTo(2);
            assertThat(response.getHitDiceTotal()).isEqualTo("1d10");
            assertThat(response.getSpellSlots()).isNull();
            assertThat(response.getSpellcastingAbility()).isNull();
        }

        @Test
        @DisplayName("15.2 Create single-class full caster (Wizard): HP with d6, spell slots calculated, spellcasting ability = INT")
        void createWizardSingleClassFullCaster() {
            // Given: Elf Wizard, level 1, INT 16 (+3), CON 12 (+1)
            CharacterCreateRequest req = baseCreateRequest("TestWizard", wizardId, elfId);
            req.setLevel(1);
            req.setConstitution(12);
            req.setIntelligence(16);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(elfId)).thenReturn(Optional.of(elf));
            when(characterClassRepository.findById(wizardId)).thenReturn(Optional.of(wizard));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then: HP = 6 + 1 = 7, 2 first-level slots, INT spellcasting, DC 13, attack +5
            assertThat(response.getHpMax()).isEqualTo(7);
            assertThat(response.getSpellcastingAbility()).isEqualTo("INT");
            assertThat(response.getSpellSaveDc()).isEqualTo(13); // 8 + 2 + 3
            assertThat(response.getSpellAttackBonus()).isEqualTo(5); // 2 + 3

            // Parse spell slots to verify 2 first-level slots
            Map<String, Map<String, Integer>> slots = parseSpellSlots(response.getSpellSlots());
            assertThat(slots).containsKey("1");
            assertThat(slots.get("1").get("total")).isEqualTo(2);
        }

        @Test
        @DisplayName("15.3 Create single-class half caster (Paladin): spell slots at level 2")
        void createPaladinSingleClassHalfCaster() {
            // Given: Paladin level 2, CHA 16 (+3), CON 14 (+2)
            CharacterCreateRequest req = baseCreateRequest("TestPaladin", paladinId, humanId);
            req.setLevel(2);
            req.setCharisma(16);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(humanId)).thenReturn(Optional.of(human));
            when(characterClassRepository.findById(paladinId)).thenReturn(Optional.of(paladin));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then: Level 2 Paladin -> caster level floor(2/2) = 1 -> 2 first-level slots
            assertThat(response.getSpellcastingAbility()).isEqualTo("CHA");

            Map<String, Map<String, Integer>> slots = parseSpellSlots(response.getSpellSlots());
            assertThat(slots).containsKey("1");
            assertThat(slots.get("1").get("total")).isEqualTo(2);
        }

        @Test
        @DisplayName("15.4 Create single-class pact caster (Warlock): pact slots only, no regular slots")
        void createWarlockSingleClassPactCaster() {
            // Given: Warlock level 1, CHA 16
            CharacterCreateRequest req = baseCreateRequest("TestWarlock", warlockId, humanId);
            req.setLevel(1);
            req.setCharisma(16);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(humanId)).thenReturn(Optional.of(human));
            when(characterClassRepository.findById(warlockId)).thenReturn(Optional.of(warlock));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then: pact_1 slot with 1 total, no regular slots
            assertThat(response.getSpellcastingAbility()).isEqualTo("CHA");

            Map<String, Map<String, Integer>> slots = parseSpellSlots(response.getSpellSlots());
            assertThat(slots).containsKey("pact_1");
            assertThat(slots.get("pact_1").get("total")).isEqualTo(1);
            // No regular slot keys (only pact_ prefixed)
            boolean hasRegularSlots = slots.keySet().stream().anyMatch(k -> !k.startsWith("pact_"));
            assertThat(hasRegularSlots).isFalse();
        }

        @Test
        @DisplayName("15.5 Create multiclass at creation (Fighter 3 / Wizard 2): combined HP, spell slots, hit dice map")
        void createMulticlassFighterWizard() throws Exception {
            // Given: Fighter 3 / Wizard 2, CON 14 (+2)
            CharacterCreateRequest req = baseCreateRequest("TestMulti", fighterId, humanId);
            req.setLevel(5);

            String mcEntries = objectMapper.writeValueAsString(List.of(
                    Map.of("classId", fighterId.toString(), "className", "Fighter", "level", 3),
                    Map.of("classId", wizardId.toString(), "className", "Wizard", "level", 2)
            ));
            req.setMulticlassClassEntries(mcEntries);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(humanId)).thenReturn(Optional.of(human));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            when(characterClassRepository.findById(wizardId)).thenReturn(Optional.of(wizard));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then:
            // HP: Fighter L1 = 10+2=12, L2 = 6+2=8, L3 = 6+2=8
            //     Wizard L1 = 4+2=6, L2 = 4+2=6
            //     Total = 12 + 8 + 8 + 6 + 6 = 40
            assertThat(response.getHpMax()).isEqualTo(40);

            // Spell slots from Wizard caster level 2 -> 3 first-level slots
            Map<String, Map<String, Integer>> slots = parseSpellSlots(response.getSpellSlots());
            assertThat(slots).containsKey("1");
            assertThat(slots.get("1").get("total")).isEqualTo(3);

            // Hit dice map should have both classes
            Map<String, HitDiceEntry> hdMap = objectMapper.readValue(
                    response.getHitDiceMap(), new TypeReference<Map<String, HitDiceEntry>>() {});
            assertThat(hdMap).containsKeys("Fighter", "Wizard");
            assertThat(hdMap.get("Fighter").total()).isEqualTo(3);
            assertThat(hdMap.get("Fighter").faces()).isEqualTo(10);
            assertThat(hdMap.get("Wizard").total()).isEqualTo(2);
            assertThat(hdMap.get("Wizard").faces()).isEqualTo(6);

            // Multiclass entries JSON has both classes
            List<MulticlassEntry> mcParsed = objectMapper.readValue(
                    response.getMulticlassEntries(), new TypeReference<>() {});
            assertThat(mcParsed).hasSize(2);
        }

        @Test
        @DisplayName("15.6 Ability scores saved correctly with standard array")
        void abilityScoresSavedCorrectly() {
            // Given: standard array scores STR 15, DEX 14, CON 13, INT 12, WIS 10, CHA 8
            CharacterCreateRequest req = baseCreateRequest("TestScores", fighterId, humanId);
            req.setStrength(15);
            req.setDexterity(14);
            req.setConstitution(13);
            req.setIntelligence(12);
            req.setWisdom(10);
            req.setCharisma(8);
            req.setAbilityScoreMethod("Standard Array");
            req.setLevel(1);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(humanId)).thenReturn(Optional.of(human));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then: all six ability scores match input exactly
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
            // Given: Elf (Perception, longsword, longbow) Fighter (all armor, all weapons, STR/CON saves)
            // The frontend merges these; the service stores whatever the request provides
            CharacterCreateRequest req = baseCreateRequest("TestMerge", fighterId, elfId);
            req.setLevel(1);
            req.setSkillProficiencies("[\"Perception\",\"Athletics\"]");
            req.setSavingThrowProficiencies("[\"STR\",\"CON\"]");
            req.setArmorProficiencies("[\"Light\",\"Medium\",\"Heavy\",\"Shields\"]");
            req.setWeaponProficiencies("[\"Simple\",\"Martial\",\"Longsword\",\"Shortsword\",\"Shortbow\",\"Longbow\"]");

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(elfId)).thenReturn(Optional.of(elf));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then: all proficiencies stored correctly
            assertThat(response.getSkillProficiencies()).contains("Perception");
            assertThat(response.getSavingThrowProficiencies()).contains("STR", "CON");
            assertThat(response.getArmorProficiencies()).contains("Light", "Medium", "Heavy", "Shields");
            assertThat(response.getWeaponProficiencies()).contains("Simple", "Martial");

            // Verify no duplicate Perception
            List<String> skills = objectMapper.convertValue(
                    parseJsonArray(response.getSkillProficiencies()), new TypeReference<>() {});
            long perceptionCount = skills.stream().filter("Perception"::equals).count();
            assertThat(perceptionCount).isEqualTo(1);
        }

        @Test
        @DisplayName("15.8 Background proficiencies merged with deduplication")
        void backgroundProficienciesMergedWithDeduplication() {
            // Given: Fighter (Athletics) + Soldier background (Athletics, Intimidation)
            // The merged result should have Athletics only once
            CharacterCreateRequest req = baseCreateRequest("TestBgMerge", fighterId, humanId);
            req.setLevel(1);
            req.setBackgroundId(soldierBgId);
            req.setSkillProficiencies("[\"Athletics\",\"Intimidation\"]");
            req.setToolProficiencies("[\"Playing card set\",\"Vehicles (land)\"]");
            req.setLanguageProficiencies("[\"Common\"]");

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(humanId)).thenReturn(Optional.of(human));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            when(backgroundRepository.findById(soldierBgId)).thenReturn(Optional.of(soldierBg));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then: Athletics appears once, Intimidation present, tool & language proficiencies stored
            List<String> skills = objectMapper.convertValue(
                    parseJsonArray(response.getSkillProficiencies()), new TypeReference<>() {});
            long athleticsCount = skills.stream().filter("Athletics"::equals).count();
            assertThat(athleticsCount).isEqualTo(1);
            assertThat(skills).contains("Intimidation");
            assertThat(response.getToolProficiencies()).contains("Playing card set");
            assertThat(response.getLanguageProficiencies()).contains("Common");
            assertThat(response.getBackgroundName()).isEqualTo("Soldier");
        }

        @Test
        @DisplayName("15.9 Tasha's ability score reassignment records racial bonuses")
        void tashasAbilityScoreReassignment() {
            // Given: Hill Dwarf (normally +2 CON, +1 WIS) with Tasha's reassignment of +2 to STR
            CharacterCreateRequest req = baseCreateRequest("TestTasha", fighterId, dwarfId);
            req.setLevel(1);
            req.setStrength(16); // includes the +2 from Tasha's
            req.setConstitution(14); // no longer gets the +2
            req.setWisdom(11); // still gets +1
            req.setRacialAbilityBonuses("{\"STR\":2,\"WIS\":1}");

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(raceRepository.findById(dwarfId)).thenReturn(Optional.of(dwarf));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.createCharacter(req, userId);

            // Then: the reassignment is recorded, scores match
            assertThat(response.getRacialAbilityBonuses()).isEqualTo("{\"STR\":2,\"WIS\":1}");
            assertThat(response.getStrength()).isEqualTo(16);
            assertThat(response.getConstitution()).isEqualTo(14); // no racial bonus applied
            assertThat(response.getWisdom()).isEqualTo(11);
        }
    }

    // ========================================================================
    // Character Update (2 tests)
    // ========================================================================

    @Nested
    @DisplayName("Character Update")
    class CharacterUpdate {

        @Test
        @DisplayName("15.10 Update ability scores recalculates spell save DC and spell attack bonus")
        void updateAbilityScoresRecalculates() {
            // Given: Wizard with INT 16, proficiency +2, spell save DC 13, spell attack +5
            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID()).user(user).name("UpdateWizard")
                    .characterClass("Wizard").level(1)
                    .classRef(wizard)
                    .strength(10).dexterity(10).constitution(10)
                    .intelligence(16).wisdom(10).charisma(10)
                    .hpMax(7).hpCurrent(7).armourClass(10)
                    .proficiencyBonus(2).speed(30)
                    .spellcastingAbility("INT")
                    .spellSaveDc(13).spellAttackBonus(5)
                    .isActive(true)
                    .build();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            // When: INT updated to 18 (mod +4)
            CharacterUpdateRequest req = new CharacterUpdateRequest();
            req.setIntelligence(18);

            CharacterResponse response = service.updateCharacter(pc.getId(), req, userId);

            // Then: spell save DC = 8 + 2 + 4 = 14, spell attack = 2 + 4 = 6
            assertThat(response.getIntelligence()).isEqualTo(18);
            assertThat(response.getSpellSaveDc()).isEqualTo(14);
            assertThat(response.getSpellAttackBonus()).isEqualTo(6);
        }

        @Test
        @DisplayName("15.11 @Valid constraints reject invalid values (level 0, HP -5, STR 31)")
        void updateValidConstraintsRejectInvalid() {
            // Test DTO validation annotations directly using Jakarta Validator
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

            CharacterUpdateRequest req = new CharacterUpdateRequest();
            req.setLevel(0);       // violates @Min(1)
            req.setHpCurrent(-5);  // violates @Min(0)
            req.setStrength(31);   // violates @Max(30)

            Set<ConstraintViolation<CharacterUpdateRequest>> violations = validator.validate(req);

            // Should have at least 3 violations (one per invalid field)
            assertThat(violations).hasSizeGreaterThanOrEqualTo(3);

            Set<String> violatedPaths = new HashSet<>();
            for (ConstraintViolation<CharacterUpdateRequest> v : violations) {
                violatedPaths.add(v.getPropertyPath().toString());
            }
            assertThat(violatedPaths).contains("level", "hpCurrent", "strength");
        }
    }

    // ========================================================================
    // Short Rest (5 tests)
    // ========================================================================

    @Nested
    @DisplayName("Short Rest")
    class ShortRest {

        @Test
        @DisplayName("15.12 Spending 1 hit die heals avg die + CON mod, decreases remaining")
        void shortRestSpend1HitDie() throws Exception {
            // Given: Fighter with 20/45 HP, CON 14 (+2), 3/5 hit dice remaining (d10)
            PlayerCharacter pc = buildCharacterForRest("Fighter", 5, 10,
                    45, 20, 14,
                    Map.of("Fighter", new HitDiceEntry(5, 3, 10)),
                    null, null);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            // When: spend 1 hit die
            ShortRestRequest req = new ShortRestRequest();
            req.setHitDiceToSpend(Map.of("Fighter", 1));

            CharacterResponse response = service.shortRest(pc.getId(), req, userId);

            // Then: heal = d10 avg (6) + CON mod (2) = 8, HP = 20 + 8 = 28
            assertThat(response.getHpCurrent()).isEqualTo(28);

            // Remaining hit dice decreased to 2
            Map<String, HitDiceEntry> hdMap = objectMapper.readValue(
                    response.getHitDiceMap(), new TypeReference<>() {});
            assertThat(hdMap.get("Fighter").remaining()).isEqualTo(2);
        }

        @Test
        @DisplayName("15.13 Spending multiple hit dice from multiclass uses correct die sizes")
        void shortRestMultipleHitDiceMulticlass() throws Exception {
            // Given: Fighter 3 / Wizard 2 with 15/35 HP, CON 12 (+1)
            // Fighter d10 remaining: 2, Wizard d6 remaining: 1
            Map<String, HitDiceEntry> hdMapData = new LinkedHashMap<>();
            hdMapData.put("Fighter", new HitDiceEntry(3, 2, 10));
            hdMapData.put("Wizard", new HitDiceEntry(2, 1, 6));

            PlayerCharacter pc = buildCharacterForRest("Fighter / Wizard", 5, 10,
                    35, 15, 12, hdMapData, null, null);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            // When: spend 1 Fighter d10 and 1 Wizard d6
            ShortRestRequest req = new ShortRestRequest();
            Map<String, Integer> spend = new LinkedHashMap<>();
            spend.put("Fighter", 1);
            spend.put("Wizard", 1);
            req.setHitDiceToSpend(spend);

            CharacterResponse response = service.shortRest(pc.getId(), req, userId);

            // Then: heal = (6+1) + (4+1) = 12, HP = 15 + 12 = 27
            assertThat(response.getHpCurrent()).isEqualTo(27);

            Map<String, HitDiceEntry> hdMap = objectMapper.readValue(
                    response.getHitDiceMap(), new TypeReference<>() {});
            assertThat(hdMap.get("Fighter").remaining()).isEqualTo(1);
            assertThat(hdMap.get("Wizard").remaining()).isEqualTo(0);
        }

        @Test
        @DisplayName("15.14 Cannot spend more hit dice than remaining")
        void shortRestCannotSpendMoreThanRemaining() throws Exception {
            // Given: Fighter with 0/3 hit dice remaining
            PlayerCharacter pc = buildCharacterForRest("Fighter", 3, 10,
                    30, 20, 14,
                    Map.of("Fighter", new HitDiceEntry(3, 0, 10)),
                    null, null);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

            // When/Then: spending 1 hit die should fail
            ShortRestRequest req = new ShortRestRequest();
            req.setHitDiceToSpend(Map.of("Fighter", 1));

            assertThatThrownBy(() -> service.shortRest(pc.getId(), req, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not enough hit dice remaining");
        }

        @Test
        @DisplayName("15.15 Warlock pact slots reset on short rest, regular slots unchanged")
        void shortRestWarlockPactSlotsReset() throws Exception {
            // Given: Warlock/Wizard multiclass with 0/2 pact slots and 3/3 regular slots
            String spellSlots = objectMapper.writeValueAsString(Map.of(
                    "1", Map.of("total", 3, "used", 0),
                    "pact_3", Map.of("total", 2, "used", 2)
            ));

            PlayerCharacter pc = buildCharacterForRest("Warlock / Wizard", 5, 8,
                    30, 30, 10,
                    Map.of("Warlock", new HitDiceEntry(3, 3, 8), "Wizard", new HitDiceEntry(2, 2, 6)),
                    spellSlots, null);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            // When: short rest with no hit dice spent
            ShortRestRequest req = new ShortRestRequest();

            CharacterResponse response = service.shortRest(pc.getId(), req, userId);

            // Then: pact slots reset to 2/2, regular slots still 3/3
            Map<String, Map<String, Integer>> slots = parseSpellSlots(response.getSpellSlots());
            assertThat(slots.get("pact_3").get("used")).isEqualTo(0);
            assertThat(slots.get("pact_3").get("total")).isEqualTo(2);
            assertThat(slots.get("1").get("used")).isEqualTo(0); // was already 0
            assertThat(slots.get("1").get("total")).isEqualTo(3);
        }

        @Test
        @DisplayName("15.16 Feat resources with shortRestReset restored on short rest")
        void shortRestFeatResourcesRestored() throws Exception {
            // Given: character with "Superiority Dice" (4 max, 1 remaining, shortRest)
            // and "Luck Points" (3 max, 0 remaining, longRest) -- should NOT reset
            String featResources = objectMapper.writeValueAsString(List.of(
                    new FeatResourceEntry("Battle Master", "Superiority Dice", 4, 1, "shortRest"),
                    new FeatResourceEntry("Lucky", "Luck Points", 3, 0, "longRest")
            ));

            PlayerCharacter pc = buildCharacterForRest("Fighter", 3, 10,
                    30, 25, 14,
                    Map.of("Fighter", new HitDiceEntry(3, 3, 10)),
                    null, featResources);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            ShortRestRequest req = new ShortRestRequest();
            CharacterResponse response = service.shortRest(pc.getId(), req, userId);

            // Then: Superiority Dice reset to 4/4, Luck Points still 0/3
            List<FeatResourceEntry> resources = objectMapper.readValue(
                    response.getFeatResources(), new TypeReference<>() {});
            FeatResourceEntry superiorityDice = resources.stream()
                    .filter(r -> "Superiority Dice".equals(r.name())).findFirst().orElseThrow();
            FeatResourceEntry luckPoints = resources.stream()
                    .filter(r -> "Luck Points".equals(r.name())).findFirst().orElseThrow();

            assertThat(superiorityDice.currentUses()).isEqualTo(4); // reset to max
            assertThat(luckPoints.currentUses()).isEqualTo(0);      // NOT reset (longRest only)
        }
    }

    // ========================================================================
    // Long Rest (4 tests)
    // ========================================================================

    @Nested
    @DisplayName("Long Rest")
    class LongRest {

        @Test
        @DisplayName("15.17 Long rest restores full HP")
        void longRestFullHpRestored() throws Exception {
            // Given: Fighter with 20/45 HP
            PlayerCharacter pc = buildCharacterForRest("Fighter", 5, 10,
                    45, 20, 14,
                    Map.of("Fighter", new HitDiceEntry(5, 5, 10)),
                    null, null);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.longRest(pc.getId(), userId);

            // Then: HP restored to 45/45
            assertThat(response.getHpCurrent()).isEqualTo(45);
            assertThat(response.getHpMax()).isEqualTo(45);
        }

        @Test
        @DisplayName("15.18 Long rest restores all spell slots (regular and pact)")
        void longRestAllSpellSlotsRestored() throws Exception {
            // Given: Wizard/Warlock with used regular and pact slots
            String spellSlots = objectMapper.writeValueAsString(Map.of(
                    "1", Map.of("total", 4, "used", 4),
                    "2", Map.of("total", 3, "used", 3),
                    "pact_3", Map.of("total", 2, "used", 2)
            ));

            PlayerCharacter pc = buildCharacterForRest("Wizard / Warlock", 7, 6,
                    40, 25, 10,
                    Map.of("Wizard", new HitDiceEntry(5, 3, 6), "Warlock", new HitDiceEntry(2, 2, 8)),
                    spellSlots, null);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.longRest(pc.getId(), userId);

            // Then: all slots reset to used=0
            Map<String, Map<String, Integer>> slots = parseSpellSlots(response.getSpellSlots());
            assertThat(slots.get("1").get("used")).isEqualTo(0);
            assertThat(slots.get("1").get("total")).isEqualTo(4);
            assertThat(slots.get("2").get("used")).isEqualTo(0);
            assertThat(slots.get("2").get("total")).isEqualTo(3);
            assertThat(slots.get("pact_3").get("used")).isEqualTo(0);
            assertThat(slots.get("pact_3").get("total")).isEqualTo(2);
        }

        @Test
        @DisplayName("15.19 Long rest recovers half hit dice rounded down, minimum 1")
        void longRestHitDiceRecovery() throws Exception {
            // Given: level 7 character with 0/7 hit dice remaining
            PlayerCharacter pc = buildCharacterForRest("Fighter", 7, 10,
                    60, 50, 14,
                    Map.of("Fighter", new HitDiceEntry(7, 0, 10)),
                    null, null);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.longRest(pc.getId(), userId);

            // Then: regain floor(7/2) = 3 hit dice, so remaining = 0 + 3 = 3
            Map<String, HitDiceEntry> hdMap = objectMapper.readValue(
                    response.getHitDiceMap(), new TypeReference<>() {});
            assertThat(hdMap.get("Fighter").remaining()).isEqualTo(3);
            assertThat(hdMap.get("Fighter").total()).isEqualTo(7);
        }

        @Test
        @DisplayName("15.20 Long rest restores all feat resources (both short and long rest)")
        void longRestAllFeatResourcesRestored() throws Exception {
            // Given: "Luck Points" (0/3, longRest) and "Superiority Dice" (2/4, shortRest)
            String featResources = objectMapper.writeValueAsString(List.of(
                    new FeatResourceEntry("Lucky", "Luck Points", 3, 0, "longRest"),
                    new FeatResourceEntry("Battle Master", "Superiority Dice", 4, 2, "shortRest")
            ));

            PlayerCharacter pc = buildCharacterForRest("Fighter", 5, 10,
                    40, 30, 14,
                    Map.of("Fighter", new HitDiceEntry(5, 2, 10)),
                    null, featResources);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            mockSaveReturnsArg();

            // When
            CharacterResponse response = service.longRest(pc.getId(), userId);

            // Then: both resources restored to max
            List<FeatResourceEntry> resources = objectMapper.readValue(
                    response.getFeatResources(), new TypeReference<>() {});
            FeatResourceEntry luckPoints = resources.stream()
                    .filter(r -> "Luck Points".equals(r.name())).findFirst().orElseThrow();
            FeatResourceEntry superiorityDice = resources.stream()
                    .filter(r -> "Superiority Dice".equals(r.name())).findFirst().orElseThrow();

            assertThat(luckPoints.currentUses()).isEqualTo(3);       // longRest restored
            assertThat(superiorityDice.currentUses()).isEqualTo(4);  // shortRest also restored on long rest
        }
    }

    // ========================================================================
    // Character Deletion (2 tests)
    // ========================================================================

    @Nested
    @DisplayName("Character Deletion")
    class CharacterDeletion {

        @Test
        @DisplayName("15.21 Soft delete sets isActive=false")
        void softDeleteSetsInactiveFalse() {
            // Given: an active character owned by the requesting user
            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID()).user(user).name("DeleteMe")
                    .characterClass("Fighter").level(5)
                    .strength(16).dexterity(14).constitution(14)
                    .intelligence(10).wisdom(10).charisma(10)
                    .hpMax(40).hpCurrent(40).armourClass(16)
                    .proficiencyBonus(3).speed(30)
                    .isActive(true)
                    .build();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(encounterParticipantRepository.existsByCharacter_IdAndEncounter_StatusIn(
                    eq(pc.getId()), any())).thenReturn(false);
            mockSaveReturnsArg();

            // When
            service.deleteCharacter(pc.getId(), userId);

            // Then: isActive set to false
            assertThat(pc.getIsActive()).isFalse();
            verify(characterRepository).save(pc);
        }

        @Test
        @DisplayName("15.22 Non-owner cannot delete character")
        void nonOwnerCannotDelete() {
            // Given: a character owned by user
            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID()).user(user).name("NotYours")
                    .characterClass("Fighter").level(1)
                    .strength(16).dexterity(14).constitution(14)
                    .intelligence(10).wisdom(10).charisma(10)
                    .hpMax(12).hpCurrent(12).armourClass(10)
                    .proficiencyBonus(2).speed(30)
                    .isActive(true)
                    .build();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

            // When/Then: other user's attempt to delete should fail
            assertThatThrownBy(() -> service.deleteCharacter(pc.getId(), otherUserId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("You do not own this character");

            // Character should remain unchanged
            assertThat(pc.getIsActive()).isTrue();
            verify(characterRepository, never()).save(any());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private PlayerCharacter buildCharacterForRest(
            String className, int level, int hitDice,
            int hpMax, int hpCurrent, int constitution,
            Map<String, HitDiceEntry> hitDiceMap,
            String spellSlots, String featResources) throws Exception {

        return PlayerCharacter.builder()
                .id(UUID.randomUUID()).user(user).name("RestTestChar")
                .characterClass(className).level(level)
                .classRef(fighter) // default ref; tests override what matters
                .strength(10).dexterity(10).constitution(constitution)
                .intelligence(10).wisdom(10).charisma(10)
                .hpMax(hpMax).hpCurrent(hpCurrent)
                .armourClass(10)
                .proficiencyBonus(CharacterService.proficiencyBonusForLevel(level))
                .speed(30)
                .hitDiceMap(objectMapper.writeValueAsString(hitDiceMap))
                .hitDiceTotal(level + "d" + hitDice)
                .hitDiceRemaining(level + "d" + hitDice)
                .spellSlots(spellSlots)
                .featResources(featResources)
                .isActive(true)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Integer>> parseSpellSlots(String spellSlotsJson) {
        try {
            return objectMapper.readValue(spellSlotsJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse spell slots: " + spellSlotsJson, e);
        }
    }

    private List<?> parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<?>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON array: " + json, e);
        }
    }
}
