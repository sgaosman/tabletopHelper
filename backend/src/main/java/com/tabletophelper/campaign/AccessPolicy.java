package com.tabletophelper.campaign;

import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Decides who may read or change campaign data (D155). Services call it before doing anything else.
 * It takes IDs rather than entities, so the campaign module depends on no other module (D151).
 * A refusal is a {@link ForbiddenException}. A campaign ID that matches nothing is a
 * {@link NotFoundException}, so a campaign ID from the URL can be passed straight in.
 */
@Service
@RequiredArgsConstructor
public class AccessPolicy {

    private final CampaignRepository campaignRepository;
    private final CampaignMemberRepository campaignMemberRepository;

    /** Any member of the campaign, the DM included. */
    public void requireMember(UUID campaignId, UUID userId) {
        if (!campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, userId)) {
            loadCampaign(campaignId);
            throw new ForbiddenException("You are not a member of this campaign");
        }
    }

    /** The campaign's DM. */
    public void requireDm(UUID campaignId, UUID userId) {
        if (!loadCampaign(campaignId).getDm().getId().equals(userId)) {
            throw new ForbiddenException("Only the DM can perform this action");
        }
    }

    /** Changing a character: its owner only, not even the DM, who changes it only through DM Adjustments (D158). */
    public void requireCharacterOwner(UUID ownerId, UUID userId) {
        if (!userId.equals(ownerId)) {
            throw new ForbiddenException("You do not own this character");
        }
    }

    /**
     * Adjusting a character with a DM Adjustment (D158): the DM of the campaign it belongs to, and nobody else, its
     * owner included. {@code campaignId} is null when the character belongs to no campaign, and then it has no DM.
     */
    public void requireCharacterDm(UUID campaignId, UUID userId) {
        if (campaignId == null) {
            throw new ForbiddenException("This character isn't in a campaign, so it has no DM to adjust it");
        }
        if (!isDm(campaignId, userId)) {
            throw new ForbiddenException("Only the DM of this character's campaign can adjust it");
        }
    }

    /**
     * Reading a character: its owner, or the DM of the campaign it belongs to. {@code campaignId} is
     * null when the character belongs to no campaign.
     */
    public void requireOwnerOrDm(UUID ownerId, UUID campaignId, UUID userId) {
        if (!userId.equals(ownerId) && (campaignId == null || !isDm(campaignId, userId))) {
            throw new ForbiddenException("You do not have access to this character");
        }
    }

    /**
     * Adding a character to an encounter: its owner must be a member of the campaign, so a DM cannot
     * bring in, and later write back to, a character whose owner never joined.
     */
    public void requireOwnerIsMember(UUID campaignId, UUID ownerId) {
        if (!campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, ownerId)) {
            throw new ForbiddenException("This character's owner is not a member of this campaign");
        }
    }

    /**
     * Acting for an encounter participant (D028): the DM may act for anyone, a player only for a
     * participant they control. {@code controllerId} is the participant's controlling user, null for
     * monsters or when no participant is named.
     */
    public void requireControls(UUID campaignId, UUID controllerId, UUID userId) {
        if (!isDm(campaignId, userId) && !userId.equals(controllerId)) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
    }

    /** As above, and a player may attack or cast only while it is that participant's turn (D028). */
    public void requireControls(UUID campaignId, UUID controllerId, boolean isTheirTurn, UUID userId) {
        if (isDm(campaignId, userId)) {
            return;
        }
        if (!userId.equals(controllerId)) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
        if (!isTheirTurn) {
            throw new ForbiddenException("You can only attack on your turn");
        }
    }

    private boolean isDm(UUID campaignId, UUID userId) {
        return campaignRepository.findById(campaignId)
                .map(campaign -> campaign.getDm().getId().equals(userId))
                .orElse(false);
    }

    private Campaign loadCampaign(UUID campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new NotFoundException("Campaign not found"));
    }
}
