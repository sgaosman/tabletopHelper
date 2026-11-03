package com.tabletophelper.encounter;

import com.tabletophelper.character.CombatResults;
import com.tabletophelper.character.CombatResults.CharacterResult;
import com.tabletophelper.character.WriteBackService;
import com.tabletophelper.character.dto.CombatResultPreview;
import com.tabletophelper.character.state.CombatTotals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Write-back's side in the encounter module (D166): each player character's participant, what it joined with and what
 * it ended with, as the character module's {@link CombatResults}. They are published for the character module to
 * apply inside the transaction that completes the encounter or removes the participant, or handed to it for the End
 * dialog's preview, so the dependency runs from encounter to character only (D151). The command publishing them holds
 * the encounter's row lock, which is taken before the characters' rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class EncounterResults {

    private final ApplicationEventPublisher eventPublisher;
    private final WriteBackService writeBackService;

    /**
     * Applies what the encounter changed for these participants' characters; monsters have none. Call it before
     * anything in the transaction reads the characters' sheets, so they are read once they are locked.
     */
    void apply(Encounter encounter, Collection<EncounterParticipant> participants) {
        CombatResults results = of(encounter, participants);
        if (!results.characters().isEmpty()) {
            eventPublisher.publishEvent(results);
        }
    }

    /** What completing the encounter would write back to each player character's sheet, in the turn order. */
    List<CombatResultPreview> preview(Encounter encounter) {
        List<EncounterParticipant> inTurnOrder = encounter.getParticipants().stream()
                .sorted(Comparator.comparing(EncounterParticipant::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return writeBackService.preview(of(encounter, inTurnOrder));
    }

    // A participant without what it joined with, which only an encounter completed before R6 has, is left out
    private static CombatResults of(Encounter encounter, Collection<EncounterParticipant> participants) {
        List<CharacterResult> characters = new ArrayList<>();
        for (EncounterParticipant participant : participants) {
            if (participant.getParticipantType() != ParticipantType.PLAYER || participant.getCharacter() == null) {
                continue;
            }
            if (participant.getJoinedWith() == null) {
                log.warn("Participant {} of encounter {} has no record of what it joined with, so nothing is written "
                        + "back for it", participant.getId(), encounter.getId());
                continue;
            }
            characters.add(new CharacterResult(participant.getCharacter().getId(), participant.getJoinedWith(),
                    endedWith(participant), participant.getLifeState()));
        }
        return new CombatResults(encounter.getId(), characters);
    }

    private static CombatTotals endedWith(EncounterParticipant participant) {
        return CombatTotals.of(participant.getHpCurrent(), participant.getHpTemp() != null ? participant.getHpTemp() : 0,
                participant.getSpellSlotsCurrent(), participant.getResourcePoolsCurrent());
    }
}
