package com.tabletophelper.encounter;

import com.tabletophelper.character.LiveEncounters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The character module's question about live encounters, answered from the encounters each character takes part in
 * (D151): the dependency runs from encounter to character, never back.
 */
@Component
@RequiredArgsConstructor
class ParticipantLiveEncounters implements LiveEncounters {

    private static final List<EncounterStatus> LIVE = List.of(EncounterStatus.ACTIVE, EncounterStatus.PAUSED);
    private static final List<EncounterStatus> UNFINISHED =
            List.of(EncounterStatus.PREPARING, EncounterStatus.ACTIVE, EncounterStatus.PAUSED);

    private final EncounterParticipantRepository participantRepository;

    @Override
    public boolean includes(UUID characterId) {
        return participantRepository.existsByCharacter_IdAndEncounter_StatusIn(characterId, LIVE);
    }

    @Override
    public boolean includesUnfinished(UUID characterId) {
        return participantRepository.existsByCharacter_IdAndEncounter_StatusIn(characterId, UNFINISHED);
    }
}
