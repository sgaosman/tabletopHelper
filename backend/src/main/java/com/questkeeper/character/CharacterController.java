package com.questkeeper.character;

import com.questkeeper.character.dto.CharacterCreateRequest;
import com.questkeeper.character.dto.CharacterResponse;
import com.questkeeper.character.dto.CharacterUpdateRequest;
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
            @RequestBody CharacterUpdateRequest request,
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
}
