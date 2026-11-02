package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.shared.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Every encounter state returned here is the caller's view: the DM View for the DM, the Player View for anyone else (D157)
@RestController
@RequestMapping("/api/encounters")
@RequiredArgsConstructor
public class EncounterController {

    private final EncounterService encounterService;

    @PostMapping
    public ResponseEntity<EncounterResponse> createEncounter(
            @Valid @RequestBody EncounterCreateRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.createEncounter(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EncounterViews.forUser(response, userId));
    }

    @GetMapping("/campaign/{campaignId}")
    public ResponseEntity<List<EncounterResponse>> getEncountersByCampaign(
            @PathVariable UUID campaignId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(encounterService.getEncountersByCampaign(campaignId, userId).stream()
                .map(encounter -> EncounterViews.forUser(encounter, userId))
                .toList());
    }

    @GetMapping("/{encounterId}")
    public ResponseEntity<EncounterResponse> getEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.getEncounter(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @DeleteMapping("/{encounterId}")
    public ResponseEntity<Void> deleteEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        encounterService.deleteEncounter(encounterId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{encounterId}/participants")
    public ResponseEntity<EncounterResponse> addParticipant(
            @PathVariable UUID encounterId,
            @Valid @RequestBody AddParticipantRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.addParticipant(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PatchMapping("/{encounterId}/participants/{participantId}/name")
    public ResponseEntity<EncounterResponse> renameParticipant(
            @PathVariable UUID encounterId,
            @PathVariable UUID participantId,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        String newName = body.get("displayName");
        if (newName == null || newName.isBlank()) {
            throw new ValidationException("displayName is required");
        }
        EncounterResponse response = encounterService.renameParticipant(encounterId, participantId, newName, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PatchMapping("/{encounterId}/participants/{participantId}/visibility")
    public ResponseEntity<EncounterResponse> setParticipantVisibility(
            @PathVariable UUID encounterId,
            @PathVariable UUID participantId,
            @Valid @RequestBody VisibilityRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.setParticipantVisibility(
                encounterId, participantId, request.visible(), userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @DeleteMapping("/{encounterId}/participants/{participantId}")
    public ResponseEntity<EncounterResponse> removeParticipant(
            @PathVariable UUID encounterId,
            @PathVariable UUID participantId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.removeParticipant(encounterId, participantId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/{encounterId}/initiatives")
    public ResponseEntity<EncounterResponse> setInitiatives(
            @PathVariable UUID encounterId,
            @Valid @RequestBody BulkInitiativeRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.setInitiatives(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/{encounterId}/initiatives/roll")
    public ResponseEntity<EncounterResponse> rollAllInitiatives(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.rollAllInitiatives(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/{encounterId}/start")
    public ResponseEntity<EncounterResponse> startEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.startEncounter(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/{encounterId}/pause")
    public ResponseEntity<EncounterResponse> pauseEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.pauseEncounter(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/{encounterId}/resume")
    public ResponseEntity<EncounterResponse> resumeEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.resumeEncounter(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/{encounterId}/end")
    public ResponseEntity<EncounterResponse> endEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.endEncounter(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // DM only: action templates are part of the DM View (D157)
    @GetMapping("/{encounterId}/participants/{participantId}/actions")
    public ResponseEntity<Map<String, Object>> getMonsterActions(
            @PathVariable UUID encounterId,
            @PathVariable UUID participantId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(encounterService.getMonsterActionTemplates(encounterId, participantId, userId));
    }

    @GetMapping("/join/{sessionCode}")
    public ResponseEntity<EncounterResponse> getBySessionCode(
            @PathVariable String sessionCode,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.getEncounterBySessionCode(sessionCode, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    public record VisibilityRequest(@NotNull Boolean visible) {}
}
