package com.tabletophelper.encounter;

import com.tabletophelper.character.CombatResults;
import com.tabletophelper.character.CombatResults.CharacterResult;
import com.tabletophelper.character.WriteBackService;
import com.tabletophelper.character.dto.CombatResultPreview;
import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.encounter.state.ItemUse;
import com.tabletophelper.encounter.state.WildShapeForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /**
     * Locks the player characters of the encounter's participants, in ID order after the encounter's row (D228), so
     * the start reads each sheet only once no rest, level or Write-back on it can still be committing. Call it before
     * anything in the transaction reads the sheets. Only the IDs are read here, which leaves each lazy character
     * unloaded until its row is locked.
     */
    void lockCharacters(Encounter encounter) {
        List<UUID> characterIds = encounter.getParticipants().stream()
                .filter(participant -> participant.getParticipantType() == ParticipantType.PLAYER
                        && participant.getCharacter() != null)
                .map(participant -> participant.getCharacter().getId())
                .toList();
        if (!characterIds.isEmpty()) {
            writeBackService.lockForEncounter(characterIds);
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
        return new CombatResults(encounter.getId(), encounter.getCampaign().getId(), characters);
    }

    // A druid still in Wild Shape ends with its own hit points, which the beast form left alone: the beast's are never
    // written back (PHB p.67)
    private static CombatTotals endedWith(EncounterParticipant participant) {
        int hp = WildShapeForm.of(participant).map(WildShapeForm::normalHpCurrent).orElse(participant.getHpCurrent());
        return CombatTotals.of(hp, participant.getHpTemp() != null ? participant.getHpTemp() : 0,
                participant.getSpellSlotsCurrent(), participant.getResourcePoolsCurrent())
                .withExhaustion(participant.getExhaustionLevel() != null ? participant.getExhaustionLevel() : 0)
                .withItemsUsed(itemsUsed(participant));
    }

    // How many of each inventory entry it used up, such as potions drunk and scrolls read (D210)
    private static Map<String, Integer> itemsUsed(EncounterParticipant participant) {
        Map<String, Integer> used = new LinkedHashMap<>();
        if (participant.getItemsUsed() != null) {
            for (ItemUse use : participant.getItemsUsed()) {
                if (use != null && use.name() != null) {
                    used.merge(use.key(), 1, Integer::sum);
                }
            }
        }
        return used;
    }
}
