package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.encounter.internal.AttackService;
import com.tabletophelper.encounter.internal.BardicInspirationService;
import com.tabletophelper.encounter.internal.ConditionService;
import com.tabletophelper.encounter.internal.FontOfMagicService;
import com.tabletophelper.encounter.internal.HitFollowUpService;
import com.tabletophelper.encounter.internal.HitPointService;
import com.tabletophelper.encounter.internal.LegendaryResistanceService;
import com.tabletophelper.encounter.internal.MonsterActionService;
import com.tabletophelper.encounter.internal.PoolUseService;
import com.tabletophelper.encounter.internal.SpellcastingService;
import com.tabletophelper.encounter.internal.StandardActionService;
import com.tabletophelper.encounter.internal.SummonService;
import com.tabletophelper.encounter.internal.TurnEconomyEditService;
import com.tabletophelper.encounter.internal.TurnService;
import com.tabletophelper.encounter.internal.UndoService;
import com.tabletophelper.encounter.internal.WildShapeService;
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
    private final PoolUseService poolUseService;
    private final StandardActionService standardActionService;
    private final TurnEconomyEditService turnEconomyEditService;
    private final ConditionService conditionService;
    private final HitPointService hitPointService;
    private final HitFollowUpService hitFollowUpService;
    private final BardicInspirationService bardicInspirationService;
    private final FontOfMagicService fontOfMagicService;
    private final WildShapeService wildShapeService;
    private final SummonService summonService;
    private final UndoService undoService;
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

    // Divine Smite on the paladin's latest melee weapon hit this turn, spending a spell slot (PHB p.85, M17): the DM
    // for anyone, a player for their own participant, on any turn
    @PostMapping("/divine-smite")
    public ResponseEntity<EncounterResponse> divineSmite(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DivineSmiteRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = hitFollowUpService.divineSmite(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // Stunning Strike on the monk's latest melee weapon hit this turn, spending 1 ki (PHB p.79, M17): the DM for
    // anyone, a player for their own participant, on any turn
    @PostMapping("/stunning-strike")
    public ResponseEntity<EncounterResponse> stunningStrike(
            @PathVariable UUID encounterId,
            @Valid @RequestBody StunningStrikeRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = hitFollowUpService.stunningStrike(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // Deft Strike on a Kensei monk's latest hit with a kensei weapon this turn, spending 1 ki (XGE p.34): the DM for
    // anyone, a player for their own participant, on the monk's own turn
    @PostMapping("/deft-strike")
    public ResponseEntity<EncounterResponse> deftStrike(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DeftStrikeRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = hitFollowUpService.deftStrike(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // A held Bardic Inspiration die kept for the next failed save, kept for an attack, or rolled now for a check or save
    // the DM resolves (PHB p.53, M17): the DM for anyone, a player for their own participant, on any turn
    @PostMapping("/bardic-inspiration")
    public ResponseEntity<EncounterResponse> bardicInspiration(
            @PathVariable UUID encounterId,
            @Valid @RequestBody BardicInspirationRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = bardicInspirationService.use(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // The Protection fighting style's reaction, logged for the DM to apply (PHB p.72, M17)
    @PostMapping("/protection")
    public ResponseEntity<EncounterResponse> protection(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ProtectionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = hitFollowUpService.protection(encounterId, request, userId);
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

    // What the attacker's and the target's conditions do to an attack roll between them: the DM's for any attacker or
    // none (the DM's own attack), a player's for a creature they control
    @GetMapping("/attack-roll-preview")
    public ResponseEntity<AttackRollPreview> getAttackRollPreview(
            @PathVariable UUID encounterId,
            @RequestParam(required = false) UUID attackerId,
            @RequestParam UUID targetId,
            @RequestParam(defaultValue = "true") boolean withinFiveFeet,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(attackService.attackRollPreview(encounterId, attackerId, targetId, withinFiveFeet,
                userId));
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

    // Ends one of a participant's Spell Effects by hand, and the concentration it belongs to (M15): the DM's for
    // anyone, a player's for their own character
    @PostMapping("/spell-effect/end")
    public ResponseEntity<EncounterResponse> endSpellEffect(
            @PathVariable UUID encounterId,
            @Valid @RequestBody EndSpellEffectRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = spellcastingService.endSpellEffect(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // What a summoning spell can bring when the caster casts it at the slot level (D182): the DM's for anyone, a
    // player's for a participant they control
    @GetMapping("/summon-options")
    public ResponseEntity<SummonOptions> getSummonOptions(
            @PathVariable UUID encounterId,
            @RequestParam UUID participantId,
            @RequestParam String spellName,
            @RequestParam(required = false) Integer slotLevel,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(summonService.options(encounterId, participantId, spellName, slotLevel, userId));
    }

    // The DM's creatures for a summoning that waits for them, or in place of a placed summoning's (D182)
    @PostMapping("/summons")
    public ResponseEntity<EncounterResponse> placeSummon(
            @PathVariable UUID encounterId,
            @Valid @RequestBody PlaceSummonRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = summonService.place(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // A summoning the DM places with no slot spent, while the encounter is prepared or active (D182)
    @PostMapping("/summons/companion")
    public ResponseEntity<EncounterResponse> placeCompanion(
            @PathVariable UUID encounterId,
            @Valid @RequestBody CompanionSummonRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = summonService.companion(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // The DM dismisses summoned creatures, while their spell goes on (D182)
    @PostMapping("/summons/dismiss")
    public ResponseEntity<EncounterResponse> dismissSummons(
            @PathVariable UUID encounterId,
            @Valid @RequestBody DismissSummonsRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = summonService.dismiss(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // A caster's bonus-action command to its Animate Dead or Tiny Servant creatures (PHB p.212, XGE p.168, D182): the
    // DM's for anyone, a player's for their own caster on its turn
    @PostMapping("/summons/command")
    public ResponseEntity<EncounterResponse> commandSummons(
            @PathVariable UUID encounterId,
            @Valid @RequestBody CommandSummonsRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = summonService.command(encounterId, request, userId);
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

    // The DM's alone (M14): undoes the newest combat commands, refused unless expectedCommandId is still the newest
    @PostMapping("/undo")
    public ResponseEntity<EncounterResponse> undo(
            @PathVariable UUID encounterId,
            @Valid @RequestBody UndoRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = undoService.undo(encounterId, request, userId);
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

    // A sorcerer's Flexible Casting, a bonus action on their turn: a slot for sorcery points or points for a slot
    // (PHB p.101, M18): the DM for anyone, a player for their own participant
    @PostMapping("/font-of-magic")
    public ResponseEntity<EncounterResponse> fontOfMagic(
            @PathVariable UUID encounterId,
            @Valid @RequestBody FontOfMagicUseRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = fontOfMagicService.use(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // The beasts a druid can become with Wild Shape now (PHB p.66): the DM's for anyone, a player's for their own
    // character; Wild Shape itself is used through use-pool with the beast's ID
    @GetMapping("/wild-shape-options")
    public ResponseEntity<WildShapeOptions> getWildShapeOptions(
            @PathVariable UUID encounterId,
            @RequestParam UUID participantId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(wildShapeService.options(encounterId, participantId, userId));
    }

    // A Circle of the Moon druid's Combat Wild Shape healing, a bonus action while transformed that expends a spell
    // slot for 1d8 HP per level (PHB p.69): the DM for anyone, a player for their own participant
    @PostMapping("/wild-shape-heal")
    public ResponseEntity<EncounterResponse> wildShapeHeal(
            @PathVariable UUID encounterId,
            @Valid @RequestBody WildShapeHealRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = wildShapeService.heal(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // A participant's resource pool, costing its spendActionType from the turn: the DM uses anyone's, a player
    // their own participant's (D028, D174)
    @PostMapping("/use-pool")
    public ResponseEntity<EncounterResponse> usePool(
            @PathVariable UUID encounterId,
            @Valid @RequestBody UsePoolRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = poolUseService.usePool(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // Dash, Disengage, Dodge, Help, Hide, Ready, Search or Use an Object on the participant's turn: the DM for
    // anyone, a player for their own participant (D028, D174)
    @PostMapping("/standard-action")
    public ResponseEntity<EncounterResponse> standardAction(
            @PathVariable UUID encounterId,
            @Valid @RequestBody StandardActionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = standardActionService.standardAction(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // An object interaction on the participant's turn, the first each turn free (D174)
    @PostMapping("/object-interaction")
    public ResponseEntity<EncounterResponse> objectInteraction(
            @PathVariable UUID encounterId,
            @Valid @RequestBody ObjectInteractionRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = standardActionService.objectInteraction(encounterId, request, userId);
        return ResponseEntity.ok(EncounterViews.forUser(response, userId));
    }

    // The DM's alone: sets any part of a participant's turn economy (D174)
    @PutMapping("/turn-economy")
    public ResponseEntity<EncounterResponse> setTurnEconomy(
            @PathVariable UUID encounterId,
            @Valid @RequestBody TurnEconomyRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        EncounterResponse response = turnEconomyEditService.setTurnEconomy(encounterId, request, userId);
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
