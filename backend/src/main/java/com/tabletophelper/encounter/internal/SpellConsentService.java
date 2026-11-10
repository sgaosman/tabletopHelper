package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterCommands;
import com.tabletophelper.encounter.EncounterLifecycle;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.SpellConsentRequest;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The standing choice of a creature whose trait keeps spells off it "unless it wishes to be" affected, as a rakshasa's
 * Limited Magic Immunity (MM p.257, D198): which spells it lets through, set by whoever controls it. A cast's own
 * choice, made in the cast request, overrides it for that cast.
 */
@Service
@RequiredArgsConstructor
public class SpellConsentService {

    private final EncounterCommands encounterCommands;
    private final AccessPolicy accessPolicy;
    private final CreatureStats stats;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Sets which spells the creature lets through, while the encounter is prepared or under way: by the DM, or by the
     * player a spell gave control to, as Dominate Monster does. It is a choice, not a combat action, so it costs nothing
     * and undo doesn't take it back. A creature without such a trait is a 400.
     */
    @Transactional
    public EncounterResponse setSpellConsent(UUID encounterId, SpellConsentRequest request, UUID userId) {
        Encounter encounter = encounterCommands.beginAsMember(encounterId, userId);
        if (encounter.getStatus() != EncounterStatus.PREPARING) {
            EncounterLifecycle.requireActive(encounter);
        }
        EncounterParticipant participant = CombatAccess.participant(encounter, request.participantId());
        accessPolicy.requireControls(encounter.getCampaign().getId(), participant.getControlledByUserId(), userId);
        if (stats.spellConsentTrait(participant) == null) {
            throw new ValidationException(participant.getDisplayName()
                    + " has no trait that lets it choose to be affected by spells");
        }
        participant.setSpellConsent(request.consent());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
