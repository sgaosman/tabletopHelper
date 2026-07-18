package com.questkeeper.encounter;

import com.questkeeper.encounter.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/encounters/{encounterId}/combat")
@RequiredArgsConstructor
public class CombatController {

    private final CombatService combatService;
    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping("/attack")
    public ResponseEntity<EncounterResponse> rollAttack(
            @PathVariable UUID encounterId,
            @Valid @RequestBody AttackRollRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.rollAttack(encounterId, request, actorId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/damage")
    public ResponseEntity<EncounterResponse> applyDamage(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DamageRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.applyDamage(encounterId, request, actorId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/heal")
    public ResponseEntity<EncounterResponse> applyHealing(
            @PathVariable UUID encounterId,
            @Valid @RequestBody HealRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.applyHealing(encounterId, request, actorId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/hp")
    public ResponseEntity<EncounterResponse> setHp(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SetHpRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.setHp(encounterId, request, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/condition/add")
    public ResponseEntity<EncounterResponse> addCondition(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConditionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.addCondition(encounterId, request, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/condition/remove")
    public ResponseEntity<EncounterResponse> removeCondition(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConditionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.removeCondition(encounterId, request, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/death-save")
    public ResponseEntity<EncounterResponse> rollDeathSave(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DeathSaveRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.rollDeathSave(encounterId, request, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/concentration")
    public ResponseEntity<EncounterResponse> setConcentration(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConcentrationRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.setConcentration(encounterId, request, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/spell-slot/use")
    public ResponseEntity<EncounterResponse> useSpellSlot(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SpellSlotRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.useSpellSlot(encounterId, request, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/spell-slot/restore")
    public ResponseEntity<EncounterResponse> restoreSpellSlot(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SpellSlotRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.restoreSpellSlot(encounterId, request, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/turn/next")
    public ResponseEntity<EncounterResponse> advanceTurn(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.advanceTurn(encounterId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/turn/previous")
    public ResponseEntity<EncounterResponse> previousTurn(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.previousTurn(encounterId, userId);
        broadcastState(response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/log")
    public ResponseEntity<List<CombatLogResponse>> getCombatLog(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        List<CombatLogResponse> log = combatService.getCombatLog(encounterId, userId);
        return ResponseEntity.ok(log);
    }

    private void broadcastState(EncounterResponse response) {
        messagingTemplate.convertAndSend(
                "/topic/encounter/" + response.getId() + "/state", response);
    }
}
