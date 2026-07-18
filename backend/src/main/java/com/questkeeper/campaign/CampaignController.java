package com.questkeeper.campaign;

import com.questkeeper.campaign.dto.CampaignCreateRequest;
import com.questkeeper.campaign.dto.CampaignJoinRequest;
import com.questkeeper.campaign.dto.CampaignResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;

    @PostMapping
    public ResponseEntity<CampaignResponse> createCampaign(
            @Valid @RequestBody CampaignCreateRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(campaignService.createCampaign(request, userId));
    }

    @PostMapping("/join")
    public ResponseEntity<CampaignResponse> joinCampaign(
            @Valid @RequestBody CampaignJoinRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(campaignService.joinCampaign(request.getInviteCode(), userId));
    }

    @GetMapping
    public ResponseEntity<List<CampaignResponse>> getMyCampaigns(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(campaignService.getMyCampaigns(userId));
    }

    @GetMapping("/{campaignId}")
    public ResponseEntity<CampaignResponse> getCampaign(
            @PathVariable UUID campaignId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(campaignService.getCampaign(campaignId, userId));
    }
}
