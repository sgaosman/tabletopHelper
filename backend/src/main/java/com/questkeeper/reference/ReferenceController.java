package com.questkeeper.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reference")
@RequiredArgsConstructor
public class ReferenceController {

    private final SpellRepository spellRepository;
    private final ConditionRepository conditionRepository;
    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/spells")
    public Page<Spell> searchSpells(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String school,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String subclass,
            @RequestParam(required = false) String concentration,
            @RequestParam(required = false) String ritual,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return spellRepository.searchSpells(
                name != null && name.isBlank() ? null : name,
                level, school, source, className, subclass,
                concentration, ritual, pageable);
    }

    @GetMapping("/spells/{id}")
    public ResponseEntity<Spell> getSpell(@PathVariable UUID id) {
        return spellRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/spells/filters/schools")
    public List<String> getSpellSchools() {
        return spellRepository.findDistinctSchools();
    }

    @GetMapping("/spells/filters/sources")
    public List<String> getSpellSources() {
        return spellRepository.findDistinctSources();
    }

    @GetMapping("/spells/filters/classes")
    public List<String> getSpellClasses() {
        return spellRepository.findDistinctClasses();
    }

    @GetMapping("/spells/filters/subclasses")
    public List<String> getSpellSubclasses(@RequestParam String className) {
        return spellRepository.findDistinctSubclasses(className);
    }

    @GetMapping("/conditions")
    public List<Condition> getAllConditions() {
        return conditionRepository.findAll(Sort.by("name"));
    }

    @GetMapping("/conditions/{id}")
    public ResponseEntity<Condition> getCondition(@PathVariable UUID id) {
        return conditionRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/items")
    public Page<Item> searchItems(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String rarity,
            @RequestParam(required = false) String source,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return itemRepository.searchItems(
                name != null && name.isBlank() ? null : name,
                type, rarity, source, pageable);
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<Item> getItem(@PathVariable UUID id) {
        return itemRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/items/filters/types")
    public List<String> getItemTypes() {
        return itemRepository.findDistinctTypes();
    }

    @GetMapping("/items/filters/rarities")
    public List<String> getItemRarities() {
        return itemRepository.findDistinctRarities();
    }

    @GetMapping("/items/filters/sources")
    public List<String> getItemSources() {
        return itemRepository.findDistinctSources();
    }

    @GetMapping("/quickref")
    public ResponseEntity<JsonNode> getQuickReference() {
        try {
            ClassPathResource resource = new ClassPathResource("data/5etools/generated/bookref-quick.json");
            JsonNode root = objectMapper.readTree(resource.getInputStream());
            return ResponseEntity.ok(root.path("data").path("bookref-quick"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
