package com.questkeeper.monster;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/monsters")
@RequiredArgsConstructor
public class MonsterController {

    private final MonsterService monsterService;

    @GetMapping
    public Page<Monster> searchMonsters(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cr,
            @RequestParam(required = false) String source,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return monsterService.searchMonsters(name, type, cr, source, pageable);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Monster> getMonster(@PathVariable UUID id) {
        return ResponseEntity.ok(monsterService.getMonster(id));
    }

    @GetMapping("/filters/sources")
    public List<String> getSources() {
        return monsterService.getSources();
    }

    @GetMapping("/filters/types")
    public List<String> getTypes() {
        return monsterService.getTypes();
    }

    @GetMapping("/filters/challenge-ratings")
    public List<String> getChallengeRatings() {
        return monsterService.getChallengeRatings();
    }
}
