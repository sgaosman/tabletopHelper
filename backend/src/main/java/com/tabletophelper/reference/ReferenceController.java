package com.tabletophelper.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reference")
@RequiredArgsConstructor
public class ReferenceController {

    private final SpellRepository spellRepository;
    private final ConditionRepository conditionRepository;
    private final ItemRepository itemRepository;
    private final RaceRepository raceRepository;
    private final CharacterClassRepository characterClassRepository;
    private final SubclassRepository subclassRepository;
    private final BackgroundRepository backgroundRepository;
    private final FeatRepository featRepository;
    private final OptionalFeatureRepository optionalFeatureRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/spells")
    public Page<Spell> searchSpells(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String school,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String subclass,
            @RequestParam(required = false) String concentration,
            @RequestParam(required = false) String ritual,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        List<Integer> levelList = (level != null && !level.isBlank())
                ? Arrays.stream(level.split(",")).map(Integer::parseInt).toList()
                : List.of();
        List<String> schoolList = splitFilter(school);
        List<String> sourceList = splitFilter(source);
        List<String> classList = new ArrayList<>();
        if (className != null && !className.isBlank()) classList.addAll(Arrays.asList(className.split(",")));
        if (subclass != null && !subclass.isBlank()) classList.addAll(Arrays.asList(subclass.split(",")));
        return spellRepository.searchSpells(
                name != null && name.isBlank() ? null : name,
                levelList.size(), levelList.isEmpty() ? List.of(-1) : levelList,
                schoolList.size(), schoolList.isEmpty() ? List.of("") : schoolList,
                sourceList.size(), sourceList.isEmpty() ? List.of("") : sourceList,
                classList.size(), classList.isEmpty() ? List.of("") : classList,
                concentration, ritual, pageable);
    }

    @GetMapping("/spells/{id}")
    public ResponseEntity<Spell> getSpell(@PathVariable UUID id) {
        return spellRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Cacheable("spellSchools")
    @GetMapping("/spells/filters/schools")
    public List<String> getSpellSchools() {
        return spellRepository.findDistinctSchools();
    }

    @Cacheable("spellSources")
    @GetMapping("/spells/filters/sources")
    public List<String> getSpellSources() {
        return spellRepository.findDistinctSources();
    }

    @Cacheable("spellClasses")
    @GetMapping("/spells/filters/classes")
    public List<String> getSpellClasses() {
        return spellRepository.findDistinctClasses();
    }

    @GetMapping("/spells/filters/subclasses")
    public List<String> getSpellSubclasses(@RequestParam String className) {
        return spellRepository.findDistinctSubclasses(className);
    }

    @Cacheable("conditions")
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
        List<String> typeList = splitFilter(type);
        List<String> rarityList = splitFilter(rarity);
        List<String> sourceList = splitFilter(source);
        return itemRepository.searchItems(
                name != null && name.isBlank() ? null : name,
                typeList.size(), typeList.isEmpty() ? List.of("") : typeList,
                rarityList.size(), rarityList.isEmpty() ? List.of("") : rarityList,
                sourceList.size(), sourceList.isEmpty() ? List.of("") : sourceList,
                pageable);
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<Item> getItem(@PathVariable UUID id) {
        return itemRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Cacheable("itemTypes")
    @GetMapping("/items/filters/types")
    public List<String> getItemTypes() {
        return itemRepository.findDistinctTypes();
    }

    @Cacheable("itemRarities")
    @GetMapping("/items/filters/rarities")
    public List<String> getItemRarities() {
        return itemRepository.findDistinctRarities();
    }

    @Cacheable("itemSources")
    @GetMapping("/items/filters/sources")
    public List<String> getItemSources() {
        return itemRepository.findDistinctSources();
    }

    private List<String> splitFilter(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.asList(value.split(","));
    }

    @Cacheable(value = "races", key = "#source != null ? #source : 'all'")
    @GetMapping("/races")
    public List<Race> getAllRaces(@RequestParam(required = false) String source) {
        if (source != null && !source.isBlank()) {
            return raceRepository.findBySourceOrderByNameAsc(source);
        }
        return raceRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/races/{id}")
    public ResponseEntity<Race> getRace(@PathVariable UUID id) {
        return raceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Cacheable("raceSources")
    @GetMapping("/races/filters/sources")
    public List<String> getRaceSources() {
        return raceRepository.findDistinctSources();
    }

    @Cacheable("classes")
    @GetMapping("/classes")
    public List<CharacterClass> getAllClasses() {
        return characterClassRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/classes/{id}")
    public ResponseEntity<CharacterClass> getCharacterClass(@PathVariable UUID id) {
        return characterClassRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Cacheable(value = "subclasses", key = "#id")
    @GetMapping("/classes/{id}/subclasses")
    public List<Subclass> getSubclassesForClass(@PathVariable UUID id) {
        return subclassRepository.findByCharacterClassIdOrderByNameAsc(id);
    }

    @Cacheable("backgrounds")
    @GetMapping("/backgrounds")
    public List<Background> getAllBackgrounds() {
        return backgroundRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/backgrounds/{id}")
    public ResponseEntity<Background> getBackground(@PathVariable UUID id) {
        return backgroundRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Cacheable("feats")
    @GetMapping("/feats")
    public List<Feat> getAllFeats() {
        return featRepository.findAllByOrderByNameAsc();
    }

    @GetMapping("/feats/{id}")
    public ResponseEntity<Feat> getFeat(@PathVariable UUID id) {
        return featRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/optional-features")
    public List<OptionalFeature> getOptionalFeatures(
            @RequestParam(required = false) String type) {
        if (type != null && !type.isBlank()) {
            return optionalFeatureRepository.findByFeatureTypeOrderByNameAsc(type);
        }
        return optionalFeatureRepository.findAll();
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
