package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.encounter.internal.AttackService;
import com.tabletophelper.encounter.internal.ConditionService;
import com.tabletophelper.encounter.internal.HitPointService;
import com.tabletophelper.encounter.internal.LegendaryResistanceService;
import com.tabletophelper.encounter.internal.MonsterActionService;
import com.tabletophelper.encounter.internal.SpellcastingService;
import com.tabletophelper.encounter.internal.TurnService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Every encounter state returned here is the caller's view: the DM View for the DM, the Player View for anyone else (D157)
@RestController
@RequestMapping("/api/encounters/{encounterId}/combat")
@RequiredArgsConstructor
public class CombatController {

    private final TurnService turnService;
    private final AttackService attackService;
    private final SpellcastingService spellcastingService;
    private final MonsterActionService monsterActionService;
    private final LegendaryResistanceService legendaryResistanceService;
    private final ConditionService conditionService;
    private final HitPointService hitPointService;
    private final EncounterService encounterService;

    // A weapon attack the server works out, or an attack entered by hand, which is a Manual Modifier (D164)
    @PostMapping("/attack")
    public ResponseEntity<EncounterResponse> rollAttack(
            @PathVariable UUID encounterId,
            @Valid @RequestBody AttackRollRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = attackService.rollAttack(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // The weapons the participant can attack with and the numbers for each: the DM's for anyone, a player's for
    // their own character (D164)
    @GetMapping("/attack-options")
    public ResponseEntity<List<AttackOption>> getAttackOptions(
            @PathVariable UUID encounterId,
            @RequestParam UUID participantId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(attackService.attackOptions(encounterId, participantId, userId));
    }

    // The DM's alone (D028, D161)
    @PostMapping("/damage")
    public ResponseEntity<EncounterResponse> applyDamage(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DamageRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = hitPointService.applyDamage(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // The DM's alone (D028, D161)
    @PostMapping("/heal")
    public ResponseEntity<EncounterResponse> applyHealing(
            @PathVariable UUID encounterId,
            @Valid @RequestBody HealRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = hitPointService.applyHealing(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // The DM's Set HP / revive (D161)
    @PostMapping("/hp")
    public ResponseEntity<EncounterResponse> setHp(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SetHpRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = hitPointService.setHp(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/condition/add")
    public ResponseEntity<EncounterResponse> addCondition(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConditionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = conditionService.addCondition(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/condition/remove")
    public ResponseEntity<EncounterResponse> removeCondition(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConditionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = conditionService.removeCondition(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // A Repeat Save the campaign leaves to the player (D162)
    @PostMapping("/repeat-save")
    public ResponseEntity<EncounterResponse> rollRepeatSave(
            @PathVariable UUID encounterId,
            @Valid @RequestBody RepeatSaveRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = conditionService.rollRepeatSave(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/death-save")
    public ResponseEntity<EncounterResponse> rollDeathSave(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DeathSaveRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = hitPointService.rollDeathSave(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/concentration")
    public ResponseEntity<EncounterResponse> setConcentration(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ConcentrationRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = spellcastingService.setConcentration(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/spell-slot/use")
    public ResponseEntity<EncounterResponse> useSpellSlot(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SpellSlotRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = spellcastingService.useSpellSlot(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/spell-slot/restore")
    public ResponseEntity<EncounterResponse> restoreSpellSlot(
            @PathVariable UUID encounterId,
            @Valid @RequestBody SpellSlotRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = spellcastingService.restoreSpellSlot(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // Player characters and monsters cast alike, paying from a Spell Source (D165)
    @PostMapping("/cast-spell")
    public ResponseEntity<CastSpellResponse> castSpell(
            @PathVariable UUID encounterId,
            @Valid @RequestBody CastSpellRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        CastSpellResponse response = spellcastingService.castSpell(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // What the participant can cast now and what can pay for each spell: the DM's for anyone, a player's for
    // their own character (D164, D165)
    @GetMapping("/spell-options")
    public ResponseEntity<List<SpellOption>> getSpellOptions(
            @PathVariable UUID encounterId,
            @RequestParam UUID participantId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(spellcastingService.spellOptions(encounterId, participantId, userId));
    }

    @PostMapping("/repeat-spell-effect")
    public ResponseEntity<CastSpellResponse> repeatSpellEffect(
            @PathVariable UUID encounterId,
            @Valid @RequestBody RepeatSpellEffectRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        CastSpellResponse response = spellcastingService.repeatSpellEffect(encounterId, request, actorId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/turn/next")
    public ResponseEntity<EncounterResponse> advanceTurn(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = turnService.advanceTurn(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/turn/previous")
    public ResponseEntity<EncounterResponse> previousTurn(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = turnService.previousTurn(encounterId, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // A monster's multiattacks and the attacks each makes, for a target per attack: the DM's alone (D157, D165)
    @GetMapping("/multiattack-options")
    public ResponseEntity<List<MultiattackOption>> getMultiattackOptions(
            @PathVariable UUID encounterId,
            @RequestParam UUID participantId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(monsterActionService.multiattackOptions(encounterId, participantId, userId));
    }

    // actorId stays in the API but goes unused: the request names the monster
    @PostMapping("/monster-action")
    public ResponseEntity<MonsterActionResponse> monsterAction(
            @PathVariable UUID encounterId,
            @Valid @RequestBody MonsterActionRequest request,
            @RequestParam(required = false) UUID actorId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        MonsterActionResponse response = monsterActionService.monsterAction(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/legendary-resistance")
    public ResponseEntity<EncounterResponse> useLegendaryResistance(
            @PathVariable UUID encounterId,
            @Valid @RequestBody LegendaryResistanceRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = legendaryResistanceService.useLegendaryResistance(
                encounterId, request.combatLogId(), userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    @PostMapping("/lair-status")
    public ResponseEntity<EncounterResponse> setLairStatus(
            @PathVariable UUID encounterId,
            @Valid @RequestBody LairStatusRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = turnService.setLairStatus(encounterId, request.monsterIds(), userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // Players get the log without entries about hidden participants (D157)
    @GetMapping("/log")
    public ResponseEntity<List<CombatLogResponse>> getCombatLog(
            @PathVariable UUID encounterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        List<CombatLogResponse> log = encounterService.getCombatLog(encounterId, userId);
        return ResponseEntity.ok(log);
    }

    public record LegendaryResistanceRequest(@NotNull UUID combatLogId) {}
    public record LairStatusRequest(List<UUID> monsterIds) {}
}
