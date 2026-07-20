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

@RestController
@RequestMapping("/api/characters")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;

    @PostMapping
    public ResponseEntity<CharacterResponse> createCharacter(
            @Valid @RequestBody CharacterCreateRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(characterService.createCharacter(request, userId));
    }

    @PutMapping("/{characterId}")
    public ResponseEntity<CharacterResponse> updateCharacter(
            @PathVariable UUID characterId,
            @Valid @RequestBody CharacterUpdateRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(characterService.updateCharacter(characterId, request, userId));
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
    public ResponseEntity<CharacterResponse> getCharacter(@PathVariable UUID characterId) {
        return ResponseEntity.ok(characterService.getCharacter(characterId));
    }

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

    @PostMapping("/{characterId}/apply-choices")
    public ResponseEntity<CharacterResponse> applyChoices(
            @PathVariable UUID characterId,
            @Valid @RequestBody ApplyChoicesRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(characterService.applyChoices(characterId, request, userId));
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
