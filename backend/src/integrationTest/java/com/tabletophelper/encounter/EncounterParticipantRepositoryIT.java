package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.internal.UserRepository;
import com.tabletophelper.reference.Race;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EncounterParticipantRepositoryIT extends IntegrationTest {

    // The statuses CharacterService.deleteCharacter treats as "in an encounter"
    private static final List<EncounterStatus> UNFINISHED =
            List.of(EncounterStatus.ACTIVE, EncounterStatus.PAUSED, EncounterStatus.PREPARING);

    @Autowired private EncounterParticipantRepository participantRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private Race race;
    private Campaign campaign;

    @BeforeEach
    void insertCampaign() {
        owner = userRepository.save(User.builder().username("dm").email("dm@example.com").passwordHash("hash").build());
        race = raceRepository.save(Race.builder().name("Human").source("PHB").build());
        campaign = campaignRepository.save(Campaign.builder().name("Lost Mine").dm(owner).inviteCode("ABCD2345").build());
    }

    private PlayerCharacter insertCharacter(String name) {
        return characterRepository.save(PlayerCharacter.builder()
                .user(owner).raceRef(race).name(name)
                .strength(10).dexterity(10).constitution(10).intelligence(10).wisdom(10).charisma(10)
                .hpMax(10).hpCurrent(10).armourClass(10)
                .build());
    }

    private void insertEncounter(EncounterStatus status, PlayerCharacter character) {
        Encounter encounter = Encounter.builder().campaign(campaign).name("Ambush").status(status).build();
        encounter.getParticipants().add(EncounterParticipant.builder()
                .encounter(encounter).participantType(ParticipantType.PLAYER).character(character)
                .displayName(character.getName()).hpMax(10).hpCurrent(10).armourClass(10)
                .build());
        encounterRepository.save(encounter);
    }

    @Test
    @DisplayName("existsByCharacter_IdAndEncounter_StatusIn finds a character whose encounter has a listed status")
    void findsCharacterInListedStatus() {
        PlayerCharacter fighting = insertCharacter("Aria");
        insertEncounter(EncounterStatus.ACTIVE, fighting);

        assertThat(participantRepository.existsByCharacter_IdAndEncounter_StatusIn(fighting.getId(), UNFINISHED)).isTrue();
        assertThat(participantRepository.existsByCharacter_IdAndEncounter_StatusIn(fighting.getId(),
                List.of(EncounterStatus.COMPLETED))).isFalse();
    }

    @Test
    @DisplayName("existsByCharacter_IdAndEncounter_StatusIn ignores completed encounters and other characters")
    void ignoresOtherStatusesAndCharacters() {
        PlayerCharacter veteran = insertCharacter("Bram");
        PlayerCharacter idle = insertCharacter("Cora");
        insertEncounter(EncounterStatus.COMPLETED, veteran);

        assertThat(participantRepository.existsByCharacter_IdAndEncounter_StatusIn(veteran.getId(), UNFINISHED)).isFalse();
        assertThat(participantRepository.existsByCharacter_IdAndEncounter_StatusIn(idle.getId(), UNFINISHED)).isFalse();
    }
}
