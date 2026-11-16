package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterCommands;
import com.tabletophelper.encounter.EncounterLifecycle;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.DismissReactionRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.state.ReactionOffer;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Lets a reaction offer pass (D216). Whoever controls the reacting creature may, the DM for anyone. It is not a
 * combat command: it isn't recorded for undo and leaves the other offers standing, and it is broadcast as any change.
 */
@Service
@RequiredArgsConstructor
public class ReactionOfferService {

    /** Why a dismissal or an answer names an offer that has gone. */
    public static final String GONE = "That reaction offer has already gone";

    private final EncounterCommands encounterCommands;
    private final AccessPolicy accessPolicy;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public EncounterResponse dismiss(UUID encounterId, DismissReactionRequest request, UUID userId) {
        Encounter encounter = encounterCommands.beginAsMember(encounterId, userId);
        EncounterLifecycle.requireActive(encounter);
        List<ReactionOffer> offers = encounter.getReactionOffers() != null ? encounter.getReactionOffers() : List.of();
        ReactionOffer offer = offers.stream()
                .filter(candidate -> candidate.id().equals(request.offerId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException(GONE));
        // A monster's offers are the DM's; a hidden creature's controller is the DM too
        UUID controller = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(offer.reactorId()))
                .findFirst()
                .map(EncounterParticipant::getControlledByUserId)
                .orElse(null);
        accessPolicy.requireControls(encounter.getCampaign().getId(), controller, userId);
        encounter.setReactionOffers(ReactionOffer.without(offers, candidate -> candidate.id().equals(offer.id())));
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
