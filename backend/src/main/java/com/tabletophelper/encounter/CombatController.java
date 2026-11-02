package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Every encounter state returned here is the caller's view: the DM View for the DM, the Player View for anyone else (D157)
@RestController
@RequestMapping("/api/encounters/{encounterId}/combat")
@RequiredArgsConstructor
public class CombatController {

    private final CombatService combatService;

    @PostMapping("/attack")
    public ResponseEntity<EncounterResponse> rollAttack(
            @PathVariable UUID encounterId,
            @Valid @RequestBody AttackRollRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.rollAttack(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/damage")
    public ResponseEntity<EncounterResponse> applyDamage(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DamageRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.applyDamage(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/heal")
    public ResponseEntity<EncounterResponse> applyHealing(
            @PathVariable UUID encounterId,
            @Valid @RequestBody HealRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.applyHealing(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/hp")
    public ResponseEntity<EncounterResponse> setHp(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SetHpRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.setHp(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/condition/add")
    public ResponseEntity<EncounterResponse> addCondition(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConditionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.addCondition(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/condition/remove")
    public ResponseEntity<EncounterResponse> removeCondition(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConditionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.removeCondition(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/death-save")
    public ResponseEntity<EncounterResponse> rollDeathSave(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DeathSaveRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.rollDeathSave(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/concentration")
    public ResponseEntity<EncounterResponse> setConcentration(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConcentrationRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.setConcentration(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/spell-slot/use")
    public ResponseEntity<EncounterResponse> useSpellSlot(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SpellSlotRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.useSpellSlot(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/spell-slot/restore")
    public ResponseEntity<EncounterResponse> restoreSpellSlot(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SpellSlotRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.restoreSpellSlot(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/cast-spell")
    public ResponseEntity<CastSpellResponse> castSpell(
            @PathVariable UUID encounterId,
            @Valid @RequestBody CastSpellRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        CastSpellResponse response = combatService.castSpell(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/repeat-spell-effect")
    public ResponseEntity<CastSpellResponse> repeatSpellEffect(
            @PathVariable UUID encounterId,
            @Valid @RequestBody RepeatSpellEffectRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        CastSpellResponse response = combatService.repeatSpellEffect(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/turn/next")
    public ResponseEntity<EncounterResponse> advanceTurn(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.advanceTurn(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/turn/previous")
    public ResponseEntity<EncounterResponse> previousTurn(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.previousTurn(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/monster-action")
    public ResponseEntity<CombatService.MonsterActionResponse> monsterAction(
            @PathVariable UUID encounterId,
            @Valid @RequestBody CombatService.MonsterActionRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        CombatService.MonsterActionResponse response = combatService.monsterAction(
                encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/monster-spell")
    public ResponseEntity<CastSpellResponse> monsterSpell(
            @PathVariable UUID encounterId,
            @Valid @RequestBody CombatService.MonsterSpellRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        CastSpellResponse response = combatService.monsterSpell(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/legendary-resistance")
    public ResponseEntity<EncounterResponse> useLegendaryResistance(
            @PathVariable UUID encounterId,
            @Valid @RequestBody LegendaryResistanceRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.useLegendaryResistance(
                encounterId, request.combatLogId(), userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/lair-status")
    public ResponseEntity<EncounterResponse> setLairStatus(
            @PathVariable UUID encounterId,
            @Valid @RequestBody LairStatusRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = combatService.setLairStatus(
                encounterId, request.monsterIds(), userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // Players get the log without entries about hidden participants (D157)
    @GetMapping("/log")
    public ResponseEntity<List<CombatLogResponse>> getCombatLog(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        List<CombatLogResponse> log = combatService.getCombatLog(encounterId, userId);
        return ResponseEntity.ok(log);
    }

    public record LegendaryResistanceRequest(@NotNull UUID combatLogId) {}
    public record LairStatusRequest(List<UUID> monsterIds) {}
}
