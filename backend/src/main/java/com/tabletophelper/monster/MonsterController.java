package com.tabletophelper.monster;

import com.tabletophelper.campaign.AccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The bestiary, for DMs only: a user who is the DM of no campaign gets a 403, as its stat blocks give monsters' AC,
 * speed and every other number the Player View keeps from players (D235).
 */
@RestController
@RequestMapping("/api/monsters")
@RequiredArgsConstructor
public class MonsterController {

    private final MonsterService monsterService;
    private final AccessPolicy accessPolicy;

    @GetMapping
    public Page<Monster> searchMonsters(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cr,
            @RequestParam(required = false) String source,
            @PageableDefault(size = 20, sort = "name") Pageable pageable,
            Authentication authentication) {
        requireDm(authentication);
        return monsterService.searchMonsters(name, type, cr, source, pageable);
    }

    @GetMapping("/search")
    public List<Monster> fuzzySearch(
            @RequestParam String name,
            @RequestParam(defaultValue = "10") int maxResults,
            Authentication authentication) {
        requireDm(authentication);
        return monsterService.fuzzySearchByName(name, Math.min(maxResults, 20));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Monster> getMonster(@PathVariable UUID id, Authentication authentication) {
        requireDm(authentication);
        return ResponseEntity.ok(monsterService.getMonster(id));
    }

    @GetMapping("/filters/sources")
    public List<String> getSources(Authentication authentication) {
        requireDm(authentication);
        return monsterService.getSources();
    }

    @GetMapping("/filters/types")
    public List<String> getTypes(Authentication authentication) {
        requireDm(authentication);
        return monsterService.getTypes();
    }

    @GetMapping("/filters/challenge-ratings")
    public List<String> getChallengeRatings(Authentication authentication) {
        requireDm(authentication);
        return monsterService.getChallengeRatings();
    }

    private void requireDm(Authentication authentication) {
        accessPolicy.requireAnyCampaignDm((UUID) authentication.getPrincipal());
    }
}
