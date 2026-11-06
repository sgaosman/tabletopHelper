package com.tabletophelper.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.dto.ClassTablesResponse;
import com.tabletophelper.reference.dto.SpellTargetingResponse;
import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reference")
@RequiredArgsConstructor
@Slf4j
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
    private final ClassTables classTables;

    private static final Duration CLASS_TABLES_MAX_AGE = Duration.ofHours(1);
    private static final String QUICK_REFERENCE = "data/5etools/generated/bookref-quick.json";

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
        List<Integer> levelList = parseLevels(level);
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

    @GetMapping("/spells/targeting")
    public ResponseEntity<SpellTargetingResponse> getSpellTargeting(
            @RequestParam String name,
            @RequestParam(defaultValue = "0") int slotLevel) {
        var spellOpt = spellRepository.findByNameIgnoreCase(name);
        if (spellOpt.isEmpty() || spellOpt.get().getEffectTemplate() == null) {
            return ResponseEntity.notFound().build();
        }

        // A template that isn't an object holds no targeting rules, as one that doesn't fit the records can't (D155)
        SpellTemplate template = spellOpt.get().getEffectTemplate()
                .readable("The targeting rules of " + spellOpt.get().getName())
                .orElseThrow(() -> new ConflictException("The targeting rules of " + spellOpt.get().getName()
                        + " can't be read"));

        int spellLevel = template.level();
        Integer baseTargetCount = template.targetCount();

        int maxTargets = baseTargetCount != null ? baseTargetCount : Integer.MAX_VALUE;
        if (baseTargetCount != null && slotLevel > spellLevel) {
            SpellTemplate.TargetScaling upcastScaling = template.targetCountUpcastScaling();
            if (upcastScaling != null && upcastScaling.additionalTargetsPerLevel() != null) {
                maxTargets += upcastScaling.additionalTargetsPerLevel() * (slotLevel - spellLevel);
            }
        }

        boolean selfOnly = Boolean.TRUE.equals(template.selfOnly());
        boolean canTargetSelf = Boolean.TRUE.equals(template.canTargetSelf()) || selfOnly;
        boolean canTargetAllies = !Boolean.FALSE.equals(template.canTargetAllies());
        boolean canTargetEnemies = !Boolean.FALSE.equals(template.canTargetEnemies());
        String targetType = template.targetType();
        boolean hasRepeatEffect = template.repeatEffect() != null;

        return ResponseEntity.ok(new SpellTargetingResponse(
                baseTargetCount != null ? maxTargets : -1,
                selfOnly,
                canTargetSelf,
                canTargetAllies,
                canTargetEnemies,
                targetType != null ? targetType : "SINGLE_TARGET",
                hasRepeatEffect));
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

    private List<Integer> parseLevels(String level) {
        if (level == null || level.isBlank()) return List.of();
        try {
            return Arrays.stream(level.split(",")).map(Integer::parseInt).toList();
        } catch (NumberFormatException e) {
            throw new ValidationException("Invalid value for level: " + level);
        }
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

    /**
     * The class tables the character rules use (D167, D169): spell slots, cantrips and spells known, and the
     * subclass, ASI, Expertise and Fighting Style levels. They change only with a release, so the browser may keep
     * them for an hour; they are read once at startup, so need no server cache.
     */
    @GetMapping("/class-tables")
    public ResponseEntity<ClassTablesResponse> getClassTables() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CLASS_TABLES_MAX_AGE).cachePrivate())
                .body(ClassTablesResponse.of(classTables));
    }

    @GetMapping("/quickref")
    public ResponseEntity<JsonNode> getQuickReference() {
        try (InputStream in = new ClassPathResource(QUICK_REFERENCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            return ResponseEntity.ok(root.path("data").path("bookref-quick"));
        } catch (IOException e) {
            // A bundled file that is missing or isn't JSON is a fault in the content (D155)
            log.error("{} can't be read: {}", QUICK_REFERENCE, e.getMessage());
            throw new ConflictException("The quick reference can't be read");
        }
    }
}
