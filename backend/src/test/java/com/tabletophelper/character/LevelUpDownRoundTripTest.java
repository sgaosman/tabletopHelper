package com.tabletophelper.character;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.LevelUpRequest;
import com.tabletophelper.character.dto.LevelUpResponse;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.UserService;
import com.tabletophelper.reference.*;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
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
class LevelUpDownRoundTripTest {

    @Mock CharacterRepository characterRepository;
    @Mock UserService userService;
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignMemberRepository campaignMemberRepository;
    @Mock RaceRepository raceRepository;
    @Mock CharacterClassRepository characterClassRepository;
    @Mock SubclassRepository subclassRepository;
    @Mock BackgroundRepository backgroundRepository;
    @Mock EncounterParticipantRepository encounterParticipantRepository;
    @Mock FeatRepository featRepository;
    @Mock SpellRepository spellRepository;
    @Mock com.tabletophelper.resourcepool.ResourcePoolService resourcePoolService;

    ObjectMapper objectMapper = new ObjectMapper();
    CharacterMapper characterMapper;
    FeatEffectResolver featEffectResolver;
    CharacterService service;

    UUID userId = UUID.randomUUID();
    UUID fighterId = UUID.randomUUID();
    UUID wizardId = UUID.randomUUID();
    User user;
    CharacterClass fighter;
    CharacterClass wizard;

    @BeforeEach
    void setUp() {
        characterMapper = new CharacterMapper(objectMapper, subclassRepository);
        featEffectResolver = new FeatEffectResolver(objectMapper, spellRepository);
        service = new CharacterService(
                characterRepository, userService, campaignRepository,
                raceRepository, characterClassRepository,
                subclassRepository, backgroundRepository, encounterParticipantRepository,
                featRepository, featEffectResolver, characterMapper, objectMapper,
                resourcePoolService, new AccessPolicy(campaignRepository, campaignMemberRepository));

        user = User.builder().id(userId).displayName("Test").build();

        fighter = CharacterClass.builder()
                .id(fighterId).name("Fighter").hitDice(10).subclassLevel(3)
                .isSpellcaster(false).isPactMagic(false)
                .features("[{\"level\":1,\"name\":\"Fighting Style\",\"description\":\"Choose a style\"},{\"level\":2,\"name\":\"Action Surge\",\"description\":\"Take an extra action\"}]")
                .build();

        wizard = CharacterClass.builder()
                .id(wizardId).name("Wizard").hitDice(6).subclassLevel(2)
                .isSpellcaster(true).isPactMagic(false).spellcastingAbility("INT")
                .features("[{\"level\":1,\"name\":\"Arcane Recovery\",\"description\":\"Recover spell slots\"},{\"level\":2,\"name\":\"Arcane Tradition\",\"description\":\"Choose a tradition\"}]")
                .build();
    }

    private PlayerCharacter buildFighter(int level) {
        List<ClassEntry> mcEntries = List.of(
                new ClassEntry(fighterId, "Fighter", null, null, level));
        Map<String, HitDicePool> hdMap = Map.of("Fighter", new HitDicePool(10, level, level));

        List<LevelHistoryEntry> history = new ArrayList<>();
        int totalHp = 12;
        List<Feature> lvl1Features = List.of(
                new Feature("Fighting Style", "Choose a style", "Fighter"));
        history.add(new LevelHistoryEntry(1, fighterId, "Fighter", 1, 12, lvl1Features, LevelChoices.NONE));

        for (int i = 2; i <= level; i++) {
            int hpGain = 7; // average d10 (5.5 → 6) + 1 CON mod
            totalHp += hpGain;
            List<Feature> features = i == 2
                    ? List.of(new Feature("Action Surge", "Take an extra action", "Fighter"))
                    : List.of();
            history.add(new LevelHistoryEntry(i, fighterId, "Fighter", i, hpGain, features, LevelChoices.NONE));
        }

        return PlayerCharacter.builder()
                .id(UUID.randomUUID()).user(user).name("TestFighter")
                .characterClass("Fighter").level(level)
                .classRef(fighter).strength(16).dexterity(14).constitution(12)
                .intelligence(10).wisdom(10).charisma(10)
                .hpMax(totalHp).hpCurrent(totalHp)
                .proficiencyBonus(CharacterService.proficiencyBonusForLevel(level))
                .multiclassEntries(mcEntries)
                .hitDice(hdMap)
                .levelHistory(history)
                .isActive(true)
                .build();
    }

    private void mockSaveReturnsArg() {
        when(characterRepository.save(any(PlayerCharacter.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("Single class level up")
    class SingleClassLevelUp {

        @Test
        @DisplayName("Fighter 1→2 gains correct HP, features, and proficiency bonus")
        void fighterLevel1To2() throws Exception {
            PlayerCharacter pc = buildFighter(1);
            int originalHp = pc.getHpMax();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            LevelUpResponse response = service.levelUp(pc.getId(), request, userId);

            assertThat(response.getCharacter().getLevel()).isEqualTo(2);
            assertThat(response.getCharacter().getHpMax()).isGreaterThan(originalHp);
            assertThat(response.getCharacter().getProficiencyBonus()).isEqualTo(2);
            assertThat(response.getPendingChoices().getNewFeatures()).contains("Action Surge");
        }

        @Test
        @DisplayName("Fighter at level 4 shows ASI available")
        void fighterLevel4Asi() throws Exception {
            PlayerCharacter pc = buildFighter(3);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            LevelUpResponse response = service.levelUp(pc.getId(), request, userId);

            assertThat(response.getCharacter().getLevel()).isEqualTo(4);
            assertThat(response.getPendingChoices().isAsiAvailable()).isTrue();
        }

        @Test
        @DisplayName("Fighter level 4→5 gains proficiency bonus increase")
        void proficiencyBonusIncrease() throws Exception {
            PlayerCharacter pc = buildFighter(4);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            LevelUpResponse response = service.levelUp(pc.getId(), request, userId);

            assertThat(response.getCharacter().getLevel()).isEqualTo(5);
            assertThat(response.getCharacter().getProficiencyBonus()).isEqualTo(3);
        }

        @Test
        @DisplayName("Cannot level up past 20")
        void cannotExceedLevel20() throws Exception {
            PlayerCharacter pc = buildFighter(20);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);

            assertThatThrownBy(() -> service.levelUp(pc.getId(), request, userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("maximum level");
        }
    }

    @Nested
    @DisplayName("Single class level down")
    class SingleClassLevelDown {

        @Test
        @DisplayName("Fighter 2→1 reverts HP and features")
        void fighterLevel2To1() throws Exception {
            PlayerCharacter pc = buildFighter(2);
            int hpBefore = pc.getHpMax();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            CharacterResponse response = service.levelDown(pc.getId(), userId);

            assertThat(response.getLevel()).isEqualTo(1);
            assertThat(response.getHpMax()).isLessThan(hpBefore);

            List<LevelHistoryEntry> history = response.getLevelHistory();
            assertThat(history).hasSize(1);
        }

        @Test
        @DisplayName("Cannot level down from level 1")
        void cannotLevelDownFromLevel1() throws Exception {
            PlayerCharacter pc = buildFighter(1);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

            assertThatThrownBy(() -> service.levelDown(pc.getId(), userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("minimum level");
        }

        @Test
        @DisplayName("Level down updates the hit dice")
        void levelDownUpdatesHitDice() throws Exception {
            PlayerCharacter pc = buildFighter(3);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            CharacterResponse response = service.levelDown(pc.getId(), userId);

            assertThat(response.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 2, 2)));
        }
    }

    @Nested
    @DisplayName("Level up/down round-trip")
    class RoundTrip {

        @Test
        @DisplayName("Level up then down restores original HP")
        void levelUpThenDownRestoresHp() throws Exception {
            PlayerCharacter pc = buildFighter(3);
            int originalHp = pc.getHpMax();
            int originalLevel = pc.getLevel();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            service.levelUp(pc.getId(), request, userId);

            assertThat(pc.getLevel()).isEqualTo(4);
            assertThat(pc.getHpMax()).isGreaterThan(originalHp);

            service.levelDown(pc.getId(), userId);

            assertThat(pc.getLevel()).isEqualTo(originalLevel);
            assertThat(pc.getHpMax()).isEqualTo(originalHp);
        }

        @Test
        @DisplayName("Level up then down restores proficiency bonus")
        void levelUpThenDownRestoresProfBonus() throws Exception {
            PlayerCharacter pc = buildFighter(4);
            int originalProfBonus = pc.getProficiencyBonus();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            service.levelUp(pc.getId(), request, userId);

            assertThat(pc.getProficiencyBonus()).isEqualTo(3);

            service.levelDown(pc.getId(), userId);

            assertThat(pc.getProficiencyBonus()).isEqualTo(originalProfBonus);
        }

        @Test
        @DisplayName("Level up then down restores the hit dice")
        void levelUpThenDownRestoresHitDice() throws Exception {
            PlayerCharacter pc = buildFighter(2);
            Map<String, HitDicePool> originalHitDice = pc.getHitDice();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            service.levelUp(pc.getId(), request, userId);

            assertThat(pc.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 3, 3)));

            service.levelDown(pc.getId(), userId);

            assertThat(pc.getHitDice()).isEqualTo(originalHitDice);
        }

        @Test
        @DisplayName("Multiple level ups then downs restore original state")
        void multipleRoundTrips() throws Exception {
            PlayerCharacter pc = buildFighter(1);
            int originalHp = pc.getHpMax();
            int originalLevel = pc.getLevel();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);

            service.levelUp(pc.getId(), request, userId);
            service.levelUp(pc.getId(), request, userId);
            service.levelUp(pc.getId(), request, userId);

            assertThat(pc.getLevel()).isEqualTo(4);

            service.levelDown(pc.getId(), userId);
            service.levelDown(pc.getId(), userId);
            service.levelDown(pc.getId(), userId);

            assertThat(pc.getLevel()).isEqualTo(originalLevel);
            assertThat(pc.getHpMax()).isEqualTo(originalHp);
        }
    }

    @Nested
    @DisplayName("Ownership validation")
    class OwnershipValidation {

        @Test
        @DisplayName("Level up rejects non-owner")
        void levelUpRejectsNonOwner() throws Exception {
            PlayerCharacter pc = buildFighter(1);
            UUID otherUserId = UUID.randomUUID();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);

            assertThatThrownBy(() -> service.levelUp(pc.getId(), request, otherUserId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("do not own");
        }

        @Test
        @DisplayName("Level down rejects non-owner")
        void levelDownRejectsNonOwner() throws Exception {
            PlayerCharacter pc = buildFighter(2);
            UUID otherUserId = UUID.randomUUID();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

            assertThatThrownBy(() -> service.levelDown(pc.getId(), otherUserId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("do not own");
        }
    }

    @Nested
    @DisplayName("Level history integrity")
    class LevelHistoryIntegrity {

        @Test
        @DisplayName("Level up appends correct history entry")
        void levelUpAppendsHistory() throws Exception {
            PlayerCharacter pc = buildFighter(1);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            service.levelUp(pc.getId(), request, userId);

            List<LevelHistoryEntry> history = pc.getLevelHistory();
            assertThat(history).hasSize(2);

            LevelHistoryEntry latest = history.get(1);
            assertThat(latest.characterLevel()).isEqualTo(2);
            assertThat(latest.classId()).isEqualTo(fighterId);
            assertThat(latest.className()).isEqualTo("Fighter");
            assertThat(latest.classLevel()).isEqualTo(2);
            assertThat(latest.hpGained()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Level down removes last history entry")
        void levelDownRemovesHistory() throws Exception {
            PlayerCharacter pc = buildFighter(3);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            service.levelDown(pc.getId(), userId);

            List<LevelHistoryEntry> history = pc.getLevelHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(history.size() - 1).characterLevel()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Spellcaster level up")
    class SpellcasterLevelUp {

        @Test
        @DisplayName("Wizard level up recalculates spell slots")
        void wizardLevelUpRecalculatesSlots() throws Exception {
            List<ClassEntry> mcEntries = List.of(
                    new ClassEntry(wizardId, "Wizard", null, null, 1));
            Map<String, HitDicePool> hdMap = Map.of(
                    "Wizard", new HitDicePool(6, 1, 1));

            List<LevelHistoryEntry> history = List.of(
                    new LevelHistoryEntry(1, wizardId, "Wizard", 1, 8,
                            List.of(new Feature("Arcane Recovery", "Recover spell slots", "Wizard")),
                            LevelChoices.NONE));

            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID()).user(user).name("TestWizard")
                    .characterClass("Wizard").level(1)
                    .classRef(wizard).strength(8).dexterity(14).constitution(12)
                    .intelligence(16).wisdom(10).charisma(10)
                    .hpMax(8).hpCurrent(8).proficiencyBonus(2)
                    .multiclassEntries(mcEntries).hitDice(hdMap)
                    .spellcastingAbility("INT")
                    .levelHistory(history)
                    .isActive(true)
                    .build();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(wizardId)).thenReturn(Optional.of(wizard));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(wizardId);
            LevelUpResponse response = service.levelUp(pc.getId(), request, userId);

            assertThat(response.getCharacter().getSpellSlots()).isNotNull();
            assertThat(response.getPendingChoices().isSpellSelectionNeeded()).isTrue();
            assertThat(response.getPendingChoices().getSpellSelectionType()).isEqualTo("prepared");
        }
    }

    @Nested
    @DisplayName("Multiclass level up")
    class MulticlassLevelUp {

        @Test
        @DisplayName("Fighter 3 adds Wizard: HP uses d6, spell slots appear, hit dice mixed")
        void fighterAddsWizard() throws Exception {
            PlayerCharacter pc = buildFighter(3);
            int originalHp = pc.getHpMax();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(wizardId)).thenReturn(Optional.of(wizard));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(wizardId);
            LevelUpResponse response = service.levelUp(pc.getId(), request, userId);

            // Character level should be 4 (Fighter 3 + Wizard 1)
            assertThat(response.getCharacter().getLevel()).isEqualTo(4);

            // HP gained uses Wizard d6 hit die, not Fighter d10
            int conMod = CharacterService.abilityMod(pc.getConstitution());
            int expectedHpGain = 6 + conMod; // class level 1 uses max die
            assertThat(pc.getHpMax()).isEqualTo(originalHp + expectedHpGain);

            // Spell slots should appear (Wizard caster level 1 = 2 first-level slots)
            assertThat(pc.getSpellSlots()).isNotNull();

            // Hit dice should include both the Fighter's d10s and the Wizard's d6
            assertThat(pc.getHitDice()).containsEntry("Fighter", new HitDicePool(10, 3, 3));
            assertThat(pc.getHitDice()).containsEntry("Wizard", new HitDicePool(6, 1, 1));
        }
    }

    @Nested
    @DisplayName("Multiclass level down")
    class MulticlassLevelDown {

        @Test
        @DisplayName("Fighter 3 / Wizard 1 level down removes Wizard, reverts to Fighter 3")
        void levelDownRemovesLastClass() throws Exception {
            int conMod = CharacterService.abilityMod(12); // CON 12 = +1
            int fighterHp = (10 + conMod) + 2 * ((10 / 2 + 1) + conMod); // lvl1 max + 2 * avg
            int wizardHpGain = 6 + conMod; // Wizard class level 1 = max d6 + CON
            int totalHp = fighterHp + wizardHpGain;

            List<ClassEntry> mcEntries = List.of(
                    new ClassEntry(fighterId, "Fighter", null, null, 3),
                    new ClassEntry(wizardId, "Wizard", null, null, 1));

            Map<String, HitDicePool> hdMap = new LinkedHashMap<>();
            hdMap.put("Fighter", new HitDicePool(10, 3, 3));
            hdMap.put("Wizard", new HitDicePool(6, 1, 1));

            List<LevelHistoryEntry> history = new ArrayList<>();
            history.add(new LevelHistoryEntry(1, fighterId, "Fighter", 1,
                    10 + conMod, List.of(), LevelChoices.NONE));
            history.add(new LevelHistoryEntry(2, fighterId, "Fighter", 2,
                    (10 / 2 + 1) + conMod, List.of(), LevelChoices.NONE));
            history.add(new LevelHistoryEntry(3, fighterId, "Fighter", 3,
                    (10 / 2 + 1) + conMod, List.of(), LevelChoices.NONE));
            history.add(new LevelHistoryEntry(4, wizardId, "Wizard", 1,
                    wizardHpGain, List.of(), LevelChoices.NONE));

            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID()).user(user).name("TestMulticlass")
                    .characterClass("Fighter / Wizard").level(4)
                    .classRef(fighter).strength(16).dexterity(14).constitution(12)
                    .intelligence(10).wisdom(10).charisma(10)
                    .hpMax(totalHp).hpCurrent(totalHp)
                    .proficiencyBonus(CharacterService.proficiencyBonusForLevel(4))
                    .multiclassEntries(mcEntries).hitDice(hdMap)
                    .spellcastingAbility("INT")
                    .levelHistory(history)
                    .isActive(true)
                    .build();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(wizardId)).thenReturn(Optional.of(wizard));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            CharacterResponse response = service.levelDown(pc.getId(), userId);

            // Should revert to Fighter 3
            assertThat(response.getLevel()).isEqualTo(3);
            assertThat(pc.getHpMax()).isEqualTo(fighterHp);

            // Spell slots should be gone (Fighter has no spellcasting)
            assertThat(pc.getSpellSlots()).isNull();

            // Hit dice should be only Fighter's d10
            assertThat(pc.getHitDice()).isEqualTo(Map.of("Fighter", new HitDicePool(10, 3, 3)));
        }
    }

    @Nested
    @DisplayName("Subclass threshold")
    class SubclassThreshold {

        @Test
        @DisplayName("Fighter leveling to level 3 flags subclass as available")
        void subclassAvailableAtThreshold() throws Exception {
            PlayerCharacter pc = buildFighter(2);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            LevelUpResponse response = service.levelUp(pc.getId(), request, userId);

            assertThat(response.getCharacter().getLevel()).isEqualTo(3);
            assertThat(response.getPendingChoices().isSubclassRequired()).isTrue();
        }
    }

    @Nested
    @DisplayName("Spellcaster level down")
    class SpellcasterLevelDown {

        @Test
        @DisplayName("Wizard level 5 to 4: spell slots recalculated, no 3rd-level slots")
        void wizardLevelDownRecalculatesSlots() throws Exception {
            int conMod = CharacterService.abilityMod(12); // CON 12 = +1
            int level1Hp = 6 + conMod;
            int avgHpGain = (6 / 2 + 1) + conMod;
            int totalHp = level1Hp + 4 * avgHpGain;

            List<ClassEntry> mcEntries = List.of(
                    new ClassEntry(wizardId, "Wizard", null, null, 5));
            Map<String, HitDicePool> hdMap = Map.of(
                    "Wizard", new HitDicePool(6, 5, 5));

            List<LevelHistoryEntry> history = new ArrayList<>();
            history.add(new LevelHistoryEntry(1, wizardId, "Wizard", 1, level1Hp,
                    List.of(new Feature("Arcane Recovery", "Recover spell slots", "Wizard")),
                    LevelChoices.NONE));
            for (int i = 2; i <= 5; i++) {
                List<Feature> features = i == 2
                        ? List.of(new Feature("Arcane Tradition", "Choose a tradition", "Wizard"))
                        : List.of();
                history.add(new LevelHistoryEntry(i, wizardId, "Wizard", i, avgHpGain,
                        features, LevelChoices.NONE));
            }

            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID()).user(user).name("TestWizard")
                    .characterClass("Wizard").level(5)
                    .classRef(wizard).strength(8).dexterity(14).constitution(12)
                    .intelligence(16).wisdom(10).charisma(10)
                    .hpMax(totalHp).hpCurrent(totalHp)
                    .proficiencyBonus(CharacterService.proficiencyBonusForLevel(5))
                    .multiclassEntries(mcEntries).hitDice(hdMap)
                    .spellcastingAbility("INT")
                    .levelHistory(history)
                    .isActive(true)
                    .build();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(wizardId)).thenReturn(Optional.of(wizard));
            mockSaveReturnsArg();

            CharacterResponse response = service.levelDown(pc.getId(), userId);

            assertThat(response.getLevel()).isEqualTo(4);
            assertThat(pc.getHpMax()).isEqualTo(totalHp - avgHpGain);

            // Spell slots should be recalculated for caster level 4
            // Level 4 full caster: 4/3 at levels 1/2, no 3rd-level slots
            assertThat(pc.getSpellSlots()).isNotNull();
            assertThat(pc.getSpellSlots()).containsKey("1");
            assertThat(pc.getSpellSlots()).containsKey("2");
            assertThat(pc.getSpellSlots()).doesNotContainKey("3");

            // Hit dice should be 4d6
            assertThat(pc.getHitDice()).isEqualTo(Map.of("Wizard", new HitDicePool(6, 4, 4)));
        }
    }
}
