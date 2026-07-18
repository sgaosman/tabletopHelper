package com.questkeeper.encounter;

import com.questkeeper.encounter.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/encounters")
@RequiredArgsConstructor
public class EncounterController {

    private final EncounterService encounterService;
    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping
    public ResponseEntity<EncounterResponse> createEncounter(
            @Valid @RequestBody EncounterCreateRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(encounterService.createEncounter(request, userId));
    }

    @GetMapping("/campaign/{campaignId}")
    public ResponseEntity<List<EncounterResponse>> getEncountersByCampaign(
            @PathVariable UUID campaignId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(encounterService.getEncountersByCampaign(campaignId, userId));
    }

    @GetMapping("/{encounterId}")
    public ResponseEntity<EncounterResponse> getEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(encounterService.getEncounter(encounterId, userId));
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
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{encounterId}/participants/{participantId}")
    public ResponseEntity<EncounterResponse> removeParticipant(
            @PathVariable UUID encounterId,
            @PathVariable UUID participantId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.removeParticipant(encounterId, participantId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{encounterId}/initiatives")
    public ResponseEntity<EncounterResponse> setInitiatives(
            @PathVariable UUID encounterId,
            @Valid @RequestBody BulkInitiativeRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.setInitiatives(encounterId, request, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{encounterId}/initiatives/roll")
    public ResponseEntity<EncounterResponse> rollAllInitiatives(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.rollAllInitiatives(encounterId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{encounterId}/start")
    public ResponseEntity<EncounterResponse> startEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.startEncounter(encounterId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{encounterId}/pause")
    public ResponseEntity<EncounterResponse> pauseEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.pauseEncounter(encounterId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{encounterId}/resume")
    public ResponseEntity<EncounterResponse> resumeEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.resumeEncounter(encounterId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{encounterId}/end")
    public ResponseEntity<EncounterResponse> endEncounter(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = encounterService.endEncounter(encounterId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/join/{sessionCode}")
    public ResponseEntity<EncounterResponse> getBySessionCode(
            @PathVariable String sessionCode,
            Authentication authentication) {
        return ResponseEntity.ok(encounterService.getEncounterBySessionCode(sessionCode));
    }

    private void broadcastState(EncounterResponse response) {
        messagingTemplate.convertAndSend(
                "/topic/encounter/" + response.getId() + "/state", response);
    }
}
