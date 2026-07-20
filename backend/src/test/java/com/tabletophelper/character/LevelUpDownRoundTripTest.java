package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.LevelHistoryEntry;
import com.tabletophelper.character.dto.LevelUpRequest;
import com.tabletophelper.character.dto.LevelUpResponse;
import com.tabletophelper.character.dto.MulticlassEntry;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.reference.*;
import com.tabletophelper.user.User;
import com.tabletophelper.user.UserRepository;
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
    UUID fighterId = UUID.randomUUID();
    UUID wizardId = UUID.randomUUID();
    User user;
    CharacterClass fighter;
    CharacterClass wizard;

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

    private PlayerCharacter buildFighter(int level) throws Exception {
        String mcEntries = objectMapper.writeValueAsString(List.of(
                new MulticlassEntry(fighterId.toString(), "Fighter", null, null, level)));
        String hdMap = objectMapper.writeValueAsString(Map.of(
                "Fighter", Map.of("total", level, "remaining", level, "faces", 10)));

        List<LevelHistoryEntry> history = new ArrayList<>();
        int totalHp = 12;
        List<LevelHistoryEntry.FeatureRecord> lvl1Features = List.of(
                new LevelHistoryEntry.FeatureRecord("Fighting Style", "Choose a style", "Fighter"));
        history.add(new LevelHistoryEntry(1, fighterId.toString(), "Fighter", 1, 12, lvl1Features, Map.of()));

        for (int i = 2; i <= level; i++) {
            int hpGain = 7; // average d10 (5.5 → 6) + 1 CON mod
            totalHp += hpGain;
            List<LevelHistoryEntry.FeatureRecord> features = i == 2
                    ? List.of(new LevelHistoryEntry.FeatureRecord("Action Surge", "Take an extra action", "Fighter"))
                    : List.of();
            history.add(new LevelHistoryEntry(i, fighterId.toString(), "Fighter", i, hpGain, features, Map.of()));
        }

        return PlayerCharacter.builder()
                .id(UUID.randomUUID()).user(user).name("TestFighter")
                .characterClass("Fighter").level(level)
                .classRef(fighter).strength(16).dexterity(14).constitution(12)
                .intelligence(10).wisdom(10).charisma(10)
                .hpMax(totalHp).hpCurrent(totalHp)
                .proficiencyBonus(CharacterService.proficiencyBonusForLevel(level))
                .multiclassEntries(mcEntries)
                .hitDiceMap(hdMap)
                .hitDiceTotal(level + "d10")
                .hitDiceRemaining(level + "d10")
                .levelHistory(objectMapper.writeValueAsString(history))
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
                    .isInstanceOf(IllegalArgumentException.class)
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

            List<LevelHistoryEntry> history = objectMapper.readValue(
                    response.getLevelHistory(), new TypeReference<>() {});
            assertThat(history).hasSize(1);
        }

        @Test
        @DisplayName("Cannot level down from level 1")
        void cannotLevelDownFromLevel1() throws Exception {
            PlayerCharacter pc = buildFighter(1);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

            assertThatThrownBy(() -> service.levelDown(pc.getId(), userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minimum level");
        }

        @Test
        @DisplayName("Level down updates hit dice map and total")
        void levelDownUpdatesHitDice() throws Exception {
            PlayerCharacter pc = buildFighter(3);

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            CharacterResponse response = service.levelDown(pc.getId(), userId);

            assertThat(response.getHitDiceTotal()).isEqualTo("2d10");
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
        @DisplayName("Level up then down restores hit dice total")
        void levelUpThenDownRestoresHitDice() throws Exception {
            PlayerCharacter pc = buildFighter(2);
            String originalHdTotal = pc.getHitDiceTotal();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));
            when(characterClassRepository.findById(fighterId)).thenReturn(Optional.of(fighter));
            mockSaveReturnsArg();

            LevelUpRequest request = new LevelUpRequest();
            request.setClassId(fighterId);
            service.levelUp(pc.getId(), request, userId);

            assertThat(pc.getHitDiceTotal()).isEqualTo("3d10");

            service.levelDown(pc.getId(), userId);

            assertThat(pc.getHitDiceTotal()).isEqualTo(originalHdTotal);
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
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("do not own");
        }

        @Test
        @DisplayName("Level down rejects non-owner")
        void levelDownRejectsNonOwner() throws Exception {
            PlayerCharacter pc = buildFighter(2);
            UUID otherUserId = UUID.randomUUID();

            when(characterRepository.findById(pc.getId())).thenReturn(Optional.of(pc));

            assertThatThrownBy(() -> service.levelDown(pc.getId(), otherUserId))
                    .isInstanceOf(IllegalArgumentException.class)
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

            List<LevelHistoryEntry> history = objectMapper.readValue(
                    pc.getLevelHistory(), new TypeReference<>() {});
            assertThat(history).hasSize(2);

            LevelHistoryEntry latest = history.get(1);
            assertThat(latest.characterLevel()).isEqualTo(2);
            assertThat(latest.classId()).isEqualTo(fighterId.toString());
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

            List<LevelHistoryEntry> history = objectMapper.readValue(
                    pc.getLevelHistory(), new TypeReference<>() {});
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
            String mcEntries = objectMapper.writeValueAsString(List.of(
                    new MulticlassEntry(wizardId.toString(), "Wizard", null, null, 1)));
            String hdMap = objectMapper.writeValueAsString(Map.of(
                    "Wizard", Map.of("total", 1, "remaining", 1, "faces", 6)));

            List<LevelHistoryEntry> history = List.of(
                    new LevelHistoryEntry(1, wizardId.toString(), "Wizard", 1, 8,
                            List.of(new LevelHistoryEntry.FeatureRecord("Arcane Recovery", "Recover spell slots", "Wizard")),
                            Map.of()));

            PlayerCharacter pc = PlayerCharacter.builder()
                    .id(UUID.randomUUID()).user(user).name("TestWizard")
                    .characterClass("Wizard").level(1)
                    .classRef(wizard).strength(8).dexterity(14).constitution(12)
                    .intelligence(16).wisdom(10).charisma(10)
                    .hpMax(8).hpCurrent(8).proficiencyBonus(2)
                    .multiclassEntries(mcEntries).hitDiceMap(hdMap)
                    .hitDiceTotal("1d6").hitDiceRemaining("1d6")
                    .spellcastingAbility("INT")
                    .levelHistory(objectMapper.writeValueAsString(history))
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
}
