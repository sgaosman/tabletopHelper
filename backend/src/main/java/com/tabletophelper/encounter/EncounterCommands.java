package com.tabletophelper.encounter;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The start of every command that changes an encounter (D160). Access is checked by campaign ID first, so a
 * caller who may not act never locks the row. The encounter is then loaded under a pessimistic write lock
 * held until the transaction ends: commands on one encounter queue instead of failing, and each starts from
 * the last one's committed state. A wait longer than five seconds is a 409. The change is announced at once,
 * and {@link EncounterBroadcaster} publishes the encounter only if the transaction commits.
 *
 * <p>Call it from inside the command's transaction.
 */
@Component
@RequiredArgsConstructor
public class EncounterCommands {

    private final EncounterRepository encounterRepository;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher eventPublisher;

    /** A command only the campaign's DM may give. */
    public Encounter beginAsDm(UUID encounterId, UUID userId) {
        accessPolicy.requireDm(campaignIdOf(encounterId), userId);
        return lock(encounterId);
    }

    /**
     * A command any campaign member may give. The caller then checks which participant the user may act
     * for, against the locked state (D028).
     */
    public Encounter beginAsMember(UUID encounterId, UUID userId) {
        accessPolicy.requireMember(campaignIdOf(encounterId), userId);
        return lock(encounterId);
    }

    private UUID campaignIdOf(UUID encounterId) {
        return encounterRepository.findCampaignIdById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
    }

    private Encounter lock(UUID encounterId) {
        encounterRepository.limitLockWaits();
        Encounter encounter = encounterRepository.lockById(encounterId)
                // Deleted by the command it waited for
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        eventPublisher.publishEvent(new EncounterChanged(encounterId));
        return encounter;
    }
}
