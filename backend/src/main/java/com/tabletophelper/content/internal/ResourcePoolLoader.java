package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.MonsterPoolTrigger;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the M24.5 resource pool reference tables from resource-pools.json (D169): the pool definitions, by pool ID;
 * the pools each class and subclass grants, by class, subclass and pool; and the monster pool triggers, by pool and
 * condition. A class or subclass the classes loader didn't write is logged and its pools skipped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResourcePoolLoader implements ContentLoader {

    static final String FILE = "data/content/resource-pools.json";

    private final ResourcePoolDefinitionRepository poolDefRepo;
    private final ClassFeaturePoolRepository classFeaturePoolRepo;
    private final MonsterPoolTriggerRepository monsterTriggerRepo;
    private final CharacterClassRepository classRepo;
    private final SubclassRepository subclassRepo;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "resource-pools";
    }

    @Override
    public List<String> files() {
        return List.of(FILE);
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public List<String> dependsOn() {
        return List.of("classes");
    }

    @Override
    public void load(LoadContext context) throws Exception {
        JsonNode root;
        try (InputStream is = new ClassPathResource(FILE).getInputStream()) {
            root = objectMapper.readTree(is);
        }
        Map<String, ResourcePoolDefinition> definitions = context.upsert("resource_pool_definitions", poolDefRepo,
                definitions(root.path("definitions")), ResourcePoolDefinition::getPoolId);
        context.upsert("class_feature_pools", classFeaturePoolRepo,
                classPools(root.path("classPools"), definitions), ResourcePoolLoader::classPoolKey);
        context.upsert("monster_pool_triggers", monsterTriggerRepo,
                monsterTriggers(root.path("monsterTriggers"), definitions), trigger -> triggerKey(context, trigger));
    }

    // ── Pool definitions ─────────────────────────────────────────

    private List<ResourcePoolDefinition> definitions(JsonNode entries) throws Exception {
        List<ResourcePoolDefinition> defs = new ArrayList<>();
        for (JsonNode entry : entries) {
            JsonNode metadata = entry.path("metadata");
            defs.add(ResourcePoolDefinition.builder()
                    .poolId(required(entry, "poolId"))
                    .displayName(required(entry, "displayName"))
                    .sourceType(required(entry, "sourceType"))
                    .maxUsesFormula(text(entry, "maxUsesFormula"))
                    .resetOn(required(entry, "resetOn"))
                    .resetAmount(text(entry, "resetAmount"))
                    .resetCheck(text(entry, "resetCheck"))
                    .spendActionType(text(entry, "spendActionType"))
                    .description(text(entry, "description"))
                    .icon(text(entry, "icon"))
                    .metadata(metadata.isObject() ? objectMapper.writeValueAsString(metadata) : null)
                    .build());
        }
        return defs;
    }

    // ── Class feature pools (junction table) ──────────────────────

    private List<ClassFeaturePool> classPools(JsonNode entries, Map<String, ResourcePoolDefinition> definitions) {
        Map<String, CharacterClass> classes = new HashMap<>();
        for (CharacterClass cc : classRepo.findAll()) {
            classes.put(LoadContext.nameAndSource(cc.getName(), cc.getSource()), cc);
        }
        Map<String, Subclass> subclasses = new HashMap<>();
        for (Subclass sc : subclassRepo.findAll()) {
            subclasses.put(CharacterClassLoader.subclassKey(sc), sc);
        }

        List<ClassFeaturePool> mappings = new ArrayList<>();
        for (JsonNode entry : entries) {
            String poolId = required(entry, "poolId");
            String classKey = LoadContext.nameAndSource(required(entry, "class"), text(entry, "classSource"));
            CharacterClass cc = classes.get(classKey);
            if (cc == null) {
                log.warn("Class '{}' not found in database, skipping pool mapping for '{}'", classKey, poolId);
                continue;
            }
            Subclass sc = null;
            String subclassName = text(entry, "subclass");
            if (subclassName != null) {
                String subclassKey = classKey + "|" + LoadContext.nameAndSource(subclassName,
                        text(entry, "subclassSource"));
                sc = subclasses.get(subclassKey);
                if (sc == null) {
                    log.warn("Subclass '{}' not found in database, skipping pool mapping for '{}'", subclassKey, poolId);
                    continue;
                }
            }
            ResourcePoolDefinition def = definitions.get(poolId);
            if (def == null) {
                throw new IllegalStateException(FILE + " maps " + classKey + " to the pool " + poolId
                        + ", which it doesn't define");
            }
            mappings.add(ClassFeaturePool.builder()
                    .characterClass(cc)
                    .subclass(sc)
                    .minLevel(entry.path("minLevel").asInt())
                    .poolDefinition(def)
                    .build());
        }
        return mappings;
    }

    /** A class pool's natural key: its class, its subclass if it has one, and its pool. */
    static String classPoolKey(ClassFeaturePool pool) {
        CharacterClass cc = pool.getCharacterClass();
        Subclass sc = pool.getSubclass();
        return LoadContext.nameAndSource(cc.getName(), cc.getSource()) + "|"
                + (sc != null ? LoadContext.nameAndSource(sc.getName(), sc.getSource()) : "") + "|"
                + pool.getPoolDefinition().getPoolId();
    }

    // ── Monster pool triggers ─────────────────────────────────────

    private List<MonsterPoolTrigger> monsterTriggers(JsonNode entries, Map<String, ResourcePoolDefinition> definitions)
            throws Exception {
        List<MonsterPoolTrigger> triggers = new ArrayList<>();
        for (JsonNode entry : entries) {
            String poolId = required(entry, "poolId");
            ResourcePoolDefinition def = definitions.get(poolId);
            if (def == null) {
                throw new IllegalStateException(FILE + " has a monster trigger for the pool " + poolId
                        + ", which it doesn't define");
            }
            triggers.add(MonsterPoolTrigger.builder()
                    .poolDefinition(def)
                    .triggerCondition(objectMapper.writeValueAsString(entry.path("triggerCondition")))
                    .priority(entry.path("priority").asInt(0))
                    .build());
        }
        return triggers;
    }

    // A trigger's natural key: its pool and its condition's JSON with the keys sorted
    private static String triggerKey(LoadContext context, MonsterPoolTrigger trigger) {
        return trigger.getPoolDefinition().getPoolId() + "|" + context.canonicalJson(trigger.getTriggerCondition());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private static String required(JsonNode entry, String field) {
        String value = text(entry, field);
        if (value == null) {
            throw new IllegalStateException(FILE + " has an entry without " + field + ": " + entry);
        }
        return value;
    }
}
