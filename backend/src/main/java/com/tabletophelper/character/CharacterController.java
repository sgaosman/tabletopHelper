package com.tabletophelper.character;

import com.tabletophelper.character.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Characters. The server is the only authority over a character (D158): besides creating it, levelling it and
 * making the Pending Choices its levels owe (D159), its owner changes it only through the intent endpoints below, and
 * the DM of its campaign only through DM Adjustments and by reviving it once it has died, each answering with the
 * character as the server then works it out. Encounters change it only through Write-back (D166).
 */
@RestController
@RequestMapping("/api/characters")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;
    private final CharacterSheetService sheetService;
    private final RestService restService;
    private final CharacterSpellService spellService;
    private final DmAdjustmentService adjustmentService;
    private final PendingChoiceService choiceService;
    private final WriteBackService writeBackService;

    @PostMapping
    public ResponseEntity<CharacterResponse> createCharacter(
            @Valid @RequestBody CharacterCreateRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(characterService.createCharacter(request, userId));
    }

    @GetMapping
    public ResponseEntity<List<CharacterResponse>> getMyCharacters(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(characterService.getMyCharacters(userId));
    }

    @GetMapping("/campaign/{campaignId}")
    public ResponseEntity<List<CharacterResponse>> getCharactersInCampaign(
            @PathVariable UUID campaignId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(characterService.getCharactersInCampaign(campaignId, userId));
    }

    @GetMapping("/{characterId}")
    public ResponseEntity<CharacterResponse> getCharacter(
            @PathVariable UUID characterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(characterService.getCharacter(characterId, userId));
    }

    // ── The sheet ────────────────────────────────────────────────

    @PatchMapping("/{characterId}/details")
    public ResponseEntity<CharacterResponse> updateDetails(
            @PathVariable UUID characterId,
            @Valid @RequestBody CharacterDetailsRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(sheetService.updateDetails(characterId, request, userId));
    }

    @PutMapping("/{characterId}/campaign")
    public ResponseEntity<CharacterResponse> setCampaign(
            @PathVariable UUID characterId,
            @Valid @RequestBody CharacterCampaignRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(sheetService.setCampaign(characterId, request, userId));
    }

    @PutMapping("/{characterId}/inventory")
    public ResponseEntity<CharacterResponse> updateInventory(
            @PathVariable UUID characterId,
            @Valid @RequestBody InventoryRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(sheetService.updateInventory(characterId, request, userId));
    }

    @PostMapping("/{characterId}/hp")
    public ResponseEntity<CharacterResponse> changeHitPoints(
            @PathVariable UUID characterId,
            @Valid @RequestBody HitPointChangeRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(sheetService.changeHitPoints(characterId, request, userId));
    }

    /** Spends a slot of a spell level, "1" to "9", or a pact slot, "pact_N". */
    @PostMapping("/{characterId}/spell-slots/{slot}/spend")
    public ResponseEntity<CharacterResponse> spendSlot(
            @PathVariable UUID characterId,
            @PathVariable String slot,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(sheetService.spendSlot(characterId, slot, userId));
    }

    @PostMapping("/{characterId}/spell-slots/{slot}/restore")
    public ResponseEntity<CharacterResponse> restoreSlot(
            @PathVariable UUID characterId,
            @PathVariable String slot,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(sheetService.restoreSlot(characterId, slot, userId));
    }

    @PostMapping("/{characterId}/pools/spend")
    public ResponseEntity<CharacterResponse> spendPool(
            @PathVariable UUID characterId,
            @Valid @RequestBody PoolUseRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(sheetService.spendPool(characterId, request, userId));
    }

    @PostMapping("/{characterId}/pools/recover")
    public ResponseEntity<CharacterResponse> recoverPool(
            @PathVariable UUID characterId,
            @Valid @RequestBody PoolUseRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(sheetService.recoverPool(characterId, request, userId));
    }

    // ── Rests ────────────────────────────────────────────────────

    /** A short rest, answering with the rested character and what each Hit Die it spent rolled (D158). */
    @PostMapping("/{characterId}/short-rest")
    public ResponseEntity<ShortRestResponse> shortRest(
            @PathVariable UUID characterId,
            @Valid @RequestBody(required = false) ShortRestRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(restService.shortRest(characterId, request, userId));
    }

    @PostMapping("/{characterId}/long-rest")
    public ResponseEntity<CharacterResponse> longRest(
            @PathVariable UUID characterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(restService.longRest(characterId, userId));
    }

    // ── Spells ───────────────────────────────────────────────────

    @PutMapping("/{characterId}/spells/prepared")
    public ResponseEntity<CharacterResponse> setPreparedSpells(
            @PathVariable UUID characterId,
            @Valid @RequestBody PreparedSpellsRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(spellService.setPrepared(characterId, request, userId));
    }

    @PostMapping("/{characterId}/spellbook")
    public ResponseEntity<CharacterResponse> addToSpellbook(
            @PathVariable UUID characterId,
            @Valid @RequestBody SpellbookRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(spellService.addToSpellbook(characterId, request, userId));
    }

    @DeleteMapping("/{characterId}/spellbook")
    public ResponseEntity<CharacterResponse> removeFromSpellbook(
            @PathVariable UUID characterId,
            @Valid @RequestBody SpellbookRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(spellService.removeFromSpellbook(characterId, request, userId));
    }

    // ── DM Adjustments ───────────────────────────────────────────

    /** Adds a DM Adjustment, which only the DM of the character's campaign may (D158). */
    @PostMapping("/{characterId}/adjustments")
    public ResponseEntity<CharacterResponse> addAdjustment(
            @PathVariable UUID characterId,
            @Valid @RequestBody DmAdjustmentRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(adjustmentService.add(characterId, request, userId));
    }

    /** Removes a DM Adjustment, which stays on the sheet as the record. */
    @DeleteMapping("/{characterId}/adjustments/{adjustmentId}")
    public ResponseEntity<CharacterResponse> removeAdjustment(
            @PathVariable UUID characterId,
            @PathVariable UUID adjustmentId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(adjustmentService.remove(characterId, adjustmentId, userId));
    }

    /** Revives a character who died in an encounter, at 1 hit point, which only the DM of its campaign may (D166). */
    @PostMapping("/{characterId}/revive")
    public ResponseEntity<CharacterResponse> revive(
            @PathVariable UUID characterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(writeBackService.revive(characterId, userId));
    }

    // ── Levels ───────────────────────────────────────────────────

    @PostMapping("/{characterId}/level-up")
    public ResponseEntity<LevelUpResponse> levelUp(
            @PathVariable UUID characterId,
            @RequestBody(required = false) LevelUpRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(characterService.levelUp(characterId, request, userId));
    }

    @PostMapping("/{characterId}/level-down")
    public ResponseEntity<CharacterResponse> levelDown(
            @PathVariable UUID characterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(characterService.levelDown(characterId, userId));
    }

    /** Makes one of the character's Pending Choices, named by its ID (D159). */
    @PostMapping("/{characterId}/apply-choices")
    public ResponseEntity<CharacterResponse> applyChoices(
            @PathVariable UUID characterId,
            @Valid @RequestBody ApplyChoicesRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(choiceService.apply(characterId, request, userId));
    }

    @GetMapping("/{characterId}/eligible-classes")
    public ResponseEntity<List<EligibleClassResponse>> getEligibleClasses(
            @PathVariable UUID characterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(characterService.getEligibleClasses(characterId, userId));
    }

    @DeleteMapping("/{characterId}")
    public ResponseEntity<Void> deleteCharacter(
            @PathVariable UUID characterId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        characterService.deleteCharacter(characterId, userId);
        return ResponseEntity.noContent().build();
    }
}
